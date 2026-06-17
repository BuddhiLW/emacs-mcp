(ns hive-mcp.agent.saa.orchestrator
  "SAA (Silence-Abstract-Act) orchestrator implementing ISAAOrchestrator protocol.

   Ports are injected at construction from hive-mcp.saa.registry: an IPhaseProvider
   drives phase-config/build-options/execute-phase!, an IObservationScorer scores
   Silence observations, and an IPlanSynthesizer proposes plans. Defaults
   (:saa/default) preserve today's behavior (DefaultPhaseProvider -> bridge query!,
   Korzybski scoring). The :es/score / :ep/generate / :ec/enrich extension hooks layer
   OVER the ports and never fall back into any provider SDK."
  (:require [clojure.core.async :as async :refer [go go-loop chan >! <! close!]]
            [clojure.string :as str]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.protocols.agent-bridge :as bridge]
            [hive-mcp.protocols.saa :as psaa]
            [hive-mcp.saa.registry :as registry]
            [hive-mcp.saa.prompt :as prompt]
            [hive-mcp.saa.model :as model]
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
  "Score via the injected IObservationScorer port, then layer the optional
   :es/score extension OVER the port output. FIX#5: no SDK fallback — the port
   default (DefaultObservationScorer) is the Korzybski floor."
  [scorer observations]
  (let [scored (psaa/score scorer observations)]
    (if-let [score-fn (ext/get-extension :es/score)]
      (score-fn scored)
      scored)))

(defn- plan-from-observations-enhanced
  "Synthesize via the injected IPlanSynthesizer port, then layer the optional
   :ep/generate extension OVER the port output. No SDK fallback."
  [planner scored task]
  (let [synthesized (psaa/synthesize planner scored task)]
    (if-let [plan-fn (ext/get-extension :ep/generate)]
      (plan-fn synthesized task)
      synthesized)))

(defn- enrich-silence-context
  "Enrich Silence phase with additional context from extension. No SDK fallback."
  [task]
  (if-let [enrich-fn (ext/get-extension :ec/enrich)]
    (enrich-fn task)
    nil))

(defn- build-phase-prompt
  "Build the full prompt for a SAA phase via the provider-neutral prompt builder
   drawing the goal fragment from saa.model."
  [phase task-or-content extra-context]
  (prompt/build-phase-prompt phase task-or-content extra-context model/saa-phase-model))

(defn- build-phase-opts
  "Build provider-options for a phase through the injected IPhaseProvider.
   The provider's build-options is the sole vendor-token emitter; user-opts pass
   through as neutral overrides. :phase is stamped so the stream adapter can tag."
  [provider phase user-opts]
  (psaa/build-options provider phase (assoc user-opts :phase phase)))

(defn- execute-phase-via-provider!
  "Execute a SAA phase through the injected IPhaseProvider. The default provider
   queries the agent session (bridge query!), behavior-identical to the prior
   inline path."
  [provider session prompt provider-opts]
  (psaa/execute-phase! provider session prompt provider-opts))

(defn- pm->raw-envelope
  "Adapt one injected-provider PhaseMessage back to the legacy raw envelope
   ({:type _ :content/:data _}) the phase collectors and consumers expect.
   Keeps the streaming envelope shape behavior-identical to the prior bridge path."
  [pm]
  (case (:adt/variant pm)
    :pm/chunk          {:type :message  :content (:content pm)}
    :pm/observation    {:type :message  :content (:observation pm)}
    :pm/phase-complete {:type :complete :content (:payload pm)}
    :pm/error          {:type :error    :error (:error pm)}
    :pm/started        {:type :started}
    :pm/saa-complete   (assoc (:summary pm) :type :saa-complete)
    pm))

(defn- raw-or-pm
  "Normalize a phase-stream message to the legacy raw envelope. The injected
   provider streams :pm/* variants; a plain map already carries :type."
  [msg]
  (if (:adt/variant msg) (pm->raw-envelope msg) msg))

(defn- collect-phase-messages!
  "Drain a phase channel, normalizing each message to the legacy raw envelope,
   stamping :saa-phase, forwarding to out-ch, and collecting the raw envelopes."
  [phase-ch out-ch saa-phase]
  (go-loop [messages []]
    (if-let [msg (<! phase-ch)]
      (let [raw (raw-or-pm msg)]
        (when out-ch
          (>! out-ch (assoc raw :saa-phase saa-phase)))
        (recur (conj messages raw)))
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
          provider (:phase-provider config)
          out-ch (chan 1024)]
      (init-agent-state! agent-id task)
      (transition-phase! agent-id :silence)
      (maybe-shout! config agent-id :silence "Starting observation phase")
      (go
        (try
          (let [enrichment (enrich-silence-context task)
                prompt (build-phase-prompt :silence task enrichment)
                phase-opts (build-phase-opts provider :silence opts)
                phase-ch (execute-phase-via-provider! provider session prompt phase-opts)
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
          provider (:phase-provider config)
          scorer (:scorer config)
          planner (:planner config)
          out-ch (chan 1024)]
      (transition-phase! agent-id :abstract)
      (maybe-shout! config agent-id :abstract
                    (str "Starting synthesis with " (count observations) " observations"))
      (go
        (try
          (let [scored (score-observations-enhanced scorer observations)
                task (:task (get-agent-state agent-id))
                ext-plan (plan-from-observations-enhanced planner scored task)
                prompt (build-phase-prompt :abstract scored task)
                phase-opts (build-phase-opts provider :abstract opts)
                phase-ch (execute-phase-via-provider! provider session prompt phase-opts)
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
          provider (:phase-provider config)
          out-ch (chan 1024)]
      (transition-phase! agent-id :act)
      (maybe-shout! config agent-id :act "Starting execution phase")
      (go
        (try
          (let [task (:task (get-agent-state agent-id))
                prompt (build-phase-prompt :act plan task)
                phase-opts (build-phase-opts provider :act opts)
                phase-ch (execute-phase-via-provider! provider session prompt phase-opts)
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

  ;; W5 retires this inline phase-flow: full-SAA delegation moves to the mesh FSM
  ;; (saa.mesh ->saa-resources, not yet on classpath). Until then this drives the
  ;; cycle by chaining the port-routed run-silence!/run-abstract!/run-act! — no
  ;; vendor SDK reference, behavior-preserving.
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
  "Create an SAAOrchestrator instance with optional config.

   The three SAA ports are resolved from saa.registry at construction and merged
   into config (caller overrides win): an IPhaseProvider drives
   phase-config/build-options/execute-phase!, an IObservationScorer scores
   observations, an IPlanSynthesizer proposes plans. :provider-id selects the
   registered provider/scorer/planner triple (defaults to :saa/default — the
   LSP-clean built-ins). Resolvers always return a satisfying record (never nil)."
  ([] (->saa-orchestrator {}))
  ([config]
   (let [provider-id (get config :provider-id :saa/default)]
     (->SAAOrchestrator
      (merge {:shout? true
              :score-threshold 0.0
              :max-silence-turns 50
              :max-abstract-turns 20
              :max-act-turns 100
              :phase-provider (registry/lookup-phase-provider-or-default provider-id)
              :scorer (registry/lookup-scorer-or-default provider-id)
              :planner (registry/lookup-planner-or-default provider-id)}
             config)))))

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
