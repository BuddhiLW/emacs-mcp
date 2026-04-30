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
            [hive-mcp.vectordb.facade :as facade]
            [taoensso.timbre :as log]
            [hive-mcp.tools.kanban.filters :as kf]))
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

(defn- effective-dir [directory]
  (kt/effective-dir directory ctx/current-directory))

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

(defn- resolve-project-ids-with-descendants [project-id]
  (when-let [pid (when-not (= project-id "global") project-id)]
    (when-let [desc (seq (tree/get-descendant-ids pid))]
      (vec (cons pid desc)))))

(defn- query-kanban-entries
  "Fetch kanban entries from the underlying memory store.

   `query-tags` are pushed into the store query (server-side AND-filter),
   so a status-restricted lookup like ['kanban' 'done'] doesn't get
   truncated by the store's `:limit + sort-by :created desc` window.

   Without this push-down, soft-deleted (done) tasks — which retain their
   original `:created` timestamp — fall off the end of the active-task
   window once enough todo/doing/review tasks accumulate, and
   `kanban list status=done` (or descendant traversal that surfaces done
   children) returns empty.

   `include-descendants?` aggregates child-project tasks via the cached
   project tree. For leaf projects (no descendants) we still bump to the
   larger `effective-limit` when descendants were requested — soft-deleted
   tasks accumulate over time and the active-task window MUST be wide
   enough to surface them."
  [project-id include-descendants? limit query-tags]
  (let [global? (= project-id "global")
        all-project-ids (when (and include-descendants? (not global?))
                          (resolve-project-ids-with-descendants project-id))
        multi-project? (or global? (boolean all-project-ids))
        effective-limit (max limit 500)
        ;; Honour include-descendants? even on leaf projects: the
        ;; descendant flag signals the caller wants the full task
        ;; lineage including done/archived items, so widen the window.
        single-limit (if include-descendants? effective-limit limit)
        entries (cond
                  (and global? include-descendants?)
                  (facade/query-entries :type "note" :tags query-tags
                                        :limit effective-limit)
                  all-project-ids
                  (facade/query-entries :type "note" :tags query-tags
                                        :project-ids all-project-ids
                                        :limit effective-limit)
                  :else
                  (facade/query-entries :type "note" :tags query-tags
                                        :project-id project-id
                                        :limit single-limit))]
    {:entries entries :multi-project? multi-project?}))

