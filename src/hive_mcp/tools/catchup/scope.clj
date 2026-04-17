(ns hive-mcp.tools.catchup.scope
  "Scope resolution and query functions for catchup workflow.

   Facade layer — delegates to child namespaces:
     - catchup.hierarchy   : project-id resolution + chunked fetch
     - catchup.scope_filter: pure filter/sort helpers (TBD)
     - catchup.axiom_cache : stale-while-revalidate axiom cache (TBD)
     - catchup.hydration   : batch-get content pipeline (TBD)
     - catchup.bundle      : query orchestrators (TBD)"
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.tools.catchup.hierarchy :as hier]
            [hive-mcp.tools.catchup.scope-filter :as sf]
            [hive-mcp.tools.catchup.axiom-cache :as axc]
            [hive-mcp.tools.catchup.hydration :as hydr]
            [hive-weave.pool :as wpool]
            [hive-weave.parallel :as wpar]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.set :as set]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Re-exports from scope_filter for backward-compat:
(def distinct-by         sf/distinct-by)
(def ^:private newest-first sf/newest-first)

(defn get-current-project-name
  "Get current project name from .hive-project.edn or directory path (no Emacs dependency)."
  ([] (get-current-project-name nil))
  ([directory]
   (rescue nil
           (when directory
             (or
              ;; Priority 1: :name from .hive-project.edn
              (let [config (kg-scope/read-direct-project-config directory)]
                (or (:name config) (:project-id config)))
              ;; Priority 2: last path segment
              (let [parts (str/split (str directory) #"/")]
                (last parts)))))))

(def filter-by-tags           sf/filter-by-tags)
(def ^:private compute-full-scope-tags sf/compute-full-scope-tags)
(def ^:private scope-filter-entries    sf/scope-filter-entries)
(def ^:private scope-pierce-entries    sf/scope-pierce-entries)

(def ^:private scoped-branch-budget-ms
  "Per-branch budget for the hierarchy / global-piercing fan-out. Each branch
   is its own Milvus RPC; giving each its own budget means a slow branch
   can't drag the faster one past the outer deadline. Sized for metadata-only
   scans: 300 rows at ~107ms/row (project-filtered, 5-field projection) lands
   around 32s. 60s leaves ~50% headroom for jitter + the fork-join wrapper."
  60000)

(defn query-scoped-entries
  "Query memory store entries filtered by project scope with hierarchy and
   scope-piercing. The two Milvus calls (hierarchy fetch + global-piercing
   fetch) run concurrently via hive-weave fork-join with independent per-branch
   budgets so a slow cold-path Milvus query on one branch doesn't block the
   other. Branches that exceed their budget return [] (partial results)."
  [entry-type tags project-id limit]
  (when (mem-proto/store-set?)
    (let [store (mem-proto/get-store)
          limit-val (or limit 20)
          in-project? (and project-id (not= project-id "global"))
          hierarchy-ids (hier/compute-hierarchy-project-ids project-id)
          over-fetch-factor (if hierarchy-ids 3 4)
          tasks (cond-> [[:hierarchy
                          #(mem-proto/query-entries
                             store
                             {:type entry-type
                              :project-ids hierarchy-ids
                              :limit (min (* limit-val over-fetch-factor) 500)})
                          []]]
                  in-project?
                  (conj [:global
                         #(mem-proto/query-entries
                            store
                            {:type entry-type
                             :project-id "global"
                             :limit 100})
                         []]))
          {:keys [hierarchy global]} (apply wpar/fork-join
                                            {:budget-ms scoped-branch-budget-ms}
                                            tasks)
          entries         (or hierarchy [])
          global-entries  (or global [])
          full-scope-tags (compute-full-scope-tags project-id)
          all-visible-ids (set (or hierarchy-ids ["global"]))
          scoped (scope-filter-entries entries full-scope-tags all-visible-ids)
          scope-piercing (when in-project?
                           (scope-pierce-entries global-entries project-id))
          scoped (distinct-by :id (concat scoped scope-piercing))
          filtered (filter-by-tags scoped tags)]
      (->> filtered
           newest-first
           (take limit-val)))))

