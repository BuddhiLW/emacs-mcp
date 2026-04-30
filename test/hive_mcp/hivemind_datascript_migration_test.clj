(ns hive-mcp.hivemind-datascript-migration-test
  "TDD tests for agent-registry migration to DataScript.

   ADR-002 (AMENDED): DataScript is the unified Clojure registry.
   The agent-registry atom should be deprecated in favor of:
   - DataScript queries for slave info (status, name, presets, cwd)
   - A minimal message-history atom for message ring buffer (hivemind-specific)

   Migration goals:
   1. get-status queries DataScript for :agents, not agent-registry atom
   2. get-agent-messages uses message-history atom (messages not in DataScript)
   3. shout! updates both DataScript (status) and message-history (messages)
   4. hivemind_messages works for any slave in DataScript

   SOLID: Single source of truth (DataScript) for slave data
   CLARITY: Layers stay pure (DataScript = infrastructure, hivemind = coordination)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.swarm.datascript :as ds]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(defn- hivemind-atoms-fixture
  "Reset hivemind agent-registry and message-history atoms before/after."
  [f]
  (let [reset-atoms!
        (fn []
          (when-let [agent-reg (resolve 'hive-mcp.hivemind.core/agent-registry)]
            (reset! @agent-reg {}))
          (when-let [msg-history (resolve 'hive-mcp.hivemind.core/message-history)]
            (reset! @msg-history {})))]
    (reset-atoms!)
    (try (f) (finally (reset-atoms!)))))

(use-fixtures :each
  (iso/with-isolations :swarm-ds)
  hivemind-atoms-fixture)

;;; =============================================================================
;;; Helper Functions
;;; =============================================================================

(defn get-hivemind-messages-handler
  "Extract the hivemind_messages handler from tools list."
  []
  (let [tool (first (filter #(= "hivemind_messages" (:name %)) hivemind/tools))]
    (:handler tool)))

(defn get-hivemind-status-handler
  "Extract the hivemind_status handler from tools list."
  []
  (let [tool (first (filter #(= "hivemind_status" (:name %)) hivemind/tools))]
    (:handler tool)))

;;; =============================================================================
;;; Core Migration Tests: DataScript as Source of Truth for Slaves
;;; =============================================================================

(deftest get-status-reads-from-datascript-test
  (testing "get-status returns slaves from DataScript, not agent-registry atom"
    ;; Add slave directly to DataScript (as sync.clj would do)
    (ds/add-slave! "ds-slave-1" {:name "ling-1" :status :idle :depth 1})
    (ds/add-slave! "ds-slave-2" {:name "ling-2" :status :working :depth 1})

    (let [status (hivemind/get-status)
          agents (:agents status)]
      ;; Both should appear in :agents
      (is (contains? agents "ds-slave-1")
          "DataScript slave should appear in hivemind status")
      (is (contains? agents "ds-slave-2")
          "DataScript slave should appear in hivemind status")
      ;; Status should come from DataScript
      (is (= :idle (get-in agents ["ds-slave-1" :status]))
          "Status should reflect DataScript state"))))

(deftest get-status-includes-datascript-metadata-test
  (testing "get-status includes slave metadata from DataScript"
    (ds/add-slave! "meta-slave" {:name "my-ling"
                                 :status :working
                                 :depth 2
                                 :presets ["tdd" "clarity"]
                                 :cwd "/project/path"})

    (let [status (hivemind/get-status)
          agent-data (get-in status [:agents "meta-slave"])]
      (is (= "my-ling" (:name agent-data)))
      (is (= :working (:status agent-data)))
      ;; Presets and cwd if available
      (is (or (nil? (:presets agent-data))
              (= #{"tdd" "clarity"} (set (:presets agent-data))))))))

(deftest hivemind-messages-works-for-datascript-slaves-test
  (testing "hivemind_messages returns messages for slaves in DataScript"
    ;; Add slave to DataScript (not hivemind registry)
    (ds/add-slave! "ds-only-slave" {:name "test" :status :idle})

    (let [handler (get-hivemind-messages-handler)
          result (handler {:agent_id "ds-only-slave"})
          parsed (json/read-str (:text result) :key-fn keyword)]
      ;; Should NOT return error - slave exists in DataScript
      (is (nil? (:error parsed))
          "Should not error for slave that exists in DataScript")
      (is (= "ds-only-slave" (:agent_id parsed))))))

;;; =============================================================================
;;; Message History Tests (Separate from Slave Data)
;;; =============================================================================

(deftest shout-stores-messages-separately-test
  (testing "shout! stores messages in message-history, not dependent on DataScript"
    ;; Add slave to DataScript first
    (ds/add-slave! "shouting-slave" {:name "ling" :status :idle})

    ;; Shout some messages
    (hivemind/shout! "shouting-slave" :started {:task "work" :message "beginning"})
    (hivemind/shout! "shouting-slave" :progress {:task "work" :message "step 1"})

    ;; Get messages
    (let [messages (hivemind/get-agent-messages "shouting-slave")]
      (is (= 2 (count messages)))
      (is (= :started (:event-type (first messages))))
      (is (= :progress (:event-type (second messages)))))))

(deftest shout-updates-datascript-status-test
  (testing "shout! updates slave status in DataScript"
    ;; Add slave with initial status
    (ds/add-slave! "status-slave" {:name "ling" :status :idle})

    ;; Shout with :working event type
    (hivemind/shout! "status-slave" :working {:task "task-1" :message "working on it"})

    ;; Verify DataScript was updated
    (let [slave (ds/get-slave "status-slave")]
      (is (= :working (:slave/status slave))
          "DataScript status should be updated by shout!"))))

(deftest message-ring-buffer-limit-test
  (testing "Message history respects 10-message ring buffer limit"
    (ds/add-slave! "chatty-slave" {:name "ling" :status :idle})

    ;; Send 15 messages
    (doseq [i (range 15)]
      (hivemind/shout! "chatty-slave" :progress {:task "work" :message (str "msg-" i)}))

    ;; Should only have last 10
    (let [messages (hivemind/get-agent-messages "chatty-slave")]
      (is (= 10 (count messages)))
      ;; First message should be msg-5 (oldest retained)
      (is (= "msg-5" (:message (first messages)))))))

;;; =============================================================================
;;; Integration Tests: DataScript + Messages Combined
;;; =============================================================================

(deftest get-status-merges-datascript-and-messages-test
  (testing "get-status merges DataScript slave data with message history"
    ;; Add slave to DataScript
    (ds/add-slave! "hybrid-slave" {:name "hybrid-ling" :status :idle})

    ;; Shout to create messages
    (hivemind/shout! "hybrid-slave" :started {:task "test" :message "begin"})

    (let [status (hivemind/get-status)
          agent-data (get-in status [:agents "hybrid-slave"])]
      ;; Should have DataScript data
      (is (some? (:status agent-data)))
      ;; Should have messages
      (is (vector? (:messages agent-data)))
      (is (= 1 (count (:messages agent-data)))))))

(deftest available-agents-lists-datascript-slaves-test
  (testing "available-agents in error response lists DataScript slaves"
    ;; Add slaves to DataScript only
    (ds/add-slave! "avail-1" {:name "ling-1" :status :idle})
    (ds/add-slave! "avail-2" {:name "ling-2" :status :working})

    (let [handler (get-hivemind-messages-handler)
          result (handler {:agent_id "nonexistent"})
          parsed (json/read-str (:text result) :key-fn keyword)
          available (:available-agents parsed)]
      (is (some #(= "avail-1" %) available))
      (is (some #(= "avail-2" %) available)))))

;;; =============================================================================
;;; Backward Compatibility Tests
;;; =============================================================================

(deftest register-agent-still-works-test
  (testing "register-agent! continues to work (adds to DataScript if not present)"
    ;; Use register-agent! directly
    (hivemind/register-agent! "legacy-agent" {:name "old-style"})

    ;; Should be visible in status
    (let [status (hivemind/get-status)
          agents (:agents status)]
      (is (contains? agents "legacy-agent")))))

(deftest clear-agent-cleans-up-test
  (testing "clear-agent! removes from both DataScript and message-history"
    ;; Setup: add slave and shout
    (ds/add-slave! "temp-slave" {:name "temp" :status :idle})
    (hivemind/shout! "temp-slave" :started {:task "work" :message "hi"})

    ;; Clear
    (hivemind/clear-agent! "temp-slave")

    ;; Should not appear in status
    (let [status (hivemind/get-status)
          agents (:agents status)]
      (is (not (contains? agents "temp-slave"))))))

;;; =============================================================================
;;; Edge Cases
;;; =============================================================================

(deftest messages-for-unknown-slave-returns-nil-test
  (testing "get-agent-messages returns nil for unknown slave"
    (is (nil? (hivemind/get-agent-messages "totally-unknown")))))

(deftest shout-for-nonexistent-slave-creates-entry-test
  (testing "shout! for non-DataScript slave still works (graceful)"
    ;; Shout without adding to DataScript first
    (hivemind/shout! "orphan-agent" :progress {:task "work" :message "hi"})

    ;; Should have messages stored
    (let [messages (hivemind/get-agent-messages "orphan-agent")]
      (is (= 1 (count messages))))))
