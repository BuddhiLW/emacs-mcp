(ns hive-mcp.workflows.sub-fsms.drone-loop-handlers
  "Handler functions for the drone-loop FSM.

   Extracted from drone-loop to keep per-file cyclomatic complexity manageable.
   Each handler is a pure function: (resources, data) -> {:data data', :fx [...]}
   or (resources, data) -> data (for non-FX handlers).

   See hive-mcp.workflows.sub-fsms.drone-loop for FSM spec, predicates,
   and public API."

  (:require [hive-mcp.workflows.sub-fsms.llm-call :as llm-call]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const default-max-turns
  "Default maximum agentic loop iterations."
  10)

(def ^:const failure-threshold
  "Consecutive failures before forced termination."
  3)

;; =============================================================================
;; Shared Helpers
;; =============================================================================

(defn make-default-result
  "Build a result map in the standard drone-loop output shape.
   Uses keyword-as-function with defaults to avoid branching on nil."
  [data & {:keys [status result]}]
  {:status          (or status :error)
   :result          (or result "unknown error")
   :steps           (:steps data [])
   :tool_calls_made (:tool-calls-made data 0)
   :tokens          (:total-tokens data {:input 0 :output 0 :total 0})
   :turns           (:turn data 0)
   :model           (:model data "unknown")
   :kg-stats        {:observations (:obs-count data 0)
                     :reasoning    (:reason-count data 0)
                     :facts        0}})

(defn- accumulate-tokens
  "Merge new token usage into accumulated totals."
  [prev-tokens usage]
  (if usage
    {:input  (+ (:input prev-tokens 0) (:input usage 0))
     :output (+ (:output prev-tokens 0) (:output usage 0))
     :total  (+ (:total prev-tokens 0) (:total usage 0))}
    prev-tokens))

;; =============================================================================
;; Termination Logic (ported from drone/loop.clj)
;; =============================================================================

(defn- no-progress?
  "Check if the last 3 steps all represent failures."
  [steps]
  (and (>= (count steps) 3)
       (every? (fn [step]
                 (or (= :error (:response-type step))
                     (and (= :tool_calls (:response-type step))
                          (:all-failed? step))))
               (take-last 3 steps))))

(defn should-terminate?
  "Evaluate termination heuristics for the agentic loop.

   Priority order:
   1. Max turns reached
   2. Last response was text-only (no tool calls = LLM done)
   3. Consecutive failures exceed threshold
   4. Completion language detected
   5. No progress in last 3 turns

   Returns:
     {:terminate? boolean :reason string}"
  [data]
  (let [{:keys [turn max-turns steps consecutive-failures
                last-response-type completion?]
         :or   {turn 0, max-turns default-max-turns,
                consecutive-failures 0, steps []}} data]
    (cond
      (>= turn max-turns)
      {:terminate? true :reason (str "Max turns reached (" max-turns ")")}

      (= :text last-response-type)
      {:terminate? true :reason "Text-only response (no tool calls = task complete)"}

      (>= consecutive-failures failure-threshold)
      {:terminate? true :reason (str "Too many consecutive failures (" consecutive-failures ")")}

      completion?
      {:terminate? true :reason "Completion language detected in response"}

      (no-progress? steps)
      {:terminate? true :reason "No progress in last 3 turns"}

      :else
      {:terminate? false :reason "Continuing"})))

;; =============================================================================
;; Handlers (Pure: resources, data -> {:data data', :fx [...]})
;; =============================================================================

(defn handle-init
  "Initialize the drone loop session.

   Sets up:
   - Turn counter, token accumulators, step history
   - System prompt from task spec

   FX declared: :drone/seed-session, :drone/emit
   Resources used: :session-store (passed to FX)"
  [resources data]
  (let [{:keys [task files drone-id max-turns model trace?]
         :or   {max-turns default-max-turns}} data
        {:keys [session-store]} resources]

    (log/info "Drone loop FSM init"
              {:drone-id drone-id
               :max-turns max-turns
               :model model
               :files (count (or files []))
               :has-session-store? (some? session-store)})

    {:data (assoc data
                  :turn 0
                  :steps []
                  :tool-calls-made 0
                  :total-tokens {:input 0 :output 0 :total 0}
                  :consecutive-failures 0
                  :last-response-type nil
                  :last-text nil
                  :completion? false
                  :obs-count 0
                  :reason-count 0
                  :max-turns max-turns
                  :system-prompt (llm-call/default-system-prompt
                                  {:files (or files [])}))
     :fx (cond-> []
           session-store
           (conj [:drone/seed-session {:store session-store
                                       :task task
                                       :files files}])
           trace?
           (conj [:drone/emit {:event-type :agentic-loop-started
                               :data {:drone-id drone-id
                                      :max-turns max-turns
                                      :model model}}]))}))

(defn handle-reconstruct-context
  "Reconstruct compressed context from session store for current turn.

   On turn 0: uses raw task as context (no history yet).
   On turn N>0: delegates to reconstruct-fn which queries the session store
   for compressed observations/reasoning from previous turns.

   Resources used: :reconstruct-fn, :session-store"
  [resources data]
  (let [{:keys [turn task]} data
        {:keys [reconstruct-fn session-store]} resources
        context (if (and reconstruct-fn session-store (pos? (or turn 0)))
                  (try
                    (reconstruct-fn session-store task turn)
                    (catch Throwable e
                      (log/debug "Context reconstruction failed, using raw task"
                                 {:turn turn :error (.getMessage e)})
                      task))
                  task)]
    (assoc data :context context)))

(defn handle-llm-call
  "Delegate to the llm-call sub-FSM for LLM interaction.

   Builds input from current context + history, runs the llm-call sub-FSM,
   and merges response data back into parent state.

   Resources used: :backend, :tool-schemas (via llm-call sub-FSM)"
  [resources data]
  (let [{:keys [context system-prompt turn messages]} data
        {:keys [backend tool-schemas]} resources

        ;; Build messages for llm-call
        llm-input {:task context
                   :files (:files data)
                   :turn (or turn 0)
                   :system-prompt system-prompt
                   :messages messages}

        ;; Run llm-call sub-FSM
        llm-result (if backend
                     (llm-call/run-llm-call llm-input
                                            {:backend backend
                                             :tool-schemas tool-schemas})
                     {:response-type :error
                      :error "No LLM backend provided"
                      :tool-calls nil
                      :text-content nil
                      :completion? false
                      :usage nil})]

    (log/debug "LLM call result"
               {:turn turn
                :response-type (:response-type llm-result)
                :has-tool-calls? (boolean (seq (:tool-calls llm-result)))
                :completion? (:completion? llm-result)
                :usage (:usage llm-result)})

    ;; Merge sub-FSM output into parent data
    (let [prev-tokens (:total-tokens data {:input 0 :output 0 :total 0})]
      (assoc data
             :response-type (:response-type llm-result)
             :tool-calls    (:tool-calls llm-result)
             :text-content  (:text-content llm-result)
             :completion?   (:completion? llm-result)
             :error         (:error llm-result)
             :total-tokens  (accumulate-tokens prev-tokens (:usage llm-result))))))

(defn handle-dispatch-response
  "Route based on LLM response type — text, tool_calls, or error.

   Does NOT change data — this is a pure routing state.
   The dispatch predicates determine the next state."
  [_resources data]
  data)

(defn handle-execute-tools
  "Execute tool calls from the LLM response.

   Delegates to execute-tools-fn resource for actual execution.
   Collects results and tracks success/failure metrics.

   FX declared: :drone/emit (when trace?)
   Resources used: :execute-tools-fn, :agent-id"
  [resources data]
  (let [{:keys [tool-calls turn drone-id permissions trace?]} data
        {:keys [execute-tools-fn agent-id]} resources
        agent-id (or agent-id drone-id)]

    (if-not (and execute-tools-fn (seq tool-calls))
      ;; No tools to execute or no executor
      {:data (assoc data
                    :tool-results []
                    :all-failed? false
                    :last-response-type :tool_calls
                    :last-text "No tools executed")
       :fx []}

      (let [tool-names (mapv :name tool-calls)
            _ (log/info "Executing tools"
                        {:drone-id drone-id :turn turn :tools tool-names})

            ;; Execute via resource fn (value-producing — stays inline)
            tool-results (try
                           (execute-tools-fn agent-id tool-calls (or permissions #{}))
                           (catch Throwable e
                             (log/error e "Tool execution failed"
                                        {:drone-id drone-id :turn turn})
                             ;; Return error results for each call
                             (mapv (fn [_] {:content (str "Error: " (ex-message e))})
                                   tool-calls)))

            ;; Count failures
            batch-failures (count (filter
                                   (fn [r] (str/starts-with?
                                            (:content r "") "Error:"))
                                   tool-results))
            all-failed? (and (seq tool-results)
                             (= batch-failures (count tool-results)))
            result-summary (str/join "; " (map :content tool-results))]

        {:data (assoc data
                      :tool-results (vec (map vector tool-calls tool-results))
                      :all-failed? all-failed?
                      :last-response-type :tool_calls
                      :last-text result-summary
                      :tool-calls-made (+ (:tool-calls-made data 0)
                                          (count tool-calls))
                      :consecutive-failures (if all-failed?
                                              (inc (:consecutive-failures data 0))
                                              0))
         :fx (cond-> []
               trace?
               (conj [:drone/emit {:event-type :agentic-loop-turn
                                   :data {:drone-id drone-id :turn turn
                                          :phase :executing-tools
                                          :tools tool-names}}]))}))))

(defn handle-compress
  "Declare FX for recording tool results and reasoning in session store.

   Each tool call result becomes an observation FX. The LLM's reasoning
   (tool selection rationale) is recorded separately via FX.

   FX declared: :drone/record-obs (per tool result), :drone/record-reason
   Resources used: :session-store (passed to FX)"
  [resources data]
  (let [{:keys [turn tool-results files]} data
        {:keys [session-store]} resources
        new-obs-count (+ (:obs-count data 0)
                         (count (or tool-results [])))
        tool-names (mapv (comp :name first) tool-results)

        ;; Build observation FX — one per tool result
        obs-fx (when (and session-store (seq tool-results))
                 (mapv (fn [[call result-msg]]
                         (let [content (:content result-msg "")
                               success? (not (str/starts-with? content "Error:"))]
                           [:drone/record-obs
                            {:store     session-store
                             :turn      turn
                             :tool-name (:name call)
                             :result    {:success success?
                                         :result  {:text content}
                                         :error   (when-not success? content)}
                             :opts      {:file (first files)}}]))
                       tool-results))

        ;; Build reasoning FX
        reason-fx (when session-store
                    [[:drone/record-reason
                      {:store  session-store
                       :turn   turn
                       :action (str "Execute tools: " (str/join ", " tool-names))
                       :detail "LLM requested tool execution"}]])]

    ;; Increment turn and record step (pure data transform)
    {:data (-> data
               (assoc :obs-count new-obs-count
                      :reason-count (inc (:reason-count data 0))
                      :turn (inc (:turn data 0)))
               (update :steps conj {:turn turn
                                    :response-type (:response-type data)
                                    :tool-calls-count (count tool-results)
                                    :all-failed? (:all-failed? data)}))
     :fx (into (or obs-fx []) reason-fx)}))

(defn- advance-turn-for-text
  "For text/error responses, advance the turn counter and record step.
   Tool responses go through ::execute-tools -> ::compress instead.
   Returns [updated-data fx-vector]."
  [data session-store]
  (let [{:keys [turn response-type text-content error]} data]
    (if (#{:text :error} response-type)
      (let [content (or text-content error "")
            reason-fx (when (and session-store (= :text response-type))
                        [[:drone/record-reason
                          {:store  session-store
                           :turn   turn
                           :action "Final response (text-only)"
                           :detail (subs content 0 (min 200 (count content)))}]])]
        [(-> data
             (assoc :turn (inc (:turn data 0))
                    :last-text content
                    :last-response-type response-type
                    :consecutive-failures
                    (if (= :error response-type)
                      (inc (:consecutive-failures data 0))
                      0))
             (update :steps conj {:turn turn
                                  :response-type response-type
                                  :all-failed? (= :error response-type)}))
         (or reason-fx [])])
      [data []])))

(defn handle-check-done
  "Evaluate termination heuristics to decide: loop or finalize.

   For text/error responses (no tool execution path), also increments
   turn counter and records the step before checking termination.

   FX declared: :drone/record-reason (for text responses), :drone/emit
   Resources used: :session-store (passed to FX)
   The dispatch predicates (done? / continue?) determine the next state."
  [resources data]
  (let [{:keys [session-store]} resources
        [data text-fx] (advance-turn-for-text data session-store)
        term-result (should-terminate? data)
        emit-fx (when (:trace? data)
                  [[:drone/emit {:event-type :agentic-loop-turn
                                 :data {:drone-id (:drone-id data)
                                        :turn (:turn data)
                                        :phase :check-done
                                        :terminate? (:terminate? term-result)
                                        :reason (:reason term-result)}}]])]

    ;; Store termination result for dispatch predicates
    {:data (assoc data :termination term-result)
     :fx (into text-fx (or emit-fx []))}))

(defn handle-finalize
  "Build the final execution result.

   Computes status from termination state and assembles the result map
   matching the contract of run-agentic-loop in drone/loop.clj.

   FX declared: :drone/emit (when trace?)"
  [_resources data]
  (let [{:keys [turn max-turns last-text
                drone-id termination trace?]
         :or   {turn 0, max-turns default-max-turns}} data

        status (if (>= turn max-turns) :max_steps :completed)

        final-result (or last-text
                         (str "Loop terminated: "
                              (:reason termination "unknown")))]

    (log/info "Drone loop FSM finalized"
              {:drone-id drone-id
               :status status
               :turns turn
               :reason (:reason termination)
               :tool-calls (:tool-calls-made data 0)})

    ;; Return the result in the same shape as drone/loop.clj
    {:data (assoc data
                  :fsm-result
                  (make-default-result data
                                       :status status
                                       :result final-result))
     :fx (cond-> []
           trace?
           (conj [:drone/emit {:event-type :agentic-loop-completed
                               :data {:drone-id drone-id
                                      :status status
                                      :turns turn
                                      :tool-calls (:tool-calls-made data 0)}}]))}))

(defn handle-fsm-error
  "Handle FSM error state. Builds error result from current data."
  [_resources data]
  (log/warn "drone-loop FSM error:" (:error data))
  (assoc data
         :fsm-result
         (make-default-result data
                              :result (or (:error data) "FSM error"))))