(def entry-expiring-soon? sf/entry-expiring-soon?)

(defn query-expiring-entries
  "Query entries expiring within 7 days, scoped to project with scope-piercing."
  [project-id limit]
  (let [in-project? (and project-id (not= project-id "global"))
        store (mem-proto/get-store)
        hierarchy-ids (hier/compute-hierarchy-project-ids project-id)
        entries (mem-proto/query-entries store {:project-ids hierarchy-ids
                                                :limit 200})
        full-scope-tags (compute-full-scope-tags project-id)
        all-visible-ids (set (or hierarchy-ids ["global"]))
        scoped (scope-filter-entries entries full-scope-tags all-visible-ids)
        scope-piercing (when in-project?
                         (let [global-entries (mem-proto/query-entries store {:project-id "global"
                                                                              :limit 100})]
                           (scope-pierce-entries global-entries project-id)))
        scoped (distinct-by :id (concat scoped scope-piercing))]
    (->> scoped
         (filter entry-expiring-soon?)
         newest-first
         (take (or limit 20)))))

;; Re-exports from axiom_cache for backward-compat:
(def invalidate-axioms-cache! axc/invalidate-axioms-cache!)
(def query-axioms             axc/query-axioms)

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

(def ^:private bundle-global-limit
  "Cap on global scope-pierce fetch. Only axioms + catchup-priority conventions
   cross boundary, so this stays small."
  200)

(def ^:private bundle-axioms-limit
  "Cap on the cross-project axiom pull. Axioms are by definition global: they
   apply regardless of the caller's project scope, including axioms authored
   under sibling projects that are neither ancestors nor descendants of the
   current hierarchy. The hierarchy + global-pierce branches miss those, so
   we add a dedicated `type=axiom` branch with no project filter. Kept small
   because axiom cardinality is low across the hive."
  200)

(def ^:private bundle-principles-limit
  "Cap on the cross-project principle pull. Principles, like axioms, are
   high-value knowledge that should be visible regardless of authoring scope.
   Without a dedicated fetch, principles authored under sibling/parent projects
   are missed by the hierarchy + global-pierce branches."
  100)

