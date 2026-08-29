(ns hive-mcp.channel.audience-test
  "Audience routing + progress digest — pure, no fixtures, no live state."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.channel.audience :as aud]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; coordinator-reader?
;; =============================================================================

(deftest coordinator-reader?-test
  (testing "the MCP lane's reader ids, bare and project-suffixed"
    (is (aud/coordinator-reader? "coordinator"))
    (is (aud/coordinator-reader? "coordinator-hive"))
    (is (aud/coordinator-reader? nil)))
  (testing "a ling is not a coordinator"
    (is (not (aud/coordinator-reader? "vt-billing-checkpoint")))
    (is (not (aud/coordinator-reader? "ling-7")))))

;; =============================================================================
;; addressed-to? — the anti-pollution contract
;; =============================================================================

(deftest siblings-never-see-each-other-test
  (testing "a shout from one ling does NOT reach a sibling under the same parent"
    (let [msg {:agent-id "vt-billing" :parent-id "coordinator" :project-id "hive"}]
      (is (not (aud/addressed-to? "vt-media-retention" msg)))
      (is (not (aud/addressed-to? "vt-pack-trifecta" msg))))))

(deftest spawner-receives-test
  (testing "the shout reaches exactly the agent named by :parent-id"
    (let [msg {:agent-id "child" :parent-id "ling-a"}]
      (is (aud/addressed-to? "ling-a" msg))
      (is (not (aud/addressed-to? "ling-b" msg)))))
  (testing "a coordinator-spawned ling reaches the coordinator lane despite the
            project suffix on the reader id"
    (let [msg {:agent-id "vt-billing" :parent-id "coordinator"}]
      (is (aud/addressed-to? "coordinator-hive" msg))
      (is (aud/addressed-to? "coordinator" msg)))))

(deftest grandchildren-stop-at-their-spawner-test
  (testing "a grandchild's shout reaches its own spawner, not the coordinator"
    (let [msg {:agent-id "grandchild" :parent-id "ling-a"}]
      (is (aud/addressed-to? "ling-a" msg))
      (is (not (aud/addressed-to? "coordinator-hive" msg))))))

(deftest root-shouts-reach-coordinators-test
  (testing "no :parent-id means root-level — coordinator readers only"
    (let [msg {:agent-id "wave-scheduler"}]
      (is (aud/addressed-to? "coordinator-hive" msg))
      (is (not (aud/addressed-to? "some-ling" msg))))))

(deftest broadcast-reaches-everyone-test
  (let [msg {:agent-id "coordinator" :broadcast? true}]
    (is (aud/addressed-to? "coordinator-hive" msg))
    (is (aud/addressed-to? "any-ling" msg))))

(deftest no-self-echo-for-lings-test
  (testing "a ling does not read back its own shout"
    (is (not (aud/addressed-to? "ling-a" {:agent-id "ling-a" :parent-id "coordinator"}))))
  (testing "but the coordinator still sees shouts it authored (wave scheduler)"
    (is (aud/addressed-to? "coordinator-hive" {:agent-id "coordinator"}))))

(deftest filter-messages-test
  (testing "filter-messages keeps order and drops what is not addressed"
    (let [msgs [{:agent-id "a" :parent-id "coordinator" :timestamp 1}
                {:agent-id "b" :parent-id "a" :timestamp 2}
                {:agent-id "c" :parent-id "coordinator" :timestamp 3}]]
      (is (= [1 3] (mapv :timestamp (aud/filter-messages "coordinator-hive" msgs))))
      (is (= [2] (mapv :timestamp (aud/filter-messages "a" msgs)))))))

;; =============================================================================
;; digest — the anti-micromanagement contract
;; =============================================================================

(defn- progress [a m] {:a a :e "progress" :m m})

(deftest digest-collapses-a-progress-burst-test
  (testing "21 per-turn rows from one agent collapse to a single rollup"
    (let [rows (mapv #(progress "vt-billing" (str "turn " %)) (range 1 22))
          out (aud/digest rows)]
      (is (= 1 (count out)))
      (is (= 21 (:n (first out))) "carries the burst count")
      (is (= "turn 21" (:m (first out))) "carries the LAST message, not the first"))))

(deftest digest-passes-lifecycle-through-verbatim-test
  (let [rows [(progress "a" "turn 1")
              (progress "a" "turn 2")
              {:a "a" :e "error" :m "boom"}]
        out (aud/digest rows)]
    (is (= ["progress" "error"] (mapv :e out)))
    (is (= "boom" (:m (last out))) "the error is not collapsed")))

(deftest digest-keeps-agents-separate-test
  (let [rows [(progress "a" "a1") (progress "b" "b1") (progress "a" "a2")]
        out (aud/digest rows)]
    (is (= 2 (count out)))
    (is (= #{"a" "b"} (set (map :a out))))
    (is (= "a2" (:m (first (filter #(= "a" (:a %)) out)))))))

(deftest digest-preserves-position-of-last-row-test
  (testing "the rollup sits where the agent's LAST progress row was, so it
            reads as current state relative to other agents' events"
    (let [rows [(progress "a" "a1")
                {:a "b" :e "completed" :m "done"}
                (progress "a" "a2")]
          out (aud/digest rows)]
      (is (= ["completed" "progress"] (mapv :e out))))))

(deftest digest-leaves-a-lone-progress-row-untouched-test
  (let [rows [(progress "a" "only")]]
    (is (= rows (aud/digest rows)) "no :n key when nothing was collapsed")))

(deftest digest-of-empty-is-empty-test
  (is (= [] (aud/digest [])))
  (is (= [] (aud/digest nil))))
