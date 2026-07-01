(ns hive-mcp.system.shutdown-without-agent-test
  "Integration test that confirms the plan's graceful-degradation claim:
   with hive-agent NOT on the classpath, the JVM shutdown sequence still
   fires and does not throw — only hive-agent-owned hooks are absent.

   We cannot truly remove hive-agent from the classpath in-process, so
   we simulate the scenario via **isolation**: snapshot the registry,
   reset to {}, register ONLY the in-core hooks as fakes, then invoke
   `run-shutdown-sequence!` directly.

   Cases covered:
     1. Empty registry — pure absence of every addon (incl. hive-agent).
     2. hive-mcp-core-only hooks — 3 fakes for olympus-ws/stop,
        coordinator/mark-terminated, session-end/hooks.
     3. registry-snapshot observability over the core-only set.
     4. Addon-throws rescue — fake \"lings/kill-all\" throws, subsequent
        core hook still runs.
     5. Registry-leak canary — fixture's pre-state is preserved.

   Invariants held:
     - Never mutates the real production registry across the test
       boundary (convention 20260122235103-7151cc29).
     - Never calls `register-shutdown-hook!` — that installs a real JVM
       shutdown hook which cannot be removed in-process.
     - Exercises `run-shutdown-sequence!` directly."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.protocols.lifecycle :as proto]
            [hive-mcp.server.lifecycle :as lc]
            [hive-mcp.system.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Isolation fixture — snapshot/restore the real registry
;; =============================================================================

(def ^:dynamic *saved-registry* nil)

(use-fixtures :each
  (fn [f]
    (let [saved (reg/capture-all)]
      (binding [*saved-registry* saved]
        (try
          (reg/reset-all!)
          (f)
          (finally
            (reg/restore-all! saved)))))))

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- fake-hook
  "Build an IShutdownHook whose shutdown! side-effect is `body-fn`
   (a 0-arity fn). Used to simulate in-core hooks without touching the
   production code."
  [name priority body-fn]
  (reify proto/IShutdownHook
    (shutdown-name [_] name)
    (shutdown-priority [_] priority)
    (shutdown! [_ _] (body-fn))))

;; Names that hive-mcp-core owns (must appear when only-core is registered).
(def core-hook-names
  #{"olympus-ws/stop"
    "coordinator/mark-terminated"
    "session-end/hooks"})

;; Names that hive-agent owns (must NOT appear when hive-agent is absent).
(def agent-hook-names
  #{"lings/kill-all"
    "headless/watchdog"})

;; =============================================================================
;; Case 1 — Empty registry (pure absence of every addon)
;; =============================================================================

(deftest empty-registry-shutdown-is-safe-noop
  (testing "With NOTHING registered, run-shutdown-sequence! returns cleanly"
    (is (empty? (reg/registered-shutdown-hooks))
        "Fixture must start the test with an empty registry")
    (let [result (lc/run-shutdown-sequence!
                  {:reason :test-teardown :timeout-ms 100})]
      (is (= 0 (:ran result))
          "No hooks registered, so :ran must be 0")
      (is (= [] (:errors result))
          "Errors must be an empty vector, never nil"))))

;; =============================================================================
;; Case 2 — hive-mcp-core-only hooks (hive-agent absent)
;; =============================================================================

(deftest core-only-hooks-run-without-hive-agent
  (testing "With only in-core hooks registered, all run; agent hooks absent"
    (let [log (atom [])
          record! (fn [id] (fn [] (swap! log conj id)))]
      (reg/register-shutdown!
       (fake-hook "olympus-ws/stop" 50 (record! :olympus-ws/stop)))
      (reg/register-shutdown!
       (fake-hook "coordinator/mark-terminated" 400
                  (record! :coordinator/mark-terminated)))
      (reg/register-shutdown!
       (fake-hook "session-end/hooks" 450 (record! :session-end/hooks)))
      (let [result (lc/run-shutdown-sequence!
                    {:reason :test-teardown :timeout-ms 1000})
            registered-names (->> (reg/registered-shutdown-hooks)
                                  (map proto/shutdown-name)
                                  set)]
        (is (= 3 (:ran result))
            "All three in-core hooks must run")
        (is (= [] (:errors result))
            "No errors expected from fake in-core hooks")
        (is (= [:olympus-ws/stop
                :coordinator/mark-terminated
                :session-end/hooks]
               @log)
            "Hooks must run in ascending priority order")
        (is (= core-hook-names registered-names)
            "Only in-core hook names must be present")
        (doseq [absent agent-hook-names]
          (is (not (contains? registered-names absent))
              (str "hive-agent-owned hook '" absent "' must be absent")))))))

