(ns emacs-mcp.resilience-test
  "Tests for resilience patterns in REPL evaluation.
   
   These tests demonstrate the graceful degradation patterns and verify
   the CLARITY principle 'Yield safe failure' is properly implemented."
  (:require [clojure.test :refer [deftest testing is]]
            [emacs-mcp.resilience :as resilience]
            [emacs-mcp.evaluator :as evaluator]))

;; ============================================================================
;; Mock Evaluator for Testing
;; ============================================================================

(defn make-failing-evaluator
  "Creates a mock evaluator that always fails with a specific error."
  [error-message]
  (reify evaluator/ReplEvaluator
    (eval-code [_ _ _]
      {:success false :error error-message})
    (connected? [_] false)
    (get-status [_] {:connected false :error error-message})))

(defn make-succeeding-evaluator
  "Creates a mock evaluator that always succeeds with a specific result."
  [result]
  (reify evaluator/ReplEvaluator
    (eval-code [_ _ _]
      {:success true :result result})
    (connected? [_] true)
    (get-status [_] {:connected true})))

(defn make-mode-specific-evaluator
  "Creates an evaluator that fails in explicit mode but succeeds in silent mode."
  [explicit-error silent-result]
  (reify evaluator/ReplEvaluator
    (eval-code [_ code opts]
      (if (:silent? opts)
        {:success true :result silent-result}
        {:success false :error explicit-error}))
    (connected? [_] true)
    (get-status [_] {:connected true})))

(defn make-flaky-evaluator
  "Creates an evaluator that fails N times before succeeding.
   Useful for testing retry logic."
  [failures-before-success result]
  (let [attempts (atom 0)]
    (reify evaluator/ReplEvaluator
      (eval-code [_ _ _]
        (let [attempt (swap! attempts inc)]
          (if (> attempt failures-before-success)
            {:success true :result result}
            {:success false :error (str "Transient failure " attempt)})))
      (connected? [_] true)
      (get-status [_] {:connected true}))))

;; ============================================================================
;; Fallback Tests
;; ============================================================================

(deftest eval-with-fallback-test
  (testing "Fallback succeeds when primary mode fails"
    (let [evaluator (make-mode-specific-evaluator
                     "REPL buffer not available"
                     "42")
          result (resilience/eval-with-fallback
                  evaluator
                  "(+ 40 2)"
                  {:mode :explicit :fallback-mode :silent})]
      (is (= true (:success result)))
      (is (= "42" (:result result)))
      (is (= :silent (:mode-used result)))
      (is (= true (:fallback-used result)))
      (is (some? (:primary-error result)))))

  (testing "Primary mode succeeds, no fallback needed"
    (let [evaluator (make-succeeding-evaluator "123")
          result (resilience/eval-with-fallback
                  evaluator
                  "(* 41 3)"
                  {:mode :silent})]
      (is (= true (:success result)))
      (is (= "123" (:result result)))
      (is (= :silent (:mode-used result)))
      (is (nil? (:fallback-used result)))))

  (testing "Both modes fail"
    (let [evaluator (make-failing-evaluator "Connection lost")
          result (resilience/eval-with-fallback
                  evaluator
                  "(+ 1 1)"
                  {:mode :explicit :fallback-mode :silent})]
      (is (= false (:success result)))
      (is (= true (:fallback-used result)))
      (is (some? (:primary-error result))))))

;; ============================================================================
;; Retry Tests
;; ============================================================================

(deftest eval-with-retry-test
  (testing "Succeeds after retries"
    (let [evaluator (make-flaky-evaluator 2 "success")
          result (resilience/eval-with-retry
                  evaluator
                  "(+ 1 2)"
                  {:silent? true}
                  :max-retries 5
                  :delay-ms 10)]
      (is (= true (:success result)))
      (is (= "success" (:result result)))
      (is (= 3 (:attempts result)))))

  (testing "Succeeds on first attempt"
    (let [evaluator (make-succeeding-evaluator "42")
          result (resilience/eval-with-retry
                  evaluator
                  "(+ 1 1)"
                  {:silent? true}
                  :max-retries 3)]
      (is (= true (:success result)))
      (is (= "42" (:result result)))
      (is (= 1 (:attempts result)))))

  (testing "Exhausts all retries"
    (let [evaluator (make-failing-evaluator "Permanent failure")
          result (resilience/eval-with-retry
                  evaluator
                  "(+ 1 1)"
                  {:silent? true}
                  :max-retries 3
                  :delay-ms 10)]
      (is (= false (:success result)))
      (is (= 3 (:attempts result)))
      (is (some? (:error result))))))

