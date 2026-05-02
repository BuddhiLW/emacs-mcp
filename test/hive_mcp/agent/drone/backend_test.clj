(ns hive-mcp.agent.drone.backend-test
  "Tests for the IDroneExecutionBackend protocol and resolve-backend multimethod.

   Tests cover:
   - Protocol definition and method signatures
   - resolve-backend multimethod dispatch
   - Default (unknown backend) error handling
   - Custom backend registration via defmethod
   - Protocol contract compliance for test implementations"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.drone.backend :as backend]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn cleanup-test-backends
  "Remove test-registered multimethod implementations after each test."
  [f]
  (f)
  ;; Remove any test-registered methods to avoid cross-test pollution
  (doseq [k [:test-backend :mock-openrouter :mock-agentic :validation-backend :no-validation-backend]]
    (remove-method backend/resolve-backend k)))

(use-fixtures :each cleanup-test-backends)

;; =============================================================================
;; Test Backend Implementation
;; =============================================================================

(defrecord MockBackend [backend-kw validates?]
  backend/IDroneExecutionBackend

  (execute-drone [_this task-context]
    {:status :completed
     :result (str "Mock executed: " (:task task-context))
     :tokens {:input-tokens 100 :output-tokens 50}
     :model (:model task-context)})

  (supports-validation? [_this]
    validates?)

  (backend-type [_this]
    backend-kw))

;; =============================================================================
;; Section 1: Protocol Definition Tests
;; =============================================================================

(deftest protocol-exists
  (testing "IDroneExecutionBackend protocol is defined"
    (is (some? backend/IDroneExecutionBackend)
        "IDroneExecutionBackend protocol should exist")))

(deftest protocol-methods-exist
  (testing "Protocol methods are defined as functions"
    (is (ifn? backend/execute-drone)
        "execute-drone should be a function")
    (is (ifn? backend/supports-validation?)
        "supports-validation? should be a function")
    (is (ifn? backend/backend-type)
        "backend-type should be a function")))

(deftest protocol-satisfaction
  (testing "MockBackend satisfies IDroneExecutionBackend"
    (let [mock (->MockBackend :test true)]
      (is (satisfies? backend/IDroneExecutionBackend mock)
          "MockBackend should satisfy the protocol"))))

;; =============================================================================
;; Section 2: Protocol Contract Tests
;; =============================================================================

