(ns hive-mcp.tools.memory-kanban.query
  (:require [hive-mcp.agent.context :as ctx]
            [hive-mcp.project.tree :as tree]
            [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.tools.kanban.filters :as kf]
            [hive-mcp.tools.kanban.predicates :as kp]
            [hive-mcp.tools.kanban.transitions :as kt]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.vectordb.kanban-facade :as kanban-facade]))

(declare query-kanban-entries resolve-project-ids-with-descendants resolve-visible-project-ids effective-dir stats* filter-kanban-by-tags list-slim*)

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

   Scope resolution honours the SAME inheritance the rest of the memory
   system uses (knowledge_graph/scope.clj): a list sees its own scope +
   ANCESTORS (UP — 'child sees parent', always on), plus DESCENDANTS (DOWN)
   when `include-descendants?` is set. Previously kanban walked descendants
   ONLY, so a list from a child scope silently dropped every parent/ancestor
   task — the recurring `general/` scope-blindness. See
   `resolve-visible-project-ids`.

   `include-descendants?` aggregates child-project tasks via the cached
   project tree. For leaf projects (no descendants) we still bump to the
   larger `effective-limit` when descendants were requested — soft-deleted
   tasks accumulate over time and the active-task window MUST be wide
   enough to surface them.

   `opts` (optional) :scope \"all\" lifts the project filter entirely and
   returns the whole board across every workspace — the opt-in escape hatch
   for cross-workspace (sibling) coordination, since siblings share only via
   their common ancestor (`global`) and would otherwise stay invisible."
  ([project-id include-descendants? limit query-tags]
   (query-kanban-entries project-id include-descendants? limit query-tags nil))
  ([project-id include-descendants? limit query-tags {:keys [scope]}]
   (let [all-scopes?     (= scope "all")
         global?         (= project-id "global")
         ;; self + ancestors (always) [+ descendants when requested].
         visible-ids     (when-not (or all-scopes? global?)
                           (resolve-visible-project-ids project-id include-descendants?))
         multi-project?  (boolean (or all-scopes? global? (and visible-ids (next visible-ids))))
         effective-limit (max limit 500)
         ;; Honour include-descendants? even on leaf projects: the
         ;; descendant flag signals the caller wants the full task
         ;; lineage including done/archived items, so widen the window.
         single-limit    (if include-descendants? effective-limit limit)
         entries (cond
                   ;; scope=all, or global+descendants: no project filter — whole board.
                   (or all-scopes? (and global? include-descendants?))
                   (kanban-facade/query-entries :type "note" :tags query-tags
                                                 :limit effective-limit)
                   ;; visible hierarchy: self + ancestors [+ descendants].
                   visible-ids
                   (kanban-facade/query-entries :type "note" :tags query-tags
                                                 :project-ids (vec visible-ids)
                                                 :limit effective-limit)
                   ;; global without descendants: global scope only (unchanged).
                   :else
                   (kanban-facade/query-entries :type "note" :tags query-tags
                                                 :project-id project-id
                                                 :limit single-limit))]
     {:entries entries :multi-project? multi-project?})))

(defn resolve-project-ids-with-descendants
  "Self + all DESCENDANT project-ids (DOWN-walk via the cached project tree).
   Returns nil for global or for a leaf with no descendants (callers fall back
   to a singular :project-id filter). Retained as the pure descendant helper;
   `resolve-visible-project-ids` composes it with the ancestor chain."
  [project-id]
  (when-let [pid (when-not (= project-id "global") project-id)]
    (when-let [desc (seq (tree/get-descendant-ids pid))]
      (vec (cons pid desc)))))

(defn resolve-visible-project-ids
  "Project-ids visible from `project-id`, honouring the documented scope
   inheritance (knowledge_graph/scope.clj):

     - self + ANCESTORS  (UP — 'child sees parent', ALWAYS) via the same
       `kg/visible-scopes` chain memory queries use; and
     - DESCENDANTS       (DOWN) only when `include-descendants?` is set.

   This is the fix for kanban HCR scope-blindness: the prior code path
   (`resolve-project-ids-with-descendants`) walked DOWN only, so listing from
   a child scope dropped every parent task. Returns nil for global (caller
   handles the no-filter / single-scope branches)."
  [project-id include-descendants?]
  (when (and project-id (not= project-id "global"))
    (let [ancestors   (scope/resolve-scope-chain project-id)   ; [self … "global"]
          descendants (when include-descendants?
                        (seq (tree/get-descendant-ids project-id)))]
      (vec (distinct (concat ancestors descendants))))))

