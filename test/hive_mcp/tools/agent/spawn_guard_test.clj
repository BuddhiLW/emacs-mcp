(ns hive-mcp.tools.agent.spawn-guard-test
  "Tests for spawn handler defense-in-depth guard.

   Verifies that child lings (HIVE_MCP_ROLE=child-ling) cannot spawn
   agents, preventing recursive self-call chains.

   Test Coverage:
   1. Guard denies spawn for child lings (ling and drone types)
   2. Guard allows spawn for coordinator (normal path)
   3. Error message includes role, depth, and guidance
   4. Guard works with batch-spawn routing
   5. Guard logs violation with correct metadata"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.tools.agent.spawn :as spawn]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.emacs.client :as ec]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.logic :as logic]
            [hive-mcp.tools.swarm.core :as swarm-core]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.test.stub.terminal-addon :as term-stub]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn- logic-and-redefs-fixture
  "Reset logic db and stub swarm-addon-available? for each test."
  [f]
  (logic/reset-db!)
  (with-redefs [swarm-core/swarm-addon-available? (constantly false)]
    (try (f) (finally (logic/reset-db!)))))

(use-fixtures :each
  (iso/with-isolations :swarm-ds)
  logic-and-redefs-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn parse-response
  "Parse JSON response from handler."
  [result]
  (when-not (:isError result)
    (json/read-str (:text result) :key-fn keyword)))

;; =============================================================================
;; Spawn Guard — Denial Tests (child-ling? = true)
;; =============================================================================

(deftest test-spawn-guard-denies-ling-spawn-from-child
  (testing "child ling is denied from spawning a ling"
    (with-redefs [guards/child-ling? (constantly true)
                  guards/get-role    (constantly "child-ling")
                  guards/ling-depth  (constantly 1)]
      (let [result (spawn/handle-spawn {:type "ling"
                                        :name "recursive-ling"
                                        :cwd "/tmp/project"})]
        (is (:isError result)
            "Spawn should be denied for child lings")
        (is (re-find #"SPAWN DENIED" (:text result))
            "Error should contain SPAWN DENIED")))))

(deftest test-spawn-guard-denies-drone-spawn-from-child
  (testing "child ling is denied from spawning a drone"
    (with-redefs [guards/child-ling? (constantly true)
                  guards/get-role    (constantly "child-ling")
                  guards/ling-depth  (constantly 1)]
      (let [result (spawn/handle-spawn {:type "drone"
                                        :name "test-drone"
                                        :cwd "/tmp/project"
                                        :files ["src/core.clj"]})]
        (is (:isError result)
            "Drone spawn should also be denied for child lings")
        (is (re-find #"SPAWN DENIED" (:text result)))))))

(deftest test-spawn-guard-denies-at-depth-2
  (testing "child ling at depth 2 is denied"
    (with-redefs [guards/child-ling? (constantly true)
                  guards/get-role    (constantly "child-ling")
                  guards/ling-depth  (constantly 2)]
      (let [result (spawn/handle-spawn {:type "ling" :cwd "/tmp"})]
        (is (:isError result))
        (is (re-find #"depth=2" (:text result))
            "Error message should include current depth")))))

(deftest test-spawn-guard-denies-before-type-validation
  (testing "guard fires before type validation (invalid type still gets guard error)"
    (with-redefs [guards/child-ling? (constantly true)
                  guards/get-role    (constantly "child-ling")
                  guards/ling-depth  (constantly 1)]
      (let [result (spawn/handle-spawn {:type "invalid" :cwd "/tmp"})]
        ;; Should get SPAWN DENIED, not "must be ling or drone"
        (is (:isError result))
        (is (re-find #"SPAWN DENIED" (:text result))
            "Guard should fire before type validation")
        (is (not (re-find #"must be 'ling' or 'drone'" (:text result)))
            "Type validation should NOT have been reached")))))

;; =============================================================================
;; Spawn Guard — Allow Tests (child-ling? = false)
;; =============================================================================

(deftest test-spawn-guard-allows-coordinator-ling-spawn
  (testing "coordinator (non-child) can spawn lings normally"
    ;; Every terminal backend is addon-contributed, so a cold core resolves NO
    ;; strategy for :claude. Arrange one the way an addon would.
    (term-stub/with-terminal
      (fn []
        (with-redefs [guards/child-ling? (constantly false)]
          (let [result (spawn/handle-spawn {:type "ling"
                                            :name "coord-ling-1"
                                            :cwd "/tmp/project"})
                parsed (parse-response result)]
            (is (not (:isError result))
                (str "Coordinator should be allowed to spawn, got: " (:text result)))
            (is (:success parsed))
            (is (= "coord-ling-1" (:agent-id parsed)))))))))

(deftest test-spawn-guard-allows-coordinator-type-validation
  (testing "coordinator reaches type validation for invalid types"
    (with-redefs [guards/child-ling? (constantly false)]
      (let [result (spawn/handle-spawn {:type "invalid" :cwd "/tmp"})]
        (is (:isError result))
        (is (re-find #"type must be one of" (:text result))
            "Coordinator should get type validation error, not guard error")))))

;; =============================================================================
;; Error Message Content Tests
;; =============================================================================

(deftest test-spawn-guard-error-message-content
  (testing "guard error message contains actionable guidance"
    (with-redefs [guards/child-ling? (constantly true)
                  guards/get-role    (constantly "child-ling")
                  guards/ling-depth  (constantly 3)]
      (let [result (spawn/handle-spawn {:type "ling" :cwd "/tmp"})
            msg (:text result)]
        (is (re-find #"SPAWN DENIED" msg)
            "Should start with clear denial")
        (is (re-find #"child-ling" msg)
            "Should mention role")
        (is (re-find #"depth=3" msg)
            "Should include depth")
        (is (re-find #"recursive" msg)
            "Should explain why (recursive chains)")
        (is (re-find #"hivemind_shout" msg)
            "Should suggest alternative (ask coordinator)")))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-spawn-guard-nil-type-from-child
  (testing "child ling with nil type gets guard error, not NPE"
    (with-redefs [guards/child-ling? (constantly true)
                  guards/get-role    (constantly "child-ling")
                  guards/ling-depth  (constantly 1)]
      (let [result (spawn/handle-spawn {:cwd "/tmp"})]
        (is (:isError result))
        (is (re-find #"SPAWN DENIED" (:text result))
            "Guard should fire even with nil type")))))

(deftest test-spawn-guard-empty-params-from-child
  (testing "child ling with empty params gets guard error"
    (with-redefs [guards/child-ling? (constantly true)
                  guards/get-role    (constantly "child-ling")
                  guards/ling-depth  (constantly 1)]
      (let [result (spawn/handle-spawn {})]
        (is (:isError result))
        (is (re-find #"SPAWN DENIED" (:text result)))))))

(deftest test-spawn-guard-depth-zero-is-coordinator
  (testing "depth 0 with child-ling?=false allows spawn"
    (with-redefs [guards/child-ling? (constantly false)
                  guards/ling-depth  (constantly 0)]
      ;; Should pass guard, hit type validation
      (let [result (spawn/handle-spawn {:type "invalid"})]
        (is (:isError result))
        (is (re-find #"type must be one of" (:text result))
            "Depth 0 = coordinator, should pass through guard")))))
