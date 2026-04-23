(ns hive-mcp.agent.ling-test
  "Tests for Ling agent implementation.

   Tests cover:
   - ->ling factory function
   - spawn! with elisp integration (mocked)
   - dispatch! task queuing
   - kill! with critical ops guard
   - status retrieval
   - File claim lifecycle

   Mocking Strategy:
   - Use with-redefs to mock emacsclient/eval-elisp-with-timeout
   - Use real DataScript for state verification
   - Isolate external dependencies"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.emacs.client :as ec]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-datascript-fixture
  "Reset DataScript state before and after each test."
  [f]
  (ds/reset-conn!)
  (f)
  (ds/reset-conn!))

(use-fixtures :each reset-datascript-fixture)

;; =============================================================================
;; Mock Helpers
;; =============================================================================

(defn mock-elisp-success
  "Creates a mock elisp response indicating success."
  [result]
  {:success true :result result})

(defn mock-elisp-failure
  "Creates a mock elisp response indicating failure."
  [error]
  {:success false :error error})

;; =============================================================================
;; Section 1: Factory Function Tests (->ling)
;; =============================================================================

(deftest ling-factory-creates-record
  (testing "->ling creates a Ling record"
    (let [ling (ling/->ling "test-ling-001"
                            {:cwd "/tmp/project"
                             :presets ["tdd"]
                             :project-id "my-project"})]
      (is (instance? hive_mcp.agent.ling.spawn.Ling ling)
          "Should create a Ling record")
      (is (= "test-ling-001" (:id ling)))
      (is (= "/tmp/project" (:cwd ling)))
      (is (= ["tdd"] (:presets ling)))
      (is (= "my-project" (:project-id ling))))))

(deftest ling-factory-with-empty-opts
  (testing "->ling works with empty options"
    (let [ling (ling/->ling "minimal-ling" {})]
      (is (= "minimal-ling" (:id ling)))
      (is (nil? (:cwd ling)))
      (is (= [] (:presets ling)))
      (is (nil? (:project-id ling))))))

(deftest ling-factory-preserves-all-presets
  (testing "->ling preserves multiple presets"
    (let [presets ["tdd" "clarity" "reviewer" "coordinator"]
          ling (ling/->ling "multi-preset-ling" {:presets presets})]
      (is (= presets (:presets ling))
          "All presets should be preserved"))))

(deftest ling-factory-with-agents
  (testing "->ling stores subagent definitions"
    (let [agents {"code-reviewer" {:description "Reviews code"
                                   :prompt "You review code"
                                   :tools ["Read" "Grep"]
                                   :model "sonnet"}}
          ling (ling/->ling "agents-ling" {:cwd "/tmp"
                                           :agents agents})]
      (is (= agents (:agents ling))
          "Agents map should be stored in Ling record"))))

(deftest ling-factory-without-agents
  (testing "->ling without agents has nil agents field"
    (let [ling (ling/->ling "no-agents-ling" {:cwd "/tmp"})]
      (is (nil? (:agents ling))
          "Agents should be nil when not provided"))))

(deftest ling-factory-agents-multiple-definitions
  (testing "->ling stores multiple subagent definitions"
    (let [agents {"analyzer" {:description "Analyzes code"
                              :prompt "You analyze code"
                              :tools ["Read" "Grep" "Glob"]}
                  "tester" {:description "Writes tests"
                            :prompt "You write tests"
                            :tools ["Read" "Write" "Bash"]
                            :model "sonnet"}
                  "documenter" {:description "Writes docs"
                                :prompt "You write documentation"}}
          ling (ling/->ling "multi-agents-ling" {:cwd "/tmp"
                                                 :agents agents})]
      (is (= 3 (count (:agents ling))))
      (is (= #{"analyzer" "tester" "documenter"}
             (set (keys (:agents ling))))))))

;; =============================================================================
;; Section 2: spawn! Tests (with mocked elisp)
;; =============================================================================

