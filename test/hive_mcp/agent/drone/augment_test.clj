(ns hive-mcp.agent.drone.augment-test
  "TDD tests for drone task augmentation.

   Tests context preparation and task augmentation functions that were
   extracted from drone.clj to reduce complexity (SOLID-S).

   Key functions tested:
   - format-context-str: Format conventions/decisions as string (pure function)
   - format-file-contents: Pre-read files with path validation (I/O)
   - augment-task: Compose full augmented task with all context (integration)

   Note: augment-task tests use with-redefs to mock I/O dependencies
   (Chroma, KG, registry). Only AGPL layer tested."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-mcp.agent.drone.augment :as augment]
            [hive-mcp.agent.drone.unified-context :as unified-ctx]
            [hive-mcp.agent.drone.kg-context :as kg-ctx]
            [hive-mcp.agent.registry :as registry]
            [hive-mcp.context.budget :as budget]
            [hive-mcp.tools.diff :as diff]
            [hive-test.properties :as props]
            [hive-test.generators.core :as gen-core]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:private test-dir (str (System/getProperty "java.io.tmpdir") "/drone-augment-test"))

(defn- setup-test-files!
  "Create temporary test files."
  []
  (let [dir (io/file test-dir)]
    (.mkdirs dir)
    ;; Create a test clojure file
    (spit (io/file dir "test.clj")
          "(ns test.core)\n\n(defn hello []\n  \"Hello, World!\")")
    ;; Create another file
    (spit (io/file dir "config.edn")
          "{:port 8080\n :host \"localhost\"}")))

(defn- cleanup-test-files!
  "Remove temporary test files."
  []
  (let [dir (io/file test-dir)]
    (when (.exists dir)
      (doseq [f (.listFiles dir)]
        (.delete f))
      (.delete dir))))

(defn test-files-fixture [f]
  (setup-test-files!)
  (try
    (f)
    (finally
      (cleanup-test-files!))))

(use-fixtures :each test-files-fixture)

;; =============================================================================
;; format-context-str Tests (Pure Function)
;; =============================================================================

(deftest test-format-context-str-empty
  (testing "Empty context returns nil"
    (is (nil? (augment/format-context-str {})))
    (is (nil? (augment/format-context-str nil)))))

(deftest test-format-context-str-with-conventions
  (testing "Conventions are formatted with header"
    (let [context {:conventions [{:content "Use kebab-case"}
                                 {:content "Prefer pure functions"}]}
          result (augment/format-context-str context)]
      (is (some? result))
      (is (str/includes? result "### Conventions"))
      (is (str/includes? result "Use kebab-case"))
      (is (str/includes? result "Prefer pure functions"))
      (is (str/includes? result "## Project Context")))))

(deftest test-format-context-str-with-decisions
  (testing "Decisions are formatted with header"
    (let [context {:decisions [{:content "Use DataScript for state"}]}
          result (augment/format-context-str context)]
      (is (some? result))
      (is (str/includes? result "### Decisions"))
      (is (str/includes? result "Use DataScript for state")))))

(deftest test-format-context-str-with-both
  (testing "Both conventions and decisions are included"
    (let [context {:conventions [{:content "Rule 1"}]
                   :decisions [{:content "Decision A"}]}
          result (augment/format-context-str context)]
      (is (str/includes? result "### Conventions"))
      (is (str/includes? result "### Decisions"))
      (is (str/includes? result "Rule 1"))
      (is (str/includes? result "Decision A")))))

(deftest test-format-context-str-empty-lists
  (testing "Empty convention/decision lists produce nil"
    (is (nil? (augment/format-context-str {:conventions [] :decisions []})))))

;; =============================================================================
;; format-file-contents Tests (I/O - uses test fixture files)
;; =============================================================================

(deftest test-format-file-contents-empty-files
  (testing "Empty file list returns nil"
    (is (nil? (augment/format-file-contents [] test-dir)))
    (is (nil? (augment/format-file-contents nil test-dir)))))

(deftest test-format-file-contents-valid-files
  (testing "Valid files are read and formatted"
    (let [files [(str test-dir "/test.clj")]
          result (augment/format-file-contents files test-dir)]
      (is (some? result))
      (is (str/includes? result "## Current File Contents"))
      (is (str/includes? result "test.clj"))
      (is (str/includes? result "(ns test.core)")))))