(def ^:private tt-timed-query-var
  "Late-bound reference to `hive-ttracking.core/timed-query`. Resolved lazily
   so scope.clj still loads when the hive-ttracking addon is absent from the
   classpath (e.g. minimal CI builds). When resolved, hive-mcp uses the addon
   implementation — DI via classpath presence. When absent, falls back to the
   local `timed-query-inline` below."
  (delay
    (rescue nil
            (require 'hive-ttracking.core)
            (resolve 'hive-ttracking.core/timed-query))))

(defn- timed-query-inline
  "Fallback telemetry wrapper used when hive-ttracking is not on the
   classpath. Matches tt/timed-query's single-arity signature."
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
   fallback. DI shape: the addon decides how to emit telemetry; the consumer
   (this namespace) only knows the label+thunk contract."
  [label qfn]
  (if-let [tt @tt-timed-query-var]
    (tt label qfn)
    (timed-query-inline label qfn)))

(defn query-all-scoped
  "Single-shot scoped pull: chunked-parallel hierarchy query + global-pierce +
   global-axioms, merged + deduped. Returns

     {:by-type {\"axiom\" [...] \"decision\" [...] ...}
      :all     [<merged entries, newest-first>]}

   Metadata-only projection — `content` hydrated lazily by hydrate-buckets.
   Fork-joined with `scoped-branch-budget-ms`. Falls back to partial results
   when a branch exceeds budget; `timed-query` surfaces empties (which in
   practice are almost always fork-join fallbacks) as :warn log lines."
  [project-id]
  (when (mem-proto/store-set?)
    (let [store (mem-proto/get-store)
          in-project? (and project-id (not= project-id "global"))
          hierarchy-ids (hier/compute-hierarchy-project-ids project-id)
          per-chunk-limit (max 50 (int (/ hier/bundle-hierarchy-limit
                                          (hier/chunk-count hierarchy-ids))))
          tasks (cond-> [[:hierarchy
                          (timed-query "hierarchy"
                                       #(hier/chunked-hierarchy-fetch store hierarchy-ids per-chunk-limit))
                          []]
                         [:axioms-global
                          (timed-query "axioms-global"
                                       #(mem-proto/query-entries
                                          store
                                          {:type "axiom"
                                           :limit bundle-axioms-limit
                                           :output-fields hier/metadata-projection}))
                          []]
                         [:principles-global
                          (timed-query "principles-global"
                                       #(mem-proto/query-entries
                                          store
                                          {:type "principle"
                                           :limit bundle-principles-limit
                                           :output-fields hier/metadata-projection}))
                          []]]
                  in-project?
                  (conj [:global
                         (timed-query "global-pierce"
                                      #(mem-proto/query-entries
                                         store
                                         {:project-id "global"
                                          :limit bundle-global-limit
                                          :output-fields hier/metadata-projection}))
                         []]))
          {:keys [hierarchy global axioms-global principles-global]}
          (apply wpar/fork-join {:budget-ms scoped-branch-budget-ms} tasks)
          full-scope-tags (compute-full-scope-tags project-id)
          all-visible-ids (set (or hierarchy-ids ["global"]))
          scoped (scope-filter-entries (or hierarchy []) full-scope-tags all-visible-ids)
          pierced (when in-project?
                    (scope-pierce-entries (or global []) project-id))
          ;; Axioms and principles are global by definition — include every
          ;; `type=axiom` and `type=principle` entry regardless of authoring
          ;; project, so sibling-scoped entries aren't dropped by the
          ;; hierarchy + global-pierce filters.
          axioms-all (or axioms-global [])
          principles-all (or principles-global [])
          merged (distinct-by :id (concat scoped pierced axioms-all principles-all))
          sorted (newest-first merged)]
      {:by-type (group-by #(some-> (:type %) name) sorted)
       :all     sorted})))

;; Re-exports from hydration for backward-compat:
(def ^:private has-tag?        hydr/has-tag?)
(def ^:private hydrate-buckets hydr/hydrate-buckets)

(defn- split-by-type
  "Phase-1 trim: pick per-type buckets from the grouped+sorted bundle."
  [by-type all]
  (let [take-type (fn [t limit] (vec (take limit (get by-type t []))))
        tagged    (fn [t tag limit]
                    (vec (take limit
                               (filter #(has-tag? % tag) (get by-type t [])))))]
    {:axioms               (take-type "axiom" 100)
     :principles           (take-type "principle" 50)
     :priority-conventions (tagged    "convention" "catchup-priority" 50)
     :sessions             (tagged    "note" "session-summary" 10)
     :decisions            (take-type "decision" 50)
     :conventions          (vec (take 50
                                      (remove #(has-tag? % "catchup-priority")
                                              (get by-type "convention" []))))
     :snippets             (take-type "snippet" 20)
     :expiring             (vec (take 20 (filter entry-expiring-soon? all)))}))

(defn- hydrate-buckets
  "Phase-2 sweep: dedupe ids across all buckets, single batch-get, then
   swap each metadata shell for its hydrated twin. Entries missing from
   the store pass through untouched."
  [buckets]
  (let [all-entries (distinct-by :id (apply concat (vals buckets)))
        by-id       (-> all-entries hydrate-content index-by-id)
        swap-each   (fn [coll]
                      (mapv #(merge-hydrated % (get by-id (:id %))) coll))]
    (reduce-kv (fn [m k v] (assoc m k (swap-each v))) {} buckets)))

(defn query-catchup-bundle
  "Single-pull catchup bundle: replaces 7 per-type Milvus RPCs with 2.
   Returns the map shape catchup.clj needs, pre-split + pre-trimmed.

   Two-phase pipeline:
     phase-1  query-all-scoped   → metadata-only scan (fast, big)
     phase-2  split-by-type      → trim to display/piggyback caps
     phase-3  hydrate-buckets    → one batch-get for content on survivors"
  [project-id]
  (let [{:keys [by-type all]} (or (query-all-scoped project-id)
                                  {:by-type {} :all []})]
    (-> (split-by-type by-type all)
        hydrate-buckets)))
