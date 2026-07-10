(ns hive-mcp.workflows.saa.handlers
  "FSM state handlers for the SAA (Silence-Abstract-Act) workflow.

   Normal handlers: (resources, data) -> data'
   Terminal handlers: (resources, fsm) -> result

   Side effects flow through the resources map (dependency injection).
   Each handler is a pure-ish function that transforms FSM state data,
   with I/O delegated to resource functions.

   DDD: Domain Service — stateful workflow step execution.
   The try/catch/log envelope + clock + shout seams live in
   hive-mcp.workflows.support (boundary-step / fatal / continue / now-str / shout!)."
  (:require [taoensso.timbre :as log]
            [hive-mcp.workflows.support :as support]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Normal State Handlers: (resources, data) -> data'
;; =============================================================================

(defn handle-start
  "Initialize SAA session, resolve agent/project context.
   EDN handler key: :start"
  [resources data]
  (let [agent-id (or (:agent-id data) (:agent-id resources))
        task (:task data)]
    (log/info "[saa-fsm] Starting SAA workflow" {:agent-id agent-id :task task})
    (support/shout! resources agent-id :start (str "SAA starting: " task))
    (if (and agent-id task)
      (let [{:keys [directory project-id]}
            (support/resolve-session-identity
             resources data {:rescue-scope? true :project-default "unknown"})]
        (assoc data
               :agent-id agent-id
               :directory directory
               :project-id project-id
               :started-at (support/now-str resources)
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
    (support/shout! resources agent-id :catchup "Loading project context")
    (support/boundary-step data
      {:present? (some? catchup-fn)
       :run    (fn [d]
                 (let [context (catchup-fn agent-id directory)]
                   (assoc d
                          :phase :catchup
                          :context-loaded? true
                          :axioms (get context :axioms [])
                          :conventions (get context :conventions [])
                          :decisions (get context :decisions []))))
       :absent (fn [d]
                 (assoc d
                        :phase :catchup
                        :context-loaded? true
                        :axioms []
                        :conventions []
                        :decisions []))
       :policy support/fatal
       :spec   {:log-msg "[saa-fsm] Catchup failed"
                :label "Catchup"
                :degrade-keys {:phase :catchup :context-loaded? false}}})))

(defn handle-silence
  "Execute Silence phase: explore codebase with read-only tools.
   EDN handler key: :silence"
  [resources data]
  (let [{:keys [agent-id task observations silence-iterations]} data
        explore-fn (:explore-fn resources)
        iteration (inc (or silence-iterations 0))]
    (log/info "[saa-fsm] Silence phase iteration" {:iteration iteration :agent-id agent-id})
    (support/shout! resources agent-id :silence
                    (str "Silence phase (iteration " iteration "): exploring"))
    (support/boundary-step data
      {:present? (some? explore-fn)
       :run    (fn [d]
                 (let [result (explore-fn task agent-id (or observations []))]
                   (assoc d
                          :phase :silence
                          :observations (get result :observations observations)
                          :files-read (get result :files-read 0)
                          :discoveries (get result :discoveries 0)
                          :silence-started (or (:silence-started data) (support/now-str resources))
                          :silence-iterations iteration)))
       :absent (fn [d]
                 (assoc d
                        :phase :silence
                        :observations (or observations [{:type :task-description :content task}])
                        :files-read 0
                        :discoveries 0
                        :silence-started (or (:silence-started data) (support/now-str resources))
                        :silence-iterations iteration))
       :policy support/fatal
       :spec   {:log-msg "[saa-fsm] Silence exploration failed"
                :label "Silence"
                :degrade-keys {:phase :silence :silence-iterations iteration}}})))

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
    (support/shout! resources agent-id :silence-review
                    (str "Grounding score: " (format "%.2f" score)
                         " (threshold: " (:grounding-threshold data) ")"
                         " iteration: " (:silence-iterations data)))
    (assoc data
           :phase :silence-review
           :grounding-score score
           :silence-ended (support/now-str resources))))

(defn handle-abstract
  "Synthesize observations into a structured EDN plan.
   EDN handler key: :abstract"
  [resources data]
  (let [{:keys [agent-id task observations abstract-retries]} data
        synthesize-fn (:synthesize-fn resources)
        context (select-keys data [:axioms :conventions :decisions :project-id])
        retries (inc (or abstract-retries 0))]
    (log/info "[saa-fsm] Abstract phase" {:retry retries :obs-count (count observations)})
    (support/shout! resources agent-id :abstract
                    (str "Abstract phase: synthesizing plan"
                         (when (> retries 1) (str " (retry " (dec retries) ")"))))
    (support/boundary-step data
      {:present? (some? synthesize-fn)
       :run    (fn [d]
                 (let [plan (synthesize-fn task observations context)]
                   ;; Boundary contract: :plan nil IFF :error set. A synthesize-fn
                   ;; that returns nil WITHOUT throwing is a synthesis failure, so
                   ;; the ::abstract plan-nil-with-error? edge fires and the FSM
                   ;; routes to ::fsm/error rather than dead-ending.
                   (if (nil? plan)
                     (do
                       (log/warn "[saa-fsm] Plan synthesis returned nil plan")
                       (assoc d
                              :phase :abstract
                              :plan nil
                              :abstract-retries retries
                              :error "Synthesis returned nil plan"))
                     (assoc d
                            :phase :abstract
                            :plan plan
                            :abstract-started (or (:abstract-started data) (support/now-str resources))
                            :abstract-retries retries))))
       :absent (fn [d]
                 (assoc d
                        :phase :abstract
                        :plan nil
                        :abstract-retries retries
                        :error "No synthesize-fn provided in resources"))
       :policy support/fatal
       :spec   {:log-msg "[saa-fsm] Plan synthesis failed"
                :label "Synthesis"
                :degrade-keys {:phase :abstract :plan nil :abstract-retries retries}}})))

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
    (support/boundary-step data
      {:run   (fn [d]
                (let [{:keys [valid? errors]} (validate-fn plan)]
                  (support/shout! resources agent-id :validate-plan
                                  (if valid?
                                    "Plan validation passed"
                                    (str "Plan validation failed: " (pr-str errors))))
                  (assoc d
                         :phase :validate-plan
                         :plan-valid? (boolean valid?)
                         :validation-errors (or errors []))))
       :policy support/continue
       :spec  {:log-level :error
               :log-msg "[saa-fsm] Plan validation failed"
               :log-arg (fn [ex] {:error (ex-message ex)})
               :delta (fn [ex] {:phase :validate-plan
                                :plan-valid? false
                                :validation-errors [(str "Validation exception: " (ex-message ex))]})}})))

(defn handle-store-plan
  "Store plan in memory and optionally convert to kanban tasks.
   EDN handler key: :store-plan"
  [resources data]
  (let [{:keys [plan agent-id directory]} data
        store-fn (:store-plan-fn resources)
        skip {:phase :store-plan
              :plan-memory-id nil
              :kanban-task-ids []
              :kg-edges-created 0}]
    (log/info "[saa-fsm] Storing plan" {:agent-id agent-id})
    (support/shout! resources agent-id :store-plan "Storing plan in memory")
    (support/boundary-step data
      {:present? (some? store-fn)
       :run    (fn [d]
                 (let [{:keys [memory-id kanban-ids kg-edges]} (store-fn plan agent-id directory)]
                   (assoc d
                          :phase :store-plan
                          :plan-memory-id memory-id
                          :kanban-task-ids (or kanban-ids [])
                          :kg-edges-created (or kg-edges 0))))
       :absent (fn [d] (merge d skip))
       :policy support/continue
       :spec   {:log-level :error
                :log-msg "[saa-fsm] Plan storage failed"
                :log-arg (fn [ex] {:error (ex-message ex)})
                :delta skip}})))

(defn handle-act-dispatch
  "Dispatch plan execution via DAG-Wave, ling spawn, or direct execution.
   EDN handler key: :act-dispatch"
  [resources data]
  (let [{:keys [plan agent-id execution-mode]} data
        dispatch-fn (:dispatch-fn resources)
        mode (or execution-mode :direct)]
    (log/info "[saa-fsm] Act dispatch" {:mode mode :agent-id agent-id})
    (support/shout! resources agent-id :act-dispatch
                    (str "Act phase: dispatching execution (mode: " (name mode) ")"))
    (support/boundary-step data
      {:present? (some? dispatch-fn)
       :run    (fn [d]
                 (let [{:keys [wave-id result]} (dispatch-fn plan mode agent-id)]
                   (assoc d
                          :phase :act-dispatch
                          :execution-mode mode
                          :wave-id wave-id
                          :execution-result result
                          :act-started (or (:act-started data) (support/now-str resources)))))
       :absent (fn [d]
                 (assoc d
                        :phase :act-dispatch
                        :execution-mode mode
                        :execution-result {:status :no-dispatch-fn}
                        :act-started (or (:act-started data) (support/now-str resources))))
       :policy support/fatal
       :spec   {:log-msg "[saa-fsm] Dispatch failed"
                :label "Dispatch"
                :degrade-keys {:phase :act-dispatch :execution-mode mode}}})))

(defn handle-act-verify
  "Verify execution results with TDD, lint, integration checks.
   EDN handler key: :act-verify"
  [resources data]
  (let [{:keys [agent-id execution-result plan]} data
        verify-fn (:verify-fn resources)]
    (log/info "[saa-fsm] Act verify")
    (support/shout! resources agent-id :act-verify "Verifying execution results")
    (support/boundary-step data
      {:present? (some? verify-fn)
       :run    (fn [d]
                 (let [{:keys [passed? details]} (verify-fn execution-result plan)]
                   (assoc d
                          :phase :act-verify
                          :tests-passed? (boolean passed?)
                          :verification details
                          :act-ended (support/now-str resources))))
       :absent (fn [d]
                 (assoc d
                        :phase :act-verify
                        :tests-passed? true
                        :verification {:status :no-verify-fn}
                        :act-ended (support/now-str resources)))
       :policy support/continue
       :spec   {:log-level :warn
                :log-msg "[saa-fsm] Verification failed"
                :log-arg (fn [ex] {:error (ex-message ex)})
                :delta (fn [ex] {:phase :act-verify
                                 :tests-passed? false
                                 :verification {:error (ex-message ex)}
                                 :act-ended (support/now-str resources)})}})))


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
    (support/shout! resources agent-id :end "SAA workflow complete")
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
    (support/shout! resources agent-id :error
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
