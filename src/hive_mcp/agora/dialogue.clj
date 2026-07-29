(ns hive-mcp.agora.dialogue
  "Dialogue-aware dispatch wrapper for ling-to-ling communication.

   Part of the Agora (Multi-Ling Dialogue Infrastructure) epic.

   KEY CONCEPTS
   ============
   - **Dialogue**: A conversation thread between 2+ lings with turn tracking
   - **Turn**: A single message in a dialogue with sender, receiver, signal
   - **Signal**: Nash equilibrium signal indicating participant stance

   SIGNALS (Nash Equilibrium)
   ==========================
   Each turn includes a signal parsed from `[SIGNAL: X]` prefix:

   | Signal      | Meaning                    | Effect                |
   |-------------|----------------------------|-----------------------|
   | :propose    | 'Here's a change'          | Resets equilibrium    |
   | :counter    | 'I disagree, alternative'  | Resets equilibrium    |
   | :no-change  | 'I wouldn't change'        | +1 toward equilibrium |
   | :approve    | 'I accept current state'   | +1 toward equilibrium |
   | :defer      | 'I have no opinion'        | Neutral (won't block) |

   USAGE
   =====
   ```clojure
   ;; Create a new dialogue
   (create-dialogue {:participants [\"writer-123\" \"critic-456\"]
                     :topic \"Code review for auth module\"})

   ;; Dispatch within dialogue context
   (dialogue-dispatch
     {:dialogue-id \"d-uuid\"
      :from \"writer-123\"
      :to \"critic-456\"
      :message \"[SIGNAL: propose] Here's my implementation...\"})
   ```"

  (:require [hive-mcp.agora.schema :as schema]
            [hive-mcp.agora.signal :as signal]
            [hive-mcp.tools.swarm.dispatch :as dispatch]
            [hive-mcp.channel.websocket :as ws]
            [datascript.core :as d]
            [clojure.data.json :as json]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Signal Definitions - Delegated to signal.clj (Single Source of Truth)
;; =============================================================================

(def signals
  "Valid dialogue turn signal values. Delegates to signal.clj."
  signal/signal-types)

(def equilibrium-signals
  "Signals that contribute to Nash equilibrium. Delegates to signal.clj."
  signal/equilibrium-signals)

(def disruption-signals
  "Signals that reset/disrupt equilibrium. Delegates to signal.clj."
  signal/disruption-signals)

;; =============================================================================
;; State - Delegated to schema.clj (DataScript)
;; =============================================================================

;; NOTE: All dialogue state is now managed by hive-mcp.agora.schema
;; using DataScript. This namespace provides dialogue-aware dispatch
;; and Nash equilibrium detection on top of schema's CRUD operations.

;; =============================================================================
;; Signal Parsing - Delegated to signal.clj
;; =============================================================================

(defn parse-signal
  "Parse signal from message. Delegates to signal/parse-signal.

   Returns tuple: [signal-type cleaned-message detection-method]

   For structured map input, returns the validated signal.
   For string input, parses via signal/signal-from-legacy.

   Examples:
   (parse-signal \"[SIGNAL: propose] Here's my change\")
   => [:propose \"Here's my change\" :prefix]

   (parse-signal \"Here's my analysis\")
   => [:propose \"Here's my analysis\" :default]"
  [message]
  (let [[signal-map cleaned method] (signal/signal-from-legacy message)]
    [(:type signal-map) cleaned method]))

(defn format-signal
  "Format a signal into message prefix.

   (format-signal :approve \"LGTM\")
   => \"[SIGNAL: approve] LGTM\""
  [signal message]
  (str "[SIGNAL: " (name signal) "] " message))

;; =============================================================================
;; Turn Recording (delegates to schema)
;; =============================================================================

(defn- record-turn!
  "Record a turn in the dialogue via schema's DataScript.

   Turn data:
   {:sender     slave-id
    :receiver   slave-id
    :message    string (without signal prefix)
    :signal     keyword}

   Returns the created turn or nil if dialogue not found."
  [dialogue-id {:keys [sender receiver message signal]}]
  (schema/add-turn! dialogue-id
                    {:sender sender
                     :receiver receiver
                     :message message
                     :signal signal}))

(defn- update-turn-task-id!
  "Update a turn with the resulting task-id after dispatch.
   NOTE: Currently a no-op as task-ref linking requires entity ID.
   TODO: Implement via DataScript transaction if needed."
  [_dialogue-id _turn-id _task-id]
  ;; Task linking happens at dispatch time via :task-ref in add-turn!
  nil)

;; =============================================================================
;; Dialogue Lifecycle
;; =============================================================================

(defn create-dialogue
  "Create a new dialogue session via schema's DataScript.

   Arguments:
   - participants: Vector of slave-ids joining the dialogue
   - topic: Description of what the dialogue is about

   Returns: dialogue-id

   Example:
   (create-dialogue {:participants [\"writer-123\" \"critic-456\"]
                     :topic \"Code review for auth module\"})"
  [{:keys [participants topic]}]
  {:pre [(vector? participants) (>= (count participants) 2)]}
  (let [{:keys [id]} (schema/create-dialogue!
                      {:participants participants
                       :name (or topic "Unspecified topic")
                       :config {:threshold 0.8 :timeout-ms 300000}})]
    ;; Emit event
    (ws/emit! :agora/created {:dialogue-id id
                              :participants participants
                              :topic topic})
    id))

(defn join-dialogue
  "Add a participant to an existing dialogue.

   Used for dynamic role assignment when lings recruit new voices.

   Returns: true if joined, false if dialogue not found"
  [dialogue-id slave-id]
  (if-let [_dialogue (schema/get-dialogue dialogue-id)]
    (let [c (schema/get-conn)
          db @c
          eid (:db/id (d/entity db [:agora.dialogue/id dialogue-id]))]
      (d/transact! c [{:db/id eid
                       :agora.dialogue/participants slave-id}])
      (log/info "Participant" slave-id "joined dialogue" dialogue-id)
      (ws/emit! :agora/participant-joined {:dialogue-id dialogue-id
                                           :slave-id slave-id})
      true)
    (do
      (log/warn "Cannot join - dialogue not found:" dialogue-id)
      false)))

(defn leave-dialogue
  "Remove a participant from a dialogue.

   Typically called during ling /wrap or session end.
   If participant was in active turn, marks as :defer.

   Returns: true if left, false if not found"
  [dialogue-id slave-id]
  (if-let [_dialogue (schema/get-dialogue dialogue-id)]
    (let [c (schema/get-conn)
          db @c
          eid (:db/id (d/entity db [:agora.dialogue/id dialogue-id]))]
      ;; Retract the participant
      (d/transact! c [[:db/retract eid :agora.dialogue/participants slave-id]])
      (log/info "Participant" slave-id "left dialogue" dialogue-id)
      (ws/emit! :agora/participant-left {:dialogue-id dialogue-id
                                         :slave-id slave-id})
      ;; Check if dialogue should end (< 2 participants)
      (let [updated (schema/get-dialogue dialogue-id)
            remaining (count (:participants updated))]
        (when (< remaining 2)
          (schema/update-dialogue-status! dialogue-id :aborted)
          (log/info "Dialogue" dialogue-id "ended - insufficient participants")))
      true)
    (do
      (log/warn "Cannot leave - dialogue not found:" dialogue-id)
      false)))

;; =============================================================================
;; Dialogue Queries
;; =============================================================================

(defn get-dialogue
  "Get dialogue by ID from schema's DataScript."
  [dialogue-id]
  (when-let [d (schema/get-dialogue dialogue-id)]
    ;; Adapt schema's shape to dialogue.clj's expected shape
    {:id (:id d)
     :participants (set (:participants d))
     :topic (:name d)
     :created-at (when-let [ts (:created d)] (java.util.Date. ts))
     :status (:status d)}))

(defn get-dialogue-turns
  "Get all turns in a dialogue from schema's DataScript."
  [dialogue-id]
  (->> (schema/get-turns dialogue-id)
       (map (fn [t]
              {:id (:id t)
               :turn-num (:turn-number t)
               :sender (:sender t)
               :receiver (:receiver t)
               :message (:message t)
               :signal (:signal t)
               :timestamp (when-let [ts (:timestamp t)] (java.util.Date. ts))
               :task-id (:task-ref t)}))))

(defn get-last-turn-for
  "Get the most recent turn addressed to a specific participant.
   Returns the last turn where this agent was the receiver."
  [dialogue-id slave-id]
  (->> (get-dialogue-turns dialogue-id)
       (filter #(= (:receiver %) slave-id))
       last))

(defn get-participants
  "Get current participants in a dialogue."
  [dialogue-id]
  (if-let [d (schema/get-dialogue dialogue-id)]
    (set (:participants d))
    #{}))

(defn dialogue-active?
  "Check if dialogue is still active."
  [dialogue-id]
  (= :active (:status (schema/get-dialogue dialogue-id))))

;; =============================================================================
;; Nash Equilibrium Detection (delegated to consensus namespace)
;; =============================================================================

(defn nash-equilibrium?
  "Check if dialogue has reached Nash equilibrium.

   Returns true when all participants have signaled :no-change or :approve
   in their most recent turn.

   NOTE: This is a basic implementation. The consensus namespace provides
   more sophisticated detection with thresholds and timeout handling."
  [dialogue-id]
  (let [participants (get-participants dialogue-id)
        last-turns (map #(get-last-turn-for dialogue-id %) participants)
        signals (map :signal (filter some? last-turns))]
    (and (= (count signals) (count participants))
         (every? equilibrium-signals signals))))

;; =============================================================================
;; Dialogue Dispatch
;; =============================================================================

(defn dialogue-dispatch
  "Dispatch within dialogue context.

   Records turn, emits event, checks Nash equilibrium, then delegates
   to underlying swarm-dispatch.

   Arguments:
   - dialogue-id: Conversation thread ID
   - from: Sender slave-id (defaults to env CLAUDE_SWARM_SLAVE_ID)
   - to: Receiver slave-id (required)
   - message: Message content, may include [SIGNAL: X] prefix
   - signal: Optional explicit signal (keyword or string), takes priority over message parsing

   Signal priority: explicit :signal param > parsed from message prefix > default :propose

   Returns: Result from swarm-dispatch with dialogue metadata

   Example:
   ;; With message prefix (backward compatible)
   (dialogue-dispatch
     {:dialogue-id \"dialogue-123\"
      :to \"critic-456\"
      :message \"[SIGNAL: propose] Here's iteration 2...\"})

   ;; With explicit signal (data-driven)
   (dialogue-dispatch
     {:dialogue-id \"dialogue-123\"
      :to \"critic-456\"
      :signal :approve
      :message \"LGTM, ship it!\"})"
  [{:keys [dialogue-id from to message timeout_ms files signal]}]
  {:pre [(some? dialogue-id) (some? to) (some? message)]}
  (let [sender-id (or from (System/getenv "CLAUDE_SWARM_SLAVE_ID") "unknown")
        ;; Signal priority: explicit param > parsed from message > natural language > default :propose
        explicit-signal (when signal
                          (let [s (if (keyword? signal) signal (keyword signal))]
                            (when (contains? signals s) s)))
        [parsed-signal cleaned-message detection-method] (if explicit-signal
                                                           ;; Skip parsing when explicit signal provided
                                                           [nil message :explicit]
                                                           (parse-signal message))
        final-signal (or explicit-signal parsed-signal :propose)
        final-detection (if explicit-signal :explicit detection-method)
        ;; Create turn data
        turn-data {:sender sender-id
                   :receiver to
                   :message cleaned-message
                   :signal final-signal}]

    ;; Validate dialogue exists and is active
    (when-not (dialogue-active? dialogue-id)
      (let [dialogue (schema/get-dialogue dialogue-id)
            status (:status dialogue)]
        (throw (ex-info (case status
                          :consensus "Dialogue already reached consensus"
                          :timeout "Dialogue timed out"
                          :aborted "Dialogue was aborted"
                          "Dialogue not active")
                        {:dialogue-id dialogue-id
                         :status status}))))

    ;; Record turn before dispatch
    (record-turn! dialogue-id turn-data)
    (let [turn (last (get-dialogue-turns dialogue-id))]

      ;; Emit turn event
      (ws/emit! :agora/turn {:dialogue-id dialogue-id
                             :turn-num (:turn-num turn)
                             :from sender-id
                             :to to
                             :signal final-signal})

      ;; Check for Nash equilibrium after recording turn
      (when (nash-equilibrium? dialogue-id)
        (schema/update-dialogue-status! dialogue-id :consensus)
        (log/info "Dialogue" dialogue-id "reached Nash equilibrium (CONSENSUS)")
        (ws/emit! :agora/consensus {:dialogue-id dialogue-id
                                    :turns (count (get-dialogue-turns dialogue-id))}))

      ;; Delegate to standard swarm dispatch
      (let [dispatch-result (dispatch/handle-swarm-dispatch
                             {:slave_id to
                              :prompt message
                              :timeout_ms timeout_ms
                              :files files})]

        ;; Emit turn-dispatched event for relay handler
        (ws/emit! :agora/turn-dispatched
                  {:dialogue-id dialogue-id
                   :from sender-id
                   :to to
                   :turn-num (:turn-num turn)
                   :signal final-signal
                   :message cleaned-message
                   :topic (:topic (get-dialogue dialogue-id))})

        ;; Update turn with task-id from result (if available)
        (when-let [task-id (rescue nil (some-> dispatch-result :result
                                     (json/read-str :key-fn keyword)
                                     :task-id))]
          (update-turn-task-id! dialogue-id (:id turn) task-id))

        ;; Return enriched result
        {:dialogue-id dialogue-id
         :turn (:turn-num turn)
         :signal final-signal
         :signal-detection final-detection
         :dispatch-result dispatch-result}))))

;; =============================================================================
;; Convenience Functions for Lings
;; =============================================================================

(defn propose
  "Send a proposal in a dialogue.
   Shorthand for dialogue-dispatch with :propose signal."
  [dialogue-id to message & {:keys [timeout_ms files]}]
  (dialogue-dispatch {:dialogue-id dialogue-id
                      :to to
                      :message (format-signal :propose message)
                      :timeout_ms timeout_ms
                      :files files}))

(defn approve
  "Send approval in a dialogue.
   Shorthand for dialogue-dispatch with :approve signal."
  [dialogue-id to message & {:keys [timeout_ms]}]
  (dialogue-dispatch {:dialogue-id dialogue-id
                      :to to
                      :message (format-signal :approve message)
                      :timeout_ms timeout_ms}))

(defn counter
  "Send counter-proposal in a dialogue.
   Shorthand for dialogue-dispatch with :counter signal."
  [dialogue-id to message & {:keys [timeout_ms files]}]
  (dialogue-dispatch {:dialogue-id dialogue-id
                      :to to
                      :message (format-signal :counter message)
                      :timeout_ms timeout_ms
                      :files files}))

(defn no-change
  "Signal no changes needed in a dialogue.
   Shorthand for dialogue-dispatch with :no-change signal."
  [dialogue-id to message & {:keys [timeout_ms]}]
  (dialogue-dispatch {:dialogue-id dialogue-id
                      :to to
                      :message (format-signal :no-change message)
                      :timeout_ms timeout_ms}))

;; =============================================================================
;; Testing/Debug Utilities
;; =============================================================================

(defn list-dialogues
  "List all dialogues with summary info from schema's DataScript."
  []
  (->> (schema/list-dialogues)
       (map (fn [d]
              {:id (:id d)
               :topic (:name d)
               :status (:status d)
               :created-at (when-let [ts (:created d)] (java.util.Date. ts))}))
       (sort-by :created-at)
       reverse))

(defn reset-dialogues!
  "Clear all dialogue state. For testing.
   Delegates to schema's reset-conn!"
  []
  (schema/reset-conn!)
  (log/info "Dialogue state reset (via schema)"))

(defn dialogue-summary
  "Get a human-readable summary of a dialogue.

   Returns {:id :topic :status :participants :turn-count :last-signal
   :consensus?}, or nil when the dialogue does not exist."
  [dialogue-id]
  (when-let [d (get-dialogue dialogue-id)]
    (let [turns (get-dialogue-turns dialogue-id)]
      {:id dialogue-id
       :topic (:topic d)
       :status (:status d)
       :participants (vec (:participants d))
       :turn-count (count turns)
       :last-signal (some-> (last turns) :signal)
       :consensus? (= :consensus (:status d))})))
