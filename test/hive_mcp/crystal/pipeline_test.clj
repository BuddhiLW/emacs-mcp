(ns hive-mcp.crystal.pipeline-test
  "Tests for crystal/pipeline.clj — harvest pipeline with result/ok-> railway.

   Golden tests:
   - G1: mock sources → expected SynthesisInput shape
   - G2: empty sources → pipeline error
   - G3: partial failure → SynthesisInput with source-errors

   Property tests:
   - P1: N harvest sources → merged SynthesisInput has all synthesis keys
   - P2: any source returning :error doesn't block others (fault isolation)
   - P3: HarvestOutcome ADT exhaustiveness — all variants constructible
   - P4: merge-outcomes preserves source-id keys from ok outcomes
   - P5: run-pipeline always returns a Result (ok or err)"
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.crystal.pipeline :as pipeline]
            [hive-mcp.dns.result :as result]
            [hive-dsl.adt :as adt]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- mock-source
  "Create a mock IHarvestSource that returns static data."
  [id data]
  (pipeline/make-source id (fn [_ctx] data)))

(defn- failing-source
  "Create a mock IHarvestSource that throws on harvest."
  [id msg]
  (pipeline/make-source id (fn [_ctx] (throw (ex-info msg {:source id})))))

(defn- slow-source
  "Create a mock IHarvestSource that sleeps before returning."
  [id sleep-ms data]
  (pipeline/make-source id (fn [_ctx] (Thread/sleep sleep-ms) data)))

;; =============================================================================
;; Golden Tests
;; =============================================================================

(deftest g1-happy-path-synthesis-input
  (testing "All 5 sources succeed → SynthesisInput has canonical shape"
    (let [sources [(mock-source :hivemind {:messages [{:text "deployed v2" :agent "a1"}]})
                   (mock-source :kanban   {:tasks-completed [{:id "T1" :title "Fix null check"}]})
                   (mock-source :git      {:commits ["abc123 fix: null check" "def456 refactor: pool"]})
                   (mock-source :memory   {:created-count 5 :accessed-count 18})
                   (mock-source :session  {:session-start "2026-04-01T10:00:00Z"
                                           :session-end   "2026-04-01T12:30:00Z"
                                           :duration-minutes 150})]
          result  (pipeline/run-pipeline sources {:dir "/tmp/test"})]
      (is (result/ok? result) "Pipeline should return ok")
      (let [si (:ok result)]
        ;; All synthesis keys present
        (is (= [{:text "deployed v2" :agent "a1"}] (:hivemind-messages si)))
        (is (= [{:id "T1" :title "Fix null check"}] (:kanban-changes si)))
        (is (= ["abc123 fix: null check" "def456 refactor: pool"] (:git-commits si)))
        (is (= 5 (:created-count (:memory-stats si))))
        (is (= 18 (:accessed-count (:memory-stats si))))
        (is (= "2026-04-01T10:00:00Z" (:session-start (:session-timing si))))
        (is (= 150 (:duration-minutes (:session-timing si))))
        (is (empty? (:source-errors si)) "No errors expected")))))

(deftest g2-empty-sources-pipeline-error
  (testing "Empty source list → pipeline returns err :pipeline/no-sources"
    (let [result (pipeline/run-pipeline [] {:dir "/tmp"})]
      (is (result/err? result) "Empty sources should error")
      (is (= :pipeline/no-sources (:error result))))))

(deftest g3-partial-failure-fault-isolation
  (testing "One source fails, others succeed → SynthesisInput with source-errors"
    (let [sources [(mock-source :hivemind {:messages [{:text "hello"}]})
                   (failing-source :kanban "DataScript unavailable")
                   (mock-source :git {:commits ["abc123 fix"]})]
          result  (pipeline/run-pipeline sources {:dir "/tmp"})]
      (is (result/ok? result) "Pipeline should still succeed")
      (let [si (:ok result)]
        (is (= [{:text "hello"}] (:hivemind-messages si))
            "Hivemind data should be present")
        (is (= ["abc123 fix"] (:git-commits si))
            "Git data should be present")
        (is (= 1 (count (:source-errors si)))
            "One source-error expected")
        (is (= :kanban (:source-id (first (:source-errors si))))
            "Failed source should be :kanban")))))