(deftest test-format-file-contents-missing-file
  (testing "Missing files show error message"
    (let [files [(str test-dir "/nonexistent.clj")]
          result (augment/format-file-contents files test-dir)]
      (is (some? result))
      (is (str/includes? result "nonexistent.clj")))))

(deftest test-format-file-contents-path-escape-blocked
  (testing "Path traversal attempts are blocked"
    (let [files [(str test-dir "/../../../etc/passwd")]
          result (augment/format-file-contents files test-dir)]
      (is (some? result))
      (is (str/includes? result "BLOCKED")))))

(deftest test-format-file-contents-multiple-files
  (testing "Multiple files are all included"
    (let [files [(str test-dir "/test.clj")
                 (str test-dir "/config.edn")]
          result (augment/format-file-contents files test-dir)]
      (is (str/includes? result "test.clj"))
      (is (str/includes? result "config.edn"))
      (is (str/includes? result "(ns test.core)"))
      (is (str/includes? result ":port 8080")))))

;; =============================================================================
;; augment-task Tests (Integration - with-redefs for I/O mocking)
;; =============================================================================

(deftest test-augment-task-basic
  (testing "Basic task augmentation includes task section"
    (with-redefs [unified-ctx/unified-context-available? (constantly false)
                  registry/get-tool (constantly nil)
                  diff/get-project-root (constantly test-dir)
                  kg-ctx/format-files-with-kg-context
                  (fn [_files _opts]
                    {:context nil :files-read [] :kg-skipped [] :summary {}})]
      (let [result (augment/augment-task "Fix the bug" [] {})]
        (is (string? result))
        (is (str/includes? result "## Task"))
        (is (str/includes? result "Fix the bug"))))))

(deftest test-augment-task-with-files
  (testing "Task with files includes file list"
    (with-redefs [unified-ctx/unified-context-available? (constantly false)
                  registry/get-tool (constantly nil)
                  diff/get-project-root (constantly test-dir)
                  kg-ctx/format-files-with-kg-context
                  (fn [files _opts]
                    {:context (str "## File Contents\n" (str/join "\n" files))
                     :files-read files
                     :kg-skipped []
                     :summary {:kg-known 0 :needs-read (count files) :stale 0}})]
      (let [files [(str test-dir "/test.clj")]
            result (augment/augment-task "Update function" files
                                         {:project-root test-dir})]
        (is (str/includes? result "## Files to modify"))
        (is (str/includes? result "test.clj"))))))

(deftest test-augment-task-injects-project-root
  (testing "Project root is injected for propose_diff"
    (with-redefs [unified-ctx/unified-context-available? (constantly false)
                  registry/get-tool (constantly nil)
                  diff/get-project-root (constantly test-dir)
                  kg-ctx/format-files-with-kg-context
                  (fn [_files _opts]
                    {:context nil :files-read [] :kg-skipped [] :summary {}})]
      (let [result (augment/augment-task "Fix bug" []
                                         {:project-root "/project/path"})]
        (is (str/includes? result "## Project Directory"))
        (is (str/includes? result "/project/path"))))))

(deftest test-augment-task-return-metadata
  (testing "Return metadata about context path and budget when requested"
    (with-redefs [unified-ctx/unified-context-available? (constantly false)
                  registry/get-tool (constantly nil)
                  diff/get-project-root (constantly test-dir)
                  kg-ctx/format-files-with-kg-context
                  (fn [_files _opts]
                    {:context nil :files-read ["a.clj"] :kg-skipped []
                     :summary {:kg-known 0 :needs-read 1 :stale 0}})]
      (let [result (augment/augment-task "Task" ["a.clj"]
                                         {:return-metadata true
                                          :project-root test-dir})]
        (is (map? result))
        (is (contains? result :task))
        (is (contains? result :files-read))
        (is (contains? result :unified?))
        (is (contains? result :compressed?))
        (is (false? (:compressed? result))
            "No ctx-refs → not compressed path")
        (is (false? (:unified? result))
            "Unified unavailable → legacy path")))))

(deftest test-augment-task-budget-metadata
  (testing "Budget metadata returned when token-budget is set"
    (with-redefs [unified-ctx/unified-context-available? (constantly false)
                  registry/get-tool (constantly nil)
                  diff/get-project-root (constantly test-dir)
                  kg-ctx/format-files-with-kg-context
                  (fn [_files _opts]
                    {:context nil :files-read [] :kg-skipped [] :summary {}})]
      (let [result (augment/augment-task "Task" []
                                         {:return-metadata true
                                          :token-budget 2000})]
        (is (some? (:budget result))
            "Budget metadata present when token-budget set")
        (is (= 2000 (get-in result [:budget :total-budget]))
            "Budget matches requested token-budget")))))

