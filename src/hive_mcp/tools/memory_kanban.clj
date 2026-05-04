(ns hive-mcp.tools.memory-kanban
  "MCP handlers for kanban tasks. Pure orchestration delegates to:

   - hive-mcp.tools.kanban.predicates  — status enums + entry shape
   - hive-mcp.tools.kanban.transitions — pure derivation of new state
   - hive-mcp.tools.kanban.events      — event-driven move semantics

   Status transitions are SOFT: moving to `done` retags the entry as
   `done` (status field + tag) and stamps `:completed`, but the memory
   entry id and KG edges are preserved.

   Hard delete remains available via the explicit `delete*` path for
   duplicates / cancellations."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.memory.temporal :as temporal]
            [hive-mcp.project.tree :as tree]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.tools.kanban.events :as kanban-events]
            [hive-mcp.tools.kanban.predicates :as kp]
            [hive-mcp.tools.kanban.transitions :as kt]
            [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.crud :as mem-crud]
            [hive-mcp.tools.memory.scope :as scope]
            [taoensso.timbre :as log]
            [hive-mcp.tools.kanban.filters :as kf]
            [hive-mcp.vectordb.kanban-facade :as kanban-facade]
            [hive-mcp.tools.memory-kanban.query :as query]))

(declare query-kanban-entries resolve-project-ids-with-descendants effective-dir stats* filter-kanban-by-tags list-slim*)
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ============================================================
;; Result DSL boundary
;; ============================================================

(defn- safe-call
  "Execute thunk f, catch exceptions as MCP errors."
  [category f]
  (try (f)
       (catch Exception e
         (log/error e (str (name category) " failed"))
         (mcp-error (.getMessage e)))))

;; ============================================================
;; Movement tracking — used by create + delete paths
;; (move uses the event-driven path which handles tracking itself)
;; ============================================================

(defn- track-movement!
  [{:keys [task-id title from to project-id]}]
  (try
    (ds/register-kanban-movement!
     {:task-id task-id :title title :from from :to to :project-id project-id})
    (catch Exception e
      (log/debug "track-movement! failed (non-fatal):" (.getMessage e)))))

;; ============================================================
;; Descendant aggregation (HCR Wave 5)
;; ============================================================

;; ============================================================
;; Pure operations
;; ============================================================

(defn- create* [{:keys [title description priority context directory agent_id tags]
                 :as _params}]
  (when (or (nil? title) (and (string? title) (str/blank? title)))
    (throw (ex-info "Kanban task requires a non-empty title" {:type :validation-error})))
  (let [eff-dir   (effective-dir directory)
        eff-agent (or agent_id (ctx/current-agent-id) (System/getenv "CLAUDE_SWARM_SLAVE_ID"))
        priority  (or priority "medium")
        project-id (scope/get-current-project-id eff-dir)
        content (cond-> {:task-type "kanban" :title title :status "todo"
                         :priority priority :created (kt/kanban-timestamp)
                         :started nil :context context}
                  description (assoc :description description))
        ;; Merge caller-supplied tags (e.g. wave:N from plan-to-kanban,
        ;; epic:foo from grouping) with the standard kanban tag set.
        ;; Audit kanban 20260429203429: previously :tags was silently dropped.
        extra-tags (when (sequential? tags)
                     (filterv string? tags))
        tags (vec (distinct (concat (kt/build-kanban-tags "todo" priority project-id)
                                    (or extra-tags []))))
        ;; Thread the kanban-store toggle's active key into the generic
        ;; memory-add pipeline. Embedding + duplicate detection + KG
        ;; edges all stay on `mem-crud/handle-add`; only the IMemoryStore
        ;; slot routing changes. :default in legacy mode, :kanban after
        ;; the cutover flag flips.
        crud-result (mem-crud/handle-add {:type "note"
                                          :content (json/write-str content)
                                          :tags tags :directory eff-dir
                                          :agent_id eff-agent :duration "short"
                                          :store-key (kanban-facade/active-key)})]
    (log/info "kanban-create result:" crud-result)
    (when-not (:isError crud-result)
      (track-movement! {:task-id (or (:text crud-result) "unknown")
                        :title title :from nil :to "todo"
                        :project-id project-id}))
    (if (:isError crud-result)
      crud-result
      {:type "text" :text (:text crud-result)})))

(defn- move*
  "Soft-transition a kanban task to a new status via the event bus.
   On success, return a slim view derived from the committed effect
   payload instead of doing an immediate backend read. Some vector
   backends are read-after-write eventual here, so reading the entry
   back can echo the old status even though the write succeeded."
  [{:keys [task_id new_status status id directory]}]
  (let [task-id    (or task_id id)
        new-status (or new_status status)
        result     (kanban-events/dispatch-move!
                    {:task-id task-id :new-status new-status :directory directory})]
    (if (r/ok? result)
      (let [{:keys [content tags]} (get-in result [:ok :kanban/facade-update :payload])]
        (mcp-json (kt/task->slim {:id task-id :content content :tags tags})))
      (mcp-error (or (:message result)
                     (str "Move failed: " (:error result)))))))