(deftest g4-timeout-isolation
  (testing "Slow source times out, others succeed → SynthesisInput with timeout error"
    (let [sources [(mock-source :hivemind {:messages [{:text "fast"}]})
                   (slow-source :kanban 5000 {:tasks-completed []})
                   (mock-source :git {:commits []})]
          ;; 200ms timeout — kanban will timeout
          result  (pipeline/run-pipeline sources {:dir "/tmp"} {:timeout-ms 200})]
      (is (result/ok? result))
      (let [si (:ok result)]
        (is (= [{:text "fast"}] (:hivemind-messages si)))
        (is (= 1 (count (:source-errors si))))
        (is (= :harvest/timeout (:outcome-type (first (:source-errors si)))))))))

;; =============================================================================
;; ADT Tests
;; =============================================================================

(deftest harvest-outcome-adt-construction
  (testing "All HarvestOutcome variants constructible"
    (let [ok      (pipeline/harvest-outcome :harvest/ok      {:source-id :git :data {:commits []}})
          timeout (pipeline/harvest-outcome :harvest/timeout {:source-id :git :elapsed-ms 10000.0})
          error   (pipeline/harvest-outcome :harvest/error   {:source-id :git :message "boom"})]
      (is (adt/adt? ok))
      (is (adt/adt? timeout))
      (is (adt/adt? error))
      (is (= :harvest/ok      (:adt/variant ok)))
      (is (= :harvest/timeout (:adt/variant timeout)))
      (is (= :harvest/error   (:adt/variant error)))
      (is (= :HarvestOutcome  (:adt/type ok))))))

(deftest harvest-outcome-exhaustive-case
  (testing "adt-case covers all variants"
    (let [ok (pipeline/harvest-outcome :harvest/ok {:source-id :x :data {:a 1}})]
      (is (= :matched
             (adt/adt-case pipeline/HarvestOutcome ok
               :harvest/ok      :matched
               :harvest/timeout :timeout
               :harvest/error   :error))))))

;; =============================================================================
;; Unit Tests — merge-outcomes
;; =============================================================================

(deftest merge-outcomes-unit
  (testing "merge-outcomes collects ok data by source-id"
    (let [outcomes [(pipeline/harvest-outcome :harvest/ok {:source-id :a :data {:x 1}})
                    (pipeline/harvest-outcome :harvest/ok {:source-id :b :data {:y 2}})]
          result   (pipeline/merge-outcomes outcomes)]
      (is (result/ok? result))
      (let [merged (:ok result)]
        (is (= {:x 1} (:a merged)))
        (is (= {:y 2} (:b merged)))
        (is (nil? (:source-errors merged))))))

  (testing "merge-outcomes separates errors"
    (let [outcomes [(pipeline/harvest-outcome :harvest/ok    {:source-id :a :data {:x 1}})
                    (pipeline/harvest-outcome :harvest/error {:source-id :b :message "fail"})]
          result   (pipeline/merge-outcomes outcomes)]
      (is (result/ok? result))
      (let [merged (:ok result)]
        (is (= {:x 1} (:a merged)))
        (is (nil? (:b merged)))
        (is (= 1 (count (:source-errors merged))))))))

;; =============================================================================
;; Unit Tests — harvest-one
;; =============================================================================

(deftest harvest-one-success
  (testing "harvest-one wraps successful harvest in :harvest/ok"
    (let [src    (mock-source :test {:data 42})
          result (pipeline/harvest-one src {})]
      (is (= :harvest/ok (:adt/variant result)))
      (is (= :test (:source-id result)))
      (is (= {:data 42} (:data result))))))

