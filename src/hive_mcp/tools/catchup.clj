(ns hive-mcp.tools.catchup
  "Native Catchup workflow — thin facade delegating to sub-namespaces.

   Gathers session context from the registered IMemoryStore (currently
   Milvus or Qdrant) with project scoping. Designed for the /catchup skill
   to restore context at session start.

   Sub-namespace delegation (Sprint 2):
   - catchup.scope     — scope-filtered store queries, project context
   - catchup.format    — entry metadata transforms, response builders
   - catchup.git       — git status via Emacs
   - catchup.spawn     — spawn-time context injection (dual-mode)
   - catchup.permeation — auto-permeation of ling wraps

   Public API:
   - handle-native-catchup  — main catchup handler
   - handle-native-wrap     — wrap/crystallize handler
   - spawn-context          — re-export from catchup.spawn"
  (:require [hive-mcp.agent.context :as ctx]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.catchup.scope :as catchup-scope]
            [hive-mcp.tools.catchup.format :as fmt]
            [hive-mcp.tools.catchup.git :as catchup-git]
            [hive-mcp.tools.catchup.carto :as catchup-carto]
            [hive-mcp.tools.catchup.spawn :as catchup-spawn]
            [hive-mcp.tools.catchup.scope-filter :as sf]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.channel.memory-piggyback :as memory-piggyback]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.channel.context-store :as context-store]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.project.tree :as project-tree]
            [hive-mcp.dns.result :refer [rescue ok ok? let-ok try-effect* ok->]]
            [hive-mcp.agent.context :as ctx]
            [hive-dsl.context.identity :as ctx-id]
            [hive-ttracking.core :as tt]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [hive-mcp.tools.catchup.relevance :as relevance]
            [hive-mcp.vectordb.kanban-facade :as kanban-facade]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Re-exports (backward compatibility)
;; =============================================================================

(defn spawn-context
  "Generate a compact context payload for ling spawn injection.
   Delegates to catchup.spawn/spawn-context. See that ns for full docs."
  ([directory] (catchup-spawn/spawn-context directory))
  ([directory opts] (catchup-spawn/spawn-context directory opts)))

;; =============================================================================
;; Parallel Execution Helpers
;; =============================================================================

(defn- safe-deref
  "Deref a future with timeout-ms. Returns default on timeout or exception.
   Label identifies which query timed out so operators can spot silent drops."
  ([fut timeout-ms default]
   (safe-deref fut timeout-ms default "unlabeled"))
  ([fut timeout-ms default label]
   (try
     (let [result (deref fut timeout-ms ::timeout)]
       (if (= result ::timeout)
         (do (future-cancel fut)
             (log/warn "catchup: parallel query timed out after" timeout-ms "ms:" label)
             default)
         result))
     (catch Exception e
       (log/warn "catchup: parallel deref failed for" label ":" (.getMessage e))
       default))))

(def ^:private ^:const query-timeout-ms
  "Outer safe-deref timeout for the bundle/git/carto futures. The bundle
   future runs query-all-scoped (fork-join with its own 60s per-branch
   budget) AND hydrate-buckets (single batch-get for ~270 survivors).
   Measured 2026-04-17 on a 45-project hive hierarchy:
     query-all-scoped   : ~42s
     hydrate-buckets    : ~162s  (Milvus batch-get on ~270 ids)
     total bundle       : ~204s
   60s was the original value and caused silent empty bundles whenever the
   hierarchy was non-trivial — the outer safe-deref hit its timeout long
   before hydrate finished, returning the {} default and propagating 0
   counts to every catchup bucket. 300s gives hydrate realistic headroom
   until the Milvus batch-get path is itself optimized."
  300000)

