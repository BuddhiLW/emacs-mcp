(ns hive-mcp.agent.ling.strategy-test
  "Tests for the Ling Strategy Protocol decomposition.

   Tests cover:
   - ILingStrategy protocol contract
   - Protocol contract tests via mock strategy (reify)
   - Terminal registry integration (addon-contributed backends)
   - Headless registry integration (addon-contributed headless backends)
   - Strategy resolution (resolve-strategy via registries)
   - Ling facade delegation to strategies

   Note: Vterm-specific tests live in vterm-mcp addon project.
   Headless strategy tests live in headless_addon_strategy_test.clj.
   All external dependencies are mocked."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.ling.strategy :as strategy]
            [hive-mcp.agent.ling.terminal-registry :as terminal-reg]
            [hive-mcp.agent.ling.headless-registry :as headless-reg]
            [hive-mcp.addons.terminal]
            [hive-mcp.addons.headless :as headless-proto]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [clojure.string :as str]
            [hive-test.isolation :as iso]
            hive-mcp.isolation-methods))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each
  (iso/with-isolations :swarm-ds :terminal-registry :headless-registry))

;; =============================================================================
;; Mock Strategy Helper
;; =============================================================================

(defn- make-mock-strategy
  "Create a mock ILingStrategy via reify for protocol contract tests.
   All parameters are optional with sensible defaults."
  [& {:keys [spawn-fn dispatch-fn status-fn kill-fn interrupt-fn]
      :or {spawn-fn (fn [ctx _opts] (:id ctx))
           dispatch-fn (fn [_ctx _opts] true)
           status-fn (fn [_ctx ds-data] (or ds-data {:slave/status :unknown}))
           kill-fn (fn [ctx] {:killed? true :id (:id ctx)})
           interrupt-fn (fn [ctx] {:success? false :ling-id (:id ctx) :errors ["Not supported"]})}}]
  (reify strategy/ILingStrategy
    (strategy-spawn! [_ ctx opts] (spawn-fn ctx opts))
    (strategy-dispatch! [_ ctx opts] (dispatch-fn ctx opts))
    (strategy-status [_ ctx ds] (status-fn ctx ds))
    (strategy-kill! [_ ctx] (kill-fn ctx))
    (strategy-interrupt! [_ ctx] (interrupt-fn ctx))))

;; =============================================================================
;; Section 1: ILingStrategy Protocol Tests
;; =============================================================================

(deftest strategy-protocol-exists
  (testing "ILingStrategy protocol is defined with correct methods"
    (is (some? strategy/ILingStrategy)
        "ILingStrategy protocol should exist")
    ;; Verify the protocol has the expected method signatures
    (is (ifn? strategy/strategy-spawn!)
        "strategy-spawn! should be a function")
    (is (ifn? strategy/strategy-dispatch!)
        "strategy-dispatch! should be a function")
    (is (ifn? strategy/strategy-status)
        "strategy-status should be a function")
    (is (ifn? strategy/strategy-kill!)
        "strategy-kill! should be a function")))

;; =============================================================================
;; Section 2: Protocol Contract Tests (via mock strategy reify)
;; =============================================================================

(deftest protocol-spawn-returns-id
  (testing "ILingStrategy spawn returns slave-id string"
    (let [strat (make-mock-strategy
                 :spawn-fn (fn [ctx _opts] (:id ctx)))
          ctx {:id "spawn-test-001" :cwd "/tmp/project" :presets ["tdd"]}]
      (let [slave-id (strategy/strategy-spawn! strat ctx {})]
        (is (= "spawn-test-001" slave-id)
            "Should return the ling id from context")))))

(deftest protocol-spawn-failure-throws
  (testing "ILingStrategy spawn can throw on failure"
    (let [strat (make-mock-strategy
                 :spawn-fn (fn [ctx _opts]
                             (throw (ex-info "Failed to spawn ling"
                                             {:id (:id ctx) :error "Backend down"}))))
          ctx {:id "fail-ling" :cwd "/tmp" :presets []}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Failed to spawn ling"
                            (strategy/strategy-spawn! strat ctx {}))))))

