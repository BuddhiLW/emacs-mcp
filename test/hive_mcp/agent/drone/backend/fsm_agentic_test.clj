(ns hive-mcp.agent.drone.backend.fsm-agentic-test
  "Tests for FSMAgenticBackend — IDroneExecutionBackend via FSM drone-loop.

   Tests cover:
   - Protocol satisfaction and contract compliance
   - resolve-backend :fsm-agentic registration
   - Resource wiring from task-context
   - Result adaptation from FSM output -> protocol contract
   - Graceful degradation when drone-loop FSM unavailable
   - Constructor and factory patterns
   - End-to-end mock execution"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.agent.drone.backend.fsm-agentic :as fsm-backend]
            [clojure.string]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn cleanup-fixture
  "Remove :fsm-agentic method after tests to avoid cross-suite pollution."
  [f]
  (f)
  ;; The defmethod is loaded via require — don't remove it since it's
  ;; the real registration. Only remove if we registered test-specific ones.
  )

(use-fixtures :each cleanup-fixture)

;; =============================================================================
;; Section 1: Protocol Satisfaction
;; =============================================================================

(deftest protocol-satisfaction
  (testing "FSMAgenticBackend satisfies IDroneExecutionBackend"
    (let [backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (is (satisfies? backend/IDroneExecutionBackend backend-impl)
          "FSMAgenticBackend should satisfy the protocol"))))

(deftest backend-type-returns-keyword
  (testing "backend-type returns :fsm-agentic"
    (let [backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (is (= :fsm-agentic (backend/backend-type backend-impl))
          "Should return :fsm-agentic keyword"))))

(deftest supports-validation-returns-true
  (testing "supports-validation? returns true (FSM can include validation sub-FSM)"
    (let [backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (is (true? (backend/supports-validation? backend-impl))
          "FSM agentic backend should support validation"))))

;; =============================================================================
;; Section 2: Constructor & Factory
;; =============================================================================

(deftest make-backend-no-args
  (testing "make-fsm-agentic-backend with no args creates valid backend"
    (let [backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (is (instance? hive_mcp.agent.drone.backend.fsm_agentic.FSMAgenticBackend
                     backend-impl))
      (is (map? (:opts backend-impl)))
      (is (empty? (:opts backend-impl))))))

(deftest make-backend-with-opts
  (testing "make-fsm-agentic-backend with opts stores them"
    (let [factory-fn (fn [model] {:model model})
          executor-fn (fn [call sandbox] {:result "ok"})
          backend-impl (fsm-backend/make-fsm-agentic-backend
                        {:llm-backend-factory factory-fn
                         :tool-executor-fn executor-fn})]
      (is (= factory-fn (get-in backend-impl [:opts :llm-backend-factory])))
      (is (= executor-fn (get-in backend-impl [:opts :tool-executor-fn]))))))

;; =============================================================================
;; Section 3: resolve-backend Registration
;; =============================================================================

(deftest resolve-backend-fsm-agentic
  (testing "resolve-backend :fsm-agentic returns FSMAgenticBackend"
    (let [backend-impl (backend/resolve-backend {:backend :fsm-agentic
                                                 :model "test-model"})]
      (is (satisfies? backend/IDroneExecutionBackend backend-impl)
          "Resolved backend should satisfy protocol")
      (is (= :fsm-agentic (backend/backend-type backend-impl))
          "Resolved backend should have correct type"))))

(deftest resolve-backend-passes-context
  (testing "resolve-backend passes :llm-backend-factory and :tool-executor-fn from context"
    (let [factory-fn (fn [m] m)
          executor-fn (fn [c s] nil)
          backend-impl (backend/resolve-backend {:backend :fsm-agentic
                                                 :model "test-model"
                                                 :llm-backend-factory factory-fn
                                                 :tool-executor-fn executor-fn})]
      (is (= factory-fn (get-in backend-impl [:opts :llm-backend-factory])))
      (is (= executor-fn (get-in backend-impl [:opts :tool-executor-fn]))))))

;; =============================================================================
;; Section 4: Graceful Degradation (drone-loop not yet available)
;; =============================================================================

(deftest execute-drone-graceful-degradation
  (testing "execute-drone returns :failed when drone-loop FSM not available"
    ;; Since drone_loop.clj doesn't exist yet, this tests the fallback path
    (let [backend-impl (fsm-backend/make-fsm-agentic-backend)
          task-context {:task "Fix the null pointer"
                        :model "devstral-small:24b"
                        :preset "drone-worker"
                        :tools []
                        :max-steps 10
                        :drone-id "drone-fsm-test-001"
                        :cwd "/tmp/project"
                        :sandbox {:allowed-files #{}
                                  :allowed-dirs #{"/tmp/project"}
                                  :blocked-tools #{}}}]
      ;; Force unavailability by redefining the resolver
      (with-redefs [fsm-backend/get-run-drone-loop (constantly nil)]
        (let [result (backend/execute-drone backend-impl task-context)]
          (is (map? result) "Result should be a map")
          (is (= :failed (:status result))
              "Should fail gracefully when drone-loop unavailable")
          (is (string? (:result result))
              "Should have descriptive error message")
          (is (clojure.string/includes? (:result result) "not yet available")
              "Error message should indicate drone-loop is pending")
          (is (= {:input-tokens 0 :output-tokens 0} (:tokens result))
              "Should have zero tokens on failure")
          (is (= "devstral-small:24b" (:model result))
              "Should preserve model from task-context")
          (is (= 0 (:steps result))
              "Should have zero steps on failure")
          (is (= :unavailable (get-in result [:metadata :reason]))
              "Metadata should indicate unavailable"))))))

;; =============================================================================
;; Section 5: Execute with Mock drone-loop
;; =============================================================================

(deftest execute-drone-with-mock-run-fn
  (testing "execute-drone delegates to run-drone-loop when available"
    (let [captured-data (atom nil)
          captured-resources (atom nil)
          mock-run-fn (fn [data resources]
                        (reset! captured-data data)
                        (reset! captured-resources resources)
                        {:status :completed
                         :result "Task completed successfully"
                         :turns 3
                         :tool-calls 2
                         :usage {:input-tokens 500 :output-tokens 200}
                         :kg-observations [{:type :file-read :file "src/foo.clj"}]})
          backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (let [result (backend/execute-drone backend-impl
                                            {:task "Fix the null pointer"
                                             :model "test-model"
                                             :preset "tdd"
                                             :tools ["read_file" "grep"]
                                             :max-steps 5
                                             :drone-id "drone-mock-001"
                                             :cwd "/project"
                                             :sandbox {:allowed-files #{"src/foo.clj"}
                                                       :allowed-dirs #{"/project"}
                                                       :blocked-tools #{}}})]
          ;; Verify result adaptation
          (is (= :completed (:status result))
              "Should map :completed -> :completed")
          (is (= "Task completed successfully" (:result result)))
          (is (= {:input-tokens 500 :output-tokens 200} (:tokens result)))
          (is (= "test-model" (:model result)))
          (is (= 3 (:steps result)) "Should map :turns to :steps")
          (is (= 2 (:tool-calls result)))
          (is (= :fsm-agentic (get-in result [:metadata :backend])))
          (is (= 1 (get-in result [:metadata :kg-observations])))

          ;; Verify initial data was passed correctly
          (let [data @captured-data]
            (is (= "Fix the null pointer" (:task data)))
            (is (= 5 (:max-turns data)))
            (is (= "drone-mock-001" (:drone-id data)))
            (is (= 0 (:turn data)))
            (is (= [] (:messages data)))
            (is (= :running (:status data))))

          ;; Verify resources were built
          (let [resources @captured-resources]
            (is (= "test-model" (:model resources)))
            (is (= ["read_file" "grep"] (:tools resources)))
            (is (= "tdd" (:preset resources)))
            (is (= "/project" (:cwd resources)))
            (is (= "drone-mock-001" (:drone-id resources)))))))))

(deftest execute-drone-maps-timeout-status
  (testing "execute-drone maps :max-turns-exceeded to :timeout"
    (let [mock-run-fn (fn [_data _resources]
                        {:status :max-turns-exceeded
                         :result "Reached max turns"
                         :turns 20
                         :tool-calls 15
                         :usage {:input-tokens 10000 :output-tokens 5000}})
          backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (let [result (backend/execute-drone backend-impl
                                            {:task "Long task"
                                             :model "test-model"
                                             :max-steps 20
                                             :drone-id "drone-timeout-test"
                                             :sandbox {:allowed-files #{}
                                                       :allowed-dirs #{}
                                                       :blocked-tools #{}}})]
          (is (= :timeout (:status result))
              "Should map :max-turns-exceeded to :timeout")
          (is (= 20 (:steps result)))
          (is (= 15 (:tool-calls result))))))))

(deftest execute-drone-maps-unknown-status-to-failed
  (testing "execute-drone maps unknown FSM status to :failed"
    (let [mock-run-fn (fn [_data _resources]
                        {:status :weird-unknown-status
                         :result "Something unexpected"})
          backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (let [result (backend/execute-drone backend-impl
                                            {:task "Test task"
                                             :model "test-model"
                                             :drone-id "drone-unknown"
                                             :sandbox {:allowed-files #{}
                                                       :allowed-dirs #{}
                                                       :blocked-tools #{}}})]
          (is (= :failed (:status result))
              "Unknown FSM status should map to :failed"))))))

(deftest execute-drone-handles-exception
  (testing "execute-drone catches exceptions from run-drone-loop"
    (let [mock-run-fn (fn [_data _resources]
                        (throw (ex-info "Simulated crash" {:cause :test})))
          backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (let [result (backend/execute-drone backend-impl
                                            {:task "Crash test"
                                             :model "test-model"
                                             :drone-id "drone-crash"
                                             :sandbox {:allowed-files #{}
                                                       :allowed-dirs #{}
                                                       :blocked-tools #{}}})]
          (is (= :failed (:status result))
              "Should return :failed on exception")
          (is (clojure.string/includes? (:result result) "Simulated crash")
              "Should include exception message")
          (is (= 0 (:steps result)))
          (is (= :fsm-agentic (get-in result [:metadata :backend]))))))))

;; =============================================================================
;; Section 6: Resource Wiring with Custom Factories
;; =============================================================================

(deftest execute-drone-wires-llm-backend-factory
  (testing "LLM backend factory from opts is wired into resources"
    (let [factory-called (atom nil)
          captured-resources (atom nil)
          mock-factory (fn [model]
                         (reset! factory-called model)
                         {:type :mock-llm :model model})
          mock-run-fn (fn [_data resources]
                        (reset! captured-resources resources)
                        {:status :completed :result "ok"})
          ;; drone-loop owns the FSM resources contract: ask it which slot an
          ;; LLMBackend lands in rather than restating the key here.
          make-resources (requiring-resolve
                          'hive-mcp.workflows.sub-fsms.drone-loop/make-production-resources)
          sentinel (Object.)
          backend-slot (some (fn [[k v]] (when (identical? v sentinel) k))
                             (make-resources sentinel {}))
          backend-impl (fsm-backend/make-fsm-agentic-backend
                        {:llm-backend-factory mock-factory})]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (backend/execute-drone backend-impl
                               {:task "Factory test"
                                :model "custom-model"
                                :drone-id "drone-factory"
                                :sandbox {:allowed-files #{}
                                          :allowed-dirs #{}
                                          :blocked-tools #{}}})
        (is (some? backend-slot)
            "drone-loop resources contract must carry an LLMBackend slot")
        (is (= "custom-model" @factory-called)
            "Factory should be called with model")
        (is (= {:type :mock-llm :model "custom-model"}
               (get @captured-resources backend-slot))
            "Resources should carry the factory result in drone-loop's backend slot")))))

(deftest execute-drone-wires-tool-executor
  (testing "Tool executor from opts is wired into resources"
    (let [captured-resources (atom nil)
          mock-executor (fn [call sandbox] {:result "executed"})
          mock-run-fn (fn [_data resources]
                        (reset! captured-resources resources)
                        {:status :completed :result "ok"})
          backend-impl (fsm-backend/make-fsm-agentic-backend
                        {:tool-executor-fn mock-executor})]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (backend/execute-drone backend-impl
                               {:task "Executor test"
                                :model "test-model"
                                :drone-id "drone-executor"
                                :sandbox {:allowed-files #{}
                                          :allowed-dirs #{}
                                          :blocked-tools #{}}})
        (is (= mock-executor (:tool-executor @captured-resources))
            "Resources should contain tool executor fn")))))

;; =============================================================================
;; Section 7: Initial Data Construction
;; =============================================================================

(deftest initial-data-construction
  (testing "Initial data is correctly built from task-context"
    (let [captured-data (atom nil)
          mock-run-fn (fn [data _resources]
                        (reset! captured-data data)
                        {:status :completed :result "ok"})
          backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (backend/execute-drone backend-impl
                               {:task "Build initial data"
                                :model "test-model"
                                :max-steps 15
                                :drone-id "drone-init-data"
                                :cwd "/my/project"
                                :sandbox {:allowed-files #{"a.clj" "b.clj"}
                                          :allowed-dirs #{"/my/project"}
                                          :blocked-tools #{}}})
        (let [data @captured-data]
          (is (= "Build initial data" (:task data)))
          (is (= 15 (:max-turns data)) ":max-steps maps to :max-turns")
          (is (= "drone-init-data" (:drone-id data)))
          (is (= 0 (:turn data)) "Turn starts at 0")
          (is (= [] (:messages data)) "Messages start empty")
          (is (= [] (:kg-observations data)) "KG observations start empty")
          (is (= [] (:tool-results data)) "Tool results start empty")
          (is (= :running (:status data)) "Status starts as :running")
          ;; Files extracted from sandbox allowed-files (set -> vec)
          (is (vector? (:files data)) "Files should be a vector")
          (is (= #{"a.clj" "b.clj"} (set (:files data)))
              "Files should come from sandbox allowed-files"))))))

(deftest initial-data-defaults
  (testing "Initial data handles missing optional fields"
    (let [captured-data (atom nil)
          mock-run-fn (fn [data _resources]
                        (reset! captured-data data)
                        {:status :completed :result "ok"})
          backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (backend/execute-drone backend-impl
                               {:task "Minimal context"
                                :model "test-model"
                                :drone-id "drone-minimal"
                                :sandbox {:allowed-files #{}
                                          :allowed-dirs #{}
                                          :blocked-tools #{}}})
        (let [data @captured-data]
          (is (= 20 (:max-turns data)) "Default max-turns is 20")
          (is (= [] (:files data)) "Empty allowed-files produces empty vector"))))))

;; =============================================================================
;; Section 8: Result Adaptation Edge Cases
;; =============================================================================

(deftest result-adaptation-nil-fields
  (testing "adapt-fsm-result handles nil/missing fields gracefully"
    (let [mock-run-fn (fn [_data _resources]
                        {:status :completed})  ;; Minimal result
          backend-impl (fsm-backend/make-fsm-agentic-backend)]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (let [result (backend/execute-drone backend-impl
                                            {:task "Minimal result test"
                                             :model "test-model"
                                             :drone-id "drone-nil"
                                             :sandbox {:allowed-files #{}
                                                       :allowed-dirs #{}
                                                       :blocked-tools #{}}})]
          (is (= :completed (:status result)))
          (is (= "" (:result result)) "nil result should default to empty string")
          (is (= {:input-tokens 0 :output-tokens 0} (:tokens result))
              "Missing usage should default to 0")
          (is (= "test-model" (:model result)))
          (is (= 0 (:steps result)) "nil turns should default to 0")
          (is (= 0 (:tool-calls result)) "nil tool-calls should default to 0"))))))

;; =============================================================================
;; Section 9: End-to-End Resolve -> Execute
;; =============================================================================

(deftest e2e-resolve-and-execute
  (testing "Full resolve -> execute roundtrip with :fsm-agentic"
    (let [mock-run-fn (fn [data _resources]
                        {:status :completed
                         :result (str "Processed: " (:task data))
                         :turns 2
                         :tool-calls 1
                         :usage {:input-tokens 300 :output-tokens 100}})
          ;; Resolve
          backend-impl (backend/resolve-backend {:backend :fsm-agentic})]
      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (let [result (backend/execute-drone backend-impl
                                            {:task "E2E test task"
                                             :model "devstral-small:24b"
                                             :tools ["read_file"]
                                             :max-steps 5
                                             :drone-id "drone-e2e"
                                             :cwd "/tmp"
                                             :sandbox {:allowed-files #{"test.clj"}
                                                       :allowed-dirs #{"/tmp"}
                                                       :blocked-tools #{}}})]
          (is (= :completed (:status result)))
          (is (= "Processed: E2E test task" (:result result)))
          (is (= "devstral-small:24b" (:model result)))
          (is (= 2 (:steps result)))
          (is (= {:input-tokens 300 :output-tokens 100} (:tokens result))))))))

(comment
  ;; Run all FSM agentic backend tests
  (clojure.test/run-tests 'hive-mcp.agent.drone.backend.fsm-agentic-test))
