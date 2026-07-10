;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.breaker-test
  "Pure tests for ENGINE-L1.1 circuit breaker. No IO, no clock injection
   from the test side — `cb/attempt`, `record-failure!`, `record-success!`
   are exercised via in-memory atoms, and `maybe-recover` is tested with
   explicit timestamps to keep the wall-clock out of assertions."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.slots.breaker :as cb]))

(def ^:private fast-policy
  {:max-failures        2
   :initial-cooldown-ms 1000
   :max-cooldown-ms     8000})

(deftest fresh-breaker-is-closed
  (let [b (cb/fresh)]
    (is (= :closed (:state b)))
    (is (= 0 (:failures b)))
    (is (= :pass (cb/decision b)))))

(deftest closes-then-trips-on-threshold
  (testing "first failure stays :closed"
    (let [b (cb/on-failure (cb/fresh) fast-policy 100)]
      (is (= :closed (:state b)))
      (is (= 1 (:failures b)))
      (is (= :pass (cb/decision b)))))
  (testing "second failure trips :open"
    (let [b (-> (cb/fresh)
                (cb/on-failure fast-policy 100)
                (cb/on-failure fast-policy 200))]
      (is (= :open (:state b)))
      (is (= 200 (:opened-at b)))
      (is (= 1000 (:cooldown-ms b)))
      (is (= :block (cb/decision b))))))

(deftest cooldown-expiry-promotes-to-half-open
  (let [tripped (-> (cb/fresh)
                    (cb/on-failure fast-policy 100)
                    (cb/on-failure fast-policy 200))]
    (testing "stays :open while inside cooldown window"
      (is (= :open (:state (cb/maybe-recover tripped 500)))))
    (testing "promotes to :half-open once window elapses"
      (let [recovered (cb/maybe-recover tripped 1500)]
        (is (= :half-open (:state recovered)))
        (is (= :pass (cb/decision recovered)))))))

(deftest half-open-success-resets-to-closed
  (let [tripped (-> (cb/fresh)
                    (cb/on-failure fast-policy 100)
                    (cb/on-failure fast-policy 200)
                    (cb/maybe-recover 1500))]
    (is (= :half-open (:state tripped)))
    (let [healed (cb/on-success tripped)]
      (is (= :closed (:state healed)))
      (is (= 0 (:failures healed))))))

(deftest half-open-failure-extends-cooldown
  (let [tripped  (-> (cb/fresh)
                     (cb/on-failure fast-policy 100)
                     (cb/on-failure fast-policy 200)
                     (cb/maybe-recover 1500))
        re-open  (cb/on-failure tripped fast-policy 1600)]
    (is (= :open (:state re-open)))
    (is (= 2000 (:cooldown-ms re-open))
        "cooldown doubled (1000 → 2000), bounded by max-cooldown-ms")))

(deftest cooldown-caps-at-max
  (let [policy {:max-failures 1 :initial-cooldown-ms 4000 :max-cooldown-ms 5000}
        once   (cb/on-failure (cb/fresh) policy 0)
        twice  (-> (cb/maybe-recover once 4001)
                   (cb/on-failure policy 4002))]
    (is (= :open (:state twice)))
    (is (= 5000 (:cooldown-ms twice))
        "doubled cooldown clamps to max-cooldown-ms")))

(deftest atom-orchestration-blocks-after-threshold
  (let [breakers (atom {})
        slot     :carto
        b1 (cb/attempt breakers slot fast-policy)]
    (is (= :pass (cb/decision b1)))
    (cb/record-failure! breakers slot fast-policy)
    (cb/record-failure! breakers slot fast-policy)
    (is (= :block
           (cb/decision (cb/attempt breakers slot fast-policy)))
        "after 2 failures the breaker blocks new attempts")))

(deftest record-success-collapses-state
  (let [breakers (atom {})]
    (cb/record-failure! breakers :carto fast-policy)
    (cb/record-success! breakers :carto)
    (is (= :closed (:state (get @breakers :carto))))
    (is (= 0      (:failures (get @breakers :carto))))))

(deftest snapshot-reflects-current-state
  (let [breakers (atom {})]
    (cb/record-failure! breakers :carto fast-policy)
    (let [snap (cb/snapshot breakers)]
      (is (contains? snap :carto))
      (is (= 1 (:failures (:carto snap)))))))

(deftest reset!-clears-every-breaker
  (let [breakers (atom {})]
    (cb/record-failure! breakers :carto fast-policy)
    (cb/record-failure! breakers :memory fast-policy)
    (cb/reset! breakers)
    (is (empty? @breakers))))
