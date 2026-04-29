(ns hive-mcp.hivemind-spawn-registration-test
  "Tests for hivemind agent registration on spawn.

   Bug: hivemind_messages returns 'Agent not found' for valid spawned agents
   because agents only get registered in agent-registry when they shout.

   Fix: Register agents in hivemind agent-registry when they spawn,
   not just when they shout.

   Tests verify:
   - register-agent! creates entry in agent-registry
   - Registered agents appear in available-agents
   - hivemind_messages works for registered (non-shouting) agents
   - Swarm registry registration also registers in hivemind"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.tools.swarm.registry :as swarm-registry]
            [hive-dsl.bounded-atom :refer [bclear!]]
            [hive-test.isolation :as iso]
            hive-mcp.isolation-methods))

;;; Test fixtures

(use-fixtures :each (iso/with-isolations :swarm-ds :agent-registry))

;;; Helper to get the handler

(defn get-hivemind-messages-handler
  "Extract the hivemind_messages handler from tools list."
  []
  (let [tool (first (filter #(= "hivemind_messages" (:name %)) hivemind/tools))]
    (:handler tool)))

;;; Core functionality: register-agent! (new function)

(deftest register-agent!-creates-entry-test
  (testing "register-agent! creates entry in agent-registry without requiring shout"
    (hivemind/register-agent! "spawned-agent-123" {:name "test-ling"})

    ;; Should appear in available-agents
    (let [status (hivemind/get-status)
          agents (:agents status)]
      (is (contains? agents "spawned-agent-123"))
      ;; ADR-002 AMENDED: Status is now :idle (DataScript valid status)
      (is (= :idle (get-in agents ["spawned-agent-123" :status]))))))

(deftest register-agent!-preserves-metadata-test
  (testing "register-agent! stores spawn metadata"
    (hivemind/register-agent! "agent-with-meta"
                              {:name "my-ling"
                               :presets ["tdd" "clarity"]
                               :cwd "/project/path"})

    ;; ADR-002 AMENDED: Use public API (get-status) instead of internal atom
    ;; Metadata is now stored in DataScript, not agent-registry atom
    ;; Note: presets order not preserved (DataScript :db.cardinality/many)
    (let [status (hivemind/get-status)
          agent-data (get-in status [:agents "agent-with-meta"])]
      (is (= "my-ling" (:name agent-data)))
      (is (= #{"tdd" "clarity"} (set (:presets agent-data))))
      (is (= "/project/path" (:cwd agent-data))))))

(deftest register-agent!-initializes-messages-empty-test
  (testing "register-agent! initializes with empty messages vector"
    (hivemind/register-agent! "fresh-agent" {:name "new"})

    (let [messages (hivemind/get-agent-messages "fresh-agent")]
      (is (vector? messages))
      (is (empty? messages)))))

;;; Bug regression: hivemind_messages should work for registered agents

(deftest hivemind-messages-works-for-registered-agent-test
  (testing "Bug fix: hivemind_messages returns messages for registered (spawned) agent"
    ;; Register without shouting (simulates spawn)
    (hivemind/register-agent! "swarm-test-agent-456" {:name "test"})

    (let [handler (get-hivemind-messages-handler)
          result (handler {:agent_id "swarm-test-agent-456"})
          parsed (json/read-str (:text result) :key-fn keyword)]
      ;; Should NOT return "Agent not found" error
      (is (nil? (:error parsed))
          "Should not return error for registered agent")
      (is (= "swarm-test-agent-456" (:agent_id parsed)))
      ;; Messages should be empty vector (not nil)
      (is (vector? (:messages parsed))))))

(deftest hivemind-messages-available-agents-includes-registered-test
  (testing "Bug fix: available-agents includes registered (spawned) agents"
    ;; Register two agents: one via shout, one via register
    (hivemind/shout! "shouting-agent" :started {:task "work"})
    (hivemind/register-agent! "registered-agent" {:name "ling"})

    (let [handler (get-hivemind-messages-handler)
          ;; Query nonexistent to get available-agents
          result (handler {:agent_id "nonexistent"})
          parsed (json/read-str (:text result) :key-fn keyword)
          available (:available-agents parsed)]
      ;; Both should appear in available-agents
      (is (some #(= "shouting-agent" %) available)
          "Shouting agent should be available")
      (is (some #(= "registered-agent" %) available)
          "Registered agent should be available"))))

;;; Integration: swarm spawn -> hivemind registration

(deftest swarm-registry-registers-in-hivemind-test
  (testing "Swarm registry registration also registers in hivemind"
    ;; Simulate spawn event by calling handle-ling-registered
    (let [spawn-event {:slave-id "swarm-ling-789"
                       :name "task-ling"
                       :presets ["tdd"]
                       :cwd "/work"}]
      ;; This should register in BOTH registries
      (#'swarm-registry/handle-ling-registered spawn-event))

    ;; Verify swarm registry
    (let [lings (swarm-registry/get-available-lings)]
      (is (contains? lings "swarm-ling-789")))

    ;; Verify hivemind registry - this is the bug fix!
    (let [handler (get-hivemind-messages-handler)
          result (handler {:agent_id "swarm-ling-789"})
          parsed (json/read-str (:text result) :key-fn keyword)]
      (is (nil? (:error parsed))
          "Spawned ling should be available in hivemind")
      (is (= "swarm-ling-789" (:agent_id parsed))))))

(deftest shout-after-register-appends-messages-test
  (testing "Shouting after register appends messages to existing entry"
    ;; Register first (spawn)
    (hivemind/register-agent! "agent-x" {:name "ling"})

    ;; Then shout (agent does work)
    (hivemind/shout! "agent-x" :progress {:task "testing" :message "step 1"})
    (hivemind/shout! "agent-x" :completed {:task "testing" :message "done"})

    ;; Should have messages
    (let [messages (hivemind/get-agent-messages "agent-x")]
      (is (= 2 (count messages)))
      (is (= :progress (keyword (:event-type (first messages)))))
      (is (= :completed (keyword (:event-type (second messages))))))))