;; ============================================================================
;; Safe Eval Tests
;; ============================================================================

(deftest safe-eval-test
  (testing "Returns :ok on success"
    (let [evaluator (make-succeeding-evaluator "42")
          result (resilience/safe-eval evaluator "(+ 1 2)" {:silent? true})]
      (is (contains? result :ok))
      (is (= "42" (:ok result)))
      (is (nil? (:error result)))))

  (testing "Returns :error on failure, never throws"
    (let [evaluator (make-failing-evaluator "Division by zero")
          result (resilience/safe-eval evaluator "(/ 1 0)" {:silent? true})]
      (is (contains? result :error))
      (is (= "Division by zero" (:error result)))
      (is (= "EvaluationFailure" (:type result)))
      (is (some? (:code result)))))

  (testing "Catches throwables from evaluator"
    (let [evil-evaluator (reify evaluator/ReplEvaluator
                           (eval-code [_ _ _]
                             (throw (ex-info "Boom!" {:cause :evil})))
                           (connected? [_] true)
                           (get-status [_] {:connected true}))
          result (resilience/safe-eval evil-evaluator "(+ 1 1)" {:silent? true})]
      (is (contains? result :error))
      (is (some? (:type result)))
      (is (some? (:error result))))))

;; ============================================================================
;; Predicate Tests
;; ============================================================================

(deftest predicate-tests
  (testing "success? works with both result formats"
    (is (resilience/success? {:success true :result "42"}))
    (is (resilience/success? {:ok "42"}))
    (is (not (resilience/success? {:success false :error "fail"})))
    (is (not (resilience/success? {:error "fail"}))))

  (testing "failed? is inverse of success?"
    (is (resilience/failed? {:success false :error "fail"}))
    (is (resilience/failed? {:error "fail"}))
    (is (not (resilience/failed? {:success true :result "42"})))
    (is (not (resilience/failed? {:ok "42"}))))

  (testing "get-value extracts result from both formats"
    (is (= "42" (resilience/get-value {:success true :result "42"})))
    (is (= "42" (resilience/get-value {:ok "42"})))
    (is (nil? (resilience/get-value {:success false :error "fail"})))
    (is (nil? (resilience/get-value {:error "fail"}))))

  (testing "get-error extracts error message"
    (is (= "fail" (resilience/get-error {:success false :error "fail"})))
    (is (= "fail" (resilience/get-error {:error "fail"})))
    (is (nil? (resilience/get-error {:success true :result "42"})))
    (is (nil? (resilience/get-error {:ok "42"})))))

;; ============================================================================
;; Integration Test
;; ============================================================================

(deftest integration-test
  (testing "Realistic failure scenario with mode-specific evaluator"
    (let [evaluator (make-mode-specific-evaluator
                     "CIDER REPL not ready"
                     "evaluation-successful")]

      ;; Try with fallback
      (let [result (resilience/eval-with-fallback
                    evaluator
                    "(require 'my.namespace)"
                    {:mode :explicit :fallback-mode :silent})]
        (is (resilience/success? result))
        (is (= :silent (:mode-used result)))
        (is (:fallback-used result)))

      ;; Use safe-eval wrapper
      (let [result (resilience/safe-eval
                    evaluator
                    "(+ 1 2)"
                    {:silent? true})]
        (is (contains? result :ok))
        (is (= "evaluation-successful" (resilience/get-value result))))))

  (testing "Realistic retry scenario with flaky connection"
    (let [evaluator (make-flaky-evaluator 2 "data-loaded")]

      ;; Retry should handle transient failures
      (let [result (resilience/eval-with-retry
                    evaluator
                    "(load-data)"
                    {:silent? true}
                    :max-retries 5
                    :delay-ms 50)]
        (is (resilience/success? result))
        (is (= 3 (:attempts result)))
        (is (= "data-loaded" (resilience/get-value result)))))))
