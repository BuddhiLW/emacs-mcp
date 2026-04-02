(ns hive-mcp.agent.drone.loop
  "In-process agentic drone loop with think-act-observe execution.

   Architecture (post-refactoring):
   - Predicates (termination, evaluation): loop-predicates ns
   - Session lifecycle (KG, recording):    loop-session ns
   - This ns: core loop mechanics only

   State flows through a single map to avoid N-arg recur.
   Enhanced session lifecycle uses rescue (never throws)."
  (:require [hive-mcp.agent.drone.loop-predicates :as pred]
            [hive-mcp.agent.drone.loop-session :as lsess]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.agent.registry :as registry]
            [hive-mcp.agent.executor :as executor]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.channel.core :as channel]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Re-export public predicates for backward compatibility
(def should-terminate? pred/should-terminate?)
(def evaluate-result pred/evaluate-result)
(def select-next-tool pred/select-next-tool)

;; =============================================================================
;; Token Accounting (pure)
;; =============================================================================

(def zero-tokens
  "Initial token counters."
  {:input 0 :output 0 :total 0})

(defn- accumulate-tokens
  "Add usage deltas to running totals. Returns total unchanged if usage is nil."
  [total usage]
  (if usage
    (merge-with (fnil + 0 0) total (select-keys usage [:input :output :total]))
    total))

;; =============================================================================
;; Result Builders (pure)
;; =============================================================================

(defn- build-kg-stats
  "Extract KG stats from loop state."
  [{:keys [obs-count reason-count]}]
  {:observations obs-count :reasoning reason-count :facts 0})

(defn- build-final-result
  "Construct the final result map from loop state and termination info."
  [{:keys [turn steps tool-calls-made total-tokens model-name
           last-text] :as state}
   max-turns term-reason]
  (let [status (pred/determine-final-status state max-turns)]
    {:status          status
     :result          (or last-text (str "Loop terminated: " term-reason))
     :steps           steps
     :tool_calls_made tool-calls-made
     :tokens          total-tokens
     :turns           turn
     :model           model-name
     :kg-stats        (build-kg-stats state)}))

(defn- build-error-result
  "Construct an error result map (for exceptions and unknown response types)."
  [{:keys [steps tool-calls-made total-tokens turn model-name] :as state} message]
  {:status          :error
   :result          message
   :steps           steps
   :tool_calls_made tool-calls-made
   :tokens          total-tokens
   :turns           turn
   :model           model-name
   :kg-stats        (build-kg-stats state)})

;; =============================================================================
;; System Prompt
;; =============================================================================

(defn- build-system-prompt
  "Build the system prompt for the drone LLM call."
  [task-spec]
  (str "You are a coding drone. You MUST use the propose_diff tool for ALL file changes. "
       "NEVER output code as plain text or markdown code blocks. "
       "Every file modification MUST go through the propose_diff tool with file_path, old_content, and new_content parameters. "
       "If you output code as text instead of calling propose_diff, your changes will be LOST and the task will FAIL. "
       "Use read_file to examine files, then propose_diff to make changes. Be concise and focused."
       (when (seq (:files task-spec))
         (str "\n\nTarget files: " (str/join ", " (:files task-spec))))))

;; =============================================================================
;; Turn Execution (single LLM round-trip)
;; =============================================================================

(defn- advance-state
  "Create the next loop state from the current state and response details."
  [state updated-tokens response-type text-content
   {:keys [tool-call-delta consecutive-failures-fn obs-delta reason-delta]
    :or   {tool-call-delta 0 obs-delta 0 reason-delta 0}}]
  (-> state
      (update :turn inc)
      (update :steps conj (assoc (:response state) :turn (:turn state)))
      (update :tool-calls-made + tool-call-delta)
      (assoc  :total-tokens updated-tokens)
      (assoc  :consecutive-failures (consecutive-failures-fn (:consecutive-failures state)))
      (assoc  :last-response-type response-type)
      (assoc  :last-text text-content)
      (update :obs-count + obs-delta)
      (update :reason-count + reason-delta)
      (dissoc :response)))

