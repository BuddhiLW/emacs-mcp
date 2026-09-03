(ns hive-mcp.agora.consensus-test
  "TDD tests for agora/consensus.clj - Nash equilibrium detection.

   Tests the consensus detection logic using mocked schema functions.
   The schema namespace is created by a separate ling."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [hive-mcp.agora.consensus :as consensus]
            [hive-mcp.agora.schema]))

;; =============================================================================
;; Test Fixtures - Mock Schema Functions
;; =============================================================================

(def mock-dialogues
  "Mock dialogue data for testing.
   Uses same key names as schema/get-dialogue and schema/get-turns return."
  {:dialogue-1
   {:id "dialogue-1"
    :participants ["writer" "critic"]
    :config consensus/default-config
    ;; An ACTIVE dialogue: the critic's last signal is a reset signal, so not
    ;; every participant sits in equilibrium. (`:no-change` here would make
    ;; this a consensus, since no-change + approve are both equilibrium.)
    :turns [{:sender "writer" :receiver "critic" :signal :propose :turn-number 1}
            {:sender "critic" :receiver "writer" :signal :counter :turn-number 2}
            {:sender "writer" :receiver "critic" :signal :approve :turn-number 3}
            {:sender "critic" :receiver "writer" :signal :counter :turn-number 4}
            {:sender "writer" :receiver "critic" :signal :approve :turn-number 5}]}

   :dialogue-equilibrium
   {:id "dialogue-equilibrium"
    :participants ["alice" "bob"]
    :config consensus/default-config
    :turns [{:sender "alice" :receiver "bob" :signal :propose :turn-number 1}
            {:sender "bob" :receiver "alice" :signal :no-change :turn-number 2}
            {:sender "alice" :receiver "bob" :signal :approve :turn-number 3}]}

   :dialogue-stuck
   {:id "dialogue-stuck"
    :participants ["p1" "p2"]
    :config (assoc consensus/default-config :stuck-threshold 3)
    :turns (vec (map-indexed
                 (fn [i _] {:sender "p1" :receiver "p2" :signal :defer :turn-number (inc i)})
                 (range 12)))}

   :dialogue-deadlock
   {:id "dialogue-deadlock"
    :participants ["a" "b"]
    :config consensus/default-config
    :turns [{:sender "a" :receiver "b" :signal :propose :turn-number 1}
            {:sender "b" :receiver "a" :signal :counter :turn-number 2}
            {:sender "a" :receiver "b" :signal :counter :turn-number 3}
            {:sender "b" :receiver "a" :signal :counter :turn-number 4}
            {:sender "a" :receiver "b" :signal :counter :turn-number 5}
            {:sender "b" :receiver "a" :signal :counter :turn-number 6}]}

   :dialogue-threshold
   {:id "dialogue-threshold"
    :participants ["r1" "r2" "r3" "r4" "r5"]
    :config {:threshold 0.8 :minimum-turns 2
             :stuck-threshold 10 :timeout-turns 50}
    :turns [{:sender "r1" :signal :approve :turn-number 1}
            {:sender "r2" :signal :approve :turn-number 2}
            {:sender "r3" :signal :approve :turn-number 3}
            {:sender "r4" :signal :approve :turn-number 4}
            {:sender "r5" :signal :counter :turn-number 5}]}

   :dialogue-insufficient
   {:id "dialogue-insufficient"
    :participants ["x" "y"]
    :config consensus/default-config
    :turns [{:sender "x" :receiver "y" :signal :approve :turn-number 1}]}

   :dialogue-empty
   {:id "dialogue-empty"
    :participants []
    :config consensus/default-config
    :turns []}})

(defn mock-get-dialogue
  "Mock implementation of schema/get-dialogue.
   Returns dialogue with :id, :participants, :config, etc."
  [dialogue-id]
  (when-let [dialogue (get mock-dialogues dialogue-id)]
    (select-keys dialogue [:id :participants :config :status :created :name])))

(defn mock-get-turns
  "Mock implementation of schema/get-turns.
   Returns turns with :sender, :receiver, :signal, :turn-number."
  [dialogue-id]
  (get-in mock-dialogues [dialogue-id :turns] []))

(defn with-mocked-schema [f]
  (with-redefs [hive-mcp.agora.schema/get-dialogue mock-get-dialogue
                hive-mcp.agora.schema/get-turns mock-get-turns]
    (f)))

(use-fixtures :each with-mocked-schema)

;; =============================================================================
;; Signal Constants Tests
;; =============================================================================