(deftest ling-spawn-success
  (testing "spawn! registers ling in DataScript on success"
    (let [ling (ling/->ling "spawn-test-001"
                            {:cwd "/tmp/project"
                             :presets ["tdd"]
                             :project-id "test-project"})]
      ;; Mock elisp to return success
      (with-redefs [ec/eval-elisp-with-timeout
                    (fn [_code _timeout]
                      (mock-elisp-success "spawn-test-001"))]
        (let [result (proto/spawn! ling {:depth 1})]
          ;; Should return the ling ID
          (is (= "spawn-test-001" result)
              "spawn! should return ling ID on success")

          ;; Verify DataScript registration
          (let [slave (ds-queries/get-slave "spawn-test-001")]
            (is (some? slave) "Slave should be registered in DataScript")
            (is (= :idle (:slave/status slave)) "Status should be :idle")
            (is (= 1 (:slave/depth slave)) "Depth should be 1")
            (is (= "/tmp/project" (:slave/cwd slave)) "CWD should match")
            (is (= "test-project" (:slave/project-id slave)) "Project ID should match")))))))

(deftest ling-spawn-with-task
  (testing "spawn! dispatches initial task when provided"
    (let [ling (ling/->ling "spawn-task-test"
                            {:cwd "/tmp/project"
                             :project-id "test-project"})]
      (with-redefs [ec/eval-elisp-with-timeout
                    (fn [_code _timeout]
                      (mock-elisp-success "spawn-task-test"))]
        (proto/spawn! ling {:task "Explore the codebase"})

        ;; Verify slave was registered
        (let [slave (ds-queries/get-slave "spawn-task-test")]
          (is (some? slave) "Slave should exist")
          ;; Status should be :working after dispatch
          (is (= :working (:slave/status slave))
              "Status should be :working after task dispatch"))))))

(deftest ling-spawn-failure-throws
  (testing "spawn! throws on elisp failure"
    (let [ling (ling/->ling "spawn-fail-test" {:cwd "/tmp"})]
      (with-redefs [ec/eval-elisp-with-timeout
                    (fn [_code _timeout]
                      (mock-elisp-failure "Emacs not running"))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Failed to spawn ling"
                              (proto/spawn! ling {}))
            "Should throw on elisp failure")))))

(deftest ling-spawn-with-parent
  (testing "spawn! sets parent relationship"
    (let [parent-ling (ling/->ling "parent-ling" {:cwd "/tmp"})
          child-ling (ling/->ling "child-ling" {:cwd "/tmp"})]
      (with-redefs [ec/eval-elisp-with-timeout
                    (fn [_code _timeout]
                      (mock-elisp-success "ok"))]
        ;; Spawn parent first
        (proto/spawn! parent-ling {:depth 1})
        ;; Spawn child with parent
        (proto/spawn! child-ling {:depth 2 :parent "parent-ling"})

        (let [child (ds-queries/get-slave "child-ling")]
          (is (= 2 (:slave/depth child)) "Child depth should be 2")
          (is (= "parent-ling" (:slave/parent child))
              "Parent should be set correctly"))))))

(deftest ling-spawn-with-kanban-task
  (testing "spawn! associates with kanban task"
    (let [ling (ling/->ling "kanban-ling" {:cwd "/tmp"})]
      (with-redefs [ec/eval-elisp-with-timeout
                    (fn [_code _timeout]
                      (mock-elisp-success "kanban-ling"))]
        (proto/spawn! ling {:kanban-task-id "TASK-123"})

        (let [slave (ds-queries/get-slave "kanban-ling")]
          (is (= "TASK-123" (:slave/kanban-task-id slave))
              "Kanban task ID should be set"))))))

;; =============================================================================
;; Section 3: dispatch! Tests
;; =============================================================================

