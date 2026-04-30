(ns hive-mcp.plan.gate-test
  "Tests for plan gate (FSM validation on memory write).

   Tests are designed to run via nREPL (not bash) per project axiom:
   'Clojure Tests Run via nREPL, Never Bash'

   Run: (require '[clojure.test :refer [run-tests]])
        (run-tests 'hive-mcp.plan.gate-test)"
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.plan.gate :as sut]
            [hive-mcp.plan.schema :as schema]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; plan-content? Detection Tests
;; =============================================================================

(deftest plan-content?-test
  (testing "detects EDN plan content"
    (is (sut/plan-content? "{:steps [{:id \"s1\" :title \"Do thing\"}]}"))
    (is (sut/plan-content? "{:plan/steps [{:step/id \"s1\"}]}"))
    (is (sut/plan-content? "```edn\n{:steps [{:id \"s1\" :title \"X\"}]}\n```")))

  (testing "detects markdown plan content (1+ header — parity with parser)"
    (is (sut/plan-content? "## Step 1\nDo A\n## Step 2\nDo B"))
    (is (sut/plan-content? "## Only One Section\nSome notes")))

  (testing "rejects non-plan content"
    (is (not (sut/plan-content? "Just a note about the plan")))
    (is (not (sut/plan-content? "FRICTION: tool X returned unexpected result")))
    (is (not (sut/plan-content? "")))
    (is (not (sut/plan-content? nil)))))

;; =============================================================================
;; validate-for-storage Tests - Valid Plans
;; =============================================================================

(deftest validate-valid-edn-plan-test
  (testing "valid minimal EDN plan passes gate"
    (let [content "{:id \"plan-test\" :title \"Test Plan\" :steps [{:id \"step-1\" :title \"Do thing\"}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result))
      (is (= 1 (get-in result [:metadata :steps-count])))
      (is (= :edn (get-in result [:metadata :source-format])))
      (is (false? (get-in result [:metadata :has-dependencies?])))))

  (testing "valid EDN plan with dependencies passes gate"
    (let [content "{:id \"plan-deps\" :title \"Dep Plan\"
                    :steps [{:id \"step-1\" :title \"First\" :depends-on []}
                            {:id \"step-2\" :title \"Second\" :depends-on [\"step-1\"]}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result))
      (is (= 2 (get-in result [:metadata :steps-count])))
      (is (true? (get-in result [:metadata :has-dependencies?])))))

  (testing "valid plan in code block passes gate"
    (let [content "# My Plan\n\n```edn\n{:id \"plan-cb\" :title \"Block Plan\" :steps [{:id \"s1\" :title \"Task 1\"}]}\n```"
          result (sut/validate-for-storage content)]
      (is (:valid? result))
      (is (= 1 (get-in result [:metadata :steps-count])))))

  (testing "valid plan with namespaced keys passes gate"
    (let [content "{:plan/id \"plan-ns\" :plan/title \"NS Plan\"
                    :plan/steps [{:step/id \"s1\" :step/title \"Thing\" :step/depends-on []}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result))
      (is (= 1 (get-in result [:metadata :steps-count]))))))

;; =============================================================================
;; validate-for-storage Tests - Invalid Plans
;; =============================================================================

(deftest validate-invalid-plan-test
  (testing "plan with no steps fails gate"
    (let [content "{:id \"plan-empty\" :title \"Empty\" :steps []}"
          result (sut/validate-for-storage content)]
      (is (not (:valid? result)))
      (is (seq (:errors result)))
      (is (string? (:hint result)))))

  (testing "plan with invalid dependency refs fails gate"
    (let [content "{:id \"plan-bad-dep\" :title \"Bad Deps\"
                    :steps [{:id \"step-1\" :title \"First\" :depends-on [\"step-99\"]}]}"
          result (sut/validate-for-storage content)]
      (is (not (:valid? result)))
      (is (= :dependencies (:phase result)))
      (is (some #(re-find #"step-99" %) (:errors result)))))

  (testing "unparseable content fails gate"
    (let [content "This is not a plan at all, just random text with {broken edn"
          result (sut/validate-for-storage content)]
      (is (not (:valid? result)))
      (is (= :parse (:phase result)))
      (is (string? (:hint result))))))

;; =============================================================================
;; validate-for-storage Tests - Markdown Plans
;; =============================================================================

(deftest validate-markdown-plan-test
  (testing "valid markdown plan passes gate"
    (let [content "# My Plan\n\n## Step 1: Setup\nConfigure things\n\n## Step 2: Build\nBuild the thing"
          result (sut/validate-for-storage content)]
      (is (:valid? result))
      (is (= 2 (get-in result [:metadata :steps-count])))
      (is (= :markdown (get-in result [:metadata :source-format]))))))

;; =============================================================================
;; format-gate-error Tests
;; =============================================================================

(deftest format-gate-error-test
  (testing "formats error with hint and contract"
    (let [gate-result {:valid? false
                       :errors ["Schema: missing :title"]
                       :hint "Fix the schema"
                       :phase :schema}
          formatted (sut/format-gate-error gate-result)]
      (is (string? formatted))
      (is (re-find #"Schema: missing :title" formatted))
      (is (re-find #"(?i)plan-to-kanban contract" formatted))
      (is (re-find #"phase: schema" formatted)))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest edge-case-test
  (testing "nil content returns parse error"
    (let [result (sut/validate-for-storage nil)]
      (is (not (:valid? result)))))

  (testing "empty string returns parse error"
    (let [result (sut/validate-for-storage "")]
      (is (not (:valid? result)))))

  (testing "plan with decision-id passes through metadata"
    (let [content "{:id \"plan-dec\" :title \"Dec Plan\" :decision-id \"dec-123\"
                    :steps [{:id \"s1\" :title \"Step\"}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result))
      (is (= "dec-123" (get-in result [:metadata :decision-id]))))))

;; =============================================================================
;; Multi-Step Plans (Regression: 3+ steps)
;; =============================================================================

(deftest validate-multi-step-plans-test
  (testing "3-step EDN plan passes gate"
    (let [content "{:id \"plan-3\" :title \"Three Steps\"
                    :steps [{:id \"step-1\" :title \"First\" :depends-on [] :priority :high}
                            {:id \"step-2\" :title \"Second\" :depends-on [\"step-1\"] :priority :high}
                            {:id \"step-3\" :title \"Third\" :depends-on [\"step-1\"] :priority :medium}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result) (str "3-step plan should pass gate, got: " (:errors result)))
      (is (= 3 (get-in result [:metadata :steps-count])))))

  (testing "3-step plan with :priority :normal passes (alias → :medium)"
    (let [content "{:id \"plan-normal\" :title \"Normal Priority\"
                    :steps [{:id \"step-1\" :title \"First\" :depends-on [] :priority :high}
                            {:id \"step-2\" :title \"Second\" :depends-on [\"step-1\"] :priority :high}
                            {:id \"step-3\" :title \"Third\" :depends-on [\"step-1\"] :priority :normal}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result) (str ":normal should be aliased to :medium, got: " (:errors result)))
      (is (= 3 (get-in result [:metadata :steps-count])))))

  (testing "5-step EDN plan passes gate"
    (let [content "{:id \"plan-5\" :title \"Five Steps\"
                    :steps [{:id \"s1\" :title \"Step 1\" :depends-on [] :priority :high}
                            {:id \"s2\" :title \"Step 2\" :depends-on [\"s1\"] :priority :medium}
                            {:id \"s3\" :title \"Step 3\" :depends-on [\"s1\"] :priority :medium}
                            {:id \"s4\" :title \"Step 4\" :depends-on [\"s2\" \"s3\"] :priority :low}
                            {:id \"s5\" :title \"Step 5\" :depends-on [\"s4\"] :priority :high}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result) (str "5-step plan should pass gate, got: " (:errors result)))
      (is (= 5 (get-in result [:metadata :steps-count])))))

  (testing "20-step plan passes gate (acceptance criterion: 20+ steps)"
    (let [steps (mapv (fn [i]
                        (str "{:id \"s" i "\" :title \"Step " i "\""
                             " :depends-on " (if (= i 1) "[]" (str "[\"s" (dec i) "\"]"))
                             " :priority " (case (mod i 3) 0 ":high" 1 ":medium" 2 ":low")
                             "}"))
                      (range 1 21))
          content (str "{:id \"plan-20\" :title \"Twenty Steps\" :steps ["
                       (clojure.string/join " " steps) "]}")
          result (sut/validate-for-storage content)]
      (is (:valid? result) (str "20-step plan should pass gate, got: " (:errors result)))
      (is (= 20 (get-in result [:metadata :steps-count]))))))

;; =============================================================================
;; Priority/Estimate Normalization via Gate
;; =============================================================================

(deftest validate-priority-aliases-test
  (testing "case-insensitive priority keywords pass gate"
    (let [content "{:id \"plan-case\" :title \"Case Test\"
                    :steps [{:id \"s1\" :title \"Step\" :priority :HIGH}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result) (str ":HIGH should normalize to :high, got: " (:errors result)))))

  (testing "unknown priority defaults to :medium and passes gate"
    (let [content "{:id \"plan-unk\" :title \"Unknown Priority\"
                    :steps [{:id \"s1\" :title \"Step\" :priority :urgent}]}"
          result (sut/validate-for-storage content)]
      (is (:valid? result) (str ":urgent should normalize to :high, got: " (:errors result))))))

;; =============================================================================
;; Normalization Unit Tests (schema layer)
;; =============================================================================

(deftest normalize-priority-test
  (testing "canonical values pass through"
    (is (= :high (schema/normalize-priority :high)))
    (is (= :medium (schema/normalize-priority :medium)))
    (is (= :low (schema/normalize-priority :low))))

  (testing "nil defaults to :medium"
    (is (= :medium (schema/normalize-priority nil))))

  (testing "case-insensitive keywords"
    (is (= :high (schema/normalize-priority :HIGH)))
    (is (= :medium (schema/normalize-priority :Medium)))
    (is (= :low (schema/normalize-priority :LOW))))

  (testing ":normal aliases to :medium"
    (is (= :medium (schema/normalize-priority :normal)))
    (is (= :medium (schema/normalize-priority :Normal))))

  (testing "other aliases"
    (is (= :high (schema/normalize-priority :critical)))
    (is (= :high (schema/normalize-priority :urgent)))
    (is (= :low (schema/normalize-priority :trivial))))

  (testing "unknown keywords clamp to :medium"
    (is (= :medium (schema/normalize-priority :banana)))
    (is (= :medium (schema/normalize-priority :p99))))

  (testing "string values"
    (is (= :high (schema/normalize-priority "high")))
    (is (= :medium (schema/normalize-priority "normal")))
    (is (= :medium (schema/normalize-priority "MEDIUM")))))

(deftest normalize-estimate-test
  (testing "canonical values pass through"
    (is (= :small (schema/normalize-estimate :small)))
    (is (= :medium (schema/normalize-estimate :medium)))
    (is (= :large (schema/normalize-estimate :large))))

  (testing "nil defaults to :medium"
    (is (= :medium (schema/normalize-estimate nil))))

  (testing "case-insensitive keywords"
    (is (= :small (schema/normalize-estimate :SMALL)))
    (is (= :large (schema/normalize-estimate :Large))))

  (testing "unknown keywords clamp to :medium"
    (is (= :medium (schema/normalize-estimate :huge)))
    (is (= :medium (schema/normalize-estimate :tiny)))))
