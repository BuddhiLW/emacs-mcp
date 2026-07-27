(ns hive-mcp.tools.magit-pinning-test
  "Pinning tests for Magit MCP handlers.

   Tests cover the following handlers:
   - handle-magit-status: Get comprehensive git repository status
   - handle-magit-branches: Get branch information
   - handle-magit-log: Get recent commit log

   All tests use with-redefs to mock emacsclient calls and verify
   proper MCP response format {:type \"text\" :text ...}."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-mcp.tools.magit :as tools]
            [hive-mcp.test.stub.emacs-ext :as se]))

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
  "Execute body with a stub Emacs whose eval-elisp answers with MOCK-FN.

   Magit handlers reach Emacs through hive-mcp.emacs-ext.client, which
   resolves :emacs/eval-elisp from the extension registry. The registry is the
   seam — a with-redefs on any emacs client namespace binds a var the handlers
   no longer call, and every response then carries the registry-miss error."
  [mock-fn & body]
  `(se/with-stub-emacs [_# {:default-response ~mock-fn}]
     ~@body))

;; =============================================================================
;; handle-magit-status Tests
;; =============================================================================

(deftest handle-magit-status-success-test
  (testing "Returns proper MCP response format on success"
    (let [mock-result "{\"branch\":\"main\",\"staged\":[],\"unstaged\":[],\"untracked\":[]}"]
      (with-mock-emacsclient (mock-emacsclient-success mock-result)
        (let [result (tools/handle-magit-status {})]
          (is (= "text" (:type result))
              "Response type should be 'text'")
          (is (string? (:text result))
              "Response text should be a string")
          (is (= mock-result (:text result))
              "Response text should match mock result")
          (is (nil? (:isError result))
              "Success response should not have isError"))))))

(deftest handle-magit-status-error-test
  (testing "Returns proper MCP error response on failure"
    (with-mock-emacsclient (mock-emacsclient-failure "Magit addon not available")
      (let [result (tools/handle-magit-status {})]
        (is (= "text" (:type result))
            "Error response type should be 'text'")
        (is (string? (:text result))
            "Error response text should be a string")
        (is (str/includes? (:text result) "Error:")
            "Error response should contain 'Error:' prefix")
        (is (str/includes? (:text result) "Magit addon not available")
            "Error response should contain the error message")
        (is (true? (:isError result))
            "Error response should have isError true")))))

(deftest handle-magit-status-with-directory-test
  (testing "Accepts directory parameter (even if ignored in current impl)"
    (with-mock-emacsclient (mock-emacsclient-success "{\"branch\":\"develop\"}")
      (let [result (tools/handle-magit-status {:directory "/path/to/repo"})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))))))

;; =============================================================================
;; handle-magit-branches Tests
;; =============================================================================

(deftest handle-magit-branches-success-test
  (testing "Returns proper MCP response format on success"
    (let [mock-result "{\"current\":\"main\",\"upstream\":\"origin/main\",\"local\":[\"main\",\"develop\"],\"remote\":[\"origin/main\",\"origin/develop\"]}"]
      (with-mock-emacsclient (mock-emacsclient-success mock-result)
        (let [result (tools/handle-magit-branches {})]
          (is (= "text" (:type result))
              "Response type should be 'text'")
          (is (string? (:text result))
              "Response text should be a string")
          (is (= mock-result (:text result))
              "Response text should match mock result")
          (is (nil? (:isError result))
              "Success response should not have isError"))))))

(deftest handle-magit-branches-error-test
  (testing "Returns proper MCP error response on failure"
    (with-mock-emacsclient (mock-emacsclient-failure "Not a git repository")
      (let [result (tools/handle-magit-branches {})]
        (is (= "text" (:type result))
            "Error response type should be 'text'")
        (is (string? (:text result))
            "Error response text should be a string")
        (is (str/includes? (:text result) "Error:")
            "Error response should contain 'Error:' prefix")
        (is (str/includes? (:text result) "Not a git repository")
            "Error response should contain the error message")
        (is (true? (:isError result))
            "Error response should have isError true")))))

(deftest handle-magit-branches-with-directory-test
  (testing "Accepts directory parameter"
    (with-mock-emacsclient (mock-emacsclient-success "{\"current\":\"feature/test\"}")
      (let [result (tools/handle-magit-branches {:directory "/custom/path"})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))))))

;; =============================================================================
;; handle-magit-log Tests
;; =============================================================================

(deftest handle-magit-log-success-test
  (testing "Returns proper MCP response format on success"
    (let [mock-result "[{\"hash\":\"abc123\",\"author\":\"dev\",\"date\":\"2024-01-15\",\"subject\":\"Initial commit\"}]"]
      (with-mock-emacsclient (mock-emacsclient-success mock-result)
        (let [result (tools/handle-magit-log {})]
          (is (= "text" (:type result))
              "Response type should be 'text'")
          (is (string? (:text result))
              "Response text should be a string")
          (is (= mock-result (:text result))
              "Response text should match mock result")
          (is (nil? (:isError result))
              "Success response should not have isError"))))))

(deftest handle-magit-log-error-test
  (testing "Returns proper MCP error response on failure"
    (with-mock-emacsclient (mock-emacsclient-failure "Git log failed")
      (let [result (tools/handle-magit-log {})]
        (is (= "text" (:type result))
            "Error response type should be 'text'")
        (is (string? (:text result))
            "Error response text should be a string")
        (is (str/includes? (:text result) "Error:")
            "Error response should contain 'Error:' prefix")
        (is (str/includes? (:text result) "Git log failed")
            "Error response should contain the error message")
        (is (true? (:isError result))
            "Error response should have isError true")))))

(deftest handle-magit-log-with-count-test
  (testing "Accepts count parameter"
    (se/with-stub-emacs [emacs {:default-response {:success true :result "[]" :duration-ms 10}}]
      (let [result (tools/handle-magit-log {:count 5})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))
        ;; Verify count is passed in elisp
        (is (str/includes? (first (se/evaluated emacs)) "5")
            "Elisp should contain the count parameter")))))

(deftest handle-magit-log-default-count-test
  (testing "Uses default count of 10 when not specified"
    (se/with-stub-emacs [emacs {:default-response {:success true :result "[]" :duration-ms 10}}]
      (let [result (tools/handle-magit-log {})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))
        ;; Verify default count of 10 is used
        (is (str/includes? (first (se/evaluated emacs)) "10")
            "Elisp should contain default count of 10")))))

(deftest handle-magit-log-with-directory-test
  (testing "Accepts directory parameter"
    (with-mock-emacsclient (mock-emacsclient-success "[]")
      (let [result (tools/handle-magit-log {:count 3 :directory "/some/repo"})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))))))

;; =============================================================================
;; Response Format Consistency Tests
;; =============================================================================

(deftest all-handlers-return-consistent-format-test
  (testing "All magit handlers return consistent MCP response format"
    (with-mock-emacsclient (mock-emacsclient-success "{}")
      ;; Test status
      (let [status-result (tools/handle-magit-status {})]
        (is (contains? status-result :type))
        (is (contains? status-result :text))
        (is (= "text" (:type status-result))))

      ;; Test branches
      (let [branches-result (tools/handle-magit-branches {})]
        (is (contains? branches-result :type))
        (is (contains? branches-result :text))
        (is (= "text" (:type branches-result))))

      ;; Test log
      (let [log-result (tools/handle-magit-log {})]
        (is (contains? log-result :type))
        (is (contains? log-result :text))
        (is (= "text" (:type log-result)))))))

(deftest all-handlers-error-format-consistent-test
  (testing "All magit handlers return consistent error format"
    (with-mock-emacsclient (mock-emacsclient-failure "Test error")
      ;; Test status error
      (let [status-result (tools/handle-magit-status {})]
        (is (= "text" (:type status-result)))
        (is (true? (:isError status-result)))
        (is (str/starts-with? (:text status-result) "Error:")))

      ;; Test branches error
      (let [branches-result (tools/handle-magit-branches {})]
        (is (= "text" (:type branches-result)))
        (is (true? (:isError branches-result)))
        (is (str/starts-with? (:text branches-result) "Error:")))

      ;; Test log error
      (let [log-result (tools/handle-magit-log {})]
        (is (= "text" (:type log-result)))
        (is (true? (:isError log-result)))
        (is (str/starts-with? (:text log-result) "Error:"))))))

;; =============================================================================
;; Elisp Generation Verification Tests
;; =============================================================================

(deftest elisp-calls-correct-functions-test
  (testing "handle-magit-status calls correct elisp function"
    (se/with-stub-emacs [emacs {:default-response {:success true :result "{}" :duration-ms 10}}]
      (tools/handle-magit-status {})
      (let [elisp (first (se/evaluated emacs))]
        (is (str/includes? elisp "hive-mcp-magit")
            "Should require hive-mcp-magit")
        (is (str/includes? elisp "hive-mcp-magit-api-status")
            "Should call hive-mcp-magit-api-status"))))

  (testing "handle-magit-branches calls correct elisp function"
    (se/with-stub-emacs [emacs {:default-response {:success true :result "{}" :duration-ms 10}}]
      (tools/handle-magit-branches {})
      (let [elisp (first (se/evaluated emacs))]
        (is (str/includes? elisp "hive-mcp-magit")
            "Should require hive-mcp-magit")
        (is (str/includes? elisp "hive-mcp-magit-api-branches")
            "Should call hive-mcp-magit-api-branches")))))
