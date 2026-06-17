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
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.search :as mem-search]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
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
;; Idempotency-key (b+ retry safety)
;;
;; Without this, re-running a wrap-ceremony DSL batch after a transient
;; failure mints fresh kanban ids for every `b+` and pollutes the board
;; with duplicates. Memory `m+` is dedup'd by content-hash; this gives
;; `b+` the same retry-safe property.
;;
;; The key is carried as a tag (`idempotency:<key>`) on the entry. A
;; create call with an idempotency-key first looks up an existing entry
;; carrying that tag in the same project scope; if found, returns its
;; id verbatim (no second write). If not found, the new entry is
;; tagged so the next retry within TTL hits.
;; ============================================================

(defn- idempotency-tag
  "Build the canonical tag string for an idempotency key. Returns nil
   when the key is blank — keeps the no-key path zero-overhead."
  [k]
  (when (and k (string? k) (not (str/blank? k)))
    (str "idempotency:" k)))

(defn- normalize-idempotency-key
  "Pull the idempotency key from any of the accepted param shapes
   (`:idempotency_key`, `:idempotency-key`, `:idk`). Returns the
   string or nil. Centralised so the DSL surface and direct callers
   stay symmetric."
  [params]
  (or (:idempotency_key params)
      (:idempotency-key params)
      (:idk params)))

(defn- find-by-idempotency-key
  "Return the id of an existing kanban entry tagged with
   `idempotency:<key>` in `project-id`'s scope, or nil. Wraps the
   facade in `safe-call`-style rescue so a transient backend failure
   never breaks the create path — a missed lookup degrades to
   'create as if no key', which is the same behaviour the caller
   would have got pre-feature."
  [idem-key project-id]
  (when-let [tag (idempotency-tag idem-key)]
    (try
      (let [results (kanban-facade/query-entries
                     :type             "note"
                     :tags             ["kanban" tag]
                     :project-id       project-id
                     :limit            1
                     :include-expired? false)]
        (some-> (first results) :id))
      (catch Throwable t
        (log/debug "idempotency lookup failed (non-fatal):" (ex-message t))
        nil))))

;; ============================================================
;; Descendant aggregation (HCR Wave 5)
;; ============================================================

;; ============================================================
;; Pure operations
;; ============================================================

(defn- create* [{:keys [title priority context agent_id tags description]
                 :as params}]
  (when (or (nil? title) (and (string? title) (str/blank? title)))
    (throw (ex-info "Kanban task requires a non-empty title" {:type :validation-error})))
  ;; HCR: explicit :directory > :_caller_cwd (bb-mcp session pwd) >
  ;; request-ctx > server user.dir. Keeps default scope on the caller's
  ;; pwd project instead of the server's own (hive-mcp).
  (let [eff-dir   (ctx/resolve-caller-directory params)
        eff-agent (or agent_id (ctx/current-agent-id) (System/getenv "CLAUDE_SWARM_SLAVE_ID"))
        priority  (or priority "medium")
        project-id (scope/get-current-project-id eff-dir)
        idem-key   (normalize-idempotency-key params)
        ;; Idempotency check: if an entry tagged `idempotency:<key>`
        ;; already exists in this project scope, return its id without
        ;; writing again. Makes `b+` safe to re-run as part of a
        ;; partially-failed wrap-ceremony batch.
        existing-id (find-by-idempotency-key idem-key project-id)]
    (if existing-id
      (do
        (log/info "kanban-create idempotency hit — returning existing id"
                  {:idempotency-key idem-key :id existing-id})
        {:type "text" :text existing-id})
      (let [content (cond-> {:task-type "kanban" :title title :status "todo"
                             :priority priority :created (kt/kanban-timestamp)
                             :started nil :context context}
                      description (assoc :description description))
            ;; Merge caller-supplied tags (e.g. wave:N from plan-to-kanban,
            ;; epic:foo from grouping) with the standard kanban tag set.
            ;; Audit kanban 20260429203429: previously :tags was silently dropped.
            extra-tags (when (sequential? tags)
                         (filterv string? tags))
            ;; Thread the idempotency tag through if a key was supplied
            ;; — future creates with the same key will hit
            ;; `find-by-idempotency-key` above and short-circuit.
            idem-tag (idempotency-tag idem-key)
            tags (vec (distinct (concat (kt/build-kanban-tags "todo" priority project-id)
                                        (or extra-tags [])
                                        (when idem-tag [idem-tag]))))
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
          {:type "text" :text (:text crud-result)})))))

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
;; Get — unified by-id retrieval across BOTH backends
;;
;; Kanban entries live in the dedicated :kanban slot (qdrant) while
;; `memory get` only reads the :default slot. That split is why agents
;; hit dead ends: a task id is unreachable from `memory get` and the
;; kanban tool previously had no `get` verb at all. This resolves an id
;; from either store, surfaces the full kanban fields + a compact KG
;; edge summary, and on a total miss falls back to a semantic memory
;; search to suggest near-matches.
;; ============================================================

