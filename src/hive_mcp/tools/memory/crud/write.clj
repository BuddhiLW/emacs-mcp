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
            [hive-mcp.knowledge-graph.connection :as kg-conn]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.schema :as kg-schema]
            [hive-mcp.concurrency.pool :as pool]
            [hive-weave.pool :as wpool]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.crystal.recall :as recall]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))

(def ^:const ^:private memory-write-timeout-ms
  "Timeout budget for a single memory write (Chroma add + KG tx + fetch).
   On timeout the write may still land later; caller gets an error."
  30000)
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- update-target-incoming!
  "Append edge-id to target entry's kg-incoming field.
   Wraps the store read+write pair in `with-resilience` so the KG
   back-edge bookkeeping survives a transient Milvus transport drop."
  [target-id edge-id]
  (let [store (mem-proto/get-store)]
    (when-let [target-entry (with-resilience (mem-proto/get-entry store target-id))]
      (let [existing-incoming (or (:kg-incoming target-entry) [])
            updated-incoming (conj existing-incoming edge-id)]
        (with-resilience
          (mem-proto/update-entry! store target-id {:kg-incoming updated-incoming}))))))

(defn- create-kg-edges!
  "Create KG edges for the given relationships and update target entries.
   Uses conn/with-tx-batch to coalesce all edge transact! calls."
  [entry-id {:keys [kg_implements kg_supersedes kg_depends_on kg_refines]} project-id agent-id]
  (kg-conn/with-tx-batch
    (let [created-by (when agent-id (str "agent:" agent-id))
          create-edges (fn [targets relation]
                         (when (seq targets)
                           (mapv (fn [target-id]
                                   (let [edge-id (kg-edges/add-edge!
                                                  {:from entry-id
                                                   :to target-id
                                                   :relation relation
                                                   :scope project-id
                                                   :confidence 1.0
                                                   :created-by created-by})]
                                     (update-target-incoming! target-id edge-id)
                                     edge-id))
                                 targets)))]
      (vec (concat
            (create-edges kg_implements :implements)
            (create-edges kg_supersedes :supersedes)
            (create-edges kg_depends_on :depends-on)
            (create-edges kg_refines :refines))))))

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

(defn- index-entry!
  "Index entry through IMemoryStore. Plan-type entries get enriched with
   plan-specific metadata (`:plan-status`, `:steps-count`) but are
   stored via the same `add-entry!` path as every other type — there
   is no separate plans collection.

   Wraps the store add-entry! in `with-resilience` so a dropped
   HTTP transport (selector-manager-closed IOException surfaced as
   ExecutionException) kicks the heal loop and retries once before
   surfacing the failure to the caller."
  [{:keys [type content tags-with-scope content-hash duration-str
           expires project-id abstraction-level knowledge-gaps]}]
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
      (mem-proto/add-entry! (mem-proto/get-store) entry))))

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

   The KG outgoing-edge update is wrapped in `with-resilience` so a
   transient Milvus transport drop during the edge-link write kicks
   the heal loop instead of poisoning KG edges. All types — plans
   included — flow through the single IMemoryStore."
  [entry-id kg-params project-id agent-id
   {:keys [tags-with-scope type knowledge-gaps]}]
  (let [store (mem-proto/get-store)
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
      (mcp-json (cond-> (fmt/entry->json-alist created)
                  (seq edge-ids) (assoc :kg_edges_created edge-ids)))
      (mcp-error (str "Entry indexed as " entry-id " but retrieval failed. Check memory store connectivity.")))))

(defn- do-add!
  "Inner core of handle-add. Runs the memory-store/KG IO path through
   the single IMemoryStore — no per-type collection branching.
   Intended to be submitted to the memory pool for isolation.

   Both the duplicate-detection read and the duplicate-tag-merge write
   are wrapped in `with-resilience`: if the Milvus HTTP transport drops
   between the find-duplicate call and the dedup update, the heal loop
   fires and the call retries once — otherwise transient failures here
   would leak through `with-store`'s generic try/catch as errors."
  [{:keys [type content tags duration directory agent_id
           kg_implements kg_supersedes kg_depends_on kg_refines abstraction_level]}]
  (let [tags-vec (coerce-vec! tags :tags [])
        kg-vecs {:kg-implements-vec (coerce-vec! kg_implements :kg_implements [])
                 :kg-supersedes-vec (coerce-vec! kg_supersedes :kg_supersedes [])
                 :kg-depends-on-vec (coerce-vec! kg_depends_on :kg_depends_on [])
                 :kg-refines-vec    (coerce-vec! kg_refines :kg_refines [])}
        directory (or directory (ctx/current-directory))
        abstraction-level (or abstraction_level
                              (classify/classify-abstraction-level type content tags-vec))
        knowledge-gaps (gaps/extract-knowledge-gaps content)]
    (log/info "mcp-memory-add:" type "directory:" directory "agent_id:" agent_id)
    (with-store
      (let [project-id (scope/get-current-project-id directory)
            agent-id (or agent_id (ctx/current-agent-id)
                         (System/getenv "CLAUDE_SWARM_SLAVE_ID"))
            tags-with-scope (build-entry-tags tags-vec agent-id kg-vecs project-id)
            store (mem-proto/get-store)
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
                entry-ctx {:type type :content content :tags-with-scope tags-with-scope
                           :content-hash content-hash :duration-str duration-str
                           :expires expires :project-id project-id
                           :abstraction-level abstraction-level
                           :knowledge-gaps knowledge-gaps :agent-id agent-id}
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
                    _ (recall/register-created-id! entry-id project-id)
                    kg-params {:kg_implements (:kg-implements-vec kg-vecs)
                               :kg_supersedes (:kg-supersedes-vec kg-vecs)
                               :kg_depends_on (:kg-depends-on-vec kg-vecs)
                               :kg_refines    (:kg-refines-vec kg-vecs)}]
                (finalize-entry! entry-id kg-params project-id agent-id entry-ctx)))))))))

(defn handle-add
  "Add an entry to project memory with optional KG edge creation.
   Runs the IO-heavy core on the dedicated memory-pool so slow Chroma
   or KG calls cannot saturate the shared io-pool."
  [{:keys [abstraction_level] :as args}]
  (try
    (if (and abstraction_level (not (kg-schema/valid-abstraction-level? abstraction_level)))
      (mcp-error (str "Invalid abstraction_level: " abstraction_level))
      (let [timeout-sentinel ::timeout
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
      (if (#{:coercion-error :embedding-too-long :plan-gate-rejected} (:type (ex-data e)))
        (mcp-error (.getMessage e))
        (throw e)))))
