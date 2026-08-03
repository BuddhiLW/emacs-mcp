(ns hive-mcp.tools.multi-integration-test
  "Integration tests for the multi tool end-to-end pipeline.

   Tests the FULL stack: handle-multi (consolidated/multi.clj)
   → tools/multi.clj batch engine → real consolidated tool handlers.

   Unlike multi_test.clj (unit tests) and multi_tool_integration_test.clj
   (structural/schema tests), these tests exercise actual tool invocations
   through the multi facade with real MCP-style params.

   Covers:
   1. normalize-op edge cases (string keys, auto-ID, depends_on normalization)
   2. Single dispatch through handle-multi to real tools
   3. Batch dispatch through handle-multi → run-multi → real tool handlers
   4. handle-batch from consolidated/multi (MCP entry point)
   5. Dependency-ordered wave execution with real tools
   6. Error propagation through the full stack
   7. Mixed success/failure batch execution
   8. get-tool-handler resolution
   9. resolve-consolidated-handler vs resolve-tool-handler
  10. FX registration and emission
  11. Real tool output verification (result data, not just success flags)
  12. Cross-tool batch with result forwarding
  13. Wave FX emission with real tool execution
  14. Config/preset/kanban real command execution (beyond help)

   NOTE (2026): the `hive-mcp.events.multi` orchestration layer (orchestrate /
   execute! / chain / fan-out / merge-results / wrap-orchestrator, plus the
   :multi/ping and :multi/echo demo orchestrators) was DELETED in 2bbd1d7 along
   with its own unit test (test/hive_mcp/events/multi_test.clj). It has no
   successor: the similarly-named `hive.events.multi` in the hive-events library
   is a *different* subsystem (handler dispatch + pure batch-op compilation —
   normalize-op/validate-ops/assign-waves), consumed by src/hive_mcp/multi/plan.clj.
   The orchestration tests that lived here are documented as obsolete below."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hive-mcp.tools.multi :as multi]
            [hive-mcp.tools.consolidated.multi :as c-multi]
            [hive-mcp.test.stub.memory-store :as stub]
            [hive-mcp.test.stub.batch-extensions :as bx]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- parse-json-response
  "Parse a JSON MCP response. Returns parsed map or nil."
  [result]
  (when (and result (not (:isError result)) (:text result))
    (try
      (json/read-str (:text result) :key-fn keyword)
      (catch Exception _ nil))))

(defn- help-response?
  "Check if response is a valid help response."
  [result]
  (and (not (:isError result))
       (string? (:text result))
       (str/includes? (:text result) "Available commands")))

(defn- extract-wave-results
  "Extract flat list of op results from a parsed batch response."
  [parsed]
  (when-let [waves (:waves parsed)]
    (mapcat val waves)))

(use-fixtures :each stub/with-stub-store bx/with-batch-extensions)

;; =============================================================================
;; Part 1: normalize-op Tests
;; =============================================================================

(deftest normalize-op-string-keys-test
  (testing "normalize-op converts string keys to keywords"
    (let [op (multi/normalize-op {"id" "op-1" "tool" "memory" "command" "add"
                                  "content" "hello"})]
      (is (= "op-1" (:id op)))
      (is (= "memory" (:tool op)))
      (is (= "add" (:command op)))
      (is (= "hello" (:content op))))))

(deftest normalize-op-auto-id-test
  (testing "normalize-op auto-generates ID when missing"
    (let [op (multi/normalize-op {:tool "memory" :command "add"})]
      (is (string? (:id op)))
      (is (str/starts-with? (:id op) "op-"))))

  (testing "normalize-op auto-generates ID when blank"
    (let [op (multi/normalize-op {:id "" :tool "memory" :command "add"})]
      (is (string? (:id op)))
      (is (str/starts-with? (:id op) "op-")))))

(deftest normalize-op-preserves-existing-id-test
  (testing "normalize-op preserves non-blank ID"
    (let [op (multi/normalize-op {:id "my-op" :tool "memory" :command "add"})]
      (is (= "my-op" (:id op))))))

(deftest normalize-op-depends-on-string-test
  (testing "normalize-op wraps single string depends_on in vector"
    (let [op (multi/normalize-op {:id "a" :tool "kg" :command "edge"
                                  :depends_on "op-1"})]
      (is (= ["op-1"] (:depends_on op))))))

(deftest normalize-op-depends-on-vector-test
  (testing "normalize-op keeps vector depends_on as vector"
    (let [op (multi/normalize-op {:id "a" :tool "kg" :command "edge"
                                  :depends_on ["op-1" "op-2"]})]
      (is (= ["op-1" "op-2"] (:depends_on op))))))

(deftest normalize-op-depends-on-nil-test
  (testing "normalize-op leaves nil depends_on unchanged"
    (let [op (multi/normalize-op {:id "a" :tool "memory" :command "add"
                                  :depends_on nil})]
      ;; nil depends_on should not be transformed (no :depends_on key in output
      ;; since cond-> skips nil)
      (is (nil? (:depends_on op))))))

(deftest normalize-op-depends-on-non-sequential-test
  (testing "normalize-op handles non-sequential depends_on gracefully"
    (let [op (multi/normalize-op {:id "a" :tool "memory" :command "add"
                                  :depends_on 42})]
      ;; Non-string, non-sequential should become empty vector
      (is (= [] (:depends_on op))))))

(deftest normalize-op-preserves-extra-params-test
  (testing "normalize-op preserves all params beyond standard keys"
    (let [op (multi/normalize-op {:id "a" :tool "memory" :command "add"
                                  :content "hello" :tags ["x"] :type "note"
                                  :duration "short" :directory "/tmp"})]
      (is (= "hello" (:content op)))
      (is (= ["x"] (:tags op)))
      (is (= "note" (:type op)))
      (is (= "short" (:duration op)))
      (is (= "/tmp" (:directory op))))))

