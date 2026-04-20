(ns hive-mcp.chroma.core
  "Chroma vector database integration for semantic memory search.

   Thin facade that delegates to sub-modules for modularity (<200 LOC each):
   - hive-mcp.chroma.embeddings  — Embedding provider protocol and management
   - hive-mcp.chroma.connection  — Configuration, collections, status
   - hive-mcp.chroma.helpers     — Shared utilities (serialization, metadata)
   - hive-mcp.chroma.crud        — Memory entry CRUD operations
   - hive-mcp.chroma.search      — Semantic search
   - hive-mcp.chroma.maintenance — Cleanup and expiration

   All public vars are re-exported here as the canonical entry point.
   Require as: [hive-mcp.chroma.core :as chroma]."
  (:require [hive-mcp.chroma.embeddings :as emb]
            [hive-mcp.chroma.connection :as conn]
            [hive-mcp.chroma.gate :as gate]
            [hive-mcp.chroma.helpers :as h]
            [hive-mcp.chroma.crud :as crud]
            [hive-mcp.chroma.search :as search]
            [hive-mcp.chroma.maintenance :as maint]
            [hive-mcp.protocols.memory :as pmem]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn answer [] 42)

;;; --- Embedding Provider Protocol ---
(def EmbeddingProvider emb/EmbeddingProvider)
(def embed-text emb/embed-text)
(def embed-batch emb/embed-batch)
(def embedding-dimension emb/embedding-dimension)
(def set-embedding-provider! emb/set-embedding-provider!)
(def embedding-configured? emb/embedding-configured?)
(def get-embedding-provider emb/get-embedding-provider)
(def reset-embedding-provider! emb/reset-embedding-provider!)

;;; --- Collection-Aware Embedding API ---
(def get-provider-for emb/get-provider-for)
(def embed-text-for emb/embed-text-for)
(def embed-batch-for emb/embed-batch-for)
(def get-dimension-for emb/get-dimension-for)

;;; --- Configuration & Connection ---
(def configure! conn/configure!)
(def reset-collection-cache! conn/reset-collection-cache!)
(def status conn/status)
(def chroma-available? conn/chroma-available?)
(def reinitialize-embeddings! conn/reinitialize-embeddings!)

;;; --- Content Hashing ---
(def content-hash h/content-hash)

;;; --- CRUD Operations (dispatch via IMemoryStore when active store set) ---
;;; Legacy kwarg/positional signatures preserved. Active store → protocol.
;;; No active store (startup/edge cases) → concrete chroma.crud fallback.

(defn index-memory-entry!
  "Add an entry. Dispatches to active IMemoryStore via add-entry!."
  [entry]
  (if (pmem/store-set?)
    (pmem/add-entry! (pmem/get-store) entry)
    (crud/index-memory-entry! entry)))

(defn get-entry-by-id
  "Fetch entry by id. Dispatches to active IMemoryStore via get-entry."
  [id]
  (if (pmem/store-set?)
    (pmem/get-entry (pmem/get-store) id)
    (crud/get-entry-by-id id)))

(defn query-entries
  "Query entries. Legacy kwargs coerced to opts map for protocol."
  [& {:as opts}]
  (if (pmem/store-set?)
    (pmem/query-entries (pmem/get-store) opts)
    (apply crud/query-entries (mapcat identity opts))))

(defn update-entry!
  "Update entry fields. Dispatches to active IMemoryStore."
  [id updates]
  (if (pmem/store-set?)
    (pmem/update-entry! (pmem/get-store) id updates)
    (crud/update-entry! id updates)))

(defn delete-entry!
  "Delete entry by id. Dispatches to active IMemoryStore."
  [id]
  (if (pmem/store-set?)
    (pmem/delete-entry! (pmem/get-store) id)
    (crud/delete-entry! id)))

(defn find-duplicate
  "Find duplicate by content-hash. Kwargs form → opts map for protocol."
  [type content-hash & {:as opts}]
  (if (pmem/store-set?)
    (pmem/find-duplicate (pmem/get-store) type content-hash (or opts {}))
    (apply crud/find-duplicate type content-hash (mapcat identity (or opts {})))))

;;; Not yet in IMemoryStore protocol — keep concrete chroma.crud:
(def query-grounded-from crud/query-grounded-from)
(def update-staleness! crud/update-staleness!)
(def index-memory-entries! crud/index-memory-entries!)
(def collection-stats crud/collection-stats)

;;; --- Semantic Search ---

(defn search-similar
  "Semantic similarity search. Kwargs → opts map for protocol."
  [query-text & {:as opts}]
  (if (pmem/store-set?)
    (pmem/search-similar (pmem/get-store) query-text (or opts {}))
    (apply search/search-similar query-text (mapcat identity (or opts {})))))

(def search-by-id search/search-by-id)

;;; --- Maintenance ---

(defn cleanup-expired!
  "Delete all expired entries via active IMemoryStore."
  []
  (if (pmem/store-set?)
    (pmem/cleanup-expired! (pmem/get-store))
    (maint/cleanup-expired!)))

(defn entries-expiring-soon
  "Entries expiring within N days. Kwargs → opts map for protocol."
  [days & {:as opts}]
  (if (pmem/store-set?)
    (pmem/entries-expiring-soon (pmem/get-store) days (or opts {}))
    (apply maint/entries-expiring-soon days (mapcat identity (or opts {})))))

;;; --- Concurrency Gate ---
(def gate-stats gate/gate-stats)
