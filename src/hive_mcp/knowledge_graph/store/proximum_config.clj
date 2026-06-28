;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.store.proximum-config
  "Typed config for the Proximum vector store, resolved via hive-di
   defconfig.

   Mirrors DatalevinKGConfig surface so the vector slot backend switch
   is a config flip, not a code change. Single source of truth for:
   :db-path (Konserve filestore directory) and :dim (embedding
   dimensionality).

   Resolution per field (via hive-di coalesce):
     1. Explicit override map (caller passes :db-path / :dim key)
     2. HIVE_VEC_PROXIMUM_PATH / HIVE_VEC_PROXIMUM_DIM env var
     3. ~/.config/hive-mcp/config.edn at [:services :proximum <key>]
     4. Hardcoded XDG default

   Added 2026-05-07 for STORAGE-2 phase 2: per-slot vector routing for
   the new :carto-vec and :memory-vec slots. Proximum (HNSW + Konserve)
   replaces the qdrant/milvus addons for in-process vector slots and
   gives us git-like branching / time-travel over the index."
  (:require [hive-di.core :as di]
            [hive-di.source :as src]))

(def ^:const config-edn-path
  "Canonical hive-mcp config file. Each field's :file source reads from
   here. Symmetric with DatalevinKGConfig and DatahikeKGConfig."
  (str (System/getProperty "user.home") "/.config/hive-mcp/config.edn"))

(def ^:const default-db-path
  "XDG-conformant default. Sibling subdir to the Datalevin / Datahike
   stores so operators can keep all backends side-by-side during the
   STORAGE migration."
  (str (System/getProperty "user.home") "/.local/share/hive-mcp/proximum"))

(def ^:const default-dim
  "Default embedding dimensionality. Matches sentence-transformers
   `all-MiniLM-L6-v2` (384) — the small-model fallback. Operators wiring
   in a larger embedder (Qwen3 4096, OpenAI 1536) override via env or
   config.edn :services :proximum :dim."
  384)

(di/defconfig ProximumKGConfig
  :db-path (src/coalesce
             [(src/env "HIVE_VEC_PROXIMUM_PATH" :required false)
              (src/file config-edn-path [:services :proximum :path]
                        :required false)]
             :default default-db-path
             :type :string
             :doc "Proximum Konserve filestore directory. Override via env or config.edn :services :proximum :path.")
  :dim (src/coalesce
         [(src/env "HIVE_VEC_PROXIMUM_DIM" :required false)
          (src/file config-edn-path [:services :proximum :dim]
                    :required false)]
         :default default-dim
         :type :long
         :doc "Vector dimensionality for the Proximum HNSW index. Override via env or config.edn :services :proximum :dim."))
