(ns hive-mcp.events.claim-test
  "Tests for file claim event handlers and effects.

   Tests the file-claim event cascade:
   release-claim! → :claim/file-released → check wait-queue → :claim/notify-waiting → targeted shout

   SOLID: SRP - Tests only claim-related events
   CLARITY: I - Inputs are guarded via test validation"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.effects :as effects]
            [hive-mcp.events.handlers.claim :as claim]
            [hive-mcp.swarm.datascript :as ds]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn- claim-handlers-fixture
  "Reset effects + register claim handlers."
  [f]
  (effects/reset-registration!)
  (effects/register-effects!)
  (claim/register-handlers!)
  (f))

(use-fixtures :each
  (iso/with-isolations :swarm-ds :events)
  claim-handlers-fixture)

;; =============================================================================
;; Test: :targeted-shout Effect
;; =============================================================================

(deftest targeted-shout-effect-test
  (testing ":targeted-shout effect targets specific agent"
    (let [shout-calls (atom [])]
      ;; Mock shout handler
      (ev/reg-fx :targeted-shout
                 (fn [data]
                   (swap! shout-calls conj data)))

      ;; Create a simple handler that emits targeted-shout
      (ev/reg-event :test/targeted-shout
                    []
                    (fn [_ [_ data]]
                      {:targeted-shout {:target-agent-id "ling-worker-123"
                                        :event-type :file-available
                                        :data data}}))

      (ev/dispatch [:test/targeted-shout {:file "src/core.clj"}])

      (is (= 1 (count @shout-calls)))
      (is (= "ling-worker-123" (:target-agent-id (first @shout-calls))))
      (is (= :file-available (:event-type (first @shout-calls)))))))

;; =============================================================================
;; Test: :dispatch-n Effect
;; =============================================================================

