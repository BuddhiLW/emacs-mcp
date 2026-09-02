(ns hive-mcp.workflows.sub-fsms.llm-call-test
  "Tests for LLM-call sub-FSM.

   Covers: message building, API call, response parsing, completion detection,
   error handling, graceful degradation, and convenience API."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.workflows.sub-fsms.llm-call :as llm-call]
            [hive-mcp.agent.protocol :as proto]
            [hive.events.fsm :as fsm]
            [clojure.string]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- mock-backend
  "Create a mock LLMBackend that returns the given response."
  [response & {:keys [model] :or {model "mock-model"}}]
  (reify proto/LLMBackend
    (chat [_ _msgs _tools] response)
    (model-name [_] model)))

(defn- throwing-backend
  "Create a mock LLMBackend that throws on chat."
  [ex-msg]
  (reify proto/LLMBackend
    (chat [_ _msgs _tools] (throw (ex-info ex-msg {})))
    (model-name [_] "throwing-mock")))

;; =============================================================================
;; FSM Compilation
;; =============================================================================

(deftest test-fsm-compiles
  (testing "LLM-call FSM spec compiles without errors"
    (let [compiled @llm-call/compiled]
      (is (map? compiled))
      (is (contains? compiled :fsm))
      (is (contains? (:fsm compiled) :hive.events.fsm/start))
      (is (contains? (:fsm compiled) :hive.events.fsm/end))
      (is (contains? (:fsm compiled) :hive.events.fsm/error)))))

(deftest test-fsm-has-expected-states
  (testing "FSM contains all user-defined states"
    (let [states (set (keys (:fsm @llm-call/compiled)))]
      (is (contains? states :hive-mcp.workflows.sub-fsms.llm-call/api-call))
      (is (contains? states :hive-mcp.workflows.sub-fsms.llm-call/parse-response)))))

;; =============================================================================
;; Message Building
;; =============================================================================

(deftest test-build-messages-from-task
  (testing "Builds messages from task when no pre-built messages"
    (let [result (llm-call/handle-build-messages
                  {} {:task "Fix the bug" :files ["src/foo.clj"] :turn 0})]
      (is (vector? (:messages-ready result)))
      (is (= 2 (count (:messages-ready result))))
      (is (= "system" (:role (first (:messages-ready result)))))
      (is (= "user" (:role (second (:messages-ready result)))))
      (is (= "Fix the bug" (:content (second (:messages-ready result)))))
      (is (some? (:system-prompt result))))))

(deftest test-build-messages-uses-existing
  (testing "Uses pre-built messages when provided"
    (let [existing [{:role "system" :content "Custom"}
                    {:role "user" :content "Task"}
                    {:role "assistant" :content "Working..."}]
          result (llm-call/handle-build-messages
                  {} {:task "Fix bug" :messages existing :turn 2})]
      (is (= existing (:messages-ready result))))))

(deftest test-build-messages-with-custom-prompt-fn
  (testing "Uses custom system-prompt-fn from resources"
    (let [custom-fn (fn [{:keys [task]}] (str "CUSTOM: " task))
          result (llm-call/handle-build-messages
                  {:system-prompt-fn custom-fn}
                  {:task "Deploy" :turn 0})]
      (is (= "CUSTOM: Deploy"
             (:content (first (:messages-ready result))))))))

(deftest test-build-messages-with-explicit-system-prompt
  (testing "Uses explicit system-prompt from data over fn"
    (let [result (llm-call/handle-build-messages
                  {}
                  {:task "Deploy" :system-prompt "My custom prompt" :turn 0})]
      (is (= "My custom prompt"
             (:content (first (:messages-ready result))))))))

;; =============================================================================
;; API Call
;; =============================================================================

(deftest test-api-call-text-response
  (testing "API call with text response"
    (let [backend (mock-backend {:type :text :content "Done" :usage {:input 10 :output 5 :total 15}})
          data {:messages-ready [{:role "system" :content "sys"} {:role "user" :content "task"}]
                :turn 0}
          result (llm-call/handle-api-call {:backend backend} data)]
      (is (some? (:raw-response result)))
      (is (= :text (get-in result [:raw-response :type])))
      (is (nil? (:error result))))))

(deftest test-api-call-no-backend
  (testing "Graceful degradation with no backend"
    (let [result (llm-call/handle-api-call {} {:messages-ready [] :turn 0})]
      (is (= :error (:response-type result)))
      (is (= "No LLM backend provided" (:error result))))))

