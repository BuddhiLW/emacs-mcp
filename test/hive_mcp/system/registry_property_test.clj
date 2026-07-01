(ns hive-mcp.system.registry-property-test
  "Property-based + example tests for hive-mcp.system.registry +
   hive-mcp.server.lifecycle/run-shutdown-sequence!.

   Invariants covered:
     P1 priority ordering   — registered-shutdown-hooks returns sorted (asc)
     P2 idempotent register — re-registering same name overwrites
     E1 rescue on exception — Throwable thrower does not block subsequent hooks
     E2 rescue on AssertionError — catch Throwable, not catch Exception
                                   (axiom 20260227204442-3d0e1f7c)
     E3 budget timeout      — slow hook hits :timeout-ms, :timeout surfaces
                              in :errors, subsequent hooks still run
     E4 empty registry      — returns {:ran 0 :errors []} without throwing

   Isolation: per-test fixture captures/restores the registry via
   reg/capture-all + reg/restore-all!, and reg/reset-all! clears it during the
   test body. The REAL production registry is never mutated across the test
   boundary (convention 20260122235016-2b1e7be5)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.server.lifecycle :as server-lifecycle]
            [hive-mcp.system.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn make-hook
  "Build an IShutdownHook whose shutdown! side-effect is `body-fn` (a 0-arity
   function). `name` is the registry key; `priority` determines execution
   order (asc)."
  [name priority body-fn]
  (reify lifecycle/IShutdownHook
    (shutdown-name [_] name)
    (shutdown-priority [_] priority)
    (shutdown! [_ _] (body-fn))))

;; =============================================================================
;; Isolation fixture — never mutates the real production registry
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
;; Generators
;; =============================================================================

(def gen-priority
  "Priority generator — matches the guidance band 0-500 in
   hive-mcp.protocols.lifecycle IShutdownHook docstring."
  (gen/choose 0 500))

