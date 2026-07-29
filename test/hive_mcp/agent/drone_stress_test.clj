(ns hive-mcp.agent.drone-stress-test
  "Stress tests for Drone path resolution and Wave orchestration.

   Domain Concepts:
   - PathValidator: Validates file paths against project boundaries (CLARITY-I)
   - WavePreflight: Pre-execution validation ensuring directories exist
   - DroneTask: Unit of work dispatched to a drone agent
   - WaveExecution: Batch execution of multiple drone tasks

   Test Organization (DDD bounded contexts):
   - Path Validation: Ensures drones can only access valid project files
   - Wave Pre-flight: Directory creation and task validation
   - Wave Orchestration: Plan creation and execution
   - Error Handling: Graceful degradation (CLARITY-Y)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.tools.swarm.wave :as wave]
            [hive-mcp.tools.diff :as diff]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.handlers :as handlers]
            [taoensso.timbre :as log]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

;; =============================================================================
;; Test Infrastructure (SOLID-I: Small focused helpers)
;; =============================================================================

(def ^:dynamic *project-root*
  "Simulated project root for path validation tests.
   Represents the drone's working directory context."
  nil)

;; -----------------------------------------------------------------------------
;; Fixture: DataScript Reset (SOLID-S: Single responsibility)
;; -----------------------------------------------------------------------------

;; -----------------------------------------------------------------------------
;; Fixture: Temporary Project Directory
;; -----------------------------------------------------------------------------

(defn- create-temp-project-dir []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "drone-test-" (System/currentTimeMillis)))]
    (.mkdirs temp-dir)
    temp-dir))

(defn- cleanup-temp-dir [temp-dir]
  (doseq [file (reverse (file-seq temp-dir))]
    (.delete file)))

(defn project-dir-fixture
  "Create isolated project directory for each test.
   CLARITY-I: Validates inputs at test boundaries."
  [f]
  (let [temp-dir (create-temp-project-dir)]
    (binding [*project-root* (.getAbsolutePath temp-dir)]
      (log/debug "Test project root:" *project-root*)
      (try
        (f)
        (finally
          (cleanup-temp-dir temp-dir))))))

(use-fixtures :each
  (iso/with-isolations :swarm-ds :events)
  project-dir-fixture)

;; =============================================================================
;; Test Helpers (SOLID-I: Focused, composable helpers)
;; =============================================================================

(defn- create-file!
  "Create a file with content in the test project.
   Returns absolute path for use in assertions."
  [relative-path content]
  {:pre [(string? relative-path) (string? content)]}
  (let [file (io/file *project-root* relative-path)]
    (io/make-parents file)
    (spit file content)
    (log/debug "Created test file:" (.getAbsolutePath file))
    (.getAbsolutePath file)))

(defn- create-clj-file!
  "Create a minimal Clojure source file.
   CLARITY-C: Composes create-file! rather than duplicating."
  [relative-path ns-sym]
  (create-file! relative-path
                (str "(ns " ns-sym ")\n\n(defn placeholder []\n  :todo)\n")))

(defn- abs-path
  "Construct absolute path from relative path in test project."
  [relative-path]
  (str *project-root* "/" relative-path))

(defn- validate-path
  "Validate a path against the test project root.
   Domain operation: PathValidator.validate"
  [path]
  (diff/validate-diff-path path *project-root*))

;; =============================================================================
;; Path Validation Tests (CLARITY-R: Clear intent in names)
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Valid Path Cases (SOLID-O: Table-driven for extensibility)
;; -----------------------------------------------------------------------------

