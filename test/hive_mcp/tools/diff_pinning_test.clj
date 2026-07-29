(ns hive-mcp.tools.diff-pinning-test
  "Pinning tests for drone diff workflow tools.

   Tests verify:
   - propose_diff stores diffs in pending atom
   - list_proposed_diffs returns all pending diffs
   - apply_diff applies changes and removes from pending
   - reject_diff discards without applying
   - Path resolution uses correct project root (BUG FIX)

   Uses with-redefs to mock file operations for isolated unit testing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [hive-mcp.tools.diff :as diff]
            [hive-mcp.tools.diff.validation :as diff-validation]
            [hive-mcp.agent.context :as ctx]
            [hive-dsl.bounded-atom :refer [bput! bget bcount bclear!]]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn gen-diff-id
  "Generate unique diff ID for testing."
  []
  (str "diff-" (java.util.UUID/randomUUID)))

(defn make-test-diff
  "Create a test diff proposal."
  [& {:keys [id file-path old-content new-content description drone-id]
      :or {id (gen-diff-id)
           file-path "/test/file.clj"
           old-content "(defn foo [] 1)"
           new-content "(defn foo [] 2)"
           description "Fix return value"
           drone-id "drone-1"}}]
  {:id id
   :file-path file-path
   :old-content old-content
   :new-content new-content
   :description description
   :drone-id drone-id})

(defn parse-response-text
  "Parse the JSON text from a handler response."
  [response]
  (json/read-str (:text response) :key-fn keyword))

(defn clear-pending-diffs!
  "Clear all pending diffs for test isolation."
  []
  (bclear! diff/pending-diffs))

(defn mock-validate-path
  "Mock path validation to always succeed for testing."
  ([file-path] (mock-validate-path file-path nil))
  ([file-path _project-root]
   {:valid true :resolved-path file-path}))

(defn mock-file-exists
  "Create a mock File that reports .exists as true."
  [path]
  (proxy [java.io.File] [path]
    (exists [] true)
    (getParentFile [] nil)))

(use-fixtures :each (fn [f]
                      (clear-pending-diffs!)
                      (f)
                      (clear-pending-diffs!)))