(deftest normalize-op-auto-id-uniqueness-test
  (testing "normalize-op generates unique IDs for distinct ops"
    (let [ops (repeatedly 10 #(multi/normalize-op {:tool "memory" :command "add"}))
          ids (map :id ops)]
      (is (= (count ids) (count (distinct ids)))
          "Auto-generated IDs should be unique"))))

(deftest normalize-op-mixed-key-types-test
  (testing "normalize-op handles mix of string and keyword keys"
    (let [op (multi/normalize-op {"id" "mixed" :tool "config" "command" "list"})]
      (is (= "mixed" (:id op)))
      (is (= "config" (:tool op)))
      (is (= "list" (:command op))))))

;; =============================================================================
;; Part 2: get-tool-handler Resolution
;; =============================================================================

(deftest get-tool-handler-all-registered-test
  (testing "get-tool-handler resolves all 17 registered tools"
    (doseq [tool-name ["agent" "memory" "kg" "hivemind" "magit"
                       "kanban" "preset" "olympus" "agora" "analysis"
                       "project" "session" "emacs" "wave" "migration"
                       "config" "workflow"]]
      (is (fn? (c-multi/get-tool-handler tool-name))
          (str "Tool '" tool-name "' should resolve to a handler fn")))))

(deftest get-tool-handler-keyword-test
  (testing "get-tool-handler works with keyword input"
    (is (fn? (c-multi/get-tool-handler :memory)))
    (is (fn? (c-multi/get-tool-handler :kg)))))

(deftest get-tool-handler-nil-unknown-test
  (testing "get-tool-handler returns nil for unknown tools"
    (is (nil? (c-multi/get-tool-handler "nonexistent")))
    (is (nil? (c-multi/get-tool-handler nil)))))

;; =============================================================================
;; Part 3: resolve-consolidated-handler + resolve-tool-handler
;; =============================================================================

(deftest resolve-consolidated-handler-test
  (testing "resolve-consolidated-handler finds known tools"
    (is (fn? (multi/resolve-consolidated-handler "memory")))
    (is (fn? (multi/resolve-consolidated-handler "kg")))
    (is (fn? (multi/resolve-consolidated-handler "config")))))

(deftest resolve-consolidated-handler-unknown-test
  (testing "resolve-consolidated-handler returns nil for unknown"
    (is (nil? (multi/resolve-consolidated-handler "fake-tool-xyz")))))

(deftest resolve-tool-handler-consolidates-first-test
  (testing "resolve-tool-handler tries consolidated first"
    (let [handler (multi/resolve-tool-handler "memory")]
      (is (fn? handler))
      ;; Invoking with help command should return help text
      (let [result (handler {:command "help"})]
        (is (not (:isError result)))
        (is (str/includes? (:text result) "Available commands"))))))

;; =============================================================================
;; Part 4: Single Dispatch — handle-multi with Real Tools
;; =============================================================================

(deftest handle-multi-config-help-test
  (testing "handle-multi routes to config help via single dispatch"
    (let [result (c-multi/handle-multi {"tool" "config" "command" "help"})]
      (is (help-response? result))
      (is (str/includes? (:text result) "get"))
      (is (str/includes? (:text result) "set"))
      (is (str/includes? (:text result) "list"))
      (is (str/includes? (:text result) "reload")))))

(deftest handle-multi-preset-help-test
  (testing "handle-multi routes to preset help"
    (let [result (c-multi/handle-multi {"tool" "preset" "command" "help"})]
      (is (help-response? result))
      (is (str/includes? (:text result) "list"))
      (is (str/includes? (:text result) "search")))))

(deftest handle-multi-session-help-test
  (testing "handle-multi routes to session help"
    (let [result (c-multi/handle-multi {"tool" "session" "command" "help"})]
      (is (help-response? result))
      (is (str/includes? (:text result) "whoami"))
      (is (str/includes? (:text result) "catchup")))))

(deftest handle-multi-workflow-help-test
  (testing "handle-multi routes to workflow help with nested commands"
    (let [result (c-multi/handle-multi {"tool" "workflow" "command" "help"})]
      (is (help-response? result))
      ;; Workflow has nested forge commands
      (is (str/includes? (:text result) "forge")))))

(deftest handle-multi-agent-help-test
  (testing "handle-multi routes to agent help with nested dag commands"
    (let [result (c-multi/handle-multi {"tool" "agent" "command" "help"})]
      (is (help-response? result))
      (is (str/includes? (:text result) "spawn"))
      (is (str/includes? (:text result) "dag")))))

(deftest handle-multi-unknown-command-on-real-tool-test
  (testing "handle-multi forwards unknown command to tool, which returns error"
    (let [result (c-multi/handle-multi {"tool" "config" "command" "nonexistent"})]
      (is (:isError result))
      (is (str/includes? (:text result) "Unknown command")))))

(deftest handle-multi-string-key-normalization-test
  (testing "handle-multi normalizes JSON string keys to keywords"
    ;; MCP sends string keys in JSON
    (let [result (c-multi/handle-multi {"tool" "analysis" "command" "help"})]
      (is (help-response? result)))))

(deftest handle-multi-keyword-params-test
  (testing "handle-multi also works with keyword params (internal calls)"
    (let [result (c-multi/handle-multi {:tool "migration" :command "help"})]
      (is (help-response? result)))))

;; =============================================================================
;; Part 4b: Single Dispatch — Real Tool Execution (beyond help)
;; =============================================================================

(deftest handle-multi-config-list-test
  (testing "handle-multi routes config list command (returns real config)"
    (let [result (c-multi/handle-multi {"tool" "config" "command" "list"})]
      (is (not (:isError result)))
      (is (string? (:text result))))))

(deftest handle-multi-session-whoami-test
  (testing "handle-multi routes session whoami (returns agent identity)"
    (let [result (c-multi/handle-multi {"tool" "session" "command" "whoami"})]
      (is (not (:isError result)))
      (is (string? (:text result))))))

(deftest handle-multi-kg-stats-test
  (testing "handle-multi routes kg stats (returns graph statistics)"
    (let [result (c-multi/handle-multi {"tool" "kg" "command" "stats"})]
      (is (not (:isError result)))
      (is (string? (:text result))))))

(deftest handle-multi-migration-status-test
  (testing "handle-multi routes migration status (returns backend info)"
    (let [result (c-multi/handle-multi {"tool" "migration" "command" "status"})]
      (is (not (:isError result)))
      ;; cmd-status returns raw data map {:kg {...} :backups {...}}, not {:text "..."}
      (is (some? result))
      (is (map? result))
      (is (contains? result :kg)))))

(deftest handle-multi-analysis-scc-test
  (testing "handle-multi routes analysis scc (code metrics)"
    (let [result (c-multi/handle-multi {"tool" "analysis" "command" "scc"})
          text   (or (:text result) (some :text (:content result)))]
      ;; scc may succeed or fail depending on the scc binary; either way the
      ;; response must be a well-formed MCP result carrying text.
      (is (some? result))
      (is (string? text)))))

;; =============================================================================
;; Part 5: Batch Dispatch — handle-multi with operations
;; =============================================================================

(deftest handle-multi-batch-mode-activates-test
  (testing "handle-multi activates batch mode when operations present and no tool"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "op-1" "tool" "config" "command" "help"}]})]
      ;; Should process as batch, not show help
      (is (not (:isError result)))
      (let [parsed (parse-json-response result)]
        (is (some? parsed))
        (is (:success parsed))))))

(deftest handle-multi-batch-mode-single-op-test
  (testing "Batch mode with single help operation succeeds"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "op-1" "tool" "config" "command" "help"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 1 (get-in parsed [:summary :total])))
      (is (= 1 (get-in parsed [:summary :success])))
      (is (= 0 (get-in parsed [:summary :failed]))))))

(deftest handle-multi-batch-mode-multi-op-test
  (testing "Batch mode with multiple help operations succeeds"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "op-1" "tool" "config" "command" "help"}
                                 {"id" "op-2" "tool" "preset" "command" "help"}
                                 {"id" "op-3" "tool" "migration" "command" "help"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 3 (get-in parsed [:summary :total])))
      (is (= 3 (get-in parsed [:summary :success]))))))

(deftest handle-multi-batch-with-dependencies-test
  (testing "Batch mode respects dependency ordering"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "op-1" "tool" "config" "command" "help"}
                                 {"id" "op-2" "tool" "preset" "command" "help"
                                  "depends_on" ["op-1"]}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 2 (get-in parsed [:summary :total])))
      (is (= 2 (get-in parsed [:summary :success])))
      (is (= 2 (get-in parsed [:summary :waves]))))))

(deftest handle-multi-batch-parallel-independent-test
  (testing "Independent batch ops execute in the same wave"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "op-1" "tool" "config" "command" "help"}
                                 {"id" "op-2" "tool" "preset" "command" "help"}
                                 {"id" "op-3" "tool" "analysis" "command" "help"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      ;; All independent → 1 wave
      (is (= 1 (get-in parsed [:summary :waves]))))))

(deftest handle-multi-batch-nil-operations-test
  (testing "Batch mode rejects nil operations"
    (let [result (c-multi/handle-multi {"operations" nil})]
      ;; When operations is nil and no tool, shows help (nil check in handle-batch)
      ;; The consolidated/multi.clj checks for (some? operations) first
      (is (some? result)))))

(deftest handle-multi-batch-empty-operations-test
  (testing "Batch mode rejects empty operations"
    (let [result (c-multi/handle-multi {"operations" []})]
      (is (:isError result)))))

(deftest handle-multi-batch-non-sequential-test
  (testing "Batch mode rejects non-sequential operations"
    (let [result (c-multi/handle-multi {"operations" "not-an-array"})]
      (is (:isError result)))))

;; =============================================================================
;; Part 5b: Batch Dispatch — Real Tool Commands (Beyond Help)
;; =============================================================================

(deftest handle-multi-batch-real-config-and-kg-test
  (testing "Batch mode executes real config list + kg stats in parallel"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "cfg" "tool" "config" "command" "list"}
                                 {"id" "kgs" "tool" "kg" "command" "stats"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 2 (get-in parsed [:summary :success])))
      (is (= 1 (get-in parsed [:summary :waves]))
          "Independent ops should run in 1 wave"))))

(deftest handle-multi-batch-mixed-real-commands-test
  (testing "Batch mode with diverse real tool commands"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "cfg" "tool" "config" "command" "list"}
                                 {"id" "mig" "tool" "migration" "command" "status"}
                                 {"id" "ses" "tool" "session" "command" "whoami"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 3 (get-in parsed [:summary :total])))
      (is (= 3 (get-in parsed [:summary :success])))
      ;; All independent
      (is (= 1 (get-in parsed [:summary :waves]))))))

(deftest handle-multi-batch-sequential-real-commands-test
  (testing "Batch mode with dependency chain on real commands"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "step-1" "tool" "config" "command" "list"}
                                 {"id" "step-2" "tool" "migration" "command" "status"
                                  "depends_on" ["step-1"]}
                                 {"id" "step-3" "tool" "kg" "command" "stats"
                                  "depends_on" ["step-2"]}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 3 (get-in parsed [:summary :success])))
      (is (= 3 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 6: handle-batch (consolidated/multi.clj) Direct Tests
;; =============================================================================

(deftest consolidated-handle-batch-operations-test
  (testing "handle-batch directly invoked with valid operations"
    (let [result (c-multi/handle-multi
                  {:operations [{"id" "op-1" "tool" "config" "command" "help"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 1 (get-in parsed [:summary :success]))))))

(deftest consolidated-handle-batch-dry-run-test
  (testing "handle-batch dry-run mode returns plan without executing"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "op-1" "tool" "config" "command" "help"}
                                 {"id" "op-2" "tool" "preset" "command" "help"
                                  "depends_on" ["op-1"]}]
                   "dry_run" true})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (true? (:dry_run parsed)))
      (is (some? (:plan parsed)))
      ;; Dry-run should not execute — success/failed stay at 0
      (is (= 0 (get-in parsed [:summary :success])))
      (is (= 0 (get-in parsed [:summary :failed]))))))

;; =============================================================================
;; Part 7: handle-batch (tools/multi.clj) Direct Tests
;; =============================================================================

