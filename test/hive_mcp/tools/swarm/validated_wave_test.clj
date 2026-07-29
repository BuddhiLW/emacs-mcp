(ns hive-mcp.tools.swarm.validated-wave-test
  "Tests for dispatch_validated_wave tool handler.

   Covers:
   - Successful validation on first iteration
   - Self-healing loop with multiple iterations
   - Max retries exhaustion (partial status)
   - Task generation from lint findings
   - Error handling for empty tasks"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-mcp.tools.swarm.validated-wave :as validated-wave]
            [hive-mcp.tools.swarm.wave :as wave]
            [hive-mcp.analysis.resolve :as resolve]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(def ^:dynamic *mock-wave-result* nil)
(def ^:dynamic *mock-lint-result* nil)
(def ^:dynamic *wave-call-count* (atom 0))

(defn reset-mocks [f]
  (reset! *wave-call-count* 0)
  (binding [*mock-wave-result* nil
            *mock-lint-result* nil]
    (f)))

(use-fixtures :each reset-mocks)

;;; =============================================================================
;;; Helper Functions
;;; =============================================================================

(defn parse-response
  "Parse MCP response text as JSON."
  [response]
  (when (= "text" (:type response))
    (json/read-str (:text response) :key-fn keyword)))

(defn mock-wave-execute!
  "Mock wave execution that returns configurable results."
  [_plan-id _opts]
  (swap! *wave-call-count* inc)
  (str "wave-" @*wave-call-count*))

(defn mock-wave-get-plan-status
  "Mock plan status that returns completed items."
  [_plan-id]
  {:status :completed
   :items [{:file "src/foo.clj" :status :completed}
           {:file "src/bar.clj" :status :completed}]})

(defn mock-wave-create-plan!
  "Mock plan creation."
  [_tasks _preset]
  (str "plan-" @*wave-call-count*))

;;; =============================================================================
;;; Unit Tests: Task Generation
;;; =============================================================================

(deftest test-format-finding-for-task
  (testing "format-finding-for-task creates readable task line"
    (let [finding {:filename "src/foo.clj"
                   :row 42
                   :col 10
                   :level :error
                   :type :unused-binding
                   :message "unused binding x"}
          result (#'validated-wave/format-finding-for-task finding)]
      (is (str/includes? result "Line 42"))
      (is (str/includes? result "Col 10"))
      (is (str/includes? result "error"))
      (is (str/includes? result "unused-binding"))
      (is (str/includes? result "unused binding x")))))

(deftest test-generate-fix-tasks
  (testing "generate-fix-tasks groups findings by file"
    (let [findings [{:filename "src/foo.clj" :row 1 :col 1 :level :error :type :syntax :message "error 1"}
                    {:filename "src/foo.clj" :row 5 :col 1 :level :error :type :syntax :message "error 2"}
                    {:filename "src/bar.clj" :row 10 :col 1 :level :error :type :syntax :message "error 3"}]
          original-tasks [{:file "src/foo.clj" :task "original task"}]
          result (#'validated-wave/generate-fix-tasks findings original-tasks)]
      (is (= 2 (count result)) "Should generate one task per file")
      (let [foo-task (first (filter #(= "src/foo.clj" (:file %)) result))]
        (is (some? foo-task))
        (is (str/includes? (:task foo-task) "error 1"))
        (is (str/includes? (:task foo-task) "error 2"))
        (is (str/includes? (:task foo-task) "original task"))))))

(deftest test-generate-fix-tasks-without-original-context
  (testing "generate-fix-tasks works without original task context"
    (let [findings [{:filename "src/new.clj" :row 1 :col 1 :level :error :type :syntax :message "new error"}]
          result (#'validated-wave/generate-fix-tasks findings [])]
      (is (= 1 (count result)))
      (is (str/includes? (:task (first result)) "new error")))))

;;; =============================================================================
;;; Unit Tests: File Extraction
;;; =============================================================================

(deftest test-extract-modified-files
  (testing "extract-modified-files returns completed files only"
    (let [plan-status {:items [{:file "src/a.clj" :status :completed}
                               {:file "src/b.clj" :status :failed}
                               {:file "src/c.clj" :status :completed}]}
          result (#'validated-wave/extract-modified-files plan-status)]
      (is (= ["src/a.clj" "src/c.clj"] result)))))

(deftest test-extract-modified-files-empty
  (testing "extract-modified-files returns empty for all failures"
    (let [plan-status {:items [{:file "src/a.clj" :status :failed}]}
          result (#'validated-wave/extract-modified-files plan-status)]
      (is (empty? result)))))

;;; =============================================================================
;;; Integration Tests: Handler
;;; =============================================================================

(deftest test-handler-empty-tasks-error
  (testing "handler returns error for empty tasks array"
    (let [response (validated-wave/handle-dispatch-validated-wave {:tasks []})]
      (is (= "text" (:type response)))
      ;; Assert the CONTENT of the rejection, not the incidental word "error" —
      ;; the message now reads "Validated wave failed: tasks array is required
      ;; and must not be empty", which satisfies the intent while failing a
      ;; substring check for "error".
      (is (str/includes? (:text response) "failed"))
      (is (str/includes? (:text response) "tasks array is required")))))

(deftest test-handler-validates-task-structure
  (testing "handler normalizes task keys from string maps"
    ;; This tests that MCP's string keys get normalized
    (with-redefs [wave/create-plan! (fn [tasks _]
                                      (is (every? #(contains? % :file) tasks))
                                      (is (every? #(contains? % :task) tasks))
                                      "plan-1")
                  wave/execute-wave! (fn [_ _] "wave-1")
                  wave/get-plan-status (fn [_] {:items [{:file "test.clj" :status :completed}]})
                  resolve/resolve-kondo-analysis (fn [] (fn [_ & _opts] {:findings []}))]
      (let [response (validated-wave/handle-dispatch-validated-wave
                      {:tasks [{"file" "test.clj" "task" "test task"}]
                       :validate false})]
        (is (= "text" (:type response)))))))

;;; =============================================================================
;;; Integration Tests: Execute Validated Wave
;;; =============================================================================

(deftest test-execute-validated-wave-success-first-iteration
  (testing "success on first iteration when no lint errors"
    (with-redefs [wave/create-plan! mock-wave-create-plan!
                  wave/execute-wave! mock-wave-execute!
                  wave/get-plan-status (fn [_] {:items [{:file "src/foo.clj" :status :completed}]})
                  resolve/resolve-kondo-analysis (fn [] (fn [_ & _opts] {:findings []}))]
      (let [result (validated-wave/execute-validated-wave!
                    [{:file "src/foo.clj" :task "test"}]
                    {:validate true :trace false})]
        (is (= :success (:status result)))
        (is (= 1 (:iterations result)))
        (is (= 1 @*wave-call-count*))))))

(deftest test-execute-validated-wave-no-validation
  (testing "success without validation"
    (with-redefs [wave/create-plan! mock-wave-create-plan!
                  wave/execute-wave! mock-wave-execute!
                  wave/get-plan-status (fn [_] {:items [{:file "src/foo.clj" :status :completed}]})]
      (let [result (validated-wave/execute-validated-wave!
                    [{:file "src/foo.clj" :task "test"}]
                    {:validate false :trace false})]
        (is (= :success (:status result)))
        (is (= 1 (:iterations result)))))))

(deftest test-execute-validated-wave-self-healing
  (testing "self-healing loop fixes errors on retry"
    (let [lint-calls (atom 0)]
      (with-redefs [wave/create-plan! mock-wave-create-plan!
                    wave/execute-wave! mock-wave-execute!
                    wave/get-plan-status (fn [_] {:items [{:file "src/foo.clj" :status :completed}]})
                    resolve/resolve-kondo-analysis
                    (fn [] (fn [_ & _opts]
                             (swap! lint-calls inc)
                             (if (= 1 @lint-calls)
                               {:findings [{:filename "src/foo.clj"
                                            :row 1 :col 1
                                            :level :error
                                            :type :syntax
                                            :message "test error"}]}
                               {:findings []})))]
        (let [result (validated-wave/execute-validated-wave!
                      [{:file "src/foo.clj" :task "test"}]
                      {:validate true :max-retries 3 :trace false})]
          (is (= :success (:status result)))
          (is (= 2 (:iterations result)))
          (is (= 2 @*wave-call-count*)))))))

(deftest test-execute-validated-wave-max-retries
  (testing "returns partial when max retries exhausted"
    (with-redefs [wave/create-plan! mock-wave-create-plan!
                  wave/execute-wave! mock-wave-execute!
                  wave/get-plan-status (fn [_] {:items [{:file "src/foo.clj" :status :completed}]})
                  resolve/resolve-kondo-analysis
                  (fn [] (fn [_ & _opts]
                           {:findings [{:filename "src/foo.clj"
                                        :row 1 :col 1
                                        :level :error
                                        :type :syntax
                                        :message "persistent error"}]}))]
      (let [result (validated-wave/execute-validated-wave!
                    [{:file "src/foo.clj" :task "test"}]
                    {:validate true :max-retries 3 :trace false})]
        (is (= :partial (:status result)))
        (is (= 3 (:iterations result)))
        (is (= 3 @*wave-call-count*))
        (is (seq (:findings result)))
        (is (str/includes? (:message result) "3 iterations"))))))

(deftest test-execute-validated-wave-iteration-history
  (testing "history tracks all iterations"
    (let [lint-calls (atom 0)]
      (with-redefs [wave/create-plan! mock-wave-create-plan!
                    wave/execute-wave! mock-wave-execute!
                    wave/get-plan-status (fn [_] {:items [{:file "src/foo.clj" :status :completed}]})
                    resolve/resolve-kondo-analysis
                    (fn [] (fn [_ & _opts]
                             (swap! lint-calls inc)
                             (if (<= @lint-calls 2)
                               {:findings [{:filename "src/foo.clj"
                                            :row @lint-calls :col 1
                                            :level :error
                                            :type :syntax
                                            :message (str "error " @lint-calls)}]}
                               {:findings []})))]
        (let [result (validated-wave/execute-validated-wave!
                      [{:file "src/foo.clj" :task "test"}]
                      {:validate true :max-retries 5 :trace false})]
          (is (= 3 (count (:history result))))
          (is (= [1 2 3] (mapv :iteration (:history result))))
          ;; First two have findings, third is clean
          (is (= 1 (:finding-count (nth (:history result) 0))))
          (is (= 1 (:finding-count (nth (:history result) 1))))
          (is (= 0 (:finding-count (nth (:history result) 2)))))))))

;;; =============================================================================
;;; Unit Tests: Lint Level Filtering
;;; =============================================================================

(deftest test-lint-files-error-level
  (testing "lint-files filters to errors only at error level"
    (with-redefs [resolve/resolve-kondo-analysis
                  (fn [] (fn [_ & _opts]
                           {:findings [{:level :error :filename "a.clj"}
                                       {:level :warning :filename "a.clj"}
                                       {:level :info :filename "a.clj"}]}))]
      (let [result (#'validated-wave/lint-files ["a.clj"] "error")]
        (is (= 1 (:count result)))
        (is (every? #(= :error (:level %)) (:findings result)))))))

(deftest test-lint-files-warning-level
  (testing "lint-files includes errors and warnings at warning level"
    (with-redefs [resolve/resolve-kondo-analysis
                  (fn [] (fn [_ & _opts]
                           {:findings [{:level :error :filename "a.clj"}
                                       {:level :warning :filename "a.clj"}
                                       {:level :info :filename "a.clj"}]}))]
      (let [result (#'validated-wave/lint-files ["a.clj"] "warning")]
        (is (= 2 (:count result)))))))

(deftest test-lint-files-info-level
  (testing "lint-files includes all at info level"
    (with-redefs [resolve/resolve-kondo-analysis
                  (fn [] (fn [_ & _opts]
                           {:findings [{:level :error :filename "a.clj"}
                                       {:level :warning :filename "a.clj"}
                                       {:level :info :filename "a.clj"}]}))]
      (let [result (#'validated-wave/lint-files ["a.clj"] "info")]
        (is (= 3 (:count result)))))))

;;; =============================================================================
;;; Constants Tests
;;; =============================================================================

(deftest test-default-constants
  (testing "default constants have sensible values"
    (is (= 3 validated-wave/default-max-retries))
    (is (= "error" validated-wave/default-lint-level))))
