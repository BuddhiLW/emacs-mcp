(ns hive-mcp.agora.stages
  "Two-Stage Agora: staged debate orchestration.

   Stage 1 (:research) - Research drones gather evidence in parallel.
                          Each drone investigates one focus area and
                          outputs structured evidence JSON.
                          Stage ends when all research drones signal :no-change.

   Stage 2 (:debate)   - Debate drones argue positions with Stage 1
                          evidence injected into their context.
                          Standard Nash equilibrium consensus applies.

   Event flow:
   :agora/debate-started -> Stage 1 research drones dispatched
   :agora/turn-completed (all research done) -> :agora/stage-transition
   :agora/stage-transition -> Collect evidence, spawn Stage 2 participants
   Stage 2 runs via normal event chain until consensus/timeout."

  (:require [hive-mcp.agora.schema :as schema]
            [hive-mcp.channel.websocket :as ws]
            [hive-mcp.events.core :as ev]
            [datascript.core :as d]
            [clojure.data.json :as json]
            [clojure.string]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(declare cleanup-staged-debate!)

;; =============================================================================
;; Stage Definitions
;; =============================================================================

(def valid-stages
  "Valid stage keywords."
  #{:research :debate})

(def default-stage-config
  "Default configuration for staged debates."
  {:research {:max-turns-per-drone 5
              :parallel true}
   :debate   {:use-evidence true
              :evidence-injection :context}})

;; =============================================================================
;; Stage State Tracking
;; =============================================================================

(defonce ^{:doc "Map of dialogue-id -> stage state.
                 Tracks which stage a debate is in, evidence pool,
                 and participant assignments per stage."}
  stage-state
  (atom {}))

(defn- register-staged-debate!
  "Register a new staged debate."
  [dialogue-id {:keys [stage-config research-participants debate-participants topic]}]
  (swap! stage-state assoc dialogue-id
         {:current-stage :research
          :stage-config (merge default-stage-config stage-config)
          :topic topic
          :evidence-pool []
          :research-participants (or research-participants [])
          :debate-participants (or debate-participants [])
          :research-complete #{}})
  ;; Store stage in DataScript
  (let [c (schema/get-conn)
        db @c
        eid (:db/id (d/entity db [:agora.dialogue/id dialogue-id]))]
    (when eid
      (d/transact! c [{:db/id eid
                       :agora.dialogue/stage :research
                       :agora.dialogue/stage-config (pr-str (merge default-stage-config stage-config))
                       :agora.dialogue/evidence-pool "[]"}])))
  (log/info "Registered staged debate:" dialogue-id "starting with :research stage"))

(defn staged-debate?
  "Check if a dialogue is a staged debate."
  [dialogue-id]
  (contains? @stage-state dialogue-id))

(defn current-stage
  "Get current stage for a dialogue."
  [dialogue-id]
  (:current-stage (get @stage-state dialogue-id)))

(defn get-evidence-pool
  "Get accumulated evidence from research stage."
  [dialogue-id]
  (:evidence-pool (get @stage-state dialogue-id) []))

;; =============================================================================
;; Evidence Collection
;; =============================================================================

(defn collect-evidence!
  "Collect evidence from a research drone's turn.

   Extracts evidence from the turn's signal map and adds to pool."
  [dialogue-id participant-id evidence]
  (when (seq evidence)
    (let [tagged-evidence (mapv #(assoc % :gathered-by participant-id) evidence)]
      (swap! stage-state update-in [dialogue-id :evidence-pool] into tagged-evidence)
      ;; Update DataScript
      (let [c (schema/get-conn)
            db @c
            eid (:db/id (d/entity db [:agora.dialogue/id dialogue-id]))
            current-pool (:evidence-pool (get @stage-state dialogue-id))]
        (when eid
          (d/transact! c [{:db/id eid
                           :agora.dialogue/evidence-pool (pr-str current-pool)}])))
      (log/debug "Collected" (count tagged-evidence) "evidence items from" participant-id
                 "for dialogue" dialogue-id))))

(defn mark-research-complete!
  "Mark a research participant as done."
  [dialogue-id participant-id]
  (swap! stage-state update-in [dialogue-id :research-complete] conj participant-id))

(defn all-research-complete?
  "Check if all research participants have completed."
  [dialogue-id]
  (let [state (get @stage-state dialogue-id)]
    (when state
      (let [research-ids (set (map :id (:research-participants state)))
            complete-ids (:research-complete state)]
        (and (seq research-ids)
             (= research-ids complete-ids))))))

;; =============================================================================
;; Stage Transitions
;; =============================================================================

(defn transition-to-debate!
  "Transition from research stage to debate stage.

   1. Collect all evidence from research stage
   2. Update stage to :debate
   3. Prepare evidence injection for debate participants
   4. Emit :agora/stage-transition event

   Returns: {:stage :debate :evidence-count N}"
  [dialogue-id]
  (let [state (get @stage-state dialogue-id)
        evidence-pool (:evidence-pool state)
        topic (:topic state)]
    ;; Update stage
    (swap! stage-state assoc-in [dialogue-id :current-stage] :debate)
    ;; Update DataScript
    (let [c (schema/get-conn)
          db @c
          eid (:db/id (d/entity db [:agora.dialogue/id dialogue-id]))]
      (when eid
        (d/transact! c [{:db/id eid
                         :agora.dialogue/stage :debate}])))
    ;; Emit stage transition event
    (ws/emit! :agora/stage-transition {:dialogue-id dialogue-id
                                       :from-stage :research
                                       :to-stage :debate
                                       :evidence-count (count evidence-pool)})
    (try
      (ev/dispatch [:agora/stage-transition {:dialogue-id dialogue-id
                                             :from-stage :research
                                             :to-stage :debate
                                             :evidence-pool evidence-pool
                                             :topic topic}])
      (catch Throwable e
        (log/warn "Event dispatch failed for stage-transition:" (.getMessage e))))
    (log/info "Stage transition:" dialogue-id "research -> debate"
              "with" (count evidence-pool) "evidence items")
    {:stage :debate
     :evidence-count (count evidence-pool)}))

;; =============================================================================
;; Evidence Formatting (for injection into debate prompts)
;; =============================================================================

(defn format-evidence-pool
  "Format evidence pool for injection into debate drone prompts.

   Returns a string suitable for the EVIDENCE POOL section of
   the debate-drone preset context."
  [evidence-pool]
  (if (empty? evidence-pool)
    "No evidence gathered."
    (->> evidence-pool
         (map (fn [{:keys [source content confidence gathered-by]}]
                (str "- [" (or source "unknown") "] "
                     content
                     (when confidence (str " (confidence: " confidence ")"))
                     (when gathered-by (str " — gathered by " gathered-by)))))
         (clojure.string/join "\n"))))

(defn format-evidence-json
  "Format evidence pool as JSON for structured injection."
  [evidence-pool]
  (json/write-str
   (mapv (fn [{:keys [source content confidence]}]
           {:source (or source "unknown")
            :content content
            :confidence (or confidence 0.5)})
         evidence-pool)))

;; =============================================================================
;; Staged Debate Creation
;; =============================================================================

(defn create-staged-debate!
  "Create a two-stage debate.

   Arguments:
   - topic: What the debate is about
   - research-roles: Vector of {:role :position} for Stage 1 research drones
   - debate-roles: Vector of {:role :position} for Stage 2 debate drones
   - opts: Optional {:stage-config {...} :methodology :model}

   Returns: {:dialogue-id :stage :research-participants :debate-participants}"
  [topic research-roles debate-roles & [{:keys [stage-config methodology _model]}]]
  {:pre [(string? topic) (seq research-roles) (>= (count debate-roles) 2)]}
  (let [;; Create participants for both stages (but only research starts)
        ;; Research participants are registered with the dialogue
        ;; Debate participants are stored in stage-state for later
        research-participant-info (mapv (fn [r] {:id (str "research-" (:role r) "-" (System/currentTimeMillis))
                                                 :role (:role r)
                                                 :position (or (:position r) (:role r))
                                                 :type :drone})
                                        research-roles)
        debate-participant-info (mapv (fn [r] {:id (str "debate-" (:role r) "-" (System/currentTimeMillis))
                                               :role (:role r)
                                               :position (:position r)
                                               :type :drone})
                                      debate-roles)
        all-ids (mapv :id (concat research-participant-info debate-participant-info))
        ;; Create the dialogue in schema with all participants
        {:keys [id]} (schema/create-dialogue!
                      {:participants all-ids
                       :name topic
                       :config {:threshold 0.8
                                :timeout-ms 600000
                                :methodology (or methodology :opinion)
                                :staged true}})]
    ;; Register stage state
    (register-staged-debate! id {:stage-config stage-config
                                 :research-participants research-participant-info
                                 :debate-participants debate-participant-info
                                 :topic topic})
    (log/info "Created staged debate:" id
              "research:" (count research-participant-info)
              "debate:" (count debate-participant-info))
    {:dialogue-id id
     :stage :research
     :research-participants research-participant-info
     :debate-participants debate-participant-info
     :topic topic}))

;; =============================================================================
;; Research Stage Execution
;; =============================================================================

(defn- format-research-prompt
  "Format prompt for a research drone."
  [{:keys [topic role position]}]
  (str "TOPIC: " topic "\n"
       "FOCUS: " (or position role) "\n"
       "QUESTION: Investigate this aspect of the topic and gather evidence.\n\n"
       "GATHER EVIDENCE. Respond with JSON only."))

(defn execute-research-turn!
  "Execute a single research drone turn.

   Sends prompt to research drone, parses evidence from response,
   and collects it into the evidence pool.

   Returns: {:success bool :evidence [...] :signal kw}"
  [dialogue-id participant-info]
  (let [{:keys [id role position]} participant-info
        topic (:topic (get @stage-state dialogue-id))
        prompt (format-research-prompt {:topic topic :role role :position position})]
    ;; Delegate to drone
    (try
      (require 'hive-mcp.agent.core)
      (let [delegate-fn (resolve 'hive-mcp.agent.core/delegate-drone!)
            result (delegate-fn {:task prompt
                                 :preset "research-drone"
                                 :trace false})]
        (if (= (:status result) :completed)
          ;; Parse response for evidence
          (try
            (let [parsed (json/read-str (:result result) :key-fn keyword)
                  evidence (or (:evidence parsed) [])
                  sig (keyword (or (:signal parsed) "no-change"))]
              ;; Collect evidence into pool
              (collect-evidence! dialogue-id id evidence)
              ;; Record turn in schema
              (schema/add-turn! dialogue-id
                                {:sender id
                                 :receiver "all"
                                 :message (or (:message parsed) "Research complete")
                                 :signal sig})
              ;; Check if this drone signals completion
              (when (#{:no-change :defer} sig)
                (mark-research-complete! dialogue-id id))
              {:success true :evidence evidence :signal sig :participant-id id})
            (catch Throwable e
              (log/warn "Failed to parse research response from" id ":" (.getMessage e))
              ;; Mark as complete even on parse failure (don't block transition)
              (mark-research-complete! dialogue-id id)
              {:success false :error (str "Parse error: " (.getMessage e)) :participant-id id}))
          ;; Drone failed
          (do
            (log/warn "Research drone" id "failed:" (:message result))
            (mark-research-complete! dialogue-id id)
            {:success false :error (or (:message result) "Drone failed") :participant-id id})))
      (catch Throwable e
        (log/error e "Research turn failed for" id)
        (mark-research-complete! dialogue-id id)
        {:success false :error (.getMessage e) :participant-id id}))))

(defn execute-research-stage!
  "Execute the full research stage for a staged debate.

   Runs all research drones (sequentially for now, parallel in future),
   collects evidence, and triggers stage transition when all complete.

   Returns: {:stage :research :results [...] :transition? bool}"
  [dialogue-id]
  (let [state (get @stage-state dialogue-id)
        research-participants (:research-participants state)]
    (when-not state
      (throw (ex-info "Not a staged debate" {:dialogue-id dialogue-id})))
    (when-not (= :research (:current-stage state))
      (throw (ex-info "Not in research stage" {:dialogue-id dialogue-id
                                               :current-stage (:current-stage state)})))
    (log/info "Executing research stage for" dialogue-id
              "with" (count research-participants) "drones")
    ;; Execute each research drone
    (let [results (mapv #(execute-research-turn! dialogue-id %) research-participants)]
      ;; Check if all research is complete
      (if (all-research-complete? dialogue-id)
        ;; Transition to debate stage
        (let [transition-result (transition-to-debate! dialogue-id)]
          (log/info "Research stage complete for" dialogue-id
                    "transitioning to debate with"
                    (:evidence-count transition-result) "evidence items")
          {:stage :debate
           :results results
           :transition? true
           :evidence-count (:evidence-count transition-result)})
        ;; Some drones didn't complete (shouldn't happen with sequential execution)
        {:stage :research
         :results results
         :transition? false}))))

(defn start-debate-stage!
  "Start the debate stage with evidence from research.

   Creates debate participants, injects evidence into their context,
   and kicks off the debate.

   Returns: {:dialogue-id :stage :debate :participants [...]}"
  [dialogue-id]
  (let [state (get @stage-state dialogue-id)
        _ (when-not (= :debate (:current-stage state))
            (throw (ex-info "Not in debate stage" {:dialogue-id dialogue-id
                                                   :current-stage (:current-stage state)})))
        evidence-pool (:evidence-pool state)
        debate-participants (:debate-participants state)
        topic (:topic state)
        evidence-text (format-evidence-pool evidence-pool)]
    ;; Create debate using debate.clj with evidence-enriched topic
    (require 'hive-mcp.agora.debate)
    (let [create-fn (resolve 'hive-mcp.agora.debate/create-debate!)
          enriched-topic (str topic "\n\n## EVIDENCE FROM RESEARCH STAGE\n" evidence-text)
          roles (mapv (fn [p] {:role (:role p) :position (:position p)}) debate-participants)
          result (create-fn enriched-topic roles {:methodology :fact-based})]
      (log/info "Debate stage started for" dialogue-id
                "with" (count debate-participants) "participants"
                "and" (count evidence-pool) "evidence items")
      (assoc result :evidence-count (count evidence-pool)
             :staged-dialogue-id dialogue-id))))

(defn run-staged-debate!
  "Run a complete two-stage debate: research then debate.

   1. Execute research stage (all drones gather evidence)
   2. Transition to debate stage
   3. Start debate with evidence injection
   4. Run debate to consensus

   Returns: combined result from both stages."
  [dialogue-id]
  (log/info "Running staged debate:" dialogue-id)
  ;; Stage 1: Research
  (let [research-result (execute-research-stage! dialogue-id)]
    (if (:transition? research-result)
      ;; Stage 2: Debate
      (let [debate-result (start-debate-stage! dialogue-id)]
        ;; Run the debate to completion
        (require 'hive-mcp.agora.debate)
        (let [run-fn (resolve 'hive-mcp.agora.debate/run-debate!)
              final-result (run-fn (:dialogue-id debate-result))]
          ;; Cleanup
          (cleanup-staged-debate! dialogue-id)
          {:staged-dialogue-id dialogue-id
           :research research-result
           :debate final-result
           :status (:status final-result)}))
      ;; Research didn't complete
      {:staged-dialogue-id dialogue-id
       :research research-result
       :status :research-incomplete})))

;; =============================================================================
;; Stage Queries
;; =============================================================================

(defn get-stage-status
  "Get comprehensive stage status for a dialogue."
  [dialogue-id]
  (when-let [state (get @stage-state dialogue-id)]
    {:dialogue-id dialogue-id
     :current-stage (:current-stage state)
     :topic (:topic state)
     :evidence-count (count (:evidence-pool state))
     :research-participants (count (:research-participants state))
     :debate-participants (count (:debate-participants state))
     :research-complete-count (count (:research-complete state))
     :all-research-done? (all-research-complete? dialogue-id)}))

;; =============================================================================
;; Cleanup
;; =============================================================================

(defn cleanup-staged-debate!
  "Remove stage state after debate completion."
  [dialogue-id]
  (swap! stage-state dissoc dialogue-id))
