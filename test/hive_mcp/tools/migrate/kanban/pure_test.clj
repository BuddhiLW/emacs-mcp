(ns hive-mcp.tools.migrate.kanban.pure-test
  "Trifecta tests for the kanban migrator's pure layer. Properties drive
   confidence in totality + invariants; golden cases pin behaviour for the
   handful of inputs the rest of the codebase reasons about."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-mcp.tools.migrate.kanban.pure :as pure]
            [hive-test.trifecta :refer [deftrifecta]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-kanban-content
  (gen/hash-map
   :task-type (gen/return "kanban")
   :title gen/string-alphanumeric
   :status (gen/elements ["todo" "doing" "done" "review"])))

(def gen-non-kanban-content
  (gen/hash-map
   :task-type (gen/elements ["other" "swarm" "tracking" nil])
   :title gen/string-alphanumeric))

(def gen-stub-entry
  (gen/hash-map :id gen/string-alphanumeric))

(def gen-kanban-entry
  (gen/hash-map :id gen/string-alphanumeric :content gen-kanban-content))

(def gen-non-kanban-entry
  (gen/hash-map :id gen/string-alphanumeric :content gen-non-kanban-content))

(def gen-any-entry-or-nil
  (gen/one-of [(gen/return nil) gen-stub-entry gen-kanban-entry gen-non-kanban-entry]))

;; =============================================================================
;; task-type-kanban?
;; =============================================================================

(deftrifecta task-type-kanban-trifecta
  hive-mcp.tools.migrate.kanban.pure/task-type-kanban?
  {:gen gen-any-entry-or-nil
   :pred boolean?
   :num-tests 100
   :mutations [["always-true"  (fn [_] true)]
               ["always-false" (fn [_] false)]]
   :assert (fn []
             (testing "kanban content is recognised"
               (is (true? (pure/task-type-kanban? {:content {:task-type "kanban"}})))
               (is (true? (pure/task-type-kanban? {:content {"task-type" "kanban"}}))))
             (testing "non-kanban content is rejected"
               (is (false? (pure/task-type-kanban? nil)))
               (is (false? (pure/task-type-kanban? {})))
               (is (false? (pure/task-type-kanban? {:content {}})))
               (is (false? (pure/task-type-kanban? {:content {:task-type "swarm"}})))))})

;; =============================================================================
;; full-payload?
;; =============================================================================

(deftrifecta full-payload-trifecta
  hive-mcp.tools.migrate.kanban.pure/full-payload?
  {:gen gen-any-entry-or-nil
   :pred boolean?
   :num-tests 100
   :mutations [["always-true"  (fn [_] true)]
               ["nil-only"     (fn [e] (some? e))]]
   :assert (fn []
             (is (false? (pure/full-payload? nil)))
             (is (false? (pure/full-payload? {})))
             (is (false? (pure/full-payload? {:id "x"})))
             (is (false? (pure/full-payload? {:id "x" :content {}})))
             (is (true?  (pure/full-payload? {:id "x" :content {:task-type "kanban"}}))))})

;; =============================================================================
;; classify-outcome
;; =============================================================================

(deftest classify-outcome-totality
  (testing "result always lies in the outcome enum"
    (doseq [s [nil
               {:id "x"}
               {:id "x" :content {:task-type "swarm"}}
               {:id "x" :content {:task-type "kanban" :title "t"}}]
            t [nil
               {:id "x"}
               {:id "x" :content {}}
               {:id "x" :content {:task-type "kanban" :title "t"}}]]
      (is (contains? pure/outcome-types
                     (pure/classify-outcome s t))))))

(deftest classify-outcome-rules
  (testing "missing source dominates"
    (is (= :missing-from-source
           (pure/classify-outcome nil {:id "x" :content {:task-type "kanban"}}))))
  (testing "non-kanban source short-circuits"
    (is (= :not-task
           (pure/classify-outcome {:id "x" :content {:task-type "swarm"}} nil))))
  (testing "kanban source + full target = already-full"
    (is (= :already-full
           (pure/classify-outcome
             {:id "x" :content {:task-type "kanban"}}
             {:id "x" :content {:task-type "kanban" :title "t"}}))))
  (testing "kanban source + missing target = ready-to-write"
    (is (= :ready-to-write
           (pure/classify-outcome
             {:id "x" :content {:task-type "kanban"}}
             nil))))
  (testing "kanban source + stub target (no content) = ready-to-write"
    (is (= :ready-to-write
           (pure/classify-outcome
             {:id "x" :content {:task-type "kanban"}}
             {:id "x"}))))
  (testing "kanban source + target with empty content = ready-to-write"
    (is (= :ready-to-write
           (pure/classify-outcome
             {:id "x" :content {:task-type "kanban"}}
             {:id "x" :content {}})))))

;; =============================================================================
;; slice-batch
;; =============================================================================

(def gen-slice-args
  (gen/let [n  (gen/choose 0 50)
            ids (gen/vector gen/string-alphanumeric n)
            cursor (gen/choose 0 60)
            size   (gen/choose 1 20)]
    [ids cursor size]))

(deftrifecta slice-batch-trifecta
  hive-mcp.tools.migrate.kanban.pure/slice-batch
  {:gen gen-slice-args
   :apply? true
   :pred (fn [[batch new-cursor done?]]
           (and (vector? batch)
                (integer? new-cursor)
                (boolean? done?)
                (<= (count batch) 20)))
   :num-tests 200})

(deftest slice-batch-cases
  (testing "empty list short-circuits"
    (is (= [[] 0 true] (pure/slice-batch [] 0 5))))
  (testing "cursor past end yields empty + done"
    (is (= [[] 10 true] (pure/slice-batch (vec (range 10)) 10 5))))
  (testing "first batch advances by size"
    (let [[batch nc done?] (pure/slice-batch (vec (range 10)) 0 3)]
      (is (= [0 1 2] batch))
      (is (= 3 nc))
      (is (false? done?))))
  (testing "final partial batch flips done?"
    (let [[batch nc done?] (pure/slice-batch (vec (range 7)) 5 5)]
      (is (= [5 6] batch))
      (is (= 7 nc))
      (is (true? done?))))
  (testing "negative cursor clamped to 0"
    (let [[batch nc _] (pure/slice-batch (vec (range 5)) -3 2)]
      (is (= [0 1] batch))
      (is (= 2 nc)))))

;; =============================================================================
;; tally-outcomes
;; =============================================================================

(deftest tally-outcomes-shape
  (testing "always returns full enum shape"
    (let [result (pure/tally-outcomes [])]
      (is (= pure/outcome-types (set (keys result)))))))

(deftest tally-outcomes-counts
  (let [outcomes [{:outcome :written}
                  {:outcome :written}
                  {:outcome :already-full}
                  {:outcome :not-task}]
        ;; :written is not an outcome-type — confirms tally tolerates extra keys.
        result (pure/tally-outcomes outcomes)]
    (is (= 1 (:already-full result)))
    (is (= 1 (:not-task result)))))

;; =============================================================================
;; merge-tally
;; =============================================================================

(deftest merge-tally-commutative-on-shared-keys
  (let [a {:already-full 1 :not-task 2}
        b {:already-full 3 :ready-to-write 4}]
    (is (= (pure/merge-tally a b) (pure/merge-tally b a)))))

(deftest merge-tally-sum
  (is (= {:already-full 4 :not-task 2 :ready-to-write 4}
         (pure/merge-tally
           {:already-full 1 :not-task 2}
           {:already-full 3 :ready-to-write 4}))))

;; =============================================================================
;; dedup-sorted-ids
;; =============================================================================

(deftest dedup-sorted-ids-cases
  (is (= [] (pure/dedup-sorted-ids [])))
  (is (= ["a" "b" "c"] (pure/dedup-sorted-ids [["b" "a"] ["c" "a"]])))
  (is (= ["1" "2" "3"]
         (pure/dedup-sorted-ids [["3" "1"] ["2"] ["1" "3"]]))))

(deftest dedup-sorted-ids-idempotent
  (let [seqs [["c" "a" "b"] ["a" "d"] ["b"]]
        once  (pure/dedup-sorted-ids seqs)
        twice (pure/dedup-sorted-ids [once])]
    (is (= once twice))))
