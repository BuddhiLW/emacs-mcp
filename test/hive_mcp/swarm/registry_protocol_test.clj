(ns hive-mcp.swarm.registry-protocol-test
  "TDD tests for ISwarmRegistry protocol.

   Tests the protocol contract independent of implementation details.
   Uses DataScriptRegistry as the reference implementation.

   Coverage:
   - Slave CRUD operations via protocol
   - Task CRUD operations via protocol
   - Query operations (by status, by project)
   - Contract behaviors (nil returns, idempotency)

   CLARITY-L: Tests protocol boundary, not implementation internals."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.swarm.protocol :as proto]
            [hive-mcp.swarm.datascript.registry :as registry]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *registry* nil)

(defn- registry-fixture
  "Bind *registry* to the default DS registry for each test."
  [f]
  (binding [*registry* (registry/get-default-registry)]
    (f)))

(use-fixtures :each
  (iso/with-isolations :swarm-ds)
  registry-fixture)

(defn gen-slave-id []
  (str "test-slave-" (java.util.UUID/randomUUID)))

(defn gen-task-id []
  (str "test-task-" (java.util.UUID/randomUUID)))

;; =============================================================================
;; Slave Operations - Protocol Contract Tests
;; =============================================================================

(deftest add-slave!-creates-entity-test
  (testing "add-slave! creates a slave with given id"
    (let [slave-id (gen-slave-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (let [slave (proto/get-slave *registry* slave-id)]
        (is (= slave-id (:slave/id slave)))
        (is (= :idle (:slave/status slave)))))))

(deftest add-slave!-with-all-options-test
  (testing "add-slave! accepts all documented options"
    (let [parent-id (gen-slave-id)
          slave-id (gen-slave-id)
          opts {:name "test-worker"
                :status :working
                :depth 2
                :parent parent-id
                :presets ["tdd" "clojure"]
                :cwd "/home/user/project"
                :project-id "proj-123"}]
      ;; Create parent first (DataScript requires referenced entities to exist)
      (proto/add-slave! *registry* parent-id {:status :idle :depth 1})
      (proto/add-slave! *registry* slave-id opts)
      (let [slave (proto/get-slave *registry* slave-id)]
        (is (= "test-worker" (:slave/name slave)))
        (is (= :working (:slave/status slave)))
        (is (= 2 (:slave/depth slave)))
        (is (= parent-id (:slave/parent slave)))
        (is (contains? (:slave/presets slave) "tdd"))
        (is (= "/home/user/project" (:slave/cwd slave)))
        (is (= "proj-123" (:slave/project-id slave)))))))

(deftest get-slave-returns-nil-for-nonexistent-test
  (testing "get-slave returns nil for non-existent slave"
    (is (nil? (proto/get-slave *registry* "nonexistent-slave-id")))))

(deftest update-slave!-modifies-attributes-test
  (testing "update-slave! changes slave attributes"
    (let [slave-id (gen-slave-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (proto/update-slave! *registry* slave-id {:slave/status :working})
      (let [slave (proto/get-slave *registry* slave-id)]
        (is (= :working (:slave/status slave)))))))

(deftest update-slave!-returns-nil-for-nonexistent-test
  (testing "update-slave! returns nil for non-existent slave"
    (is (nil? (proto/update-slave! *registry* "nonexistent" {:slave/status :idle})))))

(deftest remove-slave!-deletes-entity-test
  (testing "remove-slave! removes slave from registry"
    (let [slave-id (gen-slave-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (proto/remove-slave! *registry* slave-id)
      (is (nil? (proto/get-slave *registry* slave-id))))))

(deftest remove-slave!-returns-nil-for-nonexistent-test
  (testing "remove-slave! returns nil for non-existent slave"
    (is (nil? (proto/remove-slave! *registry* "nonexistent")))))

(deftest get-all-slaves-returns-all-test
  (testing "get-all-slaves returns all registered slaves"
    (let [slave1-id (gen-slave-id)
          slave2-id (gen-slave-id)
          slave3-id (gen-slave-id)]
      (proto/add-slave! *registry* slave1-id {:status :idle})
      (proto/add-slave! *registry* slave2-id {:status :working})
      (proto/add-slave! *registry* slave3-id {:status :blocked})
      (let [slaves (proto/get-all-slaves *registry*)]
        (is (= 3 (count slaves)))))))

(deftest get-all-slaves-returns-empty-when-none-test
  (testing "get-all-slaves returns empty seq when no slaves"
    (let [slaves (proto/get-all-slaves *registry*)]
      (is (empty? slaves)))))

(deftest get-slaves-by-status-filters-correctly-test
  (testing "get-slaves-by-status returns only matching slaves"
    (let [slave1-id (gen-slave-id)
          slave2-id (gen-slave-id)
          slave3-id (gen-slave-id)]
      (proto/add-slave! *registry* slave1-id {:status :idle})
      (proto/add-slave! *registry* slave2-id {:status :idle})
      (proto/add-slave! *registry* slave3-id {:status :working})
      (let [idle-slaves (proto/get-slaves-by-status *registry* :idle)]
        (is (= 2 (count idle-slaves)))
        (is (every? #(= :idle (:slave/status %)) idle-slaves))))))

(deftest get-slaves-by-status-returns-empty-for-no-match-test
  (testing "get-slaves-by-status returns empty for unmatched status"
    (let [slave-id (gen-slave-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (let [busy-slaves (proto/get-slaves-by-status *registry* :working)]
        (is (empty? busy-slaves))))))

(deftest get-slaves-by-project-filters-correctly-test
  (testing "get-slaves-by-project returns only matching project slaves"
    (let [slave1-id (gen-slave-id)
          slave2-id (gen-slave-id)
          slave3-id (gen-slave-id)]
      (proto/add-slave! *registry* slave1-id {:status :idle :project-id "proj-A"})
      (proto/add-slave! *registry* slave2-id {:status :idle :project-id "proj-A"})
      (proto/add-slave! *registry* slave3-id {:status :idle :project-id "proj-B"})
      (let [proj-a-slaves (proto/get-slaves-by-project *registry* "proj-A")]
        (is (= 2 (count proj-a-slaves)))
        (is (every? #(= "proj-A" (:slave/project-id %)) proj-a-slaves))))))

;; =============================================================================
;; Task Operations - Protocol Contract Tests
;; =============================================================================

(deftest add-task!-creates-task-test
  (testing "add-task! creates a task for a slave"
    (let [slave-id (gen-slave-id)
          task-id (gen-task-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (proto/add-task! *registry* task-id slave-id {:status :dispatched})
      (let [task (proto/get-task *registry* task-id)]
        (is (= task-id (:task/id task)))
        (is (= :dispatched (:task/status task)))))))

(deftest add-task!-with-all-options-test
  (testing "add-task! accepts all documented options"
    (let [slave-id (gen-slave-id)
          task-id (gen-task-id)
          opts {:status :queued
                :prompt "Implement feature X"
                :files ["/src/a.clj" "/src/b.clj"]}]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (proto/add-task! *registry* task-id slave-id opts)
      (let [task (proto/get-task *registry* task-id)]
        (is (= :queued (:task/status task)))
        (is (= "Implement feature X" (:task/prompt task)))))))

(deftest get-task-returns-nil-for-nonexistent-test
  (testing "get-task returns nil for non-existent task"
    (is (nil? (proto/get-task *registry* "nonexistent-task-id")))))

(deftest update-task!-modifies-attributes-test
  (testing "update-task! changes task attributes"
    (let [slave-id (gen-slave-id)
          task-id (gen-task-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (proto/add-task! *registry* task-id slave-id {:status :dispatched})
      (proto/update-task! *registry* task-id {:task/status :completed})
      (let [task (proto/get-task *registry* task-id)]
        (is (= :completed (:task/status task)))))))

(deftest update-task!-returns-nil-for-nonexistent-test
  (testing "update-task! returns nil for non-existent task"
    (is (nil? (proto/update-task! *registry* "nonexistent" {:task/status :completed})))))

(deftest get-tasks-for-slave-returns-all-tasks-test
  (testing "get-tasks-for-slave returns all tasks for a slave"
    (let [slave-id (gen-slave-id)
          task1-id (gen-task-id)
          task2-id (gen-task-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (proto/add-task! *registry* task1-id slave-id {:status :dispatched})
      (proto/add-task! *registry* task2-id slave-id {:status :queued})
      (let [tasks (proto/get-tasks-for-slave *registry* slave-id)]
        (is (= 2 (count tasks)))))))

(deftest get-tasks-for-slave-with-status-filters-test
  (testing "get-tasks-for-slave with status filters tasks"
    (let [slave-id (gen-slave-id)
          task1-id (gen-task-id)
          task2-id (gen-task-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (proto/add-task! *registry* task1-id slave-id {:status :dispatched})
      (proto/add-task! *registry* task2-id slave-id {:status :completed})
      (let [dispatched-tasks (proto/get-tasks-for-slave *registry* slave-id :dispatched)]
        (is (= 1 (count dispatched-tasks)))
        (is (= :dispatched (:task/status (first dispatched-tasks))))))))

(deftest get-tasks-for-slave-returns-empty-for-no-tasks-test
  (testing "get-tasks-for-slave returns empty when slave has no tasks"
    (let [slave-id (gen-slave-id)]
      (proto/add-slave! *registry* slave-id {:status :idle})
      (let [tasks (proto/get-tasks-for-slave *registry* slave-id)]
        (is (empty? tasks))))))

;; =============================================================================
;; Protocol Wrapper Functions Tests
;; =============================================================================

(deftest wrapper-functions-delegate-correctly-test
  (testing "Wrapper functions delegate to protocol methods"
    (let [slave-id (gen-slave-id)
          task-id (gen-task-id)]
      ;; Test proto/add-slave!*
      (proto/add-slave!* *registry* slave-id {:status :idle :name "wrapper-test"})
      ;; Test proto/get-slave*
      (let [slave (proto/get-slave* *registry* slave-id)]
        (is (= "wrapper-test" (:slave/name slave))))
      ;; Test proto/update-slave!*
      (proto/update-slave!* *registry* slave-id {:slave/status :working})
      (is (= :working (:slave/status (proto/get-slave* *registry* slave-id))))
      ;; Test proto/get-all-slaves*
      (is (= 1 (count (proto/get-all-slaves* *registry*))))
      ;; Test proto/add-task!*
      (proto/add-task!* *registry* task-id slave-id {:status :dispatched})
      ;; Test proto/get-task*
      (is (some? (proto/get-task* *registry* task-id)))
      ;; Test proto/get-tasks-for-slave*
      (is (= 1 (count (proto/get-tasks-for-slave* *registry* slave-id))))
      ;; Test proto/remove-slave!*
      (proto/remove-slave!* *registry* slave-id)
      (is (nil? (proto/get-slave* *registry* slave-id))))))

;; =============================================================================
;; Status Constants Tests
;; =============================================================================

(deftest slave-statuses-defined-test
  (testing "slave-statuses contains expected values"
    (is (contains? proto/slave-statuses :idle))
    (is (contains? proto/slave-statuses :spawning))
    (is (contains? proto/slave-statuses :starting))
    (is (contains? proto/slave-statuses :working))
    (is (contains? proto/slave-statuses :blocked))
    (is (contains? proto/slave-statuses :error))
    (is (contains? proto/slave-statuses :terminated))))

(deftest task-statuses-defined-test
  (testing "task-statuses contains expected values"
    (is (contains? proto/task-statuses :dispatched))
    (is (contains? proto/task-statuses :completed))
    (is (contains? proto/task-statuses :error))
    (is (contains? proto/task-statuses :timeout))
    (is (contains? proto/task-statuses :queued))))
