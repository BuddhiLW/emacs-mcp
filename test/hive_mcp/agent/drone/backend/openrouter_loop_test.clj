(ns hive-mcp.agent.drone.backend.openrouter-loop-test
  "Tests for OpenRouterLoopBackend — IDroneExecutionBackend wrapping OpenRouter drone loop.

   Tests cover:
   - Protocol satisfaction and contract compliance
   - resolve-backend multimethod registration
   - Constructor defaults and model override
   - execute-drone with mocked LLM backend (no real API calls)
   - Error handling when backend creation fails"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string]
            [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.agent.drone.backend.openrouter-loop :as openrouter-loop]
            [hive-mcp.agent.drone.loop]
            [hive-mcp.agent.registry]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn ensure-registered-fixture
  "Re-register :openrouter-loop defmethod before each test (require is no-op
   if ns already loaded, so we must explicitly eval the defmethod)."
  [f]
  (defmethod backend/resolve-backend :openrouter-loop [context]
    (openrouter-loop/->openrouter-loop-backend {:model (:model context)}))
  (f)
  (remove-method backend/resolve-backend :openrouter-loop))

(use-fixtures :each ensure-registered-fixture)

;; =============================================================================
;; Section 1: Protocol Satisfaction
;; =============================================================================

(deftest protocol-satisfaction-test
  (testing "OpenRouterLoopBackend satisfies IDroneExecutionBackend"
    (let [b (openrouter-loop/->openrouter-loop-backend)]
      (is (satisfies? backend/IDroneExecutionBackend b)
          "Should satisfy the protocol"))))

;; =============================================================================
;; Section 2: backend-type
;; =============================================================================

(deftest backend-type-test
  (testing "backend-type returns :openrouter-loop"
    (let [b (openrouter-loop/->openrouter-loop-backend)]
      (is (= :openrouter-loop (backend/backend-type b))
          "backend-type should return :openrouter-loop"))))

;; =============================================================================
;; Section 3: supports-validation?
;; =============================================================================

(deftest supports-validation-test
  (testing "supports-validation? returns true"
    (let [b (openrouter-loop/->openrouter-loop-backend)]
      (is (true? (backend/supports-validation? b))
          "OpenRouter loop backend should support validation"))))

;; =============================================================================
;; Section 4: Constructor
;; =============================================================================

(deftest constructor-defaults-test
  (testing "Default constructor has nil model-override"
    (let [b (openrouter-loop/->openrouter-loop-backend)]
      (is (nil? (:model-override b))
          "Default model-override should be nil")))

  (testing "Constructor with model override"
    (let [b (openrouter-loop/->openrouter-loop-backend {:model "custom-model"})]
      (is (= "custom-model" (:model-override b))
          "model-override should be set"))))

;; =============================================================================
;; Section 5: resolve-backend Registration
;; =============================================================================

(deftest resolve-backend-registration-test
  (testing ":openrouter-loop backend is resolved correctly"
    (let [resolved (backend/resolve-backend {:backend :openrouter-loop
                                             :model "test-model"})]
      (is (satisfies? backend/IDroneExecutionBackend resolved)
          "Resolved backend should satisfy protocol")
      (is (= :openrouter-loop (backend/backend-type resolved))
          "Resolved backend should be :openrouter-loop"))))

(deftest resolve-backend-passes-model-test
  (testing "resolve-backend passes model from context to backend"
    (let [resolved (backend/resolve-backend {:backend :openrouter-loop
                                             :model "devstral-small:24b"})]
      (is (= "devstral-small:24b" (:model-override resolved))
          "Model should be passed through from context"))))

;; =============================================================================
;; Section 6: execute-drone with Mock
;; =============================================================================

