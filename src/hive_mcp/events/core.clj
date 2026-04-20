(ns hive-mcp.events.core
  "Event system facade for hive-mcp.

   Delegates core event machinery to hive-events library (hive.events),
   adding hive-mcp-specific extensions:
   - Prometheus telemetry in dispatch
   - Malli schema validation at dispatch boundary
   - Metrics interceptor for observability
   - validate-event interceptor for data validation
   - init! for hive-mcp-specific coeffect registration
   - Deregistration API (unreg-event, unreg-fx, unreg-cofx)
   - Inspection API (registered-events, registered-effects, registered-coeffects)

   Core event machinery (interceptor chain, fx, cofx) lives in
   hive-events (io.github.hive-agi/hive-events). This namespace is
   a thin facade that re-exports the canonical API and adds hive-mcp
   extensions.

   ## Architecture

   Delegated to hive.events:
   - Interceptor primitives (->interceptor, enqueue, execute)
   - Effect registration & lookup (reg-fx, get-fx)
   - Coeffect registration & injection (reg-cofx, inject-cofx)
   - Built-in interceptors (trim-v)

   Kept in hive-mcp (extensions):
   - Event registry & dispatch (Prometheus + malli wrapping)
   - Metrics interceptor (bounded rolling window telemetry)
   - Debug interceptor (timbre instead of println)
   - Validation interceptor (malli schema)
   - Deregistration (unreg-event, unreg-fx, unreg-cofx)
   - Inspection (registered-events, registered-effects, registered-coeffects, handler-registry-status)
   - init! / reset-all! / with-clean-registry"

  (:require [hive.events.interceptor :as interceptor]
            [hive.events.fx :as fx]
            [hive.events.cofx :as cofx]
            [malli.core :as m]
            [malli.error :as me]
            [hive-mcp.events.schemas :as schemas]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.channel.websocket :as ws]
            [hive-mcp.telemetry.prometheus :as prom]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State (hive-mcp specific)
;; =============================================================================

(defonce ^:private *initialized (atom false))

;; Event handler registry - kept in hive-mcp for custom dispatch flow
;; (Prometheus + malli wrapping not available in hive.events.router)
(defonce ^:private *event-handlers (atom {}))

;; =============================================================================
;; Metrics Configuration (E2)
;; =============================================================================

(def ^:private default-metrics-config
  "Default configuration for metrics buffer sizes."
  {:max-timings 1000
   :max-timings-per-type 200})

(defonce ^:private *metrics-config (atom default-metrics-config))

(defn configure-metrics!
  "Configure metrics buffer limits.

   Options:
   - :max-timings          - Max samples in the global :timings buffer (default: 1000)
   - :max-timings-per-type - Max samples per event type in :timings-by-type (default: 200)

   Example:
   ```clojure
   (configure-metrics! {:max-timings 500 :max-timings-per-type 100})
   ```"
  [{:keys [max-timings max-timings-per-type]}]
  (swap! *metrics-config merge
         (cond-> {}
           max-timings          (assoc :max-timings max-timings)
           max-timings-per-type (assoc :max-timings-per-type max-timings-per-type))))

;; =============================================================================
;; Bounded Buffer Helpers (E2)
;; =============================================================================

(defn- bounded-conj
  "Append `val` to vector `v`, dropping the oldest entry when `(count v) >= cap`.
   Returns [updated-vec dropped?].

   Uses subvec for O(~1) FIFO eviction on Clojure persistent vectors."
  [v val cap]
  (let [v (or v [])]
    (if (>= (count v) cap)
      [(conj (subvec v 1) val) true]
      [(conj v val) false])))

;; =============================================================================
;; Metrics State - hive-mcp specific
;; =============================================================================

(def ^:private *metrics
  "Metrics atom tracking event dispatch statistics.

   Shape:
   {:events-dispatched N      ; Total events dispatched
    :events-by-type    {kw N} ; Count per event-id keyword
    :effects-executed  N      ; Total effects successfully executed
    :errors           N       ; Total effect execution errors
    :timings-by-type  {kw []} ; Bounded rolling window of dispatch times per event type (ms)
    :timings          [...]   ; Bounded rolling window of all dispatch times (ms)
    :timings-dropped  N}      ; Total timings dropped due to buffer overflow (never resets)"
  (atom {:events-dispatched 0
         :events-by-type {}
         :effects-executed 0
         :errors 0
         :timings-by-type {}
         :timings []
         :timings-dropped 0}))

(defn get-metrics
  "Get current metrics snapshot.

   Returns map with:
   - :events-dispatched  - Total events dispatched
   - :events-by-type     - Map of event-id -> count
   - :effects-executed   - Total effects successfully executed
   - :errors             - Total effect execution errors
   - :avg-dispatch-ms    - Average dispatch time across all events (ms)
   - :avg-by-type        - Map of event-id -> average dispatch time (ms)
   - :timings-count      - Number of timing samples
   - :timings-by-type    - Map of event-id -> [timing samples]
   - :timings-buffer-size     - Current size of global timings buffer
   - :timings-buffer-capacity - Configured max for global timings buffer
   - :timings-dropped         - Total timings dropped due to buffer overflow"

  []
  (let [m @*metrics
        cfg @*metrics-config
        timings (:timings m)
        timings-by-type (:timings-by-type m)
        avg-ms (if (seq timings)
                 (/ (reduce + timings) (count timings))
                 0)
        avg-by-type (reduce-kv
                     (fn [acc event-id event-timings]
                       (if (seq event-timings)
                         (assoc acc event-id (/ (reduce + event-timings) (count event-timings)))
                         acc))
                     {}
                     timings-by-type)]
    (assoc m
           :avg-dispatch-ms avg-ms
           :avg-by-type avg-by-type
           :timings-count (count timings)
           :timings-buffer-size (count timings)
           :timings-buffer-capacity (:max-timings cfg)
           :timings-dropped (:timings-dropped m 0))))

(defn reset-metrics!
  "Reset all metrics counters including dropped counter. For testing."
  []
  (reset! *metrics {:events-dispatched 0
                    :events-by-type {}
                    :effects-executed 0
                    :errors 0
                    :timings-by-type {}
                    :timings []
                    :timings-dropped 0}))

;; =============================================================================
;; Re-exports from hive.events — Interceptor Primitives
;; (THE canonical implementations — zero duplication)
;; =============================================================================

(def ->interceptor
  "Create an interceptor from keyword arguments. Delegated to hive.events.interceptor."
  interceptor/->interceptor)

(def enqueue
  "Add interceptors to the context's queue. Delegated to hive.events.interceptor."
  interceptor/enqueue)

(defn interceptor?
  "Returns true if m is a valid interceptor map."
  [m]
  (and (map? m)
       (contains? m :id)))

;; =============================================================================
;; Context Access — simple utilities kept inline for ergonomics
;; =============================================================================

(defn get-coeffect
  "Get a coeffect value from context."
  ([context]
   (:coeffects context))
  ([context key]
   (get-in context [:coeffects key]))
  ([context key not-found]
   (get-in context [:coeffects key] not-found)))

(defn assoc-coeffect
  "Associate a coeffect value in context."
  [context key value]
  (assoc-in context [:coeffects key] value))

(defn update-coeffect
  "Update a coeffect value in context."
  [context key f & args]
  (apply update-in context [:coeffects key] f args))

(defn get-effect
  "Get an effect value from context."
  ([context]
   (:effects context))
  ([context key]
   (get-in context [:effects key]))
  ([context key not-found]
   (get-in context [:effects key] not-found)))

(defn assoc-effect
  "Associate an effect value in context."
  [context key value]
  (assoc-in context [:effects key] value))

(defn update-effect
  "Update an effect value in context."
  [context key f & args]
  (apply update-in context [:effects key] f args))

;; =============================================================================
;; Re-exports from hive.events — Fx/Cofx
;; =============================================================================

(def reg-fx
  "Register an effect handler. Delegated to hive.events.fx.

   Effect handlers execute side effects described in the :effects map.

   Example:
   ```clojure
   (reg-fx :shout
     (fn [data]
       (hivemind/shout! (:event-type data) (:message data))))
   ```"
  fx/reg-fx)

(def reg-cofx
  "Register a coeffect handler. Delegated to hive.events.cofx.

   Coeffect handlers inject values into the context's :coeffects.

   Example:
   ```clojure
   (reg-cofx :now
     (fn [coeffects]
       (assoc coeffects :now (java.time.Instant/now))))
   ```"
  cofx/reg-cofx)

(def inject-cofx
  "Create an interceptor that injects a coeffect. Delegated to hive.events.cofx.

   The coeffect handler registered with `reg-cofx` will be called
   during the :before phase."
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
;; Interceptor Execution — wraps hive.events.interceptor/execute
;; =============================================================================

(defn execute
  "Execute an interceptor chain for the given event.

   Creates context from event + interceptors, then delegates to
   hive.events.interceptor/execute for the actual chain execution
   (with proper LIFO :after ordering).

   Args:
   - event        - The event vector, e.g. [:event-id data]
   - interceptors - Collection of interceptor maps

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
;; Effect Execution — wraps hive.events.fx with metrics tracking
;; =============================================================================

(defn do-fx
  "Execute all effects in the context's :effects map.

   Uses hive.events.fx/get-fx for handler lookup (unified registry).
   Tracks effect execution metrics (effects-executed, errors).

   Arities:
   - [context] - Uses hive.events global fx registry (production)
   - [context fx-handlers] - Legacy 2-arity (uses global registry)"

  ([context]
   (doseq [[effect-id effect-data] (:effects context)]
     (if-let [handler (fx/get-fx effect-id)]
       (try
         (handler effect-data)
         (swap! *metrics update :effects-executed inc)
         (catch Exception e
           (swap! *metrics update :errors inc)
           (log/error "Effect" effect-id "failed:" (.getMessage e))))
       (log/warn "No effect handler for" effect-id)))
   context)
  ([context _fx-handlers]
   ;; Legacy 2-arity preserved for backwards compatibility.
   ;; Uses hive.events.fx global registry (fx-handlers arg ignored).
   (do-fx context)))

;; =============================================================================
;; Event Registration & Dispatch
;; =============================================================================

(defn reg-event
  "Register an event handler with interceptors.

   Stores handler + interceptors in the hive-mcp event registry.
   The handler-interceptor is built at dispatch time.

   Example:
   ```clojure
   (reg-event :task-complete
     [debug-interceptor validate-interceptor]
     (fn [coeffects event]
       {:shout {:event-type :completed
                :message (str \"Task \" (second event) \" done\")}}))
   ```"
  [event-id interceptors handler-fn]
  (swap! *event-handlers assoc event-id
         {:interceptors interceptors
          :handler handler-fn}))

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

(defn dispatch
  "Dispatch an event through its registered handler chain.

   Wraps the core interceptor execution with:
   1. Malli schema validation at boundary
   2. Prometheus telemetry

   Uses hive.events.interceptor/execute for chain processing and
   hive.events.fx/get-fx for effect handler lookup.

   Throws: ExceptionInfo if event is invalid or no handler registered."
  [event]
  (schemas/validate-event! event)
  (let [event-id (first event)
        start-ns (System/nanoTime)]
    (prom/inc-events-total! event-id :info)
    (if-let [{:keys [interceptors handler]} (get @*event-handlers event-id)]
      (let [;; Handler interceptor converts coeffects to effects
            handler-interceptor (->interceptor
                                 :id :handler
                                 :before (fn [context]
                                           (let [coeffects (:coeffects context)
                                                 ctx-event (:event coeffects)]
                                             (update context :effects merge (handler coeffects ctx-event)))))
            ;; Build full chain: registered interceptors + handler
            full-chain (conj (vec interceptors) handler-interceptor)
            ;; Execute via hive.events interceptor engine
            result (execute event full-chain)
            elapsed-sec (/ (- (System/nanoTime) start-ns) 1e9)]
        (prom/observe-request-duration! (str "event-dispatch-" (name event-id)) elapsed-sec)
        (do-fx result)
        result)
      (throw (ex-info (str "No handler registered for event: " event-id)
                      {:event event})))))

(defn dispatch-sync
  "Synchronous dispatch - same as dispatch for now.
   Future: dispatch may become async."
  [event]
  (dispatch event))

;; =============================================================================
;; Built-in Interceptors
;; =============================================================================

(def debug
  "Interceptor that logs event and effects for debugging.
   Uses timbre logging to avoid stdout pollution in MCP context.
   (Overrides hive.events/debug which uses println.)"
  (->interceptor
   :id :debug
   :before (fn [context]
             (log/debug "Event:" (get-coeffect context :event))
             context)
   :after (fn [context]
            (log/debug "Effects:" (:effects context))
            context)))

(def metrics
  "Interceptor that tracks event dispatch metrics per event type.

   Records:
   - Total event count (incremented in :before)
   - Per-event-type count (tracked in :events-by-type)
   - Dispatch timing in ms (recorded in :after)
   - Per-event-type timings (tracked in :timings-by-type)

   Timings are bounded by configurable limits (see configure-metrics!):
   - Global :timings capped at :max-timings (default 1000)
   - Per-type :timings-by-type capped at :max-timings-per-type (default 200)
   Oldest entries are dropped first (FIFO)."

  (->interceptor
   :id :metrics
   :before (fn [context]
             (let [event (get-coeffect context :event)
                   event-id (when (vector? event) (first event))]
               (swap! *metrics
                      (fn [m]
                        (-> m
                            (update :events-dispatched inc)
                            (update-in [:events-by-type event-id] (fnil inc 0)))))
               (-> context
                   (assoc-coeffect :metrics-start-ns (System/nanoTime))
                   (assoc-coeffect :metrics-event-id event-id))))
   :after (fn [context]
            (let [start-ns (get-coeffect context :metrics-start-ns)
                  event-id (get-coeffect context :metrics-event-id)
                  elapsed-ms (when start-ns
                               (/ (- (System/nanoTime) start-ns) 1000000.0))]
              (when elapsed-ms
                (let [{:keys [max-timings max-timings-per-type]} @*metrics-config]
                  (swap! *metrics
                         (fn [m]
                           (let [[new-timings global-dropped?]
                                 (bounded-conj (:timings m) elapsed-ms max-timings)
                                 [new-type-timings type-dropped?]
                                 (bounded-conj (get-in m [:timings-by-type event-id])
                                               elapsed-ms max-timings-per-type)
                                 total-dropped (+ (if global-dropped? 1 0)
                                                  (if type-dropped? 1 0))]
                             (-> m
                                 (assoc :timings new-timings)
                                 (assoc-in [:timings-by-type event-id] new-type-timings)
                                 (update :timings-dropped + total-dropped))))))))
            context)))

(def trim-v
  "Interceptor that removes the event-id from the event vector.
   Delegated to hive.events.interceptor/trim-v."
  interceptor/trim-v)

;; =============================================================================
;; Validation Interceptor (hive-mcp specific - malli integration)
;; =============================================================================

(defn validate-event
  "Create a validation interceptor that validates event data against a malli schema.

   The validation interceptor runs in the :before phase and validates:
   1. Event vector structure (always - uses schemas/Event)
   2. Event data (optional - if schema is provided)

   When schema is provided, it validates the event data (second element of
   the event vector) against the malli schema.

   Args:
   - schema (optional) - Malli schema to validate the event data against

   Usage:
   ```clojure
   ;; Basic structure validation only
   (reg-event :my-event
     [(validate-event)]
     handler-fn)

   ;; Structure + data schema validation
   (def TaskData [:map [:id :string] [:title :string]])
   (reg-event :task/create
     [(validate-event TaskData)]
     handler-fn)
   ```

   On validation failure, throws ex-info with:
   - :event       - The invalid event
   - :error       - Humanized error message
   - :schema-type - :structure or :data (which validation failed)

   POC-14: Validation interceptor for event system."
  ([]
   (->interceptor
    :id :validate-event
    :before (fn [context]
              (let [event (get-coeffect context :event)]
                ;; Always validate basic event structure
                (when-not (schemas/valid-event? event)
                  (throw (ex-info "Invalid event structure: event must be a vector with keyword first"
                                  {:event event
                                   :error (schemas/explain-event event)
                                   :schema-type :structure})))
                context))))
  ([data-schema]
   (->interceptor
    :id :validate-event
    :before (fn [context]
              (let [event (get-coeffect context :event)]
                ;; First validate basic event structure
                (when-not (schemas/valid-event? event)
                  (throw (ex-info "Invalid event structure: event must be a vector with keyword first"
                                  {:event event
                                   :error (schemas/explain-event event)
                                   :schema-type :structure})))
                ;; Then validate event data against provided schema
                (let [event-data (second event)]
                  (when-not (m/validate data-schema event-data)
                    (throw (ex-info "Invalid event data: data does not match schema"
                                    {:event event
                                     :event-data event-data
                                     :error (me/humanize (m/explain data-schema event-data))
                                     :schema-type :data}))))
                context)))))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn init!
  "Initialize the event system.

   Registers built-in coeffects including:
   - :now         - Current timestamp (java.time.Instant)
   - :random      - Random number (0-1)
   - :agent-context - Swarm agent environment context (EVENTS-05)
   - :db-snapshot  - Current DataScript database state (EVENTS-05)

   Also registers built-in effects:
   - :channel-publish - Emit event to WebSocket channel (POC-05)
   - :prometheus      - Report Prometheus metrics
   - :log             - Structured logging

   Note: hive.events registers built-in :now/:random/:uuid cofx at load time.
   hive-mcp overrides :now with java.time.Instant (vs millis).

   Safe to call multiple times."
  []
  (when-not @*initialized
    ;; Register built-in coeffects (override hive.events defaults
    ;; with hive-mcp-specific implementations)
    (reg-cofx :now
              (fn [coeffects]
                (assoc coeffects :now (java.time.Instant/now))))

    (reg-cofx :random
              (fn [coeffects]
                (assoc coeffects :random (rand))))

    ;; EVENTS-05: Agent context coeffect
    ;; Injects swarm agent environment information
    (reg-cofx :agent-context
              (fn [coeffects]
                (assoc coeffects :agent-context
                       {:agent-id (System/getenv "CLAUDE_SWARM_SLAVE_ID")
                        :parent-id (System/getenv "CLAUDE_SWARM_PARENT_ID")
                        :depth (some-> (System/getenv "CLAUDE_SWARM_DEPTH")
                                       Integer/parseInt)
                        :role (System/getenv "CLAUDE_SWARM_ROLE")})))

    ;; EVENTS-05: DataScript snapshot coeffect
    ;; Injects current database state for queries
    (reg-cofx :db-snapshot
              (fn [coeffects]
                (assoc coeffects :db-snapshot @(ds/get-conn))))

    ;; =============================================================================
    ;; Built-in Effects
    ;; =============================================================================

    ;; POC-05: Channel publish effect
    ;; Publishes typed events to WebSocket channel for Emacs consumption
    (reg-fx :channel-publish
            (fn [{:keys [event data]}]
              (ws/emit! event data)))

    ;; Handles :prometheus effects from event handlers for drone/wave telemetry
    ;; Effect shape: {:counter :drone_started :labels {:parent "none"}
    ;;                :histogram {:name :drone_duration_seconds :value 5.0}}
    (reg-fx :prometheus
            (fn [effect-data]
              (rescue nil (prom/handle-prometheus-effect! effect-data))))

    ;; Handles :log effects from event handlers for structured logging
    ;; Effect shape: {:level :info :message "Drone started: drone-123"}
    ;; NOTE: Uses timbre logging instead of println to avoid stdout pollution
    ;; in MCP context (stdout is used for JSON-RPC communication)
    (reg-fx :log
            (fn [{:keys [level message]}]
              (case level
                :debug (log/debug message)
                :info  (log/info message)
                :warn  (log/warn message)
                :error (log/error message)
                (log/info message))))

    ;; No-op effect: :mcp-response is a data-only effect read by dispatch-sync callers
    ;; (not executed as a side-effect, just carried in the context)
    (reg-fx :mcp-response (fn [_] nil))

    ;; Mark as initialized
    (reset! *initialized true)
    (log/info "Event system initialized with coeffects: :now :random :agent-context :db-snapshot")
    (log/info "Registered effects: :channel-publish :mcp-response"))
  @*initialized)

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn handler-registered?
  "Check if a handler is registered for the given event-id.
   Public API to avoid accessing private state."
  [event-id]
  (contains? @*event-handlers event-id))

;; =============================================================================
;; Deregistration API (E1)
;; =============================================================================

(defn unreg-event
  "Remove event handler for event-id.
   Returns true if the handler was found and removed, false if not found.
   Thread-safe (uses swap! on atom)."
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
  "Remove effect handler for fx-id.
   Returns true if the handler was found and removed, false if not found.
   Accesses hive.events.fx/fx-registry atom directly (unreg-fx not in library API).
   Thread-safe (uses swap! on atom)."
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
  "Remove coeffect handler for cofx-id.
   Returns true if the handler was found and removed, false if not found.
   Accesses hive.events.cofx/cofx-registry atom directly (unreg-cofx not in library API).
   Thread-safe (uses swap! on atom)."
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
;; Inspection / Diagnostics API (E1)
;; =============================================================================

(defn registered-events
  "Return set of registered event IDs."
  []
  (set (keys @*event-handlers)))

(defn registered-effects
  "Return set of registered effect IDs.
   Reads hive.events.fx/fx-registry atom directly (registered-fx-ids not in library API)."
  []
  (set (keys @(deref (resolve 'hive.events.fx/fx-registry)))))

(defn registered-coeffects
  "Return set of registered coeffect IDs.
   Reads hive.events.cofx/cofx-registry atom directly (registered-cofx-ids not in library API)."
  []
  (set (keys @(deref (resolve 'hive.events.cofx/cofx-registry)))))

(defn handler-registry-status
  "Return a summary of all registered handlers across event, fx, and cofx registries.

   Returns:
   {:event-count  N
    :fx-count     N
    :cofx-count   N
    :events       [sorted event-id keywords]
    :effects      [sorted fx-id keywords]
    :coeffects    [sorted cofx-id keywords]}"
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
;; Testing Helpers
;; =============================================================================

(defmacro with-clean-registry
  "Execute body with fresh, isolated registries. For testing.

   Creates a clean slate for event handlers, effect handlers, and
   coeffect handlers, then restores the original state after the
   body executes (even if an exception occurs).

   Saves/restores both hive-mcp event registry AND hive.events
   fx/cofx registries (accessed via var-get for testing only).

   Example:
   ```clojure
   (with-clean-registry
     (reg-event :test [] (fn [_ _] {:log {:msg \"test\"}}))
     (dispatch [:test])
     ;; registries reset after block
   )
   ```"

  [& body]
  `(let [;; Save hive-mcp event registry
         event-handlers# (var-get #'*event-handlers)
         old-handlers# @event-handlers#
         ;; Save hive.events fx/cofx registries (private atoms, testing only)
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
  "Reset all event system state. Primarily for testing.

   Resets handlers, coeffects, effects, and metrics.
   Clears both hive-mcp event registry and hive.events fx/cofx registries."

  []
  (guards/when-not-coordinator
   "ev/reset-all! blocked"
   (clojure.core/reset! *initialized false)
   (clojure.core/reset! *event-handlers {})
   (fx/clear-fx)
   (cofx/clear-cofx)
   (reset-metrics!)
   nil))