(defn- try-get
  "Best-effort store read; nil on any failure (unregistered slot, transport
   blip, missing entry). Keeps the cross-store probe non-throwing."
  [thunk]
  (try (thunk) (catch Throwable _ nil)))

(defn- fetch-entry-any-store
  "Resolve a single entry by id across the kanban store and the default
   memory store (separate backends). Returns [entry store-label] or
   [nil nil]. Probes, in order: kanban-facade (mode-aware), the :default
   slot, then the :kanban slot explicitly — covering :default mode where a
   task was written to the dedicated slot, and vice versa."
  [id]
  (or (when-let [e (try-get #(kanban-facade/get-entry-by-id id))]
        [e (if (kp/kanban-entry? e) "kanban" "default")])
      (when-let [e (try-get #(proto/get-entry (proto/get-store) id))]
        [e "default"])
      (when (kanban-facade/registered? :kanban)
        (when-let [e (try-get #(proto/get-entry (proto/get-store :kanban) id))]
          [e "kanban"]))
      [nil nil]))

(defn- kg-summary
  "Compact KG-edge summary for an id: outgoing/incoming as
   {:from :to :relation}. Non-fatal — returns {} on any failure."
  [id]
  (try
    (let [edge->m (fn [e] {:from     (:kg-edge/from e)
                           :to       (:kg-edge/to e)
                           :relation (some-> (:kg-edge/relation e) name)})
          out (kg-edges/get-edges-from id)
          in  (kg-edges/get-edges-to id)]
      (cond-> {}
        (seq out) (assoc :kg_outgoing (mapv edge->m out))
        (seq in)  (assoc :kg_incoming (mapv edge->m in))))
    (catch Throwable _ {})))

(defn- search-suggestions
  "Run a semantic memory search to surface near-matches when a direct get
   misses in both stores. Parses the search tool's JSON envelope back to
   data. Non-fatal — nil on any failure or blank query."
  [q directory]
  (when (and (string? q) (not (str/blank? q)))
    (try
      (let [res (mem-search/handle-search-semantic
                 {:query q :limit 5 :directory directory})
            txt (:text res)]
        (when txt (json/read-str txt :key-fn keyword)))
      (catch Throwable _ nil))))

(defn- get*
  "Unified get-by-id across the kanban store and the default memory store.
   Accepts :task_id (or :id). Returns the full entry envelope with kanban
   fields (title/description/status/priority) surfaced, a `:store` marker
   (\"kanban\"|\"default\"), `:is_kanban`, and a compact KG-edge summary.

   On a miss in both backends, falls back to a semantic memory search over
   :query (or the id) and returns the candidates under :suggestions — so a
   stale or mistyped id still points the caller somewhere useful."
  [{:keys [task_id id query] :as params}]
  (let [target  (or task_id id)
        eff-dir (ctx/resolve-caller-directory params)]
    (if (or (nil? target) (and (string? target) (str/blank? target)))
      (mcp-error "kanban get requires :task_id (or :id)")
      (let [[entry store-label] (fetch-entry-any-store target)]
        (if entry
          (mcp-json (-> (fmt/entry->json-alist entry)
                        (assoc :store store-label
                               :is_kanban (kp/kanban-entry? entry))
                        (merge (kg-summary target))))
          (let [suggestions (search-suggestions (or query target) eff-dir)]
            (mcp-json (cond-> {:error          "Entry not found by id"
                               :id             target
                               :searched_stores ["kanban" "default"]
                               :hint           "id missed in both the kanban store and the default memory store (separate backends); semantic-search candidates below"}
                        (seq suggestions) (assoc :suggestions suggestions)))))))))

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

(defn handle-mem-kanban-get
  "Get a single task/entry by id, unified across the kanban store and the
   default memory store (separate backends). Surfaces full kanban fields +
   KG-edge summary; on miss, returns semantic-search suggestions."
  [params]
  (safe-call :kanban/get-failed #(with-store (get* params))))

(defn handle-mem-kanban-quick
  [{:keys [title directory agent_id]}]
  (handle-mem-kanban-create {:title title :directory directory :agent_id agent_id}))

(def tools
  "REMOVED: Flat mem-kanban tools no longer exposed. Use consolidated `kanban` tool."
  [])

(def ^:private query-kanban-entries hive-mcp.tools.memory-kanban.query/query-kanban-entries)

(def ^:private resolve-project-ids-with-descendants hive-mcp.tools.memory-kanban.query/resolve-project-ids-with-descendants)

(def ^:private resolve-visible-project-ids hive-mcp.tools.memory-kanban.query/resolve-visible-project-ids)

(def ^:private effective-dir hive-mcp.tools.memory-kanban.query/effective-dir)

(def ^:private stats* hive-mcp.tools.memory-kanban.query/stats*)

(def ^:private filter-kanban-by-tags hive-mcp.tools.memory-kanban.query/filter-kanban-by-tags)

(def ^:private list-slim* hive-mcp.tools.memory-kanban.query/list-slim*)
