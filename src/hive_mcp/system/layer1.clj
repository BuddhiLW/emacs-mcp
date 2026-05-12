(ns hive-mcp.system.layer1
  "Integrant key implementations — Layer 1: guards, hooks, events, coordinator.

   These are the foundation keys (Phase 1-2 of server/core.clj start!).
   No external service dependencies — pure in-process state setup.

   Each init-key wraps existing functions from:
     - server/guards.clj  → mark-coordinator-running!/stopped!
     - server/lifecycle.clj → init-hooks!, register-shutdown-hook!
     - server/init.clj → init-events!, register-coordinator!

   halt-key! reverses init-key (guards down, hooks triggered, coordinator marked)."
  (:require [integrant.core :as ig]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.server.lifecycle :as lifecycle]
            [hive-mcp.server.init :as init]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; :hive/guards — Coordinator protection + delegation enforcement
;; =============================================================================

(defmethod ig/init-key :hive/guards
  [_ config]
  (log/info ":hive/guards init — marking coordinator running, activating delegation guards")
  (guards/mark-coordinator-running!)
  (guards/enable-guards!)
  (when-let [mode (:enforcement-mode config)]
    (guards/set-enforcement-mode! mode))
  {:status :running
   :enforcement-mode (or (:enforcement-mode config) :warn)})

(defmethod ig/halt-key! :hive/guards
  [_ _state]
  (log/info ":hive/guards halt — marking coordinator stopped")
  (guards/mark-coordinator-stopped!)
  (guards/disable-guards!))

;; =============================================================================
;; :hive/hooks — Global hooks registry + crystal hooks + JVM shutdown hook
;; =============================================================================

(defmethod ig/init-key :hive/hooks
  [_ config]
  (log/info ":hive/hooks init — creating hooks registry, registering crystal hooks + shutdown hook")
  (let [hooks-registry-atom       (atom nil)
        shutdown-hook-registered?  (atom false)
        coordinator-id-atom        (atom (:coordinator-id config))]
    ;; Delegate to existing lifecycle/init-hooks! which:
    ;; 1. Creates registry
    ;; 2. Injects into sync module
    ;; 3. Registers crystal hooks (auto-wrap)
    ;; 4. Registers JVM shutdown hook
    (lifecycle/init-hooks! hooks-registry-atom shutdown-hook-registered? coordinator-id-atom)
    ;; Register the three in-core IShutdownHook impls (migrated from the
    ;; transitional tail of run-shutdown-sequence! in task a4). Late-resolved
    ;; to keep the compile-time dep direction from system → addons thin.
    (require 'hive-mcp.system.shutdown.in-core)
    ((resolve 'hive-mcp.system.shutdown.in-core/register-in-core-shutdown!)
     coordinator-id-atom hooks-registry-atom)
    ;; Task c1 DONE: `hive-mcp.system.shutdown.kill-all-lings` moved into
    ;; `hive-agent.lifecycle.kill-all-lings`; hive-agent.init/init! now loads
    ;; that ns which self-registers into the shutdown registry via defonce.
    ;; TRANSITIONAL — task c3 moves this ns into a hive-nats addon and registers via IAddon.init!
    (require 'hive-mcp.nats.lifecycle)
    {:hooks-registry-atom      hooks-registry-atom
     :shutdown-hook-registered? shutdown-hook-registered?
     :coordinator-id-atom      coordinator-id-atom
     :status                   :running}))

(defmethod ig/halt-key! :hive/hooks
  [_ state]
  (log/info ":hive/hooks halt — triggering session-end hooks")
  ;; Trigger session-end for clean shutdown (auto-wrap, etc.)
  ;; The JVM shutdown hook also does this, but explicit halt is cleaner for REPL reset
  (when-let [registry-atom (:hooks-registry-atom state)]
    (when-let [registry @registry-atom]
      (result/rescue nil
        (let [trigger-hooks (requiring-resolve 'hive-mcp.hooks.core/trigger-hooks)]
          (trigger-hooks registry :session-end {:reason "integrant-halt"
                                                :triggered-by "ig/halt-key!"})
          (log/info ":hive/hooks session-end hooks triggered")))
      ;; Clear the registry
      (reset! registry-atom nil))))

;; =============================================================================
;; :hive/events — hive-events system (re-frame inspired event dispatch)
;; =============================================================================

(defmethod ig/init-key :hive/events
  [_ _config]
  (log/info ":hive/events init — initializing hive-events system")
  (init/init-events!)
  {:status :running})

(defmethod ig/halt-key! :hive/events
  [_ _state]
  ;; hive-events is process-global (re-frame style) — no teardown needed.
  ;; Handlers survive across reset, which is fine for single-system-per-JVM.
  (log/info ":hive/events halt — noop (process-global event system)"))

;; =============================================================================
;; :hive/delivery-channels — frontend-agnostic IDeliveryChannel registry
;; =============================================================================
;; Registers every chosen IDeliveryChannel impl UNCONDITIONALLY at startup so
;; headless hosts (NATS off, Emacs off) still get a working delivery surface.
;; Selection: explicit ids set > HIVE_DELIVERY_CHANNELS env > every factory.

(defmethod ig/init-key :hive/delivery-channels
  [_ config]
  (log/info ":hive/delivery-channels init — registering IDeliveryChannel fanout endpoints" config)
  (let [registered (result/rescue nil
                     (init/init-delivery-channels!)
                     (require 'hive-mcp.protocols.delivery-channel)
                     (let [get-fn (resolve 'hive-mcp.protocols.delivery-channel/get-channels)
                           ch-id  (resolve 'hive-mcp.protocols.delivery-channel/channel-id)]
                       (mapv ch-id (get-fn))))]
    {:registered registered
     :status     (if (seq registered) :running :degraded)}))

(defmethod ig/halt-key! :hive/delivery-channels
  [_ _state]
  ;; Channel registry is process-global; clearing it on halt would orphan
  ;; in-flight fanouts. Leave channels registered across reset.
  (log/info ":hive/delivery-channels halt — noop (registry survives reset)"))

;; =============================================================================
;; :hive/coordinator — DataScript registration + hivemind coordinator entry
;; =============================================================================

(defmethod ig/init-key :hive/coordinator
  [_ _config]
  (log/info ":hive/coordinator init — registering coordinator in DataScript + hivemind")
  (let [coordinator-id-atom (atom nil)]
    (init/register-coordinator! coordinator-id-atom)
    {:coordinator-id-atom coordinator-id-atom
     :coordinator-id      @coordinator-id-atom
     :status              :running}))

(defmethod ig/halt-key! :hive/coordinator
  [_ state]
  (log/info ":hive/coordinator halt — marking coordinator terminated in DataScript")
  (when-let [coord-id (:coordinator-id state)]
    (result/rescue nil
      (require 'hive-mcp.swarm.datascript)
      (let [mark-terminated! (resolve 'hive-mcp.swarm.datascript/mark-coordinator-terminated!)]
        (mark-terminated! coord-id)
        (log/info ":hive/coordinator terminated:" coord-id)))))
