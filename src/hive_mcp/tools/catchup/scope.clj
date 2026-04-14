(ns hive-mcp.tools.catchup.scope
  "Scope resolution and query functions for catchup workflow."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-weave.pool :as wpool]
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

(defn- compute-hierarchy-project-ids
  "Compute the full set of visible project IDs for DB-level filtering."
  [project-id]
  (let [in-project? (and project-id (not= project-id "global"))]
    (when in-project?
      (let [visible (kg-scope/visible-scopes project-id)
            descendants (kg-scope/descendant-scopes project-id)
            all-ids (distinct (concat visible descendants))]
        (vec (remove #(= "global" %) all-ids))))))

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

(defn query-scoped-entries
  "Query memory store entries filtered by project scope with hierarchy and
   scope-piercing. The two Milvus calls (hierarchy fetch + global-piercing
   fetch) are fanned out in parallel on the dedicated :catchup pool so the
   effective wall-clock is `max(hierarchy, piercing)` instead of `sum`."
  [entry-type tags project-id limit]
  (when (mem-proto/store-set?)
    (let [store (mem-proto/get-store)
          limit-val (or limit 20)
          in-project? (and project-id (not= project-id "global"))
          hierarchy-ids (compute-hierarchy-project-ids project-id)
          over-fetch-factor (if hierarchy-ids 3 4)
          pool (pool/catchup-pool)
          f-hierarchy (wpool/submit!
                       pool
                       #(try (mem-proto/query-entries
                              store
                              {:type entry-type
                               :project-ids hierarchy-ids
                               :limit (min (* limit-val over-fetch-factor) 500)})
                             (catch Throwable t
                               (log/warnf t "query-scoped-entries hierarchy branch threw [%s]" entry-type)
                               [])))
          f-global (when in-project?
                     (wpool/submit!
                      pool
                      #(try (mem-proto/query-entries
                             store
                             {:type entry-type
                              :project-id "global"
                              :limit 100})
                            (catch Throwable t
                              (log/warnf t "query-scoped-entries global-piercing branch threw [%s]" entry-type)
                              []))))
          ;; Unbounded .get here is acceptable because callers that need
          ;; latency bounds (query-axioms) wrap query-scoped-entries in
          ;; deref-with-deadline via their own submitted future, and
          ;; query-scoped-entries is itself always invoked from a
          ;; bounded outer context (catchup.clj query-timeout-ms).
          entries (.get ^Future f-hierarchy)
          global-entries (when f-global (.get ^Future f-global))
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
        hierarchy-ids (compute-hierarchy-project-ids project-id)
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
   ~5-6s on the type-filter cold path, so we budget 9.5s to stay under the
   10s catchup acceptance gate while still letting the formal branch land."
  9500)

(def ^:private axioms-legacy-budget-ms
  "Wall-clock budget for the legacy `type=convention` + tag=axiom branch.
   Kept tight because the tag predicate lives in-memory after the type
   query: if the type query itself stalls past this budget, we accept
   partial results from the formal branch rather than block the whole
   catchup."
  1500)

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

(defn query-axioms
  "Query axiom entries (both formal type and legacy tagged conventions).
   Sub-queries run in parallel on the dedicated :catchup pool (hive-weave
   bounded ThreadPoolExecutor via `hive-mcp.concurrency.pool/catchup-pool`)
   and are deref'd with a bounded deadline (`query-axioms-budget-ms`).

   Partial-tolerance: if the legacy `convention[axiom]` branch stalls on a
   Milvus cold path, it is cancelled at the deadline and the formal-type
   branch still delivers results. This replaces the prior pattern of
   `@f-legacy` (unbounded deref) which could block the outer catchup
   timeout indefinitely and drop all axioms including the ones f-formal
   already produced.

   The hive-ttracking EPIC (kanban 20260414104332-192b2da4) will later
   wrap this pattern behind `tt/track` + `deftest-tt`."
  [project-id]
  (let [pool (pool/catchup-pool)
        now (System/currentTimeMillis)
        formal-deadline (+ now axioms-formal-budget-ms)
        legacy-deadline (+ now axioms-legacy-budget-ms)
        f-formal (wpool/submit!
                  pool
                  #(try (query-scoped-entries "axiom" nil project-id 100)
                        (catch Throwable t
                          (log/warn t "catchup/query-axioms formal branch threw")
                          [])))
        f-legacy (wpool/submit!
                  pool
                  #(try (query-scoped-entries "convention" ["axiom"] project-id 100)
                        (catch Throwable t
                          (log/warn t "catchup/query-axioms legacy branch threw")
                          [])))
        ;; Deref legacy (tighter budget) first so we fail-fast on slow-tag
        ;; path, then formal with its generous budget. Order is irrelevant
        ;; for correctness — both futures already run in parallel on pool.
        legacy (deref-with-deadline f-legacy legacy-deadline "legacy" axioms-legacy-budget-ms)
        formal (deref-with-deadline f-formal formal-deadline "formal" axioms-formal-budget-ms)]
    (distinct-by :id (concat formal legacy))))

(defn query-regular-conventions
  "Query conventions excluding axioms and priority ones."
  [project-id axiom-ids priority-ids]
  (let [all-conventions (query-scoped-entries "convention" nil project-id 50)
        excluded-ids (set/union axiom-ids priority-ids)]
    (remove #(contains? excluded-ids (:id %)) all-conventions)))
