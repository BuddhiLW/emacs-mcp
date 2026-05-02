(ns hive-mcp.tools.migrate.kanban.api
  "Public boundary for the kanban migrator. Wires concrete adapters into
   a `deps` map and exposes a thin façade that callers (REPL users, MCP
   tool handler, scheduled jobs) consume.

   Default wiring:
     :source-lister  → milvus :default
     :source-reader  → milvus :default
     :target-reader  → qdrant :kanban
     :writer         → qdrant :kanban
     :state          → file at hive-mcp.tools.migrate.kanban.state/default-path

   Override any slot to swap stores (test stubs, alternate backends)."
  (:require [hive-dsl.result :as r]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.tools.migrate.kanban.events :as mig-events]
            [hive-mcp.tools.migrate.kanban.milvus :as mig-milvus]
            [hive-mcp.tools.migrate.kanban.qdrant :as mig-qdrant]
            [hive-mcp.tools.migrate.kanban.state :as mig-state]
            [hive-mcp.tools.migrate.kanban.usecases :as uc]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn make-deps
  "Build the dependency map. Pass overrides as kwargs to swap any slot."
  [& {:keys [source-store target-store state-path
             source-lister source-reader target-reader writer state]}]
  (mig-events/init!)
  (let [src-store (or source-store (proto/get-store))
        tgt-store (or target-store (proto/get-store :kanban))]
    {:source-lister (or source-lister (mig-milvus/make-id-lister src-store))
     :source-reader (or source-reader (mig-milvus/make-entry-reader src-store))
     :target-reader (or target-reader (mig-qdrant/make-entry-reader tgt-store))
     :writer        (or writer (mig-qdrant/make-entry-writer tgt-store))
     :state         (or state (mig-state/make (or state-path
                                                   mig-state/default-path)))}))

;; =============================================================================
;; Façade — each delegates to the use case with the wired deps
;; =============================================================================

(defn init-ids!
  "Phase A: list source ids and persist into state."
  ([] (init-ids! (make-deps)))
  ([deps] (uc/init-ids! deps)))

(defn step!
  "One migration batch."
  ([opts] (step! (make-deps) opts))
  ([deps opts] (uc/step! deps opts)))

(defn run!
  "Loop step! until done or `:max-steps` reached."
  ([opts] (run! (make-deps) opts))
  ([deps opts] (uc/run! deps opts)))

(defn status
  "Read-only progress snapshot."
  ([] (status (make-deps)))
  ([deps] (uc/status deps)))

(defn reset!
  "Wipe the state file and start over. Phase A must rerun afterwards."
  ([] (reset! (make-deps)))
  ([{:keys [state] :as _deps}]
   ((requiring-resolve 'hive-mcp.tools.migrate.kanban.ports/reset-state!) state)))