(deftest protocol-dispatch-returns-true
  (testing "ILingStrategy dispatch returns true on success"
    (let [strat (make-mock-strategy
                 :dispatch-fn (fn [_ctx task-opts]
                                (is (= "Fix the bug" (:task task-opts)))
                                true))
          ctx {:id "dispatch-test" :cwd "/tmp"}]
      (let [result (strategy/strategy-dispatch! strat ctx {:task "Fix the bug"
                                                           :timeout-ms 30000})]
        (is (true? result))))))

(deftest protocol-dispatch-failure-throws
  (testing "ILingStrategy dispatch can throw on failure"
    (let [strat (make-mock-strategy
                 :dispatch-fn (fn [_ctx _opts]
                                (throw (ex-info "Dispatch failed"
                                                {:error "Timeout"}))))
          ctx {:id "fail-dispatch" :cwd "/tmp"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Dispatch failed"
                            (strategy/strategy-dispatch! strat ctx {:task "Fix it"}))))))

(deftest protocol-status-with-ds-data
  (testing "ILingStrategy status returns DataScript data when available"
    (let [strat (make-mock-strategy
                 :status-fn (fn [_ctx ds-data] ds-data))
          ctx {:id "status-test"}
          ds-data {:slave/id "status-test" :slave/status :working}]
      (let [result (strategy/strategy-status strat ctx ds-data)]
        (is (= :working (:slave/status result))
            "Should return DataScript status directly")))))

(deftest protocol-status-enriches-unknown
  (testing "ILingStrategy status enriches unknown status"
    (let [strat (make-mock-strategy
                 :status-fn (fn [_ctx ds-data]
                              (assoc ds-data :backend-alive? true)))
          ctx {:id "unknown-test"}
          ds-data {:slave/id "unknown-test" :slave/status :unknown}]
      (let [result (strategy/strategy-status strat ctx ds-data)]
        (is (true? (:backend-alive? result))
            "Should indicate backend alive")))))

(deftest protocol-kill-returns-killed
  (testing "ILingStrategy kill returns killed map"
    (let [strat (make-mock-strategy
                 :kill-fn (fn [ctx] {:killed? true :id (:id ctx)}))
          ctx {:id "kill-test"}]
      (let [result (strategy/strategy-kill! strat ctx)]
        (is (true? (:killed? result)))
        (is (= "kill-test" (:id result)))))))

(deftest protocol-kill-returns-not-killed
  (testing "ILingStrategy kill returns killed?=false on failure"
    (let [strat (make-mock-strategy
                 :kill-fn (fn [ctx] {:killed? false :id (:id ctx) :reason :backend-failed}))
          ctx {:id "kill-fail-test"}]
      (let [result (strategy/strategy-kill! strat ctx)]
        (is (false? (:killed? result)))
        (is (= :backend-failed (:reason result)))))))

;; =============================================================================
;; Section 3: Headless Registry Strategy Tests
;; =============================================================================

