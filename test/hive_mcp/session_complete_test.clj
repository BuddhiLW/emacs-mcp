(ns hive-mcp.session-complete-test
  "Unit tests for session_complete MCP tool.

   Tests the session completion workflow that:
   1. Commits git changes
   2. Moves kanban tasks to done
   3. Runs wrap/crystallize
   4. Shouts completion to hivemind
   5. Auto-triggers plan_to_kanban for explorer presets

   TDD: Tests written first, implementation follows.

   CLARITY Framework:
   - C: Composition - builds on existing git, kanban, crystal infrastructure
   - L: Layers pure - handler orchestrates, effects execute
   - I: Inputs guarded - validates commit_msg required
   - T: Telemetry first - shouts completion for coordinator"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-mcp.tools.session-complete :as sc]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.effects :as effects]
            [hive-mcp.events.handlers :as handlers]
            [hive-mcp.vectordb.facade :as memory]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.test.stub.memory-store :as mem-stub]))

;; =============================================================================
;; Test Fixtures and Helpers
;; =============================================================================

(def ^:dynamic *test-collection* "hive-mcp-test-session-complete")

(def ^:dynamic *effects-log* (atom []))

(defn with-mock-effects
  "Fixture that captures effect calls for verification."
  [f]
  (reset! *effects-log* [])
  ;; Reset event system for clean state
  (ev/reset-all!)
  (handlers/reset-registration!)
  (effects/reset-registration!)
  (sc/reset-registration!)
  ;; Register handlers and effects
  (effects/register-effects!)
  (handlers/register-handlers!)
  ;; Register session-complete handler
  (sc/register-handler!)
  ;; Override effects to capture calls
  (ev/reg-fx :git-commit
             (fn [data]
               (swap! *effects-log* conj [:git-commit data])))
  (ev/reg-fx :kanban-move-done
             (fn [data]
               (swap! *effects-log* conj [:kanban-move-done data])))
  (ev/reg-fx :wrap-crystallize
             (fn [data]
               (swap! *effects-log* conj [:wrap-crystallize data])))
  (ev/reg-fx :shout
             (fn [data]
               (swap! *effects-log* conj [:shout data])))
  (try
    (f)
    (finally
      (reset! *effects-log* []))))

(defn combined-fixture [f]
  (mem-stub/with-stub-store
    (fn []
      (with-mock-effects f))))

(use-fixtures :each combined-fixture)

(defn parse-mcp-response
  "Parse MCP response - extracts :text and parses as JSON."
  [response]
  (-> response :text (json/read-str :key-fn keyword)))

