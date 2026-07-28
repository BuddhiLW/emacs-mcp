(ns hive-mcp.state-sync-test
  "TDD tests for hivemind <-> swarm state synchronization.

   Problem: When a ling calls `hivemind_shout :completed`, the `swarm_status`
   should show that slave as 'idle', not 'working'. Currently these are
   disconnected state stores.

   CLARITY: Telemetry first - verify state flows correctly between systems.
   DDD: Testing the domain invariant that agent state is consistent."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.tools.swarm :as swarm]
            [hive-dsl.bounded-atom :refer [bget bclear!]]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.hivemind.event-registry :as event-registry]))

;; =============================================================================
;; Test Fixtures - Reset state between tests
;; =============================================================================

(use-fixtures :each (iso/with-isolations :swarm-ds :agent-registry))

;; =============================================================================
;; Core Requirement: Agent-ID to Slave-ID Mapping
;; =============================================================================

(deftest test-agent-id-maps-to-slave-id
  (testing "Agent ID from hivemind_shout should map to slave ID in swarm_status"
    ;; Given: A ling is registered in the swarm registry
    (swarm/register-ling! "slave-abc-123" {:name "test-ling" :presets [] :cwd "/tmp"})

    ;; When: The ling shouts with its agent_id (which should match slave_id)
    (hivemind/shout! "slave-abc-123" :started {:task "Test task" :message "Starting"})

    ;; Then: The hivemind agent registry should contain this agent
    (is (some? (bget hivemind/agent-registry "slave-abc-123"))
        "Agent should be registered in hivemind after shout")))

;; =============================================================================
;; State Sync: Started Event -> Working Status
;; =============================================================================

(deftest test-started-event-sets-working-status
  (testing "When hivemind receives :started event, slave status should be 'working'"
    ;; Given: A registered ling
    (swarm/register-ling! "slave-worker-1" {:name "worker" :presets [] :cwd "/tmp"})

    ;; When: The ling shouts :started
    (hivemind/shout! "slave-worker-1" :started {:task "Processing data" :message "Beginning work"})

    ;; Then: DataScript carries the status the event registry maps :started to,
    ;; NOT the event keyword itself (ADR-002: DataScript is source of truth).
    ;; Asking the registry rather than restating :working keeps this assertion
    ;; correct for free when the registry gains or remaps an event type.
    (let [slave-status (:slave/status (ds/get-slave "slave-worker-1"))]
      (is (= (event-registry/slave-status :started) slave-status)
          "Slave status in DataScript should be the registry status for :started"))

    ;; AND: get-slave-working-status should return "working"
    (is (= "working" (swarm/get-slave-working-status "slave-worker-1"))
        "Slave should be marked as 'working' after :started event")))

(deftest test-progress-event-maintains-working-status
  (testing "When hivemind receives :progress event, slave should remain 'working'"
    ;; Given: A ling that has started
    (swarm/register-ling! "slave-worker-2" {:name "worker2" :presets [] :cwd "/tmp"})
    (hivemind/shout! "slave-worker-2" :started {:task "Long task" :message "Starting"})

    ;; When: The ling reports progress
    (hivemind/shout! "slave-worker-2" :progress {:task "Long task" :message "50% complete"})

    ;; Then: Slave should still be "working"
    (is (= "working" (swarm/get-slave-working-status "slave-worker-2"))
        "Slave should remain 'working' during progress updates")))

;; =============================================================================
;; State Sync: Completed Event -> Idle Status
;; =============================================================================

(deftest test-completed-event-sets-idle-status
  (testing "When hivemind receives :completed event, slave status should be 'idle'"
    ;; Given: A ling that is currently working
    (swarm/register-ling! "slave-finisher-1" {:name "finisher" :presets [] :cwd "/tmp"})
    (hivemind/shout! "slave-finisher-1" :started {:task "Quick task" :message "Starting"})

    ;; When: The ling shouts :completed
    (hivemind/shout! "slave-finisher-1" :completed {:task "Quick task" :message "Done!" :result "success"})

    ;; Then: DataScript carries the registry status for :completed, not the
    ;; event keyword (ADR-002: DataScript is source of truth).
    (let [slave-status (:slave/status (ds/get-slave "slave-finisher-1"))]
      (is (= (event-registry/slave-status :completed) slave-status)
          "Slave status in DataScript should be the registry status for :completed"))

    ;; AND: The slave should now be "idle" (ready for new work)
    (is (= "idle" (swarm/get-slave-working-status "slave-finisher-1"))
        "Slave should be marked as 'idle' after :completed event")))

(deftest test-error-event-sets-idle-status
  (testing "When hivemind receives :error event, slave status should be 'idle'"
    ;; Given: A working ling
    (swarm/register-ling! "slave-error-1" {:name "error-prone" :presets [] :cwd "/tmp"})
    (hivemind/shout! "slave-error-1" :started {:task "Risky task" :message "Attempting"})

    ;; When: The ling reports an error
    (hivemind/shout! "slave-error-1" :error {:task "Risky task" :error "Failed to complete"})

    ;; Then: Slave should be idle (available for retry/new work)
    (is (= "idle" (swarm/get-slave-working-status "slave-error-1"))
        "Slave should be 'idle' after error (available for new work)")))

