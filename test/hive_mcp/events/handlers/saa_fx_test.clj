(ns hive-mcp.events.handlers.saa-fx-test
  "Tests for SAA workflow side-effect handlers.

   Validates that :saa/run-workflow, :saa/tool-gate, :saa/context-inject,
   and :saa/shout effect handlers register correctly and execute without errors.

   Uses ev/with-clean-registry for test isolation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.events.handlers.saa-fx :as saa-fx]
            [hive-mcp.events.core :as ev]
            [hive-mcp.hivemind.core]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean-registry-fixture [f]
  (ev/with-clean-registry
    (f)))

(use-fixtures :each clean-registry-fixture)

;; =============================================================================
;; Registration Tests
;; =============================================================================

(deftest test-register-saa-fx!
  (testing "register-saa-fx! registers all 4 effect handlers"
    (saa-fx/register-saa-fx!)
    (is (fn? (ev/get-fx-handler :saa/run-workflow))
        ":saa/run-workflow should be registered")
    (is (fn? (ev/get-fx-handler :saa/tool-gate))
        ":saa/tool-gate should be registered")
    (is (fn? (ev/get-fx-handler :saa/context-inject))
        ":saa/context-inject should be registered")
    (is (fn? (ev/get-fx-handler :saa/shout))
        ":saa/shout should be registered")))

(deftest test-idempotent-registration
  (testing "register-saa-fx! can be called multiple times safely"
    (saa-fx/register-saa-fx!)
    (saa-fx/register-saa-fx!)
    (is (fn? (ev/get-fx-handler :saa/tool-gate))
        "Handler still registered after double call")))

;; =============================================================================
;; :saa/run-workflow Tests
;; =============================================================================

(deftest test-saa-run-workflow-nil-graceful
  (testing ":saa/run-workflow handles nil task/agent-id gracefully (no-op)"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/run-workflow)]
      (is (nil? (handler {:task nil :agent-id nil}))
          "Handler should return nil when task or agent-id missing"))))

(deftest test-saa-run-workflow-requires-task-and-agent
  (testing ":saa/run-workflow requires both task and agent-id"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/run-workflow)]
      ;; Missing agent-id
      (is (nil? (handler {:task "some task" :agent-id nil}))
          "Should no-op without agent-id")
      ;; Missing task
      (is (nil? (handler {:task nil :agent-id "test-agent"}))
          "Should no-op without task"))))

;; =============================================================================
;; :saa/tool-gate Tests
;; =============================================================================

(deftest test-saa-tool-gate-executes-without-error
  (testing ":saa/tool-gate handler executes without error"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/tool-gate)]
      (handler {:phase :silence
                :agent-id "test-agent"
                :allowed-tools ["read_file" "grep" "glob_files"]})
      (is true "Handler executed without error"))))

(deftest test-saa-tool-gate-nil-graceful
  (testing ":saa/tool-gate handles nil phase/agent-id gracefully"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/tool-gate)]
      (handler {:phase nil :agent-id nil})
      (is true "Handler handled nil gracefully"))))

(deftest test-saa-tool-gate-each-phase
  (testing ":saa/tool-gate works for each SAA phase"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/tool-gate)]
      (doseq [phase [:silence :abstract :act]]
        (handler {:phase phase
                  :agent-id "test-agent"
                  :allowed-tools ["tool-1"]})
        (is true (str "Phase " (name phase) " handled"))))))

;; =============================================================================
;; :saa/context-inject Tests
;; =============================================================================

(deftest test-saa-context-inject-executes-without-error
  (testing ":saa/context-inject handler executes without error"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/context-inject)]
      (handler {:phase :abstract
                :agent-id "test-agent"
                :context {:axioms ["ax1"] :conventions ["conv1"]}})
      (is true "Handler executed without error"))))

(deftest test-saa-context-inject-nil-graceful
  (testing ":saa/context-inject handles nil phase/agent-id gracefully"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/context-inject)]
      (handler {:phase nil :agent-id nil :context {}})
      (is true "Handler handled nil gracefully"))))

