(ns hive-mcp.tools.memory.crud.write
  "Write operations for memory: add entry with KG edge creation."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.tools.memory.classify :as classify]
            [hive-mcp.tools.memory.gaps :as gaps]
            [hive-mcp.tools.core :refer [mcp-json mcp-error coerce-vec!]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.plan.plans :as plans]
            [hive-mcp.plan.gate :as plan-gate]
            [clojure.edn :as edn]
            [hive-mcp.knowledge-graph.connection :as kg-conn]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.schema :as kg-schema]
            [hive-mcp.concurrency.pool :as pool]
            [hive-weave.pool :as wpool]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.crystal.recall :as recall]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]
            [hive-mcp.memory.type-registry :as type-registry]
            [hive-weave.core :as weave]))

(def ^:const ^:private memory-write-timeout-ms
  "Timeout budget for a single memory write (Chroma add + KG tx + fetch).
   On timeout the write may still land later; caller gets an error."
  30000)
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- find-target-store
  "Probe each registered IMemoryStore for `target-id`. Returns the
   first store whose `get-entry` returns a non-nil record, paired
   with the entry itself so callers don't pay a second read.

   Cross-slot routing: a `:default` (milvus) entry that depends-on a
   target living in `:kanban` (qdrant-local) needs its back-edge
   metadata write routed to the qdrant slot. Probing the registry
   resolves the target's actual slot regardless of which slot the
   source entry was written to.

   :default is probed first when present so the common case (axioms,
   principles, conventions all in :default) costs exactly one read.
   Returns `[slot store entry]` on hit, `nil` on miss."
  [target-id]
  (let [registry (mem-proto/registered-stores)
        ordered (concat (when-let [s (:default registry)] [[:default s]])
                        (filter (fn [[k _]] (not= k :default)) registry))]
    (some (fn [[slot store]]
            (when-let [entry (with-resilience (mem-proto/get-entry store target-id))]
              [slot store entry]))
          ordered)))

(defn- update-target-incoming!
  "Append edge-id to target entry's kg-incoming field, preserving the
   existing embedding.

   Cross-slot routing: probes `(registered-stores)` to locate the slot
   that actually owns `target-id`. Without this, a back-edge from a
   :default entry to a target living in :kanban (or vice-versa) would
   read+write the wrong slot — the read returns nil, the write is a
   silent no-op, and the kg-incoming bookkeeping drifts.

   Uses the IMemoryStoreMetadataWrite protocol (`update-metadata!`)
   when the resolved store satisfies it — the no-embed fast path. Falls
   back to the slow `update-entry!` for stores that don't implement
   metadata writes (re-embeds on every field change).

   Each store probe + the final write are wrapped in `with-resilience`
   so KG back-edge bookkeeping survives a transient transport drop."
  [target-id edge-id]
  (when-let [[_slot store target-entry] (find-target-store target-id)]
    (let [existing-incoming (or (:kg-incoming target-entry) [])
          updated-incoming  (conj existing-incoming edge-id)
          updates           {:kg-incoming updated-incoming}]
      (with-resilience
        (if (mem-proto/metadata-write-store? store)
          (mem-proto/update-metadata! store target-id updates)
          (mem-proto/update-entry!    store target-id updates))))))

