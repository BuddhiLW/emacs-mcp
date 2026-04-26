(ns hive-mcp.agent.saa.orchestrator
  "SAA (Silence-Abstract-Act) orchestrator implementing ISAAOrchestrator protocol."
  (:require [clojure.core.async :as async :refer [go go-loop chan >! <! close!]]
            [clojure.string :as str]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.protocols.agent-bridge :as bridge]
            [hive-mcp.extensions.registry :as ext]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private agent-states (atom {}))

(defn- init-agent-state!
  "Initialize SAA state for an agent."
  [agent-id task]
  (swap! agent-states assoc agent-id
         {:phase :idle
          :task task
          :observations []
          :plan nil
          :result nil
          :phase-history []
          :started-at (System/currentTimeMillis)
          :phase-started-at (System/currentTimeMillis)
          :error nil}))

(defn- transition-phase!
  "Transition an agent to a new SAA phase, recording in history."
  [agent-id new-phase]
  (swap! agent-states update agent-id
         (fn [state]
           (when state
             (let [now (System/currentTimeMillis)
                   history-entry {:phase (:phase state)
                                  :started-at (or (:phase-started-at state)
                                                  (:started-at state))
                                  :ended-at now}]
               (-> state
                   (assoc :phase new-phase
                          :phase-started-at now)
                   (update :phase-history conj history-entry)))))))

(defn- update-agent-state!
  "Update specific fields in an agent's SAA state."
  [agent-id updates]
  (swap! agent-states update agent-id merge updates))

(defn- get-agent-state
  "Get current SAA state for an agent."
  [agent-id]
  (get @agent-states agent-id))

(defn- clear-agent-state!
  "Remove SAA state for an agent."
  [agent-id]
  (swap! agent-states dissoc agent-id))

