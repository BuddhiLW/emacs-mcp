(ns hive-mcp.tools.wave-test
  "Unit tests for dispatch_drone_wave functionality.

   Tests:
   1. Plan creation with items
   2. Item status updates
   3. Event handler dispatch
   4. MCP tool schema validation

   TDD approach: Tests verify core functionality without external deps."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.tools.swarm.wave :as wave]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.handlers :as handlers]
            [hive-test.isolation :as iso]
            [hive-mcp.tools.swarm.wave.execution :as execution]
            hive-mcp.isolation-methods
            [clojure.java.io :as io]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each (iso/with-isolations :swarm-ds :events))

;; =============================================================================
;; Plan Creation Tests
;; =============================================================================

(deftest create-plan-test
  (testing "creates plan with items"
    (let [tasks [{:file "a.clj" :task "task a"}
                 {:file "b.clj" :task "task b"}]
          plan-id (wave/create-plan! tasks "drone-worker")]
      (is (string? plan-id))
      (is (.startsWith plan-id "plan-"))
      (is (= 2 (count (wave/get-pending-items plan-id)))))))

(deftest create-plan-with-default-preset-test
  (testing "creates plan with default preset when not specified"
    (let [tasks [{:file "test.clj" :task "test task"}]
          plan-id (wave/create-plan! tasks)]
      (is (string? plan-id))
      (let [plan (ds/get-plan plan-id)]
        (is (= "drone-worker" (:change-plan/preset plan)))))))

(deftest create-plan-with-custom-preset-test
  (testing "creates plan with custom preset"
    (let [tasks [{:file "test.clj" :task "test task"}]
          plan-id (wave/create-plan! tasks "tdd")
          plan (ds/get-plan plan-id)]
      (is (= "tdd" (:change-plan/preset plan))))))

;; =============================================================================
;; Item Status Tests
;; =============================================================================

