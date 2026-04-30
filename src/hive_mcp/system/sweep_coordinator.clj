(ns hive-mcp.system.sweep-coordinator
  "Single-timer sweep coordinator driving all registered ISweepable impls.

   Strategy: one ScheduledExecutorService heartbeat every
   min(all-sweep-intervals) seconds (floor 10s, ceiling 60s). Each sweeper
   carries its own :last-run-at; sweep! fires when now - last-run-at >=
   sweep-interval-s. This avoids per-sweeper timer pools while still
   honoring per-sweeper cadence.

   Registers itself as an IShutdownHook at priority 50 so sweeps stop
   before subprocess kills (priority 100+) fire."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.system.registry :as reg]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService
                                 TimeUnit ScheduledFuture]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private executor-atom (atom nil))   ; ScheduledExecutorService
(defonce ^:private handle-atom   (atom nil))   ; ScheduledFuture
(defonce ^:private running?-atom (atom false))
(defonce ^:private last-run-at   (atom {}))    ; sweep-name -> ms
(defonce ^:private run-counts    (atom {}))    ; sweep-name -> int

;; =============================================================================
;; Config
;; =============================================================================

(def ^:private min-heartbeat-s 10)
(def ^:private max-heartbeat-s 60)
(def ^:private default-heartbeat-s 30)

;; =============================================================================
;; Internals
;; =============================================================================

(defn- current-heartbeat-s
  "Compute the heartbeat cadence as min(registered sweep intervals), clamped
   to [min-heartbeat-s, max-heartbeat-s]. Falls back to default when the
   sweep registry is empty."
  []
  (let [sweepers  (reg/registered-sweeps)
        intervals (map lifecycle/sweep-interval-s sweepers)]
    (if (empty? intervals)
      default-heartbeat-s
      (-> (reduce min intervals)
          (max min-heartbeat-s)
          (min max-heartbeat-s)))))

(defn- run-due-sweeps!
  "Iterate the sweep registry once, invoking sweep! on any impl whose
   per-sweeper interval has elapsed since its last run. Errors are caught
   at Throwable granularity so a single misbehaving sweeper cannot stop
   the heartbeat."
  []
  (let [now (System/currentTimeMillis)]
    (doseq [impl (reg/registered-sweeps)]
      (let [sname       (lifecycle/sweep-name impl)
            interval-ms (* 1000 (long (lifecycle/sweep-interval-s impl)))
            last        (get @last-run-at sname 0)]
        (when (>= (- now last) interval-ms)
          (try
            (lifecycle/sweep! impl {:now-ms now})
            (swap! last-run-at assoc sname now)
            (swap! run-counts update sname (fnil inc 0))
            (catch Throwable t
              (log/error t "Sweep error" {:name sname}))))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start!
  "Start the single-timer sweep coordinator. Idempotent — no-op if already
   running. Creates a single-threaded ScheduledExecutorService and schedules
   run-due-sweeps! at a fixed rate equal to current-heartbeat-s."
  []
  (when-not @running?-atom
    (let [heartbeat-s (current-heartbeat-s)
          ^ScheduledExecutorService exec
          (or @executor-atom
              (let [e (Executors/newSingleThreadScheduledExecutor)]
                (reset! executor-atom e)
                e))
          handle (.scheduleAtFixedRate
                  exec
                  ^Runnable run-due-sweeps!
                  (long heartbeat-s)
                  (long heartbeat-s)
                  TimeUnit/SECONDS)]
      (reset! handle-atom handle)
      (reset! running?-atom true)
      (log/info "Sweep coordinator started"
                {:heartbeat-s heartbeat-s
                 :registered  (count (reg/registered-sweeps))}))))

(defn stop!
  "Stop the sweep coordinator. Idempotent — safe to call when not running.
   Cancels the scheduled handle, shuts down the executor, and resets state."
  []
  (when-let [^ScheduledFuture handle @handle-atom]
    (try (.cancel handle false)
         (catch Throwable t
           (log/warn t "Sweep coordinator cancel failed"))))
  (reset! handle-atom nil)
  (when-let [^ScheduledExecutorService exec @executor-atom]
    (try (.shutdownNow exec)
         (catch Throwable t
           (log/warn t "Sweep coordinator executor shutdown failed"))))
  (reset! executor-atom nil)
  (when @running?-atom
    (log/info "Sweep coordinator stopped"))
  (reset! running?-atom false))

(defn status
  "Return a pure read of coordinator state, intended for the /status tool."
  []
  {:running?    @running?-atom
   :heartbeat-s (current-heartbeat-s)
   :last-run-at @last-run-at
   :run-counts  @run-counts
   :registered  (mapv lifecycle/sweep-name (reg/registered-sweeps))})

;; =============================================================================
;; Self-registration as IShutdownHook (priority 50)
;; =============================================================================

(defrecord SweepCoordinatorShutdown []
  lifecycle/IShutdownHook
  (shutdown-priority [_] 50)
  (shutdown-name     [_] "sweep-coordinator/stop")
  (shutdown!         [_ _ctx] (stop!)))

;; Auto-register on ns load so orchestrated shutdown stops sweeps before
;; subprocess kills (priority 100+) and store closes (priority 300+) run.
(reg/register-shutdown! (->SweepCoordinatorShutdown))