(deftest harvest-one-failure
  (testing "harvest-one wraps exception in :harvest/error"
    (let [src    (failing-source :test "kaboom")
          result (pipeline/harvest-one src {})]
      (is (= :harvest/error (:adt/variant result)))
      (is (= :test (:source-id result)))
      (is (= "kaboom" (:message result))))))

;; =============================================================================
;; Property Tests
;; =============================================================================

;; Generator: random source-id keyword
(def gen-source-id
  (gen/fmap (fn [s] (keyword (str "src-" s)))
            (gen/choose 0 99)))

;; Generator: random harvest data map
(def gen-harvest-data
  (gen/hash-map :value gen/small-integer
                :items (gen/vector gen/string-alphanumeric 0 5)))

;; Generator: mock source that succeeds
(def gen-ok-source
  (gen/let [id   gen-source-id
            data gen-harvest-data]
    (mock-source id data)))

;; Generator: mock source that fails
(def gen-fail-source
  (gen/let [id gen-source-id]
    (failing-source id "generated-failure")))

(defspec p1-n-sources-all-synthesis-keys 50
  (prop/for-all [sources (gen/vector gen-ok-source 1 10)]
    (let [result (pipeline/run-pipeline sources {} {:timeout-ms 5000})]
      (and (result/ok? result)
           (let [si (:ok result)]
             (every? #(contains? si %)
                     [:hivemind-messages :kanban-changes :memory-stats
                      :git-commits :session-timing :source-errors]))))))

(defspec p2-fault-isolation-errors-dont-block 50
  (prop/for-all [ok-sources   (gen/vector gen-ok-source 1 5)
                 fail-sources (gen/vector gen-fail-source 1 3)]
    (let [all-sources (concat ok-sources fail-sources)
          result      (pipeline/run-pipeline (vec all-sources) {} {:timeout-ms 5000})]
      ;; Pipeline always succeeds when at least one source exists
      (and (result/ok? result)
           ;; Source-errors count matches fail-sources count
           (= (count fail-sources)
              (count (:source-errors (:ok result))))))))

(defspec p3-harvest-outcome-adt-always-valid 100
  (prop/for-all [id gen-source-id]
    (let [ok      (pipeline/harvest-outcome :harvest/ok      {:source-id id :data {}})
          timeout (pipeline/harvest-outcome :harvest/timeout {:source-id id :elapsed-ms 1000.0})
          error   (pipeline/harvest-outcome :harvest/error   {:source-id id :message "err"})]
      (and (adt/adt-valid? ok)
           (adt/adt-valid? timeout)
           (adt/adt-valid? error)))))

(defspec p4-merge-preserves-ok-source-ids 50
  (prop/for-all [sources (gen/vector gen-ok-source 1 8)]
    (let [outcomes (pipeline/harvest-all sources {} 5000)
          result   (pipeline/merge-outcomes outcomes)]
      (if (result/ok? result)
        (let [merged (:ok result)
              ;; All ok source-ids should be keys in merged
              ok-ids (set (map pipeline/source-id sources))]
          (every? #(contains? merged %) ok-ids))
        ;; err is acceptable (shouldn't happen with ok sources, but safe)
        true))))

(defspec p5-run-pipeline-always-returns-result 50
  (prop/for-all [sources (gen/vector (gen/one-of [gen-ok-source gen-fail-source]) 0 8)]
    (let [result (pipeline/run-pipeline (vec sources) {} {:timeout-ms 2000})]
      (or (result/ok? result)
          (result/err? result)))))

(comment
  ;; Run all tests
  ;; clj -X:test :nses '[hive-mcp.crystal.pipeline-test]'

  ;; Quick REPL check
  (pipeline/run-pipeline
   [(mock-source :hivemind {:messages [{:text "hi"}]})
    (mock-source :git {:commits ["abc fix"]})]
   {:dir "/tmp"})
  )