(defn- retag*
  "Retag a kanban entry: scope-move (project_id) + optional ±tags.
   Tags-only mutation — preserves entry id, content, KG edges, qdrant point.
   Routes via the event bus so audit + tracking stay uniform."
  [{:keys [task_id id project_id new_project_id add_tags remove_tags directory]}]
  (let [task-id (or task_id id)
        new-pid (or new_project_id project_id)
        result  (kanban-events/dispatch-retag!
                 {:task-id        task-id
                  :new-project-id new-pid
                  :add-tags       add_tags
                  :remove-tags    remove_tags
                  :directory      directory})]
    (if (r/ok? result)
      (let [{:keys [tags]} (get-in result [:ok :kanban/facade-update :payload])
            entry          (kanban-facade/get-entry-by-id task-id)]
        (mcp-json (kt/task->slim
                   (assoc entry :id task-id :tags tags)
                   true)))
      (mcp-error (or (:message result)
                     (str "Retag failed: " (:error result)))))))

(defn- delete!
  "Hard-delete a kanban entry. Records :kanban-delete temporal mutation
   with previous-value for audit before removal.

   Delete routes via kanban-facade so the entry leaves whichever
   slot(s) the toggle currently writes to — :kanban-only post-cutover,
   both during dual-read soak."
  [entry task-id]
  (let [content    (:content entry)
        old-status (kt/content-val content :status nil)
        title      (kt/content-val content :title nil)
        project-id (kt/extract-project-id-from-tags entry)]
    (temporal/record-mutation-silent!
     {:entry-id       task-id
      :op             :kanban-delete
      :data           {:deleted true :previous-status old-status}
      :previous-value (select-keys entry [:content :tags :duration])
      :project-id     project-id})
    (track-movement! {:task-id task-id :title title
                      :from old-status :to "deleted"
                      :project-id project-id})
    (kanban-facade/delete-entry! task-id)
    (mcp-json {:deleted true :id task-id :previous-status old-status})))

(defn- delete* [{:keys [task_id id]}]
  (let [task-id (or task_id id)]
    (if-let [entry (kanban-facade/get-entry-by-id task-id)]
      (if (kp/kanban-task-type? (:content entry))
        (delete! entry task-id)
        (mcp-error (str "Entry is not a kanban task: " task-id)))
      (mcp-error (str "Task not found: " task-id)))))

;; ============================================================
;; Public Handlers
;; ============================================================

(defn handle-mem-kanban-create [params]
  (safe-call :kanban/create-failed #(create* params)))

(defn handle-mem-kanban-list-slim
  "List kanban tasks. HCR: include_descendants=true aggregates child projects."
  [params]
  (safe-call :kanban/list-failed #(with-store (list-slim* params))))

(defn handle-mem-kanban-move
  "Move task to new status via the kanban event bus.
   Moving to `done` is a SOFT transition: status retagged, entry preserved,
   KG edges intact. Use `handle-mem-kanban-delete` for hard removal."
  [params]
  (safe-call :kanban/move-failed #(with-store (move* params))))

(defn handle-mem-kanban-retag
  "Retag a kanban entry: scope-move (project_id) + optional ±tags.
   Preserves entry id + KG edges (tags-only mutation, no re-embed)."
  [params]
  (safe-call :kanban/retag-failed #(with-store (retag* params))))

(defn handle-mem-kanban-delete
  "Hard-delete a kanban task by task_id. Records :kanban-delete temporal
   mutation with previous-value snapshot for audit."
  [params]
  (safe-call :kanban/delete-failed #(with-store (delete* params))))

(defn handle-mem-kanban-stats [params]
  (safe-call :kanban/stats-failed #(with-store (stats* params))))

(defn handle-mem-kanban-quick
  [{:keys [title directory agent_id]}]
  (handle-mem-kanban-create {:title title :directory directory :agent_id agent_id}))

(def tools
  "REMOVED: Flat mem-kanban tools no longer exposed. Use consolidated `kanban` tool."
  [])

(def ^:private query-kanban-entries hive-mcp.tools.memory-kanban.query/query-kanban-entries)

(def ^:private resolve-project-ids-with-descendants hive-mcp.tools.memory-kanban.query/resolve-project-ids-with-descendants)

(def ^:private effective-dir hive-mcp.tools.memory-kanban.query/effective-dir)

(def ^:private stats* hive-mcp.tools.memory-kanban.query/stats*)

(def ^:private filter-kanban-by-tags hive-mcp.tools.memory-kanban.query/filter-kanban-by-tags)

(def ^:private list-slim* hive-mcp.tools.memory-kanban.query/list-slim*)