(deftest test-saa-context-inject-empty-context
  (testing ":saa/context-inject handles empty context"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/context-inject)]
      (handler {:phase :silence :agent-id "test-agent" :context {}})
      (is true "Empty context handled"))))

;; =============================================================================
;; :saa/shout Tests
;; =============================================================================

(deftest test-saa-shout-broadcasts-via-hivemind
  (testing ":saa/shout broadcasts via hivemind with correct data"
    (saa-fx/register-saa-fx!)
    (let [shouted (atom nil)
          handler (ev/get-fx-handler :saa/shout)]
      (with-redefs [hive-mcp.hivemind.core/shout!
                    (fn [aid etype data]
                      (reset! shouted {:agent-id aid
                                       :event-type etype
                                       :data data}))]
        (handler {:agent-id "test-ling"
                  :phase :silence
                  :message "Exploring codebase"
                  :event-type :progress})
        (is (= "test-ling" (:agent-id @shouted))
            "Should use provided agent-id")
        (is (= :progress (:event-type @shouted))
            "Should use provided event-type")
        (is (= :saa (get-in @shouted [:data :workflow]))
            "Should include :saa workflow marker")
        (is (= :silence (get-in @shouted [:data :phase]))
            "Should include phase")
        (is (= "Exploring codebase" (get-in @shouted [:data :message]))
            "Should include message")))))

(deftest test-saa-shout-fallback-agent-id
  (testing ":saa/shout falls back to env var when agent-id nil"
    (saa-fx/register-saa-fx!)
    (let [shouted (atom nil)
          handler (ev/get-fx-handler :saa/shout)]
      (with-redefs [hive-mcp.hivemind.core/shout!
                    (fn [aid _etype _data]
                      (reset! shouted {:agent-id aid}))]
        (handler {:agent-id nil
                  :phase :act
                  :message "Acting"
                  :event-type :started})
        ;; Should use env var fallback or "unknown-agent"
        (is (string? (:agent-id @shouted))
            "Should resolve to a string agent-id")))))

(deftest test-saa-shout-default-event-type
  (testing ":saa/shout defaults event-type to :progress"
    (saa-fx/register-saa-fx!)
    (let [shouted (atom nil)
          handler (ev/get-fx-handler :saa/shout)]
      (with-redefs [hive-mcp.hivemind.core/shout!
                    (fn [_aid etype _data]
                      (reset! shouted {:event-type etype}))]
        (handler {:agent-id "test-ling"
                  :phase :abstract
                  :message "Planning"})
        (is (= :progress (:event-type @shouted))
            "Should default to :progress when event-type not provided")))))

(deftest test-saa-shout-nil-message
  (testing ":saa/shout handles nil message gracefully"
    (saa-fx/register-saa-fx!)
    (let [handler (ev/get-fx-handler :saa/shout)]
      (with-redefs [hive-mcp.hivemind.core/shout!
                    (fn [_aid _etype _data] nil)]
        (handler {:agent-id "test-ling" :phase :silence :message nil})
        (is true "Nil message handled gracefully")))))

(deftest default-dispatch-fn-test
  (testing "non-dag-wave modes return :no-dispatch-for-mode without side effects"
    (let [f (saa-fx/default-dispatch-fn "/test/project")]
      (is (= {:wave-id nil :result {:status :no-dispatch-for-mode :mode :direct}}
             (f {:id "p1"} :direct "agent-1" {:run-id "r1"})))
      (is (= {:wave-id nil :result {:status :no-dispatch-for-mode :mode :ling}}
             (f {:id "p1"} :ling "agent-1" {})))))

  (testing "contract shape: 4-arity with ctx map"
    (let [f (saa-fx/default-dispatch-fn "/test/project")]
      (is (fn? f))
      (is (map? (f {:id "p1"} :direct "agent-1" nil))))))
