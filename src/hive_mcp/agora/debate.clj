(ns hive-mcp.agora.debate
  "Extensible Agora debate automation with pluggable participant backends.

   Implements ADR 20260124224722-51e4fad6: Drone-Based Agora Automation.

   Architecture:
   - IDebateParticipant protocol: Open for extension (O)
   - DroneParticipant: Default implementation using OpenRouter drones
   - LingParticipant: Alternative using Claude lings (future)

   Key features:
   - Auto-spawn participants as debate actors
   - Event-driven turn relay (no manual dispatch)
   - Structured JSON output parsing (no NLP)
   - Auto-consensus detection and termination"

  (:require [hive-mcp.agora.schema :as schema]
            [hive-mcp.agora.signal :as signal]
            [hive-mcp.agora.consensus :as consensus]
            [hive-mcp.channel.websocket :as ws]
            [hive-mcp.events.core :as ev]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IDebateParticipant Protocol
;; =============================================================================

(defprotocol IDebateParticipant
  "Protocol for debate participants. Implement this to add new participant types.

   Examples:
   - DroneParticipant: Uses OpenRouter free-tier models
   - LingParticipant: Uses Claude lings (premium)
   - MockParticipant: For testing"

  (participant-id [this]
    "Return unique identifier for this participant.")

  (participant-type [this]
    "Return participant type keyword (:drone, :ling, :mock).")

  (send-prompt! [this prompt]
    "Send prompt to participant and return response.
     Returns {:response string} or {:error string}.")

  (get-role [this]
    "Return the role this participant plays in the debate.")

  (get-position [this]
    "Return the position this participant argues for."))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const default-model "x-ai/grok-code-fast-1")
(def ^:const debate-preset "drone-worker")
(def ^:const max-turns 20)

;; =============================================================================
;; DroneParticipant Implementation (Default)
;; =============================================================================

(defrecord DroneParticipant [id role position model]
  IDebateParticipant

  (participant-id [_] id)

  (participant-type [_] :drone)

  (send-prompt! [_ prompt]
    (require 'hive-mcp.agent.core)
    (let [delegate-fn (resolve 'hive-mcp.agent.core/delegate-drone!)]
      (try
        (let [result (delegate-fn {:task prompt
                                   :preset debate-preset
                                   :trace false})]
          (if (= (:status result) :completed)
            {:response (:result result)}
            {:error (or (:message result) "Drone delegation failed")}))
        (catch Throwable e
          (log/error e "Drone delegation failed")
          {:error (str "Delegation failed: " (.getMessage e))}))))

  (get-role [_] role)

  (get-position [_] position))

(defn create-drone-participant
  "Factory function for creating DroneParticipant.

   Arguments:
   - role: Role in debate (e.g., 'advocate', 'skeptic')
   - position: Position to argue (e.g., 'For Strategy pattern')
   - opts: Optional {:model 'model-id' :id 'custom-id'}

   Returns: DroneParticipant record"
  [role position & [{:keys [model id]}]]
  (->DroneParticipant
   (or id (str "drone-" (System/currentTimeMillis) "-" (rand-int 1000)))
   role
   position
   (or model default-model)))

;; =============================================================================
;; MockParticipant for Testing
;; =============================================================================

(defrecord MockParticipant [id role position responses response-idx]
  IDebateParticipant

  (participant-id [_] id)

  (participant-type [_] :mock)

  (send-prompt! [_ _prompt]
    (let [idx @response-idx
          response (get responses idx {:signal "approve" :message "Mock approval"})]
      (swap! response-idx inc)
      {:response (json/write-str response)}))

  (get-role [_] role)

  (get-position [_] position))

(defn create-mock-participant
  "Factory for MockParticipant (testing).

   Arguments:
   - role: Role in debate
   - position: Position to argue
   - responses: Vector of response maps [{:signal :message :confidence}]"
  [role position responses]
  (->MockParticipant
   (str "mock-" (rand-int 10000))
   role
   position
   (vec responses)
   (atom 0)))

;; =============================================================================
;; Debate State Tracking
;; =============================================================================

(defonce ^{:doc "Map of dialogue-id -> {:participants [...] :current-turn N :methodology kw}
                 Participants are IDebateParticipant implementations."}
  debate-state
  (atom {}))

(defn- register-debate!
  "Register a new debate with its participants (protocol implementations)."
  [dialogue-id {:keys [participants methodology topic]}]
  (swap! debate-state assoc dialogue-id
         {:participants participants
          :methodology (or methodology :opinion)
          :topic topic
          :current-turn 0
          :turn-order (mapv participant-id participants)
          :participant-types (into {} (map (fn [p] [(participant-id p) (participant-type p)])
                                           participants))}))

(defn- get-debate
  "Get debate state by dialogue-id."
  [dialogue-id]
  (get @debate-state dialogue-id))

(defn- advance-turn!
  "Advance to next turn in debate."
  [dialogue-id]
  (swap! debate-state update-in [dialogue-id :current-turn] inc))

(defn- cleanup-debate!
  "Remove debate state after completion."
  [dialogue-id]
  (swap! debate-state dissoc dialogue-id))

;; =============================================================================
;; Prompt Formatting
;; =============================================================================

(defn- format-debate-prompt
  "Format prompt for drone with dialogue context.

   Injects role, position, topic, and previous message."
  [{:keys [dialogue-id _drone-id role position topic turn-num previous-message]}]
  (str "You are a debate participant. Argue your position concisely.\n\n"
       "DIALOGUE: " dialogue-id "\n"
       "TURN: " turn-num "\n"
       "YOUR ROLE: " role "\n"
       "YOUR POSITION: " position "\n"
       "TOPIC: " topic "\n"
       (when previous-message
         (str "\nOPPONENT SAID: " previous-message "\n"))
       "\n## REQUIRED OUTPUT FORMAT\n"
       "Respond with ONLY this JSON (no other text):\n"
       "{\"signal\": \"propose\", \"message\": \"Your argument (2-3 sentences)\", \"confidence\": 0.8}\n\n"
       "Valid signals: propose, counter, approve, no-change, defer\n"
       "- propose: New argument\n"
       "- counter: Disagree with opponent\n"
       "- approve: Accept OPPONENT's argument (agree with THEIR proposal, not your own)\n"
       "- no-change: No objection to current state\n\n"
       "YOUR TURN. Output JSON only:"))

;; =============================================================================
;; Response Parsing
;; =============================================================================

(defn parse-drone-response
  "Parse structured JSON response from drone via signal.clj.

   Expects: {\"signal\": \"...\", \"message\": \"...\", \"confidence\": N,
             \"target\": \"...\" (optional), \"evidence\": [...] (optional)}
   Returns: {:signal :keyword :message string :confidence float
             :target string :evidence vector}

   Returns {:error ...} if parsing fails."
  [response]
  (try
    (let [;; Try to extract JSON from response (handle markdown code blocks)
          json-str (if (str/includes? response "```")
                     (-> response
                         (str/replace #"```json\s*" "")
                         (str/replace #"```\s*" "")
                         str/trim)
                     (str/trim response))
          parsed (json/read-str json-str :key-fn keyword)
          ;; Use signal.clj for structured parsing
          signal-map (signal/signal-from-map
                      {:type (keyword (:signal parsed))
                       :message (:message parsed)
                       :confidence (:confidence parsed)
                       :target (:target parsed)
                       :evidence (:evidence parsed)})]
      (if (:error signal-map)
        {:error (:error signal-map)
         :raw response}
        {:signal (:type signal-map)
         :message (:message signal-map)
         :confidence (:strength signal-map)
         :target (:target signal-map)
         :evidence (:evidence signal-map)}))
    (catch Throwable e
      (log/warn "Failed to parse drone response:" (.getMessage e))
      {:error (str "JSON parse error: " (.getMessage e))
       :raw response})))

;; =============================================================================
;; Turn Execution
;; =============================================================================

(defn- get-next-participant
  "Get the next participant in turn order (protocol-based)."
  [dialogue-id]
  (let [{:keys [participants turn-order current-turn]} (get-debate dialogue-id)
        idx (mod current-turn (count turn-order))
        target-id (nth turn-order idx)]
    (first (filter #(= (participant-id %) target-id) participants))))

(defn- get-previous-message
  "Get the previous turn's message for context."
  [dialogue-id]
  (let [turns (schema/get-turns dialogue-id)]
    (when (seq turns)
      (:message (last (sort-by :turn-number turns))))))

(defn execute-turn!
  "Execute a single debate turn using protocol-based participants.

   1. Get next participant (via IDebateParticipant protocol)
   2. Format prompt with context
   3. Send prompt via protocol method
   4. Parse response
   5. Record turn
   6. Check consensus
   7. Emit events

   Returns: {:success bool :signal kw :message string :consensus? bool}"
  [dialogue-id]
  (let [debate (get-debate dialogue-id)
        _ (when-not debate
            (throw (ex-info "Debate not found" {:dialogue-id dialogue-id})))
        dialogue (schema/get-dialogue dialogue-id)
        _ (when (not= :active (:status dialogue))
            (throw (ex-info "Dialogue not active" {:status (:status dialogue)})))
        turn-num (inc (:current-turn debate))
        _ (when (> turn-num max-turns)
            (schema/update-dialogue-status! dialogue-id :timeout)
            (throw (ex-info "Max turns exceeded" {:turn-num turn-num})))
        participant (get-next-participant dialogue-id)
        participant-pid (participant-id participant)
        previous-msg (get-previous-message dialogue-id)
        ;; Format and send prompt via protocol
        prompt (format-debate-prompt
                {:dialogue-id dialogue-id
                 :drone-id participant-pid
                 :role (get-role participant)
                 :position (get-position participant)
                 :topic (:topic debate)
                 :turn-num turn-num
                 :previous-message previous-msg})
        result (send-prompt! participant prompt)]

    (if (:error result)
      ;; Participant failed - record error and continue
      (do
        (log/error "Participant failed:" (:error result))
        (ws/emit! :agora/participant-error {:dialogue-id dialogue-id
                                            :participant-id participant-pid
                                            :participant-type (participant-type participant)
                                            :error (:error result)})
        {:success false :error (:error result)})

      ;; Parse response and record turn
      (let [parsed (parse-drone-response (:response result))]
        (if (:error parsed)
          ;; Parse failed - use :propose as default
          (do
            (log/warn "Parse failed, defaulting to :propose")
            (schema/add-turn! dialogue-id
                              {:sender participant-pid
                               :receiver "all"
                               :message (or (:raw parsed) "Parse error")
                               :signal :propose})
            (advance-turn! dialogue-id)
            {:success false :error (:error parsed) :raw (:raw parsed)})

          ;; Success - record turn and check consensus
          (let [{:keys [signal message confidence target evidence]} parsed
                ;; Determine receiver (next participant or "all")
                next-participant (get-next-participant dialogue-id)
                receiver (if next-participant
                           (participant-id next-participant)
                           "all")]
            (schema/add-turn! dialogue-id
                              {:sender participant-pid
                               :receiver receiver
                               :message message
                               :signal signal})
            (advance-turn! dialogue-id)

            ;; Emit turn event to WebSocket (for Emacs UI)
            (ws/emit! :agora/turn-completed
                      {:dialogue-id dialogue-id
                       :participant-id participant-pid
                       :participant-type (participant-type participant)
                       :signal signal
                       :message message
                       :confidence confidence
                       :target target
                       :evidence evidence
                       :turn-num turn-num})

            ;; Check consensus
            (let [consensus-status (consensus/check-consensus dialogue-id)
                  reached? (= :consensus consensus-status)
                  turn-data {:dialogue-id dialogue-id
                             :participant-id participant-pid
                             :participant-type (participant-type participant)
                             :signal signal
                             :message message
                             :confidence confidence
                             :turn-num turn-num}]
              (when reached?
                (schema/update-dialogue-status! dialogue-id :consensus)
                (ws/emit! :agora/consensus {:dialogue-id dialogue-id
                                            :turns turn-num})
                ;; Dispatch consensus event through event system for crystallization
                (try
                  (ev/dispatch [:agora/consensus {:dialogue-id dialogue-id
                                                  :turns turn-num}])
                  (catch Throwable e
                    (log/warn "Event dispatch failed for consensus:" (.getMessage e)))))

              ;; Dispatch turn-completed through event system for auto-continuation
              ;; Only dispatch if NOT consensus (consensus handler takes over)
              (when-not reached?
                (try
                  (ev/dispatch [:agora/turn-completed turn-data])
                  (catch Throwable e
                    (log/warn "Event dispatch failed for turn-completed:" (.getMessage e)))))

              {:success true
               :signal signal
               :message message
               :confidence confidence
               :turn-num turn-num
               :consensus? reached?})))))))

;; =============================================================================
;; Debate Lifecycle
;; =============================================================================

(defn create-debate!
  "Create a new debate with pluggable participant backends.

   Arguments:
   - topic: What the debate is about
   - roles: Vector of {:role \"advocate\" :position \"For X\"}
   - opts: Optional:
     - :methodology - :opinion|:fact-based|:mixed
     - :participant-factory - fn taking (role position) -> IDebateParticipant
                             Defaults to create-drone-participant
     - :model - Model ID for drone participants

   Returns: {:dialogue-id \"...\" :participants [...] :status :active}

   Example (default drones):
   (create-debate!
     \"Should we use Strategy vs Decorator?\"
     [{:role \"advocate\" :position \"For Strategy pattern\"}
      {:role \"skeptic\" :position \"Against, prefer Decorator\"}])

   Example (custom participant factory):
   (create-debate!
     \"Topic\"
     roles
     {:participant-factory create-mock-participant})"
  [topic roles & [{:keys [methodology participant-factory model]}]]
  {:pre [(string? topic) (>= (count roles) 2)]}
  (let [;; Factory defaults to drone participant
        factory (or participant-factory
                    (fn [role position]
                      (create-drone-participant role position {:model model})))
        ;; Create participants via factory (protocol implementations)
        participants (mapv (fn [role-map]
                             (factory (:role role-map) (:position role-map)))
                           roles)
        participant-ids (mapv participant-id participants)
        ;; Create dialogue via schema
        {:keys [id]} (schema/create-dialogue!
                      {:participants participant-ids
                       :name topic
                       :config {:threshold 0.8
                                :timeout-ms 600000
                                :methodology (or methodology :opinion)}})]
    ;; Register debate state with protocol participants
    (register-debate! id {:participants participants
                          :methodology (or methodology :opinion)
                          :topic topic})
    (log/info "Created debate:" id "with" (count participants) "participants"
              "types:" (mapv participant-type participants))
    (let [participant-info (mapv (fn [p]
                                   {:id (participant-id p)
                                    :type (participant-type p)
                                    :role (get-role p)})
                                 participants)]
      (ws/emit! :agora/debate-created {:dialogue-id id
                                       :topic topic
                                       :participants participant-info
                                       :methodology (or methodology :opinion)})
      ;; Emit debate-started event for auto-kick of first turn
      (try
        (ev/dispatch [:agora/debate-started {:dialogue-id id
                                             :topic topic
                                             :participants participant-info}])
        (catch Throwable e
          (log/warn "Event dispatch failed for debate-started:" (.getMessage e)))))
    {:dialogue-id id
     :participants (mapv (fn [p]
                           {:id (participant-id p)
                            :type (participant-type p)
                            :role (get-role p)
                            :position (get-position p)})
                         participants)
     :topic topic
     :methodology (or methodology :opinion)
     :status :active}))

(defn run-debate!
  "Run a debate to completion (blocking).

   Executes turns until consensus or max-turns reached.

   Returns: {:dialogue-id :status :turns :final-signal :summary}"
  [dialogue-id]
  (log/info "Running debate:" dialogue-id)
  (loop [turn-results []]
    (let [result (try
                   (execute-turn! dialogue-id)
                   (catch clojure.lang.ExceptionInfo e
                     {:error (ex-message e) :data (ex-data e)}))]
      (cond
        ;; Consensus reached
        (:consensus? result)
        (do
          (cleanup-debate! dialogue-id)
          {:dialogue-id dialogue-id
           :status :consensus
           :turns (count (conj turn-results result))
           :final-signal (:signal result)
           :summary "Debate reached consensus"})

        ;; Error occurred
        (:error result)
        (do
          (when (= "Dialogue not active" (:error result))
            (cleanup-debate! dialogue-id))
          {:dialogue-id dialogue-id
           :status (or (-> result :data :status) :error)
           :turns (count turn-results)
           :error (:error result)
           :summary (str "Debate ended: " (:error result))})

        ;; Continue to next turn
        :else
        (recur (conj turn-results result))))))

(defn start-debate!
  "Create and run a debate in one call.

   Convenience function combining create-debate! and run-debate!.

   Returns the same as run-debate!"
  [topic roles & [opts]]
  (let [{:keys [dialogue-id]} (create-debate! topic roles opts)]
    (run-debate! dialogue-id)))

;; =============================================================================
;; Async Debate (Event-Driven)
;; =============================================================================

(defn start-debate-async!
  "Start a debate without blocking.

   Kicks off first turn and relies on event handlers for subsequent turns.
   Use this when you want event-driven debate flow.

   Returns: {:dialogue-id :status :first-turn-result}"
  [topic roles & [opts]]
  (let [{:keys [dialogue-id] :as debate} (create-debate! topic roles opts)
        first-result (execute-turn! dialogue-id)]
    (assoc debate :first-turn-result first-result)))

(defn continue-debate!
  "Continue an async debate by executing next turn.

   Called by event handler when drone responds.

   Returns turn result or nil if debate ended."
  [dialogue-id]
  (when-let [_debate (get-debate dialogue-id)]
    (when (= :active (:status (schema/get-dialogue dialogue-id)))
      (execute-turn! dialogue-id))))

;; =============================================================================
;; Queries
;; =============================================================================

(defn get-debate-status
  "Get current status of a debate.

   Returns: {:dialogue-id :status :turn-count :participants :methodology}"
  [dialogue-id]
  (when-let [debate (get-debate dialogue-id)]
    (let [dialogue (schema/get-dialogue dialogue-id)
          turns (schema/get-turns dialogue-id)
          participants (:participants debate)]
      {:dialogue-id dialogue-id
       :status (:status dialogue)
       :turn-count (count turns)
       :current-turn (:current-turn debate)
       :participants (mapv (fn [p]
                             {:id (participant-id p)
                              :type (participant-type p)
                              :role (get-role p)})
                           participants)
       :methodology (:methodology debate)
       :topic (:topic debate)})))

(defn list-active-debates
  "List all active debates (any participant type)."
  []
  (->> @debate-state
       (filter (fn [[id _]]
                 (= :active (:status (schema/get-dialogue id)))))
       (mapv (fn [[id state]]
               {:dialogue-id id
                :topic (:topic state)
                :methodology (:methodology state)
                :current-turn (:current-turn state)
                :participant-count (count (:participants state))
                :participant-types (set (map participant-type (:participants state)))}))))
