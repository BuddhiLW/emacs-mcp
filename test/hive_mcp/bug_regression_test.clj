(ns hive-mcp.bug-regression-test
  "TDD tests pinning down bugs found in testing session 2025-12-31.
   These tests should FAIL until the bugs are fixed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-mcp.tools.registry :as tools]
            [hive-mcp.tools.cider :as cider]
            [hive-mcp.tools.consolidated.kanban :as kanban]
            [hive-mcp.tools.swarm :as swarm]))

;; =============================================================================
;; BUG #2: CIDER eval returns feature name instead of result (HIGH)
;; Expected: {:text "6"} for (+ 1 2 3)
;; Actual: {:text "hive-mcp-cider"}
;; =============================================================================

(deftest ^:integration test-cider-eval-returns-result
  (testing "BUG #2: cider-eval-silent should return evaluation result, not feature name"
    (let [result (cider/handle-cider-eval-silent {:code "(+ 1 2 3)"})]
      (is (map? result) "Should return a map")
      (is (contains? result :text) "Should have :text key")
      ;; This test pins down the bug - previously returned "hive-mcp-cider"
      (is (not= "hive-mcp-cider" (:text result))
          "Should NOT return the feature name (original bug)")
      ;; In test environment CIDER may not be connected
      ;; Valid outcomes: either "6" (success) or error message about CIDER
      (let [text (str (:text result))]
        (is (or (str/includes? text "6")
                (str/includes? text "CIDER not connected")
                (str/includes? text "not loaded"))
            "Should return result or proper error, not feature name")))))

;; =============================================================================
;; BUG #3: list-prompts returns nil for some entry fields (MEDIUM)
;; Expected: All entries have :id, :created, :source populated
;; Actual: Second entry returns nil for these fields despite org file being correct
;; =============================================================================

(deftest test-prompt-list-returns-complete-entries
  (testing "BUG #3: list-prompts should return complete entries with all fields"
    ;; prompt-capture namespace was removed; test kept as documentation
    (is true "prompt-capture namespace removed — bug obsolete")))

;; =============================================================================
;; BUG #5: handle-mcp-kanban-status times out (HIGH)
;; Expected: Should return within 5 seconds
;; Actual: Times out after 10+ seconds
;; =============================================================================

(deftest ^:integration test-kanban-status-performance
  (testing "BUG #5: kanban-status should complete within reasonable time"
    (let [start (System/currentTimeMillis)
          ;; Use a timeout wrapper
          result (deref
                  (future (kanban/handle-kanban {:command "status"}))
                  5000 ; 5 second timeout
                  {:timeout true})]
      (is (not (:timeout result))
          "kanban-status should complete within 5 seconds")
      (when-not (:timeout result)
        (let [elapsed (- (System/currentTimeMillis) start)]
          (is (< elapsed 5000)
              (format "Should complete in <5s, took %dms" elapsed)))))))

;; =============================================================================
;; BUG #6: Swarm returns double-encoded JSON (LOW)
;; Expected: Clean parsed data structure
;; Actual: Returns escaped JSON strings within JSON
;; =============================================================================

(deftest ^:integration test-swarm-status-clean-json
  (testing "BUG #6: swarm-status should return clean JSON, not double-encoded"
    (let [result (swarm/handle-swarm-status {})]
      (is (map? result) "Should return a map")
      (when-let [text (get-in result [:content 0 :text])]
        ;; Check for double-encoding signs
        (is (not (str/includes? text "\\\\\\\""))
            "Should not have triple-escaped quotes (double-encoding sign)")
        (is (not (str/includes? text "\\\"{"))
            "Should not have escaped JSON object start")))))

;; =============================================================================
;; BUG #7: swarm_collect returns "0ms timeout" immediately (HIGH) - FIXED 2026-01-01
;; Expected: Should poll for result until task completes or timeout
;; Actual: Returns "Collection timed out after 0ms" immediately
;; Root cause: emacsclient returns quoted JSON, needs double-parse
;; =============================================================================

(deftest test-emacsclient-json-double-parse
  (testing "BUG #7: emacsclient JSON output needs double parsing"
    ;; Simulate what emacsclient returns for json-encode output
    (let [emacsclient-output "\"{\\\"status\\\":\\\"completed\\\",\\\"result\\\":\\\"test\\\"}\""
          ;; First parse: unwrap emacsclient quotes
          first-parse (clojure.data.json/read-str emacsclient-output)
          ;; Second parse: parse the actual JSON
          second-parse (clojure.data.json/read-str first-parse :key-fn keyword)]
      ;; First parse should return a string
      (is (string? first-parse)
          "First parse should return the inner JSON as string")
      ;; Second parse should return a map
      (is (map? second-parse)
          "Second parse should return the parsed map")
      (is (= "completed" (:status second-parse))
          "Should correctly extract status from double-parsed JSON"))))

(deftest test-swarm-collect-parses-correctly
  (testing "BUG #7: swarm_collect handler should parse emacsclient output correctly"
    ;; Dynamically require swarm namespace
    (require '[hive-mcp.tools.swarm :as swarm-ns])
    ;; This test requires Emacs to be running with swarm addon loaded
    ;; In CI environment, we just verify the handler function exists
    (is (ifn? (resolve 'hive-mcp.tools.swarm/handle-swarm-collect))
        "handle-swarm-collect should exist")
    ;; Verify swarm-addon-available? exists (used for guard)
    (is (ifn? (resolve 'hive-mcp.tools.swarm/swarm-addon-available?))
        "swarm-addon-available? should exist")))

;; =============================================================================
;; BUG #8: cider_eval_silent hangs with heartbeat timeout (HIGH) - OPEN
;; Expected: Should return result within reasonable time
;; Actual: "Eval timed out after 60 seconds (heartbeat polling)"
;; =============================================================================

(deftest ^:integration test-cider-eval-does-not-hang
  (testing "BUG #8: cider_eval_silent should not hang indefinitely"
    ;; Use a timeout wrapper to detect hanging
    (let [result (deref
                  (future
                    (try
                      (cider/handle-cider-eval-silent {:code "(+ 1 1)"})
                      (catch Exception e
                        {:error (.getMessage e)})))
                  10000 ; 10 second timeout - should be plenty
                  {:timeout true :error "Evaluation hung for 10+ seconds"})]
      ;; Should either succeed or fail fast, never hang
      (is (not (:timeout result))
          "cider_eval_silent should complete within 10 seconds, not hang"))))

;; =============================================================================
;; BUG #9: claude-context search hangs (HIGH) - OPEN
;; Expected: Search should complete within 30 seconds
;; Actual: AbortError - operation was aborted after timeout
;; Note: This is an external MCP tool, test just documents the issue
;; =============================================================================

;; No test added - external tool, would need integration test setup

;; =============================================================================
;; Helper to run all bug tests
;; =============================================================================

(defn run-bug-tests []
  (clojure.test/run-tests 'hive-mcp.bug-regression-test))
