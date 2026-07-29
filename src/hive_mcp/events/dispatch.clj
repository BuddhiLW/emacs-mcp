(ns hive-mcp.events.dispatch
  "Event dispatch + interceptor execution. Wraps hive.events.interceptor with
   malli validation, Prometheus telemetry, and metrics-tracked effect execution.
   Built-in dispatch-side interceptors here: debug, validate-event."
  (:require [hive.events.interceptor :as interceptor]
            [hive.events.fx :as fx]
            [malli.core :as m]
            [malli.error :as me]
            [hive-mcp.events.context :as ctx]
            [hive-mcp.events.metrics :as metrics]
            [hive-mcp.events.registry :as registry]
            [hive-mcp.events.schemas :as schemas]
            [hive-mcp.telemetry.prometheus :as prom]
            [taoensso.timbre :as log]
            [hive.events.observer :as observer]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Interceptor Predicate
;; =============================================================================

(def ^:const unhandled-effect-warn-threshold
  "After this many silent misses for the same effect-id, do-fx escalates from
   WARN to ERROR-level log so unregistered effects can't blackhole the system
   (ENGINE-L0.4, incident 2026-05-11 — missing :resilience/dim-mismatch handler
   went unnoticed under WARN-default log routing)."
  10)

(defonce ^:private *unhandled-effect-counts
  ^{:doc "Per-effect-id miss counter for L0.4 loud-fail escalation."}
  (atom {}))

(defn unhandled-effect-counts
  "Read snapshot of per-effect-id miss counts. Diagnostic only."
  []
  @*unhandled-effect-counts)

(defn reset-unhandled-effect-counts!
  "Clear the unhandled-effect counter — call from test fixtures."
  []
  (reset! *unhandled-effect-counts {}))

(defn interceptor?
  "Returns true if m is a valid interceptor map."
  [m]
  (and (map? m)
       (contains? m :id)))

;; =============================================================================
;; Interceptor Chain Execution
;; =============================================================================

(defn execute
  "Execute an interceptor chain for the given event.

   Creates context from event + interceptors, then delegates to
   hive.events.interceptor/execute for the actual chain execution
   (with proper LIFO :after ordering).

   Args:
   - event              - The event vector, e.g. [:event-id data]
   - interceptors       - Collection of interceptor maps
   - initial-coeffects  - (optional) seed coeffects merged with {:event ...}

   Returns the final context with :coeffects and :effects."
  ([event interceptors]
   (execute event interceptors {}))
  ([event interceptors initial-coeffects]
   (interceptor/execute
    {:coeffects (merge {:event event} initial-coeffects)
     :effects {}
     :queue (vec interceptors)
     :stack []})))

;; =============================================================================
;; Effect Execution (with metrics tracking)
;; =============================================================================

(defn- record-unhandled-effect!
  "Increment the miss counter for effect-id and log at WARN until the
   threshold is crossed, then escalate to ERROR for every subsequent miss.
   Returns the new count."
  [effect-id]
  (let [n (-> *unhandled-effect-counts
              (swap! update effect-id (fnil inc 0))
              (get effect-id))]
    (if (< n unhandled-effect-warn-threshold)
      (log/warn "No effect handler for" effect-id
                (str "(miss " n "/" unhandled-effect-warn-threshold ")"))
      (log/error "BLACKHOLE: no effect handler for" effect-id
                 "after" n "misses — register a handler via reg-fx or"
                 "remove the emitter (ENGINE-L0.4)"))
    n))

(defn do-fx
  "Execute all effects in the context's :effects map.

   Uses hive.events.fx/get-fx for handler lookup (unified registry).
   Tracks effect execution via metrics/record-effect-executed! and
   metrics/record-effect-error!. Missing handlers are counted per
   effect-id; the WARN escalates to ERROR after
   `unhandled-effect-warn-threshold` misses (ENGINE-L0.4).

   Arities:
   - [context]              - Uses hive.events global fx registry
   - [context _fx-handlers] - Legacy 2-arity (uses global registry)"
  ([context]
   (doseq [[effect-id effect-data] (:effects context)]
     (if-let [handler (fx/get-fx effect-id)]
       (try
         (handler effect-data)
         (metrics/record-effect-executed!)
         (catch Throwable e
           (metrics/record-effect-error!)
           (log/error "Effect" effect-id "failed:" (.getMessage e))))
       (record-unhandled-effect! effect-id)))
   context)
  ([context _fx-handlers]
   (do-fx context)))

;; =============================================================================
;; Dispatch
;; =============================================================================

(defn dispatch
  "Dispatch an event through its registered handler chain.

   Wraps interceptor execution with:
   1. Malli schema validation at boundary
   2. Prometheus telemetry

   Uses hive.events.interceptor/execute for chain processing and
   hive.events.fx/get-fx for effect handler lookup.

   Registered observers (hive.events.observer) are notified after the
   effects are applied, and also for an event that has no handler.

   Throws ExceptionInfo if event is invalid or no handler registered."
  [event]
  (schemas/validate-event! event)
  (let [event-id (first event)
        start-ns (System/nanoTime)]
    (prom/inc-events-total! event-id :info)
    (if-let [{:keys [interceptors handler]} (registry/get-handler-entry event-id)]
      (let [handler-interceptor
            (interceptor/->interceptor
             :id :handler
             :before (fn [context]
                       (let [coeffects (:coeffects context)
                             ctx-event (:event coeffects)]
                         (update context :effects merge (handler coeffects ctx-event)))))
            full-chain (conj (vec interceptors) handler-interceptor)
            result (execute event full-chain)
            elapsed-sec (/ (- (System/nanoTime) start-ns) 1e9)]
        (prom/observe-request-duration! (str "event-dispatch-" (name event-id)) elapsed-sec)
        (do-fx result)
        (observer/notify! event-id result)
        result)
      (do
        (observer/notify! event-id {:coeffects {:event event}})
        (throw (ex-info (str "No handler registered for event: " event-id)
                        {:event event}))))))

(defn dispatch-sync
  "Synchronous dispatch — same as dispatch for now.
   Future: dispatch may become async."
  [event]
  (dispatch event))

;; =============================================================================
;; Built-in Interceptors (dispatch-side)
;; =============================================================================

(def debug
  "Interceptor that logs event and effects for debugging.
   Uses timbre logging to avoid stdout pollution in MCP context.
   (Overrides hive.events/debug which uses println.)"
  (interceptor/->interceptor
   :id :debug
   :before (fn [context]
             (log/debug "Event:" (ctx/get-coeffect context :event))
             context)
   :after (fn [context]
            (log/debug "Effects:" (:effects context))
            context)))

(defn validate-event
  "Create a validation interceptor that validates event data against a malli schema.

   Runs in :before phase and validates:
   1. Event vector structure (always — uses schemas/Event)
   2. Event data (optional — if data-schema is provided)

   Args:
   - data-schema (optional) - Malli schema to validate event data against

   Usage:
   ```clojure
   ;; Structure validation only
   (reg-event :my-event
     [(validate-event)]
     handler-fn)

   ;; Structure + data schema
   (def TaskData [:map [:id :string] [:title :string]])
   (reg-event :task/create
     [(validate-event TaskData)]
     handler-fn)
   ```

   On failure, throws ex-info with:
   - :event       - The invalid event
   - :error       - Humanized error message
   - :schema-type - :structure or :data"
  ([]
   (interceptor/->interceptor
    :id :validate-event
    :before (fn [context]
              (let [event (ctx/get-coeffect context :event)]
                (when-not (schemas/valid-event? event)
                  (throw (ex-info "Invalid event structure: event must be a vector with keyword first"
                                  {:event event
                                   :error (schemas/explain-event event)
                                   :schema-type :structure})))
                context))))
  ([data-schema]
   (interceptor/->interceptor
    :id :validate-event
    :before (fn [context]
              (let [event (ctx/get-coeffect context :event)]
                (when-not (schemas/valid-event? event)
                  (throw (ex-info "Invalid event structure: event must be a vector with keyword first"
                                  {:event event
                                   :error (schemas/explain-event event)
                                   :schema-type :structure})))
                (let [event-data (second event)]
                  (when-not (m/validate data-schema event-data)
                    (throw (ex-info "Invalid event data: data does not match schema"
                                    {:event event
                                     :event-data event-data
                                     :error (me/humanize (m/explain data-schema event-data))
                                     :schema-type :data}))))
                context)))))