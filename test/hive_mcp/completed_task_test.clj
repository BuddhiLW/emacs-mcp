(ns hive-mcp.completed-task-test
  "TDD tests for completed-task registry in DataScript.
   
   Tests:
   - register-completed-task! - capturing task completions
   - get-completed-tasks-this-session - retrieving session tasks
   - clear-completed-tasks! - cleanup
   
   CLARITY: TDD approach - tests written first."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.test-fixtures :as tf]
            [hive-test.isolation :as iso]
            hive-mcp.isolation-methods))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each (iso/with-isolations :swarm-ds))

;; =============================================================================
;; register-completed-task! Tests
;; =============================================================================

(deftest register-completed-task!-basic-test
  (testing "registers a completed task with required fields"
    (let [result (ds/register-completed-task! 
                   "task-123" 
                   {:title "Implement feature X"
                    :agent-id "swarm-ling-abc"})]
      (is (some? result) "should return transaction report")
      (is (map? result) "should be a map")))

  (testing "registers task with minimal fields"
    (tf/reset-isolated-swarm!)
    (let [result (ds/register-completed-task! "task-456" {})]
      (is (some? result) "should allow minimal registration"))))

(deftest register-completed-task!-auto-timestamp-test
  (testing "auto-generates completed-at timestamp"
    (ds/register-completed-task! "task-789" {:title "Test task"})
    (let [task (ds/get-completed-task "task-789")]
      (is (some? (:completed-task/completed-at task)) 
          "should have auto-generated timestamp")
      (is (instance? java.util.Date (:completed-task/completed-at task))
          "timestamp should be a Date"))))

(deftest register-completed-task!-agent-id-test
  (testing "stores agent-id correctly"
    (ds/register-completed-task! 
      "task-agent" 
      {:title "Agent task" 
       :agent-id "swarm-ling-xyz-123"})
    (let [task (ds/get-completed-task "task-agent")]
      (is (= "swarm-ling-xyz-123" (:completed-task/agent-id task))))))

(deftest register-completed-task!-duplicate-handling-test
  (testing "allows registering same task-id (upsert behavior)"
    (ds/register-completed-task! "task-dup" {:title "First"})
    (ds/register-completed-task! "task-dup" {:title "Second"})
    (let [task (ds/get-completed-task "task-dup")]
      ;; Due to :db/unique :db.unique/identity, this is an upsert
      (is (= "Second" (:completed-task/title task))
          "should update title on duplicate"))))

;; =============================================================================
;; get-completed-task Tests
;; =============================================================================

(deftest get-completed-task-basic-test
  (testing "retrieves task by ID"
    (ds/register-completed-task! "task-get" {:title "Get me"})
    (let [task (ds/get-completed-task "task-get")]
      (is (some? task) "should find task")
      (is (= "task-get" (:completed-task/id task)))
      (is (= "Get me" (:completed-task/title task)))))

  (testing "returns nil for non-existent task"
    (is (nil? (ds/get-completed-task "non-existent")))))

;; =============================================================================
;; get-completed-tasks-this-session Tests
;; =============================================================================

(deftest get-completed-tasks-this-session-empty-test
  (testing "returns empty seq when no tasks registered"
    (let [tasks (ds/get-completed-tasks-this-session)]
      (is (empty? tasks) "should be empty initially"))))

(deftest get-completed-tasks-this-session-multiple-test
  (testing "returns all registered tasks"
    (ds/register-completed-task! "task-1" {:title "Task 1" :agent-id "ling-1"})
    (ds/register-completed-task! "task-2" {:title "Task 2" :agent-id "ling-1"})
    (ds/register-completed-task! "task-3" {:title "Task 3" :agent-id "ling-2"})
    (let [tasks (ds/get-completed-tasks-this-session)]
      (is (= 3 (count tasks)) "should return all 3 tasks")
      (is (every? #(:completed-task/id %) tasks) 
          "all should have IDs"))))

(deftest get-completed-tasks-this-session-sorted-test
  (testing "returns tasks sorted by completion time (most recent first)"
    (ds/register-completed-task! "task-old" {:title "Old"})
    (Thread/sleep 10)  ;; Small delay to ensure different timestamps
    (ds/register-completed-task! "task-new" {:title "New"})
    (let [tasks (ds/get-completed-tasks-this-session)]
      (is (= "task-new" (:completed-task/id (first tasks)))
          "most recent should be first"))))

(deftest get-completed-tasks-this-session-agent-filter-test
  (testing "can filter by agent-id"
    (ds/register-completed-task! "t1" {:title "T1" :agent-id "ling-1"})
    (ds/register-completed-task! "t2" {:title "T2" :agent-id "ling-2"})
    (ds/register-completed-task! "t3" {:title "T3" :agent-id "ling-1"})
    (let [ling1-tasks (ds/get-completed-tasks-this-session {:agent-id "ling-1"})
          ling2-tasks (ds/get-completed-tasks-this-session {:agent-id "ling-2"})]
      (is (= 2 (count ling1-tasks)) "ling-1 should have 2 tasks")
      (is (= 1 (count ling2-tasks)) "ling-2 should have 1 task"))))

;; =============================================================================
;; clear-completed-tasks! Tests
;; =============================================================================

(deftest clear-completed-tasks!-basic-test
  (testing "clears all completed tasks"
    (ds/register-completed-task! "ct1" {:title "C1"})
    (ds/register-completed-task! "ct2" {:title "C2"})
    (is (= 2 (count (ds/get-completed-tasks-this-session))))
    
    (let [cleared (ds/clear-completed-tasks!)]
      (is (= 2 cleared) "should return count of cleared tasks")
      (is (empty? (ds/get-completed-tasks-this-session)) 
          "should be empty after clear"))))

(deftest clear-completed-tasks!-empty-test
  (testing "handles empty state gracefully"
    (let [cleared (ds/clear-completed-tasks!)]
      (is (= 0 cleared) "should return 0 when nothing to clear"))))

(deftest clear-completed-tasks!-idempotent-test
  (testing "multiple clears are safe"
    (ds/register-completed-task! "ct" {:title "C"})
    (ds/clear-completed-tasks!)
    (let [second-clear (ds/clear-completed-tasks!)]
      (is (= 0 second-clear) "second clear should return 0"))))

;; =============================================================================
;; Integration with db-stats Tests
;; =============================================================================

(deftest db-stats-includes-completed-tasks-test
  (testing "db-stats includes completed-tasks count"
    (ds/register-completed-task! "st1" {:title "Stats task"})
    (ds/register-completed-task! "st2" {:title "Stats task 2"})
    (let [stats (ds/db-stats)]
      (is (contains? stats :completed-tasks) 
          "stats should include :completed-tasks")
      (is (= 2 (:completed-tasks stats))
          "should count completed tasks"))))
