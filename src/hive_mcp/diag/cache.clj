(ns hive-mcp.diag.cache
  "ICacheProbe adapter for datalevin's process-wide query-result cache — the
   classic \"invisible retainer\": a private `defonce ConcurrentHashMap` inside
   the datalevin library (datalevin.db/caches), keyed by (dir store), each value
   an `datalevin.utl.LRUCache` holding fully-realized datalog result vectors. It
   is reachable from NO application var, so a var scan never finds it; this probe
   makes it addressable, inspectable, and evictable.

   IO-boundary adapter: it reaches into a library-private field by reflection.
   That coupling is deliberately isolated HERE, behind the ICacheProbe contract,
   so the clinic and every other adapter stay ignorant of datalevin internals.
   If datalevin is not on the classpath, every method degrades to an `err`
   Result — the probe is inert, not fatal."
  (:require [hive-spi.diag.ports :as p]
            [hive-dsl.result :as r]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def ^:private cache-kw :datalevin/query-result)

(defn- caches-map
  "The datalevin.db/caches ConcurrentHashMap, or nil when datalevin is absent."
  []
  (try
    (some-> (requiring-resolve 'datalevin.db/caches) deref)
    (catch Throwable _ nil)))

(defn- lru-field
  "Read a private field `nm` off an `datalevin.utl.LRUCache` instance (reflective;
   never realizes cached values)."
  [lru nm]
  (try
    (let [f (.getDeclaredField (class lru) nm)]
      (.setAccessible f true)
      (.get f lru))
    (catch Throwable _ nil)))

(defn- store-key->str [k]
  ;; datalevin keys the cache by (dir store); the first element is the dir path.
  (str (if (coll? k) (first k) k)))

(defrecord DatalevinQueryCache []
  p/ICacheProbe

  (cache-id [_] cache-kw)

  (cache-occupancy [_]
    (if-let [caches (caches-map)]
      (r/ok
       {:diag/cache-id cache-kw
        :diag/stores
        (vec (for [k (enumeration-seq (.keys ^java.util.concurrent.ConcurrentHashMap caches))
                   :let [lru (.get ^java.util.concurrent.ConcurrentHashMap caches k)
                         entry-map (lru-field lru "map")]]
               (cond-> {:diag/store (store-key->str k)
                        :diag/entries (when (instance? java.util.Map entry-map)
                                        (.size ^java.util.Map entry-map))}
                 (lru-field lru "capacity")
                 (assoc :diag/capacity (long (lru-field lru "capacity"))))))})
      (r/err :diag/cache-unavailable {:cache-id cache-kw :reason "datalevin.db/caches not resolvable"})))

  (evict-cache! [_]
    (if-let [caches (caches-map)]
      (let [rt (Runtime/getRuntime)
            used #(- (.totalMemory rt) (.freeMemory rt))
            before (used)]
        ;; Pure performance cache: clearing only forces query recomputation.
        (doseq [k (enumeration-seq (.keys ^java.util.concurrent.ConcurrentHashMap caches))]
          (let [lru (.get ^java.util.concurrent.ConcurrentHashMap caches k)
                entry-map (lru-field lru "map")]
            (when (instance? java.util.Map entry-map)
              (.clear ^java.util.Map entry-map))))
        (dotimes [_ 2] (System/gc) (Thread/sleep 400))
        (let [after (used)]
          (r/ok {:diag/used-before-bytes before
                 :diag/used-after-bytes after
                 :diag/reclaimed-bytes (- before after)})))
      (r/err :diag/cache-unavailable {:cache-id cache-kw :reason "datalevin.db/caches not resolvable"}))))

(defn make-datalevin-query-cache
  "Construct the datalevin query-result ICacheProbe. Zero-config: it locates the
   library-private cache registry lazily on each call, so it is safe to build
   even before any datalevin store is opened."
  []
  (->DatalevinQueryCache))