(def gen-hook-name
  "Unique-enough hook names for property tests. We bias toward the same
   namespace prefix so test output is recognizable in logs."
  (gen/fmap #(str "hook-" %) gen/nat))

(def gen-name+priority
  (gen/tuple gen-hook-name gen-priority))

;; =============================================================================
;; P1 — Priority ordering invariant
;; =============================================================================

(defspec priority-ordering-is-ascending 50
  (prop/for-all
   [entries (gen/such-that
             #(= (count %) (count (distinct (map first %))))
             (gen/vector gen-name+priority 1 20)
             50)]
   ;; Fresh isolated registry for each shrink trial. The fixture only runs
   ;; once per deftest, so we reset inside the property too for safety when
   ;; test.check shrinks across many inputs.
   (reg/reset-all!)
   (doseq [[n p] entries]
     (reg/register-shutdown! (make-hook n p (fn [] :ok))))
   (let [priorities (map lifecycle/shutdown-priority
                         (reg/registered-shutdown-hooks))]
     (= priorities (sort priorities)))))

;; =============================================================================
;; P2 — Idempotent registration (second registration wins)
;; =============================================================================

(defspec idempotent-registration-second-wins 50
  (prop/for-all
   [n  gen-hook-name
    p1 gen-priority
    p2 gen-priority]
   (reg/reset-all!)
   (reg/register-shutdown! (make-hook n p1 (fn [] :first)))
   (reg/register-shutdown! (make-hook n p2 (fn [] :second)))
   (let [hooks (reg/registered-shutdown-hooks)]
     (and (= 1 (count hooks))
          (= n (lifecycle/shutdown-name (first hooks)))
          (= p2 (lifecycle/shutdown-priority (first hooks)))))))

;; =============================================================================
;; E1 — Rescue on ExceptionInfo mid-sequence
;; =============================================================================

(deftest rescue-on-exception-info-mid-sequence
  (testing "A thrower in the middle does not block subsequent hooks"
    (let [log (atom [])
          record! (fn [id] (fn [] (swap! log conj id)))]
      (reg/register-shutdown! (make-hook "a" 10 (record! :a)))
      (reg/register-shutdown! (make-hook "b" 20 (record! :b)))
      (reg/register-shutdown! (make-hook "middle" 30
                                         (fn [] (throw (ex-info "boom" {})))))
      (reg/register-shutdown! (make-hook "d" 40 (record! :d)))
      (reg/register-shutdown! (make-hook "e" 50 (record! :e)))
      (let [result (server-lifecycle/run-shutdown-sequence!
                    {:reason :test-teardown :timeout-ms 1000})]
        (is (= 5 (:ran result)) "All five hooks should have been attempted")
        (is (= [:a :b :d :e] @log)
            "Hooks before and after the thrower must have run")
        (is (some #(= "middle" (:name %)) (:errors result))
            "Error list must contain the thrower by name")
        (let [err (first (filter #(= "middle" (:name %)) (:errors result)))]
          (is (instance? clojure.lang.ExceptionInfo (:error err))
              ":error should carry the original ExceptionInfo"))))))

;; =============================================================================
;; E2 — Rescue on AssertionError (Throwable, not Exception)
;; =============================================================================

(deftest rescue-on-assertion-error-throwable-branch
  (testing "AssertionError is caught (Throwable), subsequent hooks run"
    ;; If run-shutdown-sequence! used `catch Exception` instead of
    ;; `catch Throwable`, the AssertionError would escape the future and
    ;; the subsequent hook would NOT run (axiom 20260227204442-3d0e1f7c).
    (let [ran-after? (atom false)]
      (reg/register-shutdown! (make-hook "assert-thrower" 10
                                         (fn [] (throw (AssertionError.
                                                        "assertion-fail")))))
      (reg/register-shutdown! (make-hook "after" 20
                                         (fn [] (reset! ran-after? true))))
      (let [result (server-lifecycle/run-shutdown-sequence!
                    {:reason :test-teardown :timeout-ms 1000})]
        (is (true? @ran-after?)
            "Hook after AssertionError-thrower MUST run — confirms catch Throwable")
        (is (some #(and (= "assert-thrower" (:name %))
                        (instance? AssertionError (:error %)))
                  (:errors result))
            "Error list must capture the AssertionError")
        (is (= 2 (:ran result)))))))

;; =============================================================================
;; E3 — Budget timeout
;; =============================================================================

(deftest budget-timeout-surfaces-and-unblocks-sequence
  (testing "Slow hook is abandoned after :timeout-ms; subsequent hooks run"
    (let [ran-after? (atom false)
          start      (System/currentTimeMillis)]
      (reg/register-shutdown! (make-hook "slow" 10
                                         (fn []
                                           (Thread/sleep 10000)
                                           :never-returns)))
      (reg/register-shutdown! (make-hook "after" 20
                                         (fn [] (reset! ran-after? true))))
      (let [result   (server-lifecycle/run-shutdown-sequence!
                      {:reason :test-teardown :timeout-ms 100})
            elapsed  (- (System/currentTimeMillis) start)]
        (is (true? @ran-after?)
            "Hook after the slow one must run once timeout elapses")
        (is (< elapsed 5000)
            (str "Orchestrator must abandon the slow hook near :timeout-ms "
                 "(observed " elapsed "ms)"))
        (is (some #(and (= "slow" (:name %)) (= :timeout (:error %)))
                  (:errors result))
            "Errors must contain {:name \"slow\" :error :timeout}")
        (is (= 2 (:ran result)))))))

;; =============================================================================
;; E4 — Empty registry is a no-op
;; =============================================================================

(deftest empty-registry-is-noop
  (testing "run-shutdown-sequence! with no registered hooks returns cleanly"
    (is (empty? (reg/registered-shutdown-hooks)))
    (let [result (server-lifecycle/run-shutdown-sequence!
                  {:reason :test-teardown :timeout-ms 100})]
      (is (= 0 (:ran result)))
      (is (= [] (:errors result))
          "Errors must be an empty vector, never nil"))))
