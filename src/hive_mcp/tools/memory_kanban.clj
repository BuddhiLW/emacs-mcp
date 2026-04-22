(ns hive-mcp.tools.memory-kanban
  "In-memory kanban tools using the memory system.

   Tasks stored as memory entries with:
   - type: 'note'
   - tags: ['kanban', status, priority]
   - duration: 'short-term' (7 days)
   - content: {:task-type 'kanban' :title ... :status ...}

   Moving to 'done' DELETES from memory (after creating progress note).

   Result DSL boundary: try-effect* catches exceptions at handler level.
   CC-optimized: if-let/when-let/when-not/cond->/case are 0 CC in scc."
  (:require [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.crystal.hooks :as crystal-hooks]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.tools.memory.crud :as mem-crud]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.memory.temporal :as temporal]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.project.tree :as tree]
            [hive-mcp.agent.context :as ctx]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]])
  (:import [java.time ZonedDateTime]
           [java.time.format DateTimeFormatter]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ============================================================
;; Result DSL boundary (single handler wrapper)
;; ============================================================

(defn- safe-call
  "Execute thunk f, catch exceptions as MCP errors. try/catch = 0 CC in scc.
   Pure functions return MCP response maps directly."
  [category f]
  (try (f)
       (catch Exception e
         (log/error e (str (name category) " failed"))
         (mcp-error (.getMessage e)))))

;; ============================================================
;; CC-free helpers (if-let/when-let/when-not/cond->/case = 0 CC)
;; ============================================================

(defn- effective-dir
  "Resolve directory: explicit > ctx binding. if-let = 0 CC."
  [directory]
  (if-let [d directory] d (ctx/current-directory)))

(defn- content-val
  "Get value from content map, trying keyword then string key with default.
   if-let chain = 0 CC (vs (or (get k) (get s) default) = 2 CC)."
  [content k default]
  (if-let [v (get content k)] v
          (if-let [v2 (get content (name k))] v2
                  default)))

(defn- kanban-task-type?
  "Check if content has task-type 'kanban'. some = 0 CC."
  [content]
  (some #(= "kanban" (get content %)) [:task-type "task-type"]))

;; ============================================================
;; Direct Chroma Kanban Helpers
;; ============================================================

(defn- kanban-timestamp []
  (.format (ZonedDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ")))

(defn- build-kanban-tags [status priority project-id]
  (conj ["kanban" status (str "priority-" priority)]
        (scope/make-scope-tag project-id)))

(defn- kanban-entry? [entry]
  (boolean (kanban-task-type? (:content entry))))

(def ^:private status-enum->tag
  {"inprogress" "doing" "inreview" "review" "todo" "todo" "done" "done"})

(def ^:private valid-statuses #{"todo" "doing" "review" "done"})

(def ^:private priority-order
  {"high" 0 "priority-high" 0 "medium" 1 "priority-medium" 1 "low" 2 "priority-low" 2})

(defn- sort-by-priority-then-created [tasks]
  (sort (fn [a b]
          (let [pa (get priority-order (content-val a :priority "medium") 1)
                pb (get priority-order (content-val b :priority "medium") 1)]
            (case (compare pa pb)
              0 (compare (str (:id a)) (str (:id b)))
              (compare pa pb))))
        tasks))

;; ============================================================
;; Descendant Aggregation (HCR Wave 5)
;; ============================================================

(defn- resolve-project-ids-with-descendants [project-id]
  (when-let [pid (when-not (= project-id "global") project-id)]
    (when-let [desc (seq (tree/get-descendant-ids pid))]
      (vec (cons pid desc)))))

(defn- extract-project-id-from-tags [entry]
  (some (fn [tag]
          (when-let [s (when-not (nil? tag) (str tag))]
            (when-not (not (.startsWith ^String s "scope:project:"))
              (subs s (count "scope:project:")))))
        (:tags entry)))

;; ============================================================
;; Slim Formatting
;; ============================================================

(defn- task->slim
  ([entry] (task->slim entry false))
  ([entry multi-project?]
   (let [content (:content entry)]
     (cond-> {:id (:id entry)
              :title (content-val content :title nil)
              :status (content-val content :status nil)
              :priority (content-val content :priority nil)}
       multi-project? (assoc :project (extract-project-id-from-tags entry))))))

;; ============================================================
;; Query Helpers (DRY between list-slim and stats)
;; ============================================================

(defn- query-kanban-entries [project-id include-descendants? limit]
  (let [global? (= project-id "global")
        all-project-ids (when (and include-descendants? (not global?))
                          (resolve-project-ids-with-descendants project-id))
        multi-project? (or global? (boolean all-project-ids))
        effective-limit (max limit 500)
        entries (cond
                  ;; Global + descendants: query all kanban entries (no project filter)
                  (and global? include-descendants?)
                  (facade/query-entries :type "note" :tags ["kanban"]
                                        :limit effective-limit)
                  ;; Specific project + descendants
                  all-project-ids
                  (facade/query-entries :type "note" :tags ["kanban"]
                                        :project-ids all-project-ids
                                        :limit effective-limit)
                  ;; Single project scope
                  :else
                  (facade/query-entries :type "note" :tags ["kanban"]
                                        :project-id project-id
                                        :limit limit))]
    {:entries entries :multi-project? multi-project?}))

(defn- filter-kanban-by-tags [entries required-tags]
  (->> entries
       (filter (fn [entry]
                 (let [entry-tags (set (:tags entry))]
                   (every? #(contains? entry-tags %) required-tags))))
       (filter kanban-entry?)))

;; ============================================================
;; Movement Tracking (session-scoped for wrap harvest)
;; ============================================================

(defn- track-movement!
  "Record a kanban status transition in DataScript for wrap harvest.
   Non-fatal — movement tracking failure should never block kanban ops."
  [{:keys [task-id title from to project-id]}]
  (try
    (ds/register-kanban-movement!
     {:task-id task-id :title title :from from :to to :project-id project-id})
    (catch Exception e
      (log/debug "track-movement! failed (non-fatal):" (.getMessage e)))))

;; ============================================================
;; Pure Logic (return MCP response maps directly)
;; ============================================================

(defn- create* [{:keys [title description priority context directory agent_id]}]
  (when (or (nil? title) (and (string? title) (str/blank? title)))
    (throw (ex-info "Kanban task requires a non-empty title" {:type :validation-error})))
  (let [eff-dir (effective-dir directory)
        eff-agent (if-let [a agent_id] a
                          (if-let [c (ctx/current-agent-id)] c
                                  (System/getenv "CLAUDE_SWARM_SLAVE_ID")))
        priority (if-let [p priority] p "medium")
        project-id (scope/get-current-project-id eff-dir)
        content (cond-> {:task-type "kanban" :title title :status "todo"
                         :priority priority :created (kanban-timestamp)
                         :started nil :context context}
                  description (assoc :description description))
        tags (build-kanban-tags "todo" priority project-id)
        crud-result (mem-crud/handle-add {:type "note"
                                          :content (json/write-str content)
                                          :tags tags :directory eff-dir
                                          :agent_id eff-agent :duration "short"})]
    (log/info "kanban-create result:" crud-result)
    (when-not (:isError crud-result)
      (track-movement! {:task-id (or (:text crud-result) "unknown")
                        :title title :from nil :to "todo"
                        :project-id project-id}))
    (if-let [_ (:isError crud-result)]
      crud-result
      {:type "text" :text (:text crud-result)})))

(defn- list-slim* [{:keys [status directory include_descendants]
                    :or {include_descendants true}}]
  (let [eff-dir (effective-dir directory)
        project-id (scope/get-current-project-id eff-dir)
        status-tag (when-let [s status] (get status-enum->tag s s))
        required-tags (if-let [st status-tag] ["kanban" st] ["kanban"])
        {:keys [entries multi-project?]} (query-kanban-entries
                                          project-id include_descendants 100)
        kanban-entries (filter-kanban-by-tags entries required-tags)
        slim-entries (mapv #(task->slim % multi-project?) kanban-entries)]
    (mcp-json (sort-by-priority-then-created slim-entries))))

(defn- archive-to-done-archive!
  "Archive task data via extension registry before deletion.
   Non-blocking, non-fatal. Delegates to extension if available."
  [entry task-id]
  (try
    (when-let [archive-fn (ext/get-extension :da/archive!)]
      (let [content (:content entry)
            scope (some-> entry :tags
                          (->> (filter #(str/starts-with? % "scope:project:"))
                               first
                               (str/replace "scope:project:" "")))
            task-data {:id task-id
                       :title (or (get content :title)
                                  (get content :description)
                                  (str task-id))
                       :scope scope
                       :agent-id (get content :agent-id)
                       :files (get content :files)
                       :completed-at (java.util.Date.)
                       :session-id (rescue nil (when-let [sid (requiring-resolve 'hive-mcp.crystal.core/session-id)]
                                          (sid)))
                       :context (get content :context)
                       :tags (filterv #(not (str/starts-with? % "scope:"))
                                      (or (:tags entry) []))}]
        (archive-fn task-data)
        (log/info "Archived done task via extension:" task-id)))
    (catch Exception e
      (log/debug "Done-archive extension not available (non-fatal):" (.getMessage e)))))

(defn- move-to-done! [entry task-id]
  (let [content (:content entry)
        old-status (content-val content :status "doing")
        title (content-val content :title nil)
        project-id (some-> entry :tags
                           (->> (filter #(clojure.string/starts-with? % "scope:project:"))
                                first
                                (clojure.string/replace "scope:project:" "")))]
    ;; Track movement for wrap harvest
    (track-movement! {:task-id task-id :title title
                      :from old-status :to "done"
                      :project-id project-id}))
  (when-let [task-data (crystal-hooks/extract-task-from-kanban-entry entry)]
    (log/info "Calling crystal hook for completed kanban task:" task-id
              "project-id:" (:project-id task-data))
    (try (crystal-hooks/on-kanban-done task-data)
         (catch Exception e (log/warn "Crystal hook failed (non-fatal):" (.getMessage e)))))
  ;; Archive to Datahike before deleting from Chroma
  (archive-to-done-archive! entry task-id)
  ;; Temporal dual-write: record deletion with full previous state
  (temporal/record-mutation-silent!
   {:entry-id       task-id
    :op             :kanban-done
    :data           {:status "done" :deleted true}
    :previous-value (select-keys entry [:content :tags :duration])
    :project-id     (some-> entry :tags
                            (->> (filter #(clojure.string/starts-with? % "scope:project:"))
                                 first
                                 (clojure.string/replace "scope:project:" "")))})
  (facade/delete-entry! task-id)
  (mcp-json {:deleted true :status "done" :id task-id}))

(defn- move-to-status! [entry task-id new-status directory]
  (let [content (:content entry)
        old-status (content-val content :status "todo")
        priority (content-val content :priority "medium")
        title (content-val content :title nil)
        new-content (cond-> (assoc content :status new-status)
                      (= new-status "doing") (assoc :started (kanban-timestamp)))
        eff-dir (effective-dir directory)
        project-id (scope/get-current-project-id eff-dir)
        new-tags (build-kanban-tags new-status priority project-id)
        _ (facade/update-entry! task-id {:content new-content :tags new-tags})
        updated (facade/get-entry-by-id task-id)]
    ;; Temporal dual-write: record status transition
    (temporal/record-mutation-silent!
     {:entry-id   task-id
      :op         :kanban-move
      :data       {:old-status old-status :new-status new-status}
      :project-id project-id})
    ;; Track movement for wrap harvest
    (track-movement! {:task-id task-id :title title
                      :from old-status :to new-status
                      :project-id project-id})
    (mcp-json (task->slim updated))))

(defn- move* [{:keys [task_id new_status status id directory]}]
  ;; Normalize param aliases: DSL/multi may pass :status instead of :new_status,
  ;; and :id instead of :task_id. In the update context these are unambiguous.
  ;; Also normalize MCP enum values (inprogress→doing, inreview→review).
  (let [raw-status (or new_status status)
        new_status (get status-enum->tag raw-status raw-status)
        task_id    (or task_id id)]
    (if-let [_ (valid-statuses new_status)]
      (if-let [entry (facade/get-entry-by-id task_id)]
        (if-let [_ (kanban-task-type? (:content entry))]
          (case new_status
            "done" (move-to-done! entry task_id)
            (move-to-status! entry task_id new_status directory))
          (mcp-error (str "Entry is not a kanban task: " task_id)))
        (mcp-error (str "Task not found: " task_id)))
      (mcp-error (str "Invalid status: " new_status ". Valid: todo, doing, review, done")))))

(defn- delete!
  "Hard-delete a kanban entry without archival or completion semantics.
   Use for removing duplicates, cancellations, or erroneously created tasks."
  [entry task-id]
  (let [content    (:content entry)
        old-status (content-val content :status "todo")
        title      (content-val content :title nil)
        project-id (some-> entry :tags
                           (->> (filter #(str/starts-with? % "scope:project:"))
                                first
                                (str/replace "scope:project:" "")))]
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
      (if-let [_ (kanban-task-type? (:content entry))]
        (delete! entry task-id)
        (mcp-error (str "Entry is not a kanban task: " task-id)))
      (mcp-error (str "Task not found: " task-id)))))

(defn- stats* [{:keys [directory include_descendants]
                :or {include_descendants true}}]
  (let [eff-dir (effective-dir directory)
        project-id (scope/get-current-project-id eff-dir)
        {:keys [entries multi-project?]} (query-kanban-entries
                                          project-id include_descendants 500)
        kanban-entries (filter-kanban-by-tags entries ["kanban"])
        stats (reduce (fn [counts entry]
                        (let [status (content-val (:content entry) :status "todo")]
                          (update counts (keyword status) (fnil inc 0))))
                      {:todo 0 :doing 0 :review 0}
                      kanban-entries)
        result (if-let [_ multi-project?]
                 (let [by-project
                       (reduce (fn [acc entry]
                                 (let [proj (if-let [p (extract-project-id-from-tags entry)] p "unknown")
                                       status (content-val (:content entry) :status "todo")]
                                   (update-in acc [proj (keyword status)] (fnil inc 0))))
                               {}
                               kanban-entries)]
                   (assoc stats :by-project by-project))
                 stats)]
    (mcp-json result)))

;; ============================================================
;; Public Handlers (boundary: safe-call wraps try-effect*)
;; ============================================================

(defn handle-mem-kanban-create
  "Create a kanban task in memory (direct Chroma, no elisp roundtrip).
   CTX Migration: Uses request context for agent_id and directory extraction."
  [params]
  (safe-call :kanban/create-failed #(create* params)))

(defn handle-mem-kanban-list-slim
  "List kanban tasks with minimal data for token optimization.
   HCR Wave 4: Pass include_descendants=true to aggregate child project tasks."
  [params]
  (safe-call :kanban/list-failed #(with-store (list-slim* params))))

(defn handle-mem-kanban-move
  "Move task to new status. Moving to 'done' DELETES the task from memory.
   CTX Migration: Uses request context for directory extraction."
  [params]
  (safe-call :kanban/move-failed #(with-store (move* params))))

(defn handle-mem-kanban-delete
  "Hard-delete a kanban task by task_id. No archival, no completion semantics.
   Records :kanban-delete temporal mutation with previous-value snapshot for audit.
   Use for duplicates, cancellations, or erroneously created tasks."
  [params]
  (safe-call :kanban/delete-failed #(with-store (delete* params))))

(defn handle-mem-kanban-stats
  "Get kanban statistics by status.
   HCR Wave 4: Pass include_descendants=true to aggregate child project stats."
  [params]
  (safe-call :kanban/stats-failed #(with-store (stats* params))))

(defn handle-mem-kanban-quick
  "Quick add task with defaults (todo, medium priority).
   CTX Migration: Delegates to handle-mem-kanban-create which uses context."
  [{:keys [title directory agent_id]}]
  (handle-mem-kanban-create {:title title :directory directory :agent_id agent_id}))

;; Tool definitions

(def tools
  "REMOVED: Flat mem-kanban tools no longer exposed. Use consolidated `kanban` tool."
  [])