(defn- make-mock-headless-backend
  "Create a mock IHeadlessBackend for registry tests."
  [id]
  (reify
    headless-proto/IHeadlessBackend
    (headless-id [_] id)
    (headless-spawn! [_ ctx _opts] (:id ctx))
    (headless-dispatch! [_ _ctx _opts] true)
    (headless-status [_ ctx ds-data] (merge ds-data {:alive? true :slave/id (:id ctx)}))
    (headless-kill! [_ ctx] {:killed? true :id (:id ctx)})
    (headless-interrupt! [_ _ctx] {:success? false})

    headless-proto/IHeadlessCapabilities
    (declared-capabilities [_] #{:cap/streaming :cap/multi-turn})))

(deftest headless-registry-resolve-returns-strategy
  (testing "Headless registry resolves to ILingStrategy"
    (let [backend (make-mock-headless-backend :test-headless)]
      (headless-reg/register-headless! :test-headless backend)
      (let [strat (headless-reg/resolve-headless-strategy :test-headless)]
        (is (some? strat) "Should resolve to a strategy")
        (is (satisfies? strategy/ILingStrategy strat) "Should satisfy ILingStrategy")))))

(deftest headless-registry-spawn-delegates
  (testing "Headless registry strategy delegates spawn to backend"
    (let [backend (make-mock-headless-backend :test-spawn)]
      (headless-reg/register-headless! :test-spawn backend)
      (let [strat (headless-reg/resolve-headless-strategy :test-spawn)
            ctx {:id "spawn-via-registry" :cwd "/tmp"}]
        (is (= "spawn-via-registry" (strategy/strategy-spawn! strat ctx {})))))))

;; =============================================================================
;; Section 4: Facade Integration Tests
;; =============================================================================

(deftest facade-spawn-uses-terminal-strategy-by-default
  (testing "Ling facade defaults to :claude terminal strategy (via terminal registry)"
    (let [;; Register a mock terminal strategy as :claude (the default mode)
          mock-terminal (reify
                          hive-mcp.addons.terminal/ITerminalAddon
                          (terminal-id [_] :claude)
                          (terminal-spawn! [_ ctx _opts]
                            (:id ctx))
                          (terminal-dispatch! [_ _ctx _opts] true)
                          (terminal-status [_ _ctx ds-data] ds-data)
                          (terminal-kill! [_ ctx] {:killed? true :id (:id ctx)})
                          (terminal-interrupt! [_ ctx] {:success? false :ling-id (:id ctx) :errors ["Not supported"]}))
          ling (ling/->ling "facade-terminal" {:cwd "/tmp" :project-id "test"})]
      (terminal-reg/register-terminal! :claude mock-terminal)
      (try
        (is (= :claude (:spawn-mode ling))
            "Default spawn mode should be :claude")
        (let [slave-id (proto/spawn! ling {:depth 1})]
          (is (= "facade-terminal" slave-id))
          ;; Verify DataScript registration (common concern)
          (let [slave (ds-queries/get-slave "facade-terminal")]
            (is (some? slave))
            (is (= :claude (:ling/spawn-mode slave)))))
        (finally
          (terminal-reg/deregister-terminal! :claude))))))

(deftest facade-spawn-uses-headless-registry
  (testing "Ling facade maps :headless to best registered headless backend"
    (let [backend (make-mock-headless-backend :claude-sdk)]
      (headless-reg/register-headless! :claude-sdk backend)
      (let [ling (ling/->ling "facade-headless"
                              {:cwd "/tmp" :project-id "test"
                               :spawn-mode :headless})]
        ;; ->ling maps :headless -> :claude-sdk via best-headless-for-provider
        (is (= :claude-sdk (:spawn-mode ling)))
        (let [slave-id (proto/spawn! ling {:depth 1})]
          (is (= "facade-headless" slave-id))
          (let [slave (ds-queries/get-slave "facade-headless")]
            (is (some? slave))
            (is (= :claude-sdk (:ling/spawn-mode slave)))))))))

(deftest facade-non-claude-model-does-not-mutate-spawn-mode
  (testing "Non-claude models no longer auto-mutate spawn-mode (seam cleanup)"
    (let [ling (ling/->ling "deepseek-ling"
                            {:cwd "/tmp" :project-id "test"
                             :model "deepseek/deepseek-v3.2"})]
      (is (= :claude (:spawn-mode ling))
          "Without explicit :spawn-mode, defaults to :claude; the model name no longer leaks into spawn-mode resolution.")
      (is (not= :openrouter (:spawn-mode ling))
          "The legacy non-claude→:openrouter auto-mapping is removed (provider is infrastructure, not a spawn-mode).")
      (is (= "deepseek/deepseek-v3.2" (:model ling))
          "Model is preserved as orthogonal config consumed by the LLM router."))))

(deftest facade-dispatch-delegates-to-strategy
  (testing "Dispatch delegates to correct strategy based on registered headless backend"
    (let [backend (make-mock-headless-backend :claude-sdk)]
      (headless-reg/register-headless! :claude-sdk backend)
      (ds-lings/add-slave! "dispatch-delegate" {:status :idle :cwd "/tmp"})
      (ds-lings/update-slave! "dispatch-delegate" {:ling/spawn-mode :claude-sdk})

      (let [ling (ling/->ling "dispatch-delegate" {:cwd "/tmp" :spawn-mode :headless})]
        (let [task-id (proto/dispatch! ling {:task "Analyze code"})]
          (is (string? task-id))
          (let [slave (ds-queries/get-slave "dispatch-delegate")]
            (is (= :working (:slave/status slave)))))))))

(deftest facade-kill-delegates-to-strategy
  (testing "Kill delegates to correct strategy and cleans up DataScript"
    (let [backend (make-mock-headless-backend :claude-sdk)]
      (headless-reg/register-headless! :claude-sdk backend)
      (ds-lings/add-slave! "kill-delegate" {:status :idle})

      (let [ling (ling/->ling "kill-delegate" {:spawn-mode :headless})]
        (let [result (proto/kill! ling)]
          (is (true? (:killed? result)))
          (is (nil? (ds-queries/get-slave "kill-delegate"))
              "Should be removed from DataScript after kill"))))))

(deftest facade-status-delegates-to-strategy
  (testing "Status delegates to correct headless strategy via registry"
    (let [backend (make-mock-headless-backend :claude-sdk)]
      (headless-reg/register-headless! :claude-sdk backend)
      (ds-lings/add-slave! "status-delegate" {:status :idle})
      (ds-lings/update-slave! "status-delegate" {:ling/spawn-mode :claude-sdk})

      (let [ling (ling/->ling "status-delegate" {:spawn-mode :headless})]
        (let [status (proto/status ling)]
          (is (some? status) "Status should be returned"))))))

(deftest facade-unregistered-terminal-throws
  (testing "Attempting :claude with no addon registered throws clear error"
    (let [ling (ling/->ling "no-terminal-ling" {:cwd "/tmp" :project-id "test"})]
      (is (= :claude (:spawn-mode ling)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No strategy registered for mode"
                            (proto/spawn! ling {:depth 1}))))))

;; =============================================================================
;; Section 5: Query Functions (mode-independent, unaffected by refactoring)
;; =============================================================================

(deftest query-fns-preserve-spawn-mode
  (testing "get-ling maps :headless to best registered headless for claude models"
    (let [backend (make-mock-headless-backend :claude-sdk)]
      (headless-reg/register-headless! :claude-sdk backend)
      (ds-lings/add-slave! "mode-preserved" {:status :idle :depth 1})
      (ds-lings/update-slave! "mode-preserved" {:ling/spawn-mode :headless
                                                :ling/model "claude"})

      (let [ling (ling/get-ling "mode-preserved")]
        (is (some? ling))
        ;; ->ling factory maps :headless -> :claude-sdk via best-headless-for-provider
        (is (= :claude-sdk (:spawn-mode ling))
            "spawn-mode should be mapped from :headless to :claude-sdk")
        (is (= "claude" (:model ling))
            "model should be preserved from DataScript"))))

  (testing "get-ling does NOT override spawn-mode for non-claude models (seam cleanup)"
    (ds-lings/add-slave! "mode-no-override" {:status :idle :depth 1})
    (ds-lings/update-slave! "mode-no-override" {:ling/spawn-mode :headless
                                                :ling/model "deepseek/deepseek-v3.2"})

    (let [ling (ling/get-ling "mode-no-override")]
      (is (some? ling))
      (is (not= :openrouter (:spawn-mode ling))
          "spawn-mode no longer leaks from model — provider routing is the LLM router's job")
      (is (= "deepseek/deepseek-v3.2" (:model ling))
          "model should be preserved from DataScript"))))

(comment
  ;; Run all strategy tests
  (clojure.test/run-tests 'hive-mcp.agent.ling.strategy-test))
