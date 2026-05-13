;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.engine.bounded.lru
  "LRU-evict bounded queue impls (ENGINE-L1.3).

   Two `IBoundedQueue` impls live here:

   - `LruQueue`  — flat FIFO with cap-by-count and drop-oldest eviction.
   - `LruByKey`  — per-key sub-queue, each capped by `:per-key-cap`,
                   with a top-level `:capacity` over the *number of keys*
                   (oldest key evicted when crossing the top cap).

   Both delegate every mutation to the pure helpers near the top of the
   file so the state-machine is testable as data. The defrecord layer
   only owns the atom and the protocol dispatch.

   Policies supported: `:drop-oldest` (default), `:drop-newest`."
  (:require [hive-mcp.engine.bounded.protocol :as bp]))

;; -----------------------------------------------------------------------------
;; Pure data helpers — flat queue
;; -----------------------------------------------------------------------------

(defn- evict-oldest [^clojure.lang.PersistentQueue q]
  (let [victim (peek q)
        rest   (pop q)]
    [rest victim]))

(defn empty-queue
  "Initial state for a flat LruQueue: a clojure.lang.PersistentQueue."
  []
  clojure.lang.PersistentQueue/EMPTY)

(defn offer-flat
  "Pure step. Returns `[next-state outcome-map]`.
   outcome-map fields:
     :outcome  — :added | :rejected | :added-and-evicted
     :evicted  — the displaced item (when outcome includes eviction)"
  [{:keys [q capacity policy] :as state} item]
  (let [n (count q)]
    (cond
      (< n capacity)
      [(assoc state :q (conj q item) :added (inc (:added state)))
       {:outcome :added}]

      (= :drop-newest policy)
      [(assoc state :rejected (inc (:rejected state)))
       {:outcome :rejected :evicted item}]

      :else                                       ; :drop-oldest (default)
      (let [[shrunk victim] (evict-oldest q)]
        [(-> state
             (assoc :q (conj shrunk item))
             (update :added inc)
             (update :evicted inc))
         {:outcome :added-and-evicted :evicted victim}]))))

(defn drain-flat
  "Pure step. Returns `[next-state drained-payload]`."
  [{:keys [q] :as state}]
  [(assoc state :q (empty-queue)) (vec q)])

(defn- new-flat-state
  [{:keys [capacity policy]}]
  {:q        (empty-queue)
   :capacity capacity
   :policy   (or policy :drop-oldest)
   :added    0
   :evicted  0
   :rejected 0})

;; -----------------------------------------------------------------------------
;; LruQueue — flat queue
;; -----------------------------------------------------------------------------

