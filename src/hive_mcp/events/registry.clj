(ns hive-mcp.events.registry
  "Event handler registry for hive-mcp.

   Registration, deregistration and inspection all delegate to
   hive.events.router, which owns the ONE registry. A handler an addon
   registers with hive.events is therefore the same registration this host's
   dispatch reads, and an addon needs no dependency on hive-mcp to register
   one. This namespace keeps only what the host owns: the *initialized gate
   and the reset that clears host metrics alongside the shared registries.

   Entry shape (hive.events.router/get-event): {:interceptors <user chain>
   :handler <fn>}. The terminal handler-interceptor is appended by whichever
   dispatch runs the event, so appending an interceptor here cannot land it
   after the handler."
  (:require [hive.events.fx :as fx]
            [hive.events.cofx :as cofx]
            [hive.events.router :as router]
            [hive-mcp.events.metrics :as metrics]
            [hive-mcp.server.guards :as guards]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce *initialized
  ^{:doc "Init gate, set by events.core/init! after built-in cofx/fx wiring."}
  (atom false))

;; =============================================================================
;; Registration
;; =============================================================================

(defn reg-event
  "Register event handler with interceptors. Handler-interceptor built at dispatch time."
  [event-id interceptors handler-fn]
  (router/reg-event-fx event-id interceptors handler-fn))

(defn get-handler-entry
  "Return the registry entry for event-id, or nil."
  [event-id]
  (router/get-event event-id))

;; =============================================================================
;; Interceptor Chain Mutation
;; =============================================================================

(defn append-interceptor!
  "Append an interceptor to an existing event's chain.
   Idempotent, skips if :id already present.
   Returns true if interceptor was added, false otherwise."
  [event-id interceptor]
  {:pre [(keyword? event-id) (map? interceptor) (:id interceptor)]}
  (router/append-interceptor! event-id interceptor))

(defn get-interceptors
  "Get the interceptor chain for an event. For debugging/verification.
   Delegates to hive.events.router, which owns the registry."
  [event-id]
  (router/get-interceptors event-id))

(defn handler-registered?
  "Check if a handler is registered for the given event-id."
  [event-id]
  (router/handler-registered? event-id))

;; =============================================================================
;; Deregistration
;; =============================================================================

(defn unreg-event
  "Remove event handler. Returns true if removed, false if not found."
  [event-id]
  (router/unreg-event event-id))

(defn unreg-fx
  "Remove fx handler. Returns true if removed, false if not found."
  [fx-id]
  (fx/unreg-fx fx-id))

(defn unreg-cofx
  "Remove cofx handler. Returns true if removed, false if not found."
  [cofx-id]
  (cofx/unreg-cofx cofx-id))

;; =============================================================================
;; Inspection
;; =============================================================================

(defn registered-events
  "Return set of registered event IDs."
  []
  (router/registered-event-ids))

(defn registered-effects
  "Return set of registered effect IDs."
  []
  (fx/registered-fx-ids))

(defn registered-coeffects
  "Return set of registered coeffect IDs."
  []
  (cofx/registered-cofx-ids))

(defn handler-registry-status
  "Summary of registered events/fx/cofx with counts and sorted IDs."
  []
  (let [events (registered-events)
        effects (registered-effects)
        coeffects (registered-coeffects)]
    {:event-count (count events)
     :fx-count    (count effects)
     :cofx-count  (count coeffects)
     :events      (vec (sort events))
     :effects     (vec (sort effects))
     :coeffects   (vec (sort coeffects))}))

;; =============================================================================
;; Test Isolation
;; =============================================================================

(defmacro with-clean-registry
  "Run body with isolated event/fx/cofx registries; restore after (testing only)."
  [& body]
  `(let [old-handlers# (router/registry-snapshot)
         old-fx#       (fx/registry-snapshot)
         old-cofx#     (cofx/registry-snapshot)]
     (try
       (router/restore-registry! {})
       (fx/restore-registry! {})
       (cofx/restore-registry! {})
       ~@body
       (finally
         (router/restore-registry! old-handlers#)
         (fx/restore-registry! old-fx#)
         (cofx/restore-registry! old-cofx#)))))

(defn reset-all!
  "Reset all event system state (testing). Clears handlers, fx, cofx, metrics."
  []
  (guards/when-not-coordinator
   "ev/reset-all! blocked"
   (clojure.core/reset! *initialized false)
   (router/clear-event)
   (fx/clear-fx)
   (cofx/clear-cofx)
   (metrics/reset-metrics!)
   nil))
