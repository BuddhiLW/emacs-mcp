(ns hive-mcp.tools.cider-pinning-test
  "Pinning tests for CIDER handler return formats.

   Tests verify the MCP response format {:type \"text\" :text \"...\"} is
   consistently returned from CIDER handlers. Uses with-redefs to mock
   emacsclient calls.

   Covers:
   - handle-cider-status
   - handle-cider-eval-silent
   - handle-cider-list-sessions"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hive-mcp.tools.cider :as tools]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.test.stub.elisp :as elisp-stub]))

;; eval-path tests call eval-elisp-with-timeout; route it through the
;; per-test eval-elisp mock (var resolved at call time).
(use-fixtures :each
  elisp-stub/with-elisp-builders
  (fn [t]
    (with-redefs [ec/eval-elisp-with-timeout (fn [elisp & _] (ec/eval-elisp elisp))]
      (t))))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn mock-emacsclient-success
  "Creates a mock eval-elisp that returns success with given result."
  [result]
  (fn [_elisp]
    {:success true :result result :duration-ms 10}))

(defn mock-emacsclient-failure
  "Creates a mock eval-elisp that returns failure with given error."
  [error]
  (fn [_elisp]
    {:success false :error error :duration-ms 10}))

(defmacro with-mock-emacsclient
  "Execute body with mocked emacsclient/eval-elisp."
  [mock-fn & body]
  `(with-redefs [ec/eval-elisp ~mock-fn]
     ~@body))

;; =============================================================================
;; handle-cider-status Tests
;; =============================================================================

(deftest handle-cider-status-success-test
  (testing "Returns proper MCP response format on success"
    (let [status-json "{\"connected\":true,\"repl-type\":\"clj\",\"ns\":\"user\"}"]
      (with-mock-emacsclient (mock-emacsclient-success status-json)
        (let [result (tools/handle-cider-status {})]
          (is (= "text" (:type result)))
          (is (string? (:text result)))
          (is (nil? (:isError result)))
          ;; Verify JSON content is passed through
          (let [parsed (json/read-str (:text result) :key-fn keyword)]
            (is (true? (:connected parsed)))
            (is (= "clj" (:repl-type parsed)))
            (is (= "user" (:ns parsed)))))))))

(deftest handle-cider-status-error-test
  (testing "Returns error format when emacsclient fails"
    (with-mock-emacsclient (mock-emacsclient-failure "CIDER not connected")
      (let [result (tools/handle-cider-status {})]
        (is (= "text" (:type result)))
        (is (string? (:text result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Error:"))
        (is (str/includes? (:text result) "CIDER not connected"))))))

(deftest handle-cider-status-disconnected-test
  (testing "Returns proper format for disconnected CIDER"
    (let [status-json "{\"connected\":false,\"repl-type\":null,\"ns\":null}"]
      (with-mock-emacsclient (mock-emacsclient-success status-json)
        (let [result (tools/handle-cider-status nil)]
          (is (= "text" (:type result)))
          (is (nil? (:isError result)))
          (let [parsed (json/read-str (:text result) :key-fn keyword)]
            (is (false? (:connected parsed)))))))))

(deftest handle-cider-status-addon-not-loaded-test
  (testing "Returns error when hive-mcp-cider addon not loaded"
    (with-mock-emacsclient (mock-emacsclient-failure "hive-mcp-cider not loaded")
      (let [result (tools/handle-cider-status {})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "not loaded"))))))

;; =============================================================================
;; handle-cider-eval-silent Tests
;; =============================================================================

(deftest handle-cider-eval-silent-success-test
  (testing "Returns proper MCP response format on successful eval"
    (with-mock-emacsclient (mock-emacsclient-success "42")
      (let [result (tools/handle-cider-eval-silent {:code "(+ 1 41)"})]
        (is (= "text" (:type result)))
        (is (= "42" (:text result)))
        (is (nil? (:isError result)))))))

(deftest handle-cider-eval-silent-complex-result-test
  (testing "Returns proper format for complex evaluation results"
    (let [complex-result "{:foo \"bar\", :count 10}"]
      (with-mock-emacsclient (mock-emacsclient-success complex-result)
        (let [result (tools/handle-cider-eval-silent {:code "(assoc {} :foo \"bar\" :count 10)"})]
          (is (= "text" (:type result)))
          (is (= complex-result (:text result)))
          (is (nil? (:isError result))))))))

(deftest handle-cider-eval-silent-nil-result-test
  (testing "Returns proper format for nil evaluation result"
    (with-mock-emacsclient (mock-emacsclient-success "nil")
      (let [result (tools/handle-cider-eval-silent {:code "(println \"side effect\")"})]
        (is (= "text" (:type result)))
        (is (= "nil" (:text result)))
        (is (nil? (:isError result)))))))

(deftest handle-cider-eval-silent-error-test
  (testing "Returns error format when evaluation fails"
    (with-mock-emacsclient (mock-emacsclient-failure "CompilerException: Unable to resolve symbol")
      (let [result (tools/handle-cider-eval-silent {:code "(undefined-fn)"})]
        (is (= "text" (:type result)))
        (is (string? (:text result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Error:"))))))

(deftest handle-cider-eval-silent-validation-error-test
  (testing "Returns validation error when code is missing"
    (let [result (tools/handle-cider-eval-silent {})]
      (is (= "text" (:type result)))
      (is (true? (:isError result)))
      ;; Should mention missing code parameter
      (is (or (str/includes? (:text result) "code")
              (str/includes? (:text result) "required")
              (str/includes? (:text result) "validation"))))))

(deftest handle-cider-eval-silent-empty-code-test
  (testing "Returns validation error for empty code string"
    (let [result (tools/handle-cider-eval-silent {:code ""})]
      (is (= "text" (:type result)))
      (is (true? (:isError result))))))

(deftest handle-cider-eval-silent-multiline-code-test
  (testing "Handles multiline code evaluation"
    (with-mock-emacsclient (mock-emacsclient-success "6")
      (let [code "(let [x 1\n      y 2\n      z 3]\n  (+ x y z))"
            result (tools/handle-cider-eval-silent {:code code})]
        (is (= "text" (:type result)))
        (is (= "6" (:text result)))
        (is (nil? (:isError result)))))))

;; =============================================================================
;; handle-cider-list-sessions Tests
;; =============================================================================

(deftest handle-cider-list-sessions-success-test
  (testing "Returns proper MCP response format with sessions list"
    (let [sessions-json "[{\"name\":\"main\",\"port\":7888,\"status\":\"connected\"},{\"name\":\"agent-1\",\"port\":7889,\"status\":\"connected\"}]"]
      (with-mock-emacsclient (mock-emacsclient-success sessions-json)
        (let [result (tools/handle-cider-list-sessions {})]
          (is (= "text" (:type result)))
          (is (string? (:text result)))
          (is (nil? (:isError result)))
          ;; Verify sessions content
          (let [parsed (json/read-str (:text result) :key-fn keyword)]
            (is (= 2 (count parsed)))
            (is (= "main" (:name (first parsed))))
            (is (= 7888 (:port (first parsed))))))))))

(deftest handle-cider-list-sessions-empty-test
  (testing "Returns empty array when no sessions exist"
    (with-mock-emacsclient (mock-emacsclient-success "[]")
      (let [result (tools/handle-cider-list-sessions nil)]
        (is (= "text" (:type result)))
        (is (= "[]" (:text result)))
        (is (nil? (:isError result)))))))

(deftest handle-cider-list-sessions-error-test
  (testing "Returns error format when listing fails"
    (with-mock-emacsclient (mock-emacsclient-failure "Session manager not initialized")
      (let [result (tools/handle-cider-list-sessions {})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Error:"))))))

(deftest handle-cider-list-sessions-addon-not-loaded-test
  (testing "Returns error when hive-mcp-cider addon not loaded"
    (with-mock-emacsclient (mock-emacsclient-failure "hive-mcp-cider not loaded")
      (let [result (tools/handle-cider-list-sessions {})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "not loaded"))))))

(deftest handle-cider-list-sessions-with-agent-ids-test
  (testing "Returns sessions with linked agent IDs"
    (let [sessions-json "[{\"name\":\"agent-task-1\",\"port\":7890,\"status\":\"connected\",\"agent_id\":\"swarm-agent-001\"}]"]
      (with-mock-emacsclient (mock-emacsclient-success sessions-json)
        (let [result (tools/handle-cider-list-sessions {})]
          (is (= "text" (:type result)))
          (is (nil? (:isError result)))
          (let [parsed (json/read-str (:text result) :key-fn keyword)]
            (is (= "swarm-agent-001" (:agent_id (first parsed))))))))))

;; =============================================================================
;; Elisp Generation Verification Tests
;; =============================================================================

(deftest elisp-call-verification-test
  (testing "Verifies correct elisp is generated for CIDER calls"
    (let [captured-elisp (atom nil)]
      (with-mock-emacsclient
        (fn [elisp]
          (reset! captured-elisp elisp)
          {:success true :result "{}" :duration-ms 10})

        ;; Test cider-status elisp
        (tools/handle-cider-status {})
        (is (str/includes? @captured-elisp "hive-mcp-cider"))
        (is (str/includes? @captured-elisp "hive-mcp-cider-status"))
        (is (str/includes? @captured-elisp "require"))

        ;; Test cider-list-sessions elisp
        (tools/handle-cider-list-sessions {})
        (is (str/includes? @captured-elisp "hive-mcp-cider-list-sessions"))))))

(deftest elisp-eval-silent-code-passing-test
  (testing "Verifies code is properly passed to elisp for eval-silent"
    (let [captured-elisp (atom nil)]
      (with-mock-emacsclient
        (fn [elisp]
          (reset! captured-elisp elisp)
          {:success true :result "42" :duration-ms 10})

        (tools/handle-cider-eval-silent {:code "(+ 1 2)"})
        (is (str/includes? @captured-elisp "hive-mcp-cider-eval-silent"))
        ;; Code should be passed as string argument
        (is (str/includes? @captured-elisp "(+ 1 2)"))))))

;; =============================================================================
;; Response Format Consistency Tests
;; =============================================================================

(deftest response-format-consistency-test
  (testing "All CIDER handlers return consistent response format"
    (with-mock-emacsclient (mock-emacsclient-success "{}")
      ;; Test each handler returns :type "text"
      (doseq [handler-fn [#(tools/handle-cider-status {})
                          #(tools/handle-cider-eval-silent {:code "(+ 1 1)"})
                          #(tools/handle-cider-list-sessions {})]]
        (let [result (handler-fn)]
          (is (= "text" (:type result))
              "All handlers must return :type \"text\"")
          (is (string? (:text result))
              "All handlers must return :text as string"))))))

(deftest error-format-consistency-test
  (testing "All CIDER handlers return consistent error format"
    (with-mock-emacsclient (mock-emacsclient-failure "Test error")
      ;; Test each handler returns proper error format
      (doseq [[name handler-fn] [["cider-status" #(tools/handle-cider-status {})]
                                 ["cider-list-sessions" #(tools/handle-cider-list-sessions {})]]]
        (let [result (handler-fn)]
          (is (= "text" (:type result))
              (str name " must return :type \"text\" on error"))
          (is (true? (:isError result))
              (str name " must return :isError true on error"))
          (is (str/includes? (:text result) "Error:")
              (str name " must include 'Error:' prefix")))))))

(deftest eval-session-threads-timeout-to-emacsclient
  (testing "handle-cider-eval-session sends the caller's :timeout (sec->ms +2s) to the emacsclient call, not the 5s default"
    (let [captured (atom nil)]
      (with-redefs [ec/eval-elisp-with-timeout (fn [_elisp timeout-ms]
                                                 (reset! captured timeout-ms)
                                                 {:success true :result "2" :duration-ms 1})]
        (tools/handle-cider-eval-session {:session_name "s" :code "(+ 1 1)"})
        (is (= 62000 @captured)
            "no :timeout -> 60s nREPL default + 2s emacsclient buffer (not the 5000ms default)")
        (tools/handle-cider-eval-session {:session_name "s" :code "(+ 1 1)" :timeout 120})
        (is (= 122000 @captured)
            "caller :timeout 120s -> 122000ms emacsclient budget")))))