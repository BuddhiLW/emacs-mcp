(ns hive-mcp.agora.dialogue-test
  "Tests for Agora dialogue dispatch wrapper.

   Covers:
   - Signal parsing from [SIGNAL: X] prefix (data-driven via signal.clj)
   - Dialogue lifecycle (create, join, leave)
   - Turn recording and querying
   - Nash equilibrium detection
   - Dialogue dispatch wrapper"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.agora.dialogue :as dialogue]
            [hive-mcp.agora.signal :as signal]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(defn reset-dialogues-fixture
  "Reset dialogue state before each test."
  [f]
  (dialogue/reset-dialogues!)
  (f))

(use-fixtures :each reset-dialogues-fixture)

;;; =============================================================================
;;; Signal Parsing Tests
;;; =============================================================================

(deftest parse-signal-with-prefix-test
  (testing "parse-signal extracts signal from [SIGNAL: X] prefix"
    (is (= [:propose "Here's my change" :prefix]
           (dialogue/parse-signal "[SIGNAL: propose] Here's my change")))
    (is (= [:approve "LGTM" :prefix]
           (dialogue/parse-signal "[SIGNAL: approve] LGTM")))
    (is (= [:no-change "Nothing to add" :prefix]
           (dialogue/parse-signal "[SIGNAL: no-change] Nothing to add")))
    (is (= [:counter "Alternative approach" :prefix]
           (dialogue/parse-signal "[SIGNAL: counter] Alternative approach")))
    (is (= [:defer "I'll let others decide" :prefix]
           (dialogue/parse-signal "[SIGNAL: defer] I'll let others decide")))))

(deftest parse-signal-case-insensitive-test
  (testing "parse-signal is case-insensitive"
    (is (= [:propose "test" :prefix]
           (dialogue/parse-signal "[SIGNAL: PROPOSE] test")))
    (is (= [:approve "test" :prefix]
           (dialogue/parse-signal "[SIGNAL: Approve] test")))
    (is (= [:no-change "test" :prefix]
           (dialogue/parse-signal "[SIGNAL: NO-CHANGE] test")))))

(deftest parse-signal-no-prefix-test
  (testing "parse-signal defaults to :propose when no prefix"
    (is (= [:propose "Just a regular message" :default]
           (dialogue/parse-signal "Just a regular message")))
    (is (= [:propose "" :default]
           (dialogue/parse-signal "")))))

(deftest parse-signal-invalid-signal-test
  (testing "parse-signal defaults to :propose for unknown signals"
    ;; Unknown signal should fall through with warning, keep original message
    (let [[signal message detection] (dialogue/parse-signal "[SIGNAL: unknown] text")]
      (is (= :propose signal))
      (is (= "[SIGNAL: unknown] text" message))
      (is (= :default detection)))))

(deftest format-signal-test
  (testing "format-signal creates [SIGNAL: X] prefix"
    (is (= "[SIGNAL: propose] My proposal"
           (dialogue/format-signal :propose "My proposal")))
    (is (= "[SIGNAL: approve] LGTM"
           (dialogue/format-signal :approve "LGTM")))))

;;; =============================================================================
;;; Dialogue Lifecycle Tests
;;; =============================================================================

(deftest create-dialogue-basic-test
  (testing "create-dialogue creates dialogue with participants"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["writer-123" "critic-456"]
                        :topic "Code review"})]
      (is (string? dialogue-id))
      (is (seq dialogue-id))
      (let [d (dialogue/get-dialogue dialogue-id)]
        (is (= #{"writer-123" "critic-456"} (:participants d)))
        (is (= "Code review" (:topic d)))
        (is (= :active (:status d)))
        (is (empty? (dialogue/get-dialogue-turns dialogue-id)))))))

(deftest create-dialogue-minimum-participants-test
  (testing "create-dialogue requires at least 2 participants"
    (is (thrown? AssertionError
                 (dialogue/create-dialogue {:participants ["only-one"]})))))

(deftest join-dialogue-test
  (testing "join-dialogue adds participant"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["writer" "critic"]})]
      (is (true? (dialogue/join-dialogue dialogue-id "mediator")))
      (is (= #{"writer" "critic" "mediator"}
             (dialogue/get-participants dialogue-id))))))

(deftest join-dialogue-not-found-test
  (testing "join-dialogue returns false for non-existent dialogue"
    (is (false? (dialogue/join-dialogue "non-existent" "someone")))))

