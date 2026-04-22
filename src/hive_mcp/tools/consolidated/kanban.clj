(ns hive-mcp.tools.consolidated.kanban
  "Consolidated Kanban CLI tool."
  (:require [clojure.tools.logging :as log]
            [hive-mcp.tools.cli :refer [make-cli-handler make-batch-handler]]
            [hive-mcp.tools.memory-kanban :as mem-kanban]
            [hive-mcp.plan.tool :as plan-tool]))

(defn- handle-batch-update
  "Batch update handler: injects :command \"update\" into each operation."
  [{:keys [operations] :as params}]
  (let [inner (make-batch-handler {:update mem-kanban/handle-mem-kanban-move})]
    (inner (assoc params :operations
                  (mapv #(assoc % :command "update") operations)))))

(def ^:private canonical-handlers
  {:list           mem-kanban/handle-mem-kanban-list-slim
   :create         mem-kanban/handle-mem-kanban-create
   :update         mem-kanban/handle-mem-kanban-move
   :delete         mem-kanban/handle-mem-kanban-delete
   :status         mem-kanban/handle-mem-kanban-stats
   :sync           (fn [_] {:success true :message "Memory kanban is single-backend, no sync needed"})
   :plan-to-kanban plan-tool/handle-plan-to-kanban
   :batch-update   handle-batch-update})

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
   :description "Kanban task management: list (all/filtered tasks), create (new task), update (change status/modify task), delete (hard-remove task by id; no archival, no completion — use for duplicates/cancellations), status (board overview + milestones), sync (backends), plan-to-kanban (convert plan to tasks, supports plan_id or plan_path), batch-update (bulk status changes). Aliases (deprecated): move→update, roadmap→status, my-tasks→list. Use command='help' to list all. HCR: use include_descendants=true to aggregate descendant project tasks."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["list" "create" "move" "status" "update" "delete" "roadmap" "my-tasks" "sync" "plan-to-kanban" "batch-update" "help"]
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
                                                    :properties {"task_id" {:type "string"}
                                                                 "new_status" {:type "string"
                                                                               :enum ["todo" "inprogress" "inreview" "done"]}
                                                                 "description" {:type "string"}}
                                                    :required ["task_id"]}
                                            :description "Array of update operations for batch-update command"}
                              "directory" {:type "string"
                                           :description "Working directory for project scope (auto-detected if not provided)"}
                              "include_descendants" {:type "boolean"
                                                     :description "Include child project tasks in results (HCR Wave 4). Default true — set false to restrict to current project only."}
                              "parallel" {:type "boolean"
                                          :description "Run batch operations in parallel (default: false)"}}
                 :required ["command"]}
   :handler handle-kanban})

(def tools [tool-def])