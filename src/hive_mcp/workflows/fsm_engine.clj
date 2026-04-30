(ns hive-mcp.workflows.fsm-engine
  "FSMWorkflowEngine -- IWorkflowEngine backed by hive.events.fsm.

   Bridges the IWorkflowEngine protocol to the hive.events.fsm runtime.
   Uses the workflow registry for spec/compiled FSM lookup and the FSM
   engine for execution.

   Architecture:
   - load-workflow:     registry/get-workflow + registry/get-spec lookup
   - validate-workflow: check :compiled exists, verify FSM structure
   - execute-workflow:  fsm/run with resources from opts, map result to protocol shape
   - execute-step:      fsm/step on constructed halted FSM
   - get-status:        atom tracking {workflow-id -> status-map}
   - cancel-workflow:   mark :cancelled in status tracking (+ assoc :quenched? for forge-belt)"

  (:require [clojure.string]
            [hive-mcp.protocols.workflow :as proto]
            [hive-mcp.workflows.registry :as registry]
            [hive.events.fsm :as fsm]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Status Tracking
;; =============================================================================

(defonce ^:private workflow-statuses
  (atom {}))

(defn- generate-workflow-id
  "Generate a unique workflow instance ID."
  [workflow-name]
  (str (name workflow-name) "-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn- update-status!
  "Update tracking status for a workflow instance."
  [workflow-id status-map]
  (swap! workflow-statuses assoc workflow-id status-map))

(defn- get-tracked-status
  "Get tracked status for a workflow instance."
  [workflow-id]
  (get @workflow-statuses workflow-id))

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private privileged-states
  "FSM states that are engine-internal, not user-defined steps."
  #{:hive.events.fsm/start :hive.events.fsm/end
    :hive.events.fsm/halt  :hive.events.fsm/error})

(defn- extract-user-states
  "Extract user-defined states from an FSM graph (excluding privileged states)."
  [fsm-graph]
  (vec (remove privileged-states (keys fsm-graph))))

(defn- states->steps
  "Convert FSM state keywords to protocol step definitions."
  [user-states]
  (mapv (fn [state-id]
          {:step-id     (str state-id)
           :title       (name state-id)
           :description (str "FSM state: " state-id)
           :dependencies []})
        user-states))

(defn- normalize-workflow-name
  "Normalize workflow name to keyword."
  [workflow-name]
  (if (keyword? workflow-name)
    workflow-name
    (keyword workflow-name)))

(defn- parse-step-id
  "Parse a step-id string back to a qualified keyword.
   Handles both namespace-qualified (\"ns/name\") and simple (\"name\") forms."
  [step-id-str]
  (if (string? step-id-str)
    (if-let [slash-idx (clojure.string/index-of step-id-str "/")]
      (keyword (subs step-id-str 0 slash-idx)
               (subs step-id-str (inc slash-idx)))
      (keyword step-id-str))
    step-id-str))

;; =============================================================================
;; FSMWorkflowEngine
;; =============================================================================

(defrecord FSMWorkflowEngine []
  proto/IWorkflowEngine

  (load-workflow [_ workflow-name opts]
    (try
      (let [wf-name     (normalize-workflow-name workflow-name)
            compiled    (registry/get-workflow wf-name)
            spec        (registry/get-spec wf-name)
            workflow-id (generate-workflow-id wf-name)]
        (if compiled
          (let [fsm-graph   (:fsm compiled)
                user-states (extract-user-states fsm-graph)
                steps       (states->steps user-states)]
            ;; Track initial status
            (update-status! workflow-id
                            {:workflow-id    workflow-id
                             :name           (name wf-name)
                             :status         :pending
                             :current-step   nil
                             :steps-completed 0
                             :steps-total    (count steps)
                             :started-at     nil
                             :completed-at   nil
                             :errors         []
                             :progress       0.0})
            {:workflow-id workflow-id
             :name        (name wf-name)
             :steps       steps
             :metadata    {:engine    :fsm
                           :compiled? true
                           :scope     (:scope opts)}
             :loaded?     true
             :errors      []
             ;; Internal: carry compiled FSM for execution
             :_compiled   compiled
             :_spec       spec})
          {:workflow-id workflow-id
           :name        (name wf-name)
           :steps       []
           :metadata    {:engine :fsm :compiled? false}
           :loaded?     false
           :errors      [(str "Workflow not found in registry: " wf-name)]}))
      (catch Throwable e
        (let [workflow-id (generate-workflow-id (or workflow-name "unknown"))]
          {:workflow-id workflow-id
           :name        (str workflow-name)
           :steps       []
           :metadata    {:engine :fsm}
           :loaded?     false
           :errors      [(str "Failed to load workflow: " (ex-message e))]}))))

  (validate-workflow [_ workflow]
    (try
      (let [compiled (:_compiled workflow)]
        (cond
          (not (:loaded? workflow))
          {:valid?           false
           :errors           (or (:errors workflow) ["Workflow not loaded"])
           :warnings         []
           :dependency-order []}

          (nil? compiled)
          {:valid?           false
           :errors           ["No compiled FSM found for workflow"]
           :warnings         []
           :dependency-order []}

          :else
          (let [fsm-graph  (:fsm compiled)
                has-start? (contains? fsm-graph :hive.events.fsm/start)
                has-end?   (contains? fsm-graph :hive.events.fsm/end)
                has-error? (contains? fsm-graph :hive.events.fsm/error)
                user-states (extract-user-states fsm-graph)
                errors   (cond-> []
                           (not has-start?) (conj "Missing ::fsm/start state")
                           (not has-end?)   (conj "Missing ::fsm/end state")
                           (not has-error?) (conj "Missing ::fsm/error state"))
                warnings (cond-> []
                           (empty? user-states) (conj "No user-defined states found"))]
            {:valid?           (empty? errors)
             :errors           errors
             :warnings         warnings
             :dependency-order (mapv str user-states)})))
      (catch Throwable e
        {:valid?           false
         :errors           [(str "Validation error: " (ex-message e))]
         :warnings         []
         :dependency-order []})))

  (execute-step [_ workflow step-id opts]
    (let [start-time (System/currentTimeMillis)]
      (try
        (let [compiled   (:_compiled workflow)
              resources  (or (:resources opts) (:context opts) {})
              state-kw   (parse-step-id step-id)
              ;; Construct a halted-like FSM state for fsm/step
              halted-fsm (or (:_halted-state opts)
                             {:fsm              (:fsm compiled)
                              :current-state-id state-kw
                              :data             (or (:context opts) {})})
              result     (fsm/step halted-fsm resources)
              duration   (- (System/currentTimeMillis) start-time)]
          {:success?    true
           :step-id     step-id
           :result      result
           :duration-ms duration
           :errors      []
           :context     (:data result result)})
        (catch Throwable e
          {:success?    false
           :step-id     step-id
           :result      nil
           :duration-ms (- (System/currentTimeMillis) start-time)
           :errors      [(ex-message e)]
           :context     (or (:context opts) {})}))))

  (execute-workflow [_ workflow opts]
    (let [start-time  (System/currentTimeMillis)
          workflow-id (:workflow-id workflow)]
      (try
        (let [compiled     (:_compiled workflow)
              resources    (or (:resources opts) (:context opts) {})
              initial-data (or (:initial-data opts) (:data opts) {})
              steps        (:steps workflow)
              total-steps  (count steps)]
          ;; Update status to running
          (update-status! workflow-id
                          {:workflow-id     workflow-id
                           :name            (:name workflow)
                           :status          :running
                           :current-step    nil
                           :steps-completed 0
                           :steps-total     total-steps
                           :started-at      (str (java.time.Instant/now))
                           :completed-at    nil
                           :errors          []
                           :progress        0.0})
          ;; Execute the FSM
          (let [result   (fsm/run compiled resources {:data initial-data})
                duration (- (System/currentTimeMillis) start-time)]
            ;; Update status to completed
            (update-status! workflow-id
                            {:workflow-id     workflow-id
                             :name            (:name workflow)
                             :status          :completed
                             :current-step    nil
                             :steps-completed total-steps
                             :steps-total     total-steps
                             :started-at      (:started-at (get-tracked-status workflow-id))
                             :completed-at    (str (java.time.Instant/now))
                             :errors          []
                             :progress        1.0})
            ;; Call on-step-complete callback if provided
            (when-let [callback (:on-step-complete opts)]
              (callback {:workflow-id workflow-id :status :completed}))
            {:success?       true
             :workflow-id    workflow-id
             :steps-completed total-steps
             :steps-total    total-steps
             :results        {:final result}
             :duration-ms    duration
             :errors         []
             :final-context  result}))
        (catch Throwable e
          (let [duration (- (System/currentTimeMillis) start-time)]
            ;; Update status to failed
            (update-status! workflow-id
                            (merge (or (get-tracked-status workflow-id) {})
                                   {:status       :failed
                                    :completed-at (str (java.time.Instant/now))
                                    :errors       [(ex-message e)]}))
            {:success?       false
             :workflow-id    workflow-id
             :steps-completed 0
             :steps-total    (count (:steps workflow))
             :results        {}
             :duration-ms    duration
             :errors         [(ex-message e)]
             :final-context  {}})))))

  (get-status [_ workflow-id]
    (get-tracked-status workflow-id))

  (cancel-workflow [_ workflow-id opts]
    (try
      (let [current-status (get-tracked-status workflow-id)]
        (if current-status
          (let [already-done? (contains? #{:completed :cancelled :failed}
                                         (:status current-status))]
            (if already-done?
              ;; Idempotent -- already in terminal state
              {:success?       true
               :workflow-id    workflow-id
               :status         (:status current-status)
               :steps-completed (:steps-completed current-status 0)
               :errors         []}
              ;; Actually cancel
              (do
                (update-status!
                 workflow-id
                 (assoc current-status
                        :status :cancelled
                        :completed-at (str (java.time.Instant/now))
                        :errors (conj (or (:errors current-status) [])
                                      (str "Cancelled: "
                                           (or (:reason opts) "user-requested")))))
                {:success?       true
                 :workflow-id    workflow-id
                 :status         :cancelled
                 :steps-completed (:steps-completed current-status 0)
                 :errors         []})))
          {:success?       false
           :workflow-id    workflow-id
           :status         :unknown
           :steps-completed 0
           :errors         ["Unknown workflow-id"]}))
      (catch Throwable e
        {:success?       false
         :workflow-id    workflow-id
         :status         :unknown
         :steps-completed 0
         :errors         [(str "Cancel failed: " (ex-message e))]}))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-engine
  "Create a new FSMWorkflowEngine instance."
  []
  (->FSMWorkflowEngine))

;; =============================================================================
;; Utility
;; =============================================================================

(defn reset-statuses!
  "Clear all tracked workflow statuses. For testing."
  []
  (reset! workflow-statuses {})
  nil)