(defn- gather-kanban-summary
  "Direct kanban-facade query for catchup summary scoped to project-id.
   Returns {:counts {:todo n :inprogress n :inreview n :done n}
            :recent-todos [{:id :title} ...] (top 10 by updated desc)
            :scope-tag str-or-nil}.

   Routes via `kanban-facade/query-entries` so the active store-routing
   mode is honored (`:default` legacy milvus, `:kanban` qdrant cutover,
   `:dual-read` soak). Reaching past the facade is a DIP one-seam
   violation and was the 2026-05-04 catchup regression: the call site
   read milvus while live writes had moved to qdrant, producing stale
   bucket counts that did not reconcile with `kanban list`.

   Railway-ROP: each facade query is wrapped in `try-effect*` so a
   single bad query short-circuits via `ok->` rather than throwing
   through the whole computation. The full empty result is the
   fallback when any leg errors. The caller (safe-deref) still expects
   a plain map, so the Result is unwrapped at the seam."
  [project-id]
  (let [scope-tag    (when project-id (str "scope:project:" project-id))
        base-tags    (cond-> ["kanban"] scope-tag (conj scope-tag))
        empty-result {:counts {} :recent-todos [] :scope-tag scope-tag}
        count-into   (fn [acc bucket tag]
                       (let-ok [n (try-effect* :kanban/count-failed
                                    (count (kanban-facade/query-entries
                                            :type "note"
                                            :tags (conj base-tags tag)
                                            :limit 200
                                            :output-fields ["id"])))]
                         (ok (assoc-in acc [:counts bucket] n))))
        attach-recent (fn [acc]
                        (let-ok [rows (try-effect* :kanban/recent-failed
                                        (kanban-facade/query-entries
                                         :type "note"
                                         :tags (conj base-tags "todo")
                                         :limit 10
                                         :order-by [:updated :desc]
                                         :output-fields ["id" "content" "tags"]
                                         :include-content? true))]
                          (ok (assoc acc :recent-todos
                                     (mapv (fn [e]
                                             {:id    (:id e)
                                              :title (or (get-in e [:content :title])
                                                         (when (string? (:content e))
                                                           (first (clojure.string/split-lines (:content e))))
                                                         "(no title)")
                                              :tags  (:tags e)})
                                           rows)))))
        result (ok-> (ok empty-result)
                     (count-into :todo       "todo")
                     (count-into :inprogress "doing")
                     (count-into :inreview   "review")
                     (count-into :done       "done")
                     attach-recent)]
    (if (ok? result) (:ok result) empty-result)))

;; =============================================================================
;; Main Catchup Handler
;; =============================================================================

