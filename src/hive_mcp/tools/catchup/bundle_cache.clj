(ns hive-mcp.tools.catchup.bundle-cache
  "Process-wide cache for the per-project catchup bundle.

   Two tiers, both in memory and shared by every session on this JVM:

     bundle   project-id -> {:value bundle :stored-at ms :store-ref ref}
              `cached-bundle` serves a fresh entry as is, serves a stale one
              while a background refresh runs, and computes a cold one under
              single-flight so concurrent callers for the same project share
              one computation.
     content  entry-id -> {:value entry :stored-at ms :store-ref ref}
              `cached-entries` hands hydration the entries it already holds
              and fetches only the missing ids.

   An entry is only served for the store instance it was computed against
   (`:store-ref`, a weak reference): a swapped :default store starts cold.

   Invalidation is PULLED from `hive-mcp.memory.write-events`: the cache
   registers `invalidate!` as a synchronous listener, so a write of a
   bundle-relevant type drops every bundle before `notify!` returns, and an
   update or a delete also evicts that id from the content tier.
   `fresh-ttl-ms` and `max-age-ms` remain the safety net for writes that
   never pass through the notifier (another JVM, a direct backend edit).

   A bundle whose buckets are all empty is returned but never stored.
   `now-ms` is the clock; tests pin it with with-redefs."
  (:require [hive-mcp.memory.write-events :as write-events]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.catchup.axiom-cache :as axc]
            [hive-mcp.dns.result :refer [rescue rescue-log]]
            [clojure.tools.logging :as log])
  (:import [java.lang.ref WeakReference]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Knobs
;; =============================================================================

(def fresh-ttl-ms
  "Age below which a cached bundle is served without any refresh. Matches the
   axiom-cache TTL and the hive-cache :memory domain default (5 min)."
  (* 5 60 1000))

(def max-age-ms
  "Age at which a cached bundle or content entry is treated as absent (30 min).
   Between `fresh-ttl-ms` and this, a bundle is served stale while a
   background refresh runs."
  (* 30 60 1000))

(def content-max-entries
  "Cap on the content tier. On overflow the oldest entries are dropped."
  4096)

(def bundle-types
  "Memory types that can land in a catchup bucket (see bundle/split-by-type).
   A write of any other type leaves the bundle tier alone."
  #{"axiom" "axiom-candidate" "principle" "convention" "decision" "snippet" "note"})

(defn now-ms
  "Clock. with-redefs this in tests."
  []
  (System/currentTimeMillis))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private bundles    (atom {}))
(defonce ^:private content    (atom {}))
(defonce ^:private refreshing (atom #{}))
(defonce ^:private in-flight  (atom {}))
(defonce ^:private counters   (atom {:hit 0 :stale 0 :miss 0 :content-hit 0 :content-miss 0
                                     :invalidations 0}))

(defn- count! [k] (swap! counters update k (fnil inc 0)))

(defn stats
  "Diagnostic snapshot: counters plus tier sizes."
  []
  (assoc @counters
         :bundles (count @bundles)
         :content (count @content)
         :refreshing (count @refreshing)
         :in-flight (count @in-flight)))

(defn reset-cache!
  "Drop both tiers and the counters. For tests."
  []
  (reset! bundles {})
  (reset! content {})
  (reset! refreshing #{})
  (reset! counters {:hit 0 :stale 0 :miss 0 :content-hit 0 :content-miss 0 :invalidations 0})
  nil)

;; =============================================================================
;; Single flight
;; =============================================================================

(defrecord ^:private Failed [cause])

(defn single-flight
  "Run `compute-fn` for `key` unless a computation for `key` is already in
   flight, in which case wait for it and return its result. A failure is
   re-thrown to the computing caller and surfaced to waiters as an ex-info
   carrying the cause."
  [key compute-fn]
  (let [p        (promise)
        [old _]  (swap-vals! in-flight (fn [m] (if (contains? m key) m (assoc m key p))))]
    (if-let [existing (get old key)]
      (let [v @existing]
        (if (instance? Failed v)
          (throw (ex-info "single-flight compute failed" {:key key} (:cause v)))
          v))
      (try
        (let [v (compute-fn)]
          (deliver p v)
          v)
        (catch Throwable t
          (deliver p (->Failed t))
          (throw t))
        (finally
          (swap! in-flight dissoc key))))))

;; =============================================================================
;; Bundle tier
;; =============================================================================

(defn- cacheable?
  "A bundle map with at least one non-empty bucket. An all-empty bundle is
   what a stalled or absent store produces and must not be remembered."
  [bundle]
  (and (map? bundle) (boolean (some seq (vals bundle)))))

(defn- current-store
  "The :default store, or nil when none is registered."
  []
  (rescue nil (when (mem-proto/store-set?) (mem-proto/get-store))))

(defn- same-store?
  "True when `entry` was computed against `store` (identity, through the
   weak reference)."
  [entry store]
  (identical? store (some-> ^WeakReference (:store-ref entry) .get)))

(defn- age-ms [entry now] (- now (:stored-at entry)))

(defn- compute-and-store!
  [project-id compute-fn]
  (let [store (current-store)
        v     (compute-fn)]
    (if (cacheable? v)
      (swap! bundles assoc project-id {:value v
                                       :stored-at (now-ms)
                                       :store-ref (WeakReference. store)})
      (log/warn "catchup bundle-cache: not caching an empty bundle for" project-id))
    v))

(defn- refresh-in-background!
  "Stale-while-revalidate. Only the caller that adds `project-id` to the
   refreshing set submits the refresh."
  [project-id compute-fn]
  (let [[old new] (swap-vals! refreshing conj project-id)]
    (when (not= old new)
      (future
        (try
          (single-flight [:bundle project-id] #(compute-and-store! project-id compute-fn))
          (catch Throwable t
            (log/warn t "catchup bundle-cache: background refresh failed for" project-id))
          (finally
            (swap! refreshing disj project-id)))))))

(declare ensure-subscribed!)

(defn cached-bundle
  "The catchup bundle for `project-id`, computing it with `compute-fn`
   (0-arity) when the cache holds nothing usable for the current store.

     fresh  (age < fresh-ttl-ms)            -> cached value
     stale  (fresh-ttl-ms <= age < max-age) -> cached value; refresh in background
     cold   (absent, other store, age >= max-age) -> compute under single-flight"
  [project-id compute-fn]
  (ensure-subscribed!)
  (let [now   (now-ms)
        store (current-store)
        hit   (let [e (get @bundles project-id)]
                (when (and e (same-store? e store)) e))
        age   (when hit (age-ms hit now))]
    (cond
      (and hit (< age fresh-ttl-ms))
      (do (count! :hit) (:value hit))

      (and hit (< age max-age-ms))
      (do (count! :stale)
          (refresh-in-background! project-id compute-fn)
          (:value hit))

      :else
      (do (count! :miss)
          (single-flight [:bundle project-id] #(compute-and-store! project-id compute-fn))))))

;; =============================================================================
;; Content tier
;; =============================================================================

(defn- trim-content
  "Keep at most `content-max-entries`, dropping the oldest first."
  [m]
  (if (<= (count m) content-max-entries)
    m
    (->> m
         (sort-by (comp :stored-at val) >)
         (take content-max-entries)
         (into {}))))

(defn- store-content!
  [entries-by-id now store]
  (let [ref (WeakReference. store)]
    (swap! content
           (fn [m]
             (trim-content
              (reduce-kv (fn [acc id entry]
                           (assoc acc id {:value entry :stored-at now :store-ref ref}))
                         m entries-by-id))))))

(defn cached-entries
  "{id -> entry} for `ids`. Ids fresh in the content tier for the current
   store are answered from it; the rest are fetched in one call to
   `fetch-fn` (ids -> seq of entry maps) and stored. Ids the fetch does not
   return are absent from the result and are not cached."
  [ids fetch-fn]
  (let [ids      (vec (distinct (remove nil? ids)))
        now      (now-ms)
        store    (current-store)
        snapshot @content
        hits     (into {}
                       (keep (fn [id]
                               (when-let [e (get snapshot id)]
                                 (when (and (same-store? e store)
                                            (< (age-ms e now) max-age-ms))
                                   [id (:value e)]))))
                       ids)
        missing  (vec (remove #(contains? hits %) ids))
        fetched  (if (seq missing)
                   (into {} (map (juxt :id identity)) (rescue [] (fetch-fn missing)))
                   {})]
    (swap! counters update :content-hit  (fnil + 0) (count hits))
    (swap! counters update :content-miss (fnil + 0) (count missing))
    (when (seq fetched) (store-content! fetched now store))
    (merge hits fetched)))

;; =============================================================================
;; Invalidation
;; =============================================================================

(defn- bundle-relevant?
  "A write whose type can reach a catchup bucket. An unknown type is treated
   as relevant."
  [{:keys [memory-type]}]
  (or (nil? memory-type)
      (contains? bundle-types (name memory-type))))

(defn invalidate!
  "Apply one write {:op :added|:updated|:deleted :id ... :memory-type ...}.
   Evicts the id from the content tier, drops every bundle when the write is
   bundle-relevant, and drops the axiom cache when the type is axiom or
   convention. Returns nil."
  [{:keys [id memory-type] :as write}]
  (count! :invalidations)
  (when id (swap! content dissoc id))
  (when (bundle-relevant? write)
    (reset! bundles {}))
  (when (contains? #{"axiom" "convention"} (some-> memory-type name))
    (axc/invalidate-axioms-cache!))
  nil)

(defn evict-stale!
  "Drop bundle and content entries older than `max-age-ms`. Returns the
   number of entries evicted. Registered with the stale-cache sweeper."
  []
  (let [now   (now-ms)
        live? (fn [[_ e]] (< (age-ms e now) max-age-ms))
        [b0 b1] (swap-vals! bundles #(into {} (filter live?) %))
        [c0 c1] (swap-vals! content #(into {} (filter live?) %))]
    (+ (- (count b0) (count b1))
       (- (count c0) (count c1)))))

;; =============================================================================
;; Write-event subscription (pull from the channel bus)
;; =============================================================================

(defn ensure-subscribed!
  "Register `invalidate!` as a write-events listener. Idempotent; never
   throws."
  []
  (rescue-log "catchup bundle-cache: register listener" nil
    (write-events/register-listener! ::invalidate invalidate!))
  nil)

(defn subscribed?
  "True once `invalidate!` is registered with write-events."
  []
  (contains? (write-events/listener-keys) ::invalidate))
