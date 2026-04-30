(ns hive-mcp.events.core
  "Event system facade for hive-mcp.

   Re-exports the public API from focused sub-namespaces so that legacy
   callers can keep `[hive-mcp.events.core :as ev]` while new code can
   require sub-namespaces directly (ISP).

   Sub-namespaces:
   - hive-mcp.events.context   — get/assoc/update {co}effect (pure)
   - hive-mcp.events.metrics   — metrics state, interceptor, configure
   - hive-mcp.events.registry  — handler registry, deregistration, inspection
   - hive-mcp.events.dispatch  — dispatch, execute, do-fx, validate-event, debug

   Re-exported from hive.events (canonical primitives, zero duplication):
   - ->interceptor, enqueue, trim-v
   - reg-fx, reg-cofx, inject-cofx

   Owned by this namespace:
   - init!  — wires hive-mcp built-in coeffects (:now :random
              :agent-context :db-snapshot) and effects (:channel-publish
              :prometheus :log :mcp-response)."
  (:require [hive.events.interceptor :as interceptor]
            [hive.events.fx :as fx]
            [hive.events.cofx :as cofx]
            [hive-mcp.events.context :as ctx]
            [hive-mcp.events.metrics :as mt]
            [hive-mcp.events.registry :as registry]
            [hive-mcp.events.dispatch :as dispatch]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.channel.websocket :as ws]
            [hive-mcp.telemetry.prometheus :as prom]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Re-exports — hive.events primitives
;; =============================================================================

(def ->interceptor
  "Create an interceptor from keyword arguments. Delegated to hive.events.interceptor."
  interceptor/->interceptor)

(def enqueue
  "Add interceptors to the context's queue. Delegated to hive.events.interceptor."
  interceptor/enqueue)

(def trim-v
  "Interceptor that removes the event-id from the event vector.
   Delegated to hive.events.interceptor/trim-v."
  interceptor/trim-v)

(def reg-fx
  "Register an effect handler. Delegated to hive.events.fx."
  fx/reg-fx)

(def reg-cofx
  "Register a coeffect handler. Delegated to hive.events.cofx."
  cofx/reg-cofx)

(def inject-cofx
  "Create an interceptor that injects a coeffect. Delegated to hive.events.cofx."
  cofx/inject-cofx)

(defn get-fx-handler
  "Get a registered effect handler by id. Primarily for testing."
  [id]
  (fx/get-fx id))

(defn get-cofx-handler
  "Get a registered coeffect handler by id. Primarily for testing."
  [id]
  (cofx/get-cofx id))

;; =============================================================================
;; Re-exports — context helpers
;; =============================================================================

(def get-coeffect    ctx/get-coeffect)
(def assoc-coeffect  ctx/assoc-coeffect)
(def update-coeffect ctx/update-coeffect)
(def get-effect      ctx/get-effect)
(def assoc-effect    ctx/assoc-effect)
(def update-effect   ctx/update-effect)

;; =============================================================================
;; Re-exports — metrics
;; =============================================================================

(def metrics            mt/metrics)
(def get-metrics        mt/get-metrics)
(def reset-metrics!     mt/reset-metrics!)
(def configure-metrics! mt/configure-metrics!)

;; =============================================================================
;; Re-exports — registry
;; =============================================================================

(def reg-event               registry/reg-event)
(def append-interceptor!     registry/append-interceptor!)
(def get-interceptors        registry/get-interceptors)
(def handler-registered?     registry/handler-registered?)
(def unreg-event             registry/unreg-event)
(def unreg-fx                registry/unreg-fx)
(def unreg-cofx              registry/unreg-cofx)
(def registered-events       registry/registered-events)
(def registered-effects      registry/registered-effects)
(def registered-coeffects    registry/registered-coeffects)
(def handler-registry-status registry/handler-registry-status)
(def reset-all!              registry/reset-all!)

(defmacro with-clean-registry
  "Re-export of hive-mcp.events.registry/with-clean-registry."
  [& body]
  `(registry/with-clean-registry ~@body))

;; =============================================================================
;; Re-exports — dispatch
;; =============================================================================

(def interceptor?   dispatch/interceptor?)
(def execute        dispatch/execute)
(def do-fx          dispatch/do-fx)
(def dispatch       dispatch/dispatch)
(def dispatch-sync  dispatch/dispatch-sync)
(def debug          dispatch/debug)
(def validate-event dispatch/validate-event)

;; =============================================================================
;; Initialization (hive-mcp specific)
;; =============================================================================

(defn init!
  "Initialize the event system.

   Registers built-in coeffects (overriding hive.events defaults
   with hive-mcp-specific implementations):
   - :now            - Current java.time.Instant (vs millis)
   - :random         - Random number (0-1)
   - :agent-context  - Swarm agent environment context (EVENTS-05)
   - :db-snapshot    - Current DataScript database state (EVENTS-05)

   Registers built-in effects:
   - :channel-publish - Emit event to WebSocket channel (POC-05)
   - :prometheus      - Report Prometheus metrics
   - :log             - Structured logging via timbre (avoids stdout
                        pollution in MCP context)
   - :mcp-response    - Data-only effect read by dispatch-sync callers

   Safe to call multiple times; idempotent via registry/*initialized."
  []
  (when-not @registry/*initialized
    (reg-cofx :now
              (fn [coeffects]
                (assoc coeffects :now (java.time.Instant/now))))
    (reg-cofx :random
              (fn [coeffects]
                (assoc coeffects :random (rand))))
    (reg-cofx :agent-context
              (fn [coeffects]
                (assoc coeffects :agent-context
                       {:agent-id (System/getenv "CLAUDE_SWARM_SLAVE_ID")
                        :parent-id (System/getenv "CLAUDE_SWARM_PARENT_ID")
                        :depth (some-> (System/getenv "CLAUDE_SWARM_DEPTH")
                                       Integer/parseInt)
                        :role (System/getenv "CLAUDE_SWARM_ROLE")})))
    (reg-cofx :db-snapshot
              (fn [coeffects]
                (assoc coeffects :db-snapshot @(ds/get-conn))))
    (reg-fx :channel-publish
            (fn [{:keys [event data]}]
              (ws/emit! event data)))
    (reg-fx :prometheus
            (fn [effect-data]
              (rescue nil (prom/handle-prometheus-effect! effect-data))))
    (reg-fx :log
            (fn [{:keys [level message]}]
              (case level
                :debug (log/debug message)
                :info  (log/info message)
                :warn  (log/warn message)
                :error (log/error message)
                (log/info message))))
    (reg-fx :mcp-response (fn [_] nil))
    (reset! registry/*initialized true)
    (log/info "Event system initialized with coeffects: :now :random :agent-context :db-snapshot")
    (log/info "Registered effects: :channel-publish :mcp-response"))
  @registry/*initialized)
