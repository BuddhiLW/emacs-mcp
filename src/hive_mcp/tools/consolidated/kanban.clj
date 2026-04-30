(ns hive-mcp.tools.consolidated.kanban
  "Consolidated Kanban CLI tool."
  (:require [clojure.tools.logging :as log]
            [hive-mcp.tools.cli :refer [make-cli-handler make-batch-handler]]
            [hive-mcp.tools.memory-kanban :as mem-kanban]
            [hive-mcp.plan.tool :as plan-tool]))

(def ^:private batch-allowed-handlers
  "Handlers reachable from a kanban batch operation.

   Restricted to ID-keyed mutating commands — read ops (`list`, `status`)
   make no sense per-op. Adding more commands here is a deliberate decision."
  {:update mem-kanban/handle-mem-kanban-move
   :delete mem-kanban/handle-mem-kanban-delete})

(defn- with-default-command
  "Set :command on each op only when missing. Never overwrites a caller's
   explicit value — that was the silent-misroute bug (audit 20260429203443)."
  [operations default-cmd]
  (mapv (fn [op] (update op :command #(or % default-cmd))) operations))

(defn- handle-batch-update
  "Batch handler defaulting omitted :command to \"update\".

   Per-op :command is respected when supplied — pass :command \"delete\" to
   mix delete ops in. Unknown commands fail loudly per-op via the inner
   make-batch-handler (no silent misroute to update).

   Backward-compatible: callers passing only :task_id + :new_status keep
   working — :update fills in."
  [{:keys [operations] :as params}]
  (let [inner (make-batch-handler batch-allowed-handlers)]
    (inner (assoc params :operations (with-default-command operations "update")))))

(defn- handle-batch-delete
  "Batch handler defaulting omitted :command to \"delete\".

   Mirror of handle-batch-update. Use when sweeping many task ids:

     {:command \"batch-delete\"
      :operations [{:task_id \"id-1\"} {:task_id \"id-2\"} ...]}"
  [{:keys [operations] :as params}]
  (let [inner (make-batch-handler batch-allowed-handlers)]
    (inner (assoc params :operations (with-default-command operations "delete")))))

(def ^:private canonical-handlers
  {:list           mem-kanban/handle-mem-kanban-list-slim
   :create         mem-kanban/handle-mem-kanban-create
   :update         mem-kanban/handle-mem-kanban-move
   :delete         mem-kanban/handle-mem-kanban-delete
   :status         mem-kanban/handle-mem-kanban-stats
   :sync           (fn [_] {:success true :message "Memory kanban is single-backend, no sync needed"})
   :plan-to-kanban plan-tool/handle-plan-to-kanban
   :batch-update   handle-batch-update
   :batch-delete   handle-batch-delete})

(def ^:private deprecated-aliases
  {:move     :update
   :roadmap  :status
   :my-tasks :list})

(defn- wrap-deprecated
  [alias-key canonical-key handler-fn]
  (fn [params]
    (log/warn {:event :deprecation-warning
               :command (name alias-key)
               :canonical (name canonical-key)
               :message (str "DEPRECATED: '" (name alias-key)
                             "' is deprecated. Use '" (name canonical-key)
                             "' instead.")})
    (handler-fn params)))

(def handlers
  (merge canonical-handlers
         (reduce-kv (fn [m alias-key canonical-key]
                      (assoc m alias-key
                             (wrap-deprecated alias-key canonical-key
                                              (get canonical-handlers canonical-key))))
                    {}
                    deprecated-aliases)))

(def handle-kanban
  (make-cli-handler handlers))

(def tool-def
  {:name "kanban"
   :consolidated true
   :description "Kanban task management: list (all/filtered tasks), create (new task), update (change status/modify task), delete (hard-remove task by id; no archival, no completion — use for duplicates/cancellations), status (board overview + milestones), sync (backends), plan-to-kanban (convert plan to tasks, supports plan_id or plan_path), batch-update (bulk status changes; per-op :command respected — pass :command \"delete\" inside an op to mix delete in), batch-delete (sweep many task_ids; mirror of batch-update). Aliases (deprecated): move→update, roadmap→status, my-tasks→list. Use command='help' to list all. HCR: use include_descendants=true to aggregate descendant project tasks. List filters: query (substring), tags (extra required tags), tag_match (any|all), created_after / updated_after (ISO-8601), limit / offset (pagination), fields (projection)."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["list" "create" "move" "status" "update" "delete" "roadmap" "my-tasks" "sync" "plan-to-kanban" "batch-update" "batch-delete" "help"]
                                         :description "Kanban operation to perform"}
                              "status" {:type "string"
                                        :enum ["todo" "inprogress" "inreview" "done"]
                                        :description "Filter by task status"}
                              "title" {:type "string"
                                       :description "Task title for create"}
                              "description" {:type "string"
                                             :description "Task description"}
                              "task_id" {:type "string"
                                         :description "Task ID to move/update"}
                              "new_status" {:type "string"
                                            :enum ["todo" "inprogress" "inreview" "done"]
                                            :description "Target status for move"}
                              "plan_id" {:type "string"
                                         :description "Memory entry ID containing the plan (for plan-to-kanban)"}
                              "plan_path" {:type "string"
                                           :description "File path to a plan file (alternative to plan_id for plan-to-kanban). Slurps file content directly — zero-token plan loading for large plans."}
                              "operations" {:type "array"
                                            :items {:type "object"
                                                    :properties {"command"     {:type "string"
                                                                                :enum ["update" "delete"]
                                                                                :description "Per-op command override; defaults to the wrapper's verb (update for batch-update, delete for batch-delete)"}
                                                                 "task_id"     {:type "string"}
                                                                 "new_status"  {:type "string"
                                                                                :enum ["todo" "inprogress" "inreview" "done"]}
                                                                 "description" {:type "string"}}
                                                    :required ["task_id"]}
                                            :description "Array of operations for batch-update / batch-delete. Each op may specify :command (update|delete) to mix verbs in one batch; otherwise the wrapper's default applies."}
                              "directory" {:type "string"
                                           :description "Working directory for project scope (auto-detected if not provided)"}
                              "include_descendants" {:type "boolean"
                                                     :description "Include child project tasks in results (HCR Wave 4). Default true — set false to restrict to current project only."}
                              "project_id" {:type "string"
                                            :description "[list] Exact-match project filter (overrides directory-derived scope)"}
                              "query" {:type "string"
                                       :description "[list] Case-insensitive substring match on title + description"}
                              "tags" {:type "array" :items {:type "string"}
                                      :description "[list] Extra required tags beyond ['kanban' status]"}
                              "tag_match" {:type "string"
                                           :enum ["any" "all"]
                                           :description "[list] Tag match semantics for `tags` (default 'all')"}
                              "priority" {:type "string"
                                          :enum ["high" "medium" "low"]
                                          :description "[list] Filter by exact priority"}
                              "created_after" {:type "string"
                                               :description "[list] ISO-8601 timestamp; only entries with content :created >= this"}
                              "updated_after" {:type "string"
                                               :description "[list] ISO-8601 timestamp; only entries with :updated >= this"}
                              "limit" {:type "integer"
                                       :description "[list] Cap result count after sort"}
                              "offset" {:type "integer"
                                        :description "[list] Skip first N results after sort"}
                              "fields" {:type "array" :items {:type "string"}
                                        :description "[list] Project each result to a subset of fields (e.g. ['id' 'title'])"}
                              "parallel" {:type "boolean"
                                          :description "Run batch operations in parallel (default: false)"}}
                 :required ["command"]}
   :handler handle-kanban})

(def tools [tool-def])