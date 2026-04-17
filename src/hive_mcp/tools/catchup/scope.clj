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
            [hive-mcp.dns.result :refer [rescue rescue-interrupt rescue-log]]
            [hive-mcp.tools.catchup.hierarchy :as hier]
            [hive-weave.pool :as wpool]
            [hive-weave.parallel :as wpar]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.set :as set])
  (:import [java.util.concurrent Future TimeUnit TimeoutException]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn distinct-by
  "Return distinct elements from coll by the value of (f item)."
  [f coll]
  (let [seen (volatile! #{})]
    (filterv (fn [item]
               (let [key (f item)]
                 (if (contains? @seen key)
                   false
                   (do (vswap! seen conj key) true))))
             coll)))

(defn- newest-first
  "Sort entries by :created timestamp, newest first."
  [entries]
  (sort-by :created #(compare %2 %1) entries))

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

(defn filter-by-tags
  "Filter entries to only those containing all specified tags."
  [entries tags]
  (if (seq tags)
    (filter (fn [entry]
              (let [entry-tags (set (:tags entry))]
                (every? #(contains? entry-tags %) tags)))
            entries)
    entries))

(defn- compute-full-scope-tags
  "Compute full hierarchy scope tags for in-memory safety-net filtering."
  [project-id]
  (let [in-project? (and project-id (not= project-id "global"))]
    (cond-> (kg-scope/full-hierarchy-scope-tags project-id)
      in-project? (disj "scope:global"))))

(defn- scope-filter-entries
  "Apply in-memory scope filter as safety net."
  [entries scope-tags visible-ids]
  (filter (fn [entry]
            (let [entry-tags (set (or (:tags entry) []))]
              (or
               (some entry-tags scope-tags)
               (contains? visible-ids (:project-id entry)))))
          entries))

(defn- scope-pierce-entries
  "Extract axioms and catchup-priority entries that pierce scope boundaries."
  [entries project-id]
  (let [in-project? (and project-id (not= project-id "global"))]
    (when in-project?
      (filter (fn [entry]
                (let [entry-tags (set (or (:tags entry) []))
                      entry-type (str (or (:type entry) ""))]
                  (or (= entry-type "axiom")
                      (contains? entry-tags "catchup-priority"))))
              entries))))

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

(defn entry-expiring-soon?
  "Check if entry expires within 7 days."
  [entry]
  (when-let [exp (:expires entry)]
    (rescue false
            (let [exp-time (java.time.ZonedDateTime/parse exp)
                  now (java.time.ZonedDateTime/now)
                  week-later (.plusDays now 7)]
              (.isBefore exp-time week-later)))))

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

(def ^:private axioms-formal-budget-ms
  "Wall-clock budget for the formal `type=axiom` branch. query-scoped-entries
   issues TWO sequential Milvus calls (hierarchy + global-piercing), each
   ~5-6s on the type-filter cold path, so we budget 20s to stay well under
   the 60s catchup acceptance gate while still letting the formal branch land
   even on cold-path Milvus queries."
  20000)

(defn- deref-with-deadline
  "Block on `fut` up to `deadline-ms` wall-clock. On timeout, cancel(true)
   and log under `label`; on exception, log and return []. Never throws."
  [^Future fut deadline-ms label budget-ms]
  (let [remaining (max 0 (- deadline-ms (System/currentTimeMillis)))]
    (try
      (.get fut remaining TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (.cancel fut true)
        (log/warnf "catchup/query-axioms %s branch exceeded budget (%sms) — cancelled, partial results"
                   label budget-ms)
        [])
      (catch Throwable t
        (log/warnf t "catchup/query-axioms %s branch failed — partial results" label)
        []))))

(def ^:private axioms-cache-ttl-ms
  "Per-project TTL for query-axioms results. Axioms churn rarely; a 5-min
   cache eliminates repeated Chroma cold-path type-filter scans that blow
   through the 20s budget on projects with few/no axioms."
  (* 5 60 1000))

(def ^:private axioms-cache
  "{project-id {:result [...] :expires-at epoch-ms :stored-at epoch-ms}}"
  (atom {}))

(def ^:private axioms-refreshing
  "Set of project-ids currently being refreshed in the background. Gates
   against thundering-herd when several catchup calls race on a stale entry."
  (atom #{}))

(defn invalidate-axioms-cache!
  "Drop cached axiom results. Call after add/update/delete of axiom entries."
  ([] (reset! axioms-cache {}))
  ([project-id] (swap! axioms-cache dissoc project-id)))

(defn- fetch-axioms-sync!
  "Synchronous fetch with budget. Stores result in cache, returns it.

   Axioms are global: every `type=axiom` entry is visible from every project,
   including those authored under siblings that are neither ancestors nor
   descendants. We therefore skip the hierarchy + scope-piercing filters and
   hit the store with an unscoped `type=axiom` scan."
  [project-id now]
  (let [store (mem-proto/get-store)
        formal-deadline (+ now axioms-formal-budget-ms)
        f-formal (pool/with-catchup
                   (rescue-interrupt "catchup/query-axioms" []
                     (->> (mem-proto/query-entries
                            store
                            {:type "axiom"
                             :limit 200
                             :output-fields hier/metadata-projection})
                          (sort-by :created #(compare %2 %1))
                          (take 100)
                          vec)))
        formal (deref-with-deadline f-formal formal-deadline "formal"
                                    axioms-formal-budget-ms)]
    (swap! axioms-cache assoc project-id
           {:result formal
            :expires-at (+ now axioms-cache-ttl-ms)
            :stored-at now})
    formal))

(defn- trigger-refresh!
  "Fire-and-forget background refresh of axioms cache — stale-while-revalidate.
   Gated by `axioms-refreshing` to avoid thundering-herd: only the caller that
   actually adds project-id to the in-flight set submits the refresh task.
   Errors are logged via rescue-log; the in-flight slot is always released."
  [project-id]
  (let [[old new] (swap-vals! axioms-refreshing
                              (fn [s] (if (contains? s project-id) s (conj s project-id))))]
    (when (not= old new)
      (pool/with-catchup
        (rescue-log "catchup/query-axioms:refresh" nil
          (fetch-axioms-sync! project-id (System/currentTimeMillis)))
        (swap! axioms-refreshing disj project-id)))))

(defn query-axioms
  "Query axiom entries via the formal `type=axiom` branch.

   Stale-while-revalidate cache: a hit within TTL returns immediately; an
   expired hit also returns immediately and triggers a background refresh
   so the next call sees fresh data without blocking this one. Cold first
   call on a project pays the synchronous `axioms-formal-budget-ms` cost.
   Use `invalidate-axioms-cache!` after mutating axioms."
  [project-id]
  (let [now   (System/currentTimeMillis)
        hit   (get @axioms-cache project-id)
        fresh (and hit (< now (:expires-at hit)))]
    (cond
      fresh         (:result hit)
      hit           (do (trigger-refresh! project-id) (:result hit))
      :else         (fetch-axioms-sync! project-id now))))

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

(defn- has-tag? [entry tag]
  (contains? (set (:tags entry)) tag))

(defn- index-by-id
  "Build an {id → entry} index from a coll of entries."
  [entries]
  (into {} (map (juxt :id identity)) entries))

(def ^:private hydrate-chunk-size
  "Split batch-get into N-sized chunks fanned out in parallel.
   Milvus HTTP /get is roughly linear in row count but parallel chunks
   overlap HTTP round-trip + server work. Sweep on 180 rows × 13 fields:
     90×2=70s, 45×4=32s, 30×6=25s, 20×9=17s, 15×12=14s.
   Chunk=20 balances concurrency with per-call overhead (~10x vs single-shot)."
  20)

(def ^:private hydrate-chunk-budget-ms
  "Per-chunk timeout for hydrate fan-out. 20 rows × 13 fields lands ~2-3s
   cold; 20s leaves 6x headroom before bounded-pmap fallback kicks in."
  20000)

(defn- batch-fetch-content
  "Best-effort batch fetch from IMemoryStoreBatch, chunked + parallel via
   hive-weave bounded-pmap. Returns [] when the active store doesn't
   implement batch reads; timed-out chunks contribute []."
  [ids]
  (let [store (mem-proto/get-store)]
    (cond
      (not (and (seq ids) (mem-proto/batch-store? store)))
      []

      (<= (count ids) hydrate-chunk-size)
      (rescue [] (mem-proto/get-entries store ids))

      :else
      (let [chunks (mapv vec (partition-all hydrate-chunk-size ids))
            fetch  (fn [c] (mem-proto/get-entries store c))]
        (vec (mapcat identity
                     (wpar/bounded-pmap
                       {:concurrency (count chunks)
                        :timeout-ms  hydrate-chunk-budget-ms
                        :fallback    []}
                       fetch chunks)))))))

(defn- merge-hydrated
  "Prefer the hydrated entry when present; keep :distance from the
   metadata shell (vector-search scores don't survive batch-get)."
  [meta-entry hydrated-entry]
  (if hydrated-entry
    (cond-> hydrated-entry
      (:distance meta-entry) (assoc :distance (:distance meta-entry)))
    meta-entry))

(defn- hydrate-content
  "Phase-2 content re-hydration. Takes metadata-only entries, batch-fetches
   their full content in one RPC, and returns hydrated entries in the same
   order. Called after phase-1 trimming so we only pay content-transport
   cost for the small, display-bound subset."
  [entries]
  (if-not (seq entries)
    entries
    (rescue entries
      (let [by-id (-> (mapv :id entries) batch-fetch-content index-by-id)]
        (mapv #(merge-hydrated % (get by-id (:id %))) entries)))))

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