(defn- create-kg-edges!
  "Create KG edges for the given relationships and update target entries.

   Two-phase to honour the idempotence law for compound writes
   (add(c, refs=[r1...rN]) ≡ add(c) ; for r in refs: edge(r)):

     Phase 1 — KG transact (synchronous, batched). All `kg-edges/add-edge!`
       calls coalesce into a single Datahike tx via `kg-conn/with-tx-batch`.
       Fast (in-process Datahike) so sequential is fine.

     Phase 2 — Milvus back-edge writes (fan-out via hive-weave). The
       :kg-incoming bookkeeping on each target is one Milvus read + one
       upsert; no embed cost (see IMemoryStoreMetadataWrite). Running
       these in parallel under `weave/fork-join` collapses wall-clock
       latency from sum(per-target) → max(per-target), keeping the
       compound add safely under `memory-write-timeout-ms`."
  [entry-id {:keys [kg_implements kg_supersedes kg_depends_on kg_refines]} project-id agent-id]
  (let [created-by   (when agent-id (str "agent:" agent-id))
        edge-records
        (kg-conn/with-tx-batch
          (let [add-edges (fn [targets relation]
                            (when (seq targets)
                              (mapv (fn [target-id]
                                      (let [edge-id (kg-edges/add-edge!
                                                      {:from       entry-id
                                                       :to         target-id
                                                       :relation   relation
                                                       :scope      project-id
                                                       :confidence 1.0
                                                       :created-by created-by})]
                                        {:target-id target-id :edge-id edge-id}))
                                    targets)))]
            (vec (concat
                   (add-edges kg_implements :implements)
                   (add-edges kg_supersedes :supersedes)
                   (add-edges kg_depends_on :depends-on)
                   (add-edges kg_refines    :refines)))))
        tasks
        (into [] (map-indexed
                   (fn [i {:keys [target-id edge-id]}]
                     [(keyword (str "back-edge-" i))
                      #(update-target-incoming! target-id edge-id)
                      nil])
                   edge-records))]
    (when (seq tasks)
      (apply weave/fork-join {:budget-ms 25000} tasks))
    (mapv :edge-id edge-records)))

(defn- build-entry-tags
  "Build complete tags vector: base, agent, KG markers, and scope."
  [tags-vec agent-id kg-vecs project-id]
  (let [agent-tag (when agent-id (str "agent:" agent-id))
        tags-with-agent (if agent-tag (conj tags-vec agent-tag) tags-vec)
        {:keys [kg-implements-vec kg-supersedes-vec kg-depends-on-vec kg-refines-vec]} kg-vecs
        kg-tags (cond-> []
                  (seq kg-implements-vec) (conj "kg:has-implements")
                  (seq kg-supersedes-vec) (conj "kg:has-supersedes")
                  (seq kg-depends-on-vec) (conj "kg:has-depends-on")
                  (seq kg-refines-vec) (conj "kg:has-refines"))
        tags-with-kg (into tags-with-agent kg-tags)]
    (scope/inject-project-scope tags-with-kg project-id)))

(defn- validate-plan-gate!
  "Validate plan content before storage via FSM gate.

   Strategy: delegate the entire decision to `plan-gate/validate-for-storage`,
   which runs parse + schema + deps + cycles and returns a structured
   `{:valid? false :phase ... :hint ...}` for any failure mode (parse,
   schema, dependencies, cycles, empty-steps). Surfacing that result
   directly via `format-gate-error` gives lings actionable messages —
   e.g. an EDN plan with a list-shaped `:steps` or a missing :title now
   sees the FSM-emitted hint, not a generic 'doesn't look like a plan'
   reject.

   Removed: the prior `plan-content?` pre-check. It was redundant with
   the gate (which already covers EDN + markdown detection) and its
   failure path threw a generic message that hid the real parse error
   — the surface area of the EDN/markdown contract bug
   (kanban 20260429185655-1cfb9277)."
  [content]
  (let [gate-result (plan-gate/validate-for-storage content)]
    (when-not (:valid? gate-result)
      (throw (ex-info (plan-gate/format-gate-error gate-result)
                      {:type :plan-gate-rejected
                       :phase (:phase gate-result)
                       :errors (:errors gate-result)})))))

