(ns hive-mcp.agora.consensus
  "Agora Consensus: Nash Equilibrium detection for multi-ling dialogues.

   Core principle: A dialogue reaches **consensus** when it achieves
   **Nash equilibrium** - no participant would unilaterally change their
   position given the others' positions.

   DDD: Pure domain functions, no side effects.

   Depends on: hive-mcp.agora.schema (dialogue state queries)"
  (:require [hive-mcp.agora.schema :as schema]
            [hive-mcp.agora.signal :as signal]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Turn Signal Constants - Delegated to signal.clj (Single Source of Truth)
;; =============================================================================

(def equilibrium-signals
  "Signals that indicate participant has reached equilibrium state.
   Delegates to signal.clj."
  signal/equilibrium-signals)

(def reset-signals
  "Signals that reset equilibrium - participant proposes a change.
   Delegates to signal.clj."
  signal/disruption-signals)

(def neutral-signals
  "Signals that neither block nor contribute to equilibrium.
   Delegates to signal.clj."
  signal/neutral-signals)

(def all-valid-signals
  "All valid turn signals. Delegates to signal.clj."
  signal/all-valid-signals)

;; =============================================================================
;; Default Configuration
;; =============================================================================

(def default-config
  "Default consensus detection configuration."
  {:threshold 1.0            ; 100% agreement for simple dialogues
   :minimum-turns 2          ; Require at least 2 turns before consensus valid
   :stuck-threshold 10       ; Turns without progress = stuck
   :timeout-turns 50         ; Max turns before forced timeout
   :require-all-active true  ; All participants must have made a turn
   })

;; =============================================================================
;; Schema Adapters (wrap schema API for consensus-specific queries)
;; =============================================================================

(defn get-participants
  "Get all participants in a dialogue.
   Wraps schema/get-dialogue to extract participants."
  [dialogue-id]
  (or (:participants (schema/get-dialogue dialogue-id)) []))

(defn get-dialogue-config
  "Get configuration for a dialogue, with defaults.
   Wraps schema/get-dialogue to extract config."
  [dialogue-id]
  (or (:config (schema/get-dialogue dialogue-id)) default-config))

;; =============================================================================
;; Participant ID Normalization
;; =============================================================================

(defn extract-short-name
  "Extract short name from slave-id.

   Examples:
   'swarm-agora-preset-docs-skeptic-1769195802' -> 'skeptic'
   'ling-agora-critic-456' -> 'critic'
   'writer-123' -> 'writer'"
  [slave-id]
  (when slave-id
    (or
     ;; Pattern: swarm-agora-*-<role>-<timestamp>
     (second (re-find #"(?:swarm-|ling-)?agora-[^-]+-([^-]+)-\d+" slave-id))
     ;; Pattern: swarm-agora-preset-*-<role>-<timestamp>
     (second (re-find #"(?:swarm-|ling-)?agora-preset-[^-]+-([^-]+)-\d+" slave-id))
     ;; Pattern: <role>-<timestamp>
     (second (re-find #"^([^-]+)-\d+$" slave-id))
     ;; Fallback: return as-is
     slave-id)))

(defn normalize-participant-match
  "Fuzzy match sender to participant using short name extraction.

   Returns true if:
   - Exact match
   - Short names match (e.g., 'skeptic' == 'swarm-agora-skeptic-123')

   Logs a warning when fuzzy matching occurs to help debug."
  [sender participant]
  (or (= sender participant)
      (let [sender-short (extract-short-name sender)
            participant-short (extract-short-name participant)
            fuzzy-match? (and sender-short
                              participant-short
                              (= sender-short participant-short))]
        (when fuzzy-match?
          ;; Log when fuzzy matching is used (helps debug issues)
          (when-not (= sender participant)
            (log/debug "Fuzzy participant match:" sender "~" participant
                       "(short:" sender-short ")")))
        fuzzy-match?)))

(defn last-turn-for
  "Get the most recent turn for a specific participant.
   Uses fuzzy matching to handle participant ID variations."
  [dialogue-id participant]
  (let [turns (schema/get-turns dialogue-id)
        participant-turns (filter #(normalize-participant-match
                                    (:sender %) participant)
                                  turns)]
    (last (sort-by :turn-number participant-turns))))

;; =============================================================================
;; Nash Equilibrium Detection
;; =============================================================================

(defn get-active-proposal
  "Find the most recent :propose or :counter turn in the dialogue.

   This represents the 'current proposal' that needs approval.

   Returns: {:turn-number N :sender participant-id :message string} or nil"
  [dialogue-id]
  (let [turns (schema/get-turns dialogue-id)
        proposal-turns (filter #(reset-signals (:signal %)) turns)]
    (last (sort-by :turn-number proposal-turns))))

(defn approvals-aligned?
  "Check if all equilibrium signals approve the SAME proposal.

   Key insight: An approval only counts toward consensus if:
   1. It comes AFTER the most recent proposal
   2. It's from a DIFFERENT participant than the proposer

   This prevents false consensus where each participant 'approves' their own position.

   dialogue-id: identifier for the dialogue

   Returns: boolean"
  [dialogue-id]
  (let [active-proposal (get-active-proposal dialogue-id)
        participants (get-participants dialogue-id)
        last-turns (map #(last-turn-for dialogue-id %) participants)]
    (if-not active-proposal
      ;; No proposal yet - can't have alignment
      false
      (let [proposal-turn (:turn-number active-proposal)
            proposer (:sender active-proposal)
            ;; Get all equilibrium turns that came AFTER the proposal
            post-proposal-equilibrium
            (->> last-turns
                 (filter some?)
                 (filter #(equilibrium-signals (:signal %)))
                 (filter #(> (:turn-number %) proposal-turn)))]
        ;; Consensus requires:
        ;; 1. At least one approval from someone OTHER than proposer
        ;; 2. All participants have equilibrium signals
        ;; 3. No new proposals after the first approval
        (and
         ;; Someone other than proposer approved
         (some #(not (normalize-participant-match (:sender %) proposer))
               post-proposal-equilibrium)
         ;; All last turns are equilibrium signals
         (every? #(and (some? %)
                       (equilibrium-signals (:signal %)))
                 last-turns))))))

(defn nash-equilibrium?
  "Check if dialogue has reached true Nash equilibrium.

   True consensus requires:
   1. All participants have equilibrium signals (:approve/:no-change/:lgtm)
   2. Approvals are ALIGNED - they approve the same proposal, not self-approval

   The alignment check prevents false consensus where each participant
   'approves' their own position without actually agreeing.

   dialogue-id: identifier for the dialogue to check

   Returns: boolean — false (never nil) for an unknown or empty dialogue"
  [dialogue-id]
  (let [participants (get-participants dialogue-id)
        last-turns (map #(last-turn-for dialogue-id %) participants)
        signals (map :signal last-turns)]
    (boolean
     (and (seq participants)                          ; Has participants
          (every? some? last-turns)                   ; All have made a turn
          (every? equilibrium-signals signals)        ; All in equilibrium state
          (approvals-aligned? dialogue-id)))))         ; Approvals are for same proposal

;; =============================================================================
;; Threshold-based Consensus (N-party)
;; =============================================================================

(defn count-approvals
  "Count participants whose last turn signal is an equilibrium signal.

   dialogue-id: identifier for the dialogue

   Returns: integer count"
  [dialogue-id]
  (let [participants (get-participants dialogue-id)
        last-turns (map #(last-turn-for dialogue-id %) participants)
        signals (map :signal last-turns)]
    (count (filter equilibrium-signals signals))))

(defn count-participants
  "Count total active participants in a dialogue.

   dialogue-id: identifier for the dialogue

   Returns: integer count"
  [dialogue-id]
  (count (get-participants dialogue-id)))

(defn approval-ratio
  "Calculate the ratio of approving participants to total participants.

   dialogue-id: identifier for the dialogue

   Returns: float between 0.0 and 1.0"
  [dialogue-id]
  (let [total (count-participants dialogue-id)]
    (if (zero? total)
      0.0
      (/ (count-approvals dialogue-id) (float total)))))

(defn threshold-consensus?
  "For N-party dialogues with configurable threshold.

   Returns true when the approval ratio meets or exceeds the configured
   threshold (default 1.0 = unanimous).

   dialogue-id: identifier for the dialogue

   Returns: boolean"
  [dialogue-id]
  (let [config (or (get-dialogue-config dialogue-id) default-config)
        threshold (get config :threshold 1.0)]
    (>= (approval-ratio dialogue-id) threshold)))

;; =============================================================================
;; Consensus State Check
;; =============================================================================

(defn turn-count
  "Get total number of turns in a dialogue."
  [dialogue-id]
  (count (schema/get-turns dialogue-id)))

(defn turns-since-last-proposal
  "Count turns since last :propose or :counter signal."
  [dialogue-id]
  (let [turns (reverse (schema/get-turns dialogue-id))]
    (count (take-while #(not (reset-signals (:signal %))) turns))))

(defn check-consensus
  "Comprehensive consensus check for a dialogue.

   Returns one of:
   - :consensus     - Dialogue has reached Nash equilibrium or threshold
   - :continue      - Dialogue should continue (active discussion)
   - :timeout       - Dialogue has exceeded maximum turns
   - :stuck         - Dialogue appears stuck (no progress)
   - :insufficient  - Not enough turns yet for valid consensus

   dialogue-id: identifier for the dialogue

   Returns: keyword status"
  [dialogue-id]
  ;; Merge with defaults to ensure all required keys exist (fixes NPE on nil comparisons)
  (let [config (merge default-config (get-dialogue-config dialogue-id))
        {:keys [minimum-turns stuck-threshold timeout-turns]} config
        total-turns (turn-count dialogue-id)
        turns-no-change (turns-since-last-proposal dialogue-id)]
    (cond
      ;; Check timeout first
      (>= total-turns timeout-turns)
      :timeout

      ;; Check if stuck (many turns with no proposal)
      (and (>= turns-no-change stuck-threshold)
           (not (threshold-consensus? dialogue-id)))
      :stuck

      ;; Check if enough turns for valid consensus
      (< total-turns minimum-turns)
      :insufficient

      ;; Check for Nash equilibrium (strict) or threshold consensus
      (or (nash-equilibrium? dialogue-id)
          (threshold-consensus? dialogue-id))
      :consensus

      ;; Otherwise, dialogue continues
      :else
      :continue)))

;; =============================================================================
;; Stuck Detection
;; =============================================================================

(defn stuck?
  "Detect infinite loops - N turns without progress.

   A dialogue is stuck when:
   - More than stuck-threshold turns have passed without a proposal
   - But consensus has NOT been reached (people are just deferring/silent)

   dialogue-id: identifier for the dialogue

   Returns: boolean"
  [dialogue-id]
  ;; Merge with defaults to ensure stuck-threshold exists
  (let [config (merge default-config (get-dialogue-config dialogue-id))
        {:keys [stuck-threshold]} config
        turns-no-change (turns-since-last-proposal dialogue-id)]
    (and (>= turns-no-change stuck-threshold)
         (not (threshold-consensus? dialogue-id)))))

(defn calculate-progress-score
  "Calculate a progress score for the dialogue.

   Higher scores indicate more active, productive discussion.

   Returns: {:score float
             :factors {:approval-momentum float
                       :participation float
                       :recent-activity float}}"
  [dialogue-id]
  (let [total-turns (turn-count dialogue-id)
        approval-pct (approval-ratio dialogue-id)
        participant-count (count-participants dialogue-id)
        turns-active (- total-turns (turns-since-last-proposal dialogue-id))

        ;; Momentum: are we getting closer to consensus?
        approval-momentum (* approval-pct 40.0)

        ;; Participation: is everyone engaged?
        participation (if (> participant-count 0)
                        (* (/ turns-active (max 1 total-turns)) 30.0)
                        0.0)

        ;; Recent activity: has there been recent proposals?
        recent-activity (if (< (turns-since-last-proposal dialogue-id) 5)
                          30.0
                          (max 0.0 (- 30.0 (* 3 (turns-since-last-proposal dialogue-id)))))]
    {:score (+ approval-momentum participation recent-activity)
     :factors {:approval-momentum approval-momentum
               :participation participation
               :recent-activity recent-activity}}))

;; =============================================================================
;; Mediator Recruitment
;; =============================================================================

(defn deadlocked?
  "Check if dialogue is in a deadlock state.

   Deadlock: mutual :counter signals between the same participants
   without any :approve/:no-change in between.

   dialogue-id: identifier for the dialogue

   Returns: boolean"
  [dialogue-id]
  (let [turns (schema/get-turns dialogue-id)
        recent-turns (take-last 6 turns)  ; Look at last 6 turns
        recent-signals (map :signal recent-turns)]
    (and (>= (count recent-turns) 4)                        ; Need some history
         (every? #{:counter :propose} recent-signals)       ; All contentious
         (> (count (filter #{:counter} recent-signals)) 2)))) ; Multiple counters

(defn should-recruit-mediator?
  "Determine when to bring in a fresh perspective.

   Recruitment triggers:
   1. Deadlock detected (mutual :counter without progress)
   2. Stuck for too long (threshold exceeded)
   3. Progress score critically low

   dialogue-id: identifier for the dialogue

   Returns: {:recruit? boolean
             :reason keyword or nil
             :urgency :high/:medium/:low or nil}"
  [dialogue-id]
  (let [{:keys [score]} (calculate-progress-score dialogue-id)
        is-stuck (stuck? dialogue-id)
        is-deadlocked (deadlocked? dialogue-id)]
    (cond
      is-deadlocked
      {:recruit? true
       :reason :deadlock
       :urgency :high}

      is-stuck
      {:recruit? true
       :reason :stuck
       :urgency :medium}

      (< score 20.0)
      {:recruit? true
       :reason :low-progress
       :urgency :low}

      :else
      {:recruit? false
       :reason nil
       :urgency nil})))

;; =============================================================================
;; Consensus Result
;; =============================================================================

(defn consensus-result
  "Get comprehensive consensus analysis for a dialogue.

   dialogue-id: identifier for the dialogue

   Returns: {:status keyword
             :nash-equilibrium? boolean
             :approval-ratio float
             :participants int
             :turn-count int
             :progress-score float
             :mediator-needed? boolean
             :mediator-reason keyword or nil}"
  [dialogue-id]
  (let [status (check-consensus dialogue-id)
        {:keys [recruit? reason]} (should-recruit-mediator? dialogue-id)
        {:keys [score]} (calculate-progress-score dialogue-id)]
    {:status status
     :nash-equilibrium? (nash-equilibrium? dialogue-id)
     :approval-ratio (approval-ratio dialogue-id)
     :participants (count-participants dialogue-id)
     :turn-count (turn-count dialogue-id)
     :progress-score score
     :mediator-needed? recruit?
     :mediator-reason reason}))

;; =============================================================================
;; Signal Parsing - Delegated to signal.clj
;; =============================================================================

(defn parse-signal
  "Extract Nash signal from a ling dispatch message.
   Delegates to signal/parse-signal.

   message: string from swarm_dispatch prompt

   Returns: keyword signal type or :propose (default)"
  [message]
  (let [result (signal/parse-signal (or message ""))]
    (if (:error result)
      :propose
      (:type result))))

(defn signal->equilibrium-contribution
  "Determine if a signal contributes to equilibrium.
   Delegates to signal/equilibrium-contribution.

   signal: keyword

   Returns: :positive, :negative, or :neutral"
  [signal-kw]
  (signal/equilibrium-contribution signal-kw))
