;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.events.file-claim-integration-test
  "Integration tests for file-claim event cascade.

   Tests the FULL flow from release-claim! through the event system
   to hivemind notification:

   release-claim! -> :claim/file-released -> query waiting -> :claim/notify-waiting -> targeted shout

   These tests verify the complete integration, not just individual handlers.

   Test scenarios:
   1. Full cascade - file release notifies waiting ling
   2. No waiting lings - no notification dispatched
   3. Multiple waiting lings - FIFO order respected

   SOLID: SRP - Integration tests only
   CLARITY: I - Inputs are guarded via test validation"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.effects :as effects]
            [hive-mcp.events.handlers :as handlers]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-dsl.bounded-atom :refer [bounded-atom bget bcount bclear!]]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn integration-fixture
  "Fresh DataScript conn, empty agent-registry, full handler/effect stack.

   Both globals are reached through the seam their owning namespace exposes —
   `*test-conn*` for the swarm conn, `bclear!` for the registry — never by
   redefining a var: `hive-mcp.swarm.datascript` and `hive-mcp.hivemind.core`
   both re-export by VALUE, so a redef of the source var splits readers from
   writers."
  [f]
  (binding [conn/*test-conn* (conn/create-conn)]
    (bclear! hivemind/agent-registry)
    (ev/reset-all!)
    (effects/reset-registration!)
    (handlers/reset-registration!)

    (ev/init!)
    (effects/register-effects!)
    (handlers/register-handlers!)

    (try
      (f)
      (finally
        (bclear! hivemind/agent-registry)
        (ev/reset-all!)))))

(use-fixtures :each integration-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn wait-for-async
  "Wait for async event dispatches to complete."
  ([] (wait-for-async 200))
  ([ms] (Thread/sleep ms)))

(defn get-agent-messages
  "Get messages for an agent from hivemind registry."
  [agent-id]
  ;; bounded-atom: use bget to access wrapped entry data
  (let [agent-data (bget hivemind/agent-registry agent-id)]
    (:messages agent-data)))

(defn find-file-available-message
  "Find a :file-available message for a specific file in agent's messages."
  [agent-id file-path]
  (->> (get-agent-messages agent-id)
       (filter #(= :file-available (:event-type %)))
       (filter #(= file-path (get-in % [:data :file])))
       first))

;; =============================================================================
;; Test 1: Full Cascade - File Release Notifies Waiting Ling
;; =============================================================================

(deftest full-cascade-file-release-notifies-waiting-ling
  (testing "release-claim! triggers full cascade and notifies waiting ling"
    ;; Setup: ling-1 claims file, ling-2 has queued task waiting
    (ds/add-slave! "ling-1" {:name "worker-1" :status :working})
    (ds/add-slave! "ling-2" {:name "worker-2" :status :idle})

    ;; Register ling-2 in hivemind so shout can store messages
    (hivemind/register-agent! "ling-2" {:name "worker-2"})

    ;; ling-1 claims the file
    (ds/add-task! "task-1" "ling-1"
                  {:status :dispatched
                   :prompt "Working on file"
                   :files ["src/foo.clj"]})
    (ds/claim-file! "src/foo.clj" "ling-1" {:task-id "task-1"})

    ;; ling-2 has a queued task waiting for the file
    (ds/add-task! "task-2" "ling-2"
                  {:status :queued
                   :prompt "Waiting for file"
                   :files ["src/foo.clj"]})

    ;; Action: release the claim (this should trigger the cascade)
    (ds/release-claim! "src/foo.clj")

    ;; Wait for async event dispatches
    (wait-for-async)

    ;; Assert: ling-2 should have received a :file-available message
    (let [message (find-file-available-message "ling-2" "src/foo.clj")]
      (is (some? message) "ling-2 should receive :file-available notification")
      (when message
        (is (= :file-available (:event-type message)))
        (is (= "src/foo.clj" (get-in message [:data :file])))
        ;; :message is stored at top level in hivemind message (not nested in :data)
        (is (some? (:message message)))))))

;; =============================================================================
;; Test 2: No Waiting Lings - No Notification
;; =============================================================================

(deftest no-waiting-lings-no-notification
  (testing "release-claim! with no waiting lings completes without notification"
    ;; Setup: ling-1 claims file, but no one is waiting
    (ds/add-slave! "ling-1" {:name "worker-1" :status :working})

    ;; ling-1 claims the file (no task association for simpler test)
    (ds/claim-file! "src/bar.clj" "ling-1")

    ;; Action: release the claim
    (ds/release-claim! "src/bar.clj")

    ;; Wait for async event dispatches
    (wait-for-async)

    ;; Assert: No agents should have file-available messages
    ;; bounded-atom: use bcount instead of (count @...)
    (is (= 0 (bcount hivemind/agent-registry))
        "No agents should receive notifications when no one is waiting")))

(deftest no-waiting-lings-completed-tasks-ignored
  (testing "Completed tasks are not notified (only :queued status)"
    ;; Setup: ling-1 claims file, ling-2 has COMPLETED task (not waiting)
    (ds/add-slave! "ling-1" {:name "worker-1" :status :working})
    (ds/add-slave! "ling-2" {:name "worker-2" :status :idle})

    ;; Register ling-2 in hivemind
    (hivemind/register-agent! "ling-2" {:name "worker-2"})

    ;; ling-1 claims the file
    (ds/claim-file! "src/baz.clj" "ling-1")

    ;; ling-2 has a COMPLETED task with the file (should NOT be notified)
    (ds/add-task! "task-2" "ling-2"
                  {:status :completed
                   :prompt "Already done"
                   :files ["src/baz.clj"]})

    ;; Action: release the claim
    (ds/release-claim! "src/baz.clj")

    ;; Wait for async event dispatches
    (wait-for-async)

    ;; Assert: ling-2 should NOT have received notification (task not :queued)
    (let [message (find-file-available-message "ling-2" "src/baz.clj")]
      (is (nil? message)
          "Completed tasks should not receive file-available notifications"))))

;; =============================================================================
;; Test 3: Multiple Waiting Lings - All Notified
;; =============================================================================

(deftest multiple-waiting-lings-all-notified
  (testing "Multiple waiting lings all receive notification"
    ;; Setup: ling-1 claims file, ling-2 and ling-3 are waiting
    (ds/add-slave! "ling-1" {:name "worker-1" :status :working})
    (ds/add-slave! "ling-2" {:name "worker-2" :status :idle})
    (ds/add-slave! "ling-3" {:name "worker-3" :status :idle})

    ;; Register agents in hivemind
    (hivemind/register-agent! "ling-2" {:name "worker-2"})
    (hivemind/register-agent! "ling-3" {:name "worker-3"})

    ;; ling-1 claims the file
    (ds/claim-file! "src/shared.clj" "ling-1")

    ;; ling-2 queued first
    (ds/add-task! "task-2" "ling-2"
                  {:status :queued
                   :prompt "Waiting first"
                   :files ["src/shared.clj"]})

    ;; Small delay to ensure distinct timestamps
    (Thread/sleep 10)

    ;; ling-3 queued second
    (ds/add-task! "task-3" "ling-3"
                  {:status :queued
                   :prompt "Waiting second"
                   :files ["src/shared.clj"]})

    ;; Action: release the claim
    (ds/release-claim! "src/shared.clj")

    ;; Wait for async event dispatches
    (wait-for-async)

    ;; Assert: Both lings should have received notification
    (let [msg-2 (find-file-available-message "ling-2" "src/shared.clj")
          msg-3 (find-file-available-message "ling-3" "src/shared.clj")]
      (is (some? msg-2) "ling-2 should receive notification")
      (is (some? msg-3) "ling-3 should receive notification"))))

;; =============================================================================
;; Test 4: Event Cascade from release-claim!
;; =============================================================================

(deftest release-claim-dispatches-event
  (testing "release-claim! dispatches :claim/file-released event"
    (let [events-dispatched (atom [])]
      ;; Wrap the file-released handler to capture dispatch
      (ev/reg-event :claim/file-released
                    [(ev/inject-cofx :db-snapshot)]
                    (fn [coeffects event]
                      (swap! events-dispatched conj event)
                      ;; Return empty effects - we just want to capture
                      {:log "captured"}))

      ;; Setup: claim a file
      (ds/add-slave! "ling-1" {:name "worker" :status :working})
      (ds/claim-file! "src/event-test.clj" "ling-1")

      ;; Action: release
      (ds/release-claim! "src/event-test.clj")

      ;; Wait for async
      (wait-for-async 100)

      ;; Assert: event was dispatched
      (is (= 1 (count @events-dispatched)))
      (when (seq @events-dispatched)
        (let [[event-id data] (first @events-dispatched)]
          (is (= :claim/file-released event-id))
          (is (= "src/event-test.clj" (:file data))))))))

;; =============================================================================
;; Test 5: Task-Based Waiting (Queued Status Filter)
;; =============================================================================

(deftest only-queued-tasks-are-waiting
  (testing "Only tasks with :queued status are considered waiting"
    ;; Setup: Various task statuses
    (ds/add-slave! "ling-1" {:name "owner" :status :working})
    (ds/add-slave! "ling-queued" {:name "queued" :status :idle})
    (ds/add-slave! "ling-dispatched" {:name "dispatched" :status :working})
    (ds/add-slave! "ling-error" {:name "error" :status :error})

    ;; Register agents
    (hivemind/register-agent! "ling-queued" {:name "queued"})
    (hivemind/register-agent! "ling-dispatched" {:name "dispatched"})
    (hivemind/register-agent! "ling-error" {:name "error"})

    ;; Owner claims file
    (ds/claim-file! "src/status-test.clj" "ling-1")

    ;; Various task statuses
    (ds/add-task! "task-queued" "ling-queued"
                  {:status :queued :files ["src/status-test.clj"]})
    (ds/add-task! "task-dispatched" "ling-dispatched"
                  {:status :dispatched :files ["src/status-test.clj"]})
    (ds/add-task! "task-error" "ling-error"
                  {:status :error :files ["src/status-test.clj"]})

    ;; Release
    (ds/release-claim! "src/status-test.clj")
    (wait-for-async)

    ;; Only queued task should be notified
    (is (some? (find-file-available-message "ling-queued" "src/status-test.clj"))
        "Queued task should be notified")
    (is (nil? (find-file-available-message "ling-dispatched" "src/status-test.clj"))
        "Dispatched task should NOT be notified")
    (is (nil? (find-file-available-message "ling-error" "src/status-test.clj"))
        "Error task should NOT be notified")))

;; =============================================================================
;; Test 6: Complete Task Releases Claims Cascade
;; =============================================================================

(deftest complete-task-releases-claims-cascade
  (testing "complete-task! releases claims which triggers cascade"
    ;; Setup: ling-1 has task with claims, ling-2 is waiting
    (ds/add-slave! "ling-1" {:name "worker-1" :status :working})
    (ds/add-slave! "ling-2" {:name "worker-2" :status :idle})

    ;; Register ling-2 in hivemind
    (hivemind/register-agent! "ling-2" {:name "worker-2"})

    ;; ling-1 task and claim. The claim carries :task-id in the OPTS MAP —
    ;; release-claims-for-task! finds claims through the :claim/task ref.
    (ds/add-task! "task-1" "ling-1"
                  {:status :dispatched
                   :prompt "Working"
                   :files ["src/cascade.clj"]})
    (ds/claim-file! "src/cascade.clj" "ling-1" {:task-id "task-1"})

    ;; ling-2 waiting
    (ds/add-task! "task-2" "ling-2"
                  {:status :queued
                   :prompt "Waiting"
                   :files ["src/cascade.clj"]})

    ;; Action: complete task-1 (should release claims)
    (ds/complete-task! "task-1")

    ;; Wait for async
    (wait-for-async)

    ;; Assert: ling-2 notified
    (is (some? (find-file-available-message "ling-2" "src/cascade.clj"))
        "Completing task should trigger claim release and notify waiting ling")))
