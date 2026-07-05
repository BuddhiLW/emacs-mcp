(ns hive-mcp.plan.fsm
  "Plan lifecycle FSM using hive.events.fsm.

   Models the plan lifecycle as a deterministic finite state machine:

     draft → validated → approved → executing → completed

   Each transition has guards (predicates) and actions (side effects).
   plan-to-kanban is the :approved → :executing transition action.

   ## Usage

   ```clojure
   (require '[hive-mcp.plan.fsm :as plan-fsm])

   ;; Create a plan and advance through states
   (-> (plan-fsm/create-plan {:title \"My Plan\" :steps [...]})
       (plan-fsm/validate!)
       (plan-fsm/approve!)
       (plan-fsm/execute! {:directory \"/project\"}))
   ```

   ## Integration with hive.events.fsm

   The FSM spec is compiled once and reused. Resources inject
   memory/kanban/KG clients for side effects during transitions."

  (:refer-clojure :exclude [compile])
  (:require [hive.events.fsm :as fsm]
            [hive-mcp.plan.schema :as schema]
            [hive-mcp.plan.parser :as parser]
            [hive-mcp.plan.field-registry :as field-registry]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Plan States (Value Objects)
;; =============================================================================

(def states
  "Valid plan states in lifecycle order."
  #{:draft :validated :approved :executing :completed})

;; =============================================================================
;; Guards (Pure Predicates)
;; =============================================================================

(defn schema-valid?
  "Guard: plan data conforms to Plan schema."
  [{:keys [plan]}]
  (and plan (schema/valid-plan? plan)))

(defn has-tasks?
  "Guard: plan has at least one step/task."
  [{:keys [plan]}]
  (seq (:steps plan)))

(defn human-approved?
  "Guard: plan has been marked as approved.
   In practice, this is always true when transitioning from :validated,
   because the transition itself IS the approval act."
  [{:keys [status]}]
  (= status :approved))

(defn all-tasks-done?
  "Guard: all kanban tasks linked to this plan are completed."
  [{:keys [task-statuses]}]
  (and (seq task-statuses)
       (every? #(= "done" %) (vals task-statuses))))

;; =============================================================================
;; FSM Handlers (Pure: resources, data -> new-data)
;; =============================================================================

(defn handle-draft
  "Draft state: parse content into structured plan if needed."
  [_resources data]
  (if (:plan data)
    ;; Already parsed — just tag status
    (assoc data :status :draft)
    ;; Parse from raw content
    (if-let [content (:content data)]
      (let [result (parser/parse-plan content {:memory-id (:plan-id data)})]
        (if (:success result)
          (assoc data
                 :plan (:plan result)
                 :status :draft
                 :parse-format (:source-format (:plan result)))
          (assoc data
                 :status :draft
                 :error (:error result)
                 :parse-failed true)))
      (assoc data :status :draft :error "No content or plan provided"))))

(defn handle-validate
  "Validate state: schema + dependency + cycle checks plus addon-registered
   field-value checks (plan.field-registry/validate-fields)."
  [_resources {:keys [plan] :as data}]
  (let [schema-result (schema/validate-plan plan)
        dep-result (schema/validate-dependencies plan)
        cycle-result (schema/detect-cycles plan)
        field-errors (into (field-registry/validate-fields plan :plan)
                           (mapcat #(field-registry/validate-fields % :step)
                                   (:steps plan)))
        fields-result {:valid (empty? field-errors) :errors field-errors}]
    (assoc data
           :status :validated
           :validation {:schema schema-result
                        :dependencies dep-result
                        :cycles cycle-result
                        :fields fields-result}
           :valid? (and (:valid schema-result)
                        (:valid dep-result)
                        (:valid cycle-result)
                        (:valid fields-result)))))

(defn handle-approve
  "Approve state: mark plan as approved (human decision captured)."
  [_resources data]
  (assoc data :status :approved))

(defn handle-execute
  "Execute state: create kanban tasks from plan steps.
   Side-effectful — uses resources for kanban/KG operations.
   Returns data with all execute-fn result fields merged.

   Catches executor exceptions to record :error/:errors on data so the
   FSM ::fsm/error transition can surface them — without this catch,
   exceptions bubble out of fsm/run as a generic 'handler error'."
  [resources data]
  (let [execute-fn (:execute-fn resources)]
    (if execute-fn
      (try
        (let [result (execute-fn data)]
          (merge data result {:status :executing}))
        (catch clojure.lang.ExceptionInfo e
          (assoc data
                 :status :execute-failed
                 :error  (.getMessage e)
                 :errors (or (:errors (ex-data e)) [(.getMessage e)])))
        (catch Throwable t
          (assoc data
                 :status :execute-failed
                 :error  (.getMessage t)
                 :errors [(.getMessage t)])))
      (assoc data :status :executing))))

(defn handle-complete
  "Complete state: all tasks done, archive plan."
  [_resources data]
  (assoc data :status :completed))

;; =============================================================================
;; FSM Spec
;; =============================================================================

(def plan-fsm-spec
  "Plan lifecycle FSM specification.

   States: draft → validated → approved → executing → completed
   Terminal: ::fsm/end (success), ::fsm/error (failure)

   Transition guards are the dispatch predicates."
  {:fsm {::fsm/start {:handler    handle-draft
                      :dispatches [[:validated (fn [data] (and (not (:parse-failed data))
                                                               (:plan data)))]
                                   [::fsm/error (constantly true)]]}

         :validated  {:handler    handle-validate
                      :dispatches [[:approved (fn [data] (:valid? data))]
                                   [::fsm/error (constantly true)]]}

         :approved   {:handler    handle-approve
                      :dispatches [[:executing (fn [data] (has-tasks? data))]
                                   [::fsm/error (constantly true)]]}

         :executing  {:handler    handle-execute
                      :dispatches [[::fsm/end (fn [data] (:task-ids data))]
                                   [::fsm/error (constantly true)]]}

         ::fsm/error {:handler (fn [_r fsm]
                                 (log/error "Plan FSM error" {:state (:current-state-id fsm)
                                                              :error (get-in fsm [:data :error])
                                                              :errors (get-in fsm [:data :errors])})
                                 (throw (ex-info (or (get-in fsm [:data :error]) "Plan FSM failed")
                                                 (select-keys (:data fsm) [:error :errors :status :validation]))))}}

   :opts {:max-trace 20
          :pre (fn [fsm _r]
                 (log/debug "Plan FSM transition" {:state (:current-state-id fsm)
                                                   :status (get-in fsm [:data :status])})
                 fsm)}})

;; Compile once, reuse
(def ^:private compiled-fsm
  (delay (fsm/compile plan-fsm-spec)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn run-plan-fsm
  "Run the plan lifecycle FSM from draft to executing.

   Args:
   - data: Map with either:
     - :content  - Raw plan content (markdown+edn) to parse
     - :plan     - Already-parsed plan map
   - resources: Map with:
     - :execute-fn - (fn [data] -> {:task-ids [...] :kg-edges [...]})
                     Called during :approved → :executing transition
     - :directory  - Working directory for kanban task creation

   Returns: Final data map with :task-ids, :kg-edges, :status etc.
   Throws: ExceptionInfo on validation or execution failure."
  ([data]
   (run-plan-fsm data {}))
  ([data resources]
   (fsm/run @compiled-fsm resources {:data data})))

(defn draft->validated
  "Advance plan from draft to validated state only.
   Useful for dry-run validation without execution.

   Uses a custom ::error handler that propagates the actual parser/validation
   error message instead of the generic 'FSM execution error'."
  [data]
  (let [spec {:fsm {::fsm/start  {:handler    handle-draft
                                  :dispatches [[:validated (fn [d] (and (not (:parse-failed d)) (:plan d)))]
                                               [::fsm/error (constantly true)]]}
                    :validated   {:handler    handle-validate
                                  :dispatches [[::fsm/end (constantly true)]]}
                    ::fsm/error  {:handler
                                  (fn [_resources fsm]
                                    (let [d (:data fsm)
                                          error-msg (or (:error d) "Unknown plan validation error")]
                                      (throw (ex-info (str "Plan validation failed: " error-msg)
                                                      {:phase    (if (:parse-failed d) :parse :validation)
                                                       :error    error-msg
                                                       :status   (:status d)
                                                       :plan-id  (:plan-id d)}))))}}
              :opts {:max-trace 5}}]
    (fsm/run (fsm/compile spec) {} {:data data})))
