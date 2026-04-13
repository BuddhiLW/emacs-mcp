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
            [taoensso.timbre :as log]))

(def ^:const ^:private memory-write-timeout-ms
  "Timeout budget for a single memory write (Chroma add + KG tx + fetch).
   On timeout the write may still land later; caller gets an error."
  30000)
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- update-target-incoming!
  "Append edge-id to target entry's kg-incoming field."
  [target-id edge-id]
  (let [store (mem-proto/get-store)]
    (when-let [target-entry (mem-proto/get-entry store target-id)]
      (let [existing-incoming (or (:kg-incoming target-entry) [])
            updated-incoming (conj existing-incoming edge-id)]
        (mem-proto/update-entry! store target-id {:kg-incoming updated-incoming})))))

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
   Strict: type=plan MUST pass validation. No heuristic bypass."
  [content]
  (if-not (plan-gate/plan-content? content)
    ;; Content declared as plan but doesn't look like one — reject
    (throw (ex-info (str "Content declared as type=plan but is not parseable as a plan.\n"
                         "Plan content must contain either:\n"
                         "  1. EDN with {:steps [{:id \"step-1\" :title \"...\"}]}\n"
                         "  2. Markdown with ## headers for each step\n"
                         "Use type=note or type=decision for free-form content.")
                    {:type :plan-gate-rejected
                     :phase :detection
                     :errors ["Content does not match plan format"]}))
    (let [gate-result (plan-gate/validate-for-storage content)]
      (when-not (:valid? gate-result)
        (throw (ex-info (plan-gate/format-gate-error gate-result)
                        {:type :plan-gate-rejected
                         :phase (:phase gate-result)
                         :errors (:errors gate-result)}))))))

(defn- index-entry!
  "Index entry in appropriate collection.
   Plans → OpenRouter-backed plans collection.
   Everything else → Ollama-backed memory collection."
  [openrouter? {:keys [type content tags-with-scope content-hash duration-str
                       expires project-id abstraction-level knowledge-gaps agent-id]}]
  (if openrouter?
    (plans/index-plan!
     {:type type :content content :tags tags-with-scope
      :content-hash content-hash :duration duration-str
      :expires (or expires "") :project-id project-id
      :abstraction-level abstraction-level
      :knowledge-gaps knowledge-gaps :agent-id agent-id})
    (mem-proto/add-entry! (mem-proto/get-store)
                          {:type type :content content :tags tags-with-scope
                           :content-hash content-hash :duration duration-str
                           :expires (or expires "") :project-id project-id
                           :abstraction-level abstraction-level
                           :knowledge-gaps knowledge-gaps})))

(defn- finalize-entry!
  "Wire KG edges, fetch created entry, notify channel, and format response."
  [entry-id openrouter? kg-params project-id agent-id
   {:keys [tags-with-scope type knowledge-gaps]}]
  (let [store (mem-proto/get-store)
        edge-ids (create-kg-edges! entry-id kg-params project-id agent-id)
        _ (when (and (seq edge-ids) (not openrouter?))
            (mem-proto/update-entry! store entry-id {:kg-outgoing edge-ids}))
        created (if openrouter? (plans/get-plan entry-id) (mem-proto/get-entry store entry-id))]
    (when-not created
      (log/error "Failed to retrieve entry after indexing:" entry-id
                 "openrouter?" openrouter?))
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
      (mcp-error (str "Entry indexed as " entry-id " but retrieval failed. Check Chroma connectivity.")))))

(defn- do-add!
  "Inner core of handle-add. Runs the Chroma/KG/plan IO path.
   Intended to be submitted to the memory pool for isolation."
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
            existing (mem-proto/find-duplicate store type content-hash {:project-id project-id})]
        (if existing
          (let [merged-tags (distinct (concat (:tags existing) tags-with-scope))
                updated (mem-proto/update-entry! store (:id existing) {:tags merged-tags})]
            (log/info "Duplicate found, merged tags:" (:id existing))
            (mcp-json (fmt/entry->json-alist updated)))
          (let [openrouter? (plans/high-abstraction-type? type)
                _ (when (= type "plan") (validate-plan-gate! content))
                entry-ctx {:type type :content content :tags-with-scope tags-with-scope
                           :content-hash content-hash :duration-str duration-str
                           :expires expires :project-id project-id
                           :abstraction-level abstraction-level
                           :knowledge-gaps knowledge-gaps :agent-id agent-id}
                entry-id (index-entry! openrouter? entry-ctx)
                _ (recall/register-created-id! entry-id project-id)
                kg-params {:kg_implements (:kg-implements-vec kg-vecs)
                           :kg_supersedes (:kg-supersedes-vec kg-vecs)
                           :kg_depends_on (:kg-depends-on-vec kg-vecs)
                           :kg_refines    (:kg-refines-vec kg-vecs)}]
            (finalize-entry! entry-id openrouter? kg-params project-id agent-id entry-ctx)))))))

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
