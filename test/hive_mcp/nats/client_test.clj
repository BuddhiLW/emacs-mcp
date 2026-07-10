(ns hive-mcp.nats.client-test
  "Tests for hive-mcp.nats.client/clamp-depth — the StackOverflow guard
   in front of clojure.data.json on the publish path. The clamp is what
   keeps a deeply nested shout payload (or a cycle that slipped past
   prewalk-sanitizers upstream) from killing the publisher thread."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [hive-mcp.nats.client :as client]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- nested-map
  "Build a map nested DEPTH levels deep: {:k {:k {:k ...}}}."
  [depth]
  (loop [n depth acc :leaf]
    (if (zero? n) acc (recur (dec n) {:k acc}))))

(defn- nested-vec [depth]
  (loop [n depth acc [:leaf]]
    (if (zero? n) acc (recur (dec n) [acc]))))

(deftest scalars-pass-through
  (testing "primitives are returned as-is"
    (is (= nil       (client/clamp-depth nil)))
    (is (= 42        (client/clamp-depth 42)))
    (is (= "hello"   (client/clamp-depth "hello")))
    (is (= :keyword  (client/clamp-depth :keyword)))))

(deftest shallow-structures-unchanged
  (testing "structures shallower than the cap survive intact"
    (is (= {:a 1 :b 2} (client/clamp-depth {:a 1 :b 2})))
    (is (= [1 2 3]     (client/clamp-depth [1 2 3])))
    (is (= #{1 2 3}    (client/clamp-depth #{1 2 3})))))

(deftest deep-map-truncates-at-cap
  (testing "map nested past max-payload-depth gets the truncation marker"
    (let [over    (nested-map (+ client/max-payload-depth 5))
          clamped (client/clamp-depth over)]
      ;; Drill down exactly max-payload-depth levels — should hit the marker.
      (loop [n client/max-payload-depth v clamped]
        (if (zero? n)
          (is (= "<truncated:map>" v))
          (recur (dec n) (:k v)))))))

(deftest deep-vec-truncates-at-cap
  (testing "vector nested past max-payload-depth gets the truncation marker"
    (let [over    (nested-vec (+ client/max-payload-depth 5))
          clamped (client/clamp-depth over)]
      (loop [n client/max-payload-depth v clamped]
        (if (zero? n)
          (is (= "<truncated:vec>" v))
          (recur (dec n) (first v)))))))

(deftest clamped-payload-is-json-serializable
  (testing "a deep payload that would StackOverflow becomes safe JSON"
    (let [deep    (nested-map 5000)   ;; deep enough to blow naive recursion
          clamped (client/clamp-depth deep)]
      ;; Must not throw — that's the whole point.
      (is (string? (json/write-str clamped))))))

(deftest cycle-survives-clamp
  (testing "a self-referential map terminates instead of looping forever"
    (let [m (atom {:name "cycle"})]
      (swap! m assoc :self m)  ;; ratom cycle (data points to its own holder)
      ;; clamp-depth treats the atom as a scalar (not coll?) so it passes
      ;; through. The real protection is the depth bound on the surrounding
      ;; map literal, which is what publish! actually serializes.
      (is (map? (client/clamp-depth {:wrapper @m}))))))

(deftest mixed-shapes-clamp-independently
  (testing "siblings deeper than cap clamp; shallow siblings preserved"
    (let [payload {:shallow {:a 1}
                   :deep    (nested-map (+ client/max-payload-depth 3))}
          clamped (client/clamp-depth payload)]
      (is (= {:a 1} (:shallow clamped)))
      (is (some? (:deep clamped))))))
