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
            [hive-mcp.protocols.kg :as pkg]
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

(defn- store-live?
  "True iff a store is configured AND still satisfies the current
   IKGStore protocol object. Guards against a common live-REPL hazard:
   the protocol ns gets reloaded after the store was constructed, leaving
   a reify/defrecord instance that no longer satisfies the new protocol.
   `satisfies?` then returns false at every write call site, and the
   downstream `r/rescue nil` swallows the resulting AssertionError —
   producing silent transaction drops."
  []
  (and (proto/store-set?)
       (satisfies? pkg/IKGStore (proto/get-store))))

(def ^:dynamic *test-store*
  "Per-thread override for the active KG store.
   When non-nil, `ensure-store!` returns this directly without
   touching the global proto/store atom. Bound by the :kg-conn
   isolation fixture (hive-mcp.isolation-methods) so KG tests run
   against a fresh ephemeral store without polluting prod state.
   Honors axiom 20260122235103-7151cc29 (Test Isolation Silent Server Death)."
  nil)

(defn- ensure-store!
  "Ensure a store is configured. Auto-detects backend from config.
   Re-initializes when the current store is stale (see `store-live?`).
   Returns *test-store* directly when bound (test-isolation override)."
  []
  (or *test-store*
      (do
        (when-not (store-live?)
          (when (proto/store-set?)
            (log/warn "Active KG store failed satisfies? IKGStore — recreating"
                      "(likely stale protocol reference after ns reload)")
            (proto/clear-store!))
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
                (if (and store
                         (r/ok? (r/try-effect*
                                 :datahike/ensure-conn-failed
                                 (pkg/ensure-conn! store))))
                  (proto/set-store! store)
                  (do
                    (log/error "CRITICAL: Failed to initialize Datahike. Refusing to substitute another KG backend because :kg-backend requested :datahike.")
                    (throw (ex-info "Datahike KG backend unavailable"
                                    {:backend :datahike
                                     :hint "Check :services.datahike.path / HIVE_KG_DB_PATH. The configured path must be a Datahike database, not a container directory."})))))

              ;; Default: DataScript
              (proto/set-store! (ds-store/create-store)))))
        (proto/get-store))))

;; =============================================================================
;; Transaction Batching (Dynamic Var)
;; =============================================================================

(def ^:dynamic *tx-batch*
  "When bound to an atom, transact! accumulates tx-data instead of writing.
   Use with-tx-batch to bind. nil means normal (immediate) transact behavior."
  nil)

(def ^:dynamic *sync-writes*
  "When true, transact! bypasses the coalescing queue and writes synchronously.
   Use in tests for deterministic ordering. Default false."
  false)

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

;; in-flight: count of items enqueued on tx-chan but not yet flushed.
;; Incremented by transact! on successful put!, decremented by flush-batch!
;; after proto/transact! completes. Used by flush-pending! to detect drain.
(defonce ^:private in-flight (atom 0))

(defn- flush-batch!
  "Flush accumulated tx-data as a single transaction.
   `batch-item-count` is the number of producer-side items this batch drained
   from tx-chan (used to decrement in-flight); it may differ from (count batch)
   after dsl-batch/normalize-tx-datum expansion."
  [batch batch-item-count]
  (when (seq batch)
    (let [n (count batch)]
      (try
        (proto/transact! (ensure-store!) batch)
        (swap! writer-metrics (fn [m]
                                (-> m
                                    (update :batches-flushed inc)
                                    (update :items-written + n)
                                    (update :largest-batch max n))))
        (catch Throwable t
          (log/error "Coalesced batch transact failed, falling back to individual writes"
                     {:batch-size n :error (.getMessage t)})
          ;; Fallback: retry items individually so we don't lose data
          (doseq [item batch]
            (try
              (proto/transact! (ensure-store!) [item])
              (swap! writer-metrics update :items-written inc)
              (catch Throwable t2
                (log/error "Individual fallback transact also failed"
                           {:item item :error (.getMessage t2)})
                (swap! writer-metrics update :items-dropped inc)))))
        (finally
          (swap! in-flight - batch-item-count))))))

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
                              [batch producer-count]
                              (loop [batch (into [] (dsl-batch/normalize-tx-datum first-item))
                                     producer-count 1
                                     remaining coalesce-window-ms]
                                (if (or (<= remaining 0)
                                        (>= (count batch) coalesce-max-batch))
                                  [batch producer-count]
                                  (let [t0 (System/currentTimeMillis)
                                        [item port] (async/alts! [ctrl-chan
                                                                  tx-chan
                                                                  (async/timeout remaining)])]
                                    (cond
                                      (= port ctrl-chan) [batch producer-count]
                                      (nil? item)        [batch producer-count]
                                      :else
                                      (recur (into batch (dsl-batch/normalize-tx-datum item))
                                             (inc producer-count)
                                             (- remaining (- (System/currentTimeMillis) t0)))))))]
                          (flush-batch! batch producer-count)
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