(deftest test-augment-task-nil-budget-unbounded
  (testing "nil token-budget produces unbounded output (backward compat)"
    (with-redefs [unified-ctx/unified-context-available? (constantly false)
                  registry/get-tool (constantly nil)
                  diff/get-project-root (constantly test-dir)
                  kg-ctx/format-files-with-kg-context
                  (fn [_files _opts]
                    {:context nil :files-read [] :kg-skipped [] :summary {}})]
      (let [result (augment/augment-task "Task" []
                                         {:return-metadata true
                                          :token-budget nil})]
        (is (nil? (:budget result))
            "No budget metadata when token-budget is nil (unbounded)")))))

;; =============================================================================
;; Integration: Full Augmentation Flow
;; =============================================================================

(deftest test-full-augmentation-flow
  (testing "Full augmentation combines unified context, files, and task with budget"
    (with-redefs [unified-ctx/unified-context-available? (constantly true)
                  unified-ctx/prepare-drone-context
                  (fn [_opts]
                    {:conventions [{:id "c1" :type "convention"
                                    :content "Use atoms for state"}]
                     :decisions [{:id "d1" :type "decision"
                                  :content "DataScript for KG"}]
                     :snippets [] :domain [] :edges []
                     :seed-count 2 :traversal-count 2 :node-ids #{}})
                  registry/get-tool (constantly nil)
                  diff/get-project-root (constantly test-dir)
                  kg-ctx/format-files-with-kg-context
                  (fn [files _opts]
                    {:context (str "## Files\n" (str/join "\n" files))
                     :files-read files :kg-skipped []
                     :summary {:kg-known 0 :needs-read (count files) :stale 0}})]
      (let [result (augment/augment-task
                    "Implement validation"
                    [(str test-dir "/test.clj")]
                    {:project-root test-dir
                     :return-metadata true
                     :token-budget 4000})]
        ;; Task section present
        (is (str/includes? (:task result) "## Task"))
        (is (str/includes? (:task result) "Implement validation"))
        ;; Unified context path used
        (is (true? (:unified? result))
            "Unified context path was used")
        (is (false? (:compressed? result))
            "Not compressed (no ctx-refs)")
        ;; Budget applied
        (is (some? (:budget result)))
        (is (<= (get-in result [:budget :total-tokens])
                (get-in result [:budget :total-budget]))
            "Budget guarantee holds in full augmentation flow")))))

;; =============================================================================
;; resolve-context-str short-circuit priority tests (Tier1-B refactor)
;; =============================================================================

(def ^:private resolve-ctx #'augment/resolve-context-str)
(def ^:private try-compressed-var #'augment/try-compressed)
(def ^:private try-unified-var #'augment/try-unified)
(def ^:private try-legacy-var #'augment/try-legacy)

(def ^:private base-params
  {:effective-root       "/tmp"
   :effective-project-id "hive-mcp"
   :seeds                nil
   :use-unified          true
   :token-budget         2000
   :ctx-refs             nil
   :kg-node-ids          nil})

(deftest test-resolve-context-compressed-wins
  (testing "When compressed path returns non-nil, unified and legacy are never evaluated"
    (let [unified-called? (atom false)
          legacy-called?  (atom false)]
      (with-redefs [augment/try-compressed (fn [_] {:kg-ctx-str "COMPRESSED"
                                                    :compressed-ctx-str "COMPRESSED"})
                    augment/try-unified    (fn [_] (reset! unified-called? true)
                                             {:kg-ctx-str "U" :unified-ctx-str "U"})
                    augment/try-legacy     (fn [_] (reset! legacy-called? true)
                                             {:kg-ctx-str "L"})]
        (let [result (resolve-ctx "task" [] base-params)]
          (is (= "COMPRESSED" (:kg-ctx-str result)))
          (is (= "COMPRESSED" (:compressed-ctx-str result)))
          (is (nil? (:unified-ctx-str result)))
          (is (false? @unified-called?) "unified must not be called when compressed wins")
          (is (false? @legacy-called?) "legacy must not be called when compressed wins"))))))

(deftest test-resolve-context-falls-through-to-unified
  (testing "When compressed returns nil, unified is tried and its value is used"
    (let [legacy-called? (atom false)]
      (with-redefs [augment/try-compressed (constantly nil)
                    augment/try-unified    (constantly {:kg-ctx-str "UNIFIED"
                                                        :unified-ctx-str "UNIFIED"})
                    augment/try-legacy     (fn [_] (reset! legacy-called? true)
                                             {:kg-ctx-str "L"})]
        (let [result (resolve-ctx "task" [] base-params)]
          (is (= "UNIFIED" (:kg-ctx-str result)))
          (is (= "UNIFIED" (:unified-ctx-str result)))
          (is (nil? (:compressed-ctx-str result)))
          (is (false? @legacy-called?) "legacy must not be called when unified wins"))))))

(deftest test-resolve-context-falls-through-to-legacy
  (testing "When compressed and unified both return nil, legacy path result is used"
    (with-redefs [augment/try-compressed (constantly nil)
                  augment/try-unified    (constantly nil)
                  augment/try-legacy     (constantly {:kg-ctx-str "LEGACY"
                                                      :primed-ctx-str nil})]
      (let [result (resolve-ctx "task" [] base-params)]
        (is (= "LEGACY" (:kg-ctx-str result)))
        (is (nil? (:compressed-ctx-str result)))
        (is (nil? (:unified-ctx-str result)))))))

(deftest test-resolve-context-final-fallback-empty-string
  (testing "When all three paths return nil, kg-ctx-str is empty string (never throws)"
    (with-redefs [augment/try-compressed (constantly nil)
                  augment/try-unified    (constantly nil)
                  augment/try-legacy     (constantly nil)]
      (let [result (resolve-ctx "task" [] base-params)]
        (is (= "" (:kg-ctx-str result)))
        (is (nil? (:compressed-ctx-str result)))
        (is (nil? (:unified-ctx-str result)))
        (is (nil? (:primed-ctx-str result)))))))

(deftest test-resolve-context-result-schema-stable
  (testing "Result map always contains all four keys regardless of which path wins"
    (doseq [winner [{:kg-ctx-str "C" :compressed-ctx-str "C"}
                    {:kg-ctx-str "U" :unified-ctx-str "U"}
                    {:kg-ctx-str "L" :primed-ctx-str "P"}
                    nil]]
      (with-redefs [augment/try-compressed (constantly (when (:compressed-ctx-str winner) winner))
                    augment/try-unified    (constantly (when (:unified-ctx-str winner) winner))
                    augment/try-legacy     (constantly (when (:primed-ctx-str winner) winner))]
        (let [result (resolve-ctx "task" [] base-params)]
          (is (every? #(contains? result %)
                      [:kg-ctx-str :compressed-ctx-str :unified-ctx-str :primed-ctx-str])))))))

;; -----------------------------------------------------------------------------
;; Property test: resolve-context-str is TOTAL (never throws)
;; -----------------------------------------------------------------------------

(def ^:private gen-params
  (gen/let [task gen-core/gen-non-blank-string
            pid  gen-core/gen-non-blank-string
            root gen-core/gen-non-blank-string
            seeds (gen/vector gen-core/gen-non-blank-string 0 3)
            use-unified gen/boolean
            budget (gen/one-of [(gen/return nil) (gen/choose 500 8000)])
            refs (gen/vector gen-core/gen-non-blank-string 0 3)]
    {:task task
     :params {:effective-root       root
              :effective-project-id pid
              :seeds                seeds
              :use-unified          use-unified
              :token-budget         budget
              :ctx-refs             refs
              :kg-node-ids          []}}))

(defn- safe-resolve-context
  "Totality wrapper: call resolve-context-str through all failure modes neutralised.
   Every downstream dep is stubbed so the property is about the control-flow shell."
  [{:keys [task params]}]
  (with-redefs [augment/try-compressed (constantly nil)
                augment/try-unified    (constantly nil)
                augment/try-legacy     (constantly nil)]
    (resolve-ctx task [] params)))

(props/defprop-total resolve-context-str-total
  safe-resolve-context
  gen-params
  {:num-tests 100
   :pred map?})

;; =============================================================================
;; Run tests
;; =============================================================================

(comment
  ;; Run tests in REPL
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'hive-mcp.agent.drone.augment-test))