(defn- handle-text-response
  "Process a text-only LLM response. Returns updated state."
  [state response updated-tokens session-store]
  (let [content (:content response)]
    (lsess/record-text-reasoning! session-store (:turn state) content)
    (advance-state (assoc state :response response)
                   updated-tokens :text content
                   {:consecutive-failures-fn (constantly 0)
                    :reason-delta 1})))

(defn- resolve-project-id
  "Derive project-id from task-spec cwd. Cached per loop instance."
  [task-spec]
  (when-let [cwd (:cwd task-spec)]
    (try
      (require 'hive-mcp.tools.memory.scope)
      ((resolve 'hive-mcp.tools.memory.scope/get-current-project-id) cwd)
      (catch Exception _ nil))))

(defn- handle-tool-calls-response
  "Process a tool_calls LLM response. Returns updated state."
  [state response updated-tokens session-store task-spec]
  (let [{:keys [drone-id agent-id turn obs-count permissions emit!]} state
        calls (:calls response)
        tool-names (mapv :name calls)
        project-id (resolve-project-id task-spec)]
    (log/info "Agentic loop executing tools"
              {:drone-id drone-id :turn turn :tools tool-names})
    (emit! :agentic-loop-turn {:drone-id drone-id :turn turn
                               :phase :executing-tools :tools tool-names})
    (let [tool-results (ctx/with-request-context {:agent-id agent-id :project-id project-id}
                         (executor/execute-tool-calls agent-id calls permissions
                                                      {:project-id project-id}))
          new-obs      (lsess/record-tool-observations!
                        session-store turn calls tool-results obs-count task-spec)
          all-failed?  (every? (fn [r] (str/starts-with? (str (:content r)) "Error:"))
                               tool-results)
          summary      (str/join "; " (map :content tool-results))]
      (advance-state (assoc state :response response)
                     updated-tokens :tool_calls summary
                     {:tool-call-delta         (count calls)
                      :consecutive-failures-fn (if all-failed? inc (constantly 0))
                      :obs-delta               (- new-obs obs-count)
                      :reason-delta            1}))))

(defn- handle-error-response
  "Process an error LLM response. Returns updated state."
  [state response updated-tokens session-store]
  (let [{:keys [drone-id turn]} state
        error-msg (or (:error response) "Unknown LLM error")]
    (log/warn "Agentic loop LLM error" {:drone-id drone-id :turn turn :error error-msg})
    (lsess/record-error-observation! session-store turn error-msg)
    (advance-state (assoc state :response response)
                   updated-tokens :error error-msg
                   {:consecutive-failures-fn inc
                    :obs-delta 1})))

(defn- execute-turn
  "Execute a single turn of the agentic loop.
   Returns {:recur? true :state new-state} or {:recur? false :final result-map}."
  [backend messages tool-schemas session-store task-spec state]
  (try
    (let [response (proto/chat backend messages tool-schemas)
          {:keys [drone-id]} state
          updated-tokens (accumulate-tokens (:total-tokens state) (:usage response))]
      (case (:type response)
        :text       {:recur? true
                     :state  (handle-text-response state response updated-tokens session-store)}
        :tool_calls {:recur? true
                     :state  (handle-tool-calls-response
                              state response updated-tokens session-store task-spec)}
        :error      {:recur? true
                     :state  (handle-error-response state response updated-tokens session-store)}
        ;; Unknown response type
        (do
          (log/error "Unknown LLM response type" {:type (:type response) :drone-id drone-id})
          {:recur? false
           :final  (build-error-result state (str "Unknown response type: " (:type response)))})))
    (catch Exception e
      (let [{:keys [emit! drone-id turn]} state]
        (log/error e "Agentic loop exception" {:drone-id drone-id :turn turn})
        (emit! :agentic-loop-error {:drone-id drone-id :turn turn :error (ex-message e)})
        {:recur? false
         :final  (build-error-result state (str "Exception at turn " turn ": " (ex-message e)))}))))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn- noop-result
  "Result when no LLM backend is provided."
  []
  {:status :noop :result "No LLM backend provided"
   :steps [] :tool_calls_made 0 :tokens zero-tokens
   :turns 0 :model "none" :kg-stats nil})

(defn- initial-loop-state
  "Construct the initial loop state map."
  [drone-id agent-id model-name permissions emit!]
  {:turn                  0
   :steps                 []
   :tool-calls-made       0
   :total-tokens          zero-tokens
   :consecutive-failures  0
   :last-response-type    nil
   :last-text             nil
   :obs-count             0
   :reason-count          0
   ;; Context (immutable across turns)
   :drone-id              drone-id
   :agent-id              agent-id
   :model-name            model-name
   :permissions           permissions
   :emit!                 emit!})

(defn run-agentic-loop
  "Run the think-act-observe agentic loop for a drone task."
  [task-spec ctx & [opts]]
  (let [drone-id (or (:drone-id ctx) "unknown")]
    (if-let [ext-fn (ext/get-extension :al/run)]
      (do (log/info "Delegating to enhanced agentic loop" {:drone-id drone-id})
          (ext-fn task-spec ctx opts))

      (let [{:keys [max-turns backend tools permissions trace? agent-id]
             :or {max-turns 10 permissions #{} trace? false}} opts
            agent-id          (or agent-id drone-id)
            session-store     (:kg-store ctx)
            task              (:task task-spec)
            files             (or (:files task-spec) [])
            enhanced-session  (lsess/create-enhanced-session drone-id task)]

        (log/info "Starting agentic loop"
                  {:drone-id drone-id :max-turns max-turns
                   :has-backend? (some? backend)
                   :has-session-store? (some? session-store)
                   :has-enhanced? (some? enhanced-session)
                   :files (count files)})

        (if-not backend
          (do (log/warn "No LLM backend provided" {:drone-id drone-id})
              (noop-result))

          (let [model-name    (proto/model-name backend)
                tool-schemas  (registry/get-schemas tools)
                system-prompt (build-system-prompt task-spec)
                emit!         (fn [event-type data]
                                (when trace?
                                  (channel/emit-event! event-type
                                                       (assoc data :agent-id agent-id))))]

            (lsess/seed-session-store! session-store task files (:cwd task-spec))
            (emit! :agentic-loop-started {:drone-id drone-id :max-turns max-turns
                                          :model model-name})

            (loop [state (initial-loop-state drone-id agent-id model-name permissions emit!)]
              (let [term (pred/should-terminate? state {:max-turns max-turns})]
                (if (:terminate? term)
                  ;; --- Terminate ---
                  (let [result (build-final-result state max-turns (:reason term))]
                    (log/info "Agentic loop terminated"
                              {:drone-id drone-id :status (:status result)
                               :turns (:turn state) :reason (:reason term)
                               :tool-calls (:tool-calls-made state)})
                    (emit! :agentic-loop-completed
                           {:drone-id drone-id :status (:status result)
                            :turns (:turn state) :tool-calls (:tool-calls-made state)})
                    (lsess/finalize-enhanced-session! enhanced-session (:status result))
                    result)

                  ;; --- Continue ---
                  (let [messages    (lsess/build-turn-messages
                                     session-store enhanced-session
                                     system-prompt task (:turn state))
                        _           (log/debug "Agentic loop turn"
                                               {:drone-id drone-id :turn (:turn state)
                                                :context-size (count (str messages))
                                                :enhanced? (and (some? enhanced-session)
                                                                (pos? (:turn state)))})
                        _           (emit! :agentic-loop-turn
                                           {:drone-id drone-id :turn (:turn state)
                                            :phase :calling-llm})
                        turn-result (execute-turn backend messages tool-schemas
                                                  session-store task-spec state)]
                    (if (:recur? turn-result)
                      (do
                        (lsess/compress-turn-safely! enhanced-session messages)
                        (recur (:state turn-result)))
                      (do
                        (lsess/close-enhanced-session! enhanced-session)
                        (:final turn-result)))))))))))))
