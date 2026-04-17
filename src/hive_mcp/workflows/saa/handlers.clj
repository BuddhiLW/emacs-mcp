(ns hive-mcp.workflows.saa.handlers
  "FSM state handlers for the SAA (Silence-Abstract-Act) workflow.

   Normal handlers: (resources, data) -> data'
   Terminal handlers: (resources, fsm) -> result

   Side effects flow through the resources map (dependency injection).
   Each handler is a pure-ish function that transforms FSM state data,
   with I/O delegated to resource functions.

   DDD: Domain Service — stateful workflow step execution."
  (:require [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- maybe-shout!
  "Call shout-fn if available. Non-critical side effect."
  [resources agent-id phase message]
  (when-let [shout-fn (:shout-fn resources)]
    (rescue nil (shout-fn agent-id phase message))))

(defn- now-str
  "Get current time as ISO string using clock-fn from resources."
  [resources]
  (str ((or (:clock-fn resources) #(java.time.Instant/now)))))


;; =============================================================================
;; Normal State Handlers: (resources, data) -> data'
;; =============================================================================

(defn handle-start
  "Initialize SAA session, resolve agent/project context.
   EDN handler key: :start"
  [resources data]
  (let [scope-fn (or (:scope-fn resources) (constantly nil))
        directory (or (:directory data) (:directory resources))
        agent-id (or (:agent-id data) (:agent-id resources))
        task (:task data)]
    (log/info "[saa-fsm] Starting SAA workflow" {:agent-id agent-id :task task})
    (maybe-shout! resources agent-id :start (str "SAA starting: " task))
    (if (and agent-id task)
      (let [project-id (when (and scope-fn directory)
                         (rescue nil (scope-fn directory)))]
        (assoc data
               :agent-id agent-id
               :directory directory
               :project-id (or project-id "unknown")
               :started-at (now-str resources)
               :phase :start
               :grounding-threshold (get data :grounding-threshold 0.6)
               :silence-iterations 0
               :abstract-retries 0
               :plan-only? (get data :plan-only? false)
               :error nil))
      (assoc data :error "Missing required fields: :agent-id and :task"))))

(defn handle-catchup
  "Load project context from memory (axioms, conventions, decisions, KG).
   EDN handler key: :catchup"
  [resources data]
  (let [{:keys [agent-id directory]} data
        catchup-fn (:catchup-fn resources)]
    (log/info "[saa-fsm] Catchup phase" {:agent-id agent-id})
    (maybe-shout! resources agent-id :catchup "Loading project context")
    (if catchup-fn
      (try
        (let [context (catchup-fn agent-id directory)]
          (assoc data
                 :phase :catchup
                 :context-loaded? true
                 :axioms (get context :axioms [])
                 :conventions (get context :conventions [])
                 :decisions (get context :decisions [])))
        (catch Exception e
          (log/error "[saa-fsm] Catchup failed" {:error (ex-message e)})
          (assoc data
                 :phase :catchup
                 :context-loaded? false
                 :error (str "Catchup failed: " (ex-message e)))))
      (assoc data
             :phase :catchup
             :context-loaded? true
             :axioms []
             :conventions []
             :decisions []))))

(defn handle-silence
  "Execute Silence phase: explore codebase with read-only tools.
   EDN handler key: :silence"
  [resources data]
  (let [{:keys [agent-id task observations silence-iterations]} data
        explore-fn (:explore-fn resources)
        iteration (inc (or silence-iterations 0))]
    (log/info "[saa-fsm] Silence phase iteration" {:iteration iteration :agent-id agent-id})
    (maybe-shout! resources agent-id :silence
                  (str "Silence phase (iteration " iteration "): exploring"))
    (if explore-fn
      (try
        (let [result (explore-fn task agent-id (or observations []))]
          (assoc data
                 :phase :silence
                 :observations (get result :observations observations)
                 :files-read (get result :files-read 0)
                 :discoveries (get result :discoveries 0)
                 :silence-started (or (:silence-started data) (now-str resources))
                 :silence-iterations iteration))
        (catch Exception e
          (log/error "[saa-fsm] Silence exploration failed" {:error (ex-message e)})
          (assoc data
                 :phase :silence
                 :silence-iterations iteration
                 :error (str "Silence failed: " (ex-message e)))))
      (assoc data
             :phase :silence
             :observations (or observations [{:type :task-description :content task}])
             :files-read 0
             :discoveries 0
             :silence-started (or (:silence-started data) (now-str resources))
             :silence-iterations iteration))))

(defn handle-silence-review
  "Evaluate observations and decide if grounding is sufficient.
   EDN handler key: :silence-review"
  [resources data]
  (let [{:keys [agent-id observations files-read]} data
        score-fn (or (:score-grounding-fn resources)
                     (fn [obs files]
                       (min 1.0
                            (+ (if (seq obs) 0.3 0.0)
                               (min 0.4 (* 0.1 (count obs)))
                               (if (pos? (or files 0)) 0.3 0.0)))))
        score (double (score-fn observations files-read))]
    (log/info "[saa-fsm] Silence review" {:score score :obs-count (count observations)
                                          :files-read files-read})
    (maybe-shout! resources agent-id :silence-review
                  (str "Grounding score: " (format "%.2f" score)
                       " (threshold: " (:grounding-threshold data) ")"
                       " iteration: " (:silence-iterations data)))
    (assoc data
           :phase :silence-review
           :grounding-score score
           :silence-ended (now-str resources))))

(defn handle-abstract
  "Synthesize observations into a structured EDN plan.
   EDN handler key: :abstract"
  [resources data]
  (let [{:keys [agent-id task observations abstract-retries]} data
        synthesize-fn (:synthesize-fn resources)
        context (select-keys data [:axioms :conventions :decisions :project-id])
        retries (inc (or abstract-retries 0))]
    (log/info "[saa-fsm] Abstract phase" {:retry retries :obs-count (count observations)})
    (maybe-shout! resources agent-id :abstract
                  (str "Abstract phase: synthesizing plan"
                       (when (> retries 1) (str " (retry " (dec retries) ")"))))
    (if synthesize-fn
      (try
        (let [plan (synthesize-fn task observations context)]
          (assoc data
                 :phase :abstract
                 :plan plan
                 :abstract-started (or (:abstract-started data) (now-str resources))
                 :abstract-retries retries))
        (catch Exception e
          (log/error "[saa-fsm] Plan synthesis failed" {:error (ex-message e)})
          (assoc data
                 :phase :abstract
                 :plan nil
                 :abstract-retries retries
                 :error (str "Synthesis failed: " (ex-message e)))))
      (assoc data
             :phase :abstract
             :plan nil
             :abstract-retries retries
             :error "No synthesize-fn provided in resources"))))

(defn handle-validate-plan
  "Validate plan integrity: dependencies, files, waves, no cycles.
   EDN handler key: :validate-plan"
  [resources data]
  (let [{:keys [agent-id plan]} data
        validate-fn (or (:validate-plan-fn resources)
                        (fn [p]
                          (if (seq (:steps p))
                            {:valid? true :errors []}
                            {:valid? false :errors ["Plan has no steps"]})))]
    (log/info "[saa-fsm] Validating plan")
    (try
      (let [{:keys [valid? errors]} (validate-fn plan)]
        (maybe-shout! resources agent-id :validate-plan
                      (if valid?
                        "Plan validation passed"
                        (str "Plan validation failed: " (pr-str errors))))
        (assoc data
               :phase :validate-plan
               :plan-valid? (boolean valid?)
               :validation-errors (or errors [])))
      (catch Exception e
        (log/error "[saa-fsm] Plan validation failed" {:error (ex-message e)})
        (assoc data
               :phase :validate-plan
               :plan-valid? false
               :validation-errors [(str "Validation exception: " (ex-message e))])))))

(defn handle-store-plan
  "Store plan in memory and optionally convert to kanban tasks.
   EDN handler key: :store-plan"
  [resources data]
  (let [{:keys [plan agent-id directory]} data
        store-fn (:store-plan-fn resources)]
    (log/info "[saa-fsm] Storing plan" {:agent-id agent-id})
    (maybe-shout! resources agent-id :store-plan "Storing plan in memory")
    (if store-fn
      (try
        (let [{:keys [memory-id kanban-ids kg-edges]} (store-fn plan agent-id directory)]
          (assoc data
                 :phase :store-plan
                 :plan-memory-id memory-id
                 :kanban-task-ids (or kanban-ids [])
                 :kg-edges-created (or kg-edges 0)))
        (catch Exception e
          (log/error "[saa-fsm] Plan storage failed" {:error (ex-message e)})
          (assoc data
                 :phase :store-plan
                 :plan-memory-id nil
                 :kanban-task-ids []
                 :kg-edges-created 0)))
      (assoc data
             :phase :store-plan
             :plan-memory-id nil
             :kanban-task-ids []
             :kg-edges-created 0))))

(defn handle-act-dispatch
  "Dispatch plan execution via DAG-Wave, ling spawn, or direct execution.
   EDN handler key: :act-dispatch"
  [resources data]
  (let [{:keys [plan agent-id execution-mode]} data
        dispatch-fn (:dispatch-fn resources)
        mode (or execution-mode :direct)]
    (log/info "[saa-fsm] Act dispatch" {:mode mode :agent-id agent-id})
    (maybe-shout! resources agent-id :act-dispatch
                  (str "Act phase: dispatching execution (mode: " (name mode) ")"))
    (if dispatch-fn
      (try
        (let [{:keys [wave-id result]} (dispatch-fn plan mode agent-id)]
          (assoc data
                 :phase :act-dispatch
                 :execution-mode mode
                 :wave-id wave-id
                 :execution-result result
                 :act-started (or (:act-started data) (now-str resources))))
        (catch Exception e
          (log/error "[saa-fsm] Dispatch failed" {:error (ex-message e)})
          (assoc data
                 :phase :act-dispatch
                 :execution-mode mode
                 :error (str "Dispatch failed: " (ex-message e)))))
      (assoc data
             :phase :act-dispatch
             :execution-mode mode
             :execution-result {:status :no-dispatch-fn}
             :act-started (or (:act-started data) (now-str resources))))))

(defn handle-act-verify
  "Verify execution results with TDD, lint, integration checks.
   EDN handler key: :act-verify"
  [resources data]
  (let [{:keys [agent-id execution-result plan]} data
        verify-fn (:verify-fn resources)]
    (log/info "[saa-fsm] Act verify")
    (maybe-shout! resources agent-id :act-verify "Verifying execution results")
    (if verify-fn
      (try
        (let [{:keys [passed? details]} (verify-fn execution-result plan)]
          (assoc data
                 :phase :act-verify
                 :tests-passed? (boolean passed?)
                 :verification details
                 :act-ended (now-str resources)))
        (catch Exception e
          (log/warn "[saa-fsm] Verification failed" {:error (ex-message e)})
          (assoc data
                 :phase :act-verify
                 :tests-passed? false
                 :verification {:error (ex-message e)}
                 :act-ended (now-str resources))))
      (assoc data
             :phase :act-verify
             :tests-passed? true
             :verification {:status :no-verify-fn}
             :act-ended (now-str resources)))))


;; =============================================================================
;; Terminal State Handlers: (resources, fsm) -> result
;; =============================================================================

(defn handle-end
  "Terminal state handler. Returns SAA summary.
   EDN handler key: :end"
  [resources {:keys [data]}]
  (let [agent-id (:agent-id data)
        build-fn (:build-summary-fn resources)]
    (log/info "[saa-fsm] SAA workflow complete"
              {:agent-id agent-id
               :plan-only? (:plan-only? data)
               :observations (count (get data :observations []))
               :plan-valid? (:plan-valid? data)
               :tests-passed? (:tests-passed? data)})
    (maybe-shout! resources agent-id :end "SAA workflow complete")
    (if build-fn
      (build-fn data)
      (select-keys data [:agent-id :project-id :task :phase
                         :observations :grounding-score
                         :plan :plan-valid? :plan-memory-id :kanban-task-ids
                         :execution-result :tests-passed? :verification
                         :started-at :silence-started :silence-ended
                         :abstract-started :act-started :act-ended
                         :silence-iterations :abstract-retries
                         :plan-only?]))))

(defn handle-error
  "Error state handler. Captures error context.
   EDN handler key: :error"
  [resources {:keys [error data] :as _fsm}]
  (let [agent-id (:agent-id data)
        error-fn (:error-response-fn resources)]
    (log/error "[saa-fsm] SAA workflow error"
               {:agent-id agent-id :phase (:phase data) :error error})
    (maybe-shout! resources agent-id :error
                  (str "SAA FAILED at phase " (:phase data) ": " error))
    (if error-fn
      (error-fn {:phase (:phase data)
                 :agent-id agent-id
                 :task (:task data)
                 :data data
                 :error error})
      (throw (ex-info "SAA workflow error"
                      {:phase (:phase data)
                       :agent-id agent-id
                       :task (:task data)
                       :data (select-keys data [:error :plan-valid? :validation-errors
                                                :grounding-score :tests-passed?])
                       :error error})))))
