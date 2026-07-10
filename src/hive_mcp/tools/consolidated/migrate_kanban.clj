(ns hive-mcp.tools.consolidated.migrate-kanban
  "MCP boundary for the cross-store kanban migrator. Wraps
   `hive-mcp.tools.migrate.kanban.api` in command-shaped handlers and
   produces an MCP-compliant tool definition.

   Subcommands:
     init    — Phase A: list all candidate ids from source, persist
     step    — Run one batch (cursor-bounded)
     run     — Loop step until done or :max-steps reached
     status  — Read-only progress snapshot
     reset   — Wipe migration state (Phase A must rerun)"
  (:require [hive-dsl.result :as r]
            [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.migrate.kanban.api :as mig-api]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Result shaping for MCP
;; =============================================================================

(defn- result->mcp
  "Lift a Result map into the MCP `{:type \"text\" :text ...}` envelope.
   Error Results turn into `{:isError true ...}`."
  [result]
  (if (r/ok? result)
    {:type "text" :text (pr-str (:ok result))}
    {:isError true :type "text" :text (pr-str result)}))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn- cmd-init [_params]
  (result->mcp (mig-api/init-ids!)))

(defn- cmd-step [{:keys [batch_size dry_run]}]
  (result->mcp (mig-api/step! {:batch-size (or batch_size 50)
                                :dry-run?   (boolean dry_run)})))

(defn- cmd-run [{:keys [batch_size dry_run max_steps]}]
  (result->mcp (mig-api/run! {:batch-size (or batch_size 50)
                               :dry-run?   (boolean dry_run)
                               :max-steps  (or max_steps 50)})))

(defn- cmd-status [_params]
  (result->mcp (mig-api/status)))

(defn- cmd-reset [_params]
  (result->mcp (mig-api/reset!)))

(def handlers
  {:init    cmd-init
   :step    cmd-step
   :run     cmd-run
   :status  cmd-status
   :reset   cmd-reset})

(def handle-migrate-kanban
  (make-cli-handler handlers))

;; =============================================================================
;; Tool definition
;; =============================================================================

(def tool-def
  {:name         "migrate_kanban"
   :consolidated true
   :description  (str "Cross-store kanban migration (milvus :default → "
                      "qdrant :kanban). Subcommands: init (Phase A: list "
                      "candidate ids), step (one batch), run (loop until "
                      "done), status (progress snapshot), reset (wipe state). "
                      "Idempotent + resumable via on-disk cursor.")
   :inputSchema  {:type "object"
                  :properties
                  {"command"    {:type "string"
                                 :enum ["init" "step" "run" "status" "reset" "help"]
                                 :description "Migration operation"}
                   "batch_size" {:type "integer"
                                 :description "Ids per batch (default 50)"}
                   "dry_run"    {:type "boolean"
                                 :description "Skip writes; classify only"}
                   "max_steps"  {:type "integer"
                                 :description "Cap on step iterations for run (default 50)"}}
                  :required ["command"]}
   :handler      handle-migrate-kanban})

(def tools [tool-def])
