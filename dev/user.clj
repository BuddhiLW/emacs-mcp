(ns user
  "Development namespace with Integrant lifecycle and REPL utilities.

   Loaded automatically via :dev alias. Provides:
   - Integrant system lifecycle: (go), (halt), (reset), (system)
   - Spec instrumentation toggle
   - Namespace reloading
   - Test runners

   Profile selection: HIVE_PROFILE env var or :desktop default.
   System config: resources/hive/system.edn + profile overlay.

   Delegates lifecycle to hive-mcp.server.core (start!/stop!/reset!)."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.repl :refer [doc source]]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.trace :as trace]
            [clojure.java.io :as io]
            [clj-reload.core :as reload]
            [hive-mcp.server.core :as core]))

;; ============================================================
;; Integrant System Lifecycle — delegates to server.core
;; ============================================================

(defn system
  "Return the current running Integrant system map, or nil."
  []
  @core/system)

(defn go
  "Initialize the Integrant system from system.edn + profile.
   Profile defaults to HIVE_PROFILE env var or :desktop.
   Idempotent — refuses to start if system already running."
  ([] (core/start!))
  ([profile]
   (core/start! :profile profile)))

(defn halt
  "Halt the running Integrant system. Safe to call when no system running."
  []
  (core/stop!))

(defn reset
  "Halt system, reload changed namespaces via clj-reload, re-init system.
   The full REPL-driven development cycle."
  []
  (core/reset!))

(defn clear
  "Clear system state without halting (for recovery from broken state).
   Use when (halt) throws due to corrupted system."
  []
  (reset! core/system nil)
  (println "System atom cleared.")
  :cleared)

;; ============================================================
;; Spec Instrumentation
;; ============================================================

(defonce ^:private instrumented? (atom false))

(defn instrument-specs!
  "Enable spec checking on all fdef'd functions.
   Validates args on every call - useful for catching contract violations."
  []
  (let [instrumented (stest/instrument)]
    (reset! instrumented? true)
    (println "Instrumented" (count instrumented) "functions")
    instrumented))

(defn unstrument-specs!
  "Disable spec checking for performance."
  []
  (stest/unstrument)
  (reset! instrumented? false)
  (println "Specs unstrumented"))

(defn check-specs
  "Run generative tests on specified namespace.
   Uses test.check for property-based testing."
  ([ns-sym]
   (require ns-sym :reload)
   (stest/check (stest/enumerate-namespace ns-sym))))

;; ============================================================
;; Namespace Reloading (standalone, without Integrant cycle)
;; ============================================================

(defn reload!
  "Hot-reload changed namespaces using clj-reload."
  []
  (reload/reload))

;; ============================================================
;; Quick Test Runners
;; ============================================================

(defn run-tests
  "Run tests for specified namespace."
  [ns-sym]
  (require 'clojure.test)
  (require ns-sym :reload)
  ((resolve 'clojure.test/run-tests) ns-sym))

(defn run-all-tests
  "Run all tests in test directory."
  []
  (require 'cognitect.test-runner.api)
  ((resolve 'cognitect.test-runner.api/test) {}))

;; ============================================================
;; Debugging Helpers
;; ============================================================

(defn trace-ns
  "Trace all function calls in namespace."
  [ns-sym]
  (trace/trace-ns ns-sym))

(defn untrace-ns
  "Stop tracing namespace."
  [ns-sym]
  (trace/untrace-ns ns-sym))

;; ============================================================
;; Startup Banner
;; ============================================================

(println "\n=== hive-mcp dev environment ===")
(println "Integrant lifecycle:")
(println "  (go)                 - Init system (profile from HIVE_PROFILE or :desktop)")
(println "  (go :k8s-headless)   - Init with specific profile")
(println "  (halt)               - Stop system")
(println "  (reset)              - Halt + reload + go")
(println "  (system)             - Inspect running system")
(println "  (clear)              - Emergency: clear system atom")
(println "Utilities:")
(println "  (instrument-specs!)  - Enable spec validation")
(println "  (unstrument-specs!)  - Disable spec validation")
(println "  (reload!)            - Hot-reload changed files (no system cycle)")
(println "  (run-tests 'ns)      - Run tests for namespace")
(println "================================\n")

;; Auto-init: start Integrant system after nREPL is ready.
;; bb-mcp connects via nREPL and needs server-context-atom populated,
;; which happens inside start! after ig/init completes.
(future
  (try
    (Thread/sleep 2000)
    (if (io/resource "hive/system.edn")
      (do
        (println "Auto-starting Integrant system...")
        (go)
        (println "Integrant system started."))
      (println "WARN: system.edn not found on classpath."))
    (catch Exception e
      (println "Auto-init failed (non-fatal):" (.getMessage e)))))