(deftest signal-sets-test
  (testing "equilibrium signals are defined"
    (is (set? consensus/equilibrium-signals))
    (is (contains? consensus/equilibrium-signals :no-change))
    (is (contains? consensus/equilibrium-signals :approve))
    (is (contains? consensus/equilibrium-signals :lgtm)))

  (testing "reset signals are defined"
    (is (set? consensus/reset-signals))
    (is (contains? consensus/reset-signals :propose))
    (is (contains? consensus/reset-signals :counter)))

  (testing "neutral signals are defined"
    (is (set? consensus/neutral-signals))
    (is (contains? consensus/neutral-signals :defer)))

  (testing "all valid signals is union of all"
    (is (set? consensus/all-valid-signals))
    (is (every? consensus/all-valid-signals [:no-change :approve :lgtm
                                             :propose :counter :defer]))))

;; =============================================================================
;; Nash Equilibrium Detection Tests
;; =============================================================================

(deftest nash-equilibrium-test
  (testing "returns true when all participants signaled equilibrium"
    (is (true? (consensus/nash-equilibrium? :dialogue-equilibrium))))

  (testing "returns false when not all in equilibrium"
    (is (false? (consensus/nash-equilibrium? :dialogue-1))))

  (testing "returns false for empty dialogue"
    (is (false? (consensus/nash-equilibrium? :dialogue-empty))))

  (testing "returns false when stuck but not approving"
    (is (false? (consensus/nash-equilibrium? :dialogue-stuck)))))

;; =============================================================================
;; Threshold Consensus Tests
;; =============================================================================

(deftest count-approvals-test
  (testing "counts participants with equilibrium signals"
    (is (= 2 (consensus/count-approvals :dialogue-equilibrium)))
    (is (= 4 (consensus/count-approvals :dialogue-threshold)))))

(deftest count-participants-test
  (testing "counts total participants"
    (is (= 2 (consensus/count-participants :dialogue-1)))
    (is (= 5 (consensus/count-participants :dialogue-threshold)))
    (is (= 0 (consensus/count-participants :dialogue-empty)))))

(deftest approval-ratio-test
  (testing "calculates ratio correctly"
    (is (= 1.0 (consensus/approval-ratio :dialogue-equilibrium)))
    (is (= 0.8 (consensus/approval-ratio :dialogue-threshold))))

  (testing "returns 0.0 for empty dialogue"
    (is (= 0.0 (consensus/approval-ratio :dialogue-empty)))))

(deftest threshold-consensus-test
  (testing "returns true when threshold met"
    ;; dialogue-threshold has 4/5 = 0.8, threshold is 0.8
    (is (true? (consensus/threshold-consensus? :dialogue-threshold))))

  (testing "returns true when unanimous"
    (is (true? (consensus/threshold-consensus? :dialogue-equilibrium))))

  (testing "returns false when below threshold"
    (is (false? (consensus/threshold-consensus? :dialogue-deadlock)))))

;; =============================================================================
;; Check Consensus Tests
;; =============================================================================

(deftest check-consensus-test
  (testing "returns :consensus when Nash equilibrium reached"
    (is (= :consensus (consensus/check-consensus :dialogue-equilibrium))))

  (testing "returns :consensus when threshold met"
    (is (= :consensus (consensus/check-consensus :dialogue-threshold))))

  (testing "returns :continue for active dialogue"
    (is (= :continue (consensus/check-consensus :dialogue-1))))

  (testing "returns :insufficient for too few turns"
    (is (= :insufficient (consensus/check-consensus :dialogue-insufficient))))

  (testing "returns :stuck when no progress"
    (is (= :stuck (consensus/check-consensus :dialogue-stuck)))))

;; =============================================================================
;; Stuck Detection Tests
;; =============================================================================

(deftest stuck-test
  (testing "detects stuck dialogue"
    (is (true? (consensus/stuck? :dialogue-stuck))))

  (testing "not stuck when active"
    (is (false? (consensus/stuck? :dialogue-1))))

  (testing "not stuck when in equilibrium"
    (is (false? (consensus/stuck? :dialogue-equilibrium)))))

(deftest turns-since-last-proposal-test
  (testing "counts turns correctly"
    (is (= 12 (consensus/turns-since-last-proposal :dialogue-stuck)))
    (is (= 2 (consensus/turns-since-last-proposal :dialogue-equilibrium)))))

;; =============================================================================
;; Progress Score Tests
;; =============================================================================

