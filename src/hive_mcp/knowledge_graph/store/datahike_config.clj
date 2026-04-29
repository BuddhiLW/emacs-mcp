;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.store.datahike-config
  "Typed config for the Datahike KG store, resolved via hive-di defconfig.

   Replaces ad-hoc `default-db-path` constants and scattered env+config.edn
   fallback chains. Single source of truth for: db-path, store-id, backend.

   Resolution per field (via hive-di coalesce):
     1. Explicit override map (caller passes :db-path key)
     2. HIVE_KG_DB_PATH / HIVE_KG_STORE_ID / HIVE_KG_DH_BACKEND env var
     3. ~/.config/hive-mcp/config.edn at [:services :datahike <key>]
     4. Hardcoded default

   Added 2026-04-28 for the live-KG-wipe post-mortem: prior code path
   ignored the config.edn :path key, so users couldn't relocate the store
   without code changes."
  (:require [hive-di.core :as di]
            [hive-di.source :as src]))

(def ^:const config-edn-path
  "Canonical hive-mcp config file. Each field's :file source reads from here."
  (str (System/getProperty "user.home") "/.config/hive-mcp/config.edn"))

(def ^:const default-db-path
  "XDG-conformant default. Avoids CWD-relative drift across launchers/REPLs."
  (str (System/getProperty "user.home") "/.local/share/hive-mcp/datahike"))

(di/defconfig DatahikeKGConfig
  :db-path  (src/coalesce
              [(src/env "HIVE_KG_DB_PATH" :required false)
               (src/file config-edn-path [:services :datahike :path]
                         :required false)]
              :default default-db-path
              :type :string
              :doc "Datahike file-store path. Override via env or config.edn :services :datahike :path.")

  :store-id (src/coalesce
              [(src/env "HIVE_KG_STORE_ID" :required false)
               (src/file config-edn-path [:services :datahike :id]
                         :required false)]
              :type :string
              :required false
              :doc "Datahike store UUID (string or #uuid). nil → caller derives from name 'hive-mcp-kg'.")

  :backend  (src/env "HIVE_KG_DH_BACKEND"
                     :default :file
                     :type :keyword
                     :doc "Datahike storage backend: :file or :memory."))
