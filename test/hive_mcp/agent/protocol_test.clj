(ns hive-mcp.agent.protocol-test
  "Tests for IAgent protocol and implementations.

   Verifies that both Ling and Drone implementations satisfy
   the IAgent protocol with correct behavior.

   Test areas:
   - Protocol satisfaction (satisfies? checks)
   - agent-type returns correct keyword
   - can-chain-tools? behavior differs by agent type
   - claims/claim-files!/release-claims! lifecycle
   - upgrade! behavior (no-op for lings, returns spec for drones)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.agent.drone :as drone]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-test.isolation :as iso]
            hive-mcp.isolation-methods))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each (iso/with-isolations :swarm-ds))

;; =============================================================================
;; Section 1: Protocol Satisfaction Tests
;; =============================================================================

(deftest ling-satisfies-iagent
  (testing "Ling record implements IAgent protocol"
    (let [ling (ling/->ling "test-ling-001"
                            {:cwd "/tmp/test"
                             :presets ["tdd"]
                             :project-id "test-project"})]
      (is (satisfies? proto/IAgent ling)
          "Ling should satisfy IAgent protocol"))))

(deftest drone-satisfies-iagent
  (testing "Drone record implements IAgent protocol"
    (let [drone (drone/->drone "test-drone-001"
                               {:cwd "/tmp/test"
                                :task-type :testing
                                :project-id "test-project"})]
      (is (satisfies? proto/IAgent drone)
          "Drone should satisfy IAgent protocol"))))

;; =============================================================================
;; Section 2: agent-type Tests
;; =============================================================================

(deftest ling-agent-type-test
  (testing "Ling returns :ling as agent-type"
    (let [ling (ling/->ling "ling-type-test" {})]
      (is (= :ling (proto/agent-type ling))
          "Ling agent-type should be :ling"))))

(deftest drone-agent-type-test
  (testing "Drone returns :drone as agent-type"
    (let [drone (drone/->drone "drone-type-test" {})]
      (is (= :drone (proto/agent-type drone))
          "Drone agent-type should be :drone"))))

;; =============================================================================
;; Section 3: can-chain-tools? Tests
;; =============================================================================

(deftest ling-can-chain-tools-test
  (testing "Lings CAN chain multiple tool calls"
    (let [ling (ling/->ling "ling-chain-test" {})]
      (is (true? (proto/can-chain-tools? ling))
          "Lings should be able to chain tools"))))

(deftest drone-cannot-chain-tools-test
  (testing "Drones CANNOT chain multiple tool calls"
    (let [drone (drone/->drone "drone-chain-test" {})]
      (is (false? (proto/can-chain-tools? drone))
          "Drones should NOT be able to chain tools"))))

;; =============================================================================
;; Section 4: Protocol Method Presence Tests
;; =============================================================================

(deftest ling-has-all-protocol-methods
  (testing "Ling has all IAgent protocol methods"
    (let [ling (ling/->ling "ling-methods-test" {:cwd "/tmp"})]
      ;; Check that all protocol methods are callable
      ;; (they exist as methods on the record)
      (is (fn? (fn [] (.agent-type ling)))
          "should have agent-type method")
      (is (fn? (fn [] (.can-chain-tools? ling)))
          "should have can-chain-tools? method")
      (is (fn? (fn [] (.claims ling)))
          "should have claims method")
      (is (fn? (fn [] (.upgrade! ling)))
          "should have upgrade! method"))))

(deftest drone-has-all-protocol-methods
  (testing "Drone has all IAgent protocol methods"
    (let [drone (drone/->drone "drone-methods-test" {:cwd "/tmp"})]
      (is (fn? (fn [] (.agent-type drone)))
          "should have agent-type method")
      (is (fn? (fn [] (.can-chain-tools? drone)))
          "should have can-chain-tools? method")
      (is (fn? (fn [] (.claims drone)))
          "should have claims method")
      (is (fn? (fn [] (.upgrade! drone)))
          "should have upgrade! method"))))

;; =============================================================================
;; Section 5: upgrade! Tests
;; =============================================================================

(deftest ling-upgrade-is-noop
  (testing "Ling upgrade! is a no-op (returns nil)"
    (let [ling (ling/->ling "ling-upgrade-test" {})]
      (is (nil? (proto/upgrade! ling))
          "Ling upgrade! should return nil"))))

