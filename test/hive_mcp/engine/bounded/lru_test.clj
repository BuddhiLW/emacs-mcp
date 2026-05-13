;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.engine.bounded.lru-test
  "Trifecta tests for ENGINE-L1.3 bounded primitives.

   - **Property** facet exercises the cap invariant under 200 random
     loads (per record type) via `deftrifecta`.
   - **Golden** deftests pin the concrete eviction semantics (FIFO
     drop-oldest, key promotion, drain reset) that callers rely on."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-mcp.engine.bounded.protocol :as bp]
            [hive-mcp.engine.bounded.lru      :as lru]
            [hive-test.trifecta :refer [deftrifecta]]))

;; -----------------------------------------------------------------------------
;; Generators
;; -----------------------------------------------------------------------------

(def gen-policy
  (gen/elements [:drop-oldest :drop-newest]))

(def gen-flat-args
  (gen/let [cap    (gen/choose 1 8)
            policy gen-policy
            n      (gen/choose 0 50)
            items  (gen/vector gen/small-integer n)]
    [cap policy items]))

(defn run-flat
  "Build a queue, replay `items`, return its current size + capacity."
  [cap policy items]
  (let [q (lru/make-queue {:capacity cap :policy policy})]
    (doseq [x items] (bp/q-offer! q x))
    {:size     (bp/q-size q)
     :capacity cap}))

(def gen-keyed-args
  (gen/let [cap         (gen/choose 1 5)
            per-key-cap (gen/choose 1 5)
            n           (gen/choose 0 200)
            ks          (gen/vector (gen/choose 0 20) n)
            vs          (gen/vector gen/small-integer n)]
    [cap per-key-cap (mapv vector ks vs)]))

(defn run-keyed
  [cap per-key-cap entries]
  (let [q (lru/make-by-key {:capacity cap :per-key-cap per-key-cap})]
    (doseq [[k v] entries] (bp/q-offer-key! q k v))
    {:size        (bp/q-size q)
     :keys        (:keys (bp/q-stats q))
     :capacity    cap
     :per-key-cap per-key-cap}))

;; -----------------------------------------------------------------------------
;; Properties — cap invariant under random load
;; -----------------------------------------------------------------------------

(deftrifecta lru-queue-bounded-size
  hive-mcp.engine.bounded.lru-test/run-flat
  {:gen   gen-flat-args
   :apply? true
   :pred  (fn [{:keys [size capacity]}]
            (<= size capacity))
   :num-tests 200})

(deftrifecta lru-by-key-bounded-size
  hive-mcp.engine.bounded.lru-test/run-keyed
  {:gen   gen-keyed-args
   :apply? true
   :pred  (fn [{:keys [size keys capacity per-key-cap]}]
            (and (<= keys capacity)
                 (<= size (* capacity per-key-cap))))
   :num-tests 200})

;; -----------------------------------------------------------------------------
;; Goldens — flat LruQueue
;; -----------------------------------------------------------------------------

(deftest drop-oldest-evicts-fifo-with-victim
  (let [q (lru/make-queue {:capacity 2})]
    (bp/q-offer! q :a) (bp/q-offer! q :b)
    (let [r (bp/q-offer! q :c)]
      (is (= :added-and-evicted (:outcome r)))
      (is (= :a (:evicted r))))
    (is (= [:b :c] (bp/q-snapshot q)))))

(deftest drop-newest-rejects-incoming
  (let [q (lru/make-queue {:capacity 2 :policy :drop-newest})]
    (bp/q-offer! q :a) (bp/q-offer! q :b)
    (let [r (bp/q-offer! q :c)]
      (is (= :rejected (:outcome r)))
      (is (= :c (:evicted r))))
    (is (= [:a :b] (bp/q-snapshot q)))))

(deftest drain-atomically-resets-state
  (let [q (lru/make-queue {:capacity 5})]
    (doseq [x [:a :b :c]] (bp/q-offer! q x))
    (is (= [:a :b :c] (bp/q-drain! q)))
    (is (= 0 (bp/q-size q)))))

(deftest stats-reflect-cumulative-counters
  (let [q (lru/make-queue {:capacity 2})]
    (doseq [x [:a :b :c :d]] (bp/q-offer! q x))
    (let [s (bp/q-stats q)]
      (is (= 2 (:size s)))
      (is (= 4 (:added s)))
      (is (= 2 (:evicted s)))
      (is (= 0 (:rejected s))))))

;; -----------------------------------------------------------------------------
;; Goldens — per-key LruByKey
;; -----------------------------------------------------------------------------

(deftest per-key-cap-evicts-oldest-for-key
  (let [q (lru/make-by-key {:capacity 5 :per-key-cap 2})]
    (bp/q-offer-key! q :foo 1)
    (bp/q-offer-key! q :foo 2)
    (let [r (bp/q-offer-key! q :foo 3)]
      (is (= :added-and-evicted (:outcome r)))
      (is (= 1 (:evicted r))))
    (is (= {:foo [2 3]} (bp/q-snapshot q)))))

(deftest key-cap-drops-lru-key-with-items
  (let [q (lru/make-by-key {:capacity 2 :per-key-cap 5})]
    (bp/q-offer-key! q :a 1)
    (bp/q-offer-key! q :b 2)
    (let [r (bp/q-offer-key! q :c 3)]
      (is (= :a (first (:evicted-key r))))
      (is (= [1] (second (:evicted-key r)))))
    (is (= #{:b :c} (set (keys (bp/q-snapshot q)))))))

(deftest touching-a-key-promotes-it-past-lru-window
  (let [q (lru/make-by-key {:capacity 2 :per-key-cap 3})]
    (bp/q-offer-key! q :a 1)
    (bp/q-offer-key! q :b 2)
    (bp/q-offer-key! q :a 3)
    (let [r (bp/q-offer-key! q :c 4)]
      (is (= :b (first (:evicted-key r))))
      (is (= [2] (second (:evicted-key r)))))
    (is (= #{:a :c} (set (keys (bp/q-snapshot q)))))))

(deftest by-key-drain-yields-map-of-per-key-vectors
  (let [q (lru/make-by-key {:capacity 5 :per-key-cap 3})]
    (bp/q-offer-key! q :a 1)
    (bp/q-offer-key! q :a 2)
    (bp/q-offer-key! q :b 9)
    (is (= {:a [1 2] :b [9]} (bp/q-drain! q)))
    (is (= 0 (bp/q-size q)))))

(deftest flat-offer-on-by-key-is-unsupported
  (let [q (lru/make-by-key {:capacity 5 :per-key-cap 3})]
    (is (thrown? UnsupportedOperationException (bp/q-offer! q :anything)))))
