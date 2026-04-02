(ns hive-mcp.tools.cider-connect-test
  "RED/GREEN tests for CIDER connect-session contracts.

   Bugs discovered 2026-03-20:
   1. connect-session hardcodes cider-connect-clj — ignores repl-type
      (should dispatch clj/cljs/cljel like spawn's -try-connect-session does)
   2. MCP connect command has no repl-type param — can't tell CLJS from CLJ
   3. connect returns no output on failure — silent fail
   4. No argument type contracts — port could be string, nil, etc.

   Kanban: 20260320115940-4489b120"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [malli.core :as m]
            [hive-mcp.schema.cider :as cider-schema]
            [hive-mcp.tools.cider :as tools]
            [hive-mcp.emacs.client :as ec]
            [hive-mcp.emacs.elisp :as el]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn mock-success [result]
  {:success true :result result :duration-ms 10})

(defn mock-failure [error]
  {:success false :error error :duration-ms 10})

(defmacro with-mock-ec [mock-fn & body]
  `(with-redefs [ec/eval-elisp ~mock-fn]
     ~@body))

;; =============================================================================
;; Contract Tests: Argument Types through Elisp Generation
;; =============================================================================

(deftest elisp-quote-port-is-integer-test
  (testing "Port number generates integer literal in elisp, not string"
    (let [elisp (el/require-and-call-json 'hive-mcp-cider
                                           'hive-mcp-cider-connect-session
                                           "test" "localhost" 7902 "clj" nil)]
      ;; Port must appear as bare number, not quoted string
      (is (str/includes? elisp "7902")
          "Port should appear in elisp")
      (is (not (str/includes? elisp "\"7902\""))
          "Port must NOT be quoted as string"))))

(deftest elisp-quote-nil-handling-test
  (testing "nil arguments generate elisp nil, not the string \"nil\""
    (let [elisp (el/require-and-call-json 'hive-mcp-cider
                                           'hive-mcp-cider-connect-session
                                           "test" "localhost" 7902 "clj" nil)]
      ;; nil should be elisp nil, not "nil" string
      (is (str/includes? elisp " nil)")
          "nil should appear as elisp nil"))))

(deftest elisp-quote-string-escaping-test
  (testing "Strings with special chars are properly escaped in elisp"
    (let [elisp (el/require-and-call-json 'hive-mcp-cider
                                           'hive-mcp-cider-connect-session
                                           "my-session" "192.168.1.1" 7902 "clj" nil)]
      (is (str/includes? elisp "\"my-session\"")
          "Session name should be double-quoted")
      (is (str/includes? elisp "\"192.168.1.1\"")
          "Host should be double-quoted"))))

;; =============================================================================
;; Contract Tests: connect-session must accept repl-type
;; =============================================================================

(deftest connect-session-generates-repl-type-aware-elisp-test
  (testing "connect-session should pass repl-type info for CLJS dispatch"
    (let [captured-elisp (atom nil)]
      (with-mock-ec
        (fn [elisp]
          (reset! captured-elisp elisp)
          (mock-success "{\"name\":\"sisf\",\"port\":7902,\"status\":\"connected\"}"))
        ;; NOTE: This test is RED — handle-cider-connect-session doesn't
        ;; currently have a repl-type/cljs-type path. It should.
        ;; When fixed, the generated elisp should reference connect-by-repl-type
        ;; or pass repl-type to the elisp function.
        (let [result (tools/handle-cider-connect-session
                       {:name "sisf" :host "localhost" :port 7902})]
          ;; Basic contract: must return MCP response
          (is (= "text" (:type result))
              "Must return MCP text response")
          (is (string? (:text result))
              "Must return string text"))))))

;; =============================================================================
;; Contract Tests: connect-session error handling
;; =============================================================================

(deftest connect-session-returns-error-on-failure-test
  (testing "connect-session must return error response, not empty/nil"
    (with-mock-ec
      (fn [_elisp] (mock-failure "nREPL connection refused"))
      (let [result (tools/handle-cider-connect-session
                     {:name "test" :host "localhost" :port 9999})]
        (is (some? result)
            "Must return a response, not nil")
        (is (= "text" (:type result))
            "Must return MCP text response")
        (is (true? (:isError result))
            "Must flag as error")
        (is (str/includes? (:text result) "Error")
            "Must include error details")))))

(deftest connect-session-returns-error-on-wrong-repl-type-test
  (testing "Connecting clj to a cljs nREPL should report meaningful error"
    (with-mock-ec
      (fn [_elisp] (mock-failure "Unexpected param type, expected: int, given: java.lang.String"))
      (let [result (tools/handle-cider-connect-session
                     {:name "sisf-web" :host "localhost" :port 7902})]
        (is (true? (:isError result))
            "Must flag as error")
        (is (str/includes? (:text result) "Error")
            "Must surface the error, not swallow it")))))

(deftest connect-session-timeout-returns-error-test
  (testing "connect-session must return error on timeout, not empty response"
    (with-mock-ec
      (fn [_elisp] (throw (ex-info "Timeout" {:type :timeout})))
      (let [result (tools/handle-cider-connect-session
                     {:name "test" :host "localhost" :port 7910})]
        (is (some? result)
            "Must return a response even on timeout")
        (is (true? (:isError result))
            "Must flag as error on timeout")))))

;; =============================================================================
;; Contract Tests: connect-session success
;; =============================================================================

(deftest connect-session-success-returns-session-info-test
  (testing "Successful connect returns session details in MCP response"
    (with-mock-ec
      (fn [_elisp]
        (mock-success "{\"name\":\"hive\",\"port\":7910,\"status\":\"connected\"}"))
      (let [result (tools/handle-cider-connect-session
                     {:name "hive" :host "localhost" :port 7910})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))
        (is (str/includes? (:text result) "connected")
            "Should indicate connection success")))))

(deftest connect-session-duplicate-name-returns-error-test
  (testing "Connecting with duplicate session name returns error"
    (with-mock-ec
      (fn [_elisp] (mock-failure "Session 'hive' already exists"))
      (let [result (tools/handle-cider-connect-session
                     {:name "hive" :host "localhost" :port 7910})]
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "already exists"))))))

;; =============================================================================
;; Contract Tests: elisp-quote type coverage
;; =============================================================================

(deftest elisp-quote-types-contract-test
  (testing "elisp-quote handles all MCP parameter types correctly"
    (let [;; Integer port
          int-elisp (el/require-and-call-json 'f 'fn 42)
          ;; String host
          str-elisp (el/require-and-call-json 'f 'fn "hello")
          ;; nil optional
          nil-elisp (el/require-and-call-json 'f 'fn nil)
          ;; Boolean (if ever used)
          bool-elisp (el/require-and-call-json 'f 'fn true)]
      ;; Integer must NOT be quoted
      (is (re-find #"\bfn 42\)" int-elisp)
          "Integer arg must be unquoted in elisp")
      ;; String must be double-quoted
      (is (str/includes? str-elisp "\"hello\"")
          "String arg must be double-quoted")
      ;; nil must be elisp nil
      (is (re-find #"\bfn nil\)" nil-elisp)
          "nil must be elisp nil literal")
      ;; Boolean true — should it be t or non-nil?
      (is (some? bool-elisp)
          "Boolean should produce valid elisp"))))

;; =============================================================================
;; Integration-like: full chain MCP -> elisp generation -> mock response
;; =============================================================================

(deftest connect-session-full-chain-test
  (testing "Full chain: MCP params -> elisp gen -> emacsclient -> MCP response"
    (let [captured-elisp (atom nil)]
      (with-mock-ec
        (fn [elisp]
          (reset! captured-elisp elisp)
          (mock-success "{\"name\":\"inv-fe\",\"port\":7903,\"status\":\"connected\"}"))
        (let [result (tools/handle-cider-connect-session
                       {:name "inv-fe" :host "localhost" :port 7903 :agent_id "coord-1"})]
          ;; Response contract
          (is (= "text" (:type result)))
          (is (nil? (:isError result)))
          ;; Elisp contract
          (is (str/includes? @captured-elisp "require"))
          (is (str/includes? @captured-elisp "hive-mcp-cider-connect-session"))
          (is (str/includes? @captured-elisp "\"inv-fe\""))
          (is (str/includes? @captured-elisp "\"localhost\""))
          (is (str/includes? @captured-elisp "7903"))
          ;; agent_id should be passed
          (is (str/includes? @captured-elisp "\"coord-1\"")))))))

;; =============================================================================
;; Contract Tests: repl_type flows through to elisp (NEW — previously RED)
;; =============================================================================

(deftest connect-session-passes-repl-type-in-elisp-test
  (testing "connect-session with repl_type=cljs passes it in generated elisp"
    (let [captured-elisp (atom nil)]
      (with-mock-ec
        (fn [elisp]
          (reset! captured-elisp elisp)
          (mock-success "{\"name\":\"sisf\",\"port\":7902,\"repl-type\":\"cljs\",\"status\":\"connected\"}"))
        (let [result (tools/handle-cider-connect-session
                       {:name "sisf" :host "localhost" :port 7902 :repl_type "cljs"})]
          (is (= "text" (:type result)))
          ;; repl_type "cljs" must appear as a quoted string in the elisp
          (is (str/includes? @captured-elisp "\"cljs\"")
              "repl_type must be passed as string arg in elisp"))))))

(deftest connect-session-default-repl-type-is-clj-test
  (testing "connect-session without repl_type defaults to clj in elisp"
    (let [captured-elisp (atom nil)]
      (with-mock-ec
        (fn [elisp]
          (reset! captured-elisp elisp)
          (mock-success "{\"name\":\"hive\",\"port\":7910,\"status\":\"connected\"}"))
        (tools/handle-cider-connect-session
          {:name "hive" :host "localhost" :port 7910})
        ;; Default "clj" should appear in the generated elisp
        (is (str/includes? @captured-elisp "\"clj\"")
            "Default repl_type 'clj' must be passed in elisp")))))

;; =============================================================================
;; Contract Tests: Malli schema validation
;; =============================================================================

(deftest schema-validates-good-connect-params-test
  (testing "Malli schema accepts valid connect params"
    (is (m/validate cider-schema/ConnectSessionParams
                    {:name "sisf-web" :port 7902 :repl_type "cljs"}))
    (is (m/validate cider-schema/ConnectSessionParams
                    {:name "hive" :host "localhost" :port 7910}))
    (is (m/validate cider-schema/ConnectSessionParams
                    {:name "cljel-dev" :port 7888 :repl_type "cljel" :agent_id "a1"}))))

(deftest schema-rejects-invalid-port-range-test
  (testing "Port 0 is rejected"
    (is (not (m/validate cider-schema/ConnectSessionParams
                         {:name "test" :port 0}))))
  (testing "Port 70000 is rejected"
    (is (not (m/validate cider-schema/ConnectSessionParams
                         {:name "test" :port 70000}))))
  (testing "Negative port is rejected"
    (is (not (m/validate cider-schema/ConnectSessionParams
                         {:name "test" :port -1})))))

(deftest schema-rejects-invalid-repl-type-test
  (testing "Invalid repl_type is rejected"
    (is (not (m/validate cider-schema/ConnectSessionParams
                         {:name "test" :port 7902 :repl_type "python"}))))
  (testing "Empty string repl_type is rejected"
    (is (not (m/validate cider-schema/ConnectSessionParams
                         {:name "test" :port 7902 :repl_type ""})))))

(deftest schema-rejects-empty-name-test
  (testing "Empty name is rejected"
    (is (not (m/validate cider-schema/ConnectSessionParams
                         {:name "" :port 7902}))))
  (testing "Missing name is rejected"
    (is (not (m/validate cider-schema/ConnectSessionParams
                         {:port 7902})))))

(deftest handler-rejects-invalid-port-test
  (testing "Handler returns validation error for port 0"
    (let [result (tools/handle-cider-connect-session
                   {:name "test" :port 0})]
      (is (true? (:isError result))
          "Must flag as validation error")
      (is (str/includes? (:text result) "Validation error")
          "Must include validation error message")))
  (testing "Handler returns validation error for port 70000"
    (let [result (tools/handle-cider-connect-session
                   {:name "test" :port 70000})]
      (is (true? (:isError result))))))

(deftest handler-rejects-invalid-repl-type-test
  (testing "Handler returns validation error for bad repl_type"
    (let [result (tools/handle-cider-connect-session
                   {:name "test" :port 7902 :repl_type "python"})]
      (is (true? (:isError result))
          "Must flag as validation error"))))

;; =============================================================================
;; Contract Tests: Malli schema for eval and spawn
;; =============================================================================

(deftest schema-validates-eval-params-test
  (testing "Eval schema accepts valid params"
    (is (m/validate cider-schema/EvalParams
                    {:code "(+ 1 2)"}))
    (is (m/validate cider-schema/EvalParams
                    {:code "(+ 1 2)" :mode "silent" :timeout 60}))
    (is (m/validate cider-schema/EvalParams
                    {:code "(+ 1 2)" :session_name "hive" :mode "explicit"})))
  (testing "Eval schema rejects empty code"
    (is (not (m/validate cider-schema/EvalParams {:code ""}))))
  (testing "Eval schema rejects invalid mode"
    (is (not (m/validate cider-schema/EvalParams {:code "(+ 1 2)" :mode "debug"})))))

(deftest schema-validates-spawn-params-test
  (testing "Spawn schema accepts valid params"
    (is (m/validate cider-schema/SpawnSessionParams
                    {:name "worker-1"}))
    (is (m/validate cider-schema/SpawnSessionParams
                    {:name "fe" :repl_type "cljs" :project_dir "/home/user/app"})))
  (testing "Spawn schema rejects empty name"
    (is (not (m/validate cider-schema/SpawnSessionParams {:name ""}))))
  (testing "Spawn schema rejects invalid repl_type"
    (is (not (m/validate cider-schema/SpawnSessionParams
                         {:name "x" :repl_type "lua"})))))
