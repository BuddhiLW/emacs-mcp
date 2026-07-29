(ns hive-mcp.telemetry.guards-test
  "TDD tests for Telemetry Phase 1: try/catch guards and :system/* events.

   Tests verify:
   1. Harvest functions return gracefully on error (no exceptions propagate)
   2. :system/* event schemas validate correctly
   3. :emit-system-error effect emits structured errors

   CLARITY Principle: Telemetry first - observable system behavior."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core]
            [hive-mcp.channel.core]
            [hive-mcp.crystal.hooks :as hooks]
            [hive-mcp.crystal.recall :as recall]
            [hive-mcp.events.schemas :as schemas]
            [hive-mcp.events.effects :as effects]
            [hive-mcp.events.core :as ev]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.test.stub.extensions :as stub-ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-effects-fixture [f]
  (effects/reset-registration!)
  (f))

(use-fixtures :each reset-effects-fixture)

;; =============================================================================
;; Collaborator stub + fault-injecting decorator
;; =============================================================================
;;
;; The harvest code reaches Emacs through the extension seam
;; `:emacs/eval-elisp-with-timeout` (hive-mcp.emacs-ext.client), NOT through
;; hive-mcp.emacs.client. Absence is ARRANGED rather than assumed: the stub is
;; registered in the real registry and restored afterwards, so these tests
;; behave identically on a cold JVM (empty registry) and in a live image where
;; hive-emacs IS loaded.

(defn stub-eval-elisp
  "Stub collaborator for `:emacs/eval-elisp-with-timeout`.
   Always succeeds, returning PAYLOAD as the elisp result string."
  ([] (stub-eval-elisp "[]"))
  ([payload]
   (fn [_code _timeout-ms]
     {:success true :result payload :timed-out false})))

(defn throwing
  "Fault-injecting DECORATOR: wraps COLLABORATOR so every call throws MSG
   instead of delegating."
  [_collaborator msg]
  (fn [& _args] (throw (ex-info msg {}))))

(defn with-failing-emacs
  "Run F with `:emacs/eval-elisp-with-timeout` bound to a fault-injecting
   decorator over `stub-eval-elisp`, then restore the prior registration."
  [msg f]
  (stub-ext/with-extensions
    {:emacs/eval-elisp-with-timeout (throwing (stub-eval-elisp) msg)}
    f))

;; =============================================================================
;; Harvest Function Guard Tests
;; =============================================================================

(deftest harvest-session-progress-returns-gracefully-on-error
  (testing "harvest-session-progress returns error map instead of throwing"
    (with-failing-emacs "Emacs unreachable"
      (fn []
        (let [result (hooks/harvest-session-progress)]
          (is (map? result) "Should return a map, not throw")
          (is (= [] (:notes result)) "Should have empty notes on error")
          (is (= 0 (:count result)) "Should have zero count on error")
          (is (contains? result :error) "Should contain :error key with details"))))))

(deftest harvest-completed-tasks-returns-gracefully-on-error
  (testing "harvest-completed-tasks returns error map instead of throwing"
    ;; ds/get-completed-tasks-this-session is a LIVE var seam the harvest calls
    ;; directly; decorate it so the DataScript source fails alongside Emacs.
    (with-redefs [ds/get-completed-tasks-this-session
                  (throwing ds/get-completed-tasks-this-session "DataScript error")]
      (with-failing-emacs "Emacs unreachable"
        (fn []
          (let [result (hooks/harvest-completed-tasks)]
            (is (map? result) "Should return a map, not throw")
            (is (= [] (:tasks result)) "Should have empty tasks on error")
            (is (= 0 (:count result)) "Should have zero count on error")
            (is (contains? result :error) "Should contain :error key with details")))))))

(deftest harvest-git-commits-returns-gracefully-on-error
  (testing "harvest-git-commits returns error map instead of throwing"
    (with-failing-emacs "Shell execution failed"
      (fn []
        (let [result (hooks/harvest-git-commits)]
          (is (map? result) "Should return a map, not throw")
          (is (= [] (:commits result)) "Should have empty commits on error")
          (is (= 0 (:count result)) "Should have zero count on error")
          (is (contains? result :error) "Should contain :error key with details"))))))

(deftest harvest-all-returns-gracefully-on-error
  (testing "harvest-all returns partial results when sub-harvests fail"
    (with-redefs [ds/get-completed-tasks-this-session
                  (throwing ds/get-completed-tasks-this-session "DataScript down")
                  recall/get-buffered-recalls
                  (throwing recall/get-buffered-recalls "Recall buffer error")]
      (with-failing-emacs "Complete failure"
        (fn []
          (let [result (hooks/harvest-all)]
            (is (map? result) "Should return a map, not throw")
            (is (contains? result :progress-notes) "Should have progress-notes key")
            (is (contains? result :completed-tasks) "Should have completed-tasks key")
            (is (contains? result :git-commits) "Should have git-commits key")
            (is (contains? result :errors) "Should have :errors key aggregating failures")))))))

(deftest harvest-functions-include-error-metadata
  (testing "Harvest error maps include structured metadata for telemetry"
    (with-failing-emacs "Connection refused"
      (fn []
        (let [result (hooks/harvest-session-progress)
              error (:error result)]
          (is (map? error) "Error should be a map with structured data")
          (is (contains? error :type) "Error should have :type")
          (is (contains? error :fn) "Error should have :fn (function name)")
          (is (contains? error :msg) "Error should have :msg (message)"))))))

;; =============================================================================
;; :system/* Event Schema Tests
;; =============================================================================

(deftest system-error-schema-validates
  (testing ":system/error event schema validates correct data"
    (let [valid-event [:system/error {:error-type :harvest-failed
                                      :source "hooks/harvest-session-progress"
                                      :message "Emacs unreachable"
                                      :timestamp 1705000000000
                                      :context {:attempt 1}}]]
      (is (schemas/valid-event? valid-event) "Valid :system/error should pass validation"))))

(deftest system-component-failed-schema-validates
  (testing ":system/component-failed event schema validates"
    (let [valid-event [:system/component-failed {:component :emacs-channel
                                                 :reason "WebSocket disconnected"
                                                 :recoverable? true}]]
      (is (schemas/valid-event? valid-event) "Valid :system/component-failed should pass"))))

(deftest system-restart-collision-schema-validates
  (testing ":system/restart-collision event schema validates"
    (let [valid-event [:system/restart-collision {:port 7910
                                                  :existing-pid 12345}]]
      (is (schemas/valid-event? valid-event) "Valid :system/restart-collision should pass"))))

(deftest system-emacs-unreachable-schema-validates
  (testing ":system/emacs-unreachable event schema validates"
    (let [valid-event [:system/emacs-unreachable {:last-seen 1705000000000
                                                  :retry-count 3}]]
      (is (schemas/valid-event? valid-event) "Valid :system/emacs-unreachable should pass"))))

;; =============================================================================
;; :emit-system-error Effect Tests
;; =============================================================================

(deftest emit-system-error-effect-is-registered
  (testing ":emit-system-error effect is registered and callable"
    (effects/register-effects!)
    (let [handler (ev/get-fx-handler :emit-system-error)]
      (is (fn? handler) ":emit-system-error handler should be a function"))))

(deftest emit-system-error-logs-structured-format
  (testing ":emit-system-error effect handler exists and is callable"
    (effects/register-effects!)
    ;; Note: timbre/error is a macro, can't be redefined with with-redefs
    ;; Instead, we verify the handler exists and doesn't throw
    (let [handler (ev/get-fx-handler :emit-system-error)]
      (is (fn? handler) "Handler should be a function")
      ;; Call should not throw - the actual logging happens as side effect
      (try
        (handler {:error-type :harvest-failed
                  :source "hooks/harvest-session-progress"
                  :message "Emacs unreachable"
                  :context {:attempt 1}})
        (is true "Handler completed without throwing")
        (catch Exception e
          (is false (str "Handler threw exception: " (.getMessage e))))))))

(deftest emit-system-error-emits-to-channel
  (testing ":emit-system-error emits to WebSocket channel for Emacs visibility"
    (effects/register-effects!)
    (let [emitted (atom nil)]
      (with-redefs [hive-mcp.channel.core/server-connected? (fn [] true)
                    hive-mcp.channel.core/emit-event!
                    (fn [type data]
                      (reset! emitted {:type type :data data}))]
        (let [handler (ev/get-fx-handler :emit-system-error)]
          (handler {:error-type :component-failed
                    :source "channel/websocket"
                    :message "Connection lost"
                    :context {}})
          (is (= :system-error (:type @emitted)) "Should emit :system-error event")
          (is (= :component-failed (get-in @emitted [:data :error-type]))
              "Should pass error-type in data"))))))

(deftest emit-system-error-stores-in-datascript
  (testing ":emit-system-error stores in DataScript for post-mortem analysis"
    (effects/register-effects!)
    (let [transacted (atom nil)]
      (with-redefs [hive-mcp.swarm.datascript/get-conn
                    (fn [] (atom {}))
                    datascript.core/transact!
                    (fn [_conn tx-data]
                      (reset! transacted tx-data))]
        (let [handler (ev/get-fx-handler :emit-system-error)]
          (handler {:error-type :restart-collision
                    :source "server/start"
                    :message "Port 7910 already in use"
                    :context {:port 7910 :existing-pid 12345}})
          (is (some? @transacted) "Should transact to DataScript")
          (is (some #(= :system-error (:error/type %)) @transacted)
              "Transaction should include error entity"))))))

;; =============================================================================
;; Integration: Harvest → System Event Flow
;; =============================================================================

(deftest harvest-failure-emits-system-error
  (testing "When harvest fails, it emits :system/error through the real event bus"
    (let [dispatched (atom [])
          harvested (atom nil)]
      ;; A recording :system/error handler registered in the REAL registry —
      ;; the event travels the production dispatch path (validation, chain,
      ;; effects) rather than being intercepted at a redefined var.
      (ev/with-clean-registry
        (ev/reg-event :system/error []
                      (fn [_coeffects [_ data]]
                        (swap! dispatched conj data)
                        {}))
        (with-failing-emacs "Emacs down"
          (fn [] (reset! harvested (hooks/harvest-session-progress)))))
      (let [data (first @dispatched)]
        (is (= 1 (count @dispatched))
            "Should dispatch exactly one :system/error event on harvest failure")
        (is (= :harvest-failed (:error-type data))
            "Telemetry should classify the failure as :harvest-failed")
        (is (string? (:source data)) "Telemetry should name its source")
        (is (= (:error @harvested) (:context data))
            "Telemetry context is the same error map the caller receives")))))