(defn- filter-kanban-by-tags [entries required-tags]
  (->> entries
       (filter (fn [entry]
                 (let [entry-tags (set (:tags entry))]
                   (every? #(contains? entry-tags %) required-tags))))
       (filter kp/kanban-entry?)))

;; ============================================================
;; Pure operations
;; ============================================================

(defn- create* [{:keys [title description priority context directory agent_id]}]
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
        tags (kt/build-kanban-tags "todo" priority project-id)
        crud-result (mem-crud/handle-add {:type "note"
                                          :content (json/write-str content)
                                          :tags tags :directory eff-dir
                                          :agent_id eff-agent :duration "short"})]
    (log/info "kanban-create result:" crud-result)
    (when-not (:isError crud-result)
      (track-movement! {:task-id (or (:text crud-result) "unknown")
                        :title title :from nil :to "todo"
                        :project-id project-id}))
    (if (:isError crud-result)
      crud-result
      {:type "text" :text (:text crud-result)})))

(defn- list-slim*
  "List kanban tasks with optional token-budget filters.

   Filters (all optional, all applied server-side):
   - :status               todo | inprogress | inreview | done
   - :project_id           explicit project scope override (defaults to dir-resolved)
   - :include_descendants  aggregate child-project tasks (default true)
   - :query                case-insensitive substring on title + description
   - :tags                 extra tag filter beyond [kanban, status]
   - :tag_match            \"all\" (default, AND, pushed to store) or \"any\" (OR, post-filter)
   - :priority             exact: high | medium | low
   - :created_after        ISO-8601 string; entries with content :created > threshold
   - :updated_after        ISO-8601 string; checks :updated/:started/:completed
   - :limit                cap response array size
   - :offset               skip first N (after sort)
   - :fields               seq of field names to project (default = full slim shape)"
  [{:keys [status directory include_descendants project_id
           query tags tag_match priority
           created_after updated_after
           limit offset fields]
    :or   {include_descendants true
           tag_match            "all"}
    :as   params}]
  (let [eff-dir       (effective-dir directory)
        scoped-pid    (or project_id (scope/get-current-project-id eff-dir))
        status-tag    (when status (kp/normalize-status status))
        tag-mode      (keyword (or tag_match "all"))
        and-extra     (when (and (= tag-mode :all) (seq tags)) (vec tags))
        ;; Push every AND-tag into the store query so tag-restricted lookups
        ;; aren't truncated by the active-task window. Status-tag pushdown
        ;; keeps soft-deleted (done) tasks visible in long backlogs.
        required-tags (vec (concat ["kanban"]
                                   (when status-tag [status-tag])
                                   and-extra))
        ;; Bump fetch window when post-filters narrow the result set —
        ;; a 100-row store window can drop matching rows before our
        ;; clojure-side filter runs.
        fetch-limit   (if (kf/post-filters? params) 500 100)
        {:keys [entries multi-project?]} (query-kanban-entries
                                          scoped-pid include_descendants
                                          fetch-limit required-tags)
        kanban-entries (->> (filter-kanban-by-tags entries required-tags)
                            (filter #(kf/entry-tags-match? % tags tag-mode))
                            (filter #(kf/entry-matches-query? % query))
                            (filter #(kf/entry-priority? % priority))
                            (filter #(kf/entry-after-ts? % :created created_after))
                            (filter #(kf/entry-after-ts? % :updated updated_after)))
        slim-entries  (mapv #(kt/task->slim % multi-project?) kanban-entries)
        sorted        (kt/sort-by-priority-then-created slim-entries)
        paged         (kf/paginate sorted offset limit)
        projected     (mapv #(kf/project-fields % fields) paged)]
    (mcp-json projected)))

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

(defn- delete!
  "Hard-delete a kanban entry. Records :kanban-delete temporal mutation
   with previous-value for audit before removal."
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
    (facade/delete-entry! task-id)
    (mcp-json {:deleted true :id task-id :previous-status old-status})))

(defn- delete* [{:keys [task_id id]}]
  (let [task-id (or task_id id)]
    (if-let [entry (facade/get-entry-by-id task-id)]
      (if (kp/kanban-task-type? (:content entry))
        (delete! entry task-id)
        (mcp-error (str "Entry is not a kanban task: " task-id)))
      (mcp-error (str "Task not found: " task-id)))))

(defn- stats* [{:keys [directory include_descendants]
                :or {include_descendants true}}]
  (let [eff-dir    (effective-dir directory)
        project-id (scope/get-current-project-id eff-dir)
        {:keys [entries multi-project?]} (query-kanban-entries
                                          project-id include_descendants
                                          500 ["kanban"])
        kanban-entries (filter-kanban-by-tags entries ["kanban"])
        ;; Drop entries with missing/invalid status from the bucket counts —
        ;; defaulting to :todo silently resurrected entries whose tag-based
        ;; status was already moved to done/deleted but whose content map
        ;; lacked a :status key.
        bucket-keys    #{:todo :doing :review :done}
        stats (reduce (fn [counts entry]
                        (let [s (some-> (kt/content-val (:content entry) :status nil)
                                        keyword)]
                          (cond-> counts
                            (contains? bucket-keys s) (update s (fnil inc 0)))))
                      {:todo 0 :doing 0 :review 0 :done 0}
                      kanban-entries)
        result (if multi-project?
                 (let [by-project
                       (reduce (fn [acc entry]
                                 (let [proj (or (kt/extract-project-id-from-tags entry) "unknown")
                                       s (some-> (kt/content-val (:content entry) :status nil)
                                                 keyword)]
                                   (cond-> acc
                                     (contains? bucket-keys s)
                                     (update-in [proj s] (fnil inc 0)))))
                               {}
                               kanban-entries)]
                   (assoc stats :by-project by-project))
                 stats)]
    (mcp-json result)))

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
