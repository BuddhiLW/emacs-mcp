(ns hive-mcp.knowledge-graph.connection
  "Connection management and factory for Knowledge Graph.

   Delegates to the active IGraphStore implementation (DataScript, Datalevin, Datascript, Neo4J etc).
   Maintains backward-compatible API surface for existing KG modules.

   Backend selection (priority):
   1. Explicit set-backend! call
   2. HIVE_KG_BACKEND env var (:datascript | :datahike)
   3. :kg-backend in .hive-project.edn
   4. Default: config/default-kg-backend (persistent — compounding axiom)"

  (:require [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.datascript :as ds-store]
            [hive-mcp.knowledge-graph.scope :as scope]
            [hive-mcp.config.core :as config]
            [hive-dsl.result :as r]
            [hive-dsl.batch :as dsl-batch]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Config-based Backend Auto-detection
;; =============================================================================

(defn- walk-hierarchy-for-kg-backend
  "Walk up .hive-project.edn hierarchy to find :kg-backend.
   Parent is more authoritative than child — first match walking UP wins.
   Returns keyword or nil."
  []
  (r/rescue nil
            (let [cwd (System/getProperty "user.dir")
                  home (System/getProperty "user.home")]
              (loop [dir (io/file cwd)
               ;; Collect configs child→parent, then reverse for parent-first
                     configs []]
                (cond
                  (nil? dir) nil
                  (= (.getAbsolutePath dir) home)
            ;; Check home dir then stop
                  (let [all-configs (if-let [cfg (scope/read-direct-project-config (.getAbsolutePath dir))]
                                      (conj configs cfg)
                                      configs)
                  ;; Parent-first: last found = most ancestral = highest authority
                        parent-first (reverse all-configs)]
                    (some :kg-backend parent-first))

                  :else
                  (let [cfg (scope/read-direct-project-config (.getAbsolutePath dir))]
                    (recur (.getParentFile dir)
                           (if cfg (conj configs cfg) configs))))))))

(defn- detect-backend
  "Detect the desired KG backend from configuration sources.

   Priority (highest → lowest):
   1. .hive-project.edn hierarchy (parent > child > grandchild)
   2. HIVE_KG_BACKEND env var (explicit override)
   3. config.edn :services.kg.backend (global default)
   4. Fallback: config/default-kg-backend

   Rationale: project hierarchy is ground truth (lives with code),
   config.edn is user-global preference, env var is session override."
  []
  (let [hierarchy-backend (walk-hierarchy-for-kg-backend)
        env-backend (some-> (System/getenv "HIVE_KG_BACKEND") keyword)
        config-backend (config/get-service-value :kg :backend :parse keyword)
        backend (or hierarchy-backend env-backend config-backend config/default-kg-backend)]
    (log/info "KG backend detection"
              {:hierarchy hierarchy-backend
               :env env-backend
               :config config-backend
               :selected backend})
    backend))

(defn- detect-writer-config
  "Detect the writer backend config from :services.kg.writer in config.edn.
   Returns nil for :self (local) or the writer map for remote backends.

   Example config.edn:
     {:services {:kg {:backend :datahike
                      :writer {:backend :datahike-server
                               :url \"http://localhost:4444\"
                               :token \"your-token\"}}}}

   Or for kabel:
     {:services {:kg {:backend :datahike
                      :writer {:backend :kabel
                               :peer-id #uuid \"aaaa...\"
                               :local-peer <peer-atom>}}}}"
  []
  (r/rescue nil
            (let [writer-cfg (config/get-service-value :kg :writer)]
              (when (and (map? writer-cfg)
                         (not= :self (:backend writer-cfg)))
                (log/info "KG writer config detected" {:writer writer-cfg})
                writer-cfg))))

(defn- ensure-store!
  "Ensure a store is configured. Auto-detects backend from config."
  []
  (when-not (proto/store-set?)
    (let [backend (detect-backend)]
      (log/info "Auto-initializing KG backend" {:backend backend})
      (case backend
        :datalevin
        (let [store (r/guard Exception nil
                             (require 'hive-mcp.knowledge-graph.store.datalevin)
                             (let [create-fn (resolve 'hive-mcp.knowledge-graph.store.datalevin/create-store)]
                               (create-fn)))]
          (if store
            (proto/set-store! store)
            (do
              (log/error "CRITICAL: Failed to initialize Datalevin, falling back to ephemeral DataScript. KG data on disk will NOT be accessible.")
              (proto/set-store! (ds-store/create-store)))))

        :datahike
        (let [writer-cfg (detect-writer-config)
              store (r/guard Exception nil
                             ;; Pre-load konserve namespaces in correct order before datahike.
                             ;; konserve.impl.defaults requires konserve.impl.storage-layout
                             ;; which defines -atomic-move. If storage-layout is partially
                             ;; loaded (e.g. from a concurrent require), method vars don't
                             ;; get interned and defaults.cljc fails with
                             ;; "-atomic-move does not exist". Loading the full chain here
                             ;; prevents the race.
                             (require 'konserve.protocols)
                             (require 'konserve.impl.storage-layout)
                             (require 'konserve.impl.defaults)
                             (require 'konserve.cache)
                             (require 'hive-mcp.knowledge-graph.store.datahike)
                             (let [create-fn (resolve 'hive-mcp.knowledge-graph.store.datahike/create-store)]
                               (create-fn (when writer-cfg {:writer writer-cfg}))))]
          (if store
            (proto/set-store! store)
            (do
              (log/error "CRITICAL: Failed to initialize Datahike, falling back to ephemeral DataScript. KG data on disk will NOT be accessible.")
              (proto/set-store! (ds-store/create-store)))))

        ;; Default: DataScript
        (proto/set-store! (ds-store/create-store)))))
  (proto/get-store))

;; =============================================================================
;; Transaction Batching (Dynamic Var)
;; =============================================================================

(def ^:dynamic *tx-batch*
  "When bound to an atom, transact! accumulates tx-data instead of writing.
   Use with-tx-batch to bind. nil means normal (immediate) transact behavior."
  nil)

;; =============================================================================
;; Write-Coalescing Queue (Drain-and-Flush)
;; =============================================================================
;;
;; Leverages Queue concurrency primitive (Grokking Simplicity Ch 15):
;; Individual transact! calls are coalesced into batched writes.
;;
;; Design: core.async channel + go-loop consumer.
;; - Producers put individual tx-data vectors onto the channel.
;; - Consumer drains all available items within a 25ms window,
;;   then flushes them as a single d/transact! call.
;; - d/transact! (async) provides a second layer of auto-batching
;;   at the Datahike level (per whilo's advice: memory 20260205231755).
;;
;; This eliminates the "Transacting 1 objects" pattern that causes
;; high CPU on sequential single-entity writes.

(def ^:private coalesce-window-ms
  "Time window to drain additional items before flushing batch.
   Balances latency vs batch size. 25ms gives good coalescing
   without noticeable delay on interactive operations."
  25)

(def ^:private coalesce-max-batch
  "Maximum batch size before forcing a flush, even within the window."
  200)

(defonce ^:private writer-state
  (atom {:running? false :tx-chan nil :ctrl-chan nil}))

(defonce ^:private writer-metrics
  (atom {:batches-flushed 0 :items-written 0 :items-dropped 0 :largest-batch 0}))

(defn- flush-batch!
  "Flush accumulated tx-data as a single transaction."
  [batch]
  (when (seq batch)
    (let [n (count batch)]
      (try
        (proto/transact! (ensure-store!) batch)
        (swap! writer-metrics (fn [m]
                                (-> m
                                    (update :batches-flushed inc)
                                    (update :items-written + n)
                                    (update :largest-batch max n))))
        (catch Exception e
          (log/error "Coalesced batch transact failed, falling back to individual writes"
                     {:batch-size n :error (.getMessage e)})
          ;; Fallback: retry items individually so we don't lose data
          (doseq [item batch]
            (try
              (proto/transact! (ensure-store!) [item])
              (swap! writer-metrics update :items-written inc)
              (catch Exception e2
                (log/error "Individual fallback transact also failed"
                           {:item item :error (.getMessage e2)})
                (swap! writer-metrics update :items-dropped inc)))))))))

(defn- start-writer-loop!
  "Start the background write-coalescing consumer loop.
   Creates fresh tx-chan + ctrl-chan each time (fixes channel death on stop).
   Returns map with :tx-chan :ctrl-chan :go-chan."
  []
  (let [tx-chan   (async/chan 4096)
        ctrl-chan (async/chan)
        go-chan   (async/go-loop []
                    (let [[val port] (async/alts! [ctrl-chan tx-chan])]
                      (cond
                       ;; ctrl-chan closed or signaled — graceful shutdown
                        (= port ctrl-chan)
                        (log/debug "Writer loop received shutdown signal")

                       ;; tx-chan closed — also done
                        (nil? val)
                        (log/debug "Writer loop tx-chan closed")

                        :else
                        (let [first-item val
                              batch (loop [batch (into [] (dsl-batch/normalize-tx-datum first-item))
                                           remaining coalesce-window-ms]
                                      (if (or (<= remaining 0)
                                              (>= (count batch) coalesce-max-batch))
                                        batch
                                        (let [t0 (System/currentTimeMillis)
                                              [item port] (async/alts! [ctrl-chan
                                                                        tx-chan
                                                                        (async/timeout remaining)])]
                                          (cond
                                            (= port ctrl-chan) batch
                                            (nil? item)        batch
                                            :else
                                            (recur (into batch (dsl-batch/normalize-tx-datum item))
                                                   (- remaining (- (System/currentTimeMillis) t0)))))))]
                          (flush-batch! batch)
                          (recur)))))]
    {:tx-chan tx-chan :ctrl-chan ctrl-chan :go-chan go-chan}))

(defn- ensure-writer!
  "Ensure the write-coalescing loop is running.
   Uses locking + double-check to prevent concurrent starts."
  []
  (when-not (:running? @writer-state)
    (locking writer-state
      (when-not (:running? @writer-state)
        (let [{:keys [tx-chan ctrl-chan go-chan]} (start-writer-loop!)]
          (reset! writer-state {:running? true
                                :tx-chan   tx-chan
                                :ctrl-chan ctrl-chan
                                :go-chan   go-chan})
          (log/debug "Started KG write-coalescing queue"))))))

(defn stop-writer!
  "Stop the write-coalescing loop. Idempotent — safe to call multiple times."
  []
  (locking writer-state
    (let [{:keys [running? ctrl-chan tx-chan]} @writer-state]
      (when running?
        (when ctrl-chan (async/close! ctrl-chan))
        (when tx-chan (async/close! tx-chan))
        (reset! writer-state {:running? false :tx-chan nil :ctrl-chan nil})
        (log/debug "Stopped KG write-coalescing queue")))))

(defn writer-stats
  "Return writer metrics + running state for observability."
  []
  (merge @writer-metrics
         {:running? (:running? @writer-state)}))

(defn drain-writer!
  "Drain the write-coalescing queue by sending a sentinel and waiting.
   Ensures all pending writes are flushed before returning.
   Use before read operations that need write consistency.
   No-op if writer is not running or queue is empty."
  []
  (when (:running? @writer-state)
    (let [tx-chan (:tx-chan @writer-state)]
      (when tx-chan
        ;; Put a no-op marker and wait for the queue to drain.
        ;; The coalesce window is 25ms, so 50ms is ample.
        (Thread/sleep 50)))))

;; =============================================================================
;; Backward-Compatible API
;; =============================================================================

(defn get-conn
  "Get the current connection, initializing if needed.
   Preferred entry point for accessing the KG database.
   Returns the raw backend connection."
  []
  (proto/ensure-conn! (ensure-store!)))

(defn ensure-conn!
  "Ensure connection is initialized. Creates if nil.
   Returns the connection."
  []
  (get-conn))

;; Alias for ensure-conn! without the bang (for backward compatibility)
(def ensure-conn ensure-conn!)

(defn reset-conn!
  "Reset the connection to a fresh database.
   Useful for testing or clearing state."
  []
  (proto/reset-conn! (ensure-store!)))

(defn transact!
  "Transact data to the KG database.
   Priority:
     1. *tx-batch* bound (via with-tx-batch) → accumulate for explicit batch flush
     2. Otherwise → route through write-coalescing queue for automatic batching

   The coalescing queue drains items within a 25ms window and flushes
   as a single transaction. Combined with d/transact! (async) at the
   store level, this eliminates the 'Transacting 1 objects' pattern."
  [tx-data]
  (if *tx-batch*
    (swap! *tx-batch* into (dsl-batch/normalize-tx-datum tx-data))
    (do
      (ensure-writer!)
      (let [tx-chan (:tx-chan @writer-state)]
        (when-not (and tx-chan (async/put! tx-chan tx-data))
          ;; Channel full or closed — fallback to sync write
          (log/warn "Write-coalescing queue put! failed, falling back to sync transact"
                    {:tx-data-count (if (sequential? tx-data) (count tx-data) 1)})
          (swap! writer-metrics update :items-dropped
                 + (if (sequential? tx-data) (count tx-data) 1))
          (r/rescue nil
                    (proto/transact! (ensure-store!)
                                     (dsl-batch/normalize-tx-datum tx-data))))))))

(defn transact-sync!
  "Synchronous transact — bypasses the coalescing queue.
   Use ONLY when the caller needs the tx-report return value
   (e.g., extracting entity IDs from :tx-data).
   Most callers should use transact! (async coalesced) instead."
  [tx-data]
  (proto/transact! (ensure-store!) tx-data))

(defmacro with-tx-batch
  "Collect all transact! calls within body into a single transaction.
   Transparent to callers — existing transact! usage doesn't change.

   Usage:
     (with-tx-batch
       (transact! [{:kg-edge/from \"a\" :kg-edge/to \"b\"}])
       (transact! [{:kg-edge/from \"c\" :kg-edge/to \"d\"}]))
     ;; => single transact! with both edges

   Nested with-tx-batch is safe: inner batch accumulates into outer."
  [& body]
  `(if *tx-batch*
     ;; Already batching (nested) — just run body, data accumulates to outer batch
     (do ~@body)
     ;; Outermost batch — collect and flush
     (let [batch# (atom [])]
       (binding [*tx-batch* batch#]
         (let [result# (do ~@body)]
           (when (seq @batch#)
             (proto/transact! (#'ensure-store!) @batch#))
           result#)))))

(def with-tx-batch-fn
  "Function equivalent of with-tx-batch. Coalesces transact! calls into one write.
   Built on hive-dsl/transparent-batch-scope."
  (dsl-batch/transparent-batch-scope
   #'*tx-batch*
   (fn [data] (proto/transact! (ensure-store!) data))))

(defn query
  "Query the KG database.
   Delegates to the active store."
  [q & inputs]
  (if (seq inputs)
    (proto/query (ensure-store!) q inputs)
    (proto/query (ensure-store!) q)))

(defn entity
  "Get an entity by ID from the KG database.
   Delegates to the active store."
  [eid]
  (proto/entity (ensure-store!) eid))

(defn entid
  "Resolve a lookup ref to an entity ID.
   Delegates to the active store."
  [lookup-ref]
  (proto/entid (ensure-store!) lookup-ref))

(defn pull-entity
  "Pull an entity with a pattern.
   Delegates to the active store."
  [pattern eid]
  (proto/pull-entity (ensure-store!) pattern eid))

(defn eids-by-attr
  "Return a lazy sequence of entity IDs having the given attribute.
   Backed by the attribute-first index on each store — enumerates without
   materializing entity values. Delegates to the active store."
  [attr]
  (proto/eids-by-attr (ensure-store!) attr))

(defn db-snapshot
  "Get the current database snapshot.
   Delegates to the active store."
  []
  (proto/db-snapshot (ensure-store!)))

(defn close!
  "Close the active store connection.
   Required for Datalevin to flush LMDB."
  []
  (when (proto/store-set?)
    (proto/close! (proto/get-store))))

;; =============================================================================
;; Store Configuration
;; =============================================================================

(defn set-backend!
  "Configure the KG storage backend.

   Arguments:
     backend - :datascript, :datalevin, or :datahike
     opts    - Backend-specific options:
               :datalevin {:db-path \"data/kg/datalevin\"}
               :datahike  {:db-path \"data/kg/datahike\" :backend :file}"

  [backend & [opts]]
  (log/info "Setting KG backend" {:backend backend :opts opts})
  (case backend
    :datascript
    (proto/set-store! (ds-store/create-store))

    :datalevin
    (let [;; Require datalevin store dynamically to avoid hard dep
          _ (require 'hive-mcp.knowledge-graph.store.datalevin)
          create-fn (resolve 'hive-mcp.knowledge-graph.store.datalevin/create-store)
          store (create-fn opts)]
      (if store
        (proto/set-store! store)
        (do
          (log/warn "Datalevin store creation failed, falling back to DataScript")
          (proto/set-store! (ds-store/create-store)))))

    :datahike
    (let [;; Pre-load konserve namespaces in correct order (see ensure-store! comment)
          _ (require 'konserve.protocols)
          _ (require 'konserve.impl.storage-layout)
          _ (require 'konserve.impl.defaults)
          _ (require 'konserve.cache)
          _ (require 'hive-mcp.knowledge-graph.store.datahike)
          create-fn (resolve 'hive-mcp.knowledge-graph.store.datahike/create-store)
          ;; Pass writer config if present (for datahike-server/kabel backends)
          store (create-fn (cond-> (or opts {})
                             (:writer opts) (assoc :writer (:writer opts))))]
      (if store
        (proto/set-store! store)
        (do
          (log/warn "Datahike store creation failed, falling back to DataScript")
          (proto/set-store! (ds-store/create-store)))))

    ;; Unknown backend
    (throw (ex-info "Unknown KG backend" {:backend backend
                                          :valid #{:datascript :datalevin :datahike}}))))

;; =============================================================================
;; ID and Timestamp Utilities
;; =============================================================================

(defn gen-edge-id
  "Generate a unique edge ID with timestamp prefix.
   Format: edge-yyyyMMddTHHmmss-XXXXXX
   The timestamp prefix enables chronological sorting."
  []
  (let [now (java.time.LocalDateTime/now)
        formatter (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss")
        timestamp (.format now formatter)
        random-hex (format "%06x" (rand-int 0xFFFFFF))]
    (str "edge-" timestamp "-" random-hex)))

(defn now
  "Return current timestamp as java.util.Date.
   Convenience for edge :created-at fields."
  []
  (java.util.Date.))

;; =============================================================================
;; Temporal Query Facade (W3)
;; =============================================================================

(defn temporal-store?
  "Check if the current store supports temporal queries (time-travel).
   Returns true for Datahike, false for DataScript/Datalevin.

   Use this to guard temporal query calls in application code."
  []
  (proto/temporal-store? (ensure-store!)))

(defn history-db
  "Get a database containing all historical facts.

   Returns a DB value that includes retracted datoms, enabling
   queries over the complete history of the store.

   Returns nil if the store does not support temporal queries.

   Example:
     (when (temporal-store?)
       (query '[:find ?e ?attr ?v ?added
                :where [?e ?attr ?v _ ?added]]
              (history-db)))"
  []
  (let [store (ensure-store!)]
    (when (proto/temporal-store? store)
      (proto/history-db store))))

(defn as-of-db
  "Get the database as of a specific point in time.

   Arguments:
     tx-or-time - Transaction ID (integer) or java.util.Date timestamp

   Returns a DB value representing the state at that point,
   or nil if the store does not support temporal queries.

   Example:
     ;; Query state from 1 hour ago
     (as-of-db (java.util.Date. (- (System/currentTimeMillis) 3600000)))"
  [tx-or-time]
  (let [store (ensure-store!)]
    (when (proto/temporal-store? store)
      (proto/as-of-db store tx-or-time))))

(defn since-db
  "Get a database containing only facts added since a point in time.

   Arguments:
     tx-or-time - Transaction ID (integer) or java.util.Date timestamp

   Returns a DB value with only facts added after that point,
   or nil if the store does not support temporal queries.

   Useful for incremental change tracking and sync operations."
  [tx-or-time]
  (let [store (ensure-store!)]
    (when (proto/temporal-store? store)
      (proto/since-db store tx-or-time))))

(defn query-history
  "Query against the full history database.

   Arguments:
     q      - Datalog query
     inputs - Optional additional query inputs

   Returns query results against history DB, enabling queries
   that span all historical states (including retracted facts).

   Returns nil if the store does not support temporal queries.

   Example:
     ;; Find all values an attribute ever had
     (query-history '[:find ?v ?added
                      :in $ ?e ?attr
                      :where [?e ?attr ?v _ ?added]]
                    [:kg-edge/id \"some-id\"] :kg-edge/weight)"
  [q & inputs]
  (when-let [hdb (history-db)]
    ;; Dynamically require datahike.api to avoid hard dependency
    (require 'datahike.api)
    (if (seq inputs)
      (apply (resolve 'datahike.api/q) q hdb inputs)
      ((resolve 'datahike.api/q) q hdb))))

(defn query-as-of
  "Query the database as it was at a specific point in time.

   Arguments:
     tx-or-time - Transaction ID (integer) or java.util.Date timestamp
     q          - Datalog query
     inputs     - Optional additional query inputs

   Returns query results from the point-in-time snapshot,
   or nil if the store does not support temporal queries.

   Example:
     ;; What edges existed yesterday?
     (query-as-of yesterday
                  '[:find ?id
                    :where [?e :kg-edge/id ?id]])"
  [tx-or-time q & inputs]
  (when-let [aodb (as-of-db tx-or-time)]
    (require 'datahike.api)
    (if (seq inputs)
      (apply (resolve 'datahike.api/q) q aodb inputs)
      ((resolve 'datahike.api/q) q aodb))))