(defn find-effect
  "Find an effect in the log by type."
  [effect-type]
  (first (filter #(= (first %) effect-type) @*effects-log*)))

;; =============================================================================
;; Handler Tests
;; =============================================================================

(deftest handle-session-complete-basic
  (testing "Basic session complete triggers all effects"
    (let [result (sc/handle-session-complete
                  {:commit_msg "feat: implement session complete"
                   :agent_id "ling-test-123"})
          parsed (parse-mcp-response result)]
      ;; Should succeed
      (is (not (:error parsed)) "Should not return error")
      (is (= "ok" (:status parsed)) "Should return ok status")

      ;; Wait for async effects
      (Thread/sleep 100)

      ;; Verify git-commit effect was triggered
      (let [[_ git-data] (find-effect :git-commit)]
        (is (some? git-data) "Should trigger git-commit effect")
        (is (= "feat: implement session complete" (:message git-data))
            "Should pass commit message"))

      ;; Verify shout effect was triggered
      (let [[_ shout-data] (find-effect :shout)]
        (is (some? shout-data) "Should trigger shout effect")
        (is (= :completed (:event-type shout-data))
            "Should shout completed event")))))

(deftest handle-session-complete-with-task-ids
  (testing "Session complete moves kanban tasks to done"
    (let [result (sc/handle-session-complete
                  {:commit_msg "feat: complete tasks"
                   :task_ids ["task-1" "task-2"]
                   :agent_id "ling-test-456"})
          parsed (parse-mcp-response result)]
      (is (not (:error parsed)) "Should succeed")

      ;; Wait for async effects
      (Thread/sleep 100)

      ;; Verify kanban-move-done effect
      (let [[_ kanban-data] (find-effect :kanban-move-done)]
        (is (some? kanban-data) "Should trigger kanban-move-done effect")
        (is (= ["task-1" "task-2"] (:task-ids kanban-data))
            "Should pass task IDs")))))

(deftest handle-session-complete-validation
  ;; Validation failures come back as MCP errors (plain :text + :isError true),
  ;; not as a JSON body — parse-mcp-response would choke on them.
  (testing "Missing commit_msg returns error"
    (let [result (sc/handle-session-complete {:task_ids ["task-1"]})]
      (is (true? (:isError result)) "Should return an MCP error")
      (is (str/includes? (:text result) "commit_msg")
          "Error should mention commit_msg")))

  (testing "Empty commit_msg returns error"
    (let [result (sc/handle-session-complete {:commit_msg ""})]
      (is (true? (:isError result)) "Should return an MCP error for empty message")
      (is (str/includes? (:text result) "commit_msg")))))

(deftest handle-session-complete-wrap-crystallize
  (testing "Session complete triggers wrap crystallize"
    (let [result (sc/handle-session-complete
                  {:commit_msg "feat: session end"
                   :agent_id "ling-test-789"})
          _parsed (parse-mcp-response result)]

      ;; Wait for async effects
      (Thread/sleep 100)

      ;; Verify wrap-crystallize effect
      (let [[_ wrap-data] (find-effect :wrap-crystallize)]
        (is (some? wrap-data) "Should trigger wrap-crystallize effect")
        (is (= "ling-test-789" (:agent-id wrap-data))
            "Should pass agent ID to wrap")))))

(deftest handle-session-complete-agent-id-detection
  (testing "Agent ID defaults to env var when not provided"
    ;; This test verifies the fallback behavior
    (let [result (sc/handle-session-complete
                  {:commit_msg "feat: test agent detection"})
          parsed (parse-mcp-response result)]
      (is (not (:error parsed)) "Should succeed without explicit agent_id"))))

(deftest handle-session-complete-directory-scoping
  (testing "Directory is passed for project scoping"
    (let [result (sc/handle-session-complete
                  {:commit_msg "feat: scoped commit"
                   :directory "/project/path"
                   :agent_id "ling-scoped"})
          _parsed (parse-mcp-response result)]

      ;; Wait for async effects
      (Thread/sleep 100)

      ;; Git commit should receive directory
      (let [[_ git-data] (find-effect :git-commit)]
        (is (= "/project/path" (:cwd git-data))
            "Should pass directory as cwd for git")))))

;; =============================================================================
;; Event Handler Tests
;; =============================================================================

(deftest ling-session-complete-event-handler
  (testing "Event handler is registered"
    (is (ev/handler-registered? :ling/session-complete)
        "Handler should be registered")))

;; =============================================================================
;; Tool Definition Tests
;; =============================================================================

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest session-complete-full-flow
  (testing "Full session complete flow executes all steps"
    (let [result (sc/handle-session-complete
                  {:commit_msg "feat(session): complete sprint tasks"
                   :task_ids ["kanban-task-1" "kanban-task-2"]
                   :agent_id "ling-sprint-worker"
                   :directory "/tmp/test-project"})
          parsed (parse-mcp-response result)]

      (is (= "ok" (:status parsed)) "Should succeed")
      (is (= "ling-sprint-worker" (:agent_id parsed))
          "Should return agent_id")

      ;; Wait for async effects
      (Thread/sleep 150)

      ;; Verify all effects were triggered
      (is (some? (find-effect :git-commit)) "git-commit triggered")
      (is (some? (find-effect :kanban-move-done)) "kanban-move-done triggered")
      (is (some? (find-effect :wrap-crystallize)) "wrap-crystallize triggered")
      (is (some? (find-effect :shout)) "shout triggered"))))

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest config-management
  (testing "Config defaults"
    (let [config (sc/get-config)]
      (is (true? (:auto-kanban-on-explore config))
          "auto-kanban-on-explore should be true by default")))

  (testing "Config can be updated"
    (let [original (sc/get-config)]
      (sc/set-config! {:auto-kanban-on-explore false})
      (is (false? (:auto-kanban-on-explore (sc/get-config)))
          "Config should be updatable")
      ;; Restore
      (sc/set-config! original))))

;; =============================================================================
;; Explorer Preset Integration Tests
;; =============================================================================

(def ^:private explorer-directory "/tmp/hive-test-project")

(deftest explorer-preset-detection
  (testing "Ling with explorer preset is detected"
    ;; Setup: add a slave with explorer preset
    (ds/add-slave! "explorer-ling-test"
                   {:presets ["explorer" "hivemind"]
                    :status :working
                    :project-id "test-project"})
    (try
      ;; The has-explorer-preset? fn is private, test via behavior
      ;; Create a memory entry that looks like a plan
      (let [plan-content "{:plan/title \"Test Plan\" :plan/steps [{:step/id \"s1\"}]}"
            ;; The handler resolves project-id from :directory, not from the
            ;; slave row — seed the entry under the SAME id or it is unfindable.
            plan-project-id (scope/get-current-project-id explorer-directory)
            entry-id (memory/index-memory-entry!
                      {:type "decision"
                       :content plan-content
                       :tags ["plan" "exploration-output" "agent:explorer-ling-test"]
                       :project-id plan-project-id
                       :duration "medium"})]
        (try
          ;; Now complete session with the explorer ling
          (let [result (sc/handle-session-complete
                        {:commit_msg "feat: exploration complete"
                         :agent_id "explorer-ling-test"
                         :directory explorer-directory})
                parsed (parse-mcp-response result)]
            (is (= "ok" (:status parsed)) "Should succeed")
            ;; Should indicate plan_to_kanban was triggered
            (is (true? (:plan_to_kanban_triggered parsed))
                "Should trigger plan_to_kanban for explorer ling")
            (is (= entry-id (:plan_id parsed))
                "Should return the plan ID"))
          (finally
            ;; Cleanup memory entry
            (try (memory/delete-entry! entry-id) (catch Exception _)))))
      (finally
        ;; Cleanup slave
        (ds/remove-slave! "explorer-ling-test")))))

(deftest non-explorer-ling-no-trigger
  (testing "Ling without explorer preset does not trigger plan_to_kanban"
    ;; Setup: add a slave without explorer preset
    (ds/add-slave! "worker-ling-test"
                   {:presets ["tdd" "hivemind"]
                    :status :working
                    :project-id "test-project"})
    (try
      (let [result (sc/handle-session-complete
                    {:commit_msg "feat: regular work done"
                     :agent_id "worker-ling-test"
                     :directory "/tmp/hive-test-project"})
            parsed (parse-mcp-response result)]
        (is (= "ok" (:status parsed)) "Should succeed")
        (is (nil? (:plan_to_kanban_triggered parsed))
            "Should NOT trigger plan_to_kanban for non-explorer ling"))
      (finally
        (ds/remove-slave! "worker-ling-test")))))

(deftest config-disables-auto-trigger
  (testing "When auto-kanban-on-explore is false, no trigger"
    (let [original (sc/get-config)]
      (sc/set-config! {:auto-kanban-on-explore false})
      (try
        ;; Setup: add a slave with explorer preset
        (ds/add-slave! "explorer-disabled-test"
                       {:presets ["explorer"]
                        :status :working
                        :project-id "test-project"})
        (try
          (let [result (sc/handle-session-complete
                        {:commit_msg "feat: explore with disabled trigger"
                         :agent_id "explorer-disabled-test"
                         :directory "/tmp/hive-test-project"})
                parsed (parse-mcp-response result)]
            (is (= "ok" (:status parsed)) "Should succeed")
            (is (nil? (:plan_to_kanban_triggered parsed))
                "Should NOT trigger when config is disabled"))
          (finally
            (ds/remove-slave! "explorer-disabled-test")))
        (finally
          ;; Restore config
          (sc/set-config! original))))))

(deftest explorer-without-plan-no-trigger
  (testing "Explorer ling without plan memory does not crash"
    ;; Setup: add a slave with explorer preset but no plan memory
    (ds/add-slave! "explorer-no-plan-test"
                   {:presets ["explorer"]
                    :status :working
                    :project-id "test-project"})
    (try
      (let [result (sc/handle-session-complete
                    {:commit_msg "feat: explored but no plan"
                     :agent_id "explorer-no-plan-test"
                     :directory "/tmp/hive-test-project"})
            parsed (parse-mcp-response result)]
        (is (= "ok" (:status parsed)) "Should succeed even without plan")
        (is (nil? (:plan_to_kanban_triggered parsed))
            "Should NOT trigger without plan memory"))
      (finally
        (ds/remove-slave! "explorer-no-plan-test")))))