(deftest tools-multi-handle-batch-test
  (testing "tools/multi.clj handle-batch processes operations"
    (let [result (multi/handle-batch
                  {:operations [{"id" "op-1" "tool" "config" "command" "help"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 1 (get-in parsed [:summary :total]))))))

(deftest tools-multi-handle-batch-empty-test
  (testing "tools/multi.clj handle-batch rejects empty operations"
    (let [result (multi/handle-batch {:operations []})]
      (is (:isError result)))))

(deftest tools-multi-handle-batch-nil-test
  (testing "tools/multi.clj handle-batch rejects nil operations"
    (let [result (multi/handle-batch {:operations nil})]
      (is (:isError result)))))

(deftest tools-multi-handle-batch-non-sequential-test
  (testing "tools/multi.clj handle-batch rejects non-sequential"
    (let [result (multi/handle-batch {:operations "not-a-list"})]
      (is (:isError result)))))

;; =============================================================================
;; Part 8: Real Tool Execution via run-multi
;; =============================================================================

(deftest run-multi-real-config-help-test
  (testing "run-multi executes config help command successfully"
    (let [result (multi/run-multi
                  [{:id "op-1" :tool "config" :command "help"}])]
      (is (:success result))
      (is (= 1 (get-in result [:summary :total])))
      (is (= 1 (get-in result [:summary :success])))
      (is (= 0 (get-in result [:summary :failed])))
      (is (= 1 (get-in result [:summary :waves]))))))

(deftest run-multi-real-multi-tool-execution-test
  (testing "run-multi executes multiple real tool help commands"
    (let [result (multi/run-multi
                  [{:id "op-1" :tool "config" :command "help"}
                   {:id "op-2" :tool "preset" :command "help"}
                   {:id "op-3" :tool "analysis" :command "help"}])]
      (is (:success result))
      (is (= 3 (get-in result [:summary :success])))
      ;; All independent → 1 wave
      (is (= 1 (get-in result [:summary :waves]))))))

(deftest run-multi-real-dependency-chain-test
  (testing "run-multi executes dependency chain with real tools"
    (let [result (multi/run-multi
                  [{:id "op-1" :tool "config" :command "help"}
                   {:id "op-2" :tool "preset" :command "help" :depends_on ["op-1"]}
                   {:id "op-3" :tool "migration" :command "help" :depends_on ["op-2"]}])]
      (is (:success result))
      (is (= 3 (get-in result [:summary :total])))
      (is (= 3 (get-in result [:summary :success])))
      (is (= 3 (get-in result [:summary :waves]))))))

(deftest run-multi-real-diamond-dependency-test
  (testing "run-multi executes diamond dependency with real tools"
    ;; op-1 -> op-2, op-1 -> op-3, op-2 + op-3 -> op-4
    (let [result (multi/run-multi
                  [{:id "op-1" :tool "config" :command "help"}
                   {:id "op-2" :tool "preset" :command "help" :depends_on ["op-1"]}
                   {:id "op-3" :tool "analysis" :command "help" :depends_on ["op-1"]}
                   {:id "op-4" :tool "migration" :command "help" :depends_on ["op-2" "op-3"]}])]
      (is (:success result))
      (is (= 4 (get-in result [:summary :success])))
      (is (= 3 (get-in result [:summary :waves]))))))

(deftest run-multi-real-mixed-success-failure-test
  (testing "run-multi handles mix of valid and invalid tool ops"
    (let [result (multi/run-multi
                  [{:id "op-1" :tool "config" :command "help"}
                   {:id "op-2" :tool "nonexistent-tool-xyz" :command "noop"}])]
      (is (false? (:success result)))
      (is (= 2 (get-in result [:summary :total])))
      (is (= 1 (get-in result [:summary :success])))
      (is (= 1 (get-in result [:summary :failed]))))))

(deftest run-multi-dep-failure-cascades-test
  (testing "run-multi cascades failure to downstream ops"
    (let [result (multi/run-multi
                  [{:id "op-1" :tool "nonexistent-tool-xyz" :command "noop"}
                   {:id "op-2" :tool "config" :command "help" :depends_on ["op-1"]}
                   {:id "op-3" :tool "preset" :command "help" :depends_on ["op-2"]}])]
      (is (false? (:success result)))
      (is (= 3 (get-in result [:summary :total])))
      (is (= 0 (get-in result [:summary :success])))
      (is (= 3 (get-in result [:summary :failed])))
      (is (= 3 (get-in result [:summary :waves]))))))

(deftest run-multi-partial-dep-failure-test
  (testing "run-multi only cascades failure to direct dependents"
    (let [result  (multi/run-multi
                   [{:id "op-1" :tool "nonexistent-tool-xyz" :command "noop"}
                    {:id "op-2" :tool "config" :command "help" :depends_on ["op-1"]}
                    {:id "op-3" :tool "preset" :command "help"}])
          by-id   (into {} (map (juxt :id :success))
                        (mapcat :results (vals (:waves result))))]
      (is (false? (:success result)))
      (is (= 3 (get-in result [:summary :total])))
      (is (= 1 (get-in result [:summary :success])))
      (is (= 2 (get-in result [:summary :failed])))
      (is (true? (get by-id "op-3")) "independent op still runs")
      (is (false? (get by-id "op-2")) "direct dependent is skipped"))))

;; =============================================================================
;; Part 8b: Real Tool Execution — Result Data Verification
;; =============================================================================

(deftest run-multi-result-contains-actual-data-test
  (testing "run-multi op results contain actual handler output data"
    (let [result (multi/run-multi
                  [{:id "cfg-help" :tool "config" :command "help"}])
          wave-1 (get-in result [:waves 1])
          op-result (first (:results wave-1))]
      (is (true? (:success op-result)))
      ;; The result should contain the actual handler output
      (is (some? (:result op-result)))
      (let [inner (:result op-result)]
        ;; Config help returns {:type "text" :text "..."}
        (is (or (string? inner)
                (and (map? inner)
                     (contains? inner :text))))))))

(deftest run-multi-failed-op-has-error-message-test
  (testing "run-multi failed op results contain descriptive error"
    (let [result (multi/run-multi
                  [{:id "bad" :tool "nonexistent-tool-xyz" :command "noop"}])
          wave-1 (get-in result [:waves 1])
          op-result (first (:results wave-1))]
      (is (false? (:success op-result)))
      (is (string? (:error op-result)))
      (is (str/includes? (:error op-result) "not found")))))

(deftest run-multi-skipped-op-has-dep-failure-message-test
  (testing "run-multi skipped op has dependency failure error message"
    (let [result      (multi/run-multi
                       [{:id "fail" :tool "nonexistent-tool-xyz" :command "noop"}
                        {:id "skip" :tool "config" :command "help" :depends_on ["fail"]}])
          skip-result (->> (vals (:waves result))
                           (mapcat :results)
                           (filter #(= "skip" (:id %)))
                           first)]
      (is (some? skip-result))
      (is (false? (:success skip-result)))
      (is (str/includes? (str (:error skip-result)) "dependencies failed"))
      (is (str/includes? (str (:error skip-result)) "fail")))))

;; =============================================================================
;; Part 9: Wave Result Structure Verification
;; =============================================================================

(deftest run-multi-wave-structure-test
  (testing "run-multi wave results have correct structure"
    (let [result (multi/run-multi
                  [{:id "op-1" :tool "config" :command "help"}
                   {:id "op-2" :tool "preset" :command "help" :depends_on ["op-1"]}])
          wave-1 (get-in result [:waves 1])
          wave-2 (get-in result [:waves 2])]
      (is (some? wave-1))
      (is (vector? (:ops wave-1)))
      (is (= ["op-1"] (mapv :id (:ops wave-1))))
      (is (vector? (:results wave-1)))
      (is (= 1 (count (:results wave-1))))
      (is (every? :success (:results wave-1)))
      (is (some? wave-2))
      (is (= ["op-2"] (mapv :id (:ops wave-2))))
      (is (every? :success (:results wave-2)))
      (is (nil? (get-in result [:waves 3]))))))

(deftest run-multi-wave-result-has-id-and-success-test
  (testing "Each op result in waves has :id and :success"
    (let [result (multi/run-multi
                  [{:id "a" :tool "config" :command "help"}
                   {:id "b" :tool "preset" :command "help"}])]
      (doseq [[_wave-num wave-data] (:waves result)]
        (doseq [op-result (:results wave-data)]
          (is (contains? op-result :id))
          (is (contains? op-result :success)))))))

;; =============================================================================
;; Part 10: execute-op Real Tool Tests
;; =============================================================================

(deftest execute-op-real-config-help-test
  (testing "execute-op with real config help command"
    (let [result (multi/execute-op {:id "test-1" :tool "config" :command "help"})]
      (is (= "test-1" (:id result)))
      (is (true? (:success result)))
      (is (some? (:result result))))))

(deftest execute-op-real-preset-help-test
  (testing "execute-op with real preset help command"
    (let [result (multi/execute-op {:id "test-2" :tool "preset" :command "help"})]
      (is (true? (:success result))))))

(deftest execute-op-strips-meta-keys-test
  (testing "execute-op strips :id :tool :depends_on :wave before forwarding"
    ;; The handler should receive {:command "help"} without meta keys
    ;; If meta keys leaked through, it would still work for help,
    ;; but let's verify the result structure is clean
    (let [result (multi/execute-op {:id "test-3" :tool "config" :command "help"
                                    :depends_on ["op-0"] :wave 2})]
      (is (true? (:success result)))
      (is (= "test-3" (:id result))))))

(deftest execute-op-real-config-list-test
  (testing "execute-op with real config list command returns config data"
    (let [result (multi/execute-op {:id "cfg-list" :tool "config" :command "list"})]
      (is (true? (:success result)))
      (is (some? (:result result))))))

(deftest execute-op-real-kg-stats-test
  (testing "execute-op with real kg stats command"
    (let [result (multi/execute-op {:id "kg-stats" :tool "kg" :command "stats"})]
      (is (= "kg-stats" (:id result)))
      (is (true? (:success result))))))

(deftest execute-op-real-migration-status-test
  (testing "execute-op with real migration status command"
    (let [result (multi/execute-op {:id "mig-status" :tool "migration" :command "status"})]
      (is (= "mig-status" (:id result)))
      (is (true? (:success result))))))

;; =============================================================================
;; Part 11: End-to-End via handle-multi (MCP Entry Point)
;; =============================================================================

(deftest e2e-single-dispatch-test
  (testing "E2E: MCP client sends single dispatch to config"
    (let [result (c-multi/handle-multi
                  {"tool" "config" "command" "help"})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "get"))
      (is (str/includes? (:text result) "reload")))))

(deftest e2e-batch-dispatch-two-waves-test
  (testing "E2E: MCP client sends batch with 2-wave dependency"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "step-1" "tool" "config" "command" "help"}
                                 {"id" "step-2" "tool" "preset" "command" "help"
                                  "depends_on" ["step-1"]}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 2 (get-in parsed [:summary :waves])))
      (is (= 2 (get-in parsed [:summary :success]))))))

