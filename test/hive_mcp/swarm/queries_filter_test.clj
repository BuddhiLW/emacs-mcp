(ns hive-mcp.swarm.queries-filter-test
  "Tests for stale/zombie filter in agent_status queries.

   Registry-ghost fix (2026-04-27): default agent_status hides
   :alive? false rows AND rows with :last-active-at older than threshold.
   Legacy rows (no :alive? attr) treated as alive."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core :as d]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.lings :as lings]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each (iso/with-isolations :swarm-ds))

(def ^:private threshold-ms (* 30 60 1000))

(defn- bump!
  "Set :slave/alive? + :slave/last-active-at on an existing slave row."
  [slave-id alive? last-active-at]
  (let [c (conn/ensure-conn)
        eid (:db/id (d/entity @c [:slave/id slave-id]))]
    (d/transact! c [(cond-> {:db/id eid}
                      (some? alive?)         (assoc :slave/alive? alive?)
                      (some? last-active-at) (assoc :slave/last-active-at last-active-at))])))

(defn- seed-four-slaves!
  "1: alive+recent  2: alive+stale  3: zombie (alive? false)  4: legacy (no attrs)."
  [now]
  (lings/add-slave! "alive-recent" {:status :idle})
  (lings/add-slave! "alive-stale"  {:status :working})
  (lings/add-slave! "zombie"       {:status :working})
  (lings/add-slave! "legacy"       {:status :idle})
  (bump! "alive-recent" true (- now 60000))                ; 1 minute ago
  (bump! "alive-stale"  true (- now (* 2 threshold-ms)))   ; 1 hour ago
  (bump! "zombie"       false (- now (* 2 threshold-ms))))

(deftest filter-hides-stale-and-zombies-default
  (testing "default get-all-slaves filters zombies + activity-stale rows"
    (seed-four-slaves! (System/currentTimeMillis))
    (let [visible (set (map :slave/id (queries/get-all-slaves)))]
      (is (contains? visible "alive-recent") "fresh slave shown")
      (is (contains? visible "legacy")        "legacy (no :alive? attr) shown")
      (is (not (contains? visible "alive-stale")) "stale-active slave hidden")
      (is (not (contains? visible "zombie"))      ":alive? false slave hidden")
      (is (= 2 (count visible))))))

(deftest include-stale-bypasses-filter
  (testing ":include-stale? true returns ALL rows"
    (seed-four-slaves! (System/currentTimeMillis))
    (let [all (set (map :slave/id (queries/get-all-slaves :include-stale? true)))]
      (is (= 4 (count all)))
      (is (every? all #{"alive-recent" "alive-stale" "zombie" "legacy"})))))

(deftest filtered-count-le-unfiltered-property
  (testing "filtered count ≤ unfiltered count (random fixture)"
    (let [now (System/currentTimeMillis)
          n   30]
      (dotimes [i n]
        (let [sid (str "slave-" i)
              alive? (rand-nth [true false nil])         ; nil → legacy
              age    (rand-int (* 4 threshold-ms))]
          (lings/add-slave! sid {:status :idle})
          (bump! sid alive? (- now age))))
      (let [unfiltered (count (queries/get-all-slaves :include-stale? true))
            filtered   (count (queries/get-all-slaves))]
        (is (= n unfiltered))
        (is (<= filtered unfiltered))))))

(deftest by-project-honors-include-stale
  (testing "get-slaves-by-project applies same filter contract"
    (let [now (System/currentTimeMillis)]
      (lings/add-slave! "p-recent" {:status :idle :project-id "proj-x"})
      (lings/add-slave! "p-zombie" {:status :working :project-id "proj-x"})
      (bump! "p-recent" true (- now 60000))
      (bump! "p-zombie" false (- now (* 2 threshold-ms)))
      (is (= #{"p-recent"}
             (set (map :slave/id (queries/get-slaves-by-project "proj-x")))))
      (is (= #{"p-recent" "p-zombie"}
             (set (map :slave/id (queries/get-slaves-by-project "proj-x" :include-stale? true))))))))