;; =============================================================================
;; Case 3 — registry-snapshot observability
;; =============================================================================

(deftest registry-snapshot-reflects-core-only
  (testing "registry-snapshot returns only in-core names when hive-agent absent"
    (reg/register-shutdown!
     (fake-hook "olympus-ws/stop" 50 (constantly :ok)))
    (reg/register-shutdown!
     (fake-hook "coordinator/mark-terminated" 400 (constantly :ok)))
    (reg/register-shutdown!
     (fake-hook "session-end/hooks" 450 (constantly :ok)))
    (let [snap (reg/registry-snapshot)
          shutdown-names (set (map first (:shutdown snap)))]
      (is (map? snap) "registry-snapshot returns a map")
      (is (contains? snap :shutdown) "snapshot has :shutdown key")
      (is (contains? snap :sweeps) "snapshot has :sweeps key")
      (is (contains? snap :resources) "snapshot has :resources key")
      (is (= core-hook-names shutdown-names)
          "Snapshot must list exactly the in-core names")
      (doseq [absent agent-hook-names]
        (is (not (contains? shutdown-names absent))
            (str "Snapshot must NOT list hive-agent hook '" absent "'")))
      (is (= [["olympus-ws/stop" 50]
              ["coordinator/mark-terminated" 400]
              ["session-end/hooks" 450]]
             (:shutdown snap))
          "Snapshot :shutdown is [name priority] pairs in priority order"))))

;; =============================================================================
;; Case 4 — Addon-throws graceful degradation
;; =============================================================================
;;
;; This is a lightweight reuse of the catch-Throwable semantics already
;; proven in registry_property_test.clj (E1/E2). It is intentionally
;; retained here because it exercises the exact failure mode the plan's
;; graceful-degradation claim cares about: a hive-agent-owned hook
;; present in the registry but failing at shutdown-time must NOT block
;; the in-core hooks that follow. The sibling property test proves the
;; general rescue property; this one pins the core-vs-addon name split.

(deftest addon-throws-does-not-block-core-hooks
  (testing "A thrower registered as hive-agent-owned does not block session-end"
    (let [after-ran? (atom false)]
      ;; Pretend hive-agent IS loaded but its hook blows up on shutdown.
      (reg/register-shutdown!
       (fake-hook "lings/kill-all" 100
                  (fn [] (throw (ex-info "hive-agent missing" {})))))
      ;; An in-core hook scheduled after the agent hook.
      (reg/register-shutdown!
       (fake-hook "session-end/hooks" 450
                  (fn [] (reset! after-ran? true))))
      (let [result (lc/run-shutdown-sequence!
                    {:reason :test-teardown :timeout-ms 1000})]
        (is (true? @after-ran?)
            "In-core session-end hook MUST run even after addon throws")
        (is (= 2 (:ran result))
            "Both hooks counted in :ran (thrower + subsequent)")
        (is (some #(and (= "lings/kill-all" (:name %))
                        (instance? clojure.lang.ExceptionInfo (:error %)))
                  (:errors result))
            ":errors must carry the addon thrower by name with its Throwable")))))

;; =============================================================================
;; Case 5 — Registry-leak canary
;; =============================================================================

(deftest fixture-preserves-real-registry
  (testing "The isolation fixture's saved registry equals the production one"
    ;; Mutate within the test: register a fake, then assert at test-body end
    ;; that the fixture's snapshot is still intact. The fixture's finally-clause
    ;; will restore it after f returns.
    (reg/register-shutdown!
     (fake-hook "transient-test-hook" 10 (constantly :ok)))
    (is (= 1 (count (reg/registered-shutdown-hooks)))
        "Inside the test body we see our own fake")
    (is (map? *saved-registry*)
        "Fixture must expose the pre-test snapshot via *saved-registry*")
    (is (not (contains? (:shutdown *saved-registry*) "transient-test-hook"))
        "Saved snapshot must be from BEFORE this test's registration")))
