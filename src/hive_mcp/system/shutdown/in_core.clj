(ns hive-mcp.system.shutdown.in-core
  "In-core IShutdownHook impls for concrete shutdown work that used to live
   as transitional direct calls in server/lifecycle.clj.

   Each record is registered via `register-in-core-shutdown!` during
   :hive/hooks init, so orchestrated shutdown (run-shutdown-sequence!) can
   dispatch them in priority order alongside addon-registered hooks.

   Priority bands (see hive-mcp.protocols.lifecycle):
     0-99   external services (WS)   → OlympusWsShutdown = 10
     400+   final bookkeeping        → CoordinatorMarkTerminated = 450
                                     → SessionEndHooks           = 490

   External dependencies (olympus-ws/stop!, mark-coordinator-terminated!)
   are resolved lazily via requiring-resolve to match the transitional
   pattern and avoid load-order coupling. trigger-session-end! lives in
   hive-mcp.server.lifecycle (same project) and is required directly."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.server.lifecycle :as server-lifecycle]
            [hive-mcp.system.registry :as reg]
            [taoensso.timbre :as log]))

;; =============================================================================
;; OlympusWsShutdown — priority 10 (external services band)
;; =============================================================================

(defrecord OlympusWsShutdown []
  lifecycle/IShutdownHook
  (shutdown-priority [_] 10)
  (shutdown-name     [_] "olympus-ws/stop")
  (shutdown!         [_ _ctx]
    (result/rescue nil
                   (let [stop! (requiring-resolve 'hive-mcp.transport.olympus/stop!)]
                     (stop!)
                     (log/info "Olympus WebSocket server stopped")))))

;; =============================================================================
;; CoordinatorMarkTerminated — priority 450 (final bookkeeping band)
;; =============================================================================

(defrecord CoordinatorMarkTerminated [coordinator-id-atom]
  lifecycle/IShutdownHook
  (shutdown-priority [_] 450)
  (shutdown-name     [_] "coordinator/mark-terminated")
  (shutdown!         [_ _ctx]
    (when-let [coord-id @coordinator-id-atom]
      (result/rescue nil
                     (let [mark-terminated! (requiring-resolve
                                             'hive-mcp.swarm.datascript/mark-coordinator-terminated!)]
                       (mark-terminated! coord-id)
                       (log/info "Coordinator marked terminated:" coord-id))))))

(def ^:private default-session-end-budget-ms
  "Fallback wall-clock budget for the session-end wrap, in milliseconds."
  60000)

(defn- session-end-budget-ms
  "Wall-clock budget for the session-end wrap: config
   [:shutdown :session-end-timeout-ms] when that is a positive number,
   else `default-session-end-budget-ms`."
  []
  (or (result/rescue nil
        (when-let [get-in-config (requiring-resolve 'hive-mcp.config.core/get-in-config)]
          (let [ms (get-in-config [:shutdown :session-end-timeout-ms])]
            (when (and (number? ms) (pos? ms)) (long ms)))))
      default-session-end-budget-ms))

;; =============================================================================
;; SessionEndHooks — priority 490 (final bookkeeping band)
;; =============================================================================

(defrecord SessionEndHooks [hooks-registry-atom]
  lifecycle/IShutdownHook
  (shutdown-priority [_] 490)
  (shutdown-name     [_] "session-end/hooks")
  (shutdown!         [_ ctx]
    (server-lifecycle/trigger-session-end! hooks-registry-atom (:reason ctx)))

  lifecycle/IShutdownBudget
  (shutdown-timeout-ms [_] (session-end-budget-ms)))

;; =============================================================================
;; Factory — register all three at init
;; =============================================================================

(defn register-in-core-shutdown!
  "Instantiate and register the three in-core IShutdownHook impls.
   Called once during :hive/hooks init (layer1) after the hooks registry
   is ready. Idempotent: re-registration overwrites by shutdown-name."
  [coordinator-id-atom hooks-registry-atom]
  (reg/register-shutdown! (->OlympusWsShutdown))
  (reg/register-shutdown! (->CoordinatorMarkTerminated coordinator-id-atom))
  (reg/register-shutdown! (->SessionEndHooks hooks-registry-atom))
  :registered)