(deftype LruQueue [state-atom]
  bp/IBoundedQueue

  (q-offer! [_ item]
    (let [outcome (volatile! nil)]
      (swap! state-atom
             (fn [s]
               (let [[s' o] (offer-flat s item)]
                 (vreset! outcome o)
                 s')))
      @outcome))

  (q-offer-key! [this _k item] (.q-offer! this item))

  (q-drain! [_]
    (let [payload (volatile! nil)]
      (swap! state-atom
             (fn [s]
               (let [[s' p] (drain-flat s)]
                 (vreset! payload p)
                 s')))
      @payload))

  (q-snapshot [_] (vec (:q @state-atom)))

  (q-size [_] (count (:q @state-atom)))

  (q-stats [_]
    (let [s @state-atom]
      {:size     (count (:q s))
       :capacity (:capacity s)
       :policy   (:policy s)
       :added    (:added s)
       :evicted  (:evicted s)
       :rejected (:rejected s)})))

(defn make-queue
  "Build a flat bounded `IBoundedQueue`.
     :capacity  — required positive int
     :policy    — :drop-oldest (default) | :drop-newest"
  [{:keys [capacity policy] :as opts}]
  {:pre [(pos-int? capacity)
         (contains? #{nil :drop-oldest :drop-newest} policy)]}
  (->LruQueue (atom (new-flat-state opts))))

;; -----------------------------------------------------------------------------
;; Pure data helpers — per-key queue
;; -----------------------------------------------------------------------------

(defn- offer-into-key
  "Insert into the sub-queue for `k`. Eviction respects `:per-key-cap`.
   Returns `[next-by-key per-key-outcome]`."
  [by-key k item per-key-cap policy]
  (let [sub (or (get by-key k) (empty-queue))
        n   (count sub)]
    (cond
      (< n per-key-cap)
      [(assoc by-key k (conj sub item)) {:outcome :added}]

      (= :drop-newest policy)
      [by-key {:outcome :rejected :evicted item}]

      :else
      (let [[shrunk victim] (evict-oldest sub)]
        [(assoc by-key k (conj shrunk item))
         {:outcome :added-and-evicted :evicted victim}]))))

(defn- evict-oldest-key
  "Drop the LRU key from `by-key` + `key-order`. Returns `[by-key' key-order' victim-entry]`."
  [by-key key-order]
  (let [vk      (first key-order)
        rest    (rest key-order)
        victim  [vk (vec (get by-key vk))]]
    [(dissoc by-key vk) (vec rest) victim]))

(defn offer-keyed
  "Pure step. Returns `[next-state outcome-map]`.
   - `:capacity` is the cap over *number of keys* — when a brand-new
     key would push the key count over capacity, the LRU key (and all
     its items) is evicted as `:evicted-key`."
  [{:keys [by-key key-order capacity per-key-cap policy] :as state} k item]
  (if (contains? by-key k)
    (let [[by-key' o] (offer-into-key by-key k item per-key-cap policy)]
      [(cond-> (assoc state :by-key by-key' :key-order (vec (concat (remove #{k} key-order) [k])))
         (= :added (:outcome o))             (update :added inc)
         (= :added-and-evicted (:outcome o)) (-> (update :added inc) (update :evicted inc))
         (= :rejected (:outcome o))          (update :rejected inc))
       (assoc o :key k)])
    ;; new key
    (let [next-key-count (inc (count by-key))]
      (if (<= next-key-count capacity)
        (let [[by-key' o] (offer-into-key by-key k item per-key-cap policy)]
          [(-> state
               (assoc :by-key by-key' :key-order (vec (concat key-order [k])))
               (update :added inc))
           (assoc o :key k)])
        ;; over the key cap — drop LRU key first
        (let [[by-key' key-order' victim] (evict-oldest-key by-key key-order)
              [by-key'' o]                (offer-into-key by-key' k item per-key-cap policy)]
          [(-> state
               (assoc :by-key    by-key''
                      :key-order (vec (concat key-order' [k])))
               (update :added inc)
               (update :evicted-keys inc))
           (assoc o :key k :evicted-key victim)])))))

(defn drain-keyed
  [{:keys [by-key] :as state}]
  [(-> state (assoc :by-key {} :key-order []))
   (into {} (map (fn [[k q]] [k (vec q)])) by-key)])

(defn- new-keyed-state
  [{:keys [capacity per-key-cap policy]}]
  {:by-key       {}
   :key-order    []
   :capacity     capacity                 ; max distinct keys
   :per-key-cap  per-key-cap              ; per-key sub-queue cap
   :policy       (or policy :drop-oldest)
   :added        0
   :evicted      0
   :rejected     0
   :evicted-keys 0})

;; -----------------------------------------------------------------------------
;; LruByKey — per-key bounded buffer
;; -----------------------------------------------------------------------------

(deftype LruByKey [state-atom]
  bp/IBoundedQueue

  (q-offer! [_ _item]
    (throw (UnsupportedOperationException.
            "LruByKey requires a key — use q-offer-key!.")))

  (q-offer-key! [_ k item]
    (let [outcome (volatile! nil)]
      (swap! state-atom
             (fn [s]
               (let [[s' o] (offer-keyed s k item)]
                 (vreset! outcome o)
                 s')))
      @outcome))

  (q-drain! [_]
    (let [payload (volatile! nil)]
      (swap! state-atom
             (fn [s]
               (let [[s' p] (drain-keyed s)]
                 (vreset! payload p)
                 s')))
      @payload))

  (q-snapshot [_]
    (into {} (map (fn [[k q]] [k (vec q)])) (:by-key @state-atom)))

  (q-size [_] (reduce + 0 (map count (vals (:by-key @state-atom)))))

  (q-stats [_]
    (let [s @state-atom]
      {:size         (reduce + 0 (map count (vals (:by-key s))))
       :keys         (count (:by-key s))
       :capacity     (:capacity s)
       :per-key-cap  (:per-key-cap s)
       :policy       (:policy s)
       :added        (:added s)
       :evicted      (:evicted s)
       :rejected     (:rejected s)
       :evicted-keys (:evicted-keys s)})))

(defn make-by-key
  "Build a per-key bounded `IBoundedQueue`.
     :capacity    — max distinct keys (LRU-evicted on overflow)
     :per-key-cap — per-key sub-queue cap
     :policy      — :drop-oldest (default) | :drop-newest"
  [{:keys [capacity per-key-cap] :as opts}]
  {:pre [(pos-int? capacity) (pos-int? per-key-cap)]}
  (->LruByKey (atom (new-keyed-state opts))))
