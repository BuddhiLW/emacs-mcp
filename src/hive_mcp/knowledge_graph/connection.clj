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
            [hive-dsl.batch :as dsl-batch]
            [hive-mcp.knowledge-graph.connection.store :as store]
            [hive-mcp.knowledge-graph.connection.writer :as writer]
            [hive-mcp.knowledge-graph.connection.strategy :as strategy]
            [hive-mcp.knowledge-graph.connection.temporal :as temporal]))

(declare ensure-writer! stop-writer! writer-stats flush-pending! drain-writer! in-flight)

(declare store-live? ensure-store! get-conn ensure-conn! ensure-conn reset-conn!
         delete-database! close! set-backend! backend-health)

;; =============================================================================
;; Config-based Backend Auto-detection
;; =============================================================================

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

;; in-flight: count of items enqueued on tx-chan but not yet flushed.
;; Incremented by transact! on successful put!, decremented by flush-batch!
;; after proto/transact! completes. Used by flush-pending! to detect drain.
;; =============================================================================
;; Backward-Compatible API
;; =============================================================================

;; Alias for ensure-conn! without the bang (for backward compatibility)
(defn transact!
  "Transact data to the KG database via the active write strategy:
     - *tx-batch* bound   → accumulate for an explicit batch flush
     - *sync-writes* true → immediate synchronous write
     - otherwise          → route through the write-coalescing queue

   The coalescing queue drains items within a 25ms window and flushes as a
   single transaction, eliminating the 'Transacting 1 objects' pattern."
  [tx-data]
  (strategy/assert-edge-node-ids! tx-data)
  (strategy/apply-tx! (strategy/select-strategy *tx-batch* *sync-writes* ensure-store!) tx-data))

(defn transact-sync!
  "Synchronous transact — bypasses the coalescing queue.
   Use ONLY when the caller needs the tx-report return value
   (e.g., extracting entity IDs from :tx-data).
   Most callers should use transact! (async coalesced) instead."
  [tx-data]
  (strategy/assert-edge-node-ids! tx-data)
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

;; =============================================================================
;; Store Configuration
;; =============================================================================

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

(def temporal-store? temporal/temporal-store?)

(def history-db temporal/history-db)

(def as-of-db temporal/as-of-db)

(def since-db temporal/since-db)

(def query-history temporal/query-history)

(def query-as-of temporal/query-as-of)

(def ^:private store-live? hive-mcp.knowledge-graph.connection.store/store-live?)

(def ^:private ensure-store! hive-mcp.knowledge-graph.connection.store/ensure-store!)

(def get-conn hive-mcp.knowledge-graph.connection.store/get-conn)

(def ensure-conn! hive-mcp.knowledge-graph.connection.store/ensure-conn!)

(def ensure-conn hive-mcp.knowledge-graph.connection.store/ensure-conn)

(def backend-health hive-mcp.knowledge-graph.connection.store/backend-health)

(def reset-conn! hive-mcp.knowledge-graph.connection.store/reset-conn!)

(def delete-database! hive-mcp.knowledge-graph.connection.store/delete-database!)

(def close! hive-mcp.knowledge-graph.connection.store/close!)

(def set-backend! hive-mcp.knowledge-graph.connection.store/set-backend!)

(def ^:private ensure-writer! hive-mcp.knowledge-graph.connection.writer/ensure-writer!)

(def stop-writer! hive-mcp.knowledge-graph.connection.writer/stop-writer!)

(def writer-stats hive-mcp.knowledge-graph.connection.writer/writer-stats)

(def flush-pending! hive-mcp.knowledge-graph.connection.writer/flush-pending!)

(def drain-writer! hive-mcp.knowledge-graph.connection.writer/drain-writer!)

;; in-flight: re-exported (^:private) because hive-ingestor's writer_guard
;; requiring-resolves connection/in-flight to observe queue depth; the canonical
;; counter lives in connection.writer.
(def ^:private in-flight hive-mcp.knowledge-graph.connection.writer/in-flight)
