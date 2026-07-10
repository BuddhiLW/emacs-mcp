(ns hive-mcp.tools.consolidated.project
  "Consolidated project tool — absorbs kanban, config, session, workflow.

   Project's own commands stay flat (info, files, search, etc.).
   Absorbed domains use nested prefixes: 'kanban list', 'config get', etc.
   Addons can extend via contribute-commands! \"project\"."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.core :as tcore]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.tools.projectile :as projectile-handlers]
            [hive-mcp.project.tree :as tree]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.dns.result :as result]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ── Pure Result-returning functions ───────────────────────────────────────────

(defn- scan*
  [{:keys [directory max_depth force]}]
  (let [effective-dir (or directory (ctx/current-directory) ".")
        opts (cond-> {}
               max_depth (assoc :max-depth max_depth)
               force (assoc :force force))]
    (log/info "project scan" {:directory effective-dir :opts opts})
    (result/ok (tree/scan-project-tree! effective-dir opts))))

(defn- tree*
  [{:keys [project_id]}]
  (log/info "project tree" {:project-id project_id})
  (let [all-projects (tree/query-all-projects)
        tree-data (tree/build-project-tree all-projects)]
    (if project_id
      (let [descendants (tree/get-descendants tree-data project_id)
            ancestors (tree/get-ancestors tree-data project_id)
            project (tree/query-project-by-id project_id)]
        (result/ok {:project project
                    :ancestors ancestors
                    :descendants descendants
                    :children (get (:children tree-data) project_id [])}))
      (result/ok {:roots (:roots tree-data)
                  :total-projects (count all-projects)
                  :children (:children tree-data)
                  :projects (mapv #(select-keys % [:project/id :project/path :project/type :project/parent-id])
                                  all-projects)}))))

(defn- staleness*
  [{:keys [directory]}]
  (let [effective-dir (or directory (ctx/current-directory) ".")]
    (log/info "project staleness" {:directory effective-dir})
    (result/ok {:stale (tree/tree-stale? effective-dir)
                :directory effective-dir})))

;; ── Public handlers (MCP boundary) ────────────────────────────────────────────