(defn- resolve-role-card-sym
  "Resolve a `hive-spi.role.card` var, or nil when the role SPI leaf is not
   on the classpath. Keeps this ns loadable without a hard compile-time dep."
  [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defonce ^:private role-card-validator (atom nil))

(defn set-role-card-validator!
  "Inject the RoleCard validator as {:valid? fn :explain fn}.

   nil restores resolution from the hive-spi role leaf. Returns the value set."
  [v]
  (reset! role-card-validator v))

(defn current-role-card-validator
  "The injected validator, else one resolved from hive-spi.role.card, else nil."
  []
  (or @role-card-validator
      (when-let [valid? (resolve-role-card-sym (quote hive-spi.role.card/valid?))]
        {:valid? valid?
         :explain (or (resolve-role-card-sym (quote hive-spi.role.card/explain))
                      (constantly nil))})))

(defn- validate-role-gate!
  "Validate :role content against the RoleCard schema before storage.

   Resolves the validator via `current-role-card-validator` — injected first,
   else the hive-spi role leaf, else nil (gate skipped). FAIL-LOUD otherwise:
   throws `:role-gate-rejected` when the content is unreadable EDN, not a map,
   or a non-conformant RoleCard."
  [content]
  (when-let [{:keys [valid? explain]} (current-role-card-validator)]
    (let [card (try
                 (edn/read-string content)
                 (catch Exception e
                   (throw (ex-info (str "RoleCard content is not readable EDN: "
                                        (.getMessage e))
                                   {:type :role-gate-rejected}))))]
      (when-not (and (map? card) (valid? card))
        (throw (ex-info (str "RoleCard validation failed. Required: :role/id "
                             "(keyword) and :role/name (string). Explanation: "
                             (pr-str (explain card)))
                        {:type :role-gate-rejected
                         :explanation (explain card)}))))))

(defn- index-entry!
  "Index entry through IMemoryStore. Plan-type entries get enriched with
   plan-specific metadata (`:plan-status`, `:steps-count`) but are
   stored via the same `add-entry!` path as every other type — there
   is no separate plans collection.

   `:store-key` selects the multi-store registry slot. Defaults to
   `:default` (legacy). Kanban writes thread `:kanban` through so the
   entry lands in the dedicated qdrant collection instead of milvus.

   Wraps the store add-entry! in `with-resilience` so a dropped
   HTTP transport (selector-manager-closed IOException surfaced as
   ExecutionException) kicks the heal loop and retries once before
   surfacing the failure to the caller."
  [{:keys [type content tags-with-scope content-hash duration-str
           expires project-id abstraction-level knowledge-gaps store-key]
    :or   {store-key :default}}]
  (let [base-entry {:type type :content content :tags tags-with-scope
                    :content-hash content-hash :duration duration-str
                    :expires (or expires "") :project-id project-id
                    :abstraction-level abstraction-level
                    :knowledge-gaps knowledge-gaps}
        entry (if (= type "plan")
                (cond-> (assoc base-entry :plan-status "draft")
                  (plans/count-plan-steps content)
                  (assoc :steps-count (plans/count-plan-steps content)))
                base-entry)]
    (with-resilience
      (mem-proto/add-entry! (mem-proto/get-store store-key) entry))))

(def ^:private ^:const read-after-write-attempts 6)
(def ^:private ^:const read-after-write-base-ms 40)

(defn- fetch-with-retry
  "Read an entry by id with bounded exponential-ish backoff. Addresses
   read-after-write consistency lag on some IMemoryStore backends
   (e.g. Milvus's HTTP transport where `:consistency-level :strong` on
   get doesn't always surface the just-written row on the first poll).

   Attempts: `read-after-write-attempts`, waits grow linearly from
   `read-after-write-base-ms`. Total worst-case budget ≈ 40+80+120+160+200
   = 600 ms, which stays well under the 30 s memory-write-timeout-ms
   and is invisible to callers when the first read already succeeds.

   Each store read is wrapped in `with-resilience` so a transport drop
   during the read-after-write window triggers a heal-and-retry. All
   types — plans included — go through the same IMemoryStore path."
  [store entry-id]
  (loop [attempt 1]
    (let [fetched (with-resilience (mem-proto/get-entry store entry-id))]
      (cond
        (some? fetched) fetched
        (>= attempt read-after-write-attempts) nil
        :else (do (Thread/sleep (* attempt read-after-write-base-ms))
                  (recur (inc attempt)))))))

(defn- finalize-entry!
  "Wire KG edges, fetch created entry, notify channel, and format response.

   `:store-key` (passed via `entry-ctx`) routes the read-after-write +
   `:kg-outgoing` link write to the same slot the entry was indexed in.
   Defaults to `:default` (legacy / milvus). Kanban writes thread
   `:kanban` so the back-link update lands in the same qdrant
   collection that `index-entry!` wrote to.

   Note: `create-kg-edges!` does back-edge bookkeeping on entries that
   may live in *other* slots (e.g. an axiom in :default that a kanban
   entry depends-on). That cross-slot scan is a known soft-edge for
   the cutover window — see plan section D.5. KG edges themselves
   live in Datahike, unaffected by the IMemoryStore slot.

   The KG outgoing-edge update is wrapped in `with-resilience` so a
   transient Milvus transport drop during the edge-link write kicks
   the heal loop instead of poisoning KG edges. All types — plans
   included — flow through the single IMemoryStore."
  [entry-id kg-params project-id agent-id
   {:keys [tags-with-scope type knowledge-gaps store-key]
    :or   {store-key :default}}]
  (let [store (mem-proto/get-store store-key)
        edge-ids (create-kg-edges! entry-id kg-params project-id agent-id)
        _ (when (seq edge-ids)
            (with-resilience
              (mem-proto/update-entry! store entry-id {:kg-outgoing edge-ids})))
        created (fetch-with-retry store entry-id)]
    (when-not created
      (log/error "Failed to retrieve entry after indexing:" entry-id))
    (log/info "Created memory entry:" entry-id
              (when (seq edge-ids) (str " with " (count edge-ids) " KG edges"))
              (when (seq knowledge-gaps) (str " gaps:" (count knowledge-gaps))))
    (try
      (when-let [publish-fn (requiring-resolve 'hive-mcp.channel.core/publish!)]
        (publish-fn {:type :memory-added :id entry-id :memory-type type
                     :tags tags-with-scope :project-id project-id}))
      (catch Exception e (log/debug "[memory] Channel publish failed for entry" entry-id (.getMessage e))))
    (if created
      ;; The gate notice is DERIVED from the entry's own tags, so the response
      ;; cannot disagree with what was stored.
      (let [requested (type-registry/requested-type-of tags-with-scope)]
        (mcp-json (cond-> (fmt/entry->json-alist created)
                    (seq edge-ids) (assoc :kg_edges_created edge-ids)
                    requested
                    (assoc :queued_for_review
                           {:requested_type requested
                            :parked_as      type
                            :reason         (:reason (type-registry/gate-of requested))}))))
      (mcp-error (str "Entry indexed as " entry-id " but retrieval failed. Check memory store connectivity.")))))

(defn- do-add!
  "Inner core of handle-add. Runs the memory-store/KG IO path through
   the IMemoryStore registered under `:store-key` (default `:default`).

   `:store-key` propagates to dedup find/update, primary index, and
   finalize. Kanban callers thread `:kanban` (or `:dual-read`-resolved)
   to land in the dedicated qdrant collection without disturbing the
   generic memory-add surface used by axiom/principle/snippet writes.

   Both the duplicate-detection read and the duplicate-tag-merge write
   are wrapped in `with-resilience`: if the Milvus HTTP transport drops
   between the find-duplicate call and the dedup update, the heal loop
   fires and the call retries once — otherwise transient failures here
   would leak through `with-store`'s generic try/catch as errors."
  [{:keys [type content tags duration directory agent_id
           kg_implements kg_supersedes kg_depends_on kg_refines abstraction_level
           knowledge_gaps store-key]
    :or   {store-key :default}}]
  (let [tags-vec (coerce-vec! tags :tags [])
        kg-vecs {:kg-implements-vec (coerce-vec! kg_implements :kg_implements [])
                 :kg-supersedes-vec (coerce-vec! kg_supersedes :kg_supersedes [])
                 :kg-depends-on-vec (coerce-vec! kg_depends_on :kg_depends_on [])
                 :kg-refines-vec    (coerce-vec! kg_refines :kg_refines [])}
        directory (or directory (ctx/current-directory))
        abstraction-level (or abstraction_level
                              (classify/classify-abstraction-level type content tags-vec))
        ;; Callers that write structured/serialized content (e.g. kanban JSON)
        ;; pass an explicit :knowledge_gaps to bypass regex gap-extraction,
        ;; which otherwise scrapes literals like "status":"todo" out of the
        ;; JSON. An explicit vector (incl. empty) wins; absent/nil auto-extracts.
        knowledge-gaps (or knowledge_gaps (gaps/extract-knowledge-gaps content))]
    (log/info "mcp-memory-add:" type "directory:" directory "agent_id:" agent_id
              "store-key:" store-key)
    (with-store
      (let [project-id (scope/get-current-project-id directory)
            agent-id (or agent_id (ctx/current-agent-id)
                         (System/getenv "CLAUDE_SWARM_SLAVE_ID"))
            tags-with-scope (build-entry-tags tags-vec agent-id kg-vecs project-id)
            store (mem-proto/get-store store-key)
            content-hash (mem-proto/content-hash content)
            duration-str (or duration "long")
            expires (dur/calculate-expires duration-str)
            existing (with-resilience
                       (mem-proto/find-duplicate store type content-hash {:project-id project-id}))]
        (if existing
          (let [merged-tags (distinct (concat (:tags existing) tags-with-scope))
                updated (with-resilience
                          (mem-proto/update-entry! store (:id existing) {:tags merged-tags}))]
            (log/info "Duplicate found, merged tags:" (:id existing))
            (mcp-json (fmt/entry->json-alist updated)))
          (let [_ (when (= type "plan") (validate-plan-gate! content))
                _ (when (= type "role") (validate-role-gate! content))
                entry-ctx {:type type :content content :tags-with-scope tags-with-scope
                           :content-hash content-hash :duration-str duration-str
                           :expires expires :project-id project-id
                           :abstraction-level abstraction-level
                           :knowledge-gaps knowledge-gaps :agent-id agent-id
                           :store-key store-key}
                raw-id (index-entry! entry-ctx)]
            ;; Contract guard: IMemoryStore/add-entry! must return a non-blank
            ;; id string. Some backends (e.g. Milvus under circuit-breaker
            ;; fail-soft) return a {:success? false ...} failure map instead,
            ;; which used to silently poison KG edges and surface as the
            ;; misleading "Entry indexed as {...} but retrieval failed" error.
            ;; Reject map / nil / blank returns up front with an actionable
            ;; message so the store's real failure reason is visible.
            (if-not (and (string? raw-id) (not (str/blank? raw-id)))
              (mcp-error (str "Memory store add-entry! did not return an id "
                              "(got " (pr-str raw-id) "). "
                              "Backend is likely degraded or offline — "
                              "check store health and retry."))
              (let [entry-id raw-id
                    _ (recall/register-created-id! entry-id project-id type)
                    kg-params {:kg_implements (:kg-implements-vec kg-vecs)
                               :kg_supersedes (:kg-supersedes-vec kg-vecs)
                               :kg_depends_on (:kg-depends-on-vec kg-vecs)
                               :kg_refines    (:kg-refines-vec kg-vecs)}]
                (finalize-entry! entry-id kg-params project-id agent-id entry-ctx)))))))))

(defn handle-add
  "Add an entry to project memory with optional KG edge creation.
   Runs the IO-heavy core on the dedicated memory-pool so slow Chroma
   or KG calls cannot saturate the shared io-pool."
  [{:keys [type abstraction_level] :as args}]
  (try
    (cond
      (not (type-registry/valid-type? type))
      (mcp-error (str "Invalid memory type: " (pr-str type)
                      ". Type must be a safe token — letters, digits, '_' or '-', "
                      "starting with a letter, max " type-registry/max-type-length
                      " chars (e.g. axiom, decision, pattern, my-custom-type)."))

      (and abstraction_level (not (kg-schema/valid-abstraction-level? abstraction_level)))
      (mcp-error (str "Invalid abstraction_level: " abstraction_level))

      :else
      (let [;; Write gate: a request for a human-gated type (:axiom) is PARKED
            ;; as the gate's queue type and tagged pending, never honoured
            ;; outright and never dropped. Ungated types pass through untouched.
            {eff-type :type :keys [queued? gate requested]}
            (type-registry/resolve-write-type type)

            args (cond-> args
                   queued? (update :tags type-registry/queued-tags gate requested))

            ;; Canonicalize + auto-register the (possibly novel) type with sane
            ;; defaults, so unknown-but-safe types persist and stay consistent.
            args (assoc args :type (type-registry/ensure-type! eff-type))
            timeout-sentinel ::timeout
            result (wpool/await!
                    (pool/memory-pool)
                    #(try (do-add! args)
                          (catch Throwable t {::caught t}))
                    {:timeout-ms memory-write-timeout-ms
                     :fallback   timeout-sentinel
                     :name       "memory-add"})]
        (cond
          (= result timeout-sentinel)
          (mcp-error (str "memory add timed out after " memory-write-timeout-ms "ms"))

          (and (map? result) (contains? result ::caught))
          (throw (::caught result))

          :else result)))
    (catch clojure.lang.ExceptionInfo e
      (if (#{:coercion-error :embedding-too-long :plan-gate-rejected :role-gate-rejected} (:type (ex-data e)))
        (mcp-error (.getMessage e))
        (throw e)))))