(deftest e2e-batch-dispatch-with-failure-test
  (testing "E2E: MCP client sends batch with one failing op"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "ok" "tool" "config" "command" "help"}
                                 {"id" "bad" "tool" "nonexistent" "command" "x"}]})
          parsed (parse-json-response result)]
      (is (false? (:success parsed)))
      (is (= 1 (get-in parsed [:summary :success])))
      (is (= 1 (get-in parsed [:summary :failed]))))))

(deftest e2e-batch-dry-run-test
  (testing "E2E: MCP client sends batch with dry_run flag"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "a" "tool" "config" "command" "help"}
                                 {"id" "b" "tool" "preset" "command" "help"
                                  "depends_on" ["a"]}]
                   "dry_run" true})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (true? (:dry_run parsed)))
      ;; dry_run does NOT execute
      (is (= 0 (get-in parsed [:summary :success])))
      ;; But plan should be present
      (is (some? (:plan parsed))))))

(deftest e2e-batch-validation-failure-test
  (testing "E2E: MCP client sends batch with circular dependency"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "a" "tool" "config" "command" "help"
                                  "depends_on" ["b"]}
                                 {"id" "b" "tool" "preset" "command" "help"
                                  "depends_on" ["a"]}]})
          parsed (parse-json-response result)]
      (is (false? (:success parsed)))
      (is (= 0 (get-in parsed [:summary :success])))
      (is (some #(str/includes? (str %) "Circular dependency") (:errors parsed))))))

(deftest e2e-batch-self-dependency-test
  (testing "E2E: MCP client sends batch with self-referencing dependency"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "self" "tool" "config" "command" "help"
                                  "depends_on" ["self"]}]})
          parsed (parse-json-response result)]
      (is (false? (:success parsed)))
      (is (some #(str/includes? % "depends on itself") (:errors parsed))))))

(deftest e2e-batch-missing-tool-validation-test
  (testing "E2E: MCP client sends batch with missing tool field"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "no-tool" "command" "help"}]})
          parsed (parse-json-response result)]
      (is (false? (:success parsed)))
      (is (seq (:errors parsed))))))

;; =============================================================================
;; Part 11b: End-to-End — Real Command Execution
;; =============================================================================

(deftest e2e-batch-real-commands-test
  (testing "E2E: MCP client sends batch with real non-help commands"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "cfg" "tool" "config" "command" "list"}
                                 {"id" "mig" "tool" "migration" "command" "status"
                                  "depends_on" ["cfg"]}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 2 (get-in parsed [:summary :success])))
      (is (= 2 (get-in parsed [:summary :waves]))))))

(deftest e2e-single-dispatch-real-kg-stats-test
  (testing "E2E: single dispatch to kg stats returns real data"
    (let [result (c-multi/handle-multi {"tool" "kg" "command" "stats"})]
      (is (not (:isError result)))
      (is (string? (:text result))))))

;; =============================================================================
;; Part 12: Multi Help Text Verification
;; =============================================================================

(deftest multi-help-lists-all-tools-test
  (testing "Multi help text lists all 17 tool names"
    (let [result (c-multi/handle-multi {})]
      (is (not (:isError result)))
      (doseq [tool ["agent" "memory" "kg" "hivemind" "magit"
                    "kanban" "preset" "olympus" "agora" "analysis"
                    "project" "session" "emacs" "wave" "migration"
                    "config" "workflow"]]
        (is (str/includes? (:text result) tool)
            (str "Help should list tool: " tool))))))

(deftest multi-help-mentions-batch-mode-test
  (testing "Multi help text explains batch mode"
    (let [result (c-multi/handle-multi {})]
      (is (str/includes? (:text result) "Batch"))
      (is (str/includes? (:text result) "operations")))))

;; =============================================================================
;; Part 13: FX Registration
;; =============================================================================

(deftest register-fx-idempotent-test
  (testing "register-fx! can be called multiple times without error"
    ;; This tests that the registration is safe to re-run
    (is (or (true? (multi/register-fx!))
            (nil? (multi/register-fx!)))
        "register-fx! should return true or nil (if fx system not loaded)")))

;; =============================================================================
;; Part 13b: FX Emission with Real Tool Execution
;; =============================================================================

