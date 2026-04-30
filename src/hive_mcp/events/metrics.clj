(ns hive-mcp.events.metrics
  "Event dispatch metrics — bounded rolling-window telemetry.

   Owns:
   - Metrics state (atom + config)
   - Public read API: get-metrics, reset-metrics!, configure-metrics!
   - Dispatch tracking: record-effect-executed!, record-effect-error!
   - The :metrics interceptor (per-event-type timing)"
  (:require [hive.events.interceptor :as interceptor]
            [hive-mcp.events.context :as ctx]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Configuration
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
;; Bounded Buffer Helper
;; =============================================================================

(defn- bounded-conj
  "Append `val` to vector `v`, dropping the oldest entry when (count v) >= cap.
   Returns [updated-vec dropped?].

   Uses subvec for O(~1) FIFO eviction on Clojure persistent vectors."
  [v val cap]
  (let [v (or v [])]
    (if (>= (count v) cap)
      [(conj (subvec v 1) val) true]
      [(conj v val) false])))

;; =============================================================================
;; State
;; =============================================================================

(def ^:private *metrics
  "Metrics atom tracking event dispatch statistics.

   Shape:
   {:events-dispatched N      ; Total events dispatched
    :events-by-type    {kw N} ; Count per event-id keyword
    :effects-executed  N      ; Total effects successfully executed
    :errors           N       ; Total effect execution errors
    :timings-by-type  {kw []} ; Bounded rolling window per event type (ms)
    :timings          [...]   ; Bounded rolling window of all dispatch times (ms)
    :timings-dropped  N}      ; Total timings dropped due to buffer overflow"
  (atom {:events-dispatched 0
         :events-by-type {}
         :effects-executed 0
         :errors 0
         :timings-by-type {}
         :timings []
         :timings-dropped 0}))

;; =============================================================================
;; Read API
;; =============================================================================

(defn get-metrics
  "Get current metrics snapshot.

   Returns map with computed averages plus buffer telemetry:
   :avg-dispatch-ms, :avg-by-type, :timings-count,
   :timings-buffer-size, :timings-buffer-capacity, :timings-dropped."
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
;; Dispatch Tracking (called from events.dispatch)
;; =============================================================================

(defn record-effect-executed!
  "Increment :effects-executed counter."
  []
  (swap! *metrics update :effects-executed inc))

(defn record-effect-error!
  "Increment :errors counter."
  []
  (swap! *metrics update :errors inc))

;; =============================================================================
;; Metrics Interceptor
;; =============================================================================

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
  (interceptor/->interceptor
   :id :metrics
   :before (fn [context]
             (let [event (ctx/get-coeffect context :event)
                   event-id (when (vector? event) (first event))]
               (swap! *metrics
                      (fn [m]
                        (-> m
                            (update :events-dispatched inc)
                            (update-in [:events-by-type event-id] (fnil inc 0)))))
               (-> context
                   (ctx/assoc-coeffect :metrics-start-ns (System/nanoTime))
                   (ctx/assoc-coeffect :metrics-event-id event-id))))
   :after (fn [context]
            (let [start-ns (ctx/get-coeffect context :metrics-start-ns)
                  event-id (ctx/get-coeffect context :metrics-event-id)
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
