(ns hive-mcp.vectordb.carto-facade
  "Backend-agnostic facade for cartography snippet operations.

   Mirrors hive-mcp.vectordb.facade exactly, but every call is routed to the
   store registered under the :carto slot via protocols.memory/get-store :carto.

   Cartography snippets (type=snippet, tags contain 'carto') live on a
   dedicated backend (typically qdrant via hive-qdrant addon) so they do not
   pollute the general memory store. Addon callers should requiring-resolve
   symbols from this namespace instead of the general facade.

   Function signatures match vectordb.facade so the swap is zero-change for
   callers."
  (:require [hive-mcp.protocols.memory :as proto]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; CRUD Operations
;;; ============================================================================

(defn index-memory-entry!
  "Index a carto snippet via the :carto backend. Returns entry ID."
  [entry]
  (proto/add-entry! (proto/get-store :carto) entry))

(defn index-memory-entries!
  "Batch-index multiple carto snippets via the :carto backend.
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
  (let [store (proto/get-store :carto)]
    (mapv (fn [entry]
            (try
              (proto/add-entry! store entry)
              (catch Exception e
                (log/warn "carto index-memory-entries!: entry failed:"
                          (:id entry) (ex-message e))
                nil)))
          entries)))

(defn get-entry-by-id
  "Get a carto snippet by ID from the :carto backend."
  [id]
  (proto/get-entry (proto/get-store :carto) id))

(defn query-entries
  "Query carto snippets with filtering.
   Accepts keyword args for backward compat with chroma API.
   Pass :include-content? true to opt in to the :content payload field —
   default omits it so catchup metadata reads stay cheap."
  [& {:keys [type project-id project-ids tags exclude-tags limit include-expired? include-content?]
      :or {limit 100 include-expired? false include-content? false}}]
  (proto/query-entries (proto/get-store :carto)
                       {:type             type
                        :project-id       project-id
                        :project-ids      project-ids
                        :tags             tags
                        :exclude-tags     exclude-tags
                        :limit            limit
                        :include-expired? include-expired?
                        :include-content? include-content?}))

(defn find-duplicate
  "Find carto snippet with matching content-hash in the given type.
   Accepts keyword args for backward compat with chroma API."
  [type content-hash & {:keys [project-id]}]
  (proto/find-duplicate (proto/get-store :carto) type content-hash
                        {:project-id project-id}))

;;; ============================================================================
;;; Semantic Search
;;; ============================================================================

(defn search-similar
  "Semantic similarity search over carto snippets via the :carto backend.
   Accepts keyword args for backward compat with chroma API."
  [query-text & {:keys [limit type project-ids exclude-tags]
                 :or {limit 10}}]
  (proto/search-similar (proto/get-store :carto) query-text
                        {:limit        limit
                         :type         type
                         :project-ids  project-ids
                         :exclude-tags exclude-tags}))

;;; ============================================================================
;;; Mutation Operations
;;; ============================================================================

(defn update-entry!
  "Update an existing carto snippet's attributes via the :carto backend."
  [id updates]
  (proto/update-entry! (proto/get-store :carto) id updates))

(defn delete-entry!
  "Delete a carto snippet from the :carto backend."
  [id]
  (proto/delete-entry! (proto/get-store :carto) id))

;;; ============================================================================
;;; Utilities
;;; ============================================================================

(defn content-hash
  "Compute SHA-256 hash of content for deduplication.
   Pure function — delegates to protocols.memory/content-hash."
  [content]
  (proto/content-hash content))

(defn generate-id
  "Generate a unique timestamped ID for carto snippets.
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
  "Delete expired carto snippets via the :carto backend."
  []
  (proto/cleanup-expired! (proto/get-store :carto)))

(defn available?
  "Check if a :carto memory store backend is registered and ready."
  []
  (some? (get (proto/registered-stores) :carto)))
