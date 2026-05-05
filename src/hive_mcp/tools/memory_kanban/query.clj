(ns hive-mcp.tools.memory-kanban.query
  (:require [hive-mcp.agent.context :as ctx]
            [hive-mcp.project.tree :as tree]
            [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.tools.kanban.filters :as kf]
            [hive-mcp.tools.kanban.predicates :as kp]
            [hive-mcp.tools.kanban.transitions :as kt]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.vectordb.kanban-facade :as kanban-facade]))

(declare query-kanban-entries resolve-project-ids-with-descendants effective-dir stats* filter-kanban-by-tags list-slim*)

(defn query-kanban-entries
  "Fetch kanban entries from the underlying memory store.

   Routes via `kanban-facade` so reads honor the `:memory/kanban-store`
   config toggle: `:default` keeps milvus behavior, `:dual-read` merges
   :kanban + :default with kanban-first preference, `:kanban` reads
   only the dedicated qdrant collection.

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
                  (kanban-facade/query-entries :type "note" :tags query-tags
                                                :limit effective-limit)
                  all-project-ids
                  (kanban-facade/query-entries :type "note" :tags query-tags
                                                :project-ids all-project-ids
                                                :limit effective-limit)
                  :else
                  (kanban-facade/query-entries :type "note" :tags query-tags
                                                :project-id project-id
                                                :limit single-limit))]
    {:entries entries :multi-project? multi-project?}))

(defn resolve-project-ids-with-descendants [project-id]
  (when-let [pid (when-not (= project-id "global") project-id)]
    (when-let [desc (seq (tree/get-descendant-ids pid))]
      (vec (cons pid desc)))))

(defn effective-dir [directory]
  (kt/effective-dir directory ctx/current-directory))

(defn stats* [{:keys [directory include_descendants]
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

(defn filter-kanban-by-tags [entries required-tags]
  (->> entries
       (filter (fn [entry]
                 (let [entry-tags (set (:tags entry))]
                   (every? #(contains? entry-tags %) required-tags))))
       (filter kp/kanban-entry?)))

(defn list-slim*
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