(def ^:private valid-path-cases
  "Table of valid path scenarios for PathValidator.
   Format: {:desc description :setup-fn fn :path-fn fn}"
  [{:desc     "relative path to existing file"
    :setup-fn #(create-file! "src/core.clj" "(ns core)")
    :path-fn  #(identity "src/core.clj")}

   {:desc     "deeply nested relative path"
    :setup-fn #(create-file! "src/a/b/c/deep.clj" "(ns deep)")
    :path-fn  #(identity "src/a/b/c/deep.clj")}

   {:desc     "path with dots in filename"
    :setup-fn #(create-file! "file.with.dots.clj" "(ns dots)")
    :path-fn  #(identity "file.with.dots.clj")}

   {:desc     "path with spaces in directory"
    :setup-fn #(create-file! "dir with spaces/file.clj" "(ns spaces)")
    :path-fn  #(identity "dir with spaces/file.clj")}

   {:desc     "path with unicode characters"
    :setup-fn #(create-file! "dir_\u4e2d\u6587/file.clj" "(ns unicode)")
    :path-fn  #(identity "dir_\u4e2d\u6587/file.clj")}])

(deftest PathValidator-AcceptsValidRelativePaths
  (testing "PathValidator accepts relative paths within project boundary"
    (doseq [{:keys [desc setup-fn path-fn]} valid-path-cases]
      (testing desc
        ;; Setup: Create the file
        (setup-fn)
        ;; Exercise: Validate the path
        (let [result (validate-path (path-fn))]
          ;; Verify: Path is valid and has resolved path
          (is (:valid result) (str "Expected valid for: " desc))
          (is (contains? result :resolved-path)))))))

(deftest PathValidator-AcceptsExistingAbsolutePath
  (testing "PathValidator accepts absolute path to existing file within project"
    ;; Setup
    (let [file-path (create-file! "existing.clj" "(ns existing)")]
      ;; Exercise
      (let [result (validate-path file-path)]
        ;; Verify
        (is (:valid result) "Absolute path to existing file should be valid")
        (is (= file-path (:resolved-path result)))))))

;; -----------------------------------------------------------------------------
;; Invalid Path Cases (CLARITY-Y: Test error paths)
;; -----------------------------------------------------------------------------

(def ^:private invalid-path-cases
  "Table of invalid path scenarios for PathValidator error handling.
   Format: {:desc description :path string :error-pattern regex}"
  [{:desc          "empty path"
    :path          ""
    :error-pattern #"cannot be empty"}

   {:desc          "path escaping via parent traversal"
    :path          "../../../etc/passwd"
    :error-pattern #"escapes project directory"}

   {:desc          "absolute path with non-existent parent"
    :path          "/nonexistent/deeply/nested/file.clj"
    :error-pattern #"Invalid absolute path"}

   {:desc          "path traversal hidden in middle"
    :path          "src/../../../etc/passwd"
    :error-pattern #"escapes project directory"}])

(deftest PathValidator-RejectsInvalidPaths
  (testing "PathValidator rejects paths that violate security boundaries"
    (doseq [{:keys [desc path error-pattern]} invalid-path-cases]
      (testing desc
        ;; Exercise
        (let [result (validate-path path)]
          ;; Verify: Invalid with expected error message
          (is (not (:valid result)) (str "Expected invalid for: " desc))
          (is (re-find error-pattern (:error result))
              (str "Expected error matching " error-pattern)))))))

(deftest PathValidator-RejectsNonExistentAbsolutePath
  (testing "PathValidator rejects absolute path where parent directory doesn't exist"
    ;; Exercise: Path that looks absolute but doesn't exist
    (let [result (diff/validate-diff-path "/hallucinated/drone/path.clj")]
      ;; Verify: This catches LLM hallucinations like /hivemind/controller.clj
      (is (not (:valid result)))
      (is (re-find #"Invalid absolute path" (:error result))))))

;; =============================================================================
;; Wave Pre-flight Tests (Domain: WavePreflight)
;; =============================================================================

(deftest WavePreflight-CreatesParentDirectories
  (testing "ensure-parent-dirs! creates all necessary parent directories"
    ;; Setup: Tasks targeting non-existent directories
    (let [tasks [{:file (abs-path "new/nested/deep/file1.clj") :task "task1"}
                 {:file (abs-path "another/path/file2.clj") :task "task2"}]]
      ;; Verify pre-condition: directories don't exist
      (is (not (.exists (io/file *project-root* "new/nested/deep"))))
      (is (not (.exists (io/file *project-root* "another/path"))))
      ;; Exercise
      (wave/ensure-parent-dirs! tasks)
      ;; Verify: directories now exist
      (is (.exists (io/file *project-root* "new/nested/deep")))
      (is (.exists (io/file *project-root* "another/path"))))))

(deftest WavePreflight-HandlesNilFilesGracefully
  (testing "ensure-parent-dirs! handles tasks with nil file gracefully (CLARITY-Y)"
    ;; Setup: Mixed valid and nil files
    (let [tasks [{:file nil :task "nil-file-task"}
                 {:file (abs-path "valid/path.clj") :task "valid-task"}]]
      ;; Exercise & Verify: nil files are skipped, the real one is counted
      (is (= 1 (wave/ensure-parent-dirs! tasks))
          "returns the number of parent dirs created, skipping nil files")
      (is (.exists (io/file *project-root* "valid"))))))

(deftest WavePreflight-ScalesTo50Directories
  (testing "ensure-parent-dirs! handles 50 directories efficiently (CLARITY-A)"
    ;; Setup: 50 tasks in different directories
    (let [tasks (for [i (range 50)]
                  {:file (abs-path (str "dir-" i "/sub/file.clj"))
                   :task (str "task-" i)})]
      ;; Exercise
      (wave/ensure-parent-dirs! tasks)
      ;; Verify: All directories exist
      (doseq [i (range 50)]
        (is (.exists (io/file *project-root* (str "dir-" i) "sub"))
            (str "Directory " i " should exist"))))))

(deftest WavePreflight-ValidatesTaskPaths
  (testing "validate-task-paths passes for all valid paths"
    ;; Setup: Create valid files
    (doseq [i (range 5)]
      (create-file! (str "valid-" i ".clj") "(ns valid)"))
    (let [tasks (for [i (range 5)]
                  {:file (abs-path (str "valid-" i ".clj"))
                   :task (str "task-" i)})]
      ;; Exercise & Verify: Should not throw
      (is (true? (wave/validate-task-paths tasks))))))

(deftest WavePreflight-ReportsAllInvalidPaths
  (testing "validate-task-paths reports ALL invalid paths, not just first (CLARITY-Y)"
    ;; Setup: One valid, two invalid
    (create-file! "valid.clj" "(ns valid)")
    (let [tasks [{:file (abs-path "valid.clj") :task "valid"}
                 {:file "/nonexistent/a.clj" :task "invalid-a"}
                 {:file "/nonexistent/b.clj" :task "invalid-b"}]]
      ;; Exercise & Verify
      (try
        (wave/validate-task-paths tasks)
        (is false "Should have thrown ExceptionInfo")
        (catch clojure.lang.ExceptionInfo e
          (let [invalid-paths (:invalid-paths (ex-data e))]
            (is (= 2 (count invalid-paths))
                "Should report both invalid paths, not just first")))))))

;; =============================================================================
;; Wave Orchestration Tests (Domain: WaveExecution)
;; =============================================================================

(deftest WaveExecution-CreatesPlanWithItems
  (testing "create-plan! creates plan entity with correct item count"
    ;; Setup
    (let [tasks [{:file "a.clj" :task "task-a"}
                 {:file "b.clj" :task "task-b"}
                 {:file "c.clj" :task "task-c"}]]
      ;; Exercise
      (let [plan-id (wave/create-plan! tasks)]
        ;; Verify
        (is (string? plan-id))
        (is (.startsWith plan-id "plan-"))
        (is (= 3 (count (wave/get-pending-items plan-id))))))))

(deftest WaveExecution-ScalesTo20Items
  (testing "create-plan! handles 20 items for chaos wave (CLARITY-A)"
    ;; Setup
    (let [tasks (for [i (range 20)]
                  {:file (str "file-" i ".clj")
                   :task (str "chaos-task-" i)})
          plan-id (wave/create-plan! tasks)]
      ;; Verify
      (is (= 20 (count (wave/get-pending-items plan-id)))))))

(deftest WaveExecution-TracksCompletionStatus
  (testing "Wave status reflects completed items correctly"
    ;; Setup
    (let [tasks [{:file "test.clj" :task "test-task"}]
          plan-id (wave/create-plan! tasks)]
      ;; Exercise: Simulate wave execution
      (with-redefs [wave/execute-wave!
                    (fn [pid opts]
                      (let [wid (ds/create-wave! pid opts)]
                        (ds/update-wave-counts! wid {:completed 1})
                        (ds/complete-wave! wid :completed)
                        wid))]
        (let [wave-id (wave/execute-wave! plan-id {:trace false})
              status (wave/get-wave-status wave-id)]
          ;; Verify
          (is (= :completed (:status status)))
          (is (= 1 (:completed-count status)))
          (is (= 0 (:failed-count status))))))))

;; =============================================================================
;; Edge Case: Symlinks (Platform-specific)
;; =============================================================================

(deftest PathValidator-HandlesSymlinksWithinProject
  (testing "PathValidator accepts symlinks that resolve within project (CLARITY-Y)"
    ;; Skip on Windows where symlinks require elevated privileges
    (when-not (re-find #"(?i)windows" (System/getProperty "os.name"))
      ;; Setup: Create real file and symlink
      (create-file! "real/file.clj" "(ns real)")
      (let [link-dir (io/file *project-root* "linked")]
        (try
          (java.nio.file.Files/createSymbolicLink
           (.toPath link-dir)
           (.toPath (io/file *project-root* "real"))
           (into-array java.nio.file.attribute.FileAttribute []))
          ;; Exercise
          (let [result (validate-path "linked/file.clj")]
            ;; Verify
            (is (:valid result) "Symlink within project should be valid"))
          (catch Exception e
            ;; Symlink creation may fail on some systems - graceful degradation
            (log/debug "Symlink test skipped:" (.getMessage e))
            (is true "Graceful degradation for unsupported symlinks")))))))

;; =============================================================================
;; Integration: Progressive Wave Scenarios
;; =============================================================================

(deftest DroneWave-BasicThreeDrones
  (testing "Wave 1: Basic scenario with 3 drones validates successfully"
    ;; Setup: Create 3 test files
    (doseq [i (range 3)]
      (create-clj-file! (str "src/basic_" i ".clj") (symbol (str "basic-" i))))
    (let [tasks (for [i (range 3)]
                  {:file (abs-path (str "src/basic_" i ".clj"))
                   :task "Add docstring to placeholder function"})
          plan-id (wave/create-plan! (vec tasks))]
      ;; Verify: Plan created with all items
      (is (= 3 (count (wave/get-pending-items plan-id))))
      ;; Verify: All paths valid
      (is (true? (wave/validate-task-paths (vec tasks)))))))

(deftest DroneWave-PathVariationsFiveDrones
  (testing "Wave 2: Various path formats all validate correctly"
    ;; Setup: Files at different nesting levels
    (create-clj-file! "src/shallow.clj" 'shallow)
    (create-clj-file! "src/a/nested.clj" 'nested)
    (create-clj-file! "src/a/b/deep.clj" 'deep)
    (create-clj-file! "test/core_test.clj" 'core-test)
    (create-clj-file! "resources/config.clj" 'config)
    (let [tasks [{:file (abs-path "src/shallow.clj") :task "shallow"}
                 {:file (abs-path "src/a/nested.clj") :task "nested"}
                 {:file (abs-path "src/a/b/deep.clj") :task "deep"}
                 {:file (abs-path "test/core_test.clj") :task "test"}
                 {:file (abs-path "resources/config.clj") :task "config"}]]
      ;; Verify: All paths valid regardless of nesting
      (is (true? (wave/validate-task-paths tasks))))))

(deftest DroneWave-StressTenDrones
  (testing "Wave 3: Stress test with 10 drones validates successfully"
    ;; Setup
    (doseq [i (range 10)]
      (create-clj-file! (str "src/stress_" i ".clj") (symbol (str "stress-" i))))
    (let [tasks (for [i (range 10)]
                  {:file (abs-path (str "src/stress_" i ".clj"))
                   :task "Implement placeholder function"})
          plan-id (wave/create-plan! (vec tasks))]
      ;; Verify
      (is (= 10 (count (wave/get-pending-items plan-id))))
      (is (true? (wave/validate-task-paths (vec tasks)))))))

(deftest DroneWave-ChaosTwentyDrones
  (testing "Wave 4: Chaos test with 20 drones across 5 subdirectories"
    ;; Setup: 20 files distributed across 5 subdirectories
    (doseq [i (range 20)]
      (let [subdir (mod i 5)]
        (create-clj-file! (str "src/chaos_" subdir "/file_" i ".clj")
                          (symbol (str "chaos-" i)))))
    (let [tasks (for [i (range 20)]
                  (let [subdir (mod i 5)]
                    {:file (abs-path (str "src/chaos_" subdir "/file_" i ".clj"))
                     :task (case (mod i 4)
                             0 "Add docstring"
                             1 "Fix bug"
                             2 "Refactor"
                             3 "Add tests")}))
          plan-id (wave/create-plan! (vec tasks))]
      ;; Verify
      (is (= 20 (count (wave/get-pending-items plan-id))))
      (is (true? (wave/validate-task-paths (vec tasks)))))))

;; =============================================================================
;; REPL Helpers
;; =============================================================================

(comment
  ;; Run all tests in namespace
  (clojure.test/run-tests 'hive-mcp.agent.drone-stress-test)

  ;; Run specific test
  (clojure.test/test-var #'PathValidator-AcceptsValidRelativePaths)

  ;; Quick validation check
  (diff/validate-diff-path "src/core.clj" "/tmp/test-project"))
