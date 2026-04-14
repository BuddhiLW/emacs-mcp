(ns hive-mcp.plan.plans
  "Chroma vector database integration for plan memory entries.

   Plan memory entries benefit from larger embedding models.
   They get their own Chroma collection backed by OpenRouter
   embeddings (4096 dims, ~8K+ context).

   Architecture:
   ```
   hive-mcp-memory  -> Ollama (768 dims, ~1500 char, free)
                        Stores: ALL types except plans (notes, decisions, snippets, etc.)
   hive-mcp-plans   -> OpenRouter (4096 dims, ~8K+ context, cheap)
                        Stores: plans ONLY
   hive-mcp-presets -> OpenRouter (4096 dims, already working)
   ```

   Collection Schema:
     id: memory entry ID (timestamp-based, same as hive-mcp-memory)
     content: full entry text (can be 1000-5000+ chars)
     metadata:
       - type: 'plan'
       - tags: comma-separated tags
       - project-id: project scope
       - duration: TTL category
       - expires: ISO timestamp
       - content-hash: SHA256 of content
       - steps-count: number of plan steps
       - decision-id: linked decision entry ID (optional)
       - wave-count: number of wave dispatches planned (optional)
       - plan-status: draft | active | completed | superseded
       - abstraction-level: always 4 (Intent level)

   Usage:
     ;; Index a plan
     (index-plan! {:type \"plan\" :content \"...\" :tags [...] :project-id \"hive-mcp\"})

     ;; Semantic search
     (search-plans \"authentication refactoring\" :limit 3)

     ;; Get specific plan
     (get-plan \"20260206110839-4b3dadd6\")

     ;; Query plans by project
     (query-plans :project-id \"hive-mcp\" :limit 10)"
  (:require [hive-mcp.chroma.core :as chroma]
            [hive-mcp.dns.result :as result]
            [hive-weave.safe :as ws]
            [clojure-chroma-client.api :as chroma-api]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private collection-name "hive-mcp-plans")

(def high-abstraction-types
  "Memory types routed to OpenRouter-backed plans collection.
   Plans are long-form content that benefits from larger embedding
   models (4096 dims vs Ollama's 768 dims).
   Only plans route here — everything else (decisions, notes, etc.) → Ollama."
  #{"plan"})

(defn high-abstraction-type?
  "Returns true if the memory type should use the OpenRouter-backed plans collection."
  [type]
  (contains? high-abstraction-types type))

(def ^:private valid-plan-statuses
  "Valid plan-status values."
  #{"draft" "active" "completed" "superseded"})

(defonce ^:private collection-cache (atom nil))

(defn- try-get-existing-collection
  "Try to get existing collection. Returns nil on failure."
  []
  (result/rescue nil
                 (ws/deref-safe! (chroma-api/get-collection collection-name) 15000)))

(defn- delete-collection!
  "Delete the plans collection. Returns true on success."
  []
  (result/rescue false
                 (when-let [coll (try-get-existing-collection)]
                   (ws/deref-safe! (chroma-api/delete-collection coll) 30000)
                   (Thread/sleep 50))
                 true))

(defn- create-collection-with-dimension
  "Create a new collection with the given dimension.
   Returns fresh collection reference to avoid stale cache issues."
  [dim]
  ;; Verify collection doesn't exist before creating
  (when-let [_stale (try-get-existing-collection)]
    (log/warn "Stale plans collection found after delete, forcing re-delete")
    (delete-collection!))
  ;; Create the collection
  (ws/deref-safe! (chroma-api/create-collection
                    collection-name
                    {:metadata {:dimension dim
                                :created-by "hive-mcp"
                                :purpose "plan-memory-entries"}})
                  30000)
  ;; Get fresh reference
  (Thread/sleep 50)
  (or (try-get-existing-collection)
      (throw (ex-info "Failed to get plans collection after creation"
                      {:collection collection-name :dimension dim}))))

(defn- cache-and-return-collection!
  "Cache `coll` under the plans collection cache, log the action,
   and return `coll`. Used by both the dimension-mismatch recreate
   branch and the fresh-create branch of `get-or-create-collection`
   to DRY up the common tail (cache + log + return)."
  [coll required-dim action]
  (reset! collection-cache coll)
  (log/info (case action
              :recreated "Recreated plans collection:"
              :created   "Created plans collection:"
              "Cached plans collection:")
            collection-name "dimension:" required-dim)
  coll)

(defn- get-or-create-collection
  "Get existing plans collection or create new one.

   Flex Embedding Dimensions:
   If the existing collection's dimension doesn't match the current provider,
   the collection is automatically recreated with the correct dimension.
   This handles provider switches (e.g., Ollama 768 -> OpenRouter 4096).

   COLLECTION-AWARE: Uses chroma/get-provider-for to get the provider
   configured specifically for this collection (if any), falling back
   to global provider."
  []
  (if-let [coll @collection-cache]
    coll
    (let [provider (chroma/get-provider-for collection-name)]
      (when-not provider
        (throw (ex-info "Embedding provider not configured for plans collection."
                        {:type :no-embedding-provider
                         :collection collection-name})))
      (let [required-dim (chroma/embedding-dimension provider)
            existing (try-get-existing-collection)]
        (if existing
          ;; Check dimension match
          (let [existing-dim (get-in existing [:metadata :dimension])]
            (if (= existing-dim required-dim)
              ;; Dimension matches - reuse existing collection
              (do
                (reset! collection-cache existing)
                (log/info "Using existing plans collection:" collection-name "dimension:" existing-dim)
                existing)
              ;; Dimension mismatch - recreate collection!
              (do
                (log/warn "Plans embedding dimension changed:" existing-dim "->" required-dim ". Recreating collection.")
                (delete-collection!)
                (reset! collection-cache nil)
                (cache-and-return-collection!
                  (create-collection-with-dimension required-dim)
                  required-dim
                  :recreated))))
          ;; No existing collection - create new
          (cache-and-return-collection!
            (create-collection-with-dimension required-dim)
            required-dim
            :created))))))

(defn reset-collection-cache!
  "Reset the collection cache. For testing."
  []
  (reset! collection-cache nil))

(defn- entry-to-document
  "Convert plan entry to searchable document string.
   Includes structured metadata header for better semantic search."
  [{:keys [type content tags project-id plan-status steps-count decision-id]}]
  (let [entry-type (or type "plan")
        label (str "Plan Entry [" (or plan-status "draft") "]")]
    (str label "\n"
         "Type: " entry-type "\n"
         "Project: " (or project-id "unknown") "\n"
         (when steps-count (str "Steps: " steps-count "\n"))
         (when decision-id (str "Decision: " decision-id "\n"))
         "Tags: " (if (sequential? tags) (str/join ", " tags) (or tags "")) "\n\n"
         content)))

(defn- count-plan-steps
  "Heuristic to count steps in plan content.
   Looks for numbered lists, ## Step headers, etc."
  [content]
  (when content
    (let [numbered (count (re-seq #"(?m)^\s*\d+[\.\)]\s+" content))
          step-headers (count (re-seq #"(?m)^#+\s*[Ss]tep\s+\d+" content))]
      (max numbered step-headers))))

(defn- extract-entry-metadata
  "Extract metadata from plan entry map.
   Returns metadata map suitable for Chroma storage."
  [{:keys [type content tags project-id duration expires content-hash
           steps-count decision-id wave-count plan-status
           abstraction-level knowledge-gaps agent-id]}]
  (let [entry-type (or type "plan")
        is-plan? (= entry-type "plan")
        auto-steps (when is-plan? (or steps-count (count-plan-steps content)))]
    (cond-> {:type entry-type
             :tags (if (sequential? tags) (str/join "," tags) (or tags ""))
             :project-id (or project-id "")
             :duration (or duration "long")
             :expires (or expires "")
             :content-hash (or content-hash "")
             :abstraction-level (or abstraction-level 4)}
      is-plan?          (assoc :plan-status (or plan-status "draft"))
      auto-steps        (assoc :steps-count (str auto-steps))
      decision-id       (assoc :decision-id decision-id)
      wave-count        (assoc :wave-count (str wave-count))
      agent-id          (assoc :agent-id agent-id)
      knowledge-gaps    (assoc :knowledge-gaps
                               (if (sequential? knowledge-gaps)
                                 (str/join "|" knowledge-gaps)
                                 (or knowledge-gaps ""))))))

(defn index-plan!
  "Index a plan entry in the plans Chroma collection.

   Entry map keys:
     :type       - 'plan' (default: 'plan')
     :content    - Full entry text (can be 1000-5000+ chars)
     :tags       - Vector of tag strings
     :project-id - Project scope
     :duration   - TTL category (default: 'long')
     :expires    - ISO timestamp for expiration
     :content-hash - SHA256 of content
     :steps-count  - Number of plan steps (auto-detected if nil, plan only)
     :decision-id  - Linked decision entry ID (optional)
     :wave-count   - Number of wave dispatches planned (optional, plan only)
     :plan-status  - draft | active | completed | superseded (plan only)
     :id           - Pre-generated ID (optional, auto-generated if nil)

   Returns: entry ID string on success.

   COLLECTION-AWARE: Uses collection-specific embedding provider."
  [{:keys [id type] :as entry}]
  (let [coll (get-or-create-collection)
        provider (chroma/get-provider-for collection-name)
        entry-id (or id (let [ts (java.time.LocalDateTime/now)
                              fmt (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")]
                          (str (.format ts fmt) "-" (subs (str (java.util.UUID/randomUUID)) 0 8))))
        doc-text (entry-to-document entry)
        embedding (chroma/embed-text provider doc-text)
        metadata (extract-entry-metadata entry)]
    (ws/deref-safe! (chroma-api/add coll [{:id entry-id
                                           :embedding embedding
                                           :document doc-text
                                           :metadata metadata}]
                                    :upsert? true)
                    30000)
    (log/info "Indexed" (or type "plan") "in plans collection:" entry-id
              (when (:plan-status metadata) (str "status:" (:plan-status metadata)))
              (when (:steps-count metadata) (str "steps:" (:steps-count metadata))))
    entry-id))

(defn search-plans
  "Search plan entries using semantic similarity.

   Options:
     :limit       - Max results (default: 5)
     :project-id  - Filter by project
     :type        - Filter by type (default: 'plan')
     :plan-status - Filter by status (draft, active, completed, superseded)

   COLLECTION-AWARE: Uses collection-specific embedding provider.

   Returns seq of {:id, :type, :tags, :project-id, :plan-status, :distance, :preview}"
  [query-text & {:keys [limit project-id type plan-status] :or {limit 5}}]
  (let [coll (get-or-create-collection)
        provider (chroma/get-provider-for collection-name)
        query-embedding (chroma/embed-text provider query-text)
        where-clause (cond-> nil
                       type (assoc :type type)
                       project-id (assoc :project-id project-id)
                       plan-status (assoc :plan-status plan-status))
        results (ws/deref-safe! (chroma-api/query coll query-embedding
                                                  :num-results limit
                                                  :where where-clause
                                                  :include #{:documents :metadatas :distances})
                               15000)]
    (log/debug "Plan search for:" (subs query-text 0 (min 50 (count query-text)))
               "found:" (count results))
    (mapv (fn [{:keys [id document metadata distance]}]
            {:id id
             :type (get metadata :type "plan")
             :tags (when-let [t (get metadata :tags)]
                     (when (not= t "")
                       (str/split t #",")))
             :project-id (get metadata :project-id)
             :plan-status (get metadata :plan-status)
             :steps-count (when-let [s (get metadata :steps-count)]
                            (when (not= s "") (parse-long s)))
             :decision-id (get metadata :decision-id)
             :distance distance
             :preview (when document
                        (subs document 0 (min 300 (count document))))})
          results)))

(defn get-plan
  "Get a specific plan by ID from the plans collection.
   Returns full plan map or nil if not found."
  [plan-id]
  (result/rescue nil
                 (let [coll (get-or-create-collection)
                       results (ws/deref-safe! (chroma-api/get coll :ids [plan-id] :include #{:documents :metadatas})
                                               15000)]
                   (when-let [{:keys [id document metadata]} (first results)]
                     {:id id
                      :type (get metadata :type "plan")
                      :content document
                      :tags (when-let [t (get metadata :tags)]
                              (when (not= t "")
                                (str/split t #",")))
                      :project-id (get metadata :project-id)
                      :duration (get metadata :duration)
                      :expires (get metadata :expires)
                      :plan-status (get metadata :plan-status)
                      :steps-count (when-let [s (get metadata :steps-count)]
                                     (when (not= s "") (parse-long s)))
                      :decision-id (get metadata :decision-id)
                      :wave-count (when-let [w (get metadata :wave-count)]
                                    (when (not= w "") (parse-long w)))
                      :abstraction-level (when-let [a (get metadata :abstraction-level)]
                                           (if (string? a) (parse-long a) a))
                      :content-hash (get metadata :content-hash)}))))

(defn query-plans
  "Query plan entries with metadata filtering.

   Options:
     :project-id  - Filter by project
     :type        - Filter by type (default: 'plan')
     :plan-status - Filter by status
     :limit       - Max results (default: 20)
     :tags        - Filter by tags (entries must contain ALL tags)

   Returns seq of entry maps (without full content for efficiency)."
  [& {:keys [project-id type plan-status limit tags] :or {limit 20}}]
  (result/rescue []
                 (let [coll (get-or-create-collection)
                       where-clause (cond-> {}
                                      type (assoc :type type)
                                      project-id (assoc :project-id project-id)
                                      plan-status (assoc :plan-status plan-status))
                       results (ws/deref-safe! (chroma-api/get coll
                                                              :where where-clause
                                                              :include #{:metadatas :documents}
                                                              :limit limit)
                                             15000)]
                   (->> results
                        (map (fn [{:keys [id metadata document]}]
                               {:id id
                                :type (get metadata :type "plan")
                                :tags (when-let [t (get metadata :tags)]
                                        (when (not= t "")
                                          (str/split t #",")))
                                :project-id (get metadata :project-id)
                                :plan-status (get metadata :plan-status)
                                :steps-count (when-let [s (get metadata :steps-count)]
                                               (when (not= s "") (parse-long s)))
                                :decision-id (get metadata :decision-id)
                                :duration (get metadata :duration)
                                :preview (when document
                                           (subs document 0 (min 200 (count document))))}))
           ;; Apply tag filter in memory (Chroma where doesn't support substring matching on tags)
                        (filter (fn [entry]
                                  (if (seq tags)
                                    (let [entry-tags (set (:tags entry))]
                                      (every? #(contains? entry-tags %) tags))
                                    true)))
                        (take limit)
                        (vec)))))

(defn update-plan-status!
  "Update the status of a plan.
   Valid statuses: draft, active, completed, superseded."
  [plan-id new-status]
  (when-not (contains? valid-plan-statuses new-status)
    (throw (ex-info (str "Invalid plan status: " new-status
                         ". Valid: " (str/join ", " valid-plan-statuses))
                    {:type :invalid-plan-status
                     :status new-status})))
  (result/rescue nil
                 (let [coll (get-or-create-collection)]
                   (ws/deref-safe! (chroma-api/update coll [{:id plan-id
                                                              :metadata {:plan-status new-status}}])
                                   30000)
                   (log/info "Updated plan status:" plan-id "->" new-status)
                   plan-id)))

(defn delete-plan!
  "Delete a plan from the plans collection."
  [plan-id]
  (let [coll (get-or-create-collection)]
    (ws/deref-safe! (chroma-api/delete coll :ids [plan-id]) 30000)
    (log/debug "Deleted plan from Chroma:" plan-id)
    plan-id))

(defn status
  "Get plans collection integration status."
  []
  (let [base {:collection collection-name
              :chroma-configured? (chroma/embedding-configured?)}]
    (if (chroma/embedding-configured?)
      (let [plans (query-plans)
            rescue-err (some-> plans meta ::result/error)]
        (if rescue-err
          (assoc base :error (:message rescue-err))
          (assoc base
                 :count (count plans)
                 :statuses (frequencies (map :plan-status plans))
                 :projects (frequencies (map :project-id plans)))))
      base)))
