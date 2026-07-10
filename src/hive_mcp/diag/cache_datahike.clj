(ns hive-mcp.diag.cache-datahike
  "ICacheProbe adapter for datahike's process-wide query-result cache —
   `datahike.query/query-result-cache`, a `defonce atom` holding a
   `datahike.lru.LRU` keyed by DB snapshot [hash max-tx max-eid], each value a
   bucket map {cache-key -> {:result r :attrs deps}}. The LRU is bounded by
   snapshot COUNT (*query-cache-size*, default 64), never by cumulative result
   SIZE, so a handful of large result sets pin arbitrary heap while the count cap
   never trips — the same invisible-retainer shape the datalevin probe addresses,
   in a different library.

   IO-boundary adapter: it reaches the library-private LRU field by reflection.
   That coupling is isolated HERE, behind ICacheProbe, so the clinic and every
   other adapter stay ignorant of datahike internals. If datahike is not on the
   classpath, every method degrades to an `err` Result — the probe is inert, not
   fatal."
  (:require [hive-spi.diag.ports :as p]
            [hive-dsl.result :as r]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def ^:private cache-kw :datahike/query-result)

(defn- cache-atom
  "The datahike.query/query-result-cache atom, or nil when datahike is absent."
  []
  (try
    (some-> (requiring-resolve 'datahike.query/query-result-cache) deref)
    (catch Throwable _ nil)))

(defn- cache-capacity
  "Current *query-cache-size* snapshot-count cap, or nil when datahike is absent."
  []
  (try
    (some-> (requiring-resolve 'datahike.query/*query-cache-size*) deref)
    (catch Throwable _ nil)))

(defn- lru->buckets
  "The {snapshot -> bucket} map inside a datahike.lru.LRU, or nil. Reflective;
   reads the field only — never realizes cached values."
  [lru]
  (letfn [(field [nm] (try
                        (let [f (.getDeclaredField (class lru) nm)]
                          (.setAccessible f true)
                          (.get f lru))
                        (catch Throwable _ nil)))]
    (or (field "key_value") (field "key-value"))))

(defn- result-size
  "Element count of one cached result, counting a scalar aggregate result as 1.
   Counts only — never realizes the result graph."
  [r]
  (cond
    (nil? r) 0
    (or (counted? r) (sequential? r) (set? r)) (count r)
    :else 1))

(defn- bucket-tuples
  "Total cached result tuples in one snapshot bucket (counts only, no
   realization)."
  [bucket]
  (if (map? bucket)
    (reduce-kv (fn [acc _ entry] (+ acc (result-size (:result entry)))) 0 bucket)
    0))

(defrecord DatahikeQueryResultCache []
  p/ICacheProbe

  (cache-id [_] cache-kw)

  (cache-occupancy [_]
    (if-let [a (cache-atom)]
      (let [buckets (lru->buckets (deref a))
            buckets (if (map? buckets) buckets {})
            entries (count buckets)
            tuples  (reduce-kv (fn [acc _ b] (+ acc (bucket-tuples b))) 0 buckets)
            cap     (cache-capacity)]
        (r/ok
         {:diag/cache-id cache-kw
          :diag/stores
          [(cond-> {:diag/store "query-result-cache"
                    :diag/entries entries
                    :diag/tuples tuples}
             cap (assoc :diag/capacity (long cap)))]}))
      (r/err :diag/cache-unavailable
             {:cache-id cache-kw :reason "datahike.query/query-result-cache not resolvable"})))

  (evict-cache! [_]
    (if-let [clear! (try (requiring-resolve 'datahike.query/clear-query-cache!)
                         (catch Throwable _ nil))]
      (let [rt (Runtime/getRuntime)
            used #(- (.totalMemory rt) (.freeMemory rt))
            before (used)]
        ;; Pure performance cache: clearing only forces query recomputation.
        (clear!)
        (dotimes [_ 2] (System/gc) (Thread/sleep 400))
        (let [after (used)]
          (r/ok {:diag/used-before-bytes before
                 :diag/used-after-bytes after
                 :diag/reclaimed-bytes (- before after)})))
      (r/err :diag/cache-unavailable
             {:cache-id cache-kw :reason "datahike.query/clear-query-cache! not resolvable"}))))

(defn make-datahike-query-cache
  "Construct the datahike query-result ICacheProbe. Zero-config: it resolves the
   library-private cache lazily on each call, so it is safe to build even before
   any datahike store is opened."
  []
  (->DatahikeQueryResultCache))
