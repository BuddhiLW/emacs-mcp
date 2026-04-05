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
            [hive-mcp.chroma.maintenance :as maint]))

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

;;; --- CRUD Operations ---
(def index-memory-entry! crud/index-memory-entry!)
(def get-entry-by-id crud/get-entry-by-id)
(def query-entries crud/query-entries)
(def query-grounded-from crud/query-grounded-from)
(def update-entry! crud/update-entry!)
(def update-staleness! crud/update-staleness!)
(def find-duplicate crud/find-duplicate)
(def index-memory-entries! crud/index-memory-entries!)
(def delete-entry! crud/delete-entry!)
(def collection-stats crud/collection-stats)

;;; --- Semantic Search ---
(def search-similar search/search-similar)
(def search-by-id search/search-by-id)

;;; --- Maintenance ---
(def cleanup-expired! maint/cleanup-expired!)
(def entries-expiring-soon maint/entries-expiring-soon)

;;; --- Concurrency Gate ---
(def gate-stats gate/gate-stats)