(deftest test-api-call-exception
  (testing "Backend exception caught gracefully"
    (let [backend (throwing-backend "Connection timeout")
          result (llm-call/handle-api-call
                  {:backend backend}
                  {:messages-ready [] :turn 0})]
      (is (= :error (:response-type result)))
      (is (clojure.string/includes? (:error result) "Connection timeout")))))

;; =============================================================================
;; Response Parsing
;; =============================================================================

(deftest test-parse-text-response
  (testing "Parse text response with completion language"
    (let [result (llm-call/handle-parse-response
                  {}
                  {:raw-response {:type :text
                                  :content "I've successfully completed the task."
                                  :usage {:input 100 :output 50 :total 150}}})]
      (is (= :text (:response-type result)))
      (is (true? (:completion? result)))
      (is (= "I've successfully completed the task." (:text-content result)))
      (is (nil? (:tool-calls result)))
      (is (= 150 (get-in result [:usage :total]))))))

(deftest test-parse-text-no-completion
  (testing "Parse text response without completion language"
    (let [result (llm-call/handle-parse-response
                  {}
                  {:raw-response {:type :text :content "Let me check the file..."}})]
      (is (= :text (:response-type result)))
      (is (false? (:completion? result)))
      (is (= "Let me check the file..." (:text-content result))))))

(deftest test-parse-tool-calls
  (testing "Parse tool_calls response"
    (let [calls [{:id "c1" :name "read_file" :arguments {:path "x"}}
                 {:id "c2" :name "propose_diff" :arguments {:file_path "y"}}]
          result (llm-call/handle-parse-response
                  {}
                  {:raw-response {:type :tool_calls :calls calls
                                  :usage {:input 200 :output 100 :total 300}}})]
      (is (= :tool_calls (:response-type result)))
      (is (false? (:completion? result)))
      (is (= 2 (count (:tool-calls result))))
      (is (= ["read_file" "propose_diff"] (mapv :name (:tool-calls result))))
      (is (nil? (:text-content result))))))

(deftest test-parse-error-response
  (testing "Parse error response from LLM"
    (let [result (llm-call/handle-parse-response
                  {}
                  {:raw-response {:type :error :error "Rate limit exceeded"}})]
      (is (= :error (:response-type result)))
      (is (= "Rate limit exceeded" (:error result)))
      (is (false? (:completion? result))))))

(deftest test-parse-unknown-type
  (testing "Parse unknown response type"
    (let [result (llm-call/handle-parse-response
                  {}
                  {:raw-response {:type :unknown_type}})]
      (is (= :error (:response-type result)))
      (is (clojure.string/includes? (:error result) "Unknown response type")))))

(deftest test-parse-with-prior-error
  (testing "Parse skips when error already set"
    (let [result (llm-call/handle-parse-response
                  {}
                  {:error "Previous error" :raw-response {:type :text :content "x"}})]
      (is (= :error (:response-type result)))
      (is (false? (:completion? result)))
      (is (nil? (:text-content result))))))

;; =============================================================================
;; Completion Language Detection
;; =============================================================================

(deftest test-completion-language-positive
  (testing "Completion patterns detected"
    (doseq [text ["Task completed"
                  "I've successfully completed the changes"
                  "All changes have been applied"
                  "The fix has been applied"
                  "Successfully implemented the feature"
                  "Here's the summary"
                  "Nothing more to do"
                  "all done"]]
      (is (true? (llm-call/completion-language? text))
          (str "Should detect: " text)))))

(deftest test-completion-language-negative
  (testing "Non-completion text not detected"
    (doseq [text ["Let me look at the error"
                  "I found a bug in the code"
                  "Reading the file now"
                  "The function needs refactoring"
                  "I'll fix this by..."]]
      (is (false? (boolean (llm-call/completion-language? text)))
          (str "Should NOT detect: " text)))))

(deftest test-completion-language-edge-cases
  (testing "Edge cases: nil, empty, whitespace"
    (is (nil? (llm-call/completion-language? nil)))
    (is (nil? (llm-call/completion-language? "")))
    (is (nil? (llm-call/completion-language? "   ")))))

;; =============================================================================
;; End-to-End via run-llm-call
;; =============================================================================

(deftest test-e2e-text-completion
  (testing "Full FSM run: text completion"
    (let [backend (mock-backend {:type :text
                                 :content "Successfully fixed the bug."
                                 :usage {:input 80 :output 30 :total 110}})
          result (llm-call/run-llm-call
                  {:task "Fix null pointer" :files ["src/app.clj"] :turn 0}
                  {:backend backend})]
      (is (= :text (:response-type result)))
      (is (true? (:completion? result)))
      (is (= "Successfully fixed the bug." (:text-content result)))
      (is (= 110 (get-in result [:usage :total])))
      ;; Internal fields cleaned up
      (is (not (contains? result :raw-response)))
      (is (not (contains? result :messages-ready))))))

