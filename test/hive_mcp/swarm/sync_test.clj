(ns hive-mcp.swarm.sync-test
  "Tests for swarm sync module, especially Layer 4 hook integration.

   Layer 4 of 4-layer convergence pattern:
   - ARCHITECTURAL GUARANTEE that task completion emits a shout
   - Even if ling forgot to call hivemind_shout, synthetic shout fires
   - Ensures coordinator always sees task completion"
  (:require [clojure.test :refer [deftest testing is use-fixtures run-tests]]
            [hive-mcp.swarm.sync :as sync]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.hooks.core :as hooks]
            [hive-mcp.hivemind.core :as hivemind]))

;; =============================================================================
;; Test State
;; =============================================================================

(def ^:dynamic *shouts* nil)

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-state-fixture
  "Reset database and capture shouts."
  [f]
  (ds/reset-conn!)
  (binding [*shouts* (atom [])]
    ;; Capture shouts by redefining hivemind/shout!
    (with-redefs [hive-mcp.hivemind.core/shout!
                  (fn [agent-id event-type data]
                    (swap! *shouts* conj {:agent-id agent-id
                                          :event-type event-type
                                          :data data}))]
      (f))))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; Layer 4: Synthetic Shout Tests
;; =============================================================================

(deftest layer4-synthetic-shout-on-task-complete-test
  (testing "Task completion triggers synthetic shout (Layer 4 guarantee)"
    ;; Setup: Add a slave and task to DataScript
    (ds/add-slave! "test-ling-1" {:status :working :name "test" :depth 1})
    (ds/add-task! "task-123" "test-ling-1" {:status :dispatched :files []})

    ;; Ensure hooks registry exists via injection
    (when-not (sync/get-hooks-registry)
      (sync/set-hooks-registry! (hooks/create-registry)))

    ;; Simulate task completion event (as would come from channel)
    (#'sync/handle-task-completed {:task-id "task-123"
                                   :slave-id "test-ling-1"})

    ;; Verify: Synthetic shout was emitted
    (is (seq @*shouts*)
        "At least one shout should be emitted on task completion")

    (let [shout (first @*shouts*)]
      (is (= "test-ling-1" (:agent-id shout))
          "Shout should be from the completing ling")
      (is (= :completed (:event-type shout))
          "Shout event type should be :completed")
      (is (= "layer4-synthetic" (get-in shout [:data :source]))
          "Shout should be marked as synthetic from Layer 4"))))

(deftest layer4-shout-includes-task-id-test
  (testing "Synthetic shout includes task-id for traceability"
    (ds/add-slave! "ling-abc" {:status :working :name "abc" :depth 1})
    (ds/add-task! "task-xyz-789" "ling-abc" {:status :dispatched :files []})

    (when-not (sync/get-hooks-registry)
      (sync/set-hooks-registry! (hooks/create-registry)))

    (#'sync/handle-task-completed {:task-id "task-xyz-789"
                                   :slave-id "ling-abc"})

    (let [shout (first @*shouts*)]
      (is (= "task-xyz-789" (get-in shout [:data :task-id]))
          "Shout data should include task-id"))))

(deftest layer4-works-without-registry-test
  (testing "Handle task completion gracefully when no registry"
    ;; This ensures the architectural guarantee doesn't crash
    ;; even if hooks system isn't fully initialized
    (ds/add-slave! "orphan-ling" {:status :working :name "orphan" :depth 1})
    (ds/add-task! "orphan-task" "orphan-ling" {:status :dispatched :files []})

    ;; Temporarily nil out registry via injection
    (let [orig-registry (sync/get-hooks-registry)]
      (sync/set-hooks-registry! nil)

      (try
        ;; Should not throw
        (#'sync/handle-task-completed {:task-id "orphan-task"
                                       :slave-id "orphan-ling"})
        ;; Verify task was still completed in DataScript
        (is (= :completed (:task/status (ds/get-task "orphan-task")))
            "Task should be marked complete even without hooks")
        (finally
          (sync/set-hooks-registry! orig-registry))))))

;; =============================================================================
;; Integration with DataScript
;; =============================================================================

(deftest task-completion-updates-datascript-test
  (testing "Handle-task-completed updates DataScript status"
    (ds/add-slave! "ds-ling" {:status :working :name "ds" :depth 1})
    (ds/add-task! "ds-task-1" "ds-ling" {:status :dispatched :files ["/a.clj"]})
    ;; claim-file! takes an opts map (not a bare task-id string). Passing a
    ;; string silently destructures to {:task-id nil}, so the claim is created
    ;; with no :claim/task ref and release-claims-for-task! can't find it.
    (ds/claim-file! "/a.clj" "ds-ling" {:task-id "ds-task-1"})

    (when-not (sync/get-hooks-registry)
      (sync/set-hooks-registry! (hooks/create-registry)))

    (#'sync/handle-task-completed {:task-id "ds-task-1"
                                   :slave-id "ds-ling"})

    (is (= :completed (:task/status (ds/get-task "ds-task-1")))
        "Task status should be :completed")

    ;; File claim should be released
    (is (nil? (ds/get-claims-for-file "/a.clj"))
        "File claim should be released after task completion")))

(comment
  ;; Run tests from REPL
  (run-tests 'hive-mcp.swarm.sync-test))
