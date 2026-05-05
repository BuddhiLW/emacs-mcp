(ns hive-mcp.crystal.harvest.by-scope-integration-test
  "Step-4 integration: `harvest-all-by-scope` wraps `harvest-all` →
   attribution → partition → HarvestByScope.

   Backends are mocked via `with-redefs` of `harvest-all` so the test
   exercises the full pipeline without hitting Chroma / DataScript / git.

   Plan: `20260504173159-46dc47f1`."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.crystal.harvest.by-scope :as bs]
            [hive-mcp.crystal.harvest.collect :as coll]
            [hive-mcp.crystal.harvest.partition :as p]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- mock-legacy-result
  "Shape mirrors what `harvest-all` returns today (single-scope).
   The `:directory` key drives source-pid resolution in step-4."
  [pid]
  {:progress-notes      [{:project-id pid :tags []}]
   :completed-tasks     [{:completed-task/project-id pid}]
   :git-commits         ["abc1 fix"]
   :recalls             {"id-a" {:project-id pid}}
   :hivemind-messages   [{:project-id pid :m "ping"}]
   :kanban-activity     {:tasks-completed [{:project-id pid}]}
   :kg-edges-created    {:edges [{:kg-edge/scope pid}]}
   :kanban-movements    {:movements [{:kanban-movement/project-id pid}]}
   :memory-ids-created  [{:id "m1" :project-id pid}]
   :memory-ids-accessed ["m2"]
   :session             "20260504-test"
   :directory           (str "/home/leibniz/PP/" pid)
   :agent-id            "coordinator"
   :session-timing      {:session-start "x" :session-end "y" :duration-minutes 5}
   :summary             {:progress-count 1 :task-count 1 :commit-count 1
                         :recall-count 1 :hivemind-shout-count 1 :kanban-completed 1
                         :kg-edge-count 1 :kanban-movement-count 1
                         :created-count 1 :accessed-count 1}})

(deftest harvest-all-by-scope--shape-and-validity
  (testing "single-scope harvest produces a valid HarvestByScope"
    (with-redefs [coll/harvest-all (fn [_] (mock-legacy-result "hive-mcp"))]
      (let [hbs (coll/harvest-all-by-scope {:directory "/home/leibniz/PP/hive-mcp"})]
        (is (bs/valid? hbs))
        (is (= "20260504-test" (:session hbs)))
        (is (= "/home/leibniz/PP/hive-mcp" (:directory hbs)))
        (is (contains? hbs :summary))))))

(deftest harvest-all-by-scope--per-datum-pids-survive
  (testing "datums with their own pid land in the right scope slice"
    (with-redefs [coll/harvest-all (fn [_]
                                     (-> (mock-legacy-result "hive")
                                         ;; Inject a cross-pid datum
                                         (update-in [:progress-notes] conj
                                                    {:tags ["scope:project:funeraria"]})
                                         (update-in [:kg-edges-created :edges] conj
                                                    {:kg-edge/scope "sisf-crm"})))]
      (let [hbs (coll/harvest-all-by-scope {:directory "/home/leibniz/PP/hive"})
            pids (bs/scope-pids hbs)]
        (is (contains? pids "funeraria") "tag-derived pid landed in own slice")
        (is (contains? pids "sisf-crm") "kg-edge scope landed in own slice")))))

(deftest harvest-all-by-scope--umbrella-scalars-merged
  (testing "session-timing flows into umbrella"
    (with-redefs [coll/harvest-all (fn [_] (mock-legacy-result "hive"))]
      (let [hbs (coll/harvest-all-by-scope {:directory "/home/leibniz/PP/hive"})]
        (is (= {:session-start "x" :session-end "y" :duration-minutes 5}
               (get-in hbs [:umbrella :session-timing])))))))

(deftest harvest-all-by-scope--no-datum-loss
  (testing "conservation: every flat datum surfaces somewhere"
    (with-redefs [coll/harvest-all (fn [_] (mock-legacy-result "hive"))]
      (let [hbs (coll/harvest-all-by-scope {:directory "/home/leibniz/PP/hive"})
            ;; 1+1+1+1+1+1+1+1+1+1 = 10 datums in the mock
            total (p/total-datum-count hbs)]
        (is (>= total 10) (str "expected >=10 datums; got " total))))))

(deftest harvest-all-by-scope--empty-harvest-still-valid
  (testing "an empty session produces a valid empty HarvestByScope"
    (with-redefs [coll/harvest-all (fn [_]
                                     {:progress-notes []
                                      :completed-tasks []
                                      :git-commits []
                                      :recalls {}
                                      :hivemind-messages []
                                      :kanban-activity {:tasks-completed []}
                                      :kg-edges-created {:edges []}
                                      :kanban-movements {:movements []}
                                      :memory-ids-created []
                                      :memory-ids-accessed []
                                      :session "empty"
                                      :directory "/tmp"
                                      :agent-id nil})]
      (let [hbs (coll/harvest-all-by-scope {:directory "/tmp"})]
        (is (bs/valid? hbs))
        (is (= 0 (p/total-datum-count hbs)))
        (is (= "empty" (:session hbs)))))))