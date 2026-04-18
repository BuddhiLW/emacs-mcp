(ns hive-mcp.memory.store.chroma
  "ChromaDB implementation of IMemoryStore protocol.

   Wraps existing hive-mcp.chroma.core functions into protocol methods.
   This is Phase 1 of vectordb abstraction - enabling pluggable backends
   later (e.g., Milvus, DataScript-for-testing).

   DDD: Repository pattern - ChromaMemoryStore is the Chroma aggregate adapter."
  (:require [hive-mcp.protocols.memory :as proto]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =========================================================================
;; Pure Calculations (extracted to lower per-fn CC)
;; =========================================================================

(defn- now-iso
  "Current ISO 8601 timestamp string."
  []
  (str (java.time.ZonedDateTime/now (java.time.ZoneId/systemDefault))))

(defn- staleness-probability
  "Calculate staleness probability from alpha/beta parameters.
   Returns double in [0, 1]."
  [entry]
  (let [alpha (or (:staleness-alpha entry) 1)
        beta  (or (:staleness-beta entry) 1)]
    (/ (double beta) (+ alpha beta))))

(defn- feedback->field
  "Map feedback keyword to entry field name."
  [feedback]
  (case feedback
    :helpful   :helpful-count
    :unhelpful :unhelpful-count))

(defn- helpfulness-map
  "Build helpfulness ratio map from entry counts."
  [entry]
  (let [helpful   (or (:helpful-count entry) 0)
        unhelpful (or (:unhelpful-count entry) 0)
        total     (+ helpful unhelpful)]
    {:helpful-count   helpful
     :unhelpful-count unhelpful
     :total           total
     :ratio           (if-let [_ (pos? total)] (double (/ helpful total)) 0.0)}))

(defn- build-health-map
  "Build health-check response from probe result and latency.
   probe-result is a Result (ok/err from probe-chroma)."
  [probe-result latency-ms]
  (let [ts (now-iso)]
    (if-let [data (:ok probe-result)]
      (let [{:keys [available? stats]} data]
        {:healthy?    (boolean available?)
         :latency-ms  latency-ms
         :backend     "chroma"
         :entry-count (when-let [s stats] (:count s))
         :errors      (if-let [_ available?] [] ["Chroma not reachable"])
         :checked-at  ts})
      {:healthy?    false
       :latency-ms  latency-ms
       :backend     "chroma"
       :entry-count nil
       :errors      [(:message probe-result "Unknown error")]
       :checked-at  ts})))

;; =========================================================================
;; Side-Effect Wrappers (Result-returning, isolated try-catch)
;; =========================================================================

(defn- probe-chroma
  "Probe Chroma availability and stats. Returns Result."
  []
  (result/try-effect* :chroma/probe-failed
                      (let [available? (chroma/chroma-available?)
                            stats      (when-let [_ available?] (chroma/collection-stats))]
                        {:available? available? :stats stats})))

(defn- safe-entry-count
  "Get collection entry count, nil on failure."
  []
  (:ok (result/try-effect* :chroma/stats-failed
                           (:count (chroma/collection-stats)))))

(defn- propagate-to-dep!
  "Propagate staleness to a single dependency. Returns Result."
  [dep-id depth]
  (result/try-effect* :chroma/propagate-failed
                      (let [dep-beta (or (:staleness-beta (chroma/get-entry-by-id dep-id)) 1)]
                        (chroma/update-staleness! dep-id
                                                  {:beta   (inc dep-beta)
                                                   :source :transitive
                                                   :depth  (inc depth)}))))

;; =========================================================================
;; Protocol Implementation
;; =========================================================================

(defrecord ChromaMemoryStore [config-atom]
  ;; =========================================================================
  ;; IMemoryStore - Core Protocol (16 methods)
  ;; =========================================================================
  proto/IMemoryStore

  ;; --- Connection Lifecycle ---

  (connect! [_this config]
    (let [r (result/try-effect* :chroma/connect-failed
                                (do
                                  (chroma/configure! (select-keys config [:host :port :collection-name]))
                                  (when-let [_ (chroma/embedding-configured?)]
                                    (chroma/chroma-available?))
                                  (swap! config-atom merge config)
                                  {:backend  "chroma"
                                   :metadata (select-keys @config-atom [:host :port :collection-name])}))]
      (if-let [data (:ok r)]
        (merge {:success? true :errors []} data)
        {:success? false
         :backend  "chroma"
         :errors   [(:message r "Connect failed")]
         :metadata {}})))

  (disconnect! [_this]
    (try
      (chroma/reset-collection-cache!)
      {:success? true :errors []}
      (catch Exception e
        {:success? false :errors [(.getMessage e)]})))

  (connected? [_this]
    (chroma/embedding-configured?))

  (health-check [_this]
    (let [start-ms (System/currentTimeMillis)
          r        (probe-chroma)
          latency  (- (System/currentTimeMillis) start-ms)]
      (build-health-map r latency)))

  ;; --- CRUD Operations ---

  (add-entry! [_this entry]
    (chroma/index-memory-entry! entry))

  (get-entry [_this id]
    (chroma/get-entry-by-id id))

  (update-entry! [_this id updates]
    (chroma/update-entry! id updates))

  (delete-entry! [_this id]
    (chroma/delete-entry! id)
    true)

  (query-entries [_this opts]
    (let [{:keys [type project-id project-ids tags exclude-tags
                  limit include-expired? grounded-from
                  output-fields]  ;; accepted for interface compat; chroma ignores projection
           :or {limit 100 include-expired? false}} opts]
      (if grounded-from
        (chroma/query-grounded-from grounded-from)
        (chroma/query-entries :type type
                              :project-id project-id
                              :project-ids project-ids
                              :tags tags
                              :exclude-tags exclude-tags
                              :limit limit
                              :include-expired? include-expired?))))

  ;; --- Semantic Search ---

  (search-similar [_this query-text opts]
    (let [{:keys [limit type project-ids exclude-tags]} opts]
      (chroma/search-similar query-text
                             :limit (or limit 10)
                             :type type
                             :project-ids project-ids
                             :exclude-tags exclude-tags)))

  (supports-semantic-search? [_this]
    (chroma/embedding-configured?))

  ;; --- Expiration Management ---

  (cleanup-expired! [_this]
    (chroma/cleanup-expired!))

  (entries-expiring-soon [_this days opts]
    (let [{:keys [project-id]} opts]
      (chroma/entries-expiring-soon days :project-id project-id)))

  ;; --- Duplicate Detection ---

  (find-duplicate [_this type content-hash opts]
    (let [{:keys [project-id]} opts]
      (chroma/find-duplicate type content-hash :project-id project-id)))

  ;; --- Store Management ---

  (store-status [_this]
    (let [status (chroma/status)]
      {:backend          "chroma"
       :configured?      (:configured? status)
       :entry-count      (safe-entry-count)
       :supports-search? (:configured? status)}))

  (reset-store! [_this]
    (chroma/reset-collection-cache!)
    true)

  ;; =========================================================================
  ;; IMemoryStoreWithAnalytics - Optional Extension (3 methods)
  ;; =========================================================================
  proto/IMemoryStoreWithAnalytics

  (log-access! [_this id]
    (when-let [entry (chroma/get-entry-by-id id)]
      (chroma/update-entry! id {:access-count (inc (or (:access-count entry) 0))})))

  (record-feedback! [_this id feedback]
    (when-let [entry (chroma/get-entry-by-id id)]
      (let [field     (feedback->field feedback)
            new-count (inc (or (get entry field) 0))]
        (chroma/update-entry! id {field new-count}))))

  (get-helpfulness-ratio [_this id]
    (when-let [entry (chroma/get-entry-by-id id)]
      (helpfulness-map entry)))

  ;; =========================================================================
  ;; IMemoryStoreWithStaleness - Optional Extension (3 methods)
  ;; =========================================================================
  proto/IMemoryStoreWithStaleness

  (update-staleness! [_this id staleness-opts]
    (chroma/update-staleness! id staleness-opts))

  (get-stale-entries [_this threshold opts]
    (let [{:keys [project-id type]} opts
          entries (chroma/query-entries :project-id project-id
                                        :type type
                                        :limit 10000)]
      (->> entries
           (filter #(> (staleness-probability %) threshold))
           vec)))

  (propagate-staleness! [_this source-id depth]
    ;; Phase 2 feature - propagate via KG edges
    (when-let [entry (chroma/get-entry-by-id source-id)]
      (let [kg-outgoing (:kg-outgoing entry)]
        (reduce (fn [cnt dep-id]
                  (if-let [_ (seq dep-id)]
                    (if-let [_ (:ok (propagate-to-dep! dep-id depth))]
                      (inc cnt)
                      (do (log/debug "Failed to propagate staleness to" dep-id)
                          cnt))
                    cnt))
                0
                kg-outgoing)))))

(defn create-store
  "Create a new Chroma-backed memory store.

   Options (optional, also configurable via connect!):
     :host            - Chroma server host (default: localhost)
     :port            - Chroma server port (default: 8000)
     :collection-name - Collection name (default: hive-mcp-memory)

   Returns an IMemoryStore implementation.

   Example:
     (def store (create-store))
     (proto/connect! store {:host \"localhost\" :port 8000})
     (proto/set-store! store)"
  ([]
   (create-store {}))
  ([opts]
   (log/info "Creating ChromaMemoryStore" (when-let [o (seq opts)] o))
   (->ChromaMemoryStore (atom (merge {:host "localhost"
                                      :port 8000
                                      :collection-name "hive-mcp-memory"}
                                     opts)))))
