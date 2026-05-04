(ns hive-mcp.knowledge-graph.edges.stats
  "Edge statistics cache. Pre-aggregated counters maintained incrementally
   so that edge-stats reads are O(1).

   Lives below `hive-mcp.knowledge-graph.edges` so the CRUD ns can stay
   focused on persistence — the wiring from CRUD mutations to these
   counters is owned by `edges.stats-events`, which translates
   :kg.edges/added, :kg.edges/removed, and :kg.edges/scope-migrated
   events into delta calls below."
  (:require [hive-mcp.knowledge-graph.connection :as conn]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private cache
  ;; :scanned? — set ONLY by refresh!. Distinct from :initialized? (which
  ;; tracks "writable" state) so that events firing before the first scan
  ;; can't lock the cache into delta-only mode without a baseline.
  ;;
  ;; :scan-delay — single-flight guard. When non-nil, an in-flight cold scan
  ;; is running; concurrent ensure! callers piggyback on that delay instead
  ;; of fanning out duplicate scans against a 3M-edge store.
  (atom {:initialized? false
         :scanned?     false
         :scan-delay   nil
         :total-edges  0
         :by-relation  {}
         :by-scope     {}}))

(defn- compute-from-db!
  "Full scan of the KG to compute edge aggregates. Expensive on large graphs;
   only used for cold start and explicit refresh.

   Cold reads on a multi-million-edge Datahike store can blow past the
   60s default read-timeout when the schema/AVET page cache is empty.
   When the active backend exposes `*read-timeout-ms*` (currently the
   Datahike store), `binding` it up to 5 minutes scoped to this call
   only — so entity lookups and other fast paths keep their tight bound."
  []
  (let [run #(let [total (or (conn/query '[:find (count ?e) . :where [?e :kg-edge/id]]) 0)
                   by-relation-q '[:find ?rel (count ?e)
                                   :where
                                   [?e :kg-edge/id]
                                   [?e :kg-edge/relation ?rel]]
                   by-scope-q '[:find ?scope (count ?e)
                                :where
                                [?e :kg-edge/id]
                                [?e :kg-edge/scope ?scope]]]
               {:initialized? true
                :total-edges  total
                :by-relation  (into {} (conn/query by-relation-q))
                :by-scope     (into {} (conn/query by-scope-q))})
        timeout-var (try (requiring-resolve
                          'hive-mcp.knowledge-graph.store.datahike/*read-timeout-ms*)
                         (catch Throwable _ nil))]
    (if timeout-var
      (with-bindings* {timeout-var 300000} run)
      (run))))

(defn refresh!
  "Rebuild the edge-stats cache from a full DB scan.
   Call on startup or after bulk operations that bypass the event-driven
   CRUD path.

   Single-flight: a cold scan over 3M+ edges is expensive; concurrent
   callers piggyback on the same in-flight delay rather than fanning out
   duplicate scans against the same Datahike connection. The delay clears
   on success (so a later refresh! can re-scan) and on failure (so retry
   isn't permanently shadowed by a poisoned delay)."
  []
  (let [d (delay
            (try
              (let [computed (compute-from-db!)]
                (swap! cache
                       (fn [s]
                         (-> s
                             (merge computed)
                             (assoc :scanned? true :scan-delay nil))))
                :ok)
              (catch Throwable t
                (swap! cache assoc :scan-delay nil)
                (throw t))))
        installed
        (-> (swap-vals! cache
                        (fn [s]
                          (if (:scan-delay s)
                            s
                            (assoc s :scan-delay d))))
            second
            :scan-delay)]
    @installed
    nil))

(defn reset-cache!
  "Reset the cache to its uninitialized state. Used by test fixtures
   when the underlying store is swapped mid-process."
  []
  (reset! cache {:initialized? false
                 :scanned?     false
                 :scan-delay   nil
                 :total-edges  0
                 :by-relation  {}
                 :by-scope     {}})
  nil)

(defn- ensure! []
  (when-not (:scanned? @cache)
    (refresh!)))

(defn- bump
  "Adjust key k in map m by delta. Drops the key when the result is non-positive."
  [m k delta]
  (if (nil? k)
    m
    (let [n (+ (get m k 0) delta)]
      (if (pos? n) (assoc m k n) (dissoc m k)))))

(defn apply-delta!
  "Update the cache for a single edge add (+1) or remove (-1).
   Flips :initialized? to true — incremental maintenance is authoritative
   from this point on, so ensure! stops triggering a full refresh."
  [relation scope delta]
  (swap! cache
         (fn [s]
           (-> s
               (assoc :initialized? true)
               (update :total-edges #(max 0 (+ (or % 0) delta)))
               (update :by-relation bump relation delta)
               (update :by-scope bump scope delta)))))

(defn migrate-scope!
  "Move n edges from old-scope bucket to new-scope bucket in the cache.
   Like apply-delta!, flips :initialized? so the cache stays authoritative."
  [old-scope new-scope n]
  (when (pos? n)
    (swap! cache
           (fn [s]
             (-> s
                 (assoc :initialized? true)
                 (update :by-scope bump old-scope (- n))
                 (update :by-scope bump new-scope n))))))

(defn snapshot
  "Statistics about edges in the Knowledge Graph.

   Reads from the in-memory cache maintained by the event handlers in
   `edges.stats-events`. Lazy-initialized on first call via full DB scan.
   Call refresh! to rebuild after bulk operations that bypass the CRUD
   event path.

   Returns:
     {:total-edges <n>
      :by-relation {<relation-kw> <count>}
      :by-scope    {<scope-string> <count>}}"
  []
  (ensure!)
  (let [s @cache]
    {:total-edges (:total-edges s)
     :by-relation (:by-relation s)
     :by-scope    (:by-scope s)}))
