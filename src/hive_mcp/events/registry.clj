(ns hive-mcp.events.registry
  "Event handler registry — registration, deregistration, inspection.
   Owns the (:event-id → {:interceptors :handler}) map and the *initialized gate."
  (:require [hive.events.fx :as fx]
            [hive.events.cofx :as cofx]
            [hive-mcp.events.metrics :as metrics]
            [hive-mcp.server.guards :as guards]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce *initialized
  ^{:doc "Init gate — set by events.core/init! after built-in cofx/fx wiring."}
  (atom false))

(defonce ^:private *event-handlers
  ^{:doc "Event handler registry. Kept in hive-mcp for Prometheus + malli wrapping
          (not available in hive.events.router)."}
  (atom {}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn reg-event
  "Register event handler with interceptors. Handler-interceptor built at dispatch time."
  [event-id interceptors handler-fn]
  (swap! *event-handlers assoc event-id
         {:interceptors interceptors
          :handler handler-fn}))

(defn get-handler-entry
  "Return the registry entry for event-id, or nil."
  [event-id]
  (get @*event-handlers event-id))

;; =============================================================================
;; Interceptor Chain Mutation
;; =============================================================================

(defn- chain-has-id?
  "True if the interceptor chain already contains the given :id."
  [chain interceptor-id]
  (boolean (some #(= interceptor-id (:id %)) chain)))

(defn- append-to-chain
  "Append interceptor to an event entry's chain. Pure — returns updated entry."
  [entry interceptor]
  (update entry :interceptors #(conj (vec %) interceptor)))

(defn- try-append-interceptor
  "Pure swap fn: append interceptor to event-id's chain if not already present.
   Returns [updated-handlers appended?]."
  [handlers event-id interceptor]
  (if-let [entry (get handlers event-id)]
    (if (chain-has-id? (:interceptors entry) (:id interceptor))
      [handlers false]
      [(assoc handlers event-id (append-to-chain entry interceptor)) true])
    [handlers false]))

(defn append-interceptor!
  "Append an interceptor to an existing event's chain.
   Idempotent — skips if :id already present.
   Returns true if interceptor was added, false otherwise."
  [event-id interceptor]
  {:pre [(keyword? event-id) (map? interceptor) (:id interceptor)]}
  (let [appended? (atom false)]
    (swap! *event-handlers
           (fn [handlers]
             (let [[updated did-append?] (try-append-interceptor handlers event-id interceptor)]
               (reset! appended? did-append?)
               updated)))
    @appended?))

(defn get-interceptors
  "Get the interceptor chain for an event. For debugging/verification."
  [event-id]
  (get-in @*event-handlers [event-id :interceptors]))

(defn handler-registered?
  "Check if a handler is registered for the given event-id."
  [event-id]
  (contains? @*event-handlers event-id))

;; =============================================================================
;; Deregistration
;; =============================================================================

(defn unreg-event
  "Remove event handler. Returns true if removed, false if not found."
  [event-id]
  (let [removed? (atom false)]
    (swap! *event-handlers
           (fn [handlers]
             (if (contains? handlers event-id)
               (do (reset! removed? true)
                   (dissoc handlers event-id))
               handlers)))
    @removed?))

(defn unreg-fx
  "Remove fx handler. Accesses hive.events.fx/fx-registry directly (no library API)."
  [fx-id]
  (let [removed? (atom false)
        registry @(resolve 'hive.events.fx/fx-registry)]
    (swap! registry
           (fn [handlers]
             (if (contains? handlers fx-id)
               (do (reset! removed? true)
                   (dissoc handlers fx-id))
               handlers)))
    @removed?))

(defn unreg-cofx
  "Remove cofx handler. Accesses hive.events.cofx/cofx-registry directly (no library API)."
  [cofx-id]
  (let [removed? (atom false)
        registry @(resolve 'hive.events.cofx/cofx-registry)]
    (swap! registry
           (fn [handlers]
             (if (contains? handlers cofx-id)
               (do (reset! removed? true)
                   (dissoc handlers cofx-id))
               handlers)))
    @removed?))

;; =============================================================================
;; Inspection
;; =============================================================================

(defn registered-events
  "Return set of registered event IDs."
  []
  (set (keys @*event-handlers)))

(defn registered-effects
  "Return set of registered effect IDs.
   Reads hive.events.fx/fx-registry directly."
  []
  (set (keys @(deref (resolve 'hive.events.fx/fx-registry)))))

(defn registered-coeffects
  "Return set of registered coeffect IDs.
   Reads hive.events.cofx/cofx-registry directly."
  []
  (set (keys @(deref (resolve 'hive.events.cofx/cofx-registry)))))

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
  `(let [event-handlers# (var-get #'*event-handlers)
         old-handlers# @event-handlers#
         fx-atom# (var-get #'hive.events.fx/fx-registry)
         cofx-atom# (var-get #'hive.events.cofx/cofx-registry)
         old-fx# @fx-atom#
         old-cofx# @cofx-atom#]
     (try
       (clojure.core/reset! event-handlers# {})
       (clojure.core/reset! fx-atom# {})
       (clojure.core/reset! cofx-atom# {})
       ~@body
       (finally
         (clojure.core/reset! event-handlers# old-handlers#)
         (clojure.core/reset! fx-atom# old-fx#)
         (clojure.core/reset! cofx-atom# old-cofx#)))))

(defn reset-all!
  "Reset all event system state (testing). Clears handlers, fx, cofx, metrics."
  []
  (guards/when-not-coordinator
   "ev/reset-all! blocked"
   (clojure.core/reset! *initialized false)
   (clojure.core/reset! *event-handlers {})
   (fx/clear-fx)
   (cofx/clear-cofx)
   (metrics/reset-metrics!)
   nil))