(deftest test-e2e-tool-calls
  (testing "Full FSM run: tool calls"
    (let [backend (mock-backend {:type :tool_calls
                                 :calls [{:id "t1" :name "read_file"
                                          :arguments {:path "src/foo.clj"}}]
                                 :usage {:input 50 :output 25 :total 75}})
          result (llm-call/run-llm-call
                  {:task "Read file" :turn 1
                   :messages [{:role "system" :content "sys"}
                              {:role "user" :content "Read file"}]}
                  {:backend backend
                   :tool-schemas [{:name "read_file" :description "Read a file"}]})]
      (is (= :tool_calls (:response-type result)))
      (is (= 1 (count (:tool-calls result))))
      (is (= "read_file" (-> result :tool-calls first :name)))
      (is (false? (:completion? result))))))

(deftest test-e2e-error-no-backend
  (testing "Full FSM run: no backend"
    (let [result (llm-call/run-llm-call {:task "Test" :turn 0} {})]
      (is (= :error (:response-type result)))
      (is (= "No LLM backend provided" (:error result)))
      (is (not (contains? result :raw-response))))))

(deftest test-e2e-backend-exception
  (testing "Full FSM run: backend throws"
    (let [backend (throwing-backend "API unreachable")
          result (llm-call/run-llm-call {:task "Test" :turn 0} {:backend backend})]
      (is (= :error (:response-type result)))
      (is (clojure.string/includes? (:error result) "API unreachable")))))

;; =============================================================================
;; Convenience API
;; =============================================================================

(deftest test-run-llm-call-with-backend
  (testing "Convenience wrapper works"
    (let [backend (mock-backend {:type :text :content "Done" :usage {:input 10 :output 5 :total 15}})
          result (llm-call/run-llm-call-with-backend
                  backend "Fix the thing" nil {:files ["a.clj"] :turn 2})]
      (is (= :text (:response-type result)))
      (is (= "a.clj" (first (:files result))))
      (is (= 2 (:turn result))))))

(deftest test-run-llm-call-with-backend-minimal
  (testing "Convenience wrapper with minimal args"
    (let [backend (mock-backend {:type :text :content "OK"})
          result (llm-call/run-llm-call-with-backend backend "Do it" nil)]
      (is (= :text (:response-type result)))
      (is (= 0 (:turn result))))))

;; =============================================================================
;; Sub-FSM Composition (via fsm/run directly)
;; =============================================================================

(deftest test-sub-fsm-composition
  (testing "LLM-call can be used as sub-FSM from parent handler"
    (let [backend (mock-backend {:type :tool_calls
                                 :calls [{:id "c1" :name "write" :arguments {:x 1}}]})
          ;; Simulate parent handler calling sub-FSM
          parent-data {:task "Parent task" :turn 3 :parent-field "kept"}
          sub-input (select-keys parent-data [:task :turn])
          sub-result (llm-call/run-llm-call sub-input {:backend backend})
          ;; Parent merges sub-FSM result into its data
          merged (merge parent-data (select-keys sub-result [:response-type :tool-calls :completion?]))]
      (is (= :tool_calls (:response-type merged)))
      (is (= "kept" (:parent-field merged)))
      (is (= 1 (count (:tool-calls merged)))))))

;; =============================================================================
;; run-sub-fsm Helper (Parent Embedding)
;; =============================================================================

(deftest test-run-sub-fsm-basic
  (testing "run-sub-fsm extracts keys from parent data and merges result back"
    (let [backend (mock-backend {:type :text
                                 :content "Successfully fixed the bug."
                                 :usage {:input 80 :output 30 :total 110}})
          resources {:backend backend}
          parent-data {:task "Fix null pointer"
                       :files ["src/app.clj"]
                       :turn 0
                       :parent-field "preserved"
                       :other-field 42}
          result (llm-call/run-sub-fsm resources parent-data)]
      (is (= "preserved" (:parent-field result))
          "Preserves parent fields")
      (is (= 42 (:other-field result))
          "Preserves other parent fields")
      (is (= :text (:response-type result))
          "Merges :response-type into parent")
      (is (true? (:completion? result))
          "Merges :completion? into parent")
      (is (= "Successfully fixed the bug." (:text-content result))
          "Merges :text-content into parent")
      (is (= 110 (get-in result [:usage :total]))
          "Merges :usage into parent"))))

