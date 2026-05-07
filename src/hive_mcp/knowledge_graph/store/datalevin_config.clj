;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.store.datalevin-config
  "Typed config for the Datalevin KG store, resolved via hive-di defconfig.

   Mirrors DatahikeKGConfig surface so the connection.clj backend switch is
   a config flip, not a code change. Single source of truth for: db-path.

   Resolution per field (via hive-di coalesce):
     1. Explicit override map (caller passes :db-path key)
     2. HIVE_KG_DATALEVIN_PATH env var
     3. ~/.config/hive-mcp/config.edn at [:services :datalevin <key>]
     4. Hardcoded XDG default

   Added 2026-05-07 for STORAGE-1: Datalevin migration for the :carto KG
   slot. Datalevin (LMDB) has no .ksv.new -> .ksv rename race that corrupts
   konserve+datahike under the 51-scope concurrent scan fan-out."
  (:require [hive-di.core :as di]
            [hive-di.source :as src]))

(def ^:const config-edn-path
  "Canonical hive-mcp config file. Each field's :file source reads from here."
  (str (System/getProperty "user.home") "/.config/hive-mcp/config.edn"))

(def ^:const default-db-path
  "XDG-conformant default. Symmetric with Datahike default — pick the
   sibling subdir so operators can keep both stores side-by-side during
   migration."
  (str (System/getProperty "user.home") "/.local/share/hive-mcp/datalevin"))

(di/defconfig DatalevinKGConfig
  :db-path (src/coalesce
             [(src/env "HIVE_KG_DATALEVIN_PATH" :required false)
              (src/file config-edn-path [:services :datalevin :path]
                        :required false)]
             :default default-db-path
             :type :string
             :doc "Datalevin LMDB directory path. Override via env or config.edn :services :datalevin :path."))