(deftest calculate-progress-score-test
  (testing "returns score map with factors"
    (let [result (consensus/calculate-progress-score :dialogue-1)]
      (is (map? result))
      (is (contains? result :score))
      (is (contains? result :factors))
      (is (number? (:score result)))))

  (testing "equilibrium dialogue has high approval momentum"
    (let [result (consensus/calculate-progress-score :dialogue-equilibrium)]
      (is (>= (get-in result [:factors :approval-momentum]) 30.0))))

  (testing "stuck dialogue has low recent activity"
    (let [result (consensus/calculate-progress-score :dialogue-stuck)]
      (is (< (get-in result [:factors :recent-activity]) 10.0)))))

;; =============================================================================
;; Mediator Recruitment Tests
;; =============================================================================

(deftest deadlocked-test
  (testing "detects deadlock pattern"
    (is (true? (consensus/deadlocked? :dialogue-deadlock))))

  (testing "not deadlocked when normal"
    (is (false? (consensus/deadlocked? :dialogue-1)))))

(deftest should-recruit-mediator-test
  (testing "recommends mediator for deadlock"
    (let [result (consensus/should-recruit-mediator? :dialogue-deadlock)]
      (is (true? (:recruit? result)))
      (is (= :deadlock (:reason result)))
      (is (= :high (:urgency result)))))

  (testing "recommends mediator for stuck"
    (let [result (consensus/should-recruit-mediator? :dialogue-stuck)]
      (is (true? (:recruit? result)))
      (is (= :stuck (:reason result)))))

  (testing "no mediator for healthy dialogue"
    (let [result (consensus/should-recruit-mediator? :dialogue-equilibrium)]
      (is (false? (:recruit? result)))))

  (testing "result has expected keys"
    (let [result (consensus/should-recruit-mediator? :dialogue-1)]
      (is (contains? result :recruit?))
      (is (contains? result :reason))
      (is (contains? result :urgency)))))

;; =============================================================================
;; Consensus Result Tests
;; =============================================================================

(deftest consensus-result-test
  (testing "returns comprehensive analysis"
    (let [result (consensus/consensus-result :dialogue-equilibrium)]
      (is (map? result))
      (is (= :consensus (:status result)))
      (is (true? (:nash-equilibrium? result)))
      (is (= 1.0 (:approval-ratio result)))
      (is (= 2 (:participants result)))
      (is (number? (:progress-score result)))
      (is (false? (:mediator-needed? result)))))

  (testing "deadlock result shows mediator needed"
    (let [result (consensus/consensus-result :dialogue-deadlock)]
      (is (true? (:mediator-needed? result)))
      (is (= :deadlock (:mediator-reason result))))))

;; =============================================================================
;; Signal Parsing Tests
;; =============================================================================

(deftest parse-signal-test
  (testing "parses valid signals from message"
    (is (= :no-change (consensus/parse-signal "[SIGNAL: no-change] LGTM")))
    (is (= :approve (consensus/parse-signal "[SIGNAL: approve] Looks good")))
    (is (= :counter (consensus/parse-signal "[SIGNAL: counter] I disagree")))
    (is (= :propose (consensus/parse-signal "[SIGNAL: propose] New idea")))
    (is (= :lgtm (consensus/parse-signal "[SIGNAL: lgtm] Ship it")))
    (is (= :defer (consensus/parse-signal "[SIGNAL: defer] No opinion"))))

  (testing "handles case insensitivity"
    (is (= :no-change (consensus/parse-signal "[SIGNAL: NO-CHANGE] LGTM")))
    (is (= :approve (consensus/parse-signal "[SIGNAL: APPROVE] ok"))))

  (testing "defaults to :propose for missing signal"
    (is (= :propose (consensus/parse-signal "Regular message without signal")))
    (is (= :propose (consensus/parse-signal "")))
    (is (= :propose (consensus/parse-signal nil))))

  (testing "defaults to :propose for invalid signal"
    (is (= :propose (consensus/parse-signal "[SIGNAL: invalid-stuff] text")))))

(deftest signal->equilibrium-contribution-test
  (testing "equilibrium signals are positive"
    (is (= :positive (consensus/signal->equilibrium-contribution :no-change)))
    (is (= :positive (consensus/signal->equilibrium-contribution :approve)))
    (is (= :positive (consensus/signal->equilibrium-contribution :lgtm))))

  (testing "reset signals are negative"
    (is (= :negative (consensus/signal->equilibrium-contribution :propose)))
    (is (= :negative (consensus/signal->equilibrium-contribution :counter))))

  (testing "neutral signals are neutral"
    (is (= :neutral (consensus/signal->equilibrium-contribution :defer))))

  (testing "unknown signals are negative"
    (is (= :negative (consensus/signal->equilibrium-contribution :unknown)))))