(deftest execute-drone-returns-result-map
  (testing "execute-drone returns a well-formed result map"
    (let [mock (->MockBackend :test-backend false)
          task-ctx {:task "Fix the null pointer"
                    :model "devstral-small:24b"
                    :preset "drone-worker"
                    :tools []
                    :max-steps 10
                    :trace true
                    :sandbox {:allowed-files [] :allowed-dirs [] :blocked-tools []}
                    :drone-id "drone-test-001"
                    :cwd "/tmp/project"}
          result (backend/execute-drone mock task-ctx)]
      (is (map? result)
          "Result should be a map")
      (is (contains? result :status)
          "Result should contain :status")
      (is (#{:completed :failed :timeout} (:status result))
          "Status should be one of :completed :failed :timeout")
      (is (contains? result :result)
          "Result should contain :result")
      (is (string? (:result result))
          "Result text should be a string")
      (is (contains? result :model)
          "Result should contain :model"))))

(deftest execute-drone-passes-task-context
  (testing "execute-drone receives the full task context"
    (let [mock (->MockBackend :test-backend false)
          task-ctx {:task "Implement feature X"
                    :model "test-model"}
          result (backend/execute-drone mock task-ctx)]
      (is (= :completed (:status result)))
      (is (clojure.string/includes? (:result result) "Implement feature X")
          "Result should reflect the task from context")
      (is (= "test-model" (:model result))
          "Result should reflect the model from context"))))

(deftest supports-validation-returns-boolean
  (testing "supports-validation? returns true for validating backends"
    (let [validating (->MockBackend :with-validation true)]
      (is (true? (backend/supports-validation? validating)))))

  (testing "supports-validation? returns false for non-validating backends"
    (let [non-validating (->MockBackend :without-validation false)]
      (is (false? (backend/supports-validation? non-validating))))))

(deftest backend-type-returns-keyword
  (testing "backend-type returns the backend's identifying keyword"
    (let [or-backend (->MockBackend :openrouter false)
          ag-backend (->MockBackend :agentic true)]
      (is (= :openrouter (backend/backend-type or-backend))
          "OpenRouter backend should return :openrouter")
      (is (= :agentic (backend/backend-type ag-backend))
          "Agentic backend should return :agentic"))))

;; =============================================================================
;; Section 3: resolve-backend Multimethod Tests
;; =============================================================================

(deftest resolve-backend-dispatches-on-backend-key
  (testing "resolve-backend dispatches on :backend key"
    ;; Register a test backend
    (defmethod backend/resolve-backend :test-backend [_context]
      (->MockBackend :test-backend false))

    (let [resolved (backend/resolve-backend {:backend :test-backend
                                             :model "test-model"})]
      (is (satisfies? backend/IDroneExecutionBackend resolved)
          "Resolved backend should satisfy the protocol")
      (is (= :test-backend (backend/backend-type resolved))
          "Resolved backend should have correct type"))))

(deftest resolve-backend-passes-context-to-factory
  (testing "resolve-backend passes full context to defmethod"
    (let [captured-ctx (atom nil)]
      ;; Register backend that captures context
      (defmethod backend/resolve-backend :mock-openrouter [context]
        (reset! captured-ctx context)
        (->MockBackend :mock-openrouter false))

      (backend/resolve-backend {:backend :mock-openrouter
                                :model "devstral-small:24b"
                                :cwd "/tmp/project"})

      (is (= :mock-openrouter (:backend @captured-ctx))
          "Context :backend should be passed through")
      (is (= "devstral-small:24b" (:model @captured-ctx))
          "Context :model should be passed through")
      (is (= "/tmp/project" (:cwd @captured-ctx))
          "Context :cwd should be passed through"))))

(deftest resolve-backend-unknown-throws
  (testing "resolve-backend throws for unregistered backend keys"
    (is (thrown? IllegalArgumentException
                (backend/resolve-backend {:backend :nonexistent-backend}))
        "Should throw for unknown backend")

    (is (thrown-with-msg? IllegalArgumentException
                          #"No IDroneExecutionBackend registered"
                          (backend/resolve-backend {:backend :fantasy-llm}))
        "Error message should mention IDroneExecutionBackend"))

  (testing "resolve-backend returns structured error for :openrouter (not an alias)"
    (let [result (backend/resolve-backend {:backend :openrouter})]
      (is (= :unknown-backend (:error result))
          "Should return structured error, not throw")
      (is (= :openrouter (:key result))
          "Error should identify the bad key")
      (is (= :use-agentic-loop-or-omit (:fix result))
          "Error should suggest the fix")
      (is (string? (:hint result))
          "Error should include a human-readable hint"))))

(deftest resolve-backend-nil-backend-throws
  (testing "resolve-backend with nil :backend hits :default and throws"
    (is (thrown? IllegalArgumentException
                (backend/resolve-backend {:backend nil}))
        "nil backend should throw")
    (is (thrown? IllegalArgumentException
                (backend/resolve-backend {}))
        "Missing :backend key should throw")))

;; =============================================================================
;; Section 4: Multiple Backend Registration
;; =============================================================================

(deftest multiple-backends-coexist
  (testing "Multiple backends can be registered and resolved independently"
    ;; Register two backends
    (defmethod backend/resolve-backend :mock-openrouter [_ctx]
      (->MockBackend :mock-openrouter false))

    (defmethod backend/resolve-backend :mock-agentic [_ctx]
      (->MockBackend :mock-agentic true))

    (let [or-backend (backend/resolve-backend {:backend :mock-openrouter})
          ag-backend (backend/resolve-backend {:backend :mock-agentic})]
      (is (= :mock-openrouter (backend/backend-type or-backend)))
      (is (= :mock-agentic (backend/backend-type ag-backend)))
      (is (false? (backend/supports-validation? or-backend))
          "OpenRouter mock should not support validation")
      (is (true? (backend/supports-validation? ag-backend))
          "Agentic mock should support validation"))))

;; =============================================================================
;; Section 5: End-to-End Integration (resolve → execute)
;; =============================================================================

(deftest resolve-and-execute-roundtrip
  (testing "Full resolve → execute roundtrip works"
    ;; Register
    (defmethod backend/resolve-backend :test-backend [_ctx]
      (->MockBackend :test-backend false))

    ;; Resolve
    (let [backend-impl (backend/resolve-backend {:backend :test-backend
                                                  :model "devstral-small:24b"})
          ;; Execute
          result (backend/execute-drone backend-impl
                                        {:task "Add nil check to handler"
                                         :model "devstral-small:24b"
                                         :tools []
                                         :max-steps 5})]
      (is (= :completed (:status result)))
      (is (clojure.string/includes? (:result result) "Add nil check"))
      (is (= "devstral-small:24b" (:model result)))
      (is (= {:input-tokens 100 :output-tokens 50} (:tokens result))))))

(comment
  ;; Run all backend protocol tests
  (clojure.test/run-tests 'hive-mcp.agent.drone.backend-test))
