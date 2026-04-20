(ns hive-mcp.emacs.daemon-scoring
  "Pure health-scoring computation for Emacs daemons.

   Extracted from daemon-selection to separate pure scoring algorithms
   from I/O-bound selection and heartbeat orchestration.

   Contains:
   - Health classification (health-level, healthy?)
   - EWMA-based health score computation
   - Latency, error, and load penalty calculations
   - Pure heartbeat helpers (metrics, tx-data, report)

   DDD: Value Object / Domain Service (pure, no I/O).")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Core constants
;; =============================================================================

(def ^:const default-health-score
  "Default health score for daemons without an explicit score.
   Daemons start healthy until proven otherwise."
  100)


;; =============================================================================
;; Health classification
;; =============================================================================

(defn health-level
  "Classify a health score into a health level.

   Arguments:
     score - Health score 0-100 (nil treated as default-health-score)

   Returns:
     :healthy (70-100), :degraded (30-69), or :unhealthy (0-29)"
  [score]
  (let [s (or score default-health-score)]
    (cond
      (>= s 70) :healthy
      (>= s 30) :degraded
      :else     :unhealthy)))

(defn healthy?
  "Is the daemon healthy enough for new ling spawns?"
  [score]
  (not= :unhealthy (health-level score)))


;; =============================================================================
;; EWMA scoring constants
;; =============================================================================

(def ^:const ewma-alpha
  "EWMA smoothing factor. Higher = more weight on latest measurement.
   0.3 means 70% historical, 30% new — smooths transient spikes."
  0.3)

(def ^:const latency-healthy-ms
  "Response time threshold for full health. Below this = no penalty."
  500)

(def ^:const latency-degraded-ms
  "Response time threshold for degraded health. Above this = heavy penalty."
  2000)

(def ^:const latency-max-penalty
  "Maximum health points deducted for latency (scales linearly)."
  40)

(def ^:const error-penalty
  "Health points deducted per consecutive error (capped at error-penalty-max)."
  15)

(def ^:const error-penalty-max
  "Maximum cumulative error penalty."
  50)

(def ^:const ling-load-penalty-per
  "Health points deducted per ling beyond the first.
   Daemon with 5 lings = 4 * 2 = 8 points penalty."
  2)

(def ^:const recovery-bonus
  "Health points recovered per successful heartbeat after errors.
   Allows gradual recovery from error state."
  5)


;; =============================================================================
;; Score delta computations (pure)
;; =============================================================================

(defn latency-score-delta
  "Compute health score delta from response latency.

   Arguments:
     latency-ms - Response time in milliseconds

   Returns:
     Negative number (penalty) or 0 (no penalty).
     Fast responses (< 500ms): 0 penalty
     Slow responses (500-2000ms): linear penalty up to -40
     Very slow (>2000ms): full -40 penalty"
  [latency-ms]
  (cond
    (nil? latency-ms)              (- latency-max-penalty) ;; No measurement = assume worst
    (<= latency-ms latency-healthy-ms) 0
    (>= latency-ms latency-degraded-ms) (- latency-max-penalty)
    :else
    (let [range-ms  (- latency-degraded-ms latency-healthy-ms)
          excess-ms (- latency-ms latency-healthy-ms)
          ratio     (/ (double excess-ms) range-ms)]
      (- (int (* ratio latency-max-penalty))))))

(defn error-score-delta
  "Compute health score delta from consecutive error count.

   Arguments:
     consecutive-errors - Number of consecutive heartbeat failures

   Returns:
     Negative number (penalty) capped at error-penalty-max."
  [consecutive-errors]
  (- (min error-penalty-max
          (* (or consecutive-errors 0) error-penalty))))

(defn ling-load-delta
  "Compute health score delta from ling count (load pressure).

   Arguments:
     ling-count - Number of lings bound to daemon

   Returns:
     Negative number (penalty). First ling is free."
  [ling-count]
  (- (* (max 0 (dec (or ling-count 0))) ling-load-penalty-per)))

(defn compute-health-score
  "Compute a new health score using EWMA blending of current and previous scores.

   The raw score is 100 + penalties from latency, errors, and load.
   Then blended with the previous score using EWMA for smoothing.

   Arguments:
     prev-score         - Previous health score (0-100, nil = 100)
     latency-ms         - Response time of latest heartbeat (nil = failed)
     consecutive-errors - Number of consecutive heartbeat failures
     ling-count         - Number of lings bound to daemon

   Returns:
     New health score (0-100, integer)"
  [prev-score latency-ms consecutive-errors ling-count]
  (let [prev     (or prev-score default-health-score)
        ;; Raw instantaneous score from latest measurements
        raw      (max 0 (min 100
                             (+ 100
                                (latency-score-delta latency-ms)
                                (error-score-delta consecutive-errors)
                                (ling-load-delta ling-count))))
        ;; EWMA blend: smoothed = α * new + (1-α) * old
        blended  (+ (* ewma-alpha raw)
                    (* (- 1.0 ewma-alpha) prev))]
    (max 0 (min 100 (int blended)))))


;; =============================================================================
;; Heartbeat pure helpers (data builders, no I/O)
;; =============================================================================

(defn ping-metrics
  "Pure: derive (success?, latency-ms, new-errors) from a ping-result + prev-errors."
  [ping-result prev-errors]
  (let [success? (:success ping-result)]
    {:success?   success?
     :latency-ms (when success? (:duration-ms ping-result))
     :new-errors (if success? 0 (inc prev-errors))}))

(defn apply-recovery-bonus
  "Pure: add recovery-bonus to score iff ping succeeded after prior errors."
  [score success? prev-errors]
  (if (and success? (pos? prev-errors))
    (min 100 (+ score recovery-bonus))
    score))

(defn heartbeat-tx-data
  "Pure: build the DataScript tx payload for a heartbeat update."
  [eid now {:keys [success? new-errors]} final-score]
  (cond-> [{:db/id eid
            :emacs-daemon/health-score final-score
            :emacs-daemon/error-count  new-errors}]
    success?
    (conj {:db/id eid
           :emacs-daemon/heartbeat-at now
           :emacs-daemon/status :active})
    (not success?)
    (conj {:db/id eid
           :emacs-daemon/status (if (>= new-errors 3) :error :active)})))

(defn heartbeat-report
  "Pure: shape the public result map returned by heartbeat!."
  [{:keys [success? latency-ms new-errors]} final-score ling-count]
  (let [level (health-level final-score)]
    {:healthy?           (not= :unhealthy level)
     :health-score       final-score
     :health-level       level
     :latency-ms         latency-ms
     :consecutive-errors new-errors
     :ling-count         ling-count
     :success?           success?}))