(deftest get-pending-items-test
  (testing "returns only pending items"
    (let [tasks [{:file "a.clj" :task "task a"}
                 {:file "b.clj" :task "task b"}]
          plan-id (wave/create-plan! tasks)
          items (wave/get-pending-items plan-id)]
      (is (= 2 (count items)))
      (is (every? #(= :pending (:change-item/status %)) items)))))

(deftest update-item-status-test
  (testing "updates item status correctly"
    (let [tasks [{:file "a.clj" :task "task a"}]
          plan-id (wave/create-plan! tasks)
          item (first (wave/get-pending-items plan-id))
          item-id (:change-item/id item)]
      ;; Update to dispatched
      (ds/update-item-status! item-id :dispatched)
      (is (= 0 (count (wave/get-pending-items plan-id))))

      ;; Verify all items shows updated status
      (let [all-items (wave/get-plan-items plan-id)]
        (is (= 1 (count all-items)))
        (is (= :dispatched (:change-item/status (first all-items))))))))

(deftest item-completion-test
  (testing "marks item as completed with result"
    (let [tasks [{:file "a.clj" :task "task a"}]
          plan-id (wave/create-plan! tasks)
          item (first (wave/get-pending-items plan-id))
          item-id (:change-item/id item)]
      (ds/update-item-status! item-id :completed {:result "success"})
      (let [items (wave/get-plan-items plan-id)
            updated-item (first items)]
        (is (= :completed (:change-item/status updated-item)))
        (is (= "success" (:change-item/result updated-item)))))))

;; =============================================================================
;; Wave Creation Tests
;; =============================================================================

(deftest create-wave-test
  (testing "creates wave for plan"
    (let [tasks [{:file "a.clj" :task "task a"}]
          plan-id (wave/create-plan! tasks)
          wave-id (ds/create-wave! plan-id)]
      (is (string? wave-id))
      (is (.startsWith wave-id "wave-"))
      (let [wave (ds/get-wave wave-id)]
        (is (= :running (:wave/status wave)))
        (is (= 0 (:wave/completed-count wave)))
        (is (= 0 (:wave/failed-count wave)))))))

(deftest wave-concurrency-test
  (testing "creates wave with custom concurrency"
    (let [tasks [{:file "a.clj" :task "task a"}]
          plan-id (wave/create-plan! tasks)
          wave-id (ds/create-wave! plan-id {:concurrency 5})
          wave (ds/get-wave wave-id)]
      (is (= 5 (:wave/concurrency wave))))))

(deftest update-wave-counts-test
  (testing "updates wave counts correctly"
    (let [tasks [{:file "a.clj" :task "task a"}
                 {:file "b.clj" :task "task b"}]
          plan-id (wave/create-plan! tasks)
          wave-id (ds/create-wave! plan-id)]
      ;; Simulate item completion
      (ds/update-wave-counts! wave-id {:completed 1})
      (let [wave (ds/get-wave wave-id)]
        (is (= 1 (:wave/completed-count wave))))

      ;; Simulate another completion
      (ds/update-wave-counts! wave-id {:completed 1})
      (let [wave (ds/get-wave wave-id)]
        (is (= 2 (:wave/completed-count wave)))))))

(deftest complete-wave-test
  (testing "marks wave as completed"
    (let [tasks [{:file "a.clj" :task "task a"}]
          plan-id (wave/create-plan! tasks)
          wave-id (ds/create-wave! plan-id)]
      (ds/complete-wave! wave-id :completed)
      (let [wave (ds/get-wave wave-id)]
        (is (= :completed (:wave/status wave)))
        (is (some? (:wave/completed-at wave)))))))

(deftest complete-wave-partial-failure-test
  (testing "marks wave as partial-failure"
    (let [tasks [{:file "a.clj" :task "task a"}]
          plan-id (wave/create-plan! tasks)
          wave-id (ds/create-wave! plan-id)]
      (ds/complete-wave! wave-id :partial-failure)
      (let [wave (ds/get-wave wave-id)]
        (is (= :partial-failure (:wave/status wave)))))))

;; =============================================================================
;; Event Handler Tests
;; =============================================================================

(deftest wave-start-event-handler-test
  (testing ":wave/start event produces correct effects"
    (let [captured (atom [])
          prior    (ev/get-fx-handler :channel-publish)]
      (try
        ;; reg-fx is a GLOBAL registration and other tests' drones dispatch
        ;; :wave/start concurrently, so collect every invocation and select
        ;; this one by its own plan-id rather than reading a single slot.
        (ev/reg-fx :channel-publish (fn [data] (swap! captured conj data)))

        (ev/dispatch [:wave/start {:plan-id "plan-123"
                                   :wave-id "wave-456"
                                   :item-count 3}])

        (let [mine (first (filter #(= "plan-123" (get-in % [:data :plan-id]))
                                  @captured))]
          (is (some? mine)
              ":wave/start must publish a channel-publish effect for this plan")
          (is (= :wave-started (:event mine)))
          (is (= "plan-123" (get-in mine [:data :plan-id])))
          (is (= "wave-456" (get-in mine [:data :wave-id])))
          (is (= 3 (get-in mine [:data :item-count]))))
        (finally
          (if prior
            (ev/reg-fx :channel-publish prior)
            (ev/unreg-fx :channel-publish)))))))

(deftest wave-item-done-event-handler-test
  (testing ":wave/item-done event produces correct effects"
    (let [captured (atom nil)]
      (ev/reg-fx :channel-publish (fn [data] (reset! captured data)))

      (ev/dispatch [:wave/item-done {:item-id "item-789"
                                     :status :completed
                                     :wave-id "wave-456"}])

      (is (= :wave-item-done (:event @captured)))
      (is (= "item-789" (get-in @captured [:data :item-id])))
      (is (= :completed (get-in @captured [:data :status]))))))

(deftest wave-complete-event-handler-test
  (testing ":wave/complete event produces correct effects"
    (let [captured (atom nil)]
      (ev/reg-fx :channel-publish (fn [data] (reset! captured data)))

      (ev/dispatch [:wave/complete {:plan-id "plan-123"
                                    :wave-id "wave-456"
                                    :results {:completed 5 :failed 1}}])

      (is (= :wave-complete (:event @captured)))
      (is (= 5 (get-in @captured [:data :results :completed])))
      (is (= 1 (get-in @captured [:data :results :failed]))))))

;; =============================================================================
;; MCP Handler Tests
;; =============================================================================

(deftest handle-dispatch-drone-wave-empty-tasks-test
  (testing "returns error for empty tasks"
    (let [result (wave/handle-dispatch-drone-wave {:tasks []})]
      (is (= "text" (:type result)))
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (contains? parsed :error))))))

(deftest handle-dispatch-drone-wave-creates-plan-test
  (testing "creates plan and returns wave-id"
    ;; Stub the fn the HANDLER calls. `wave/execute-wave!` is a by-value
    ;; re-export and is not on this path at all — redefining it lets a real
    ;; wave reach a live drone backend.
    (with-redefs [execution/execute-wave-async!
                  (fn [plan-id & _]
                    {:wave-id (ds/create-wave! plan-id) :item-count 2})]
      (let [result (wave/handle-dispatch-drone-wave
                    {:tasks [{"file" "a.clj" "task" "fix bug"}
                             {"file" "b.clj" "task" "add test"}]})]
        (is (= "text" (:type result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= "dispatched" (:status parsed)))
          (is (string? (:plan_id parsed)))
          (is (string? (:wave_id parsed)))
          (is (= 2 (:item_count parsed))))))))

(deftest handle-dispatch-drone-wave-custom-preset-test
  (testing "passes custom preset"
    (with-redefs [execution/execute-wave-async!
                  (fn [plan-id & _]
                    {:wave-id (ds/create-wave! plan-id) :item-count 1})]
      (let [result (wave/handle-dispatch-drone-wave
                    {:tasks [{"file" "a.clj" "task" "task"}]
                     :preset "tdd"})
            parsed (json/read-str (:text result) :key-fn keyword)
            plan (ds/get-plan (:plan_id parsed))]
        (is (= "tdd" (:change-plan/preset plan)))))))

;; =============================================================================
;; Status Query Tests
;; =============================================================================

(deftest get-wave-status-test
  (testing "returns wave status map"
    (let [tasks [{:file "a.clj" :task "task a"}]
          plan-id (wave/create-plan! tasks)
          wave-id (ds/create-wave! plan-id)
          status (wave/get-wave-status wave-id)]
      (is (= wave-id (:wave-id status)))
      (is (= plan-id (:plan-id status)))
      (is (= :running (:status status)))
      (is (= 0 (:completed-count status)))
      (is (= 0 (:failed-count status))))))

(deftest get-plan-status-test
  (testing "returns plan status with items"
    (let [tasks [{:file "a.clj" :task "task a"}
                 {:file "b.clj" :task "task b"}]
          plan-id (wave/create-plan! tasks)
          status (wave/get-plan-status plan-id)]
      (is (= plan-id (:plan-id status)))
      (is (= :pending (:status status)))
      (is (= 2 (count (:items status)))))))

;; =============================================================================
;; P0: ensure-parent-dirs! Tests
;; =============================================================================

(deftest ensure-parent-dirs-test
  (testing "creates parent directories for all task files"
    (let [test-dir (str (System/getProperty "java.io.tmpdir")
                        "/hive-mcp-test-" (System/currentTimeMillis))
          tasks [{:file (str test-dir "/deep/nested/file1.clj") :task "task 1"}
                 {:file (str test-dir "/other/path/file2.clj") :task "task 2"}]]
      (try
        ;; Directories shouldn't exist yet
        (is (not (.exists (java.io.File. (str test-dir "/deep/nested")))))
        (is (not (.exists (java.io.File. (str test-dir "/other/path")))))

        ;; Call ensure-parent-dirs! (function under test)
        (wave/ensure-parent-dirs! tasks)

        ;; Directories should now exist
        (is (.exists (java.io.File. (str test-dir "/deep/nested"))))
        (is (.exists (java.io.File. (str test-dir "/other/path"))))
        (finally
          ;; Cleanup
          (doseq [dir [(str test-dir "/deep/nested")
                       (str test-dir "/deep")
                       (str test-dir "/other/path")
                       (str test-dir "/other")
                       test-dir]]
            (.delete (java.io.File. dir))))))))

(deftest ensure-parent-dirs-nil-file-test
  (testing "a nil :file is skipped; a real one still gets its parent created"
    (let [root  (io/file (System/getProperty "java.io.tmpdir")
                         (str "wave-parent-dirs-" (System/nanoTime)))
          child (io/file root "nested" "path.clj")
          tasks [{:file nil :task "task 1"}
                 {:file (.getPath child) :task "task 2"}]]
      (try
        (is (= 1 (wave/ensure-parent-dirs! tasks))
            "nil contributes nothing; only the one real file's parent is created")
        (is (.isDirectory (.getParentFile child))
            "the parent directory exists afterwards")
        (is (zero? (wave/ensure-parent-dirs! tasks))
            "second pass creates nothing — the parent is already there")
        (finally
          (doseq [f (reverse (file-seq root))] (.delete f)))))))

(deftest ensure-parent-dirs-existing-dirs-test
  (testing "works idempotently with existing directories"
    (let [existing-dir (System/getProperty "java.io.tmpdir")
          tasks [{:file (str existing-dir "/file.clj") :task "task 1"}]]
      ;; Should not throw on existing directory
      (is (zero? (wave/ensure-parent-dirs! tasks))))))

;; =============================================================================
;; P2: validate-task-paths Tests
;; =============================================================================

(deftest validate-task-paths-test
  (testing "fails fast with invalid paths before spawning drones"
    (let [tasks [{:file "/nonexistent/deeply/nested/path/file.clj" :task "task 1"}
                 {:file "/another/invalid/path.clj" :task "task 2"}]]
      ;; Should throw with :invalid-paths in ex-data
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid paths in wave"
           (wave/validate-task-paths tasks))))))

(deftest validate-task-paths-valid-test
  (testing "passes validation for paths with existing parent directories"
    (let [existing-dir (System/getProperty "java.io.tmpdir")
          tasks [{:file (str existing-dir "/file.clj") :task "task 1"}]]
      ;; Should not throw
      (is (true? (wave/validate-task-paths tasks))))))

(deftest validate-task-paths-mixed-test
  (testing "reports all invalid paths in exception data"
    (let [existing-dir (System/getProperty "java.io.tmpdir")
          tasks [{:file (str existing-dir "/valid.clj") :task "valid"}
                 {:file "/invalid/path1.clj" :task "invalid1"}
                 {:file "/invalid/path2.clj" :task "invalid2"}]]
      (try
        (wave/validate-task-paths tasks)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [invalid-paths (:invalid-paths (ex-data e))]
            ;; Should contain both invalid paths
            (is (= 2 (count invalid-paths)))
            (is (every? #(str/starts-with? % "/invalid/") invalid-paths))))))))

;; =============================================================================
;; Integration: handle-dispatch-drone-wave with pre-flight options
;; =============================================================================

(deftest handle-dispatch-drone-wave-ensure-dirs-option-test
  (testing "creates parent dirs when :ensure-dirs is true (default)"
    (let [test-dir (str (System/getProperty "java.io.tmpdir")
                        "/wave-test-" (System/currentTimeMillis))]
      (try
        (with-redefs [wave/execute-wave! (fn [plan-id _opts]
                                           (ds/create-wave! plan-id))]
          (wave/handle-dispatch-drone-wave
           {:tasks [{"file" (str test-dir "/new/path/file.clj")
                     "task" "test"}]
            :ensure_dirs true})
          ;; Should have created parent directories
          (is (.exists (java.io.File. (str test-dir "/new/path")))))
        (finally
          (doseq [dir [(str test-dir "/new/path")
                       (str test-dir "/new")
                       test-dir]]
            (.delete (java.io.File. dir))))))))

(deftest handle-dispatch-drone-wave-validate-paths-option-test
  (testing "fails fast when :validate-paths is true and paths are invalid"
    (let [result (wave/handle-dispatch-drone-wave
                  {:tasks [{"file" "/nonexistent/deep/path/file.clj"
                            "task" "test"}]
                   :validate_paths true
                   :ensure_dirs false})
          parsed (json/read-str (:text result) :key-fn keyword)]
      (is (contains? parsed :error))
      (is (str/includes? (:error parsed) "Invalid paths")))))
