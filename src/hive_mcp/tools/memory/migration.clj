(ns hive-mcp.tools.memory.migration
  "Memory migration façade.

   Re-exports public API from submodules to preserve the external surface:
   - core:    project migration handlers (migrate-project, migrate-scoped, rename-project)
   - scope:   scope-tag migration (detect-orphaned, migrate-scope, hash-scope?)
   - import:  JSON import from legacy Emacs storage
   - backend: backend-to-backend migration (e.g. Chroma <-> Proximum)
   - helpers: shared utilities (.hive-project.edn IO, scope-tag rewriting)

   Convention 20260423151955-4faf4ffe: façade pattern — use `(def x sub/x)` for
   re-exports, keep ns short."
  (:require [hive-mcp.tools.memory.migration.core :as core]
            [hive-mcp.tools.memory.migration.scope :as mig-scope]
            [hive-mcp.tools.memory.migration.import :as mig-import]
            [hive-mcp.tools.memory.migration.backend :as backend]
            [hive-mcp.tools.memory.migration.helpers :as helpers]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Re-exports — public API
;; =============================================================================

;; Project migration
(def handle-migrate-project core/handle-migrate-project)
(def handle-migrate-scoped  core/handle-migrate-scoped)
(def handle-rename-project  core/handle-rename-project)

;; Scope-tag migration
(def handle-detect-orphaned mig-scope/handle-detect-orphaned)
(def handle-migrate-scope   mig-scope/handle-migrate-scope)
(def hash-scope?            helpers/hash-scope?)

;; JSON import
(def handle-import-json mig-import/handle-import-json)
(def import-entry!      mig-import/import-entry!)

;; Backend migration
(def migrate-backend! backend/migrate-backend!)

;; =============================================================================
;; Tool registrations
;; =============================================================================

(def tools
  [{:name "mcp_memory_migrate_project"
    :description "Migrate memory entries from one project-id to another. Updates project-id metadata and optionally scope tags."
    :inputSchema {:type "object"
                  :properties {:old-project-id {:type "string"
                                                :description "Current project-id to migrate from"}
                               :new-project-id {:type "string"
                                                :description "New project-id to migrate to"}
                               :update-scopes {:type "boolean"
                                               :description "Also update scope tags (default: false)"
                                               :default false}}
                  :required ["old-project-id" "new-project-id"]}
    :handler handle-migrate-project}

   {:name "mcp_memory_import_json"
    :description "Import memory entries from legacy JSON storage to Chroma. Use dry-run to preview."
    :inputSchema {:type "object"
                  :properties {:project-id {:type "string"
                                            :description "Project ID for imported entries"}
                               :dry-run {:type "boolean"
                                         :description "Preview without importing (default: false)"
                                         :default false}}}
    :handler handle-import-json}

   {:name "mcp_memory_detect_orphaned"
    :description "Detect orphaned hash-based scope tags in memory. Returns list of hash-based scopes that may need migration to name-based scopes. Use before migrate_scope."
    :inputSchema {:type "object"
                  :properties {}}
    :handler handle-detect-orphaned}

   {:name "mcp_memory_migrate_scope"
    :description "Migrate memory entries from old hash-based scope to new name-based scope. Use detect_orphaned first to find orphaned scopes. IMPORTANT: Use dry_run=true first to preview changes."
    :inputSchema {:type "object"
                  :properties {:old_scope {:type "string"
                                           :description "Old hash-based scope ID to migrate FROM (e.g., 'd987697ae05f40b1')"}
                               :new_scope {:type "string"
                                           :description "New name-based scope ID to migrate TO (e.g., 'funeraria')"}
                               :dry_run {:type "boolean"
                                         :description "Preview changes without modifying (default: true)"
                                         :default true}}
                  :required ["old_scope" "new_scope"]}
    :handler handle-migrate-scope}

   {:name "mcp_memory_migrate_scoped"
    :description "Migrate specific memory entries by ID or tag filter from one project to another. Updates project-id and scope tags per-entry. Preserves KG edges (only updates edge scope for edges where both endpoints are in the migrated set). Use dry-run to preview."
    :inputSchema {:type "object"
                  :properties {:entry-ids {:type "array"
                                           :items {:type "string"}
                                           :description "Specific entry IDs to migrate (takes priority over tag-filter)"}
                               :tag-filter {:type "string"
                                            :description "Tag to match entries for migration (e.g., 'payment-flow'). Used when entry-ids is empty."}
                               :old-project-id {:type "string"
                                                :description "Source project-id to migrate from"}
                               :new-project-id {:type "string"
                                                :description "Target project-id to migrate to"}
                               :dry-run {:type "boolean"
                                         :description "Preview changes without modifying (default: false)"
                                         :default false}}
                  :required ["old-project-id" "new-project-id"]}
    :handler handle-migrate-scoped}])