;; =============================================================================
;; Participant ID Normalization Tests
;; =============================================================================

(deftest extract-short-name-test
  (testing "extracts role from swarm-agora pattern"
    (is (= "skeptic" (consensus/extract-short-name "swarm-agora-preset-docs-skeptic-1769195802")))
    (is (= "advocate" (consensus/extract-short-name "swarm-agora-test-advocate-9876543210")))
    (is (= "critic" (consensus/extract-short-name "ling-agora-review-critic-1234567890"))))

  (testing "extracts role from simple pattern"
    (is (= "writer" (consensus/extract-short-name "writer-123")))
    (is (= "reviewer" (consensus/extract-short-name "reviewer-456789"))))

  (testing "returns full id when no pattern matches"
    (is (= "justanid" (consensus/extract-short-name "justanid")))
    (is (= "complex-name-without-timestamp" (consensus/extract-short-name "complex-name-without-timestamp"))))

  (testing "handles nil gracefully"
    (is (nil? (consensus/extract-short-name nil)))))

(deftest normalize-participant-match-test
  (testing "exact match returns true"
    (is (true? (consensus/normalize-participant-match "writer-123" "writer-123"))))

  (testing "fuzzy match with same short name returns true"
    (is (true? (consensus/normalize-participant-match
                "skeptic-123"
                "swarm-agora-preset-docs-skeptic-456")))
    (is (true? (consensus/normalize-participant-match
                "swarm-agora-preset-docs-skeptic-1769195802"
                "skeptic-999"))))

  (testing "different short names return false"
    (is (false? (consensus/normalize-participant-match "writer-123" "critic-456")))
    (is (false? (consensus/normalize-participant-match
                 "swarm-agora-skeptic-111"
                 "swarm-agora-advocate-222")))))

;; Mock data with fuzzy participant IDs
(def mock-dialogues-fuzzy
  {:dialogue-fuzzy
   {:id "dialogue-fuzzy"
    :participants ["skeptic" "swarm-agora-preset-docs-advocate-123"]
    :config consensus/default-config
    :turns [{:sender "swarm-agora-preset-docs-skeptic-999" :receiver "advocate" :signal :propose :turn-number 1}
            {:sender "advocate-555" :receiver "skeptic" :signal :approve :turn-number 2}]}})

(deftest last-turn-for-fuzzy-matching-test
  (testing "last-turn-for uses fuzzy matching"
    ;; Override mock for this specific test
    (with-redefs [hive-mcp.agora.schema/get-dialogue
                  (fn [id] (get mock-dialogues-fuzzy id))
                  hive-mcp.agora.schema/get-turns
                  (fn [id] (get-in mock-dialogues-fuzzy [id :turns] []))]
      ;; Should find turn from "swarm-agora-preset-docs-skeptic-999" when looking for "skeptic"
      (let [turn (consensus/last-turn-for :dialogue-fuzzy "skeptic")]
        (is (some? turn))
        (is (= :propose (:signal turn)))
        (is (= 1 (:turn-number turn))))

      ;; Should find turn from "advocate-555" when looking for full participant ID
      (let [turn (consensus/last-turn-for :dialogue-fuzzy "swarm-agora-preset-docs-advocate-123")]
        (is (some? turn))
        (is (= :approve (:signal turn)))
        (is (= 2 (:turn-number turn)))))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest edge-cases-test
  (testing "handles non-existent dialogue gracefully"
    ;; These should return safe defaults, not throw
    (is (= 0 (consensus/count-participants :nonexistent)))
    (is (= 0.0 (consensus/approval-ratio :nonexistent)))
    (is (false? (consensus/nash-equilibrium? :nonexistent))))

  (testing "signal pattern handles edge cases"
    ;; Signal at wrong position
    (is (= :propose (consensus/parse-signal "text [SIGNAL: approve] more")))
    ;; Multiple signals - takes first
    (is (= :approve (consensus/parse-signal "[SIGNAL: approve] [SIGNAL: counter]")))
    ;; Whitespace variations
    (is (= :approve (consensus/parse-signal "[SIGNAL:approve] no space")))
    (is (= :approve (consensus/parse-signal "[SIGNAL:  approve] extra space")))))
