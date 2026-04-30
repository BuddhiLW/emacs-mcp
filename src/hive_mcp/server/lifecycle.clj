(ns hive-mcp.server.lifecycle
  "Server lifecycle: hooks, shutdown, configuration.

   Bounded context: Server start/stop/reload orchestration.

   Manages:
   - Global hooks registry (event-driven workflows)
   - JVM shutdown hooks (auto-wrap, coordinator cleanup)
   - Project configuration (.hive-project.edn)"
  (:require [hive-mcp.hooks.core :as hooks]
            [hive-mcp.crystal.hooks :as crystal-hooks]
            [hive-mcp.dns.result :as result]
            [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.swarm.sync :as sync]
            [hive-mcp.system.registry :as reg]
            [taoensso.timbre :as log]
            [clojure.edn :as edn]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Hooks Registry Access
;; =============================================================================

(defn get-hooks-registry
  "Get the global hooks registry for external registration.
   Takes the hooks-registry-atom as parameter for decoupling."
  [hooks-registry-atom]
  @hooks-registry-atom)

;; =============================================================================
;; Session End / Shutdown
;; =============================================================================

(defn trigger-session-end!
  "Trigger session-end hooks for auto-wrap.
   Called by the SessionEndHooks IShutdownHook impl during orchestrated
   shutdown (and, historically, directly by the JVM shutdown hook)."

  [hooks-registry-atom reason]
  (log/info "Triggering session-end hooks:" reason)
  (when-let [registry @hooks-registry-atom]
    (result/rescue nil
                   (let [ctx {:reason reason
                              :session (System/currentTimeMillis)
                              :triggered-by "jvm-shutdown"}
                         results (hooks/trigger-hooks registry :session-end ctx)]
                     (log/info "Session-end hooks completed:" (count results) "handlers executed")
                     results))))

(defn run-shutdown-sequence!
  "Run all registered IShutdownHook impls in priority order.
   Each impl gets a budget (default 5000ms). Exceptions rescued per-impl
   so one failure does not block subsequent hooks.

   Params:
     ctx — {:reason :jvm-shutdown | :repl | ...
            :timeout-ms int (default 5000, per-impl)
            :coordinator-id string (optional)
            :hooks-registry-atom atom (optional)}
   Returns: {:ran N :errors [{:name :error ex}]}"
  [ctx]
  (let [hooks      (reg/registered-shutdown-hooks)
        timeout-ms (or (:timeout-ms ctx) 5000)
        results    (atom {:ran 0 :errors []})]
    (log/info "Shutdown sequence starting"
              {:hook-count (count hooks) :reason (:reason ctx)})
    (doseq [impl hooks]
      (let [hname    (lifecycle/shutdown-name impl)
            priority (lifecycle/shutdown-priority impl)
            fut      (future
                       (try
                         (lifecycle/shutdown! impl ctx)
                         :ok
                         (catch Throwable t
                           (swap! results update :errors conj
                                  {:name hname :error t})
                           :err)))]
        (log/info "shutdown:" hname {:priority priority})
        (let [outcome (deref fut timeout-ms :timeout)]
          (when (= outcome :timeout)
            (log/warn "shutdown timeout" {:name hname :timeout-ms timeout-ms})
            (swap! results update :errors conj
                   {:name hname :error :timeout})))
        (swap! results update :ran inc)))
    (log/info "Shutdown sequence finished" @results)
    @results))

(defn register-shutdown-hook!
  "Register JVM shutdown hook that runs the registry-driven shutdown
   sequence.

   Only registers once. Safe to call multiple times.

   Parameters:
     shutdown-hook-registered? - atom tracking registration state
     coordinator-id-atom       - atom with coordinator project-id
     hooks-registry-atom       - atom with hooks registry"
  [shutdown-hook-registered? coordinator-id-atom hooks-registry-atom]
  (when-not @shutdown-hook-registered?
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      (fn []
        (log/info "JVM shutdown detected - running shutdown sequence")
        (run-shutdown-sequence!
         {:reason              :jvm-shutdown
          :timeout-ms          5000
          :coordinator-id      @coordinator-id-atom
          :hooks-registry-atom hooks-registry-atom}))))
    (reset! shutdown-hook-registered? true)
    (log/info "JVM shutdown hook registered (registry-driven)")))

;; =============================================================================
;; Project Configuration
;; =============================================================================

(defn read-project-config
  "Read .hive-project.edn config.
   Returns {:watch-dirs [...] :hot-reload bool} or nil.
   :hot-reload defaults to true for backward compatibility."
  []
  (result/rescue nil
                 (let [project-file (java.io.File. ".hive-project.edn")]
                   (when (.exists project-file)
                     (let [config (edn/read-string (slurp project-file))]
                       {:watch-dirs (:watch-dirs config)
                        :hot-reload (get config :hot-reload true)})))))

;; =============================================================================
;; Hooks Initialization
;; =============================================================================

(defn init-hooks!
  "Initialize the hooks system and register crystal hooks.

   Creates global registry, registers crystal hooks (auto-wrap),
   and sets up JVM shutdown hook.

   Should be called early in server startup.

   Parameters:
     hooks-registry-atom       - atom to store the registry
     shutdown-hook-registered? - atom tracking shutdown hook state
     coordinator-id-atom       - atom with coordinator project-id"
  [hooks-registry-atom shutdown-hook-registered? coordinator-id-atom]
  (when-not @hooks-registry-atom
    (let [registry (hooks/create-registry)]
      (reset! hooks-registry-atom registry)
      (log/info "Global hooks registry created")
      ;; Inject registry into sync module for Layer 4 hook wiring
      ;; This enables architectural guarantee of synthetic shouts on task completion
      (sync/set-hooks-registry! registry)
      ;; Register crystal hooks (includes auto-wrap on session-end)
      (crystal-hooks/register-hooks! registry)
      ;; Register JVM shutdown hook to trigger session-end
      (register-shutdown-hook! shutdown-hook-registered? coordinator-id-atom hooks-registry-atom)
      {:registry registry
       :hooks-registered true})))
