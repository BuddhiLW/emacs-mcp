(ns hive-mcp.vectordb.facade
  "Backend-agnostic facade for memory operations.

   Provides the same function signatures as hive-mcp.chroma.core but delegates
   to the active IMemoryStore backend via protocols.memory/get-store.

   External projects (lsp-mcp, hive-agent) should requiring-resolve symbols
   from this namespace instead of hive-mcp.chroma.* to stay backend-independent.

   Function signatures match the chroma API callers expect (keyword args where
   chroma used keyword args) so resolve-site swaps are zero-change for callers."
  (:require [hive-mcp.protocols.memory :as proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; CRUD Operations
;;; ============================================================================

(defn index-memory-entry!
  "Index a memory entry via the active backend. Returns entry ID."
  [entry]
  (proto/add-entry! (proto/get-store) entry))

(defn get-entry-by-id
  "Get a memory entry by ID from the active backend."
  [id]
  (proto/get-entry (proto/get-store) id))

(defn query-entries
  "Query memory entries with filtering.
   Accepts keyword args for backward compat with chroma API."
  [& {:keys [type project-id project-ids tags exclude-tags limit include-expired?]
      :or {limit 100 include-expired? false}}]
  (proto/query-entries (proto/get-store)
                       {:type             type
                        :project-id       project-id
                        :project-ids      project-ids
                        :tags             tags
                        :exclude-tags     exclude-tags
                        :limit            limit
                        :include-expired? include-expired?}))

(defn find-duplicate
  "Find entry with matching content-hash in the given type.
   Accepts keyword args for backward compat with chroma API."
  [type content-hash & {:keys [project-id]}]
  (proto/find-duplicate (proto/get-store) type content-hash
                        {:project-id project-id}))

;;; ============================================================================
;;; Semantic Search
;;; ============================================================================

(defn search-similar
  "Semantic similarity search via the active backend.
   Accepts keyword args for backward compat with chroma API."
  [query-text & {:keys [limit type project-ids exclude-tags]
                 :or {limit 10}}]
  (proto/search-similar (proto/get-store) query-text
                        {:limit        limit
                         :type         type
                         :project-ids  project-ids
                         :exclude-tags exclude-tags}))

;;; ============================================================================
;;; Mutation Operations
;;; ============================================================================

(defn update-entry!
  "Update an existing entry's attributes via the active backend."
  [id updates]
  (proto/update-entry! (proto/get-store) id updates))

(defn delete-entry!
  "Delete an entry from the active backend."
  [id]
  (proto/delete-entry! (proto/get-store) id))

;;; ============================================================================
;;; Utilities
;;; ============================================================================

(defn content-hash
  "Compute SHA-256 hash of content for deduplication.
   Pure function — delegates to protocols.memory/content-hash."
  [content]
  (proto/content-hash content))

(defn available?
  "Check if a memory store backend is configured and ready."
  []
  (proto/store-set?))
