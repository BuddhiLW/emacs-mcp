(ns hive-mcp.workflows.saa.predicates
  "Dispatch predicates and FSM interceptors for the SAA workflow.

   All predicates are pure functions of state data — no side effects.
   Used by the FSM spec (both inline and EDN) to determine state transitions.

   Also contains FSM pre/post interceptors for trace logging and
   no-op subscription handlers.

   DDD: Value Objects — pure data predicates, no domain logic.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Dispatch Predicates (pure functions of state data)
;; =============================================================================

(defn has-required-fields?
  "Check that agent-id and task are present, and no startup error."
  [data]
  (and (:agent-id data)
       (:task data)
       (not (:error data))))

(defn has-startup-error?
  "Check for missing required fields or startup error."
  [data]
  (or (not (:agent-id data))
      (not (:task data))
      (some? (:error data))))

(defn context-loaded?
  "Check if catchup loaded project context."
  [data]
  (true? (:context-loaded? data)))

(defn has-observations?
  "Check if Silence phase produced observations."
  [data]
  (some? (:observations data)))

(defn has-error?
  "Check if data contains an error."
  [data]
  (some? (:error data)))

(defn grounding-sufficient?
  "Check if observation grounding score meets threshold."
  [data]
  (>= (get data :grounding-score 0.0)
      (get data :grounding-threshold 0.6)))

(defn grounding-insufficient-retryable?
  "Check if grounding is insufficient but retries remain."
  [data]
  (and (< (get data :grounding-score 0.0)
          (get data :grounding-threshold 0.6))
       (< (get data :silence-iterations 0) 3)))

(defn grounding-max-iterations?
  "Check if max silence iterations reached (proceed anyway)."
  [data]
  (>= (get data :silence-iterations 0) 3))

(defn has-plan?
  "Check if Abstract phase produced a plan."
  [data]
  (some? (:plan data)))

(defn plan-valid?
  "Check if plan passed validation."
  [data]
  (true? (:plan-valid? data)))

(defn plan-invalid-retryable?
  "Check if plan is invalid but retries remain."
  [data]
  (and (not (:plan-valid? data))
       (< (get data :abstract-retries 0) 2)))

(defn plan-invalid-final?
  "Check if plan is invalid and no retries remain."
  [data]
  (and (not (:plan-valid? data))
       (>= (get data :abstract-retries 0) 2)))

(defn plan-only?
  "Check if we should skip Act phase (plan-only mode)."
  [data]
  (true? (:plan-only? data)))

(defn full-execution?
  "Check if we should proceed to Act phase."
  [data]
  (not (:plan-only? data)))

(defn has-execution-result?
  "Check if Act dispatch produced a result."
  [data]
  (some? (:execution-result data)))

(defn tests-passed?
  "Check if verification succeeded."
  [data]
  (true? (:tests-passed? data)))

(defn tests-failed?
  "Check if verification failed."
  [data]
  (not (:tests-passed? data)))

(defn plan-nil-with-error?
  "Check if plan is nil AND an error occurred (abstract failed fatally)."
  [data]
  (and (nil? (:plan data)) (has-error? data)))

(defn context-not-loaded?
  "Complement of context-loaded? — catchup failed to load context."
  [data]
  (not (context-loaded? data)))

(defn always [_data] true)


;; =============================================================================
;; FSM Interceptors & Subscription Handlers
;; =============================================================================

(defn noop-subscription
  "No-op subscription handler. Placeholder for future monitoring."
  [_path _old _new]
  nil)

(defn trace-log-enter
  "Pre-interceptor: append :enter trace entry to data."
  [{:keys [current-state-id] :as fsm} _resources]
  (update-in fsm [:data :trace-log] (fnil conj [])
             {:state current-state-id
              :at (str (java.time.Instant/now))
              :direction :enter}))

(defn trace-log-exit
  "Post-interceptor: append :exit trace entry to data."
  [{:keys [current-state-id] :as fsm} _resources]
  (update-in fsm [:data :trace-log] (fnil conj [])
             {:state current-state-id
              :at (str (java.time.Instant/now))
              :direction :exit}))
