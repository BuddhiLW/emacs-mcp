(ns hive-mcp.protocols.memory
  "IMemoryStore protocol family — re-exported from hive-spi.memory.ports.

   The protocols moved to the hive-spi SPI leaf so storage backends can
   implement them without compile-depending on hive-mcp. Every historical
   hive-mcp.protocols.memory/* qualified name still resolves via the plain
   `def` ALIASES below (never a second defprotocol). Predicates + the
   registry validator call `satisfies?` on the CANONICAL ports vars, not the
   local aliases: a protocol extended via extend-protocol mutates the ports
   var's root, so an alias snapshot would miss those impls.

   Registry (register-store!/get-store/set-store!) + id utils stay here."
  (:require [clojure.string]
            [hive-mcp.protocols.registry :as reg]
            [hive-mcp.memory.ids :as ids]
            [hive-spi.memory.ports :as ports]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; Protocol re-exports (def aliases of hive-spi.memory.ports — NOT defprotocol)
;;; ============================================================================

(do
  (def IMemoryStore ports/IMemoryStore)
  (def connect! ports/connect!)
  (def disconnect! ports/disconnect!)
  (def connected? ports/connected?)
  (def health-check ports/health-check)
  (def add-entry! ports/add-entry!)
  (def get-entry ports/get-entry)
  (def update-entry! ports/update-entry!)
  (def delete-entry! ports/delete-entry!)
  (def query-entries ports/query-entries)
  (def search-similar ports/search-similar)
  (def supports-semantic-search? ports/supports-semantic-search?)
  (def cleanup-expired! ports/cleanup-expired!)
  (def entries-expiring-soon ports/entries-expiring-soon)
  (def find-duplicate ports/find-duplicate)
  (def store-status ports/store-status)
  (def reset-store! ports/reset-store!))

;;; ============================================================================
;;; Store Registry (Multi-Store)
;;; ============================================================================
;;;
;;; The registry maps named keys to IMemoryStore instances. The :default slot
;;; backs all legacy callers of `(get-store)`. Additional slots can host
;;; independent stores (e.g. cartography-scoped backends) without disturbing
;;; existing code.

(defonce ^:private slot
  (reg/multi-slot {:validate #(satisfies? ports/IMemoryStore %)}))

(defn register-store!
  "Register `store` under `key` in the multi-store registry.
   Returns the registered store."
  [key store]
  (reg/reg-put! slot key store))

(defn unregister-store!
  "Remove the store at `key`. No-op if absent. Does NOT disconnect
   the underlying store; callers are responsible for lifecycle."
  [key]
  (reg/reg-remove! slot key))

(defn registered-stores
  "Return the current registry map {key -> store}. Read-only snapshot."
  []
  (reg/reg-snapshot slot))

(defn reset-registry!
  "Clear all entries from the registry. Intended for tests.
   Does NOT disconnect underlying stores."
  []
  (reg/reg-clear! slot))

(defn get-store
  "Get a memory store from the registry.
   0-arity: return the :default store, throw if none registered
            (backward-compatible with legacy callers).
   1-arity: return the store registered under `key`, throw if absent."
  ([]
   (or (:default (reg/reg-snapshot slot))
       (throw (ex-info "No default memory store registered. Call set-store! or register-store! :default first."
                       {:registry-keys (vec (keys (reg/reg-snapshot slot)))
                        :hint "Initialize with chroma-store, milvus addon, or datascript-store"}))))
  ([key]
   (or (get (reg/reg-snapshot slot) key)
       (throw (ex-info (str "Unknown memory store key: " key)
                       {:store-key key
                        :available (vec (keys (reg/reg-snapshot slot)))})))))

(defn set-store!
  "Legacy single-store setter. Routes to the :default slot of the
   multi-store registry for backward compatibility with existing callers."
  [store]
  {:pre [(satisfies? ports/IMemoryStore store)]}
  (register-store! :default store)
  store)

(defn store-set?
  "Check if a default memory store has been configured."
  []
  (some? (:default (reg/reg-snapshot slot))))

(defn reset-active-store!
  "Disconnect and clear the :default store. Leaves other registry
   entries untouched."
  []
  (when-let [store (:default (reg/reg-snapshot slot))]
    (try
      (disconnect! store)
      (catch Exception _)))
  (reg/reg-remove! slot :default)
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

;;; --- IMemoryStoreWithAnalytics ---

(do
  (def IMemoryStoreWithAnalytics ports/IMemoryStoreWithAnalytics)
  (def log-access! ports/log-access!)
  (def record-feedback! ports/record-feedback!)
  (def get-helpfulness-ratio ports/get-helpfulness-ratio))

(defn analytics-store?
  "Check if the store supports analytics tracking."
  [store]
  (satisfies? ports/IMemoryStoreWithAnalytics store))

;;; --- IMemoryStoreMetadataWrite (no-embed metadata writes) ---

(do
  (def IMemoryStoreMetadataWrite ports/IMemoryStoreMetadataWrite)
  (def update-metadata! ports/update-metadata!))

(defn metadata-write-store?
  "Check if the store supports the no-embed metadata write surface."
  [store]
  (satisfies? ports/IMemoryStoreMetadataWrite store))

;;; --- IMemoryStoreWithStaleness ---

(do
  (def IMemoryStoreWithStaleness ports/IMemoryStoreWithStaleness)
  (def update-staleness! ports/update-staleness!)
  (def get-stale-entries ports/get-stale-entries)
  (def propagate-staleness! ports/propagate-staleness!))

(defn staleness-store?
  "Check if the store supports staleness tracking."
  [store]
  (satisfies? ports/IMemoryStoreWithStaleness store))

;;; --- IMemoryStoreBatch (batched reads) ---

(do
  (def IMemoryStoreBatch ports/IMemoryStoreBatch)
  (def get-entries ports/get-entries))

(defn batch-store?
  "Check if the store supports batched reads."
  [store]
  (satisfies? ports/IMemoryStoreBatch store))

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

;;; --- IMemoryStoreWithRouting (multi-container routing) ---

(do
  (def IMemoryStoreWithRouting ports/IMemoryStoreWithRouting)
  (def target-collection-for ports/target-collection-for)
  (def relocate-entry! ports/relocate-entry!))

(defn routing-store?
  "Check if the store supports container-routing introspection + relocation."
  [store]
  (satisfies? ports/IMemoryStoreWithRouting store))

;;; --- IMemoryStoreTemporal (bitemporal queries) ---

(do
  (def IMemoryStoreTemporal ports/IMemoryStoreTemporal)
  (def asof-entry ports/asof-entry)
  (def history-entry ports/history-entry)
  (def asof-query ports/asof-query)
  (def between-query ports/between-query))

(defn temporal-store?
  "Check if the store supports bitemporal queries."
  [store]
  (satisfies? ports/IMemoryStoreTemporal store))

;;; IMemoryStoreLiveness lives in its own ns to keep this file from
;;; needing reloads. See `hive-mcp.protocols.memory-liveness`.

;;; ============================================================================
;;; Utility Functions
;;; ============================================================================

(def content-hash
  "Compute SHA-256 hash of normalized content."
  ids/content-hash)

(def generate-id
  "Generate a unique timestamped ID for memory entries."
  ids/generate-id)

(def iso-timestamp
  "Return current ISO 8601 timestamp."
  ids/iso-timestamp)