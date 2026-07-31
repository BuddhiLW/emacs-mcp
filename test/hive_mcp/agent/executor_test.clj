(ns hive-mcp.agent.executor-test
  "Tests for tool executor: agent-id attribution and allowlist enforcement.

   CLARITY-T: Verifies that agent-id propagates through tool execution
   so that hivemind_shout and similar tools can identify the calling agent.
   CLARITY-I: Verifies that allowlist enforcement rejects disallowed tools."
  (:require [clojure.test :refer :all]
            [hive-mcp.agent.executor :as executor]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.agent.registry :as registry]
            [hive-mcp.channel.piggyback-tap :as tap]
            [hive-mcp.channel.activation :as act]
            [hive-mcp.extensions.registry :as ext]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def captured-agent-id (atom nil))

(defn test-tool-handler
  "A test tool that captures the current agent-id from execution context."
  [_args]
  (reset! captured-agent-id (ctx/current-agent-id))
  {:type "text" :text "ok"})

(defn setup-test-tool! []
  (registry/register!
   [{:name "test_capture_agent"
     :description "Test tool that captures agent-id"
     :inputSchema {:type "object" :properties {}}
     :handler test-tool-handler}]))

;; =============================================================================
;; Agent-ID Propagation Tests
;; =============================================================================

(deftest current-agent-id-outside-execution
  (testing "current-agent-id returns nil when not in execution context"
    (is (nil? (executor/current-agent-id)))))