(deftest leave-dialogue-test
  (testing "leave-dialogue removes participant"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["a" "b" "c"]})]
      (is (true? (dialogue/leave-dialogue dialogue-id "c")))
      (is (= #{"a" "b"} (dialogue/get-participants dialogue-id))))))

(deftest leave-dialogue-ends-when-insufficient-test
  (testing "leave-dialogue aborts the dialogue when < 2 participants remain"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["a" "b"]})]
      (dialogue/leave-dialogue dialogue-id "b")
      (is (= :aborted (:status (dialogue/get-dialogue dialogue-id)))
          ":aborted is the terminal status in the schema's status set"))))

;;; =============================================================================
;;; Turn Recording Tests
;;; =============================================================================

(deftest record-turn-via-internal-test
  (testing "Turns are recorded with correct structure"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["sender" "receiver"]})]
      ;; Use internal function for isolated testing
      (#'dialogue/record-turn! dialogue-id
                               {:sender "sender"
                                :receiver "receiver"
                                :message "Test message"
                                :signal :propose})
      (let [turns (dialogue/get-dialogue-turns dialogue-id)]
        (is (= 1 (count turns)))
        (let [turn (first turns)]
          (is (= 1 (:turn-num turn)))
          (is (= "sender" (:sender turn)))
          (is (= "receiver" (:receiver turn)))
          (is (= "Test message" (:message turn)))
          (is (= :propose (:signal turn)))
          (is (some? (:timestamp turn)))
          (is (string? (:id turn))))))))

(deftest get-last-turn-for-test
  (testing "get-last-turn-for returns the most recent turn ADDRESSED TO a participant"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["a" "b"]})]
      (#'dialogue/record-turn! dialogue-id
                               {:sender "a" :receiver "b" :message "First" :signal :propose})
      (#'dialogue/record-turn! dialogue-id
                               {:sender "b" :receiver "a" :message "Response" :signal :counter})
      (#'dialogue/record-turn! dialogue-id
                               {:sender "a" :receiver "b" :message "Second" :signal :approve})
      (let [last-a (dialogue/get-last-turn-for dialogue-id "a")
            last-b (dialogue/get-last-turn-for dialogue-id "b")]
        (is (= "Response" (:message last-a)))
        (is (= :counter (:signal last-a)))
        (is (= "Second" (:message last-b)))
        (is (= :approve (:signal last-b)))))))

;;; =============================================================================
;;; Nash Equilibrium Tests
;;; =============================================================================

(deftest nash-equilibrium-not-reached-test
  (testing "nash-equilibrium? returns false when not all have signaled"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["a" "b"]})]
      ;; Only one participant has signaled
      (#'dialogue/record-turn! dialogue-id
                               {:sender "a" :receiver "b" :message "x" :signal :approve})
      (is (false? (dialogue/nash-equilibrium? dialogue-id))))))

(deftest nash-equilibrium-disrupted-test
  (testing "nash-equilibrium? returns false when any signal is disruptive"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["a" "b"]})]
      (#'dialogue/record-turn! dialogue-id
                               {:sender "a" :receiver "b" :message "x" :signal :approve})
      (#'dialogue/record-turn! dialogue-id
                               {:sender "b" :receiver "a" :message "x" :signal :propose})
      (is (false? (dialogue/nash-equilibrium? dialogue-id))))))

(deftest nash-equilibrium-reached-test
  (testing "nash-equilibrium? returns true when all signal equilibrium"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["a" "b"]})]
      (#'dialogue/record-turn! dialogue-id
                               {:sender "a" :receiver "b" :message "x" :signal :approve})
      (#'dialogue/record-turn! dialogue-id
                               {:sender "b" :receiver "a" :message "x" :signal :no-change})
      (is (true? (dialogue/nash-equilibrium? dialogue-id))))))

(deftest nash-equilibrium-both-approve-test
  (testing "nash-equilibrium? with both participants approving"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["writer" "critic"]})]
      (#'dialogue/record-turn! dialogue-id
                               {:sender "writer" :receiver "critic" :message "Final version" :signal :approve})
      (#'dialogue/record-turn! dialogue-id
                               {:sender "critic" :receiver "writer" :message "LGTM" :signal :approve})
      (is (true? (dialogue/nash-equilibrium? dialogue-id))))))

;;; =============================================================================
;;; List/Summary Tests
;;; =============================================================================

