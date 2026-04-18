(ns hive-mcp.protocols.memory
  "Protocol definitions for memory storage backends."
  (:require [clojure.string]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IMemoryStore Protocol (Core CRUD + Search)
;;; ============================================================================
;;;
;;; Reload-safety: `defprotocol` is NOT idempotent. Re-evaluating this form
;;; generates a fresh host interface class in the current classloader,
;;; silently invalidating every defrecord extender that was compiled against
;;; the previous interface. That shows up as `satisfies?` returning false
;;; and protocol dispatch failing with "No implementation of method ... for
;;; class: <record>" — the exact failure mode observed after L2 multi-store
;;; registry refactor when nREPL / addon load races caused the protocol ns
;;; to re-evaluate after addons had already compiled their stores.
;;;
;;; The `defonce`-guarded block below ensures `defprotocol` runs exactly
;;; once per JVM. On subsequent reloads of this namespace, the existing
;;; interface class and method Vars are preserved, so extenders compiled
;;; against them keep dispatching correctly.

(defonce ^:private -imemorystore-defined? (atom false))

(when (compare-and-set! -imemorystore-defined? false true)
  (defprotocol IMemoryStore
  "Storage backend protocol for memory entries."

  ;;; =========================================================================
  ;;; Connection Lifecycle
  ;;; =========================================================================

  (connect! [this config]
    "Initialize connection to the storage backend.")

  (disconnect! [this]
    "Close connection and release backend resources.")

  (connected? [this]
    "Check if this store has an active connection.")

  (health-check [this]
    "Verify backend health and reachability.")

;;; =========================================================================
  ;;; CRUD Operations
  ;;; =========================================================================

  (add-entry! [this entry]
    "Add a new memory entry to the store.")

  (get-entry [this id]
    "Get a memory entry by ID.")

  (update-entry! [this id updates]
    "Update an existing entry's attributes.")

  (delete-entry! [this id]
    "Delete an entry from the store.")

  (query-entries [this opts]
    "Query entries with filtering.")

  ;;; =========================================================================
  ;;; Semantic Search (Vector-based)
  ;;; =========================================================================

  (search-similar [this query-text opts]
    "Semantic similarity search.")

  (supports-semantic-search? [this]
    "Check if this store supports semantic search.")

  ;;; =========================================================================
  ;;; Expiration Management
  ;;; =========================================================================

  (cleanup-expired! [this]
    "Delete all expired entries.")

  (entries-expiring-soon [this days opts]
    "Get entries expiring within the given number of days.")

  ;;; =========================================================================
  ;;; Duplicate Detection
  ;;; =========================================================================

  (find-duplicate [this type content-hash opts]
    "Find entry with matching content-hash.")

  ;;; =========================================================================
  ;;; Store Management
  ;;; =========================================================================

  (store-status [this]
    "Get store status and configuration info.")

  (reset-store! [this]
    "Reset the store to empty state.")))

;;; ============================================================================
;;; Store Registry (Multi-Store)
;;; ============================================================================
;;;
;;; The registry maps named keys to IMemoryStore instances. The :default slot
;;; backs all legacy callers of `(get-store)`. Additional slots can host
;;; independent stores (e.g. cartography-scoped backends) without disturbing
;;; existing code.

(defonce ^:private store-registry (atom {}))

(defn register-store!
  "Register `store` under `key` in the multi-store registry.
   Returns the registered store."
  [key store]
  {:pre [(satisfies? IMemoryStore store)]}
  (swap! store-registry assoc key store)
  store)

(defn unregister-store!
  "Remove the store at `key`. No-op if absent. Does NOT disconnect
   the underlying store; callers are responsible for lifecycle."
  [key]
  (swap! store-registry dissoc key)
  nil)

(defn registered-stores
  "Return the current registry map {key -> store}. Read-only snapshot."
  []
  @store-registry)

(defn reset-registry!
  "Clear all entries from the registry. Intended for tests.
   Does NOT disconnect underlying stores."
  []
  (reset! store-registry {})
  nil)

(defn get-store
  "Get a memory store from the registry.
   0-arity: return the :default store, throw if none registered
            (backward-compatible with legacy callers).
   1-arity: return the store registered under `key`, throw if absent."
  ([]
   (or (:default @store-registry)
       (throw (ex-info "No default memory store registered. Call set-store! or register-store! :default first."
                       {:registry-keys (vec (keys @store-registry))
                        :hint "Initialize with chroma-store, milvus addon, or datascript-store"}))))
  ([key]
   (or (get @store-registry key)
       (throw (ex-info (str "Unknown memory store key: " key)
                       {:store-key key
                        :available (vec (keys @store-registry))})))))

(defn set-store!
  "Legacy single-store setter. Routes to the :default slot of the
   multi-store registry for backward compatibility with existing callers."
  [store]
  {:pre [(satisfies? IMemoryStore store)]}
  (register-store! :default store)
  store)

(defn store-set?
  "Check if a default memory store has been configured."
  []
  (some? (:default @store-registry)))

(defn reset-active-store!
  "Disconnect and clear the :default store. Leaves other registry
   entries untouched."
  []
  (when-let [store (:default @store-registry)]
    (try
      (disconnect! store)
      (catch Exception _)))
  (swap! store-registry dissoc :default)
  nil)

;;; ============================================================================
;;; Lifecycle Convenience Functions
;;; ============================================================================

(defn connect-active-store!
  "Connect the active store with the given config."
  [config]
  (connect! (get-store) config))

(defn active-store-healthy?
  "Check if the active store is connected and healthy."
  []
  (when (store-set?)
    (try
      (:healthy? (health-check (get-store)))
      (catch Exception _ false))))

(defn active-store-status
  "Get comprehensive status of the active store."
  []
  (when (store-set?)
    (let [store (get-store)]
      (merge (store-status store)
             (try (health-check store)
                  (catch Exception e
                    {:healthy? false :errors [(.getMessage e)]}))))))

;;; ============================================================================
;;; IMemoryStoreWithAnalytics Protocol (Optional Extension)
;;; ============================================================================
;;; Reload-safe: see note on IMemoryStore above.

(defonce ^:private -iwithanalytics-defined? (atom false))

(when (compare-and-set! -iwithanalytics-defined? false true)
  (defprotocol IMemoryStoreWithAnalytics
    "Optional extension for analytics tracking."

    (log-access! [this id]
      "Log an access event for an entry.")

    (record-feedback! [this id feedback]
      "Record helpfulness feedback for an entry.")

    (get-helpfulness-ratio [this id]
      "Calculate helpfulness ratio for an entry.")))

(defn analytics-store?
  "Check if the store supports analytics tracking."
  [store]
  (satisfies? IMemoryStoreWithAnalytics store))

;;; ============================================================================
;;; IMemoryStoreWithStaleness Protocol (Optional Extension)
;;; ============================================================================
;;; Reload-safe: see note on IMemoryStore above.

(defonce ^:private -iwithstaleness-defined? (atom false))

(when (compare-and-set! -iwithstaleness-defined? false true)
  (defprotocol IMemoryStoreWithStaleness
    "Optional extension for staleness tracking."

    (update-staleness! [this id staleness-opts]
      "Update staleness tracking fields for an entry.")

    (get-stale-entries [this threshold opts]
      "Get entries with staleness probability above threshold.")

    (propagate-staleness! [this source-id depth]
      "Propagate staleness from source entry to dependents.")))

(defn staleness-store?
  "Check if the store supports staleness tracking."
  [store]
  (satisfies? IMemoryStoreWithStaleness store))

;;; ============================================================================
;;; IMemoryStoreBatch Protocol (Optional Extension)
;;; ============================================================================
;;; Reload-safe: see note on IMemoryStore above.
;;;
;;; Batch fetch — single round-trip to the backend for multiple IDs. Introduced
;;; to collapse N per-item RPCs (e.g. catchup enrichment) into one call.
;;; Callers should prefer vectordb.facade/get-entries-by-ids which falls back
;;; to per-item get-entry for stores that don't implement this.

(defonce ^:private -iwithbatch-defined? (atom false))

(when (compare-and-set! -iwithbatch-defined? false true)
  (defprotocol IMemoryStoreBatch
    "Optional extension for batched reads."

    (get-entries [this ids]
      "Fetch multiple entries by ID in a single backend round-trip.
       Returns a seq of entry maps (missing IDs omitted). Order is not
       guaranteed — callers must index by :id.")))

(defn batch-store?
  "Check if the store supports batched reads."
  [store]
  (satisfies? IMemoryStoreBatch store))

(defn get-entries-projected
  "Batch-fetch entries by IDs, then trim to `output-fields` when provided.
   Projection is applied client-side so all IMemoryStoreBatch impls benefit
   without needing per-backend changes. When `output-fields` is nil,
   returns full entries (backward compat).

   output-fields: seq of field-name strings (e.g. [\"id\" \"type\" \"tags\"])."
  ([store ids]
   (get-entries store ids))
  ([store ids {:keys [output-fields]}]
   (let [entries (get-entries store ids)]
     (if (seq output-fields)
       (let [ks (set (map keyword output-fields))]
         (mapv #(select-keys % ks) entries))
       entries))))

;;; ============================================================================
;;; IMemoryStoreTemporal Protocol (Bitemporal Extension)
;;; ============================================================================
;;; Reload-safe: see note on IMemoryStore above.
;;; Only proximum implements this; other stores return :not-supported.

(defonce ^:private -iwithtemporal-defined? (atom false))

(when (compare-and-set! -iwithtemporal-defined? false true)
  (defprotocol IMemoryStoreTemporal
    "Bitemporal query extension for memory stores.
     Provides as-of, history, and between queries over immutable fact logs."

    (asof-entry [this id timestamp]
      "Return the value of entry `id` as it was known at `timestamp`.
       Returns nil if entry did not exist at that time.")

    (history-entry [this id]
      "Return seq of [timestamp value] pairs for all versions of entry `id`,
       ordered oldest-first.")

    (asof-query [this criteria timestamp]
      "Return entries matching `criteria` as they were known at `timestamp`.
       Criteria is a map with optional :type, :tags keys.")

    (between-query [this criteria t1 t2]
      "Return entries matching `criteria` that existed between t1 and t2.
       Criteria is a map with optional :type, :tags keys.")))

(defn temporal-store?
  "Check if the store supports bitemporal queries."
  [store]
  (satisfies? IMemoryStoreTemporal store))

;;; ============================================================================
;;; Utility Functions
;;; ============================================================================

(defn content-hash
  "Compute SHA-256 hash of normalized content."
  [content]
  (let [content-str (if (string? content) content (pr-str content))
        normalized (-> content-str
                       clojure.string/trim
                       (clojure.string/replace #"[ \t]+" " ")
                       (clojure.string/replace #"\n+" "\n"))
        md (java.security.MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest md (.getBytes normalized "UTF-8"))]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn generate-id
  "Generate a unique timestamped ID for memory entries."
  []
  (let [ts (java.time.LocalDateTime/now)
        fmt (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
        random-hex (format "%08x" (rand-int Integer/MAX_VALUE))]
    (str (.format ts fmt) "-" random-hex)))

(defn iso-timestamp
  "Return current ISO 8601 timestamp."
  []
  (str (java.time.ZonedDateTime/now
        (java.time.ZoneId/systemDefault))))