(deftest dispatch-n-effect-test
  (testing ":dispatch-n dispatches multiple events (order not guaranteed with futures)"
    (let [events-dispatched (atom #{})]  ;; Use set since order not guaranteed
      ;; Register simple handlers that track dispatch
      (ev/reg-event :test/event-a
                    []
                    (fn [_ _]
                      (swap! events-dispatched conj :a)
                      {:log "Event A processed"}))

      (ev/reg-event :test/event-b
                    []
                    (fn [_ _]
                      (swap! events-dispatched conj :b)
                      {:log "Event B processed"}))

      (ev/reg-event :test/event-c
                    []
                    (fn [_ _]
                      (swap! events-dispatched conj :c)
                      {:log "Event C processed"}))

      ;; Handler that uses dispatch-n
      (ev/reg-event :test/trigger-multiple
                    []
                    (fn [_ _]
                      {:dispatch-n [[:test/event-a {}]
                                    [:test/event-b {}]
                                    [:test/event-c {}]]}))

      (ev/dispatch [:test/trigger-multiple])

      ;; Wait for async dispatches
      (Thread/sleep 150)

      ;; All 3 events should have been dispatched
      (is (= 3 (count @events-dispatched)))
      (is (= #{:a :b :c} @events-dispatched)))))

;; =============================================================================
;; Test: :waiting-lings Coeffect
;; =============================================================================

(deftest waiting-lings-coeffect-test
  (testing ":waiting-lings coeffect queries waiting lings for a file"
    ;; Set up test data - a ling waiting on src/core.clj
    (ds/add-slave! "ling-worker-1" {:name "worker-1" :status :idle})
    (ds/add-task! "task-1" "ling-worker-1"
                  {:status :queued
                   :prompt "Fix bug"
                   :files ["src/core.clj" "src/util.clj"]})

    (ds/add-slave! "ling-worker-2" {:name "worker-2" :status :idle})
    (ds/add-task! "task-2" "ling-worker-2"
                  {:status :queued
                   :prompt "Add feature"
                   :files ["src/core.clj"]})

    ;; Handler that injects waiting-lings coeffect
    (ev/reg-event :test/check-waiting
                  [(ev/inject-cofx :waiting-lings "src/core.clj")]
                  (fn [coeffects _]
                    {:log {:message (str "Waiting: " (:waiting-lings coeffects))}}))

    (let [result (ev/dispatch [:test/check-waiting {}])]
      ;; The coeffects should contain lings waiting on src/core.clj
      (is (contains? (:coeffects result) :waiting-lings))
      (let [waiting (:waiting-lings (:coeffects result))]
        (is (>= (count waiting) 2))
        ;; Each waiting entry should have slave-id and task-id
        (is (every? #(contains? % :slave-id) waiting))
        (is (every? #(contains? % :task-id) waiting))))))

;; =============================================================================
;; Test: :claim/file-released Handler
;; =============================================================================

(deftest claim-file-released-handler-test
  (testing ":claim/file-released dispatches notify events for waiting lings"
    (let [notifications (atom [])]
      ;; Mock the notify effect to capture calls
      (ev/reg-fx :targeted-shout
                 (fn [data]
                   (swap! notifications conj data)))

      ;; Set up a ling waiting on the file
      (ds/add-slave! "ling-worker-1" {:name "worker-1" :status :idle})
      (ds/add-task! "task-1" "ling-worker-1"
                    {:status :queued
                     :prompt "Fix bug"
                     :files ["src/core.clj"]})

      ;; Dispatch file-released event
      (ev/dispatch [:claim/file-released {:file "src/core.clj"
                                          :released-by "ling-other"}])

      ;; Wait for async dispatches
      (Thread/sleep 100)

      ;; Should have notified the waiting ling
      (is (>= (count @notifications) 1))
      (when (seq @notifications)
        (is (= "ling-worker-1" (:target-agent-id (first @notifications))))))))

(deftest claim-file-released-no-waiters-test
  (testing ":claim/file-released with no waiting lings logs and completes"
    (let [log-calls (atom [])]
      (ev/reg-fx :log (fn [data] (swap! log-calls conj data)))

      ;; Dispatch file-released for a file no one is waiting on
      (ev/dispatch [:claim/file-released {:file "src/unused.clj"
                                          :released-by "ling-other"}])

      ;; Should log but not error
      (is (seq @log-calls)))))

;; =============================================================================
;; Test: :claim/notify-waiting Handler
;; =============================================================================

(deftest claim-notify-waiting-handler-test
  (testing ":claim/notify-waiting sends targeted shout"
    (let [shouts (atom [])]
      (ev/reg-fx :targeted-shout
                 (fn [data]
                   (swap! shouts conj data)))

      (ev/dispatch [:claim/notify-waiting {:target-agent-id "ling-worker-1"
                                           :file "src/core.clj"
                                           :task-id "task-1"}])

      (is (= 1 (count @shouts)))
      (let [shout (first @shouts)]
        (is (= "ling-worker-1" (:target-agent-id shout)))
        (is (= :file-available (:event-type shout)))
        (is (= "src/core.clj" (get-in shout [:data :file])))))))

;; =============================================================================
;; Test: Integration - Full Event Cascade
;; =============================================================================

(deftest full-event-cascade-test
  (testing "Full cascade: release → file-released → notify-waiting → shout"
    (let [shouts (atom [])]
      (ev/reg-fx :targeted-shout
                 (fn [data]
                   (swap! shouts conj data)))

      ;; Set up: ling-A claims src/core.clj, ling-B is waiting
      (ds/add-slave! "ling-A" {:name "ling-A" :status :working})
      (ds/add-slave! "ling-B" {:name "ling-B" :status :idle})

      ;; Create task for ling-A before claiming with task reference
      (ds/add-task! "task-A" "ling-A"
                    {:status :dispatched
                     :prompt "Working on file"
                     :files ["src/core.clj"]})

      ;; ling-A has the claim (with task reference)
      (ds/claim-file! "src/core.clj" "ling-A" "task-A")

      ;; ling-B has a queued task waiting for the file
      (ds/add-task! "task-B" "ling-B"
                    {:status :queued
                     :prompt "Waiting for file"
                     :files ["src/core.clj"]})

      ;; Now ling-A releases the claim - this should trigger the cascade
      (ev/dispatch [:claim/file-released {:file "src/core.clj"
                                          :released-by "ling-A"}])

      ;; Wait for async dispatches
      (Thread/sleep 200)

      ;; ling-B should have been notified
      (is (= 1 (count @shouts)))
      (when (seq @shouts)
        (is (= "ling-B" (:target-agent-id (first @shouts))))
        (is (= :file-available (:event-type (first @shouts))))))))