(defn handle-project-scan [params]
  (rb/result->mcp (rb/try-result :project/scan-failed #(scan* params))))

(defn handle-project-tree [params]
  (rb/result->mcp (rb/try-result :project/tree-failed #(tree* params))))

(defn handle-project-staleness [params]
  (rb/result->mcp (rb/try-result :project/staleness-failed #(staleness* params))))

;; ── Core project handlers ────────────────────────────────────────────────

(def project-handlers
  {:info      projectile-handlers/handle-projectile-info
   :files     projectile-handlers/handle-projectile-files
   :search    projectile-handlers/handle-projectile-search
   :find      projectile-handlers/handle-projectile-find-file
   :recent    projectile-handlers/handle-projectile-recent
   :list      projectile-handlers/handle-projectile-list-projects
   :scan      handle-project-scan
   :tree      handle-project-tree
   :staleness handle-project-staleness})

;; =============================================================================
;; Canonical Handlers — project flat + kanban/config/session/workflow nested
;; =============================================================================

(defn- lazy-subrouter
  "Nest a folded tool whose handler is itself a command router (it `case`s on
   :command) as a `:_handler` leaf. Strips the subdomain prefix from :command
   before delegating, so `project transcript list` reaches the inner router as
   command=\"list\". Resolves the target fn lazily (DIP — no compile coupling)."
  [subdomain sym]
  {:_handler
   (fn [params]
     (let [full (str (:command params))
           pfx  (str subdomain " ")
           sub  (if (str/starts-with? full pfx) (subs full (count pfx)) full)]
       (if-let [h (try (requiring-resolve sym) (catch Throwable _ nil))]
         (h (assoc params :command sub))
         (tcore/mcp-error (str subdomain " not available (handler unresolved)")))))})

(def canonical-handlers
  ;; Subdomain handler trees resolved lazily via composite/lazy-resolve-handlers
  ;; — the four `c-kanban`/`c-config`/`c-session`/`c-workflow` static :requires
  ;; that previously coupled this ns to those consolidators are gone. Same
  ;; nested handler-tree shape; same dispatch behaviour at runtime.
  (merge project-handlers
         {:kanban   (composite/lazy-resolve-handlers 'hive-mcp.tools.consolidated.kanban/handlers)
          :config   (composite/lazy-resolve-handlers 'hive-mcp.tools.consolidated.config/handlers)
          :session  (composite/lazy-resolve-handlers 'hive-mcp.tools.consolidated.session/handlers)
          :workflow (composite/lazy-resolve-handlers 'hive-mcp.tools.consolidated.workflow/handlers)
          ;; Folded standalone roots re-exposed as ergonomic subdomains.
          ;; migrate-kanban/handlers is a flat leaf map → lazy-resolve directly.
          ;; transcript routes on :command → wrap with prefix-strip subrouter.
          :migrate-kanban (composite/lazy-resolve-handlers 'hive-mcp.tools.consolidated.migrate-kanban/handlers)
          :transcript     (lazy-subrouter "transcript" 'hive-mcp.tools.consolidated.transcript/handle-transcript)}))

;; Keep backward compat alias
(def handlers canonical-handlers)

(def handle-project
  (composite/build-merged-handler "project" canonical-handlers))

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tool-def
  {:name "project"
   :consolidated true
   :description "Projectile project operations: info (project details), files (list files), search (content search), find (find by filename), recent (recently visited), list (all projects), scan (discover .hive-project.edn hierarchy), tree (query cached hierarchy), staleness (check if rescan needed). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :description "Project operation. Subdomains: 'kanban list', 'kanban get' (single task by id, unified across kanban + default memory stores), 'kanban retag', 'config get', 'session wrap', 'workflow forge strike', 'migrate-kanban status', 'transcript list'. Use command='help' to list all."}
                              ;; Project params
                              "pattern" {:type "string"
                                         :description "Glob pattern or search pattern"}
                              "filename" {:type "string"
                                          :description "Filename to search for"}
                              "directory" {:type "string"
                                           :description "Working directory for project scope"}
                              "max_depth" {:type "integer"
                                           :description "Maximum scan depth (default: 5)"}
                              "force" {:type "boolean"
                                       :description "Force rescan even if fresh"}
                              "project_id" {:type "string"
                                            :description "Project ID to query tree for / [kanban list] exact-match project filter / [kanban retag] alias for new_project_id"}
                              ;; Kanban params
                              "title" {:type "string" :description "[kanban create] Task title"}
                              "description" {:type "string" :description "[kanban create] Task description"}
                              "task_id" {:type "string" :description "[kanban get|update|delete|retag] Task ID"}
                              "id" {:type "string" :description "[kanban get] Alias for task_id — entry id to fetch from either store"}
                              "new_status" {:type "string"
                                            :enum ["todo" "inprogress" "inreview" "done"]
                                            :description "[kanban update] Target status"}
                              "new_project_id" {:type "string"
                                                :description "[kanban retag] Target project scope (preserves entry id + KG edges)"}
                              "add_tags" {:type "array" :items {:type "string"}
                                          :description "[kanban retag] Extra tags to add"}
                              "remove_tags" {:type "array" :items {:type "string"}
                                             :description "[kanban retag] Tags to remove"}
                              "status" {:type "string"
                                        :enum ["todo" "inprogress" "inreview" "done"]
                                        :description "[kanban create] Initial status (default: todo) / [kanban list] Filter by status. Aliases inprogress→doing, inreview→review are normalized."}
                              "include_descendants" {:type "boolean"
                                                     :description "[kanban] Include DESCENDANT (child) project tasks (HCR). Default true. Ancestor (parent) tasks are ALWAYS included regardless of this flag ('child sees parent')."}
                              "scope" {:type "string"
                                       :enum ["all"]
                                       :description "[kanban list/status] scope=\"all\" lifts the project filter — whole board across EVERY workspace (opt-in cross-workspace view). Omit for the default scoped view (current project + ancestors [+ descendants])."}
                              "plan_id" {:type "string" :description "[kanban plan-to-kanban] Memory plan entry ID"}
                              "plan_path" {:type "string" :description "[kanban plan-to-kanban] File path to plan"}
                              ;; Kanban list filters (token-flood reduction)
                              "query" {:type "string"
                                       :description "[kanban list] Case-insensitive substring match on title + description"}
                              "tags" {:type "array" :items {:type "string"}
                                      :description "[kanban list] Extra required tags beyond ['kanban' status]"}
                              "tag_match" {:type "string"
                                           :enum ["any" "all"]
                                           :description "[kanban list] Tag match semantics for `tags` (default 'all')"}
                              "priority" {:type "string"
                                          :enum ["high" "medium" "low"]
                                          :description "[kanban list] Filter by exact priority"}
                              "created_after" {:type "string"
                                               :description "[kanban list] ISO-8601 timestamp; only entries with content :created >= this"}
                              "updated_after" {:type "string"
                                               :description "[kanban list] ISO-8601 timestamp; only entries with :updated >= this"}
                              "limit" {:type "integer"
                                       :description "[kanban list] Cap result count after sort (pagination)"}
                              "offset" {:type "integer"
                                        :description "[kanban list] Skip first N results after sort (pagination)"}
                              "fields" {:type "array" :items {:type "string"}
                                        :description "[kanban list] Project each result to a subset of fields (e.g. ['id' 'title'])"}
                              ;; Config params
                              "key" {:type "string" :description "[config] Dotted key path (e.g. \"embeddings.ollama.host\")"}
                              "value" {:description "[config set] Value to set"}
                              ;; Session params
                              "commit_msg" {:type "string" :description "[session complete] Git commit message"}
                              "task_ids" {:type "array" :items {:type "string"}
                                          :description "[session/workflow] Kanban task IDs to mark done"}
                              "agent_id" {:type "string" :description "[session/workflow] Agent ID"}
                              ;; Workflow params
                              "task_filter" {:type "string" :description "[workflow forge] Title prefix filter for survey"}
                              ;; Shared
                              "operations" {:type "array" :items {:type "object"}
                                            :description "Array of operations for batch commands"}
                              "parallel" {:type "boolean"
                                          :description "Run batch operations in parallel (default: false)"}}
                 :required ["command"]}
   :handler handle-project})

(def tools [tool-def])
