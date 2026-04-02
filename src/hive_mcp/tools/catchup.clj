(ns hive-mcp.tools.catchup
  "Native Catchup workflow — thin facade delegating to sub-namespaces.

   Gathers session context from Chroma memory with project scoping.
   Designed for the /catchup skill to restore context at session start.

   Sub-namespace delegation (Sprint 2):
   - catchup.scope     — scope-filtered Chroma queries, project context
   - catchup.format    — entry metadata transforms, response builders
   - catchup.git       — git status via Emacs
   - catchup.spawn     — spawn-time context injection (dual-mode)
   - catchup.permeation — auto-permeation of ling wraps

   Public API:
   - handle-native-catchup  — main catchup handler
   - handle-native-wrap     — wrap/crystallize handler
   - spawn-context          — re-export from catchup.spawn"
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.catchup.scope :as catchup-scope]
            [hive-mcp.tools.catchup.format :as fmt]
            [hive-mcp.tools.catchup.git :as catchup-git]
            [hive-mcp.tools.catchup.spawn :as catchup-spawn]
            [hive-mcp.channel.memory-piggyback :as memory-piggyback]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.channel.context-store :as context-store]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-dsl.context.identity :as ctx-id]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))
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
  "Deref a future with timeout-ms. Returns default on timeout or exception."
  [fut timeout-ms default]
  (try
    (let [result (deref fut timeout-ms ::timeout)]
      (if (= result ::timeout)
        (do (future-cancel fut)
            (log/debug "catchup: parallel query timed out")
            default)
        result))
    (catch Exception e
      (log/debug "catchup: parallel deref failed:" (.getMessage e))
      default)))

(def ^:private ^:const query-timeout-ms
  "Timeout for individual parallel query futures."
  15000)

;; =============================================================================
;; Main Catchup Handler
;; =============================================================================

(defn handle-native-catchup
  "Native Clojure catchup implementation that queries Chroma directly.
   Returns structured catchup data with proper project scoping.

   If an enrichment addon is registered via :cu/a, it runs
   fire-and-forget. Results arrive via piggyback on subsequent calls."
  [args]
  (let [directory (:directory args)]
    (log/info "native-catchup: querying Chroma with project scope" {:directory directory})
    ;; Guard: early return if Chroma not configured
    (if-not (mem-proto/store-set?)
      (fmt/chroma-not-configured-error)
      (try
        (let [project-id (scope/get-current-project-id directory)
              project-name (catchup-scope/get-current-project-name directory)
              scopes (fmt/build-scopes project-name project-id)

              ;; ── Wave 1: Fire independent queries in parallel (bounded by IO pool) ──
              f-axioms      (pool/with-io (catchup-scope/query-axioms project-id))
              f-principles  (pool/with-io (catchup-scope/query-scoped-entries "principle" nil project-id 50))
              f-priority    (pool/with-io (catchup-scope/query-scoped-entries "convention" ["catchup-priority"]
                                                                              project-id 50))
              f-sessions    (pool/with-io (catchup-scope/query-scoped-entries "note" ["session-summary"] project-id 10))
              f-decisions   (pool/with-io (catchup-scope/query-scoped-entries "decision" nil project-id 50))
              f-snippets    (pool/with-io (catchup-scope/query-scoped-entries "snippet" nil project-id 20))
              f-expiring    (pool/with-io (catchup-scope/query-expiring-entries project-id 20))
              f-git         (pool/with-io (catchup-git/gather-git-info directory))

              ;; ── Wave 1: Collect with timeout ──
              axioms               (safe-deref f-axioms query-timeout-ms [])
              principles           (safe-deref f-principles query-timeout-ms [])
              priority-conventions (safe-deref f-priority query-timeout-ms [])
              sessions             (safe-deref f-sessions query-timeout-ms [])
              decisions            (safe-deref f-decisions query-timeout-ms [])
              snippets             (safe-deref f-snippets query-timeout-ms [])
              expiring             (safe-deref f-expiring query-timeout-ms [])
              git-info             (safe-deref f-git query-timeout-ms {})

              ;; ── Wave 2: Dependent query (needs axiom + priority IDs) ──
              conventions (safe-deref
                           (pool/with-io
                             (catchup-scope/query-regular-conventions
                              project-id
                              (set (map :id axioms))
                              (set (map :id priority-conventions))))
                           query-timeout-ms [])

              ;; Convert to metadata (pure, fast)
              axioms-meta (mapv fmt/entry->axiom-meta axioms)
              principles-meta (mapv #(fmt/entry->catchup-meta % 80) principles)
              priority-meta (mapv fmt/entry->priority-meta priority-conventions)
              sessions-meta (mapv #(fmt/entry->catchup-meta % 80) sessions)
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

              piggyback-entries (into (into (vec axioms) principles) priority-conventions)

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
            :context-refs context-refs}))
        (catch Exception e
          (fmt/catchup-error e))))))

;; =============================================================================
;; Wrap Handler
;; =============================================================================

(defn handle-native-wrap
  "Native Clojure wrap implementation that persists to Chroma directly.
   Delegates to :catchup/wrap extension (provided by hive-knowledge)
   for harvesting and crystallization."
  [args]
  (let [directory (:directory args)
        agent-id (:agent_id args)]
    (log/info "native-wrap: crystallizing to Chroma" {:directory directory :agent-id agent-id})
    (if-not (mem-proto/store-set?)
      (fmt/chroma-not-configured-error)
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
         :text (json/write-str {:error "Wrap extension not registered. Load hive-knowledge addon."})
         :isError true}))))