(defn handle-native-catchup
  "Native Clojure catchup implementation that queries the registered
   IMemoryStore directly. Returns structured catchup data with proper
   project scoping.

   If an enrichment addon is registered via :cu/a, it runs
   fire-and-forget. Results arrive via piggyback on subsequent calls."
  [args]
  ;; HCR directory resolution: explicit :directory > :_caller_cwd (bb-mcp) >
  ;; request-ctx :directory > server user.dir. Matches handle-native-wrap so
  ;; catchup auto-resolves scope from caller's bash pwd when :directory absent.
  (let [directory (ctx/resolve-caller-directory args)
        dir-source (ctx/caller-directory-source args)]
    (log/info "native-catchup: querying memory store with project scope"
              {:directory directory :source dir-source})
    ;; Guard: early return if no store registered
    (if-not (mem-proto/store-set?)
      (fmt/store-not-configured-error)
      (try
        ;; Project-id resolution priority:
        ;;   1. request-ctx project-id (pre-resolved by wrap-handler-context)
        ;;   2. :project-id from .hive-project.edn in the exact dir
        ;;   3. Walk up the path finding the nearest .hive-project.edn
        ;;      (covers calls from deep subdirs of a hive project — without
        ;;      this, scope/get-current-project-id returns the last path
        ;;      segment, producing a bogus project scope like "catchup".)
        ;;   4. Legacy fallback: last-path-segment / "global"
        (let [ctx-pid          (ctx/current-project-id)
              direct-cfg-pid   (when directory
                                 (rescue nil (:project-id (kg-scope/read-direct-project-config directory))))
              walked-pid       (when (and directory (not direct-cfg-pid))
                                 (rescue nil (kg-scope/infer-scope-from-path directory)))
              project-id       (or ctx-pid
                                   direct-cfg-pid
                                   (when (and walked-pid (not= walked-pid "global")) walked-pid)
                                   (scope/get-current-project-id directory))
              project-name (catchup-scope/get-current-project-name directory)
              scopes (fmt/build-scopes project-name project-id)

              ;; ── Tree scan: ensure project hierarchy is populated before queries ──
              ;; Without this, descendant-scopes returns [] and sessions stored
              ;; under child projects (e.g. hive-mcp under hive) are invisible.
              _ (rescue nil (project-tree/maybe-scan-project-tree! (or directory ".")))

              ;; ── Wave 1: ONE memory bundle + git + carto in parallel ──
              ;; The bundle replaces 7 per-type Milvus RPCs with 2 queries
              ;; (hierarchy + global-pierce), grouped by :type in memory. Avoids
              ;; Milvus type-filter scalar-scan storms that blew the budget.
              f-bundle (pool/with-io ((tt/timed-query "catchup/bundle-total"
                                                      #(catchup-scope/query-catchup-bundle project-id))))
              f-git    (pool/with-io ((tt/timed-query "catchup/git-total"
                                                      #(catchup-git/gather-git-info directory))))
              f-carto  (pool/with-io ((tt/timed-query "catchup/carto-total"
                                                      #(catchup-carto/get-status project-id))))
              f-kanban (pool/with-io ((tt/timed-query "catchup/kanban-summary-total"
                                                      #(gather-kanban-summary project-id))))

              bundle        (safe-deref f-bundle query-timeout-ms {} "bundle")
              git-info      (safe-deref f-git query-timeout-ms {} "git-info")
              carto-status  (safe-deref f-carto query-timeout-ms nil "carto-status")
              kanban-summary (safe-deref f-kanban query-timeout-ms
                                          {:counts {} :recent-todos []}
                                          "kanban-summary")

              axioms               (:axioms bundle [])
              principles           (:principles bundle [])
              priority-conventions (:priority-conventions bundle [])
              sessions             (:sessions bundle [])
              recent-wraps-raw     (:recent-wraps bundle [])
              decisions            (:decisions bundle [])
              snippets             (:snippets bundle [])
              expiring             (:expiring bundle [])
              conventions          (:conventions bundle [])

              ;; Convert to metadata (pure, fast)
              axioms-meta (mapv fmt/entry->axiom-meta axioms)
              principles-meta (mapv #(fmt/entry->catchup-meta % 80) principles)
              priority-meta (mapv fmt/entry->priority-meta priority-conventions)
              sessions-meta (mapv #(fmt/entry->catchup-meta % 80) sessions)
              recent-wraps (mapv #(select-keys % [:id :created :tags :content]) recent-wraps-raw)
              decisions-base (mapv #(fmt/entry->catchup-meta % 80) decisions)
              conventions-base (mapv #(fmt/entry->catchup-meta % 80) conventions)
              snippets-meta (mapv #(fmt/entry->catchup-meta % 60) snippets)
              expiring-meta (mapv #(fmt/entry->catchup-meta % 80) expiring)

              ;; Addon extension: fire-and-forget (async, returns nil immediately).
              _ (when-let [enrich-fn (ext/get-extension :cu/a)]
                  (enrich-fn {:directory directory
                              :project-id project-id
                              :caller-id (:_caller_id args)
                              :decisions decisions-base
                              :decisions-raw decisions
                              :conventions conventions-base
                              :conventions-raw conventions
                              :sessions sessions-meta
                              :sessions-raw sessions
                              :axioms axioms
                              :principles principles
                              :priority-conventions priority-conventions}))

              ;; Memory piggyback: enqueue axioms + priority conventions for
              ;; incremental delivery via ---MEMORY--- blocks on subsequent calls.
              ;; Axioms first (highest priority), then priority conventions.
              ;;
              ;; SESSION-SCOPED: memory piggyback uses raw caller-id (no project
              ;; dimension) for buffer key alignment with routes.clj drain wrappers.
              ;; Hivemind cursor still uses project-scoped piggyback-agent-id.
              raw-caller-id (or (:_caller_id args) "coordinator")
              caller (ctx-id/parse-caller-id raw-caller-id)
              scope (ctx-id/parse-project-scope project-id)
              piggyback-agent-id (ctx-id/make-piggyback-agent-id caller scope)

              ;; Cursor hygiene: adopt previous coordinator's cursor position
              ;; so we don't re-read hivemind messages from timestamp 0 after
              ;; a bb-mcp restart. Also evict stale cursors (> 30 min) and
              ;; adopt orphaned memory-piggyback buffers from dead instances.
              _ (rescue nil
                        (do
                          (piggyback/adopt-cursor! piggyback-agent-id project-id)
                          (piggyback/evict-stale-cursors! 1800000) ;; 30 min
                          (memory-piggyback/adopt-buffer! raw-caller-id)))

              ;; Scope-filter piggyback: keep entries relevant to this agent's
              ;; project hierarchy. Axioms used to ALWAYS pierce scope which
              ;; flooded sessions with off-topic axioms (windows-ntlm,
              ;; bufferbloat, JMM, typography). Now axioms must also pass a
              ;; tag-overlap relevance score against the project's vocabulary
              ;; — `catchup-priority` and `scope:project:<current>` still
              ;; pierce. See `hive-mcp.tools.catchup.relevance`.
              ;; Entries without scope tags pass through (global by convention).
              relevance-ctx
              (relevance/build-context
               {:project-id project-id
                :co-loaded-entries (concat priority-conventions
                                           decisions
                                           sessions)})
              relevant-axioms
              (relevance/filter-by-relevance (vec axioms) relevance-ctx)
              piggyback-raw (into (into (vec relevant-axioms) principles) priority-conventions)
              piggyback-entries
              (let [in-project? (and project-id (not= project-id "global"))]
                (if-not in-project?
                  piggyback-raw
                  (let [scope-tags (sf/compute-full-scope-tags project-id)
                        visible-ids (set (conj (or (rescue [] (kg-scope/visible-scopes project-id))
                                                   [project-id])
                                               "global"))]
                    (filterv (fn [entry]
                               (let [tags (set (or (:tags entry) []))
                                     entry-type (str (or (:type entry) ""))]
                                 (or
                                  ;; Axioms already filtered above by relevance —
                                  ;; survivors continue to pierce the scope filter.
                                  (= entry-type "axiom")
                                  ;; catchup-priority entries pierce scope
                                  (contains? tags "catchup-priority")
                                  ;; No scope tag = global, passes through
                                  (not-any? #(.startsWith ^String % "scope:project:") tags)
                                  ;; Scope-matching entries pass through
                                  (some tags scope-tags)
                                  (contains? visible-ids (:project-id entry)))))
                             piggyback-raw))))

              ;; Dual-write: Cache entry categories in context-store for pass-by-ref mode.
              ;; Uses context-put-batch! to write all categories in parallel via futures.
              ;; Each category gets its own ctx-id with 'catchup' + category tags.
              ;; TTL: 10 minutes (catchup context useful for the session duration).
              ;; Non-fatal: context-store failure doesn't break catchup.
              catchup-ttl 600000
              scope-tag  (or project-id "global")
              context-refs
              (rescue nil
                      (let [refs (context-store/context-put-batch!
                                  {:axioms                {:data axioms
                                                           :tags #{"catchup" "axioms" scope-tag}
                                                           :ttl-ms catchup-ttl}
                                   :principles            {:data principles
                                                           :tags #{"catchup" "principles" scope-tag}
                                                           :ttl-ms catchup-ttl}
                                   :priority-conventions  {:data priority-conventions
                                                           :tags #{"catchup" "priority-conventions" scope-tag}
                                                           :ttl-ms catchup-ttl}
                                   :sessions              {:data sessions
                                                           :tags #{"catchup" "sessions" scope-tag}
                                                           :ttl-ms catchup-ttl}
                                   :decisions             {:data decisions
                                                           :tags #{"catchup" "decisions" scope-tag}
                                                           :ttl-ms catchup-ttl}
                                   :conventions           {:data conventions
                                                           :tags #{"catchup" "conventions" scope-tag}
                                                           :ttl-ms catchup-ttl}
                                   :snippets              {:data snippets
                                                           :tags #{"catchup" "snippets" scope-tag}
                                                           :ttl-ms catchup-ttl}})]
                        (when (seq refs)
                          (log/info "catchup: stored" (count refs) "categories in context-store"
                                    {:refs (keys refs)}))
                        refs))

              _ (when (seq piggyback-entries)
                  (memory-piggyback/enqueue! raw-caller-id piggyback-entries context-refs))]

          (fmt/build-catchup-response
           {:project-name project-name :project-id project-id
            :scopes scopes :git-info git-info
            :axioms-meta axioms-meta :principles-meta principles-meta
            :priority-meta priority-meta
            :sessions-meta sessions-meta :decisions-meta decisions-base
            :conventions-meta conventions-base :snippets-meta snippets-meta
            :expiring-meta expiring-meta
            :recent-wraps recent-wraps
            :carto-status carto-status
            :kanban-summary kanban-summary
            :context-refs context-refs}))
        (catch Exception e
          (fmt/catchup-error e))))))

;; =============================================================================
;; Wrap Handler
;; =============================================================================

(defn handle-native-wrap
  "Native Clojure wrap implementation that persists to the registered
   IMemoryStore directly. Delegates to :catchup/wrap extension
   (provided by addon) for harvesting and crystallization."
  [args]
  (let [directory (ctx/resolve-caller-directory args)
        agent-id (:agent_id args)]
    (log/info "native-wrap: crystallizing to memory store" {:directory directory :agent-id agent-id})
    (if-not (mem-proto/store-set?)
      (fmt/store-not-configured-error)
      (if-let [wrap-fn (ext/get-extension :catchup/wrap)]
        (try
          (let [result (wrap-fn {:directory directory :agent-id agent-id})
                project-id (scope/get-current-project-id directory)]
            (if (:error result)
              {:type "text"
               :text (json/write-str {:error (:error result) :session (:session result)})
               :isError true}
              {:type "text"
               :text (json/write-str (assoc result :project-id project-id))}))
          (catch Exception e
            (log/error e "native-wrap failed")
            {:type "text"
             :text (json/write-str {:error (.getMessage e)})
             :isError true}))
        {:type "text"
         :text (json/write-str {:error "Wrap extension not registered. Load the crystal addon."})
         :isError true}))))