(deftest test-run-sub-fsm-tool-calls
  (testing "run-sub-fsm with tool_calls response"
    (let [backend (mock-backend {:type :tool_calls
                                 :calls [{:id "t1" :name "read_file"
                                          :arguments {:path "src/foo.clj"}}]})
          resources {:backend backend :tool-schemas [{:name "read_file"}]}
          parent-data {:task "Read file" :turn 1
                       :messages [{:role "system" :content "sys"}
                                  {:role "user" :content "Read file"}]
                       :budget-remaining 5.0}
          result (llm-call/run-sub-fsm resources parent-data)]
      (is (= :tool_calls (:response-type result))
          "Returns tool_calls type")
      (is (= 1 (count (:tool-calls result)))
          "Has tool calls")
      (is (= "read_file" (-> result :tool-calls first :name))
          "Correct tool name")
      (is (= 5.0 (:budget-remaining result))
          "Preserves parent budget field"))))

(deftest test-run-sub-fsm-with-overrides
  (testing "run-sub-fsm accepts override map for input keys"
    (let [backend (mock-backend {:type :text :content "Done"})
          resources {:backend backend}
          parent-data {:task "Original task" :turn 0 :parent-field "kept"}
          result (llm-call/run-sub-fsm resources parent-data
                                       {:task "Override task" :turn 5})]
      (is (= :text (:response-type result))
          "Produces result")
      (is (= "kept" (:parent-field result))
          "Preserves parent fields"))))

(deftest test-run-sub-fsm-uses-llm-call-resources-key
  (testing "run-sub-fsm prefers :llm-call-resources from parent resources"
    (let [inner-backend (mock-backend {:type :text :content "Inner"} :model "inner-model")
          outer-backend (mock-backend {:type :text :content "Outer"} :model "outer-model")
          resources {:llm-call-resources {:backend inner-backend}
                     :backend outer-backend}
          parent-data {:task "Test" :turn 0}
          result (llm-call/run-sub-fsm resources parent-data)]
      (is (= :text (:response-type result))
          "Produces result")
      (is (= "Inner" (:text-content result))
          "Uses inner backend from :llm-call-resources, not outer"))))

(deftest test-run-sub-fsm-error-propagation
  (testing "run-sub-fsm propagates error into parent data"
    (let [resources {}  ;; no backend
          parent-data {:task "Test" :turn 0 :parent-field "kept"}
          result (llm-call/run-sub-fsm resources parent-data)]
      (is (= :error (:response-type result))
          "Error type propagated")
      (is (= "No LLM backend provided" (:error result))
          "Error message propagated")
      (is (= "kept" (:parent-field result))
          "Parent fields preserved even on error"))))

;; =============================================================================
;; Default System Prompt
;; =============================================================================

(deftest test-default-system-prompt
  (testing "Default system prompt includes files"
    (let [prompt (llm-call/default-system-prompt {:files ["a.clj" "b.clj"]})]
      (is (clojure.string/includes? prompt "propose_diff"))
      (is (clojure.string/includes? prompt "a.clj, b.clj"))))

  (testing "Default system prompt without files"
    (let [prompt (llm-call/default-system-prompt {})]
      (is (clojure.string/includes? prompt "propose_diff"))
      (is (not (clojure.string/includes? prompt "Target files:"))))))

;; =============================================================================
;; Terminal Handlers (identity — NOT executed by FSM engine)
;; =============================================================================

(deftest test-handle-end-is-identity
  (testing "handle-end returns data unchanged"
    (let [data {:response-type :text :completion? true :extra "field"}
          result (llm-call/handle-end {} data)]
      (is (= data result)
          "handle-end is identity function"))))

(deftest test-handle-error-is-identity
  (testing "handle-error returns data unchanged"
    (let [data {:error "Something went wrong" :response-type :error}
          result (llm-call/handle-error {} data)]
      (is (= data result)
          "handle-error is identity function"))))

;; =============================================================================
;; FSM Spec Structure
;; =============================================================================

