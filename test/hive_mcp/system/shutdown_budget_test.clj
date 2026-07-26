(ns hive-mcp.system.shutdown-budget-test
  "Per-hook shutdown budgets: a hook may declare its own wall-clock budget
   via IShutdownBudget instead of inheriting the sequence default.

   Cases covered:
     B1 no IShutdownBudget      — hook inherits (:timeout-ms ctx)
     B2 declared budget wins    — slow hook completes past the default
     B3 non-positive declared   — falls back to the sequence default
     B4 effective budget in ctx — impl receives its own budget as :timeout-ms
     B5 session-end regression  — SessionEndHooks declares room for the wrap

   Isolation: per-test fixture captures/restores the registry
   (convention 20260122235103-7151cc29). Never calls
   `register-shutdown-hook!` — that installs a real JVM shutdown hook
   which cannot be removed in-process."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.protocols.lifecycle :as proto]
            [hive-mcp.server.lifecycle :as lc]
            [hive-mcp.system.registry :as reg]
            [hive-mcp.system.shutdown.in-core :as in-core]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Isolation fixture — snapshot/restore the real registry
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (let [saved (reg/capture-all)]
      (try
        (reg/reset-all!)
        (f)
        (finally
          (reg/restore-all! saved))))))

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- plain-hook
  "IShutdownHook with no declared budget. `body-fn` receives the shutdown ctx."
  [name priority body-fn]
  (reify proto/IShutdownHook
    (shutdown-priority [_] priority)
    (shutdown-name     [_] name)
    (shutdown!         [_ ctx] (body-fn ctx))))

(defn- budgeted-hook
  "IShutdownHook declaring `budget-ms` via IShutdownBudget. `body-fn`
   receives the shutdown ctx."
  [name priority budget-ms body-fn]
  (reify proto/IShutdownHook
    (shutdown-priority [_] priority)
    (shutdown-name     [_] name)
    (shutdown!         [_ ctx] (body-fn ctx))
    proto/IShutdownBudget
    (shutdown-timeout-ms [_] budget-ms)))

(defn- timed-out?
  "True when `result` carries a {:name name :error :timeout} entry."
  [result name]
  (boolean (some #(and (= name (:name %)) (= :timeout (:error %)))
                 (:errors result))))

;; =============================================================================
;; B1 — No IShutdownBudget: inherit the sequence default
;; =============================================================================

(deftest hook-without-budget-inherits-sequence-default
  (testing "A hook not extending IShutdownBudget is bound by (:timeout-ms ctx)"
    (reg/register-shutdown!
     (plain-hook "slow-plain" 10 (fn [_] (Thread/sleep 400) :never-returns)))
    (let [result (lc/run-shutdown-sequence! {:reason :test-teardown :timeout-ms 50})]
      (is (timed-out? result "slow-plain")
          "Sequence default must still bound hooks that declare nothing")
      (is (= 1 (:ran result))))))

;; =============================================================================
;; B2 — Declared budget overrides the sequence default
;; =============================================================================

(deftest declared-budget-overrides-sequence-default
  (testing "A hook declaring a larger budget runs past the sequence default"
    (let [done? (atom false)]
      (reg/register-shutdown!
       (budgeted-hook "slow-budgeted" 10 5000
                      (fn [_] (Thread/sleep 400) (reset! done? true))))
      (let [result (lc/run-shutdown-sequence! {:reason :test-teardown :timeout-ms 50})]
        (is (true? @done?)
            "Hook must complete under its own 5000ms budget, not the 50ms default")
        (is (not (timed-out? result "slow-budgeted")))
        (is (= [] (:errors result)))))))

;; =============================================================================
;; B3 — Non-positive declared budget falls back to the default
;; =============================================================================

(deftest non-positive-declared-budget-falls-back-to-default
  (testing "A declared budget of 0 is ignored in favour of the sequence default"
    (reg/register-shutdown!
     (budgeted-hook "zero-budget" 10 0 (fn [_] (Thread/sleep 400) :never-returns)))
    (let [result (lc/run-shutdown-sequence! {:reason :test-teardown :timeout-ms 50})]
      (is (timed-out? result "zero-budget")
          "Zero must not disable the bound"))))

;; =============================================================================
;; B4 — The effective budget reaches the impl as :timeout-ms
;; =============================================================================

(deftest effective-budget-reaches-the-impl
  (testing ":timeout-ms handed to shutdown! is that hook's own budget"
    (let [budgeted-ctx (atom nil)
          plain-ctx    (atom nil)]
      (reg/register-shutdown!
       (budgeted-hook "reports-budget" 10 4242
                      (fn [ctx] (reset! budgeted-ctx (:timeout-ms ctx)))))
      (reg/register-shutdown!
       (plain-hook "reports-default" 20
                   (fn [ctx] (reset! plain-ctx (:timeout-ms ctx)))))
      (lc/run-shutdown-sequence! {:reason :test-teardown :timeout-ms 100})
      (is (= 4242 @budgeted-ctx) "Budgeted hook sees its own budget")
      (is (= 100 @plain-ctx) "Plain hook still sees the sequence default"))))

;; =============================================================================
;; B5 — Regression: session-end wrap must outlive the 5s default
;; =============================================================================

(deftest session-end-hook-declares-room-for-the-wrap
  (testing "SessionEndHooks declares a budget covering harvest + LLM synthesis"
    (let [impl (in-core/->SessionEndHooks (atom nil))]
      (is (satisfies? proto/IShutdownBudget impl)
          "SessionEndHooks must declare its own budget")
      (is (>= (proto/shutdown-timeout-ms impl) 40000)
          "session-end wrap must outlive the 5000ms sequence default"))))