(deftest list-dialogues-test
  (testing "list-dialogues returns all dialogues"
    (dialogue/create-dialogue {:participants ["a" "b"] :topic "First"})
    (dialogue/create-dialogue {:participants ["c" "d"] :topic "Second"})
    (let [dialogues (dialogue/list-dialogues)]
      (is (= 2 (count dialogues)))
      (is (= #{"First" "Second"} (set (map :topic dialogues)))))))

(deftest dialogue-summary-test
  (testing "dialogue-summary returns human-readable summary"
    (let [dialogue-id (dialogue/create-dialogue
                       {:participants ["writer" "critic"]
                        :topic "Auth review"})]
      (#'dialogue/record-turn! dialogue-id
                               {:sender "writer" :receiver "critic" :message "x" :signal :propose})
      (#'dialogue/record-turn! dialogue-id
                               {:sender "critic" :receiver "writer" :message "x" :signal :counter})
      (let [summary (dialogue/dialogue-summary dialogue-id)]
        (is (= dialogue-id (:id summary)))
        (is (= "Auth review" (:topic summary)))
        (is (= :active (:status summary)))
        (is (= 2 (:turn-count summary)))
        (is (= :counter (:last-signal summary)))
        (is (false? (:consensus? summary)))))))

;;; =============================================================================
;;; Signal Set Tests
;;; =============================================================================

;;; =============================================================================
;;; Data-Driven Signal Tests (via signal.clj)
;;; =============================================================================

(deftest parse-signal-no-nlp-test
  (testing "NLP removed: natural language messages default to :propose"
    ;; ADR: Data-driven signals replace NLP regex parsing.
    ;; Messages without [SIGNAL: X] prefix always default to :propose.
    (is (= [:propose "LGTM, ship it!" :default]
           (dialogue/parse-signal "LGTM, ship it!")))
    (is (= [:propose "I accept this approach" :default]
           (dialogue/parse-signal "I accept this approach")))
    (is (= [:propose "I disagree, we need a different approach" :default]
           (dialogue/parse-signal "I disagree, we need a different approach"))))

  (testing "prefix still works after NLP removal"
    (is (= [:propose "I accept this, here's my proposal" :prefix]
           (dialogue/parse-signal "[SIGNAL: propose] I accept this, here's my proposal")))))

(deftest signal-from-map-test
  (testing "signal.clj constructs valid signal from structured map"
    (let [result (signal/signal-from-map {:type :propose
                                          :strength 0.8
                                          :message "Strategy pattern enables runtime switching"
                                          :evidence [{:source "GOF" :content "Chapter 5"}]})]
      (is (= :propose (:type result)))
      (is (= 0.8 (:strength result)))
      (is (= "Strategy pattern enables runtime switching" (:message result)))
      (is (= 1 (count (:evidence result))))))

  (testing "signal-from-map rejects invalid type"
    (let [result (signal/signal-from-map {:type :invalid :message "test"})]
      (is (some? (:error result)))))

  (testing "signal-from-map rejects missing message"
    (let [result (signal/signal-from-map {:type :propose})]
      (is (some? (:error result)))))

  (testing "signal-from-map accepts :signal alias for :type"
    (let [result (signal/signal-from-map {:signal :approve :message "LGTM"})]
      (is (= :approve (:type result)))))

  (testing "strength clamped to 0.0-1.0"
    (is (= 1.0 (:strength (signal/signal-from-map {:type :approve :strength 5.0 :message "x"}))))
    (is (= 0.0 (:strength (signal/signal-from-map {:type :approve :strength -1.0 :message "x"}))))))

(deftest signal-parse-unified-test
  (testing "parse-signal dispatches on input type"
    ;; Map input
    (let [result (signal/parse-signal {:type :approve :message "LGTM"})]
      (is (= :approve (:type result))))
    ;; String input (legacy)
    (let [result (signal/parse-signal "[SIGNAL: counter] I disagree")]
      (is (= :counter (:type result))))
    ;; Keyword input
    (let [result (signal/parse-signal :defer)]
      (is (= :defer (:type result)))))

  (testing "parse-signal returns error for invalid keyword"
    (let [result (signal/parse-signal :invalid-signal)]
      (is (some? (:error result))))))

;;; =============================================================================
;;; Signal Set Tests
;;; =============================================================================

(deftest signal-sets-test
  (testing "Signal sets delegate to signal.clj"
    (is (= signal/signal-types dialogue/signals))
    (is (= signal/equilibrium-signals dialogue/equilibrium-signals))
    (is (= signal/disruption-signals dialogue/disruption-signals)))

  (testing "Signal sets contain expected values"
    (is (contains? dialogue/signals :propose))
    (is (contains? dialogue/signals :counter))
    (is (contains? dialogue/signals :no-change))
    (is (contains? dialogue/signals :approve))
    (is (contains? dialogue/signals :defer))
    ;; equilibrium-signals now includes :lgtm via signal.clj
    (is (contains? dialogue/equilibrium-signals :no-change))
    (is (contains? dialogue/equilibrium-signals :approve))
    (is (contains? dialogue/equilibrium-signals :lgtm))
    (is (contains? dialogue/disruption-signals :propose))
    (is (contains? dialogue/disruption-signals :counter))))