(defn effective-dir [directory]
  (kt/effective-dir directory ctx/current-directory))

(defn stats* [{:keys [include_descendants scope]
                :or {include_descendants true}
                :as params}]
  ;; HCR: explicit :directory > :_caller_cwd (bb-mcp session pwd) >
  ;; request-ctx > server user.dir. Keeps default scope on the caller's
  ;; pwd project instead of the server's own (hive-mcp).
  (let [eff-dir    (ctx/resolve-caller-directory params)
        project-id (scope/get-current-project-id eff-dir)
        {:keys [entries multi-project?]} (query-kanban-entries
                                          project-id include_descendants
                                          20000 ["kanban"] {:scope scope})
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

   Filters (all optional):
   - :status               todo | inprogress | inreview | done (pushed to store)
   - :project_id           explicit project scope override (defaults to dir-resolved)
   - :include_descendants  aggregate child-project tasks (default true)
   - :scope                \"all\" lifts the project filter — whole board across
                           every workspace (opt-in cross-workspace view)
   - :query                case-insensitive substring on title + description
   - :tags                 extra tag filter beyond [kanban, status]
   - :tag_match            \"all\" (default, AND, pushed to store) or \"any\" (OR, post-filter)
   - :priority             exact: high | medium | low
   - :created_after        ISO-8601 string; entries with content :created > threshold
   - :updated_after        ISO-8601 string; checks :updated/:started/:completed
   - :limit                cap response array size
   - :offset               skip first N (after sort)
   - :fields               seq of field names to project (default = full slim shape)

   status + AND-tags are pushed into the store query; query/priority/date/OR-tags
   are client-side post-filters, so a narrowing request fetches the whole scoped
   board (else a match beyond the newest window silently vanishes)."
  [{:keys [status include_descendants project_id scope
           query tags tag_match priority
           created_after updated_after
           limit offset fields]
    :or   {include_descendants true
           tag_match            "all"}
    :as   params}]
  ;; HCR: explicit :directory > :_caller_cwd (bb-mcp session pwd) >
  ;; request-ctx > server user.dir.
  (let [eff-dir       (ctx/resolve-caller-directory params)
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
        ;; Fetch window: a narrowing client-side filter must see the whole
        ;; scoped board (20000 > largest scoped board); other post-filters
        ;; widen to 500; a bare list stays at 100.
        fetch-limit   (cond
                        (kf/narrowing-post-filters? params) 20000
                        (kf/post-filters? params)           500
                        :else                               100)
        {:keys [entries multi-project?]} (query-kanban-entries
                                          scoped-pid include_descendants
                                          fetch-limit required-tags {:scope scope})
        kanban-entries (->> (filter-kanban-by-tags entries required-tags)
                            (filter #(kf/entry-tags-match? % tags tag-mode))
                            (filter #(kf/entry-matches-query? % query))
                            (filter #(kf/entry-priority? % priority))
                            (filter #(kf/entry-after-ts? % :created created_after))
                            (filter #(kf/entry-after-ts? % :updated updated_after)))
        slim-entries  (mapv #(kt/task->slim % multi-project?) kanban-entries)
        sorted        (kt/sort-by-priority-then-created slim-entries)
        ;; A bare, unfiltered list (no status, no post-filter, no explicit
        ;; limit) defaults to a 100-row cap so a large board can't flood the
        ;; tool token budget. Any narrowing filter or explicit limit opts out
        ;; and returns the full matched set (offset/limit still honored).
        default-limit (when-not (or status (kf/post-filters? params)) 100)
        paged         (kf/paginate sorted offset (or limit default-limit))
        projected     (mapv #(kf/project-fields % fields) paged)]
    (mcp-json projected)))
