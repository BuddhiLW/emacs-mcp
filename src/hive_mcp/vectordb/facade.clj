(ns hive-mcp.vectordb.facade
  "Backend-agnostic facade for memory operations.

   Provides the same function signatures as hive-mcp.chroma.core but delegates
   to the active IMemoryStore backend via protocols.memory/get-store.

   External projects (lsp-mcp, hive-agent) should requiring-resolve symbols
   from this namespace instead of hive-mcp.chroma.* to stay backend-independent.

   Function signatures match the chroma API callers expect (keyword args where
   chroma used keyword args) so resolve-site swaps are zero-change for callers."
  (:require [hive-mcp.protocols.memory :as proto]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; CRUD Operations
;;; ============================================================================

(defn index-memory-entry!
  "Index a memory entry via the active backend. Returns entry ID."
  [entry]
  (with-resilience
    (proto/add-entry! (proto/get-store) entry)))

(defn index-memory-entries!
  "Batch-index multiple memory entries via the active backend.
   Each entry is indexed through the store's add-entry! protocol method,
   which handles embedding and metadata persistence internally.

   Arguments:
     entries - sequential collection of entry maps (same shape as index-memory-entry!)

   Returns:
     Vector of entry IDs (one per input entry, positionally matched).
     Entries that fail individually are logged and returned as nil in that position.

   Callers (e.g. cartography/scan.clj) should chunk large batches themselves
   to avoid holding the store lock for extended periods."
  [entries]
  (let [store (proto/get-store)]
    (mapv (fn [entry]
            (try
              (with-resilience (proto/add-entry! store entry))
              (catch Exception e
                (log/warn "index-memory-entries!: entry failed:"
                          (:id entry) (ex-message e))
                nil)))
          entries)))

(defn get-entry-by-id
  "Get a memory entry by ID from the active backend."
  [id]
  (with-resilience
    (proto/get-entry (proto/get-store) id)))

(defn get-entries-by-ids
  "Batch-fetch memory entries by IDs from the active backend.

   Uses IMemoryStoreBatch/get-entries (single RPC) when the store supports
   it — the catchup enrichment hot path. Stores without batch support fall
   back to N per-item get-entry calls.

   Returns a vector of entry maps (missing IDs omitted). Order is not
   guaranteed; callers index by :id."
  [ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (when (seq ids)
      (let [store (proto/get-store)]
        (if (proto/batch-store? store)
          (with-resilience (vec (proto/get-entries store ids)))
          (vec (keep #(rescue nil (with-resilience (proto/get-entry store %))) ids)))))))

(defn query-entries
  "Query memory entries with filtering.
   Accepts keyword args for backward compat with chroma API.
   :output-fields — optional seq of field-name strings for projection
   (e.g. [\"id\" \"type\" \"tags\"]), passed through to the store backend."
  [& {:keys [type project-id project-ids tags exclude-tags limit
             include-expired? output-fields]
      :or {limit 100 include-expired? false}}]
  (with-resilience
    (proto/query-entries (proto/get-store)
                         (cond-> {:type             type
                                  :project-id       project-id
                                  :project-ids      project-ids
                                  :tags             tags
                                  :exclude-tags     exclude-tags
                                  :limit            limit
                                  :include-expired? include-expired?}
                           output-fields (assoc :output-fields output-fields)))))

(defn find-duplicate
  "Find entry with matching content-hash in the given type.
   Accepts keyword args for backward compat with chroma API."
  [type content-hash & {:keys [project-id]}]
  (with-resilience
    (proto/find-duplicate (proto/get-store) type content-hash
                          {:project-id project-id})))

;;; ============================================================================
;;; Semantic Search
;;; ============================================================================

(defn search-similar
  "Semantic similarity search via the active backend.
   Accepts keyword args for backward compat with chroma API."
  [query-text & {:keys [limit type project-ids exclude-tags]
                 :or {limit 10}}]
  (with-resilience
    (proto/search-similar (proto/get-store) query-text
                          {:limit        limit
                           :type         type
                           :project-ids  project-ids
                           :exclude-tags exclude-tags})))

;;; ============================================================================
;;; Mutation Operations
;;; ============================================================================

(defn update-entry!
  "Update an existing entry's attributes via the active backend."
  [id updates]
  (with-resilience
    (proto/update-entry! (proto/get-store) id updates)))

(defn delete-entry!
  "Delete an entry from the active backend."
  [id]
  (with-resilience
    (proto/delete-entry! (proto/get-store) id)))

;;; ============================================================================
;;; Utilities
;;; ============================================================================

(defn content-hash
  "Compute SHA-256 hash of content for deduplication.
   Pure function — delegates to protocols.memory/content-hash."
  [content]
  (proto/content-hash content))

(defn generate-id
  "Generate a unique timestamped ID for memory entries.
   Delegates to protocols.memory/generate-id."
  []
  (proto/generate-id))

(defn get-embedding-provider
  "Get the current embedding provider.
   Delegates to chroma.embeddings/get-embedding-provider (embedding
   config is backend-independent — lives outside IMemoryStore)."
  []
  (when-let [f (rescue nil (requiring-resolve 'hive-mcp.chroma.embeddings/get-embedding-provider))]
    (f)))

(defn embedding-configured?
  "Check if an embedding provider is configured and available.
   Delegates to chroma.embeddings/embedding-configured? (embedding
   config is backend-independent — lives outside IMemoryStore)."
  []
  (when-let [f (rescue nil (requiring-resolve 'hive-mcp.chroma.embeddings/embedding-configured?))]
    (f)))

(defn cleanup-expired!
  "Delete expired entries via the active backend."
  []
  (with-resilience
    (proto/cleanup-expired! (proto/get-store))))

(defn available?
  "Check if a memory store backend is configured and ready."
  []
  (proto/store-set?))
