(ns user
  "Development namespace with Integrant lifecycle and REPL utilities.

   Loaded automatically via :dev alias. Provides:
   - Integrant system lifecycle: (go), (halt), (reset), (system)
   - Spec instrumentation toggle
   - Namespace reloading
   - Test runners

   Profile selection: HIVE_PROFILE env var or :desktop default.
   System config: resources/hive/system.edn + profile overlay."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.repl :refer [doc source]]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.trace :as trace]
            [clojure.java.io :as io]
            [clj-reload.core :as reload]
            [integrant.core :as ig]
            [meta-merge.core :refer [meta-merge]]))

;; ============================================================
;; Integrant System Lifecycle
;; ============================================================

(defonce ^:private system-atom (atom nil))

(defn- resolve-profile
  "Resolve profile keyword from HIVE_PROFILE env var or default :desktop."
  []
  (keyword (or (System/getenv "HIVE_PROFILE") "desktop")))

(defn- read-base-config
  "Read and parse the base system.edn config with Integrant readers."
  []
  (if-let [r (io/resource "hive/system.edn")]
    (ig/read-string (slurp r))
    (throw (ex-info "Base system.edn not found on classpath"
                    {:resource "hive/system.edn"
                     :hint "Ensure resources/ is on :paths"}))))

(defn- read-profile-config
  "Read profile overlay EDN. Returns {} if profile file not found."
  [profile]
  (let [path (str "hive/profiles/" (name profile) ".edn")]
    (if-let [r (io/resource path)]
      (ig/read-string (slurp r))
      (do (println "WARN: profile" path "not found, using base config only")
          {}))))

(defn- load-system-config
  "Load base system.edn merged with profile overlay via meta-merge.
   Profile nil keys are removed (Integrant convention)."
  ([] (load-system-config (resolve-profile)))
  ([profile]
   (let [base    (read-base-config)
         overlay (read-profile-config profile)
         merged  (meta-merge base overlay)]
     ;; Remove keys set to nil by profile (Integrant convention for exclusion)
     (->> merged
          (remove (fn [[_ v]] (nil? v)))
          (into {})))))

(defn system
  "Return the current running Integrant system map, or nil."
  []
  @system-atom)

(defn go
  "Initialize the Integrant system from system.edn + profile.
   Profile defaults to HIVE_PROFILE env var or :desktop.
   Idempotent — refuses to start if system already running."
  ([] (go (resolve-profile)))
  ([profile]
   (when @system-atom
     (throw (ex-info "System already running. Call (halt) first or (reset) to restart."
                     {:profile profile})))
   (println "Integrant: initializing system with profile" profile "...")
   (let [config (load-system-config profile)
         sys    (ig/init config)]
     (reset! system-atom sys)
     (println "Integrant: system GO." (count sys) "keys initialized.")
     :initiated)))

(defn halt
  "Halt the running Integrant system. Safe to call when no system running."
  []
  (when-let [sys @system-atom]
    (println "Integrant: halting system...")
    (ig/halt! sys)
    (reset! system-atom nil)
    (println "Integrant: system halted.")
    :halted))

(defn reset
  "Halt system, reload changed namespaces via clj-reload, re-init system.
   The full REPL-driven development cycle."
  []
  (let [profile (resolve-profile)]
    (halt)
    (println "Integrant: reloading changed namespaces...")
    (reload/reload)
    (println "Integrant: namespaces reloaded. Re-initializing...")
    (go profile)))

(defn clear
  "Clear system state without halting (for recovery from broken state).
   Use when (halt) throws due to corrupted system."
  []
  (reset! system-atom nil)
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
;; WebSocket Channel (for bb-mcp bridge)
;; ============================================================

(defn start-websocket!
  "Start the websocket channel server for bb-mcp communication.
   Default port 9999, configurable via HIVE_WS_PORT env var."
  ([] (start-websocket! (parse-long (or (System/getenv "HIVE_WS_PORT") "9999"))))
  ([port]
   (require '[hive-mcp.channel.websocket :as ws])
   ((resolve 'hive-mcp.channel.websocket/start!) {:port port})
   (println "WebSocket channel started on port" port)))

(defn stop-websocket!
  "Stop the websocket channel server."
  []
  (require '[hive-mcp.channel.websocket :as ws])
  ((resolve 'hive-mcp.channel.websocket/stop!))
  (println "WebSocket channel stopped"))

;; ============================================================
;; Legacy nREPL Init (pre-Integrant fallback)
;; ============================================================

(defn nrepl-init!
  "Legacy init: embedding + memory + extensions + websocket.
   Prefer (go) for full Integrant lifecycle. This remains for
   backward compat when system.edn is not yet wired."
  []
  (require 'hive-mcp.server.init)
  ((resolve 'hive-mcp.server.init/nrepl-init!))
  (println "nrepl-init! complete.")
  (start-websocket!))

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
(println "  (nrepl-init!)        - Legacy init (pre-Integrant fallback)")
(println "================================\n")

;; Auto-init: legacy path for backward compat.
;; Once T8 lands (Integrant-wired start!/stop!), replace with (go).
(future
  (try
    (Thread/sleep 2000)
    (if (io/resource "hive/system.edn")
      (do (println "System.edn found — run (go) to start Integrant lifecycle.")
          (println "Or (nrepl-init!) for legacy init."))
      (do (nrepl-init!)
          (println "Auto-init via nrepl-init! (system.edn not yet available).")))
    (catch Exception e
      (println "Auto-init failed (non-fatal):" (.getMessage e)))))
