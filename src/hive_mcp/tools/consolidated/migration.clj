(ns hive-mcp.tools.consolidated.migration
  "Consolidated Migration CLI tool."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.migration :as migration-handlers]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def keyword-params
  "Params the MCP boundary must coerce from string to keyword before dispatch:
   the underlying handlers dispatch on them with `case`, which never matches a
   string. Downstream results therefore carry these params as keywords."
  #{:scope :target :adapter :latest})

(defn- coerce-params
  "Coerce string values to keywords for params used with case keyword dispatch."
  [params]
  (reduce (fn [m k]
            (let [v (get m k)]
              (if (string? v)
                (assoc m k (keyword v))
                m)))
          params
          keyword-params))

(def handlers
  {:status   migration-handlers/cmd-status
   :backup   migration-handlers/cmd-backup
   :restore  migration-handlers/cmd-restore
   :list     migration-handlers/cmd-list
   :switch   migration-handlers/cmd-switch
   :sync     migration-handlers/cmd-sync
   :export   migration-handlers/cmd-export
   :import   migration-handlers/cmd-import
   :validate migration-handlers/cmd-validate
   :adapters migration-handlers/cmd-adapters})

(def handle-migration
  "Coerces string params to keywords before dispatch for MCP compatibility."
  (let [cli-handler (make-cli-handler handlers)]
    (fn [params]
      (cli-handler (coerce-params params)))))

(def tool-def
  {:name "migration"
   :consolidated true
   :description "KG/Memory migration: status (backend info), backup (timestamped snapshot), restore (from backup), list (available backups), switch (change backend), sync (mirror to secondary), export/import (with adapters), validate (check integrity), adapters (list available). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["status" "backup" "restore" "list" "switch" "sync" "export" "import" "validate" "adapters" "help"]
                                         :description "Migration operation to perform"}
                              "scope" {:type "string"
                                       :enum ["kg" "memory" "full"]
                                       :description "Scope for backup/export (default: kg)"}
                              "dir" {:type "string"
                                     :description "Backup directory (default: data/backups)"}
                              "adapter" {:type "string"
                                         :enum ["edn" "json"]
                                         :description "Export/import adapter (default: edn)"}
                              "path" {:type "string"
                                      :description "Backup file path (for restore/validate/export/import)"}
                              "latest" {:type "string"
                                        :enum ["kg" "memory"]
                                        :description "Restore latest backup for scope"}
                              "dry-run" {:type "boolean"
                                         :description "Preview without applying (default: false)"}
                              "target" {:type "string"
                                        :enum ["datascript" "datalevin" "datahike"]
                                        :description "Target backend for switch/sync"}
                              "db-path" {:type "string"
                                         :description "Database path for persistent backends"}
                              "backup" {:type "boolean"
                                        :description "Create backup before switch (default: true)"}
                              "backend" {:type "string"
                                         :description "Filter backups by backend"}
                              "limit" {:type "integer"
                                       :description "Max results (default: 20)"}
                              "directory" {:type "string"
                                           :description "Working directory for project-id derivation (Go ctx pattern)"}
                              "project-id" {:type "string"
                                            :description "Explicit project-id for scope filtering (backup/restore/list)"}
                              "force-cross-project" {:type "boolean"
                                                     :description "Allow restoring backup from different project (default: false)"}}
                 :required ["command"]}
   :handler handle-migration})

(def tools [tool-def])