(defn flush-pending!
  "Busy-wait until the write-coalescing queue is empty and no items are in flight.
   Deterministic replacement for (Thread/sleep N) after transact! in tests.
   Bounded deadline prevents indefinite hang if the writer is dead — returns
   `:weave/timeout` after deadline-ms (default 5000ms) rather than blocking forever.
   Returns `:ok` when drained. No-op (returns `:ok`) if writer not running."
  ([] (flush-pending! 5000))
  ([deadline-ms]
   (if-not (:running? @writer-state)
     :ok
     (let [deadline (+ (System/currentTimeMillis) deadline-ms)]
       (loop []
         (cond
           (zero? @in-flight) :ok
           (> (System/currentTimeMillis) deadline)
           (do (log/warn "flush-pending! deadline exceeded, items still in flight:" @in-flight)
               :weave/timeout)
           :else
           (do (Thread/sleep 5)
               (recur))))))))

(defn drain-writer!
  "Deprecated — prefer flush-pending!. Retained as alias for callers and tests
   that still reference the old name."
  {:deprecated "use flush-pending!"}
  []
  (flush-pending!))

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
  "Close and reopen the active KG connection. NON-DESTRUCTIVE — does NOT
   delete data on disk. The same on-disk DB is re-attached for persistent
   stores (Datahike, Datalevin); in-memory stores (DataScript) get a fresh
   empty conn since there is no persistent backing.

   For destructive wipe, use `delete-database!` with `:i-mean-it`.

   Renamed semantics 2026-04-28 — see AXIOM 'Never NUKE Data'."
  []
  (proto/reset-conn! (ensure-store!)))

(defn delete-database!
  "DESTRUCTIVE — delete the active KG database from disk. Requires
   `confirm` to be `:i-mean-it`; any other value throws.

   Only persistent backends (`(satisfies? IPersistentKGStore store)`)
   support deletion. Calling against an ephemeral backend (DataScript)
   throws — destruction has no meaning when there is no persistent state.

   Test code that needs a fresh persistent store MUST create a temp
   directory (e.g. via `(System/getProperty \"java.io.tmpdir\")`) and
   call this only against that temp path, never the production data path.

   Emits high-severity telemetry events before and after deletion."
  [confirm]
  (let [store (ensure-store!)]
    (when-not (proto/persistent-store? store)
      (throw (ex-info "delete-database! not supported on ephemeral backend"
                      {:store-class (str (class store))
                       :hint "Ephemeral backends (DataScript) have no persistent state. Use reset-conn! for a fresh in-memory conn."})))
    (proto/delete-database! store confirm)))

(defn transact!
  "Transact data to the KG database.
   Priority:
     1. *tx-batch* bound (via with-tx-batch) → accumulate for explicit batch flush
     2. Otherwise → route through write-coalescing queue for automatic batching

   The coalescing queue drains items within a 25ms window and flushes
   as a single transaction. Combined with d/transact! (async) at the
   store level, this eliminates the 'Transacting 1 objects' pattern."
  [tx-data]
  (cond
    *tx-batch*
    (swap! *tx-batch* into (dsl-batch/normalize-tx-datum tx-data))

    *sync-writes*
    (r/rescue nil
              (proto/transact! (ensure-store!)
                               (dsl-batch/normalize-tx-datum tx-data)))

    :else
    (do
      (ensure-writer!)
      (let [tx-chan (:tx-chan @writer-state)]
        ;; Pre-increment BEFORE put! so flush-pending! never observes a
        ;; transient zero while an item is mid-enqueue. If put! fails we
        ;; compensate with a decrement on the fallback path.
        (swap! in-flight inc)
        (if (and tx-chan (async/put! tx-chan tx-data))
          nil
          ;; Channel full or closed — fallback to sync write
          (do
            (swap! in-flight dec)
            (log/warn "Write-coalescing queue put! failed, falling back to sync transact"
                      {:tx-data-count (if (sequential? tx-data) (count tx-data) 1)})
            (swap! writer-metrics update :items-dropped
                   + (if (sequential? tx-data) (count tx-data) 1))
            (r/rescue nil
                      (proto/transact! (ensure-store!)
                                       (dsl-batch/normalize-tx-datum tx-data)))))))))

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

(defn query-with-db
  "Execute a Datalog query against an EXPLICIT db value (snapshot)
   instead of the live conn — enables branch-compare verification by
   running the same query against pre-tx and post-tx db snapshots.

   Backends that share Datalog query shape (Datahike / DataScript /
   Datalevin) all expose `q` as a static fn that takes a db-value as
   first arg. We dispatch via requiring-resolve so this ns stays
   compile-time-decoupled from the chosen backend.

   Returns the query result, or falls back to the live `query` when no
   backend `q` fn can be resolved (no-op test stores)."
  [db q & inputs]
  (let [resolve-q (fn [sym]
                    (try (requiring-resolve sym) (catch Exception _ nil)))
        q-fn      (or (resolve-q 'datahike.api/q)
                      (resolve-q 'datascript.core/q)
                      (resolve-q 'datalevin.core/q))]
    (if (and q-fn db)
      (apply q-fn q db inputs)
      ;; Fallback: ignore db, query live store.
      (apply query q inputs))))

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