(defn- shout-phase!
  "Broadcast phase transition to hivemind via requiring-resolve."
  [agent-id phase message]
  (rescue nil
          (when-let [shout-fn (requiring-resolve 'hive-mcp.hivemind.core/shout!)]
            (shout-fn agent-id
                      :progress
                      {:task (:task (get-agent-state agent-id))
                       :message (str "[SAA:" (name phase) "] " message)
                       :saa-phase phase}))))

(defn- maybe-shout!
  "Broadcast phase transition only if :shout? is enabled in config."
  [config agent-id phase message]
  (when (:shout? config)
    (shout-phase! agent-id phase message)))

(defn- score-observations-enhanced
  "Score observations using extension, falling back to built-in heuristic."
  [observations]
  (if-let [score-fn (ext/get-extension :es/score)]
    (score-fn observations)
    (if-let [sdk-score-fn (rescue nil (requiring-resolve 'hive-claude.sdk.saa/score-observations))]
      (sdk-score-fn observations)
      observations)))

(defn- plan-from-observations-enhanced
  "Generate a plan using extension, falling back to nil."
  [observations task]
  (if-let [plan-fn (ext/get-extension :ep/generate)]
    (plan-fn observations task)
    nil))

(defn- enrich-silence-context
  "Enrich Silence phase with additional context from extension."
  [task]
  (if-let [enrich-fn (ext/get-extension :ec/enrich)]
    (enrich-fn task)
    nil))

(defn- resolve-saa-phases
  "Resolve SAA phase config from SDK addon (hive-claude).
   Returns phase map or nil if not available."
  []
  (rescue nil
          (when-let [v (requiring-resolve 'hive-claude.sdk.saa/saa-phases)]
            @v)))

(defn- build-phase-prompt
  "Build the full prompt for a given SAA phase."
  [phase task-or-content extra-context]
  (let [saa-phases (resolve-saa-phases)
        phase-config (get saa-phases phase)
        _suffix (:system-prompt-suffix phase-config)]
    (case phase
      :silence
      (str "TASK: " task-or-content
           "\n\nExplore the codebase and collect context. "
           "List all relevant files, patterns, and observations."
           (when extra-context
             (str "\n\nPrior knowledge context:\n" (pr-str extra-context))))

      :abstract
      (str "Based on these observations from the Silence phase:\n"
           (pr-str task-or-content)
           "\n\nSynthesize these into a concrete action plan."
           (when extra-context
             (str "\n\nOriginal task: " extra-context))
           "\n\nProduce a structured plan with specific steps. "
           "Each step should name the file, the change, and the rationale.")

      :act
      (str "Execute the following plan:\n" (or task-or-content "Use best judgment.")
           (when extra-context
             (str "\n\nOriginal task: " extra-context))
           "\n\nFollow the plan precisely. Make changes file by file. "
           "Verify each change before moving to the next."))))

(defn- build-phase-opts
  "Build query options for a phase."
  [phase user-opts]
  (let [saa-phases (resolve-saa-phases)
        phase-config (get saa-phases phase)]
    (cond-> {:allowed-tools (:allowed-tools phase-config)
             :permission-mode (keyword (:permission-mode phase-config))}
      (:system-prompt user-opts)
      (assoc :system-prompt (str (:system-prompt user-opts)
                                 "\n\n" (:system-prompt-suffix phase-config)))

      (nil? (:system-prompt user-opts))
      (assoc :system-prompt (:system-prompt-suffix phase-config))

      (:max-turns user-opts)
      (assoc :max-turns (:max-turns user-opts)))))

(defn- execute-phase-via-session!
  "Execute a SAA phase by querying the agent session."
  [session prompt phase-opts]
  (bridge/query! session prompt phase-opts))

(defn- collect-phase-messages!
  "Drain a phase channel, collecting messages and forwarding to output channel."
  [phase-ch out-ch saa-phase]
  (go-loop [messages []]
    (if-let [msg (<! phase-ch)]
      (do
        (when out-ch
          (>! out-ch (assoc msg :saa-phase saa-phase)))
        (recur (conj messages msg)))
      messages)))

(defn- extract-content
  "Extract textual content from phase messages."
  [messages]
  (->> messages
       (filter #(contains? #{:message :complete :result} (:type %)))
       (mapv #(or (:content %) (:data %) (str %)))))

(defn- pipe-phase!
  "Pipe all messages from phase-ch to out-ch, then return the value of state-key
   from the agent's current state. Returns a go channel; use <! to await."
  [phase-ch out-ch agent-id state-key]
  (go-loop []
    (if-let [msg (<! phase-ch)]
      (do (>! out-ch msg) (recur))
      (get (get-agent-state agent-id) state-key))))

(defn- handle-phase-error!
  "Common error handling for SAA phase failures. Uses put! (safe for buffered channels)."
  [config agent-id phase out-ch e]
  (log/error (str "[saa] " (name phase) " phase failed")
             {:agent-id agent-id :error (ex-message e)})
  (transition-phase! agent-id :error)
  (update-agent-state! agent-id {:error (ex-message e)})
  (maybe-shout! config agent-id phase (str "FAILED: " (ex-message e)))
  (async/put! out-ch {:type :error :saa-phase phase :error (ex-message e)}))

(defrecord SAAOrchestrator [config]
  bridge/ISAAOrchestrator

  (run-silence! [_ session task opts]
    (let [agent-id (bridge/session-id session)
          out-ch (chan 1024)]
      (init-agent-state! agent-id task)
      (transition-phase! agent-id :silence)
      (maybe-shout! config agent-id :silence "Starting observation phase")
      (go
        (try
          (let [enrichment (enrich-silence-context task)
                prompt (build-phase-prompt :silence task enrichment)
                phase-opts (build-phase-opts :silence opts)
                phase-ch (execute-phase-via-session! session prompt phase-opts)
                messages (<! (collect-phase-messages! phase-ch out-ch :silence))
                observations (extract-content messages)]
            (update-agent-state! agent-id {:observations observations})
            (maybe-shout! config agent-id :silence
                          (str "Completed. Collected " (count observations) " observations"))
            (>! out-ch {:type :phase-complete
                        :saa-phase :silence
                        :observations observations
                        :observation-count (count observations)}))
          (catch Throwable e
            (handle-phase-error! config agent-id :silence out-ch e))
          (finally
            (close! out-ch))))
      out-ch))

  (run-abstract! [_ session observations opts]
    (let [agent-id (bridge/session-id session)
          out-ch (chan 1024)]
      (transition-phase! agent-id :abstract)
      (maybe-shout! config agent-id :abstract
                    (str "Starting synthesis with " (count observations) " observations"))
      (go
        (try
          (let [scored (score-observations-enhanced observations)
                task (:task (get-agent-state agent-id))
                ext-plan (plan-from-observations-enhanced scored task)
                prompt (build-phase-prompt :abstract scored task)
                phase-opts (build-phase-opts :abstract opts)
                phase-ch (execute-phase-via-session! session prompt phase-opts)
                messages (<! (collect-phase-messages! phase-ch out-ch :abstract))
                plan-content (extract-content messages)
                final-plan (or ext-plan (str/join "\n" plan-content))]
            (update-agent-state! agent-id {:plan final-plan})
            (maybe-shout! config agent-id :abstract "Completed. Plan ready for execution.")
            (>! out-ch {:type :phase-complete
                        :saa-phase :abstract
                        :plan final-plan}))
          (catch Throwable e
            (handle-phase-error! config agent-id :abstract out-ch e))
          (finally
            (close! out-ch))))
      out-ch))

  (run-act! [_ session plan opts]
    (let [agent-id (bridge/session-id session)
          out-ch (chan 1024)]
      (transition-phase! agent-id :act)
      (maybe-shout! config agent-id :act "Starting execution phase")
      (go
        (try
          (let [task (:task (get-agent-state agent-id))
                prompt (build-phase-prompt :act plan task)
                phase-opts (build-phase-opts :act opts)
                phase-ch (execute-phase-via-session! session prompt phase-opts)
                messages (<! (collect-phase-messages! phase-ch out-ch :act))
                result-content (extract-content messages)]
            (update-agent-state! agent-id {:result {:messages result-content
                                                    :message-count (count messages)}})
            (transition-phase! agent-id :complete)
            (maybe-shout! config agent-id :act
                          (str "Completed. " (count messages) " messages processed."))
            (>! out-ch {:type :phase-complete
                        :saa-phase :act
                        :result {:messages result-content
                                 :message-count (count messages)}}))
          (catch Throwable e
            (handle-phase-error! config agent-id :act out-ch e))
          (finally
            (close! out-ch))))
      out-ch))

  (run-full-saa! [this session task opts]
    (let [agent-id (bridge/session-id session)
          out-ch (chan 4096)
          {:keys [skip-silence? skip-abstract? phase-opts]} opts
          silence-opts (get phase-opts :silence {})
          abstract-opts (get phase-opts :abstract {})
          act-opts (get phase-opts :act {})]
      (init-agent-state! agent-id task)
      (maybe-shout! config agent-id :silence
                    (str "Starting full SAA cycle"
                         (when skip-silence? " (skipping Silence)")
                         (when skip-abstract? " (skipping Abstract)")))
      (go
        (try
          (let [observations
                (if-not skip-silence?
                  (<! (pipe-phase! (bridge/run-silence! this session task silence-opts)
                                   out-ch agent-id :observations))
                  [])
                plan
                (if-not skip-abstract?
                  (<! (pipe-phase! (bridge/run-abstract! this session observations abstract-opts)
                                   out-ch agent-id :plan))
                  nil)]
            (<! (pipe-phase! (bridge/run-act! this session (or plan task) act-opts)
                             out-ch agent-id :result))

            (let [final-state (get-agent-state agent-id)]
              (maybe-shout! config agent-id :complete
                            (str "SAA cycle complete. "
                                 (count (:observations final-state)) " observations, "
                                 (count (:phase-history final-state)) " phases"))
              (>! out-ch {:type :saa-complete
                          :agent-id agent-id
                          :observations-count (count (:observations final-state))
                          :plan (:plan final-state)
                          :result (:result final-state)
                          :phase-history (:phase-history final-state)
                          :elapsed-ms (- (System/currentTimeMillis)
                                         (:started-at final-state))})))
          (catch Throwable e
            (handle-phase-error! config agent-id :error out-ch e))
          (finally
            (close! out-ch))))
      out-ch)))

(defn ->saa-orchestrator
  "Create an SAAOrchestrator instance with optional config."
  ([] (->saa-orchestrator {}))
  ([config]
   (->SAAOrchestrator (merge {:shout? true
                              :score-threshold 0.0
                              :max-silence-turns 50
                              :max-abstract-turns 20
                              :max-act-turns 100}
                             config))))

(defn agent-saa-state
  "Get the current SAA state for an agent (read-only)."
  [agent-id]
  (get-agent-state agent-id))

(defn agent-saa-phase
  "Get just the current SAA phase keyword for an agent."
  [agent-id]
  (:phase (get-agent-state agent-id)))

(defn list-active-saa
  "List all agents currently in active SAA phases."
  []
  (->> @agent-states
       (filter (fn [[_ state]]
                 (#{:silence :abstract :act} (:phase state))))
       (mapv (fn [[agent-id state]]
               {:agent-id agent-id
                :phase (:phase state)
                :task (:task state)
                :started-at (:started-at state)
                :elapsed-ms (- (System/currentTimeMillis) (:started-at state))}))))

(defn clear-completed-states!
  "Remove SAA state for all completed or errored agents."
  []
  (let [to-clear (->> @agent-states
                      (filter (fn [[_ state]]
                                (#{:complete :error} (:phase state))))
                      (map first))]
    (doseq [agent-id to-clear]
      (clear-agent-state! agent-id))
    {:cleared (count to-clear)}))

(defn clear-all-states!
  "Remove all SAA states."
  []
  (reset! agent-states {})
  nil)
