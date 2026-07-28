(ns hive-mcp.tools.catchup.bundle
  "Query orchestrators for the catchup workflow.

   Two entry points:
     - query-scoped-entries / query-expiring-entries / query-regular-conventions
       : per-type fork-joined scans used by spawn + catchup_session
     - query-catchup-bundle
       : single-pull metadata scan → type-bucket trim → batch hydrate,
         drives the catchup.clj main pipeline

   Depends on:
     hierarchy    — chunked project-id fan-out + metadata-projection
     scope_filter — pure scope filters + sort/dedupe helpers
     hydration    — phase-2 batch-get content pipeline"
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.tools.catchup.hierarchy :as hier]
            [hive-mcp.tools.catchup.scope-filter :as sf]
            [hive-mcp.tools.catchup.hydration :as hydr]
            [hive-weave.parallel :as wpar]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private scoped-branch-budget-ms
  "Per-branch budget for the hierarchy / global-piercing fan-out. Each branch
   is its own Milvus RPC; giving each its own budget means a slow branch
   can't drag the faster one past the outer deadline. Sized for metadata-only
   scans: 300 rows at ~107ms/row (project-filtered, 5-field projection) lands
   around 32s. 60s leaves ~50% headroom for jitter + the fork-join wrapper."
  60000)

(def ^:private bundle-global-limit
  "Cap on global scope-pierce fetch. Only axioms + catchup-priority conventions
   cross boundary, so this stays small."
  200)

(def ^:private bundle-axioms-limit
  "Cap on the cross-project axiom pull. Axioms are by definition global —
   visible regardless of authoring scope. The hierarchy + global-pierce
   branches miss sibling-authored axioms, so we add a dedicated `type=axiom`
   branch with no project filter. Kept small because axiom cardinality is low."
  200)

(def ^:private bundle-principles-limit
  "Cap on cross-project principle pull. Like axioms, principles are visible
   regardless of authoring scope, so a dedicated fetch catches sibling/parent
   authored entries the hierarchy + global-pierce branches would drop."
  100)

(def ^:private bundle-sessions-fresh-limit 25)

(def ^:private bundle-recent-wraps-limit
  "Cap on cross-project wrap-generated note pull. Surfaces the last N
   persisted wrap syntheses (LLM-authored session summaries) so the
   ---RECENT-WRAPS--- block carries prior-session insight forward without
   re-running synthesis. Notes tagged 'wrap-generated' are sparse and
   project-scoped; a dedicated branch with no project filter catches
   sibling/parent authored entries the hierarchy + global-pierce branches
   would miss.

   Sized to over-fetch by ~20× the display cap (10 wraps shown in the
   ---recent-wraps--- block). Backends like Milvus query-scalar lack
   server-side ORDER BY, so we pair this with `:order-by [:created :desc]`
   in the query opts: the impl sorts the returned set in-memory, then
   `split-by-type` trims to the display cap. Without this over-fetch + sort
   pair, scan-order results froze the visible wraps to whatever segment
   Milvus happened to scan first."
  200)

;; =============================================================================
;; Telemetry wrapper — DI via hive-ttracking when present
;; =============================================================================