(deftest execute-drone-with-mock-test
  (testing "execute-drone returns standardized result with mocked LLM"
    (let [b (openrouter-loop/->openrouter-loop-backend {:model "mock-model"})
          ;; Mock the agentic loop to return a successful result
          mock-loop-result {:status :completed
                            :result "Task completed successfully"
                            :steps [{:type :text :turn 0}]
                            :tool_calls_made 2
                            :tokens {:input 150 :output 75 :total 225}
                            :turns 1
                            :model "mock-model"
                            :kg-stats nil}]
      (with-redefs [hive-mcp.agent.drone.loop/run-agentic-loop
                    (fn [_task-spec _ctx _opts] mock-loop-result)

                    hive-mcp.agent.registry/ensure-registered!
                    (fn [] nil)

                    ;; Mock requiring-resolve for auto-backend
                    requiring-resolve
                    (fn [sym]
                      (if (= sym 'hive-mcp.agent.openrouter/auto-backend)
                        (fn [_opts] :mock-backend)
                        (clojure.core/requiring-resolve sym)))]

        (let [result (backend/execute-drone b {:task "Fix null pointer"
                                               :model "mock-model"
                                               :tools ["read_file"]
                                               :max-steps 5
                                               :trace true
                                               :drone-id "test-drone"
                                               :cwd "/tmp/project"})]
          (is (map? result) "Result should be a map")
          (is (= :completed (:status result)) "Status should be :completed")
          (is (string? (:result result)) "Result should contain result text")
          (is (= "Task completed successfully" (:result result)))
          (is (map? (:tokens result)) "Should have tokens map")
          (is (= 150 (:input-tokens (:tokens result))) "Input tokens")
          (is (= 75 (:output-tokens (:tokens result))) "Output tokens")
          (is (= "mock-model" (:model result)) "Model should be present")
          (is (= 1 (:steps result)) "Steps should be present")
          (is (= 2 (:tool-calls result)) "Tool calls should be present"))))))

(deftest execute-drone-max-steps-status-test
  (testing "execute-drone normalizes :max_steps to :completed"
    (let [b (openrouter-loop/->openrouter-loop-backend)]
      (with-redefs [hive-mcp.agent.drone.loop/run-agentic-loop
                    (fn [_ts _ctx _opts]
                      {:status :max_steps :result "Ran out of steps"
                       :tokens {:input 0 :output 0} :turns 10 :tool_calls_made 5})

                    hive-mcp.agent.registry/ensure-registered! (fn [] nil)

                    requiring-resolve
                    (fn [sym]
                      (if (= sym 'hive-mcp.agent.openrouter/auto-backend)
                        (fn [_] :mock) (clojure.core/requiring-resolve sym)))]

        (let [result (backend/execute-drone b {:task "test" :model "m"})]
          (is (= :completed (:status result))
              ":max_steps should normalize to :completed"))))))

(deftest execute-drone-noop-status-test
  (testing "execute-drone normalizes :noop to :failed"
    (let [b (openrouter-loop/->openrouter-loop-backend)]
      (with-redefs [hive-mcp.agent.drone.loop/run-agentic-loop
                    (fn [_ts _ctx _opts]
                      {:status :noop :result "No backend"
                       :tokens {:input 0 :output 0} :turns 0 :tool_calls_made 0})

                    hive-mcp.agent.registry/ensure-registered! (fn [] nil)

                    requiring-resolve
                    (fn [sym]
                      (if (= sym 'hive-mcp.agent.openrouter/auto-backend)
                        (fn [_] :mock) (clojure.core/requiring-resolve sym)))]

        (let [result (backend/execute-drone b {:task "test" :model "m"})]
          (is (= :failed (:status result))
              ":noop should normalize to :failed"))))))

;; =============================================================================
;; Section 7: Error Handling
;; =============================================================================

(deftest execute-drone-handles-backend-creation-error-test
  (testing "execute-drone returns :failed when LLM backend creation throws"
    (let [b (openrouter-loop/->openrouter-loop-backend {:model "bad-model"})]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (if (= sym 'hive-mcp.agent.openrouter/auto-backend)
                        (fn [_] (throw (ex-info "API key required" {})))
                        (clojure.core/requiring-resolve sym)))]

        (let [result (backend/execute-drone b {:task "test" :model "bad"})]
          (is (= :failed (:status result))
              "Should return :failed on backend creation error")
          (is (string? (:result result))
              "Should contain error message")
          (is (clojure.string/includes? (:result result) "API key")
              "Error message should mention the cause"))))))

(deftest execute-drone-handles-loop-exception-test
  (testing "execute-drone returns :failed when agentic loop throws"
    (let [b (openrouter-loop/->openrouter-loop-backend)]
      (with-redefs [hive-mcp.agent.drone.loop/run-agentic-loop
                    (fn [_ts _ctx _opts]
                      (throw (ex-info "Connection timeout" {:type :timeout})))

                    hive-mcp.agent.registry/ensure-registered! (fn [] nil)

                    requiring-resolve
                    (fn [sym]
                      (if (= sym 'hive-mcp.agent.openrouter/auto-backend)
                        (fn [_] :mock) (clojure.core/requiring-resolve sym)))]

        (let [result (backend/execute-drone b {:task "test" :model "m"})]
          (is (= :failed (:status result))
              "Should return :failed on loop exception")
          (is (clojure.string/includes? (:result result) "Connection timeout")
              "Should contain exception message"))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'hive-mcp.agent.drone.backend.openrouter-loop-test))