(deftest ling-dispatch-creates-task
  (testing "dispatch! creates task in DataScript"
    ;; First register the ling in DataScript
    (ds-lings/add-slave! "dispatch-test-ling" {:status :idle :cwd "/tmp"})

    (let [ling (ling/->ling "dispatch-test-ling" {:cwd "/tmp"})]
      (let [task-id (proto/dispatch! ling {:task "Fix the bug"})]
        ;; Task ID should be returned
        (is (string? task-id) "Should return task ID")
        (is (re-matches #"task-\d+-dispatch.*" task-id)
            "Task ID should match expected format")

        ;; Verify slave status updated
        (let [slave (ds-queries/get-slave "dispatch-test-ling")]
          (is (= :working (:slave/status slave))
              "Status should be :working after dispatch"))))))

(deftest ling-dispatch-with-files
  (testing "dispatch! handles file claims"
    (ds-lings/add-slave! "dispatch-files-ling" {:status :idle :cwd "/tmp"})

    (let [ling (ling/->ling "dispatch-files-ling" {:cwd "/tmp"})]
      (proto/dispatch! ling {:task "Fix the bug"
                             :files ["src/core.clj" "src/util.clj"]})

      ;; Verify status updated
      (let [slave (ds-queries/get-slave "dispatch-files-ling")]
        (is (= :working (:slave/status slave)))))))

(deftest ling-dispatch-with-priority
  (testing "dispatch! accepts priority option"
    (ds-lings/add-slave! "priority-ling" {:status :idle})

    (let [ling (ling/->ling "priority-ling" {})]
      ;; Should not throw with priority option
      (let [task-id (proto/dispatch! ling {:task "Urgent fix"
                                           :priority :high})]
        (is (string? task-id))))))

;; =============================================================================
;; Section 4: status Tests
;; =============================================================================

(deftest ling-status-from-datascript
  (testing "status returns DataScript state"
    (ds-lings/add-slave! "status-ling"
                         {:status :working
                          :cwd "/home/test"
                          :name "test-worker"})

    (let [ling (ling/->ling "status-ling" {:cwd "/home/test"})
          status (proto/status ling)]
      (is (map? status) "Status should be a map")
      (is (= "status-ling" (:slave/id status)) "ID should match")
      (is (= :working (:slave/status status)) "Status should be :working"))))

(deftest ling-status-unregistered-returns-nil-or-elisp
  (testing "status for unregistered ling queries elisp"
    (let [ling (ling/->ling "unregistered-ling" {})]
      (with-redefs [ec/eval-elisp-with-timeout
                    (fn [_code _timeout]
                      (mock-elisp-success "nil"))]
        (let [status (proto/status ling)]
          ;; May return nil or elisp-based status
          (or (nil? status)
              (= :dead (:slave/status status))
              (= :unknown (:slave/status status))))))))

;; =============================================================================
;; Section 5: kill! Tests (Critical Ops Guard)
;; =============================================================================

(deftest ling-kill-success
  (testing "kill! removes ling and releases resources"
    (ds-lings/add-slave! "kill-test-ling" {:status :idle})

    (let [ling (ling/->ling "kill-test-ling" {})]
      (with-redefs [ec/eval-elisp-with-timeout
                    (fn [_code _timeout]
                      (mock-elisp-success "killed"))]
        (let [result (proto/kill! ling)]
          (is (map? result) "kill! should return a map")
          (is (true? (:killed? result)) "Should indicate success")
          (is (= "kill-test-ling" (:id result)))

          ;; Verify removed from DataScript
          (is (nil? (ds-queries/get-slave "kill-test-ling"))
              "Slave should be removed from DataScript"))))))

(deftest ling-kill-blocked-by-critical-op
  (testing "kill! blocked when critical ops in progress"
    (ds-lings/add-slave! "critical-ling" {:status :working})
    ;; Enter critical operation
    (ds-lings/enter-critical-op! "critical-ling" :wrap)

    (let [ling (ling/->ling "critical-ling" {})]
      (let [result (proto/kill! ling)]
        (is (map? result))
        (is (false? (:killed? result)) "Should not be killed")
        (is (= :critical-ops-blocking (:reason result)))
        (is (contains? (:blocking-ops result) :wrap))

        ;; Verify still exists
        (is (some? (ds-queries/get-slave "critical-ling"))
            "Slave should still exist")))))

(deftest ling-kill-releases-claims
  (testing "kill! releases all file claims"
    (ds-lings/add-slave! "claims-ling" {:status :working})
    ;; Add some claims
    (ds-lings/claim-file! "/tmp/file1.clj" "claims-ling")
    (ds-lings/claim-file! "/tmp/file2.clj" "claims-ling")

    ;; Verify claims exist
    (is (= 2 (count (filter #(= "claims-ling" (:slave-id %))
                            (ds-queries/get-all-claims)))))

    (let [ling (ling/->ling "claims-ling" {})]
      (with-redefs [ec/eval-elisp-with-timeout
                    (fn [_code _timeout]
                      (mock-elisp-success "killed"))]
        (proto/kill! ling)

        ;; Verify claims released
        (is (empty? (filter #(= "claims-ling" (:slave-id %))
                            (ds-queries/get-all-claims)))
            "All claims should be released")))))

;; =============================================================================
;; Section 6: claims Tests
;; =============================================================================

(deftest ling-claims-returns-claimed-files
  (testing "claims returns list of claimed files"
    (ds-lings/add-slave! "claims-test-ling" {:status :working})
    (ds-lings/claim-file! "/src/core.clj" "claims-test-ling")
    (ds-lings/claim-file! "/src/util.clj" "claims-test-ling")

    (let [ling (ling/->ling "claims-test-ling" {})
          claims (proto/claims ling)]
      (is (vector? claims) "claims should return a vector")
      (is (= 2 (count claims)) "Should have 2 claims")
      (is (contains? (set claims) "/src/core.clj"))
      (is (contains? (set claims) "/src/util.clj")))))

(deftest ling-claims-empty-when-none
  (testing "claims returns empty vector when no claims"
    (ds-lings/add-slave! "no-claims-ling" {:status :idle})

    (let [ling (ling/->ling "no-claims-ling" {})
          claims (proto/claims ling)]
      (is (vector? claims))
      (is (empty? claims)))))

;; =============================================================================
;; Section 7: claim-files! Tests
;; =============================================================================

(deftest ling-claim-files-success
  (testing "claim-files! creates claims for files"
    (ds-lings/add-slave! "claim-files-ling" {:status :working})

    (let [ling (ling/->ling "claim-files-ling" {})
          files ["/src/a.clj" "/src/b.clj"]]
      (proto/claim-files! ling files "task-123")

      ;; Verify claims created
      (let [claims (proto/claims ling)]
        (is (= 2 (count claims)))
        (is (= (set files) (set claims)))))))

(deftest ling-claim-files-conflict-detection
  (testing "claim-files! detects conflicts with other agents"
    ;; Register two lings
    (ds-lings/add-slave! "ling-a" {:status :working})
    (ds-lings/add-slave! "ling-b" {:status :working})

    ;; Ling A claims a file
    (ds-lings/claim-file! "/shared/file.clj" "ling-a" "task-a")

    ;; Ling B tries to claim the same file
    (let [ling-b (ling/->ling "ling-b" {})]
      (proto/claim-files! ling-b ["/shared/file.clj"] "task-b")

      ;; File should still be owned by ling-a (conflict logged)
      (let [claim-info (ds-queries/get-claims-for-file "/shared/file.clj")]
        (is (= "ling-a" (:slave-id claim-info))
            "Original claim should remain")))))

;; =============================================================================
;; Section 8: release-claims! Tests
;; =============================================================================

(deftest ling-release-claims-success
  (testing "release-claims! releases all claims"
    (ds-lings/add-slave! "release-ling" {:status :working})
    (ds-lings/claim-file! "/src/x.clj" "release-ling")
    (ds-lings/claim-file! "/src/y.clj" "release-ling")

    (let [ling (ling/->ling "release-ling" {})]
      ;; Verify claims exist
      (is (= 2 (count (proto/claims ling))))

      ;; Release all
      (let [released-count (proto/release-claims! ling)]
        (is (= 2 released-count) "Should return count of released claims")
        (is (empty? (proto/claims ling)) "Claims should be empty")))))

(deftest ling-release-claims-when-none
  (testing "release-claims! returns 0 when no claims"
    (ds-lings/add-slave! "no-release-ling" {:status :idle})

    (let [ling (ling/->ling "no-release-ling" {})
          released-count (proto/release-claims! ling)]
      (is (= 0 released-count)))))

;; =============================================================================
;; Section 9: upgrade! Tests
;; =============================================================================

(deftest ling-upgrade-is-noop
  (testing "upgrade! returns nil for lings (already full capability)"
    (let [ling (ling/->ling "upgrade-ling" {})]
      (is (nil? (proto/upgrade! ling))
          "Ling upgrade! should be no-op"))))

;; =============================================================================
;; Section 10: Query Function Tests
;; =============================================================================

(deftest get-ling-returns-record
  (testing "get-ling reconstitutes Ling from DataScript"
    (ds-lings/add-slave! "query-ling"
                         {:status :idle
                          :cwd "/home/user/project"
                          :presets ["tdd" "clarity"]
                          :project-id "my-project"})

    (let [ling (ling/get-ling "query-ling")]
      (is (some? ling) "Should return a ling")
      (is (instance? hive_mcp.agent.ling.spawn.Ling ling))
      (is (= "query-ling" (:id ling)))
      (is (= "/home/user/project" (:cwd ling)))
      (is (= "my-project" (:project-id ling))))))

(deftest get-ling-returns-nil-for-missing
  (testing "get-ling returns nil for non-existent ling"
    (is (nil? (ling/get-ling "nonexistent-ling")))))

(deftest list-lings-returns-all
  (testing "list-lings returns all registered lings"
    ;; Add some lings (depth 1)
    (ds-lings/add-slave! "ling-1" {:status :idle :depth 1})
    (ds-lings/add-slave! "ling-2" {:status :working :depth 1})
    ;; Add a drone (depth 2) - should be filtered out
    (ds-lings/add-slave! "drone-1" {:status :idle :depth 2})

    (let [lings (ling/list-lings)]
      (is (= 2 (count lings)) "Should return only lings (depth 1)")
      (is (every? #(instance? hive_mcp.agent.ling.spawn.Ling %) lings)
          "All results should be Ling records"))))

(deftest list-lings-filters-by-project
  (testing "list-lings filters by project-id"
    (ds-lings/add-slave! "proj-a-ling" {:status :idle :depth 1 :project-id "project-a"})
    (ds-lings/add-slave! "proj-b-ling" {:status :idle :depth 1 :project-id "project-b"})

    (let [project-a-lings (ling/list-lings "project-a")]
      (is (= 1 (count project-a-lings)))
      (is (= "proj-a-ling" (:id (first project-a-lings)))))))

(deftest get-ling-for-task-finds-assignment
  (testing "get-ling-for-task finds ling by kanban task ID"
    (ds-lings/add-slave! "task-assigned-ling"
                         {:status :working
                          :depth 1
                          :kanban-task-id "TASK-456"})

    (let [ling (ling/get-ling-for-task "TASK-456")]
      (is (some? ling))
      (is (= "task-assigned-ling" (:id ling))))))

(deftest get-ling-for-task-returns-nil-when-none
  (testing "get-ling-for-task returns nil when no ling assigned"
    (is (nil? (ling/get-ling-for-task "NONEXISTENT-TASK")))))

;; =============================================================================
;; Section 11: Critical Operation Guard Tests
;; =============================================================================

(deftest with-critical-op-protects-ling
  (testing "with-critical-op prevents kill during operation"
    (ds-lings/add-slave! "guarded-ling" {:status :working})

    (let [ling (ling/->ling "guarded-ling" {})]
      ;; Start critical operation
      (ling/with-critical-op "guarded-ling" :wrap
        ;; Try to kill during critical op
        (let [{:keys [can-kill? blocking-ops]} (ds-lings/can-kill? "guarded-ling")]
          (is (false? can-kill?) "Should not be killable")
          (is (contains? blocking-ops :wrap))))

      ;; After critical op exits, should be killable
      (let [{:keys [can-kill?]} (ds-lings/can-kill? "guarded-ling")]
        (is (true? can-kill?) "Should be killable after critical op")))))

(comment
  ;; Run tests
  (clojure.test/run-tests 'hive-mcp.agent.ling-test)

  ;; Run single test
  (clojure.test/test-vars [#'ling-spawn-success]))