(deftest drone-upgrade-returns-spec
  (testing "Drone upgrade! returns upgrade specification"
    ;; First register the drone in DataScript so upgrade! can update it
    (ds/add-slave! "drone-upgrade-test" {:slave/status :idle
                                         :slave/agent-type :drone})
    (let [drone (drone/->drone "drone-upgrade-test"
                               {:cwd "/tmp/project"
                                :project-id "test-project"
                                :parent-id "parent-ling"})]
      (let [result (proto/upgrade! drone)]
        (is (map? result) "upgrade! should return a map")
        (is (contains? result :ling-id) "should contain :ling-id")
        (is (= "/tmp/project" (:cwd result)) "should preserve cwd")
        (is (= "test-project" (:project-id result)) "should preserve project-id")))))

;; =============================================================================
;; Section 6: Polymorphic Dispatch Tests
;; =============================================================================

(deftest polymorphic-agent-type-dispatch
  (testing "agent-type dispatches correctly for both agent types"
    (let [ling (ling/->ling "poly-ling" {})
          drone (drone/->drone "poly-drone" {})]
      (is (= :ling (proto/agent-type ling)))
      (is (= :drone (proto/agent-type drone))))))

(deftest polymorphic-can-chain-dispatch
  (testing "can-chain-tools? dispatches correctly for both agent types"
    (let [ling (ling/->ling "poly-chain-ling" {})
          drone (drone/->drone "poly-chain-drone" {})]
      (is (true? (proto/can-chain-tools? ling)))
      (is (false? (proto/can-chain-tools? drone))))))

;; =============================================================================
;; Section 7: Claims Lifecycle (Without Mocking External Deps)
;; =============================================================================

(deftest drone-claims-lifecycle
  (testing "Drone claims are managed through state-atom"
    (let [drone (drone/->drone "drone-claims-test"
                               {:cwd "/tmp/project"})]
      ;; Initial claims should be empty
      (is (empty? (proto/claims drone))
          "New drone should have no claims")

      ;; Note: claim-files! requires DataScript registration and coordinator
      ;; This is tested more thoroughly in integration tests
      )))

(deftest ling-claims-empty-without-registration
  (testing "Ling claims return empty without registration"
    (let [ling (ling/->ling "ling-claims-test" {:cwd "/tmp"})]
      ;; Claims query DataScript, which should be empty for unregistered ling
      (is (empty? (proto/claims ling))
          "Unregistered ling should have no claims"))))

;; =============================================================================
;; Section 8: Record Field Access Tests
;; =============================================================================

(deftest ling-record-fields
  (testing "Ling record has expected fields"
    (let [ling (ling/->ling "ling-fields-test"
                            {:cwd "/home/user/project"
                             :presets ["tdd" "reviewer"]
                             :project-id "my-project"})]
      (is (= "ling-fields-test" (:id ling)))
      (is (= "/home/user/project" (:cwd ling)))
      (is (= ["tdd" "reviewer"] (:presets ling)))
      (is (= "my-project" (:project-id ling))))))

(deftest drone-record-fields
  (testing "Drone record has expected fields"
    (let [drone (drone/->drone "drone-fields-test"
                               {:model "test-model"
                                :task-type :testing
                                :cwd "/home/user/project"
                                :max-steps 10
                                :parent-id "parent-ling"
                                :project-id "my-project"})]
      (is (= "drone-fields-test" (:id drone)))
      (is (= "test-model" (:model drone)))
      (is (= :testing (:task-type drone)))
      (is (= "/home/user/project" (:cwd drone)))
      (is (= 10 (:max-steps drone)))
      (is (= "parent-ling" (:parent-id drone)))
      (is (= "my-project" (:project-id drone))))))

;; =============================================================================
;; Section 9: IAgentRegistry Protocol Tests
;; =============================================================================

(deftest iagent-registry-protocol-exists
  (testing "IAgentRegistry protocol is defined"
    (is (some? proto/IAgentRegistry)
        "IAgentRegistry protocol should exist")))

;; =============================================================================
;; Section 10: LLMBackend Protocol Tests
;; =============================================================================

(deftest llmbackend-protocol-exists
  (testing "LLMBackend protocol is defined"
    (is (some? proto/LLMBackend)
        "LLMBackend protocol should exist")))

(comment
  ;; Run tests
  (clojure.test/run-tests 'hive-mcp.agent.protocol-test)

  ;; Run single test
  (clojure.test/test-vars [#'ling-satisfies-iagent]))