(def ^:private tt-timed-query-var
  "Late-bound reference to `hive-ttracking.core/timed-query`. Resolved lazily
   so this namespace still loads when the hive-ttracking addon is absent from
   the classpath (e.g. minimal CI builds). DI via classpath presence."
  (delay
    (rescue nil
            (require 'hive-ttracking.core)
            (resolve 'hive-ttracking.core/timed-query))))

(defn- timed-query-inline
  "Fallback telemetry wrapper used when hive-ttracking is absent.
   Matches tt/timed-query's single-arity signature."
  [label qfn]
  (fn []
    (let [t0 (System/currentTimeMillis)
          result (qfn)
          elapsed (- (System/currentTimeMillis) t0)
          n (count (or result []))]
      (if (zero? n)
        (log/warn "catchup bundle" label "returned 0 entries in" elapsed "ms"
                  "— may indicate Milvus stall, fork-join fallback, or genuinely empty scope")
        (log/info "catchup bundle" label ":" n "entries in" elapsed "ms"))
      result)))

(defn- timed-query
  "Dispatch to hive-ttracking.core/timed-query when available, else inline
   fallback. DI shape: addon decides telemetry emission; consumer only knows
   the label+thunk contract."
  [label qfn]
  (if-let [tt @tt-timed-query-var]
    (tt label qfn)
    (timed-query-inline label qfn)))

;; =============================================================================
;; Per-type fork-joined queries
;; =============================================================================

(defn query-scoped-entries
  "Query memory store entries filtered by project scope with hierarchy and
   scope-piercing. The two Milvus calls (hierarchy fetch + global-piercing
   fetch) run concurrently via hive-weave fork-join with independent per-branch
   budgets so a slow cold-path query on one branch doesn't block the other.
   Branches that exceed their budget return [] (partial results)."
  [entry-type tags project-id limit]
  (when (mem-proto/store-set?)
    (let [store (mem-proto/get-store)
          limit-val (or limit 20)
          in-project? (and project-id (not= project-id "global"))
          hierarchy-ids (hier/compute-hierarchy-project-ids project-id)
          over-fetch-factor (if hierarchy-ids 3 4)
          tasks (cond-> [[:hierarchy
                          (timed-query "query-scoped/hierarchy"
                                       #(with-resilience
                                          (mem-proto/query-entries
                                            store
                                            {:type entry-type
                                             :project-ids hierarchy-ids
                                             :limit (min (* limit-val over-fetch-factor) 500)})))
                          []]]
                  in-project?
                  (conj [:global
                         (timed-query "query-scoped/global"
                                      #(with-resilience
                                         (mem-proto/query-entries
                                           store
                                           {:type entry-type
                                            :project-id "global"
                                            :limit 100})))
                         []]))
          {:keys [hierarchy global]} (apply wpar/fork-join
                                            {:budget-ms scoped-branch-budget-ms}
                                            tasks)
          entries         (or hierarchy [])
          global-entries  (or global [])
          full-scope-tags (sf/compute-full-scope-tags project-id)
          all-visible-ids (set (or hierarchy-ids ["global"]))
          scoped (sf/scope-filter-entries entries full-scope-tags all-visible-ids)
          scope-piercing (when in-project?
                           (sf/scope-pierce-entries global-entries project-id))
          scoped (sf/distinct-by :id (concat scoped scope-piercing))
          filtered (sf/filter-by-tags scoped tags)]
      (->> filtered
           sf/newest-first
           (take limit-val)))))

(defn query-expiring-entries
  "Query entries expiring within 7 days, scoped to project with scope-piercing."
  [project-id limit]
  (let [in-project? (and project-id (not= project-id "global"))
        store (mem-proto/get-store)
        hierarchy-ids (hier/compute-hierarchy-project-ids project-id)
        entries ((timed-query "expiring/hierarchy"
                              #(with-resilience
                                 (mem-proto/query-entries store {:project-ids hierarchy-ids
                                                                 :limit 200}))))
        full-scope-tags (sf/compute-full-scope-tags project-id)
        all-visible-ids (set (or hierarchy-ids ["global"]))
        scoped (sf/scope-filter-entries entries full-scope-tags all-visible-ids)
        scope-piercing (when in-project?
                         (let [global-entries ((timed-query "expiring/global"
                                                            #(with-resilience
                                                               (mem-proto/query-entries store {:project-id "global"
                                                                                               :limit 100}))))]
                           (sf/scope-pierce-entries global-entries project-id)))
        scoped (sf/distinct-by :id (concat scoped scope-piercing))]
    (->> scoped
         (filter sf/entry-expiring-soon?)
         sf/newest-first
         (take (or limit 20)))))

(defn query-regular-conventions
  "Query conventions excluding axioms and priority ones."
  [project-id axiom-ids priority-ids]
  (let [all-conventions (query-scoped-entries "convention" nil project-id 50)
        excluded-ids (set/union axiom-ids priority-ids)]
    (remove #(contains? excluded-ids (:id %)) all-conventions)))

;; =============================================================================
;; Collapsed catchup pull — ONE hierarchy + ONE global-pierce query, group by
;; :type in memory. Replaces 6-7 per-type Milvus RPCs to dodge type-filter
;; scalar-scan storms on the cold path.
;; =============================================================================

(defn query-all-scoped
  "Single-shot scoped pull: chunked-parallel hierarchy query + global-pierce +
   global-axioms + global-principles, merged + deduped. Returns

     {:by-type {\"axiom\" [...] \"decision\" [...] ...}
      :all     [<merged entries, newest-first>]}

   Metadata-only projection — `content` hydrated lazily by hydrate-buckets.
   Fork-joined with `scoped-branch-budget-ms`. Falls back to partial results
   when a branch exceeds budget; `timed-query` surfaces empties (which in
   practice are almost always fork-join fallbacks) as :warn log lines.

   The `sessions-fresh` and `recent-wraps-global` branches carry
   `:created-after` = now minus `fresh-window-days`; every other branch is
   unbounded in time."
  [project-id]
  (when (mem-proto/store-set?)
    (let [store (mem-proto/get-store)
          in-project? (and project-id (not= project-id "global"))
          hierarchy-ids (hier/compute-hierarchy-project-ids project-id)
          per-chunk-limit (max 50 (int (/ hier/bundle-hierarchy-limit
                                          (hier/chunk-count hierarchy-ids))))
          fresh-window-days 45
          fresh-window-cutoff (str (.minusDays (java.time.ZonedDateTime/now)
                                               fresh-window-days))
          tasks (cond-> [[:hierarchy
                          (timed-query "hierarchy"
                                       #(hier/chunked-hierarchy-fetch store hierarchy-ids per-chunk-limit))
                          []]
                         [:axioms-global
                          (timed-query "axioms-global"
                                       #(with-resilience
                                          (mem-proto/query-entries
                                            store
                                            {:type "axiom"
                                             :limit bundle-axioms-limit
                                             :output-fields hier/metadata-projection})))
                          []]
                         [:principles-global
                          (timed-query "principles-global"
                                       #(with-resilience
                                          (mem-proto/query-entries
                                            store
                                            {:type "principle"
                                             :limit bundle-principles-limit
                                             :output-fields hier/metadata-projection})))
                          []]
                         [:sessions-fresh
                          (timed-query "sessions-fresh"
                                       #(with-resilience
                                          (mem-proto/query-entries
                                            store
                                            {:type "note"
                                             :tags ["session-summary"]
                                             :limit bundle-sessions-fresh-limit
                                             :created-after fresh-window-cutoff
                                             :order-by [:created :desc]
                                             :output-fields hier/metadata-projection})))
                          []]
                         [:recent-wraps-global
                          (timed-query "recent-wraps-global"
                                       #(with-resilience
                                          (mem-proto/query-entries
                                            store
                                            {:type "note"
                                             :tags ["wrap-generated"]
                                             :limit bundle-recent-wraps-limit
                                             :created-after fresh-window-cutoff
                                             :order-by [:created :desc]
                                             :output-fields hier/metadata-projection})))
                          []]]
                  in-project?
                  (conj [:global
                         (timed-query "global-pierce"
                                      #(with-resilience
                                         (mem-proto/query-entries
                                           store
                                           {:project-id "global"
                                            :limit bundle-global-limit
                                            :output-fields hier/metadata-projection})))
                         []]))
          {:keys [hierarchy global axioms-global principles-global sessions-fresh recent-wraps-global]}
          (apply wpar/fork-join {:budget-ms scoped-branch-budget-ms} tasks)
          full-scope-tags (sf/compute-full-scope-tags project-id)
          all-visible-ids (set (or hierarchy-ids ["global"]))
          scoped (sf/scope-filter-entries (or hierarchy []) full-scope-tags all-visible-ids)
          pierced (when in-project?
                    (sf/scope-pierce-entries (or global []) project-id))
          axioms-all (or axioms-global [])
          principles-all (or principles-global [])
          ;; Sessions and wraps are project-scoped — their dedicated branches
          ;; query without project filter to dodge the per-descendant fairness
          ;; cap (see sessions_freshness_regression_test). The result MUST
          ;; then be scope-filtered to hierarchy-ids (self + descendants) +
          ;; global. Without this filter, sibling-project sessions leak through
          ;; (e.g. funeraria sessions surfacing in hive catchup). HCR is
          ;; strictly top-down — never include siblings.
          sessions-fresh-all (sf/scope-filter-entries (or sessions-fresh []) full-scope-tags all-visible-ids)
          recent-wraps-all   (sf/scope-filter-entries (or recent-wraps-global []) full-scope-tags all-visible-ids)
          merged (sf/distinct-by :id (concat scoped pierced axioms-all principles-all sessions-fresh-all recent-wraps-all))
          sorted (sf/newest-first merged)]
      {:by-type (group-by #(some-> (:type %) name) sorted)
       :all     sorted})))

(defn- split-by-type
  "Phase-1 trim: pick per-type buckets from the grouped+sorted bundle."
  [by-type all]
  (let [take-type (fn [t limit] (vec (take limit (get by-type t []))))
        tagged    (fn [t tag limit]
                    (vec (take limit
                               (filter #(hydr/has-tag? % tag) (get by-type t [])))))
        prio-principles (tagged "principle" "catchup-priority" 50)]
    {:axioms               (take-type "axiom" 100)
     :priority-principles  (if (seq prio-principles)
                             prio-principles
                             (take-type "principle" 50))
     :principles           (if (seq prio-principles)
                             (vec (take 50
                                        (remove #(hydr/has-tag? % "catchup-priority")
                                                (get by-type "principle" []))))
                             [])
     :priority-conventions (tagged    "convention" "catchup-priority" 50)
     :sessions             (tagged    "note" "session-summary" 25)
     :recent-wraps         (tagged    "note" "wrap-generated"   10)
     :decisions            (take-type "decision" 50)
     :conventions          (vec (take 50
                                      (remove #(hydr/has-tag? % "catchup-priority")
                                              (get by-type "convention" []))))
     :snippets             (take-type "snippet" 20)
     :expiring             (vec (take 20 (filter sf/entry-expiring-soon? all)))}))

(defn query-catchup-bundle
  "Single-pull catchup bundle: replaces 7 per-type Milvus RPCs with 2.
   Returns the map shape catchup.clj needs, pre-split + pre-trimmed.

   Two-phase pipeline:
     phase-1  query-all-scoped    → metadata-only scan (fast, big)
     phase-2  split-by-type       → trim to display/piggyback caps
     phase-3  hydr/hydrate-buckets → one batch-get for content on survivors"
  [project-id]
  (let [{:keys [by-type all]} (or (query-all-scoped project-id)
                                  {:by-type {} :all []})]
    (-> (split-by-type by-type all)
        hydr/hydrate-buckets)))