;; =============================================================================
;; State Sync: Blocked Event -> Blocked Status
;; =============================================================================

(deftest test-blocked-event-sets-blocked-status
  (testing "When hivemind receives :blocked event, slave status should be 'blocked'"
    ;; Given: A working ling
    (swarm/register-ling! "slave-blocked-1" {:name "blocker" :presets [] :cwd "/tmp"})
    (hivemind/shout! "slave-blocked-1" :started {:task "Dependent task" :message "Starting"})

    ;; When: The ling reports being blocked
    (hivemind/shout! "slave-blocked-1" :blocked {:task "Dependent task" :reason "Waiting for input"})

    ;; Then: Slave should be marked as blocked
    (is (= "blocked" (swarm/get-slave-working-status "slave-blocked-1"))
        "Slave should be 'blocked' when waiting for input")))

;; =============================================================================
;; Integration: swarm_status reflects hivemind state
;; =============================================================================

(deftest test-swarm-status-reflects-hivemind-events
  (testing "swarm_status response should include working status from hivemind events"
    ;; Given: Multiple lings in various states
    (swarm/register-ling! "ling-a" {:name "a" :presets [] :cwd "/tmp"})
    (swarm/register-ling! "ling-b" {:name "b" :presets [] :cwd "/tmp"})
    (swarm/register-ling! "ling-c" {:name "c" :presets [] :cwd "/tmp"})

    ;; And: They report different states
    (hivemind/shout! "ling-a" :started {:task "Task A" :message "Working"})
    (hivemind/shout! "ling-b" :completed {:task "Task B" :message "Done"})
    ;; ling-c hasn't reported anything (should be idle/unknown)

    ;; When: We get the unified status
    ;; This function should merge hivemind state into swarm status
    (let [status (swarm/get-unified-swarm-status)]
      ;; Then: Each ling should have correct working status
      (is (= "working" (get-in status ["ling-a" :working-status]))
          "ling-a should be working")
      (is (= "idle" (get-in status ["ling-b" :working-status]))
          "ling-b should be idle after completion")
      (is (= "idle" (get-in status ["ling-c" :working-status]))
          "ling-c should be idle (no activity reported)"))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-unknown-agent-returns-nil
  (testing "Querying status for unregistered agent returns nil"
    (is (nil? (swarm/get-slave-working-status "nonexistent-slave"))
        "Unknown slave should return nil status")))

(deftest test-multiple-events-use-latest-status
  (testing "Multiple events for same agent use the latest status"
    (swarm/register-ling! "slave-multi" {:name "multi" :presets [] :cwd "/tmp"})

    ;; Rapid state changes
    (hivemind/shout! "slave-multi" :started {:task "T1" :message "Start"})
    (hivemind/shout! "slave-multi" :progress {:task "T1" :message "Progress"})
    (hivemind/shout! "slave-multi" :completed {:task "T1" :message "Done"})
    (hivemind/shout! "slave-multi" :started {:task "T2" :message "New task"})

    ;; Latest state should win
    (is (= "working" (swarm/get-slave-working-status "slave-multi"))
        "Latest :started event should set status to working")))

;; =============================================================================
;; Bug Fix Tests: Registry Sync (Sprint-2)
;; =============================================================================

(deftest test-dispatch-sets-working-status
  (testing "When task is dispatched, slave status should be 'working'"
    ;; Given: A registered ling in idle state
    (swarm/register-ling! "slave-dispatch-1" {:name "dispatcher" :presets [] :cwd "/tmp"})

    ;; Verify initial state is idle
    (is (= "idle" (swarm/get-slave-working-status "slave-dispatch-1"))
        "New slave should start as 'idle'")

    ;; When: A task is dispatched to the slave (simulating sync event)
    (ds/update-slave! "slave-dispatch-1" {:slave/status :working})

    ;; Then: Slave should now be "working"
    (is (= "working" (swarm/get-slave-working-status "slave-dispatch-1"))
        "Slave should be 'working' after task dispatch")))

(deftest test-get-slave-working-status-uses-datascript
  (testing "get-slave-working-status should query DataScript, not hivemind atom"
    ;; Given: A registered ling
    (swarm/register-ling! "slave-ds-1" {:name "ds-test" :presets [] :cwd "/tmp"})

    ;; When: We set status directly in DataScript (bypassing hivemind)
    (ds/update-slave! "slave-ds-1" {:slave/status :working})

    ;; Then: get-slave-working-status should see it (proves it reads from DS)
    (is (= "working" (swarm/get-slave-working-status "slave-ds-1"))
        "Should read status from DataScript")

    ;; And: When DataScript says :completed, status should be "idle"
    (ds/update-slave! "slave-ds-1" {:slave/status :idle})
    (is (= "idle" (swarm/get-slave-working-status "slave-ds-1"))
        "Should reflect DataScript status change")))