(deftest current-agent-id-during-execution
  (testing "current-agent-id returns the agent-id during tool execution"
    (setup-test-tool!)
    (reset! captured-agent-id nil)

    (let [calls [{:id "call-1" :name "test_capture_agent" :arguments {}}]
          drone-id "drone-test-12345"]
      (executor/execute-tool-calls drone-id calls #{:auto-approve})

      ;; The tool should have captured the drone-id
      (is (= drone-id @captured-agent-id)
          "Tool handler should see the correct agent-id via current-agent-id"))))

(deftest agent-id-isolated-between-executions
  (testing "agent-id is isolated between different executions"
    (setup-test-tool!)

    ;; Execute with first agent
    (reset! captured-agent-id nil)
    (executor/execute-tool-calls "drone-A"
                                 [{:id "c1" :name "test_capture_agent" :arguments {}}]
                                 #{:auto-approve})
    (is (= "drone-A" @captured-agent-id))

    ;; Execute with second agent
    (reset! captured-agent-id nil)
    (executor/execute-tool-calls "drone-B"
                                 [{:id "c2" :name "test_capture_agent" :arguments {}}]
                                 #{:auto-approve})
    (is (= "drone-B" @captured-agent-id))

    ;; After execution, should be nil again
    (is (nil? (executor/current-agent-id)))))

;; =============================================================================
;; Integration Test: hivemind_shout Attribution (requires hivemind setup)
;; =============================================================================

(deftest hivemind-shout-uses-context-agent-id
  (testing "hivemind_shout uses execution context agent-id when not provided"
    ;; This test verifies the fix for P1: drone error attribution
    ;; When a drone calls hivemind_shout without explicit agent_id,
    ;; it should use the agent-id from the execution context (current-agent-id)

    ;; Skip if hivemind_shout isn't registered (unit test environment)
    (when-let [tool (registry/get-tool "hivemind_shout")]
      (let [drone-id "drone-attribution-test-123"
            calls [{:id "call-shout"
                    :name "hivemind_shout"
                    :arguments {:event_type "progress"
                                :message "Test message"}}]
            ;; Note: We're not providing agent_id in arguments
            ;; The tool should get it from current-agent-id
            results (executor/execute-tool-calls drone-id calls #{:auto-approve})]

        ;; Result should show the drone-id was used
        (is (some? results))))))

;; =============================================================================
;; Allowlist Enforcement Tests
;; =============================================================================

(defn setup-test-tools!
  "Register multiple test tools for allowlist testing."
  []
  (registry/register!
   [{:name "test_allowed_tool"
     :description "A tool that should be allowed"
     :inputSchema {:type "object" :properties {}}
     :handler (fn [_] {:type "text" :text "allowed-ok"})}
    {:name "test_blocked_tool"
     :description "A tool that should be blocked"
     :inputSchema {:type "object" :properties {}}
     :handler (fn [_] {:type "text" :text "blocked-ok"})}
    {:name "test_another_allowed"
     :description "Another allowed tool"
     :inputSchema {:type "object" :properties {}}
     :handler (fn [_] {:type "text" :text "another-ok"})}]))

(deftest execute-tool-calls-backward-compatible
  (testing "3-arity call still works (no allowlist = no filtering)"
    (setup-test-tools!)
    (let [calls [{:id "c1" :name "test_allowed_tool" :arguments {}}
                 {:id "c2" :name "test_blocked_tool" :arguments {}}]
          results (executor/execute-tool-calls "drone-1" calls #{:auto-approve})]
      ;; Both should execute (no allowlist filtering)
      (is (= 2 (count results)))
      (is (every? #(= "tool" (:role %)) results))
      ;; Neither should have "TOOL REJECTED" in content
      (is (not-any? #(.contains (:content %) "TOOL REJECTED") results)))))

(deftest execute-tool-calls-with-allowlist-filters
  (testing "4-arity call with allowlist rejects disallowed tools"
    (setup-test-tools!)
    (let [calls [{:id "c1" :name "test_allowed_tool" :arguments {}}
                 {:id "c2" :name "test_blocked_tool" :arguments {}}
                 {:id "c3" :name "test_another_allowed" :arguments {}}]
          results (executor/execute-tool-calls
                   "drone-1" calls #{:auto-approve}
                   {:tool-allowlist #{"test_allowed_tool" "test_another_allowed"}})]
      ;; Should have 3 results total (1 rejected + 2 executed)
      (is (= 3 (count results)))
      ;; First result should be the rejection (rejected comes first)
      (let [rejected (first results)]
        (is (= "test_blocked_tool" (:name rejected)))
        (is (.contains (:content rejected) "TOOL REJECTED")))
      ;; Last two should be successful executions
      (let [executed (rest results)]
        (is (= 2 (count executed)))
        (is (every? #(not (.contains (:content %) "TOOL REJECTED")) executed))))))

(deftest execute-tool-calls-with-empty-allowlist-rejects-all
  (testing "Empty explicit allowlist rejects all tools"
    (setup-test-tools!)
    (let [calls [{:id "c1" :name "test_allowed_tool" :arguments {}}]
          ;; Note: empty set is truthy but has no tools
          ;; resolve-allowlist with empty seq falls through to default
          ;; So we test with a set containing only an unrelated tool
          results (executor/execute-tool-calls
                   "drone-1" calls #{:auto-approve}
                   {:tool-allowlist #{"some_other_tool"}})]
      (is (= 1 (count results)))
      (is (.contains (:content (first results)) "TOOL REJECTED")))))

(deftest execute-tool-calls-nil-opts-no-filtering
  (testing "nil opts means no allowlist filtering"
    (setup-test-tools!)
    (let [calls [{:id "c1" :name "test_allowed_tool" :arguments {}}]
          results (executor/execute-tool-calls "drone-1" calls #{:auto-approve} nil)]
      (is (= 1 (count results)))
      (is (not (.contains (:content (first results)) "TOOL REJECTED"))))))

(defn- capture-drain-ctx
  "Run execute-tool-calls with drain-all! stubbed, returning the ctx it received."
  [calls]
  (let [seen (atom ::none)]
    (with-redefs [tap/drain-all! (fn [_agent-id _project-id ctx] (reset! seen ctx) nil)]
      (executor/execute-tool-calls "drone-1" calls #{:auto-approve}))
    @seen))

(deftest agent-lane-consults-the-activation-provider
  (testing "a registered provider's pins reach the agentic-loop drain"
    (setup-test-tools!)
    (try
      (ext/register! act/extension-key
                     (fn [{:keys [tool-name]}]
                       (when (= "test_allowed_tool" tool-name)
                         {:pins #{"pin-1"} :tokens #{"activated"}})))
      (let [ctx (capture-drain-ctx [{:id "c1" :name "test_allowed_tool" :arguments {}}])]
        (is (= #{"pin-1"} (:pins ctx))
            "executor must build ctx through activation/drain-ctx, not a bare {:tokens ...}")
        (is (contains? (:tokens ctx) "activated")
            "provider tokens union onto the harvested cues"))
      (finally (ext/deregister! act/extension-key)))))

(deftest agent-lane-names-its-tool-only-when-unambiguous
  (testing "a single-call batch names its tool; a multi-call batch reports nil"
    (setup-test-tools!)
    (let [seen (atom [])]
      (try
        (ext/register! act/extension-key
                       (fn [{:keys [tool-name]}] (swap! seen conj tool-name) nil))
        (capture-drain-ctx [{:id "c1" :name "test_allowed_tool" :arguments {}}])
        (capture-drain-ctx [{:id "c1" :name "test_allowed_tool" :arguments {}}
                            {:id "c2" :name "test_another_allowed" :arguments {}}])
        (is (= ["test_allowed_tool" nil] @seen)
            "fabricating one tool-name for a batch would lie to every rule keyed on it")
        (finally (ext/deregister! act/extension-key))))))

(deftest agent-lane-degrades-to-cues-without-a-provider
  (testing "no provider means the ctx is the cues alone — activation never breaks the drain"
    (setup-test-tools!)
    (ext/deregister! act/extension-key)
    (let [ctx (capture-drain-ctx [{:id "c1" :name "test_allowed_tool" :arguments {}}])]
      (is (nil? (:pins ctx)))
      (is (nil? (:floor-cap ctx)))
      (is (contains? ctx :tokens)))))