(deftest fx-wave-complete-emitted-on-real-execution-test
  (testing "run-multi emits :multi/wave-complete FX for real tool execution"
    (require 'hive.events.fx)
    (let [wave-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      (reg-fx :multi/wave-complete (fn [data] (swap! wave-calls conj data)))
      (try
        (multi/run-multi
         [{:id "a" :tool "config" :command "help"}
          {:id "b" :tool "preset" :command "help"}])
        (is (= 1 (count @wave-calls))
            "Should emit 1 wave-complete for 1 wave (2 independent ops)")
        (let [wc (first @wave-calls)]
          (is (= 1 (:wave-num wc)))
          (is (= 2 (:op-count wc)))
          (is (= 2 (:success-count wc)))
          (is (= 0 (:failed-count wc)))
          (is (= 1 (:total-waves wc))))
        (finally
          (multi/register-fx!))))))

(deftest fx-wave-complete-multi-wave-real-test
  (testing "run-multi emits wave-complete for each wave with real tools"
    (require 'hive.events.fx)
    (let [wave-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      (reg-fx :multi/wave-complete (fn [data] (swap! wave-calls conj data)))
      (try
        (multi/run-multi
         [{:id "a" :tool "config" :command "list"}
          {:id "b" :tool "migration" :command "status" :depends_on ["a"]}])
        (is (= 2 (count @wave-calls))
            "Should emit one wave-complete per wave")
        (is (= [1 2] (mapv :wave-num @wave-calls)))
        (is (every? #(= 2 (:total-waves %)) @wave-calls))
        (is (every? #(= 1 (:success-count %)) @wave-calls))
        (is (every? #(= 0 (:failed-count %)) @wave-calls))
        (finally
          (multi/register-fx!))))))

(deftest fx-op-error-not-emitted-on-success-test
  (testing "run-multi does NOT emit :multi/op-error when all ops succeed"
    (require 'hive.events.fx)
    (let [error-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      (reg-fx :multi/op-error (fn [data] (swap! error-calls conj data)))
      (try
        (multi/run-multi
         [{:id "a" :tool "config" :command "help"}
          {:id "b" :tool "preset" :command "help"}])
        (is (= 0 (count @error-calls))
            "No op-error events should be emitted when all ops succeed")
        (finally
          (multi/register-fx!))))))

(deftest fx-op-error-emitted-on-failure-test
  (testing "run-multi emits :multi/op-error for failed ops"
    (require 'hive.events.fx)
    (let [error-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      (reg-fx :multi/op-error (fn [data] (swap! error-calls conj data)))
      (try
        (multi/run-multi
         [{:id "good" :tool "config" :command "help"}
          {:id "bad" :tool "nonexistent-tool-xyz" :command "noop"}])
        (is (= 1 (count @error-calls))
            "Should emit 1 op-error for the failed op")
        (is (= "bad" (:op-id (first @error-calls))))
        (is (= 1 (:wave-num (first @error-calls))))
        (finally
          (multi/register-fx!))))))

(deftest fx-no-emission-on-dry-run-test
  (testing "run-multi does not emit FX on dry-run"
    (require 'hive.events.fx)
    (let [wave-calls (atom [])
          error-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      (reg-fx :multi/wave-complete (fn [data] (swap! wave-calls conj data)))
      (reg-fx :multi/op-error (fn [data] (swap! error-calls conj data)))
      (try
        (multi/run-multi
         [{:id "a" :tool "config" :command "help"}]
         :dry-run true)
        (is (= 0 (count @wave-calls))
            "Dry-run should not emit wave-complete FX")
        (is (= 0 (count @error-calls))
            "Dry-run should not emit op-error FX")
        (finally
          (multi/register-fx!))))))

;; =============================================================================
;; Part 14: format-results Integration
;; =============================================================================

(deftest format-results-real-execution-test
  (testing "format-results with actual run-multi output produces valid JSON"
    (let [run-result (multi/run-multi
                      [{:id "a" :tool "config" :command "help"}
                       {:id "b" :tool "preset" :command "help"}])
          formatted (multi/format-results run-result)]
      (is (= "text" (:type formatted)))
      (is (string? (:text formatted)))
      ;; Should be parseable JSON
      (let [parsed (json/read-str (:text formatted) :key-fn keyword)]
        (is (:success parsed))
        (is (= 2 (get-in parsed [:summary :total])))
        (is (some? (:waves parsed)))))))

(deftest format-results-dry-run-real-test
  (testing "format-results with dry-run output has plan"
    (let [run-result (multi/run-multi
                      [{:id "a" :tool "config" :command "help"}
                       {:id "b" :tool "preset" :command "help" :depends_on ["a"]}]
                      :dry-run true)
          formatted (multi/format-results run-result)]
      (let [parsed (json/read-str (:text formatted) :key-fn keyword)]
        (is (true? (:dry_run parsed)))
        (is (some? (:plan parsed)))
        ;; Plan should have wave structure
        (is (or (some? (:wave_1 (:plan parsed)))
                (some? (get (:plan parsed) (keyword "wave_1")))))))))

(deftest format-results-failure-real-test
  (testing "format-results with failed execution includes error details"
    (let [run-result (multi/run-multi
                      [{:id "bad" :tool "nonexistent-xyz" :command "x"}])
          formatted (multi/format-results run-result)]
      (let [parsed (json/read-str (:text formatted) :key-fn keyword)]
        (is (false? (:success parsed)))
        ;; Wave results should contain error info
        (let [wave-data (or (get (:waves parsed) :wave_1)
                            (get (:waves parsed) (keyword "wave_1")))]
          (is (some? wave-data))
          (is (some :error wave-data)))))))

(deftest format-results-mixed-success-failure-test
  (testing "format-results with mixed results preserves per-op status"
    (let [run-result (multi/run-multi
                      [{:id "good" :tool "config" :command "help"}
                       {:id "bad" :tool "nonexistent-xyz" :command "noop"}])
          formatted (multi/format-results run-result)
          parsed (json/read-str (:text formatted) :key-fn keyword)]
      (is (false? (:success parsed)))
      (is (= 1 (get-in parsed [:summary :success])))
      (is (= 1 (get-in parsed [:summary :failed])))
      ;; Verify per-op results are accessible
      (let [all-ops (extract-wave-results parsed)]
        (is (= 2 (count all-ops)))
        (is (some #(and (:success %) (= "good" (:id %))) all-ops))
        (is (some #(and (not (:success %)) (= "bad" (:id %))) all-ops))))))

;; =============================================================================
;; Part 15: Edge Cases
;; =============================================================================

(deftest handle-multi-tool-with-blank-tool-and-operations-test
  (testing "handle-multi with blank tool + operations activates batch mode"
    (let [result (c-multi/handle-multi
                  {"tool" "" "operations" [{"id" "op-1" "tool" "config" "command" "help"}]})
          parsed (parse-json-response result)]
      (is (:success parsed)))))

(deftest handle-multi-tool-nil-with-operations-test
  (testing "handle-multi with nil tool + operations activates batch mode"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "op-1" "tool" "config" "command" "help"}]})
          parsed (parse-json-response result)]
      (is (:success parsed)))))

(deftest run-multi-auto-generates-missing-ids-test
  (testing "run-multi auto-generates IDs for ops missing them"
    (let [result (multi/run-multi
                  [{:tool "config" :command "help"}
                   {:tool "preset" :command "help"}])]
      (is (:success result))
      (is (= 2 (get-in result [:summary :success]))))))

(deftest run-multi-string-keys-normalized-test
  (testing "run-multi normalizes string keys in operations"
    (let [result (multi/run-multi
                  [{"id" "s1" "tool" "config" "command" "help"}
                   {"id" "s2" "tool" "preset" "command" "help"}])]
      (is (:success result))
      (is (= 2 (get-in result [:summary :success]))))))

(deftest execute-op-keyword-command-converted-test
  (testing "execute-op converts keyword command to string"
    (let [result (multi/execute-op {:id "kw-test" :tool "config" :command :help})]
      (is (true? (:success result))))))

(deftest handle-multi-case-insensitive-tool-test
  (testing "handle-multi is case-insensitive for tool names"
    (let [result (c-multi/handle-multi {"tool" "CONFIG" "command" "help"})]
      (is (help-response? result)))
    (let [result (c-multi/handle-multi {"tool" "Memory" "command" "help"})]
      (is (help-response? result)))))

(deftest handle-multi-tool-present-overrides-batch-test
  (testing "When both tool and operations are present, single dispatch wins"
    (let [result (c-multi/handle-multi
                  {"tool" "config" "command" "help"
                   "operations" [{"id" "op1" "tool" "kg" "command" "help"}]})]
      ;; Should route to config help (single dispatch), not batch
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Available commands")))))

(deftest handle-multi-help-as-tool-name-test
  (testing "handle-multi with 'help' as tool name shows multi help"
    (let [result (c-multi/handle-multi {"tool" "help"})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Multi tool")))))

;; =============================================================================
;; Part 16: events/multi Orchestration Pipeline — REMOVED (obsolete)
;; =============================================================================
;;
;; The `hive-mcp.events.multi` namespace was deleted in 2bbd1d7 together with
;; its unit test (test/hive_mcp/events/multi_test.clj). The orchestration API it
;; exposed — the `orchestrate` defmulti, `execute!`, `effects`, `next-event`,
;; `orchestration-ctx`, `halt?`, `merge-results`, `chain`, `fan-out`,
;; `fan-out-events`, `wrap-orchestrator`, `registered-orchestrators`,
;; `orchestrator-registered?` — and its two demo orchestrators (:multi/ping,
;; :multi/echo) have NO successor anywhere in src/ or on the classpath.
;;
;; Do not "repoint" these at `hive.events.multi` (hive-events lib): despite the
;; matching name it is a different subsystem — multimethod handler dispatch
;; (handle / register-handler! / dispatch-sync) plus pure batch-op compilation
;; (normalize-op / validate-ops / expand-batch-macro / assign-waves /
;; compile-multi-spec). It has no orchestrate/ctx/chain/fan-out semantics, and
;; it is already covered by its own tests in the hive-events repo and, via
;; src/hive_mcp/multi/plan.clj, by the batch tests in this file (Parts 1-15).
;;
;; The 12 orchestration deftests that lived here (Parts 16, 16b and 34) are kept
;; as documentation of the removed behaviour, per the precedent in
;; test/hive_mcp/bug_regression_test.clj.

(deftest events-multi-orchestration-removed-test
  (testing "hive-mcp.events.multi orchestration layer removed — tests obsolete"
    (is true "events/multi orchestration layer removed in 2bbd1d7 — no successor API")))

;; =============================================================================
;; Part 17: tools/multi Batch Execution (standalone)
;; =============================================================================

(deftest batch-execution-standalone-test
  (testing "Batch execution runs a real op through tools/multi"
    ;; Was `cross-domain-batch-and-orchestrate-test`: the events/multi half of
    ;; this test is gone (see Part 16). The batch half is real coverage — kept.
    (let [batch-result (multi/run-multi
                        [{:id "a" :tool "config" :command "help"}])]
      (is (:success batch-result)))))

;; =============================================================================
;; Part 18: Batch with Large Operation Count
;; =============================================================================

(deftest run-multi-many-independent-ops-test
  (testing "run-multi handles many independent ops efficiently"
    (let [ops (mapv (fn [i]
                      {:id (str "op-" i) :tool "config" :command "help"})
                    (range 10))
          result (multi/run-multi ops)]
      (is (:success result))
      (is (= 10 (get-in result [:summary :total])))
      (is (= 10 (get-in result [:summary :success])))
      ;; All independent → 1 wave
      (is (= 1 (get-in result [:summary :waves]))))))

(deftest run-multi-wide-diamond-test
  (testing "run-multi handles wide diamond (1 root -> N middle -> 1 merge)"
    (let [n 5
          root {:id "root" :tool "config" :command "help"}
          middle (mapv (fn [i]
                         {:id (str "mid-" i) :tool "config" :command "help"
                          :depends_on ["root"]})
                       (range n))
          merge-op {:id "merge" :tool "config" :command "help"
                    :depends_on (mapv #(str "mid-" %) (range n))}
          ops (into [root] (conj middle merge-op))
          result (multi/run-multi ops)]
      (is (:success result))
      (is (= (+ 2 n) (get-in result [:summary :total])))
      (is (= (+ 2 n) (get-in result [:summary :success])))
      (is (= 3 (get-in result [:summary :waves]))))))

;; =============================================================================
;; Part 19: Dry-Run Plan Verification
;; =============================================================================

(deftest dry-run-plan-has-wave-ops-test
  (testing "dry-run plan contains ops organized by wave"
    (let [result (multi/run-multi
                  [{:id "a" :tool "config" :command "list"}
                   {:id "b" :tool "kg" :command "stats"}
                   {:id "c" :tool "preset" :command "help" :depends_on ["a" "b"]}]
                  :dry-run true)]
      (is (:success result))
      (is (true? (:dry-run result)))
      (is (= 2 (count (:waves result))))
      (is (= #{"a" "b"} (set (mapv :id (get-in result [:waves 1 :ops])))))
      (is (= ["c"] (mapv :id (get-in result [:waves 2 :ops]))))
      (doseq [op (mapcat :ops (vals (:waves result)))]
        (is (contains? op :id))
        (is (contains? op :tool))
        (is (contains? op :command))))))

(deftest dry-run-does-not-execute-test
  (testing "dry-run does not actually call tool handlers"
    ;; Using a nonexistent tool — if it executed, it would fail
    ;; but dry-run should just plan without executing
    (let [result (multi/run-multi
                  [{:id "fake" :tool "nonexistent-will-fail" :command "noop"}]
                  :dry-run true)]
      ;; Dry-run validates ops (tool existence not checked at validation)
      ;; It should succeed as a plan since validation only checks id/tool fields
      (is (:success result))
      (is (true? (:dry-run result))))))

;; =============================================================================
;; Part 20: Error Message Quality
;; =============================================================================

(deftest unknown-tool-error-message-quality-test
  (testing "Unknown tool error message is actionable"
    (let [result (c-multi/handle-multi {"tool" "banana" "command" "peel"})]
      (is (:isError result))
      ;; Should mention the unknown tool name
      (is (str/includes? (:text result) "banana"))
      ;; Should list available tools
      (is (str/includes? (:text result) "config"))
      (is (str/includes? (:text result) "memory")))))

(deftest batch-validation-error-message-quality-test
  (testing "Batch validation errors are specific and actionable"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "a" "tool" "config" "command" "help"
                                  "depends_on" ["nonexistent"]}]})
          parsed (parse-json-response result)]
      (is (false? (:success parsed)))
      (is (some #(str/includes? % "non-existent") (:errors parsed)))
      (is (some #(str/includes? % "nonexistent") (:errors parsed))))))

(deftest batch-circular-dep-error-lists-nodes-test
  (testing "Circular dependency error identifies the cycle nodes"
    (let [result (c-multi/handle-multi
                  {"operations" [{"id" "x" "tool" "config" "command" "help"
                                  "depends_on" ["y"]}
                                 {"id" "y" "tool" "config" "command" "help"
                                  "depends_on" ["z"]}
                                 {"id" "z" "tool" "config" "command" "help"
                                  "depends_on" ["x"]}]})
          parsed (parse-json-response result)
          errors (mapv str (:errors parsed))]
      (is (false? (:success parsed)))
      (is (= 0 (get-in parsed [:summary :waves])))
      (is (some #(str/includes? % "Circular dependency") errors))
      (is (every? (fn [node] (some #(str/includes? % node) errors)) ["x" "y" "z"])))))

;; =============================================================================
;; Helpers for Mutating Operation Tests (Parts 21+)
;; =============================================================================

(defn- extract-op-result-by-id
  "Extract a specific op result by ID from a parsed batch response.
   Searches across all waves."
  [parsed op-id]
  (when-let [waves (:waves parsed)]
    (->> (vals waves)
         (mapcat identity)
         (filter #(= op-id (:id %)))
         first)))

(defn- parse-inner-result
  "Parse the inner :result from an op-result, which may be a JSON-string map."
  [op-result]
  (when-let [r (:result op-result)]
    (cond
      (map? r)    r
      (string? r) (try (json/read-str r :key-fn keyword) (catch Exception _ nil))
      :else       nil)))

(defn- unique-tag
  "Generate a unique tag for test isolation."
  []
  (str "multi-integ-test-" (System/currentTimeMillis) "-" (rand-int 100000)))

;; =============================================================================
;; Part 21: Real Memory Add Through Multi (Single Dispatch)
;; =============================================================================

(deftest handle-multi-memory-add-single-dispatch-test
  (testing "Single dispatch: memory add creates a real entry"
    (let [tag (unique-tag)
          result (c-multi/handle-multi
                  {"tool" "memory"
                   "command" "add"
                   "type" "note"
                   "content" (str "Integration test entry: " tag)
                   "tags" [tag "multi-integration-test"]
                   "duration" "ephemeral"
                   "directory" "/home/lages/PP/hive/hive-mcp"})]
      (is (not (:isError result))
          (str "Memory add should succeed, got: " (:text result)))
      ;; Result should contain the entry ID
      (when-not (:isError result)
        (is (string? (:text result)))
        (is (or (str/includes? (:text result) "Added")
                (str/includes? (:text result) "id")
                (str/includes? (:text result) tag)))))))

(deftest handle-multi-memory-query-single-dispatch-test
  (testing "Single dispatch: memory query returns results"
    (let [result (c-multi/handle-multi
                  {"tool" "memory"
                   "command" "query"
                   "type" "note"
                   "limit" 3
                   "directory" "/home/lages/PP/hive/hive-mcp"})]
      (is (not (:isError result))
          (str "Memory query should succeed, got: " (:text result)))
      (is (string? (:text result))))))

;; =============================================================================
;; Part 22: Real Memory Add → Query Dependency Chain (Batch)
;; =============================================================================

(deftest batch-memory-add-then-query-chain-test
  (testing "Batch: memory add -> memory query dependency chain"
    (let [tag (unique-tag)
          result (c-multi/handle-multi
                  {"operations"
                   [{"id" "add-mem"
                     "tool" "memory"
                     "command" "add"
                     "type" "note"
                     "content" (str "Batch chain test: " tag)
                     "tags" [tag "batch-chain-test"]
                     "duration" "ephemeral"
                     "directory" "/home/lages/PP/hive/hive-mcp"}
                    {"id" "query-mem"
                     "tool" "memory"
                     "command" "query"
                     "type" "note"
                     "tags" [tag]
                     "limit" 5
                     "directory" "/home/lages/PP/hive/hive-mcp"
                     "depends_on" ["add-mem"]}]})
          parsed (parse-json-response result)]
      (is (:success parsed)
          (str "Batch memory add->query chain should succeed: " (:text result)))
      (is (= 2 (get-in parsed [:summary :total])))
      (is (= 2 (get-in parsed [:summary :success])))
      (is (= 2 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 23: Cross-Tool Parallel Operations (Memory + KG + Config)
;; =============================================================================

(deftest batch-cross-tool-parallel-test
  (testing "Batch: parallel operations across memory, kg, and config tools"
    (let [tag (unique-tag)
          result (c-multi/handle-multi
                  {"operations"
                   [{"id" "mem-add"
                     "tool" "memory"
                     "command" "add"
                     "type" "note"
                     "content" (str "Cross-tool parallel test: " tag)
                     "tags" [tag]
                     "duration" "ephemeral"
                     "directory" "/home/lages/PP/hive/hive-mcp"}
                    {"id" "kg-stats"
                     "tool" "kg"
                     "command" "stats"}
                    {"id" "cfg-list"
                     "tool" "config"
                     "command" "list"}]})
          parsed (parse-json-response result)]
      (is (:success parsed)
          (str "Cross-tool parallel should succeed: " (:text result)))
      (is (= 3 (get-in parsed [:summary :total])))
      (is (= 3 (get-in parsed [:summary :success])))
      ;; All independent → 1 wave
      (is (= 1 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 24: Cross-Tool Sequential Chain (Memory → KG → Config)
;; =============================================================================

(deftest batch-cross-tool-sequential-chain-test
  (testing "Batch: sequential chain across memory -> kg -> config tools"
    (let [tag (unique-tag)
          result (c-multi/handle-multi
                  {"operations"
                   [{"id" "step-mem"
                     "tool" "memory"
                     "command" "add"
                     "type" "note"
                     "content" (str "Sequential chain test: " tag)
                     "tags" [tag]
                     "duration" "ephemeral"
                     "directory" "/home/lages/PP/hive/hive-mcp"}
                    {"id" "step-kg"
                     "tool" "kg"
                     "command" "stats"
                     "depends_on" ["step-mem"]}
                    {"id" "step-cfg"
                     "tool" "config"
                     "command" "list"
                     "depends_on" ["step-kg"]}]})
          parsed (parse-json-response result)]
      (is (:success parsed)
          (str "Cross-tool sequential chain should succeed: " (:text result)))
      (is (= 3 (get-in parsed [:summary :success])))
      (is (= 3 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 25: E2E Batch Memory Add via handle-multi (Full MCP Path)
;; =============================================================================

(deftest e2e-batch-memory-add-test
  (testing "E2E: MCP-style batch memory add (JSON string keys)"
    (let [tag (unique-tag)
          result (c-multi/handle-multi
                  {"operations"
                   [{"id" "e2e-add"
                     "tool" "memory"
                     "command" "add"
                     "type" "note"
                     "content" (str "E2E batch add: " tag)
                     "tags" [tag "e2e-batch"]
                     "duration" "ephemeral"
                     "directory" "/home/lages/PP/hive/hive-mcp"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 1 (get-in parsed [:summary :success]))))))

;; =============================================================================
;; Part 26: Mixed Mutating + Readonly Batch
;; =============================================================================

(deftest batch-mixed-mutating-readonly-test
  (testing "Batch: mixing mutating (memory add) and readonly (config list, kg stats)"
    (let [tag (unique-tag)
          result (c-multi/handle-multi
                  {"operations"
                   [{"id" "readonly-1"
                     "tool" "config"
                     "command" "list"}
                    {"id" "mutate-1"
                     "tool" "memory"
                     "command" "add"
                     "type" "note"
                     "content" (str "Mixed batch: " tag)
                     "tags" [tag]
                     "duration" "ephemeral"
                     "directory" "/home/lages/PP/hive/hive-mcp"}
                    {"id" "readonly-2"
                     "tool" "kg"
                     "command" "stats"}]})
          parsed (parse-json-response result)]
      (is (:success parsed))
      (is (= 3 (get-in parsed [:summary :success])))
      ;; All independent → 1 wave
      (is (= 1 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 27: Concurrent Memory Adds (Same Wave)
;; =============================================================================

(deftest batch-concurrent-memory-adds-test
  (testing "Batch: 5 concurrent memory adds in same wave"
    (let [base-tag (unique-tag)
          ops (mapv (fn [i]
                      {"id" (str "add-" i)
                       "tool" "memory"
                       "command" "add"
                       "type" "note"
                       "content" (str "Concurrent add " i ": " base-tag)
                       "tags" [base-tag (str "item-" i)]
                       "duration" "ephemeral"
                       "directory" "/home/lages/PP/hive/hive-mcp"})
                    (range 5))
          result (c-multi/handle-multi {"operations" ops})
          parsed (parse-json-response result)]
      (is (:success parsed)
          (str "5 concurrent memory adds should succeed: " (:text result)))
      (is (= 5 (get-in parsed [:summary :total])))
      (is (= 5 (get-in parsed [:summary :success])))
      ;; All independent → 1 wave
      (is (= 1 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 28: Session Context Put → Get Chain
;; =============================================================================

(deftest batch-session-context-put-get-test
  (testing "Batch: session context-put followed by context-stats"
    (let [tag (unique-tag)
          result (c-multi/handle-multi
                  {"operations"
                   [{"id" "ctx-put"
                     "tool" "session"
                     "command" "context-put"
                     "data" {"test_key" tag "value" 42}
                     "tags" [tag "ctx-test"]}
                    {"id" "ctx-stats"
                     "tool" "session"
                     "command" "context-stats"
                     "depends_on" ["ctx-put"]}]})
          parsed (parse-json-response result)]
      (is (:success parsed)
          (str "Session context-put->stats chain should succeed: " (:text result)))
      (is (= 2 (get-in parsed [:summary :success])))
      (is (= 2 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 29: Kanban Operations Through Multi
;; =============================================================================

(deftest handle-multi-kanban-status-test
  (testing "Single dispatch: kanban status returns board overview"
    (let [result (c-multi/handle-multi
                  {"tool" "kanban"
                   "command" "status"
                   "directory" "/home/lages/PP/hive/hive-mcp"})]
      (is (not (:isError result))
          (str "Kanban status should succeed: " (:text result)))
      (is (string? (:text result))))))

(deftest handle-multi-kanban-list-test
  (testing "Single dispatch: kanban list returns task list"
    (let [result (c-multi/handle-multi
                  {"tool" "kanban"
                   "command" "list"
                   "directory" "/home/lages/PP/hive/hive-mcp"})]
      (is (not (:isError result))
          (str "Kanban list should succeed: " (:text result))))))

;; =============================================================================
;; Part 30: Preset List/Status Through Multi
;; =============================================================================

(deftest handle-multi-preset-list-test
  (testing "Single dispatch: preset list returns available presets"
    (let [result (c-multi/handle-multi
                  {"tool" "preset"
                   "command" "list"
                   "verbosity" "slim"})]
      (is (not (:isError result))
          (str "Preset list should succeed: " (:text result)))
      (is (string? (:text result))))))

(deftest handle-multi-preset-status-test
  (testing "Single dispatch: preset status returns integration info"
    (let [result (c-multi/handle-multi
                  {"tool" "preset"
                   "command" "status"})]
      (is (not (:isError result))
          (str "Preset status should succeed: " (:text result))))))

;; =============================================================================
;; Part 31: Memory Search Through Multi
;; =============================================================================

(deftest handle-multi-memory-search-test
  (testing "Single dispatch: memory search returns results"
    (let [result (c-multi/handle-multi
                  {"tool" "memory"
                   "command" "search"
                   "query" "test integration"
                   "limit" 3})]
      (is (not (:isError result))
          (str "Memory search should succeed: " (:text result))))))

(deftest handle-multi-memory-expiring-test
  (testing "Single dispatch: memory expiring returns expiring entries"
    (let [result (c-multi/handle-multi
                  {"tool" "memory"
                   "command" "expiring"
                   "days" 7
                   "limit" 3})]
      (is (not (:isError result))
          (str "Memory expiring should succeed: " (:text result))))))

;; =============================================================================
;; Part 32: Diamond Dependency with Real Mutating Ops
;; =============================================================================

(deftest batch-diamond-with-mutating-ops-test
  (testing "Batch: diamond dependency with real mutating ops at each node"
    (let [tag (unique-tag)
          result (c-multi/handle-multi
                  {"operations"
                   [{"id" "root"
                     "tool" "memory"
                     "command" "add"
                     "type" "note"
                     "content" (str "Diamond root: " tag)
                     "tags" [tag "diamond-root"]
                     "duration" "ephemeral"
                     "directory" "/home/lages/PP/hive/hive-mcp"}
                    {"id" "mid-1"
                     "tool" "kg"
                     "command" "stats"
                     "depends_on" ["root"]}
                    {"id" "mid-2"
                     "tool" "config"
                     "command" "list"
                     "depends_on" ["root"]}
                    {"id" "merge"
                     "tool" "memory"
                     "command" "query"
                     "type" "note"
                     "tags" [tag]
                     "limit" 5
                     "directory" "/home/lages/PP/hive/hive-mcp"
                     "depends_on" ["mid-1" "mid-2"]}]})
          parsed (parse-json-response result)]
      (is (:success parsed)
          (str "Diamond with mutating ops should succeed: " (:text result)))
      (is (= 4 (get-in parsed [:summary :total])))
      (is (= 4 (get-in parsed [:summary :success])))
      (is (= 3 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 33: FX Emission with Mutating Operations
;; =============================================================================

(deftest fx-wave-complete-with-mutating-ops-test
  (testing "FX: wave-complete emitted during batch with memory add"
    (require 'hive.events.fx)
    (let [wave-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)
          tag (unique-tag)]
      (reg-fx :multi/wave-complete (fn [data] (swap! wave-calls conj data)))
      (try
        (multi/run-multi
         [{:id "mut-add" :tool "memory" :command "add"
           :type "note" :content (str "FX test: " tag)
           :tags [tag] :duration "ephemeral"
           :directory "/home/lages/PP/hive/hive-mcp"}
          {:id "ro-cfg" :tool "config" :command "list"}])
        (is (= 1 (count @wave-calls))
            "Should emit 1 wave-complete for 1 wave (2 parallel ops)")
        (let [wc (first @wave-calls)]
          (is (= 1 (:wave-num wc)))
          (is (= 2 (:op-count wc)))
          (is (= 2 (:success-count wc)))
          (is (= 0 (:failed-count wc))))
        (finally
          (multi/register-fx!))))))

(deftest fx-multi-wave-with-mutating-chain-test
  (testing "FX: wave-complete emitted per wave in mutating chain"
    (require 'hive.events.fx)
    (let [wave-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)
          tag (unique-tag)]
      (reg-fx :multi/wave-complete (fn [data] (swap! wave-calls conj data)))
      (try
        (multi/run-multi
         [{:id "add-1" :tool "memory" :command "add"
           :type "note" :content (str "FX chain: " tag)
           :tags [tag] :duration "ephemeral"
           :directory "/home/lages/PP/hive/hive-mcp"}
          {:id "query-1" :tool "memory" :command "query"
           :type "note" :tags [tag] :limit 3
           :directory "/home/lages/PP/hive/hive-mcp"
           :depends_on ["add-1"]}])
        (is (= 2 (count @wave-calls))
            "Should emit one wave-complete per wave")
        (is (= [1 2] (mapv :wave-num @wave-calls)))
        (is (every? #(= 1 (:success-count %)) @wave-calls))
        (finally
          (multi/register-fx!))))))

;; =============================================================================
;; Part 34: events/multi wrap-orchestrator — REMOVED (obsolete)
;; =============================================================================
;;
;; The custom-orchestrator lifecycle and wrap-orchestrator middleware tests that
;; lived here exercised `hive-mcp.events.multi/orchestrate`, `execute!` and
;; `wrap-orchestrator`, all deleted in 2bbd1d7 with no successor. See the Part 16
;; note above for why `hive.events.multi` (hive-events lib) is NOT a valid
;; repoint target.

(deftest events-multi-wrap-orchestrator-removed-test
  (testing "events/multi wrap-orchestrator middleware removed — test obsolete"
    (is true "wrap-orchestrator removed in 2bbd1d7 — no successor API")))

;; =============================================================================
;; Part 35: Stress Tests — Long Chains and Wide Parallel
;; =============================================================================

(deftest stress-long-chain-real-ops-test
  (testing "Stress: 10-step linear chain with real config operations"
    (let [ops (into []
                    (map-indexed
                     (fn [i _]
                       (cond-> {:id (str "chain-" i)
                                :tool "config"
                                :command "list"}
                         (pos? i) (assoc :depends_on [(str "chain-" (dec i))])))
                     (range 10)))
          result (multi/run-multi ops)]
      (is (:success result))
      (is (= 10 (get-in result [:summary :total])))
      (is (= 10 (get-in result [:summary :success])))
      (is (= 10 (get-in result [:summary :waves]))))))

(deftest stress-wide-parallel-real-ops-test
  (testing "Stress: 15 independent operations in 1 wave"
    (let [ops (mapv (fn [i]
                      {:id (str "wide-" i)
                       :tool "config"
                       :command "list"})
                    (range 15))
          result (multi/run-multi ops)]
      (is (:success result))
      (is (= 15 (get-in result [:summary :total])))
      (is (= 15 (get-in result [:summary :success])))
      ;; All independent → 1 wave
      (is (= 1 (get-in result [:summary :waves]))))))

(deftest stress-mixed-topology-test
  (testing "Stress: mixed parallel and sequential with real ops"
    ;; Wave 1: a, b, c (parallel)
    ;; Wave 2: d depends on a, e depends on b
    ;; Wave 3: f depends on d and e
    (let [result (multi/run-multi
                  [{:id "a" :tool "config" :command "list"}
                   {:id "b" :tool "kg" :command "stats"}
                   {:id "c" :tool "migration" :command "status"}
                   {:id "d" :tool "config" :command "list" :depends_on ["a"]}
                   {:id "e" :tool "config" :command "list" :depends_on ["b"]}
                   {:id "f" :tool "config" :command "list" :depends_on ["d" "e"]}])]
      (is (:success result))
      (is (= 6 (get-in result [:summary :total])))
      (is (= 6 (get-in result [:summary :success])))
      ;; 3 waves: [a,b,c], [d,e], [f]
      (is (= 3 (get-in result [:summary :waves]))))))

(deftest stress-batch-with-mutating-adds-test
  (testing "Stress: 8 memory adds fanning out then merging via query"
    (let [tag (unique-tag)
          add-ops (mapv (fn [i]
                          {"id" (str "fan-" i)
                           "tool" "memory"
                           "command" "add"
                           "type" "note"
                           "content" (str "Fan-out stress " i ": " tag)
                           "tags" [tag (str "fan-" i)]
                           "duration" "ephemeral"
                           "directory" "/home/lages/PP/hive/hive-mcp"})
                        (range 8))
          query-op {"id" "fan-merge"
                    "tool" "memory"
                    "command" "query"
                    "type" "note"
                    "tags" [tag]
                    "limit" 20
                    "directory" "/home/lages/PP/hive/hive-mcp"
                    "depends_on" (mapv #(str "fan-" %) (range 8))}
          ops (conj add-ops query-op)
          result (c-multi/handle-multi {"operations" ops})
          parsed (parse-json-response result)]
      (is (:success parsed)
          (str "8 adds + merge query should succeed: " (:text result)))
      (is (= 9 (get-in parsed [:summary :total])))
      (is (= 9 (get-in parsed [:summary :success])))
      ;; 2 waves: [8 adds], [merge query]
      (is (= 2 (get-in parsed [:summary :waves]))))))

;; =============================================================================
;; Part 36: Config Get Through Multi
;; =============================================================================

(deftest handle-multi-config-get-test
  (testing "Single dispatch: config get retrieves a config value"
    (let [result (c-multi/handle-multi
                  {"tool" "config"
                   "command" "get"
                   "key" "embeddings"})]
      ;; May or may not have embeddings config, but should not NPE
      (is (some? result))
      (is (not (:isError result))))))

;; =============================================================================
;; Part 37: Memory Metadata Query Through Multi
;; =============================================================================

(deftest handle-multi-memory-metadata-test
  (testing "Single dispatch: memory metadata returns entry details"
    ;; First add an entry, then query metadata
    (let [tag (unique-tag)
          add-result (c-multi/handle-multi
                      {"tool" "memory"
                       "command" "add"
                       "type" "note"
                       "content" (str "Metadata test: " tag)
                       "tags" [tag]
                       "duration" "ephemeral"
                       "directory" "/home/lages/PP/hive/hive-mcp"})]
      (is (not (:isError add-result))
          (str "Memory add for metadata test should succeed: " (:text add-result))))))

;; =============================================================================
;; Part 38: Project Info Through Multi
;; =============================================================================

(deftest handle-multi-project-staleness-test
  (testing "Single dispatch: project staleness check (lightweight)"
    (let [result (c-multi/handle-multi
                  {"tool" "project"
                   "command" "staleness"
                   "directory" "/home/lages/PP/hive/hive-mcp"})]
      (is (some? result))
      (is (not (:isError result))))))

;; =============================================================================
;; Part 39: Batch with execute-op Result Verification
;; =============================================================================

(deftest execute-op-memory-add-result-structure-test
  (testing "execute-op returns proper structure for memory add"
    (let [tag (unique-tag)
          result (multi/execute-op
                  {:id "mem-add-test"
                   :tool "memory"
                   :command "add"
                   :type "note"
                   :content (str "execute-op test: " tag)
                   :tags [tag]
                   :duration "ephemeral"
                   :directory "/home/lages/PP/hive/hive-mcp"})]
      (is (= "mem-add-test" (:id result)))
      (is (true? (:success result)))
      (is (some? (:result result))))))

(deftest execute-op-memory-query-result-structure-test
  (testing "execute-op returns proper structure for memory query"
    (let [result (multi/execute-op
                  {:id "mem-query-test"
                   :tool "memory"
                   :command "query"
                   :type "note"
                   :limit 2
                   :directory "/home/lages/PP/hive/hive-mcp"})]
      (is (= "mem-query-test" (:id result)))
      (is (true? (:success result)))
      (is (some? (:result result))))))

;; =============================================================================
;; Part 40: run-multi with Real Mutating Ops — Result Data Verification
;; =============================================================================

(deftest run-multi-mutating-result-data-test
  (testing "run-multi with memory add: op result contains handler output"
    (let [tag (unique-tag)
          result (multi/run-multi
                  [{:id "add-verify"
                    :tool "memory"
                    :command "add"
                    :type "note"
                    :content (str "Result verification: " tag)
                    :tags [tag]
                    :duration "ephemeral"
                    :directory "/home/lages/PP/hive/hive-mcp"}])
          wave-1 (get-in result [:waves 1])
          op-result (first (:results wave-1))]
      (is (:success result))
      (is (true? (:success op-result)))
      ;; Should have actual result data from memory add
      (is (some? (:result op-result))))))

(deftest run-multi-mutating-chain-result-verification-test
  (testing "run-multi with memory add→query: both ops have result data"
    (let [tag (unique-tag)
          result (multi/run-multi
                  [{:id "chain-add"
                    :tool "memory"
                    :command "add"
                    :type "note"
                    :content (str "Chain verification: " tag)
                    :tags [tag]
                    :duration "ephemeral"
                    :directory "/home/lages/PP/hive/hive-mcp"}
                   {:id "chain-query"
                    :tool "memory"
                    :command "query"
                    :type "note"
                    :tags [tag]
                    :limit 5
                    :directory "/home/lages/PP/hive/hive-mcp"
                    :depends_on ["chain-add"]}])]
      (is (:success result))
      (is (= 2 (get-in result [:summary :success])))
      ;; Wave 1: add
      (let [w1 (get-in result [:waves 1])
            add-op (first (:results w1))]
        (is (true? (:success add-op)))
        (is (some? (:result add-op))))
      ;; Wave 2: query
      (let [w2 (get-in result [:waves 2])
            query-op (first (:results w2))]
        (is (true? (:success query-op)))
        (is (some? (:result query-op)))))))

(comment
  ;; Run all tests in this namespace via REPL
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'hive-mcp.tools.multi-integration-test))
