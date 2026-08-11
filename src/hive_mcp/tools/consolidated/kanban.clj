(ns hive-mcp.tools.consolidated.kanban
  "Consolidated Kanban CLI tool."
  (:require [clojure.tools.logging :as log]
            [hive-mcp.tools.cli :refer [make-cli-handler make-batch-handler]]
            [hive-mcp.tools.memory-kanban :as mem-kanban]
            [hive-mcp.plan.tool :as plan-tool]))

(def ^:private batch-allowed-handlers
  "Handlers reachable from a kanban batch operation.

   Restricted to ID-keyed mutating commands — read ops (`list`, `status`)
   make no sense per-op. Adding more commands here is a deliberate decision.

   PR4.4 — :create added so batch-create can sweep many task titles at once.
   :retag added — scope-move + ±tags batches preserve entry id + KG edges."
  {:update mem-kanban/handle-mem-kanban-move
   :delete mem-kanban/handle-mem-kanban-delete
   :create mem-kanban/handle-mem-kanban-create
   :retag  mem-kanban/handle-mem-kanban-retag})

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

(defn- handle-batch-create
  "Batch handler defaulting omitted :command to \"create\".

   Sweep many titles at once:

     {:command \"batch-create\"
      :operations [{:title \"task-1\" :description \"...\"}
                   {:title \"task-2\"} ...]}"
  [{:keys [operations] :as params}]
  (let [inner (make-batch-handler batch-allowed-handlers)]
    (inner (assoc params :operations (with-default-command operations "create")))))

(defn- handle-batch-retag
  "Batch handler defaulting omitted :command to \"retag\".

   Sweep many task scope-moves in one call:

     {:command \"batch-retag\"
      :operations [{:task_id \"id-1\" :new_project_id \"probe\"}
                   {:task_id \"id-2\" :new_project_id \"probe\"
                    :add_tags [\"epic:adapter\"]}]}"
  [{:keys [operations] :as params}]
  (let [inner (make-batch-handler batch-allowed-handlers)]
    (inner (assoc params :operations (with-default-command operations "retag")))))

(def ^:private canonical-handlers
  {:list           mem-kanban/handle-mem-kanban-list-slim
   :get            mem-kanban/handle-mem-kanban-get
   :create         mem-kanban/handle-mem-kanban-create
   :update         mem-kanban/handle-mem-kanban-move
   :delete         mem-kanban/handle-mem-kanban-delete
   :status         mem-kanban/handle-mem-kanban-stats
   :retag          mem-kanban/handle-mem-kanban-retag
   :sync           (fn [_] {:success true :message "Memory kanban is single-backend, no sync needed"})
   :plan-to-kanban plan-tool/handle-plan-to-kanban
   :plan-schema    plan-tool/handle-plan-schema
   :batch-update   handle-batch-update
   :batch-delete   handle-batch-delete
   :batch-create   handle-batch-create
   :batch-retag    handle-batch-retag})

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
   :description "Kanban task management: list (all/filtered tasks), get (single task/entry by id — unified across the kanban store AND the default memory store, since they are separate backends; surfaces full fields + KG edges; on miss returns semantic-search suggestions), create (new task), update (move status via new_status and/or edit title/description/priority in place; both in one call move first, then edit), delete (hard-remove task by id; no archival, no completion — use for duplicates/cancellations), retag (scope-move via project_id + optional ±tags; preserves entry id + KG edges, no re-embed), status (board overview + milestones), sync (backends), plan-to-kanban (convert plan to tasks, supports plan_id or plan_path), plan-schema (the plan-memory EDN contract: JSON-schema + example + how-to, so a type=plan memory can be authored without reading source), batch-update (bulk status changes; per-op :command respected — pass :command \"delete\" inside an op to mix delete in), batch-delete (sweep many task_ids; mirror of batch-update), batch-retag (sweep many scope-moves). Aliases (deprecated): move→update, roadmap→status, my-tasks→list. Use command='help' to list all. HCR: a list shows its own scope + ANCESTORS (parent tasks, always — 'child sees parent'); include_descendants=true (default) also aggregates DESCENDANT (child) project tasks; scope=\"all\" lifts the project filter entirely for a cross-workspace whole-board view. List filters: query (substring), tags (extra required tags), tag_match (any|all), created_after / updated_after (ISO-8601), limit / offset (pagination), fields (projection)."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["list" "get" "create" "move" "status" "update" "delete" "retag" "roadmap" "my-tasks" "sync" "plan-to-kanban" "plan-schema" "batch-update" "batch-delete" "batch-create" "batch-retag" "help"]
                                         :description "Kanban operation to perform"}
                              "status" {:type "string"
                                        :enum ["todo" "inprogress" "inreview" "done"]
                                        :description "Filter by task status"}
                              "title" {:type "string"
                                       :description "[create|update] Task title. On update a non-blank value replaces the current title in place — entry id, KG edges, status and scope are preserved."}
                              "description" {:type "string"
                                             :description "[create|update] Task description. On update a non-blank value replaces the current description in place."}
                              "task_id" {:type "string"
                                         :description "Task ID to get/move/update/retag/delete"}
                              "id" {:type "string"
                                    :description "[get] Alias for task_id — entry id to fetch (kanban store or default memory store)"}
                              "new_status" {:type "string"
                                            :enum ["todo" "inprogress" "inreview" "done"]
                                            :description "Target status for move"}
                              "new_project_id" {:type "string"
                                                :description "[retag] Target project scope (replaces existing scope:project:* tag, preserves entry id + KG edges)"}
                              "add_tags" {:type "array" :items {:type "string"}
                                          :description "[retag] Extra tags to add (deduplicated)"}
                              "remove_tags" {:type "array" :items {:type "string"}
                                             :description "[retag] Tags to remove (applied after add)"}
                              "plan_id" {:type "string"
                                         :description "Memory entry ID containing the plan (for plan-to-kanban). Call command='plan-schema' for the plan EDN contract (schema + example + how-to)."}
                              "plan_path" {:type "string"
                                           :description "File path to a plan file (alternative to plan_id for plan-to-kanban). Slurps file content directly — zero-token plan loading for large plans."}
                              "operations" {:type "array"
                                            :items {:type "object"
                                                    :properties {"command"        {:type "string"
                                                                                   :enum ["update" "delete" "create" "retag"]
                                                                                   :description "Per-op command override; defaults to the wrapper's verb"}
                                                                 "task_id"        {:type "string"}
                                                                 "new_status"     {:type "string"
                                                                                   :enum ["todo" "inprogress" "inreview" "done"]}
                                                                 "new_project_id" {:type "string"}
                                                                 "add_tags"       {:type "array" :items {:type "string"}}
                                                                 "remove_tags"    {:type "array" :items {:type "string"}}
                                                                 "title"          {:type "string"}
                                                                 "description"    {:type "string"}
                                                                 "priority"       {:type "string"
                                                                                   :enum ["high" "medium" "low"]}}}
                                            :description "Array of operations for batch-update / batch-delete / batch-create / batch-retag. Each op may specify :command to mix verbs in one batch; otherwise the wrapper's default applies."}
                              "directory" {:type "string"
                                           :description "Working directory for project scope (auto-detected if not provided)"}
                              "include_descendants" {:type "boolean"
                                                     :description "Include DESCENDANT (child) project tasks in results (HCR Wave 4). Default true — set false to restrict to current scope + ancestors only. Ancestor (parent) tasks are ALWAYS included regardless of this flag ('child sees parent')."}
                              "scope" {:type "string"
                                       :enum ["all"]
                                       :description "[list/status] scope=\"all\" lifts the project filter entirely — returns the whole board across EVERY workspace (opt-in cross-workspace view). Omit for the default scoped view (current project + ancestors [+ descendants])."}
                              "project_id" {:type "string"
                                            :description "[list] Exact-match project filter (overrides directory-derived scope). [retag] Alias for new_project_id."}
                              "query" {:type "string"
                                       :description "[list] Case-insensitive substring match on title + description. [get] Fallback semantic-search term when the id misses in both stores (defaults to the id itself)"}
                              "tags" {:type "array" :items {:type "string"}
                                      :description "[list] Extra required tags beyond ['kanban' status]"}
                              "tag_match" {:type "string"
                                           :enum ["any" "all"]
                                           :description "[list] Tag match semantics for `tags` (default 'all')"}
                              "priority" {:type "string"
                                          :enum ["high" "medium" "low"]
                                          :description "[list] Filter by exact priority. [create|update] Set the task priority — an update rewrites the `priority-*` tag together with the content field, so `list` and `get` cannot disagree."}
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