(defmacro with-mock-path-validation
  "Run body with path validation mocked to always succeed.
   Targets the original var in validation ns (not the facade re-export)
   since handlers.clj calls validation/validate-diff-path directly."
  [& body]
  `(with-redefs [diff-validation/validate-diff-path mock-validate-path]
     ~@body))

;; =============================================================================
;; Test: Response Format Contract
;; =============================================================================

(deftest test-response-format-success
  (testing "Success response has correct structure"
    (with-mock-path-validation
      (let [response (diff/handle-propose-diff
                      {:file_path "/test/file.clj"
                       :old_content "(defn foo [] 1)"
                       :new_content "(defn foo [] 2)"
                       :description "Fix return value"
                       :drone_id "drone-1"})]
        (is (= "text" (:type response)))
        (is (string? (:text response)))
        (is (nil? (:isError response)))))))

(deftest test-response-format-error
  (testing "Error response has correct structure when missing required fields"
    (with-mock-path-validation
      (let [response (diff/handle-propose-diff
                      {:file_path "/test/file.clj"})]
        (is (= "text" (:type response)))
        (is (string? (:text response)))
        (is (true? (:isError response)))))))

;; =============================================================================
;; Test: handle-propose-diff
;; =============================================================================

(deftest test-propose-diff-stores-in-atom
  (testing "propose_diff stores diff in pending-diffs atom"
    (with-mock-path-validation
      (let [response (diff/handle-propose-diff
                      {:file_path "/src/core.clj"
                       :old_content "(defn add [a b] (+ a b))"
                       :new_content "(defn add [a b]\n  \"Add two numbers.\"\n  (+ a b))"
                       :description "Add docstring"
                       :drone_id "drone-worker-1"})
            parsed (parse-response-text response)]
        (is (nil? (:isError response)))
        (is (string? (:id parsed)))
        (is (= "pending" (:status parsed)))
        ;; Verify stored in atom
        (is (= 1 (bcount diff/pending-diffs)))
        (let [stored (bget diff/pending-diffs (:id parsed))]
          (is (= "/src/core.clj" (:file-path stored)))
          (is (= "Add docstring" (:description stored)))
          (is (= "drone-worker-1" (:drone-id stored))))))))

(deftest test-propose-diff-generates-hunks
  (testing "propose_diff computes hunks and metrics (ADR 20260125002853)"
    (with-mock-path-validation
      (let [response (diff/handle-propose-diff
                      {:file_path "/src/core.clj"
                       :old_content "line1\nline2\nline3"
                       :new_content "line1\nmodified\nline3"
                       :description "Modify line 2"
                       :drone_id "drone-1"})
            parsed (parse-response-text response)]
        (is (nil? (:isError response)))
        (let [stored (bget diff/pending-diffs (:id parsed))]
          ;; Hunks are stored for tier-2 retrieval
          (is (vector? (:hunks stored)))
          (is (= 1 (count (:hunks stored))))
          ;; Metrics are stored for tier-1 listing
          (is (map? (:metrics stored)))
          (is (= 1 (:lines-removed (:metrics stored))))
          (is (= 1 (:lines-added (:metrics stored)))))))))

(deftest test-propose-diff-missing-required-fields
  (testing "Returns error when required fields missing"
    (with-mock-path-validation
      ;; Missing new_content
      (let [response (diff/handle-propose-diff
                      {:file_path "/test/file.clj"
                       :old_content "old"
                       :drone_id "drone-1"})
            parsed (parse-response-text response)]
        (is (true? (:isError response)))
        (is (re-find #"(?i)required|missing" (:error parsed))))
      ;; Missing file_path
      (let [response (diff/handle-propose-diff
                      {:old_content "old"
                       :new_content "new"
                       :drone_id "drone-1"})]
        (is (true? (:isError response)))))))

(deftest test-propose-diff-multiple-diffs
  (testing "Can store multiple pending diffs"
    (with-mock-path-validation
      (diff/handle-propose-diff
       {:file_path "/src/a.clj" :old_content "a" :new_content "a2"
        :description "Fix A" :drone_id "drone-1"})
      (diff/handle-propose-diff
       {:file_path "/src/b.clj" :old_content "b" :new_content "b2"
        :description "Fix B" :drone_id "drone-2"})
      (diff/handle-propose-diff
       {:file_path "/src/c.clj" :old_content "c" :new_content "c2"
        :description "Fix C" :drone_id "drone-1"})
      (is (= 3 (bcount diff/pending-diffs))))))

;; =============================================================================
;; Test: CLARITY-Y Empty Content Validation
;; =============================================================================

(deftest test-propose-diff-rejects-empty-new-content
  (testing "propose_diff rejects empty new_content (CLARITY-Y)"
    (with-mock-path-validation
      ;; Empty string
      (let [response (diff/handle-propose-diff
                      {:file_path "/src/test.clj"
                       :old_content "(defn foo [] 1)"
                       :new_content ""
                       :description "Empty change"
                       :drone_id "drone-1"})
            parsed (parse-response-text response)]
        (is (true? (:isError response)))
        (is (re-find #"(?i)empty|whitespace" (:error parsed)))
        (is (= 0 (bcount diff/pending-diffs)) "No diff should be stored"))))

  (testing "propose_diff rejects whitespace-only new_content"
    (with-mock-path-validation
      ;; Whitespace only
      (let [response (diff/handle-propose-diff
                      {:file_path "/src/test.clj"
                       :old_content "(defn foo [] 1)"
                       :new_content "   \n\t  "
                       :description "Whitespace change"
                       :drone_id "drone-1"})
            parsed (parse-response-text response)]
        (is (true? (:isError response)))
        (is (re-find #"(?i)empty|whitespace" (:error parsed)))
        (is (= 0 (bcount diff/pending-diffs)) "No diff should be stored")))))

(deftest test-apply-diff-blocks-empty-new-content-defense-in-depth
  (testing "apply_diff blocks empty new_content even if diff was stored (defense-in-depth)"
    ;; Manually inject a diff with empty content (simulating a bug bypass)
    (let [diff-id "test-empty-diff-12345"]
      (bput! diff/pending-diffs diff-id
             {:id diff-id
              :file-path "/src/test.clj"
              :old-content ""
              :new-content ""  ;; Empty content - should be blocked
              :description "Bug bypass test"
              :drone-id "drone-1"
              :unified-diff ""
              :status "pending"
              :created-at (java.time.Instant/now)})
      (let [response (diff/handle-apply-diff {:diff_id diff-id})
            parsed (parse-response-text response)]
        (is (true? (:isError response)))
        (is (re-find #"(?i)empty|whitespace" (:error parsed)))
        ;; Diff should be removed after blocked apply
        (is (nil? (bget diff/pending-diffs diff-id))))))

  (testing "apply_diff blocks whitespace-only new_content"
    (let [diff-id "test-whitespace-diff-12345"]
      (bput! diff/pending-diffs diff-id
             {:id diff-id
              :file-path "/src/test.clj"
              :old-content "(defn foo [] 1)"
              :new-content "   \n\t  "  ;; Whitespace only
              :description "Whitespace bypass test"
              :drone-id "drone-1"
              :unified-diff ""
              :status "pending"
              :created-at (java.time.Instant/now)})
      (let [response (diff/handle-apply-diff {:diff_id diff-id})
            parsed (parse-response-text response)]
        (is (true? (:isError response)))
        (is (re-find #"(?i)empty|whitespace" (:error parsed)))))))

;; =============================================================================
;; Test: handle-list-proposed-diffs
;; =============================================================================

(deftest test-list-proposed-diffs-empty
  (testing "Returns empty list when no pending diffs"
    (let [response (diff/handle-list-proposed-diffs {})
          parsed (parse-response-text response)]
      (is (nil? (:isError response)))
      (is (= [] (:diffs parsed)))
      (is (= 0 (:count parsed))))))

(deftest test-list-proposed-diffs-with-entries
  (testing "Returns all pending diffs with tier-1 metadata only (ADR 20260125002853)"
    (with-mock-path-validation
      ;; Add some diffs
      (diff/handle-propose-diff
       {:file_path "/src/a.clj" :old_content "a" :new_content "a2"
        :description "Fix A" :drone_id "drone-1"})
      (diff/handle-propose-diff
       {:file_path "/src/b.clj" :old_content "b" :new_content "b2"
        :description "Fix B" :drone_id "drone-2"})

      (let [response (diff/handle-list-proposed-diffs {})
            parsed (parse-response-text response)]
        (is (nil? (:isError response)))
        (is (= 2 (:count parsed)))
        (is (= 2 (count (:diffs parsed))))
        ;; Tier 1: metadata only - check required fields
        (doseq [d (:diffs parsed)]
          (is (contains? d :id))
          (is (contains? d :file-path))
          (is (contains? d :description))
          (is (contains? d :drone-id))
          (is (contains? d :metrics))  ;; Tier 1 includes metrics
          ;; Tier 1 does NOT include content or hunks
          (is (not (contains? d :old-content)))
          (is (not (contains? d :new-content)))
          (is (not (contains? d :hunks)))
          (is (not (contains? d :unified-diff))))))))

(deftest test-list-proposed-diffs-filter-by-drone
  (testing "Can filter diffs by drone_id"
    (with-mock-path-validation
      (diff/handle-propose-diff
       {:file_path "/src/a.clj" :old_content "a" :new_content "a2"
        :description "Fix A" :drone_id "drone-1"})
      (diff/handle-propose-diff
       {:file_path "/src/b.clj" :old_content "b" :new_content "b2"
        :description "Fix B" :drone_id "drone-2"})
      (diff/handle-propose-diff
       {:file_path "/src/c.clj" :old_content "c" :new_content "c2"
        :description "Fix C" :drone_id "drone-1"})

      (let [response (diff/handle-list-proposed-diffs {:drone_id "drone-1"})
            parsed (parse-response-text response)]
        (is (nil? (:isError response)))
        (is (= 2 (:count parsed)))
        (is (every? #(= "drone-1" (:drone-id %)) (:diffs parsed)))))))

;; =============================================================================
;; Test: handle-get-diff-details (Tier 2)
;; =============================================================================

(deftest test-get-diff-details-returns-unified-diff-on-demand
  (testing "get_diff_details returns tier-2 response with formatted hunks (ADR 20260125002853)"
    (with-mock-path-validation
      (let [response (diff/handle-propose-diff
                      {:file_path "/src/core.clj"
                       :old_content "line1\nline2\nline3"
                       :new_content "line1\nmodified\nline3"
                       :description "Modify line 2"
                       :drone_id "drone-1"})
            diff-id (:id (parse-response-text response))
            details-response (diff/handle-get-diff-details {:diff_id diff-id})
            parsed (parse-response-text details-response)]
        (is (nil? (:isError details-response)))
        ;; Tier 2: includes formatted unified diff
        (is (contains? parsed :unified-diff))
        (is (string? (:unified-diff parsed)))
        (is (re-find #"-line2" (:unified-diff parsed)))
        (is (re-find #"\+modified" (:unified-diff parsed)))
        ;; Tier 2: includes hunks
        (is (contains? parsed :hunks))
        ;; Tier 2: does NOT include raw content
        (is (not (contains? parsed :old-content)))
        (is (not (contains? parsed :new-content)))))))

;; =============================================================================
;; Test: handle-apply-diff
;; =============================================================================

(deftest test-apply-diff-success
  (testing "apply_diff applies changes and removes from pending"
    (with-mock-path-validation
      ;; Setup: propose a diff
      (let [propose-response (diff/handle-propose-diff
                              {:file_path "/src/test.clj"
                               :old_content "(defn test [] nil)"
                               :new_content "(defn test [] :ok)"
                               :description "Return :ok"
                               :drone_id "drone-1"})
            diff-id (:id (parse-response-text propose-response))
            file-content (atom "(defn test [] nil)")]
        ;; Mock file operations including io/file for .exists check
        (with-redefs [io/file mock-file-exists
                      slurp (fn [_] @file-content)
                      spit (fn [_ content] (reset! file-content content))]
          (let [apply-response (diff/handle-apply-diff {:diff_id diff-id})
                parsed (parse-response-text apply-response)]
            (is (nil? (:isError apply-response)))
            (is (= "applied" (:status parsed)))
            (is (= diff-id (:id parsed)))
            ;; Verify removed from pending
            (is (= 0 (bcount diff/pending-diffs)))
            ;; Verify file was updated
            (is (= "(defn test [] :ok)" @file-content))))))))

(deftest test-apply-diff-not-found
  (testing "Returns error when diff_id not found"
    (let [response (diff/handle-apply-diff {:diff_id "nonexistent-id"})
          parsed (parse-response-text response)]
      (is (true? (:isError response)))
      (is (re-find #"(?i)not found" (:error parsed))))))

(deftest test-apply-diff-content-mismatch
  (testing "Returns error when old_content not found in file"
    (with-mock-path-validation
      (let [propose-response (diff/handle-propose-diff
                              {:file_path "/src/test.clj"
                               :old_content "(defn test [] nil)"
                               :new_content "(defn test [] :ok)"
                               :description "Return :ok"
                               :drone_id "drone-1"})
            diff-id (:id (parse-response-text propose-response))]
        ;; Mock file with different content - old_content won't be found
        (with-redefs [slurp (fn [_] "(defn test [] :already-changed)")]
          (let [apply-response (diff/handle-apply-diff {:diff_id diff-id})
                parsed (parse-response-text apply-response)]
            (is (true? (:isError apply-response)))
            (is (re-find #"(?i)not found|modified" (:error parsed)))
            ;; Diff should still be pending (not removed on error)
            (is (= 1 (bcount diff/pending-diffs)))))))))

(deftest test-apply-diff-missing-diff-id
  (testing "Returns error when diff_id not provided"
    (let [response (diff/handle-apply-diff {})
          parsed (parse-response-text response)]
      (is (true? (:isError response)))
      (is (re-find #"(?i)required|missing" (:error parsed))))))

(deftest test-apply-diff-substring-replacement
  (testing "apply_diff replaces old_content substring within larger file"
    (with-mock-path-validation
      (let [propose-response (diff/handle-propose-diff
                              {:file_path "/src/test.clj"
                               :old_content "(defn foo [] 1)"
                               :new_content "(defn foo [] 42)"
                               :description "Change return value"
                               :drone_id "drone-1"})
            diff-id (:id (parse-response-text propose-response))
            ;; File contains the target function plus other code
            full-file-content "(ns my.ns)\n\n(defn foo [] 1)\n\n(defn bar [] 2)"]
        (with-redefs [io/file mock-file-exists
                      slurp (fn [_] full-file-content)
                      spit (fn [_ content]
                             ;; Verify only foo was changed, bar untouched
                             (is (= "(ns my.ns)\n\n(defn foo [] 42)\n\n(defn bar [] 2)" content)))]
          (let [apply-response (diff/handle-apply-diff {:diff_id diff-id})
                parsed (parse-response-text apply-response)]
            (is (nil? (:isError apply-response)))
            (is (= "applied" (:status parsed)))))))))

(deftest test-apply-diff-multiple-matches-rejected
  (testing "apply_diff rejects when old_content appears multiple times"
    (with-mock-path-validation
      (let [propose-response (diff/handle-propose-diff
                              {:file_path "/src/test.clj"
                               :old_content "(defn foo [] 1)"
                               :new_content "(defn foo [] 42)"
                               :description "Change return value"
                               :drone_id "drone-1"})
            diff-id (:id (parse-response-text propose-response))
            ;; File contains foo TWICE - ambiguous
            ambiguous-content "(defn foo [] 1)\n\n(defn bar [] 2)\n\n(defn foo [] 1)"]
        (with-redefs [io/file mock-file-exists
                      slurp (fn [_] ambiguous-content)]
          (let [apply-response (diff/handle-apply-diff {:diff_id diff-id})
                parsed (parse-response-text apply-response)]
            (is (true? (:isError apply-response)))
            (is (re-find #"(?i)multiple|ambiguous" (:error parsed)))
            ;; Diff should remain pending
            (is (= 1 (bcount diff/pending-diffs)))))))))

(deftest test-sandbox-path-translation
  (testing "translate-sandbox-path converts /tmp/fs-N/ paths to project paths"
    (with-redefs [diff-validation/get-project-root (constantly "/home/user/project")]
      (is (= "/home/user/project/src/foo.clj"
             (diff/translate-sandbox-path "/tmp/fs-1/src/foo.clj")))
      (is (= "/home/user/project/test/bar_test.clj"
             (diff/translate-sandbox-path "/tmp/fs-42/test/bar_test.clj")))
      ;; Non-sandbox paths pass through unchanged
      (is (= "/home/user/project/src/foo.clj"
             (diff/translate-sandbox-path "/home/user/project/src/foo.clj")))
      (is (= "src/relative.clj"
             (diff/translate-sandbox-path "src/relative.clj"))))))

;; =============================================================================
;; Test: handle-reject-diff
;; =============================================================================

(deftest test-reject-diff-success
  (testing "reject_diff removes diff without applying"
    (with-mock-path-validation
      (let [propose-response (diff/handle-propose-diff
                              {:file_path "/src/test.clj"
                               :old_content "old"
                               :new_content "new"
                               :description "Change"
                               :drone_id "drone-1"})
            diff-id (:id (parse-response-text propose-response))
            file-content (atom "old")]
        (with-redefs [slurp (fn [_] @file-content)
                      spit (fn [_ content] (reset! file-content content))]
          (let [reject-response (diff/handle-reject-diff {:diff_id diff-id})
                parsed (parse-response-text reject-response)]
            (is (nil? (:isError reject-response)))
            (is (= "rejected" (:status parsed)))
            ;; Verify removed from pending
            (is (= 0 (bcount diff/pending-diffs)))
            ;; Verify file was NOT changed
            (is (= "old" @file-content))))))))

(deftest test-reject-diff-not-found
  (testing "Returns error when diff_id not found"
    (let [response (diff/handle-reject-diff {:diff_id "nonexistent-id"})
          parsed (parse-response-text response)]
      (is (true? (:isError response)))
      (is (re-find #"(?i)not found" (:error parsed))))))

(deftest test-reject-diff-with-reason
  (testing "reject_diff can include rejection reason"
    (with-mock-path-validation
      (let [propose-response (diff/handle-propose-diff
                              {:file_path "/src/test.clj"
                               :old_content "old"
                               :new_content "new"
                               :description "Change"
                               :drone_id "drone-1"})
            diff-id (:id (parse-response-text propose-response))
            reject-response (diff/handle-reject-diff
                             {:diff_id diff-id
                              :reason "Change not needed"})
            parsed (parse-response-text reject-response)]
        (is (nil? (:isError reject-response)))
        (is (= "rejected" (:status parsed)))
        (is (= "Change not needed" (:reason parsed)))))))

;; =============================================================================
;; Test: Tool definitions
;; =============================================================================

;; =============================================================================
;; Test: Path Resolution with Project Root Override (BUG FIX)
;; =============================================================================
;; CLARITY-I: Validates inputs at test boundaries
;; DDD: Domain language - "path resolution", "project root", "drone directory"

(deftest test-validate-diff-path-uses-explicit-project-root
  (testing "validate-diff-path resolves relative paths against explicit project root"
    ;; CLARITY-R: Clear intent - relative path should resolve within given root
    (let [result (diff/validate-diff-path "src/core.clj" "/home/user/my-project")]
      (is (:valid result))
      (is (= "/home/user/my-project/src/core.clj" (:resolved-path result)))))

  (testing "validate-diff-path rejects paths escaping explicit project root"
    ;; CLARITY-Y: Test error path - graceful degradation
    (let [result (diff/validate-diff-path "../../../etc/passwd" "/home/user/my-project")]
      (is (not (:valid result)))
      (is (re-find #"(?i)escapes.*project" (:error result))))))

(deftest test-validate-diff-path-detects-cross-project-escape
  (testing "Absolute path from project A fails validation against project B root"
    ;; This was the original bug - drone working on one project
    ;; but MCP server at a different project root
    ;; Create real temp directories to test path escaping logic
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          project-a (str temp-dir "/test-project-a-" (System/currentTimeMillis))
          project-b (str temp-dir "/test-project-b-" (System/currentTimeMillis))
          test-file (str project-a "/internal/config.go")]
      ;; Setup: create project-a with the file
      (io/make-parents test-file)
      (spit test-file "package config")
      (io/make-parents (str project-b "/.keep"))
      (try
        (let [result (diff/validate-diff-path test-file project-b)]
          (is (not (:valid result)))
          (is (re-find #"(?i)escapes.*project" (:error result))))
        (finally
          (io/delete-file test-file true)
          (io/delete-file (str project-a "/internal") true)
          (io/delete-file project-a true)
          (io/delete-file project-b true)))))

  (testing "Absolute path within project root is valid"
    ;; Same path validated against correct root should work
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          project-a (str temp-dir "/test-project-valid-" (System/currentTimeMillis))
          test-file (str project-a "/internal/config.go")]
      ;; Setup: create project-a with the file
      (io/make-parents test-file)
      (spit test-file "package config")
      (try
        (let [result (diff/validate-diff-path test-file project-a)]
          (is (:valid result))
          (is (= test-file (:resolved-path result))))
        (finally
          (io/delete-file test-file true)
          (io/delete-file (str project-a "/internal") true)
          (io/delete-file project-a true))))))

(deftest test-propose-diff-uses-directory-parameter-for-path-validation
  (testing "propose_diff uses directory parameter as project root"
    ;; SOLID-S: Single test - directory param controls path validation
    ;; No fixture mock - we test real validation behavior
    (clear-pending-diffs!)
    (let [;; Create a temp directory structure for real validation
          temp-dir (System/getProperty "java.io.tmpdir")
          test-root (str temp-dir "/hive-test-" (System/currentTimeMillis))
          test-file (str test-root "/src/test.clj")]
      ;; Setup: create directory structure
      (io/make-parents test-file)
      (spit test-file "(ns test)")
      (try
        (let [response (diff/handle-propose-diff
                        {:file_path "src/test.clj"
                         :old_content "(ns test)"
                         :new_content "(ns test)\n;; updated"
                         :description "Add comment"
                         :drone_id "test-drone"
                         :directory test-root})
              parsed (parse-response-text response)]
          (is (nil? (:isError response))
              (str "Expected success but got: " (:error parsed)))
          (is (= "pending" (:status parsed)))
          ;; Verify resolved path uses our test root
          (is (= test-file (:file-path parsed))))
        (finally
          ;; Cleanup
          (io/delete-file test-file true)
          (io/delete-file (str test-root "/src") true)
          (io/delete-file test-root true)
          (clear-pending-diffs!))))))

(deftest test-propose-diff-uses-context-directory-for-path-validation
  (testing "propose_diff uses ctx/current-directory when no explicit directory"
    ;; SOLID-D: Dependency injection - context provides directory
    (clear-pending-diffs!)
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          test-root (str temp-dir "/hive-ctx-test-" (System/currentTimeMillis))
          test-file (str test-root "/src/ctx-test.clj")]
      ;; Setup
      (io/make-parents test-file)
      (spit test-file "(ns ctx-test)")
      (try
        ;; Bind context like wrap-handler-context does
        (ctx/with-request-context {:directory test-root}
          (let [response (diff/handle-propose-diff
                          {:file_path "src/ctx-test.clj"
                           :old_content "(ns ctx-test)"
                           :new_content "(ns ctx-test)\n;; from context"
                           :description "Context test"
                           :drone_id "context-drone"})
                parsed (parse-response-text response)]
            (is (nil? (:isError response))
                (str "Expected success but got: " (:error parsed)))
            (is (= "pending" (:status parsed)))
            (is (= test-file (:file-path parsed)))))
        (finally
          (io/delete-file test-file true)
          (io/delete-file (str test-root "/src") true)
          (io/delete-file test-root true)
          (clear-pending-diffs!))))))

(deftest test-propose-diff-directory-param-overrides-context
  (testing "Explicit directory parameter takes precedence over context"
    ;; CLARITY-C: Compose behaviors - explicit > implicit
    (clear-pending-diffs!)
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          explicit-root (str temp-dir "/explicit-" (System/currentTimeMillis))
          context-root (str temp-dir "/context-" (System/currentTimeMillis))
          test-file (str explicit-root "/override.clj")]
      ;; Only create the explicit root directory
      (io/make-parents test-file)
      (spit test-file "(ns override)")
      (try
        ;; Context has different directory, but explicit param should win
        (ctx/with-request-context {:directory context-root}
          (let [response (diff/handle-propose-diff
                          {:file_path "override.clj"
                           :old_content "(ns override)"
                           :new_content "(ns override)\n;; explicit wins"
                           :description "Override test"
                           :drone_id "override-drone"
                           :directory explicit-root})
                parsed (parse-response-text response)]
            (is (nil? (:isError response))
                (str "Expected success but got: " (:error parsed)))
            ;; Should resolve against explicit-root, not context-root
            (is (= test-file (:file-path parsed)))))
        (finally
          (io/delete-file test-file true)
          (io/delete-file explicit-root true)
          (clear-pending-diffs!))))))