(deftest test-llm-call-spec-structure
  (testing "llm-call-spec has correct shape"
    (let [spec llm-call/llm-call-spec]
      (is (map? (:fsm spec)) "Has :fsm key")
      (is (map? (:opts spec)) "Has :opts key")
      (is (= 10 (get-in spec [:opts :max-trace])) "max-trace is 10")
      (is (contains? (:fsm spec) ::fsm/start) "Has ::fsm/start")
      (is (contains? (:fsm spec) ::fsm/end) "Has ::fsm/end")
      (is (contains? (:fsm spec) ::fsm/error) "Has ::fsm/error")
      (is (contains? (:fsm spec) :hive-mcp.workflows.sub-fsms.llm-call/api-call)
          "Has ::api-call state")
      (is (contains? (:fsm spec) :hive-mcp.workflows.sub-fsms.llm-call/parse-response)
          "Has ::parse-response state"))))

(deftest test-llm-call-spec-dispatch-structure
  (testing "Each non-terminal state has :handler and :dispatches"
    (let [fsm-map (:fsm llm-call/llm-call-spec)
          user-states [::fsm/start
                       :hive-mcp.workflows.sub-fsms.llm-call/api-call
                       :hive-mcp.workflows.sub-fsms.llm-call/parse-response]]
      (doseq [state user-states]
        (let [state-def (get fsm-map state)]
          (is (fn? (:handler state-def))
              (str state " has a handler function"))
          (is (vector? (:dispatches state-def))
              (str state " has dispatches vector")))))))

;; =============================================================================
;; Edge Cases & Boundary Conditions
;; =============================================================================

(deftest test-build-messages-nil-task
  (testing "Build messages with nil task uses fallback"
    (let [result (llm-call/handle-build-messages {} {:turn 0})]
      (is (= "No task provided"
             (:content (second (:messages-ready result))))
          "Nil task uses 'No task provided' fallback"))))

(deftest test-api-call-passes-tool-schemas
  (testing "API call passes tool schemas to backend"
    (let [received-tools (atom nil)
          backend (reify proto/LLMBackend
                    (chat [_ _msgs tools]
                      (reset! received-tools tools)
                      {:type :text :content "ok"})
                    (model-name [_] "schema-test"))
          schemas [{:name "read_file" :description "Read"}
                   {:name "propose_diff" :description "Diff"}]
          _ (llm-call/handle-api-call
             {:backend backend :tool-schemas schemas}
             {:messages-ready [{:role "user" :content "test"}] :turn 0})]
      (is (= schemas @received-tools)
          "Tool schemas passed to backend.chat"))))

(deftest test-e2e-messages-updated-in-result
  (testing "E2E: result contains system-prompt but not raw internal fields"
    (let [backend (mock-backend {:type :text :content "Done" :usage {:input 10 :output 5 :total 15}})
          result (llm-call/run-llm-call {:task "Test" :turn 0} {:backend backend})]
      (is (some? (:system-prompt result))
          "System prompt preserved in result")
      (is (not (contains? result :raw-response))
          "Internal :raw-response cleaned up")
      (is (not (contains? result :messages-ready))
          "Internal :messages-ready cleaned up"))))

(deftest test-e2e-continuation-turn-preserves-messages
  (testing "E2E: continuation turn uses provided messages directly"
    (let [existing-msgs [{:role "system" :content "sys"}
                         {:role "user" :content "task"}
                         {:role "assistant" :content "I'll check..."}
                         {:role "user" :content "result of tool call"}]
          backend (mock-backend {:type :tool_calls
                                 :calls [{:id "c1" :name "propose_diff"
                                          :arguments {:file "x.clj"}}]})
          result (llm-call/run-llm-call
                  {:task "Fix bug" :turn 2 :messages existing-msgs}
                  {:backend backend})]
      (is (= :tool_calls (:response-type result))
          "Tool calls response type")
      (is (= 1 (count (:tool-calls result)))
          "One tool call returned"))))

(deftest test-parse-response-preserves-existing-data-keys
  (testing "parse-response preserves all non-response keys in data"
    (let [data {:raw-response {:type :text :content "Done" :usage {:total 10}}
                :task "My task"
                :turn 3
                :custom-key "preserved"}
          result (llm-call/handle-parse-response {} data)]
      (is (= "My task" (:task result)) "Preserves :task")
      (is (= 3 (:turn result)) "Preserves :turn")
      (is (= "preserved" (:custom-key result)) "Preserves custom keys"))))

(deftest test-completion-language-case-insensitive
  (testing "Completion patterns are case-insensitive"
    (is (true? (llm-call/completion-language? "TASK COMPLETED"))
        "All caps detected")
    (is (true? (llm-call/completion-language? "Task Completed"))
        "Title case detected")
    (is (true? (llm-call/completion-language? "ALL DONE"))
        "ALL DONE caps detected")))
