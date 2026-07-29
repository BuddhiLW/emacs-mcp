(ns hive-mcp.tools.multi-dsl-e2e-test
  "End-to-end integration tests for the Multi DSL pipeline.

   Tests cover 4 areas + edge cases:
   1. DSL → Batch → Results (verb parsing, param aliases, execution, $ref threading)
   2. Macro → DSL → Batch (macro expansion, parameterized macros, nesting)
   3. Async Pipeline (dispatch, collect, cancel, concurrency)
   4. Error Handling (unknown verbs, invalid $refs, bad batch-ids, mutual exclusion)
   5. Verb Table Coverage (contract tests for all 30 verbs)
   6. Full Pipeline + Edge Cases

   Modules under development (dsl/verbs, multi async) are resolved dynamically
   via requiring-resolve. Tests skip gracefully when target modules are not yet
   available. Tool handlers are mocked via with-redefs for isolation.

   Design decision: 20260211212019-b8499942 (Multi DSL plan)"
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hive-mcp.tools.multi :as multi]
            [hive-mcp.test.stub.batch-extensions :as bx]))

;; =============================================================================
;; Test Infrastructure
;; =============================================================================

(defn- try-resolve
  "Resolve a symbol dynamically. Returns resolved fn or nil."
  [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defmacro when-resolved
  "Execute body with sym-binding bound to the resolved var.
   Prints SKIP message when target module is not yet available.
   Usage: (when-resolved parse-fn hive-mcp.dsl.verbs/parse-sentence ...body...)"
  [sym-binding sym-name & body]
  `(if-let [~sym-binding (try-resolve '~sym-name)]
     (do ~@body)
     (println (str "  SKIP: " '~sym-name " not available yet"))))

(defn- json-ok
  "Create a mock MCP JSON text response from a data map."
  [data]
  {:type "text" :text (json/write-str data)})

(use-fixtures :each bx/with-batch-extensions)

(defn- mock-handlers
  "Build a resolve-tool-handler replacement from a map of tool-name → handler-fn."
  [handler-map]
  (fn [tool-name]
    (get handler-map tool-name)))

;; --- Standard mock handlers for testing ---

(def ^:private mock-memory-handler
  (fn [params]
    (json-ok {:id (str "mem-" (Math/abs (hash (:content params))))
              :type (or (:type params) "note")
              :status "created"
              :content (:content params)})))

(def ^:private mock-kg-handler
  (fn [params]
    (json-ok {:id (str "edge-" (Math/abs (hash [(:from params) (:to params)])))
              :from (:from params)
              :to (:to params)
              :relation (or (:relation params) "implements")
              :ok true})))

(def ^:private mock-kanban-handler
  (fn [params]
    (json-ok {:task-id (str "task-" (Math/abs (hash (:title params))))
              :title (:title params)
              :status "todo"})))

(def ^:private mock-preset-handler
  (fn [_params]
    (json-ok {:presets ["ling" "drone-worker" "saa" "tdd"]
              :count 4})))

(def ^:private standard-mock-handlers
  {"memory" mock-memory-handler
   "kg"     mock-kg-handler
   "kanban" mock-kanban-handler
   "preset" mock-preset-handler})

;; =============================================================================
;; Part 1: DSL → Batch → Results
;; =============================================================================

(deftest dsl-single-verb-sentence-parse-test
  (testing "Single verb sentence parses to correct operation map"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (let [op (parse-sentence ["m+" {"c" "hello world" "t" "note"}])]
                     (is (= "memory" (:tool op)) "m+ should map to memory tool")
                     (is (= "add" (:command op)) "m+ should map to add command")
                     (is (= "hello world" (:content op)) "c should expand to content")
                     (is (= "note" (:type op)) "t should expand to type")))))

(deftest dsl-single-verb-execute-test
  (testing "Single verb sentence → execute → verify result shape"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (let [op (parse-sentence ["m+" {"c" "test content" "t" "note"}])
            ;; Ensure :id for batch execution
                         op-with-id (cond-> op (not (:id op)) (assoc :id "op-1"))]
                     (with-redefs [multi/resolve-tool-handler (mock-handlers standard-mock-handlers)]
                       (let [result (multi/run-multi [op-with-id])]
                         (is (:success result))
                         (is (= 1 (get-in result [:summary :success])))
                         (is (= 0 (get-in result [:summary :failed])))
                         (is (= 1 (get-in result [:summary :waves])))))))))

(deftest dsl-paragraph-auto-deps-test
  (testing "Multi-sentence paragraph -> execute with auto-deps -> verify ordered results"
    (when-resolved compile-paragraph hive-mcp.dsl.verbs/compile-paragraph
                   (let [;; Paragraph: create memory, then create KG edge referencing it
                         paragraph [["m+" {"c" "my note" "t" "note"}]
                                    ["k>" {"from" "$ref:$0.data.id"
                                           "to" "node-2"
                                           "relation" "implements"}]]
                         ops (compile-paragraph paragraph)]
        ;; Should produce 2 ops with auto-deps
                     (is (= 2 (count ops)))
                     (is (some? (:id (first ops))))
                     (is (some? (:id (second ops))))
        ;; Second op should depend on first
                     (let [dep-ids (set (or (:depends_on (second ops)) []))]
                       (is (contains? dep-ids (:id (first ops)))
                           "Second op should have auto-dependency on first"))
        ;; Execute through batch engine
                     (with-redefs [multi/resolve-tool-handler (mock-handlers standard-mock-handlers)]
                       (let [result (multi/run-multi ops)]
                         (is (:success result))
                         (is (= 2 (get-in result [:summary :success])))
                         (is (= 2 (get-in result [:summary :waves]))
                             "the auto-dependency puts the ops in separate waves")))))))

(deftest dsl-param-alias-expansion-test
  (testing "Verb with param aliases → verify correct expansion"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (testing "c → content"
                     (is (= "hello" (:content (parse-sentence ["m+" {"c" "hello"}])))))
                   (testing "t → type"
                     (is (= "snippet" (:type (parse-sentence ["m+" {"t" "snippet"}])))))
                   (testing "# → tags"
                     (is (= ["a" "b"] (:tags (parse-sentence ["m+" {"#" ["a" "b"]}])))))
                   (testing "d → directory"
                     (is (= "/tmp/project" (:directory (parse-sentence ["m+" {"d" "/tmp/project"}])))))
                   (testing "q → query"
                     (is (= "search term" (:query (parse-sentence ["m?" {"q" "search term"}])))))
                   (testing "n → name"
                     (is (= "ling" (:name (parse-sentence ["p@" {"n" "ling"}])))))
                   (testing "id → id (passthrough)"
                     (is (= "entry-123" (:id (parse-sentence ["m@" {"id" "entry-123"}])))))
                   (testing "p → prompt"
                     (is (= "do the thing" (:prompt (parse-sentence ["a!" {"p" "do the thing"}])))))
                   (testing "f → files"
                     (is (= ["a.clj" "b.clj"] (:files (parse-sentence ["w!" {"f" ["a.clj" "b.clj"]}]))))))))

(deftest dsl-ref-threading-prev-test
  (testing "$ref:$0 threading: m+ creates -> k> uses $ref:$0.data.id"
    (when-resolved compile-paragraph hive-mcp.dsl.verbs/compile-paragraph
                   (let [captured-kg-params (atom nil)
                         paragraph [["m+" {"c" "test content" "t" "note"}]
                                    ["k>" {"from" "$ref:$0.data.id"
                                           "to" "target-node"
                                           "relation" "implements"}]]
                         ops (compile-paragraph paragraph)]
                     (with-redefs [multi/resolve-tool-handler
                                   (fn [tool-name]
                                     (case tool-name
                                       "memory" (fn [_]
                                                  (json-ok {:id "mem-abc-123" :status "created"}))
                                       "kg"     (fn [params]
                                                  (reset! captured-kg-params params)
                                                  (json-ok {:ok true}))
                                       nil))]
                       (let [result (multi/run-multi ops)]
                         (is (:success result) "Pipeline should succeed")
                         (is (= 2 (get-in result [:summary :success])))
                         (is (= "mem-abc-123" (:from @captured-kg-params))
                             "$0 resolves to the previous op's created id")))))))

(deftest dsl-ref-threading-named-ops-test
  (testing "$ref:op-id.path threading between explicitly named ops"
    (let [captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "memory" (fn [_] (json-ok {:id "mem-xyz" :type "note"}))
                        "kg"     (fn [p] (reset! captured p) (json-ok {:ok true}))
                        nil))]
        (let [result (multi/run-multi
                      [{:id "create-mem" :tool "memory" :command "add"
                        :content "test" :type "note"}
                       {:id "create-edge" :tool "kg" :command "edge"
                        :from "$ref:create-mem.data.id"
                        :to "target"
                        :depends_on ["create-mem"]}])]
          (is (:success result))
          (is (= "mem-xyz" (:from @captured))))))))

(deftest dsl-multi-ref-chain-test
  (testing "Three-op chain threads refs, and a $ref inside prose stays quoted"
    (let [c-captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "memory" (fn [p]
                                   (json-ok {:id (str "mem-" (Math/abs (hash (:content p))))
                                             :content (:content p)}))
                        "kg"     (fn [p]
                                   (json-ok {:edge-id (str "e-" (:from p) "-" (:to p))
                                             :from (:from p) :to (:to p)}))
                        "kanban" (fn [p]
                                   (reset! c-captured p)
                                   (json-ok {:task-id "task-1" :ok true}))
                        nil))]
        (let [result (multi/run-multi
                      [{:id "a" :tool "memory" :command "add" :content "my note"}
                       {:id "b" :tool "kg" :command "edge"
                        :from "$ref:a.data.id" :to "node-2"
                        :depends_on ["a"]}
                       {:id "c" :tool "kanban" :command "create"
                        :title "$ref:b.data.edge-id"
                        :node_id "$ref:b.data.edge-id"
                        :depends_on ["b"]}])
              mem-id (str "mem-" (Math/abs (hash "my note")))]
          (is (:success result))
          (is (= 3 (get-in result [:summary :success])))
          (is (= 3 (get-in result [:summary :waves])))
          (is (= (str "e-" mem-id "-node-2") (:node_id @c-captured))
              "an ordinary param resolves through the whole chain")
          (is (= "$ref:b.data.edge-id" (:title @c-captured))
              ":title is prose — a $ref inside it is quotation, never resolved"))))))

;; =============================================================================
;; Part 2: Macro → DSL → Batch
;; =============================================================================

(deftest macro-builtin-expansion-test
  (testing "Built-in macro expansion → verify sentence output"
    (when-resolved expand-macro hive-mcp.dsl.macros/expand-macro
                   (let [sentences (expand-macro "catchup!" {})]
                     (is (sequential? sentences) "Macro should expand to sequence of sentences")
                     (is (pos? (count sentences)) "Macro should produce at least one sentence")
        ;; Each sentence should be a valid verb sentence [verb-str params-map]
                     (doseq [s sentences]
                       (is (vector? s) "Each sentence should be a vector")
                       (is (string? (first s)) "First element should be a verb string")
                       (is (map? (second s)) "Second element should be a params map"))))))

(deftest macro-parameterized-test
  (testing "Parameterized macro → verify params flow through"
    (when-resolved expand-macro hive-mcp.dsl.macros/expand-macro
                   (let [sentences (expand-macro "plan>kb" {:plan_id "plan-test-123"})]
        ;; At least one sentence should contain the plan_id param
                     (is (some (fn [s]
                                 (let [params (second s)]
                                   (= "plan-test-123" (get params :plan_id))))
                               sentences)
                         "Parameterized plan_id should appear in expanded sentences")))))

(deftest macro-nested-expansion-test
  (testing "Composite macro → verify expansion produces all parseable verb sentences"
    (when-resolved expand-macro hive-mcp.dsl.macros/expand-macro
                   (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
        ;; plan>kb>forge! composes plan>kb + forge steps
                                  (let [sentences (expand-macro "plan>kb>forge!" {:plan_id "plan-test" :slots 3})]
          ;; Should produce >= 4 steps (plan-convert, plan-list, forge-spawn, forge-monitor)
                                    (is (>= (count sentences) 4) "Composite macro should expand to 4+ steps")
                                    (doseq [s sentences]
                                      (let [op (parse-sentence s)]
                                        (is (some? (:tool op))
                                            (str "Expanded sentence " (first s) " should parse to a valid tool"))
                                        (is (some? (:command op))
                                            (str "Expanded sentence " (first s) " should parse to a valid command")))))))))

(deftest macro-to-batch-execution-test
  (testing "Macro expansion → parse-dsl → batch execution (full pipeline)"
    (when-resolved expand-macro hive-mcp.dsl.macros/expand-macro
                   (when-resolved parse-dsl hive-mcp.dsl.verbs/parse-dsl
        ;; Macros have internal :id and :depends_on in params — use parse-dsl (not compile-paragraph)
        ;; to preserve the macro's internal wiring
                                  (let [sentences (expand-macro "catchup!" {})
                                        ops (parse-dsl sentences)]
          ;; Add session and memory mock handlers
                                    (with-redefs [multi/resolve-tool-handler
                                                  (mock-handlers (assoc standard-mock-handlers
                                                                        "session" (fn [_] (json-ok {:status "ok" :agent "test"}))))]
                                      (let [result (multi/run-multi ops)]
                                        (is (:success result)
                                            "Macro → DSL → Batch pipeline should succeed")
                                        (is (pos? (get-in result [:summary :success]))
                                            "At least one op should succeed"))))))))

;; =============================================================================
;; Part 3: Async Pipeline
;; =============================================================================

(deftest async-dispatch-and-collect-test
  (testing "Async dispatch → collect running → wait → collect completed"
    (when-resolved run-multi-async hive-mcp.tools.multi-async/run-multi-async
                   (with-redefs [multi/resolve-tool-handler
                                 (fn [tool-name]
                                   (case tool-name
                                     "memory" (fn [_]
                                                (Thread/sleep 50)
                                                (json-ok {:id "mem-async-1" :status "created"}))
                                     nil))]
                     (let [async-result (run-multi-async
                                         [{:id "a" :tool "memory" :command "add" :content "async test"}])
                           batch-id (:batch-id async-result)]
          ;; Should return immediately with batch-id
                       (is (some? batch-id) "Async dispatch should return batch-id")
                       (is (string? batch-id) "batch-id should be a string")
          ;; Collect after completion
                       (when-resolved collect-fn hive-mcp.tools.multi-async/collect-async-result
                                      (Thread/sleep 300)
                                      (let [collected (collect-fn batch-id)]
                                        (is (some? collected) "Collect should return results")
                                        (is (= "completed" (:status collected)) "Batch should be completed")
                                        (is (:success (:results collected)) "Batch results should have succeeded")
                                        (is (= 1 (get-in collected [:results :summary :success]))))))))))

(deftest async-collect-running-status-test
  (testing "Async collect while batch is still running shows running status"
    (when-resolved run-multi-async hive-mcp.tools.multi-async/run-multi-async
                   (with-redefs [multi/resolve-tool-handler
                                 (fn [tool-name]
                                   (case tool-name
                                     "memory" (fn [_]
                                                (Thread/sleep 3000) ;; Long-running
                                                (json-ok {:id "mem-slow"}))
                                     nil))]
                     (let [async-result (run-multi-async
                                         [{:id "a" :tool "memory" :command "add" :content "slow"}])
                           batch-id (:batch-id async-result)]
                       (when-resolved collect-fn hive-mcp.tools.multi-async/collect-async-result
            ;; Immediately collect — should be running
                                      (let [status (collect-fn batch-id)]
                                        (is (some? status))
                                        (is (= "running" (:status status))
                                            "Should report running status when batch is not yet complete"))))))))

(deftest async-with-dsl-input-test
  (testing "Async with DSL input → collect results"
    (when-resolved compile-paragraph hive-mcp.dsl.verbs/compile-paragraph
                   (when-resolved run-multi-async hive-mcp.tools.multi-async/run-multi-async
                                  (let [ops (compile-paragraph [["m+" {"c" "async dsl" "t" "note"}]])]
                                    (with-redefs [multi/resolve-tool-handler (mock-handlers standard-mock-handlers)]
                                      (let [async-result (run-multi-async ops)
                                            batch-id (:batch-id async-result)]
                                        (is (some? batch-id))
                                        (when-resolved collect-fn hive-mcp.tools.multi-async/collect-async-result
                                                       (Thread/sleep 300)
                                                       (let [collected (collect-fn batch-id)]
                                                         (is (= "completed" (:status collected)))
                                                         (is (:success (:results collected))
                                                             "Async DSL batch should succeed"))))))))))

(deftest async-cancel-batch-test
  (testing "Cancel async batch → verify cancelled"
    (when-resolved run-multi-async hive-mcp.tools.multi-async/run-multi-async
                   (with-redefs [multi/resolve-tool-handler
                                 (fn [tool-name]
                                   (case tool-name
                                     "memory" (fn [_]
                                                (Thread/sleep 10000) ;; Very long
                                                (json-ok {:id "mem-cancel"}))
                                     nil))]
                     (let [async-result (run-multi-async
                                         [{:id "a" :tool "memory" :command "add" :content "cancel me"}])
                           batch-id (:batch-id async-result)]
                       (when-resolved cancel-fn hive-mcp.tools.multi-async/cancel-async-batch
                                      (let [cancel-result (cancel-fn batch-id)]
                                        (is (:cancelled cancel-result) "Cancel should report success")
              ;; Collect after cancel should show cancelled status
                                        (when-resolved collect-fn hive-mcp.tools.multi-async/collect-async-result
                                                       (let [collected (collect-fn batch-id)]
                                                         (is (= "cancelled" (:status collected))
                                                             "Collected batch should show cancelled status"))))))))))

(deftest async-multiple-concurrent-batches-test
  (testing "Multiple concurrent async batches maintain isolation"
    (when-resolved run-multi-async hive-mcp.tools.multi-async/run-multi-async
                   (let [call-log (atom [])]
                     (with-redefs [multi/resolve-tool-handler
                                   (fn [tool-name]
                                     (case tool-name
                                       "memory" (fn [params]
                                                  (swap! call-log conj (:content params))
                                                  (Thread/sleep 50)
                                                  (json-ok {:id (str "mem-" (:content params))
                                                            :content (:content params)}))
                                       nil))]
          ;; Launch 3 concurrent batches
                       (let [b1 (run-multi-async [{:id "a1" :tool "memory" :command "add" :content "batch-1"}])
                             b2 (run-multi-async [{:id "a2" :tool "memory" :command "add" :content "batch-2"}])
                             b3 (run-multi-async [{:id "a3" :tool "memory" :command "add" :content "batch-3"}])]
            ;; All should have unique batch-ids
                         (is (= 3 (count (distinct [(:batch-id b1) (:batch-id b2) (:batch-id b3)])))
                             "Each batch should have a unique batch-id")
            ;; Wait for all to complete
                         (Thread/sleep 500)
                         (when-resolved collect-fn hive-mcp.tools.multi-async/collect-async-result
                                        (let [r1 (collect-fn (:batch-id b1))
                                              r2 (collect-fn (:batch-id b2))
                                              r3 (collect-fn (:batch-id b3))]
                                          (is (= "completed" (:status r1)) "Batch 1 should complete")
                                          (is (= "completed" (:status r2)) "Batch 2 should complete")
                                          (is (= "completed" (:status r3)) "Batch 3 should complete")
                                          (is (= 3 (count @call-log))
                                              "Each batch should have executed independently")))))))))

;; =============================================================================
;; Part 4: Error Handling
;; =============================================================================

(deftest error-unknown-verb-test
  (testing "Unknown verb → clear error"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (let [result (try
                                  (parse-sentence ["zzz!" {"c" "hello"}])
                                  (catch Exception e
                                    {:error true :message (ex-message e)}))]
        ;; Should either return nil/error or throw with clear message
                     (is (or (nil? result)
                             (:error result)
                             (nil? (:tool result)))
                         "Unknown verb should produce clear error, nil, or nil tool")))))

(deftest error-unknown-verb-in-batch-test
  (testing "Unknown verb in batch pipeline produces clear error"
    (let [result (multi/run-multi
                  [{:id "a" :tool "nonexistent-tool-xyz" :command "noop"}])]
      (is (false? (:success result)))
      (is (= 1 (get-in result [:summary :failed])))
      ;; Verify wave results contain meaningful error
      (let [wave-1-results (get-in result [:waves 1 :results])]
        (is (seq wave-1-results))
        (is (str/includes? (:error (first wave-1-results)) "not found"))))))

(deftest error-invalid-ref-no-depends-on-test
  (testing "$ref without depends_on → validation error, nothing executes"
    (let [result (multi/run-multi
                  [{:id "a" :tool "memory" :command "add" :content "hello"}
                   {:id "b" :tool "kg" :command "edge"
                    :from "$ref:a.data.id"}])]
      (is (false? (:success result)))
      (is (some #(str/includes? % "doesn't declare it in depends_on")
                (:errors result))
          "a $ref is an ordering edge — the target must be a declared dependency")
      (is (= 0 (get-in result [:summary :waves]))
          "refused at compile time — no wave was scheduled"))))

(deftest error-ref-to-nonexistent-op-test
  (testing "$ref to non-existent op ID → validation error"
    (let [result (multi/run-multi
                  [{:id "a" :tool "memory" :command "add"}
                   {:id "b" :tool "kg" :command "edge"
                    :from "$ref:ghost.data.id"
                    :depends_on ["ghost"]}])]
      (is (false? (:success result)))
      (is (seq (:errors result)))
      (is (some #(str/includes? % "non-existent") (:errors result))
          "Error should indicate non-existent dependency"))))

(deftest error-async-collect-bad-batch-id-test
  (testing "Async collect with bad batch-id → not-found"
    (when-resolved collect-fn hive-mcp.tools.multi-async/collect-async-result
                   (let [result (collect-fn "nonexistent-batch-id-xyz-99999")]
                     (is (or (nil? result)
                             (= "not-found" (:status result))
                             (:error result))
                         "Collecting non-existent batch should return not-found or error")))))

(deftest error-mutual-exclusion-dsl-and-operations-test
  (testing "Mutual exclusion: providing both dsl and operations → error"
    (when-resolved handle-multi hive-mcp.tools.consolidated.multi/handle-multi
                   (let [result (handle-multi {"dsl" "m+ {c: hello}"
                                               "operations" [{"tool" "memory" "command" "add"}]})]
        ;; Should reject when both dsl and operations are provided
                     (is (or (:isError result)
                             (false? (:success result)))
                         "Providing both dsl and operations should be an error")
                     (when (:isError result)
                       (is (or (str/includes? (str (:text result)) "mutual")
                               (str/includes? (str (:text result)) "exclusive")
                               (str/includes? (str (:text result)) "both")
                               (str/includes? (str (:text result)) "dsl"))
                           "Error should mention mutual exclusion or DSL conflict"))))))

(deftest error-empty-dsl-input-test
  (testing "Empty DSL input → clear error or empty result"
    (when-resolved parse-dsl hive-mcp.dsl.verbs/parse-dsl
                   (let [result (try
                                  (parse-dsl [])
                                  (catch Exception e
                                    {:error true :message (ex-message e)}))]
                     (is (or (nil? result)
                             (and (sequential? result) (empty? result))
                             (:error result))
                         "Empty DSL should produce empty result or error")))))

(deftest error-circular-ref-in-batch-test
  (testing "Circular deps are rejected before any op runs"
    (let [result (multi/run-multi
                  [{:id "a" :tool "memory" :command "add"
                    :content "$ref:b.data.id" :depends_on ["b"]}
                   {:id "b" :tool "kg" :command "edge"
                    :from "$ref:a.data.id" :depends_on ["a"]}])]
      (is (false? (:success result)))
      (is (some #(str/includes? (str %) "Circular dependency") (:errors result)))
      (is (= 0 (get-in result [:summary :waves]))))))

;; =============================================================================
;; Part 5: Verb Table Coverage (Contract Tests)
;; =============================================================================

(deftest verb-table-memory-verbs-test
  (testing "Memory verbs: m+ → add, m? → query, m@ → get, m/ → search"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "m+" "memory" "add"
                     "m?" "memory" "query"
                     "m@" "memory" "get"
                     "m/" "memory" "search"))))

(deftest verb-table-kg-verbs-test
  (testing "KG verbs: k> → edge, k^ → traverse, k! → impact, k# → stats"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "k>" "kg" "edge"
                     "k^" "kg" "traverse"
                     "k!" "kg" "impact"
                     "k#" "kg" "stats"))))

(deftest verb-table-agent-verbs-test
  (testing "Agent verbs: a+ → spawn, a? → status, a! → dispatch, ax → kill"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "a+" "agent" "spawn"
                     "a?" "agent" "status"
                     "a!" "agent" "dispatch"
                     "ax" "agent" "kill"))))

(deftest verb-table-kanban-verbs-test
  (testing "Kanban verbs: b+ → create, b> → update, b? → list, b# → status"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "b+" "kanban" "create"
                     "b>" "kanban" "update"
                     "b?" "kanban" "list"
                     "b#" "kanban" "status"))))

(deftest verb-table-session-verbs-test
  (testing "Session verbs: s. → complete, s~ → wrap, s? → whoami, s< → catchup"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "s." "session" "complete"
                     "s~" "session" "wrap"
                     "s?" "session" "whoami"
                     "s<" "session" "catchup"))))

(deftest verb-table-magit-verbs-test
  (testing "Magit verbs: g? → status, g+ → stage, g! → commit, g> → push"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "g?" "magit" "status"
                     "g+" "magit" "stage"
                     "g!" "magit" "commit"
                     "g>" "magit" "push"))))

(deftest verb-table-wave-verbs-test
  (testing "Wave verbs: w! → dispatch, w? → status, wy → approve, wn → reject"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "w!" "wave" "dispatch"
                     "w?" "wave" "status"
                     "wy" "wave" "approve"
                     "wn" "wave" "reject"))))

(deftest verb-table-hivemind-verbs-test
  (testing "Hivemind verbs: h! → shout, h? → ask"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "h!" "hivemind" "shout"
                     "h?" "hivemind" "ask"))))

(deftest verb-table-preset-verbs-test
  (testing "Preset verbs: p? → list, p@ → get, p/ → search"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "p?" "preset" "list"
                     "p@" "preset" "get"
                     "p/" "preset" "search"))))

(deftest verb-table-config-verbs-test
  (testing "Config verbs: c? → get, c! → set, c* → list"
    (when-resolved parse-sentence hive-mcp.dsl.verbs/parse-sentence
                   (are [verb expected-tool expected-cmd]
                        (let [op (parse-sentence [verb {}])]
                          (and (= expected-tool (:tool op))
                               (= expected-cmd (:command op))))
                     "c?" "config" "get"
                     "c!" "config" "set"
                     "c*" "config" "list"))))

;; =============================================================================
;; Part 6: Full Pipeline Integration (DSL string → MCP response)
;; =============================================================================

(deftest full-pipeline-dsl-string-to-mcp-response-test
  (testing "Full pipeline: DSL string input → parsed → executed → MCP response"
    (when-resolved parse-dsl hive-mcp.dsl.verbs/parse-dsl
                   (with-redefs [multi/resolve-tool-handler (mock-handlers standard-mock-handlers)]
                     (let [ops (parse-dsl [["m+" {"c" "test-content" "t" "note"}]])
              ;; Ensure all ops have IDs for batch engine
                           ops-with-ids (vec (map-indexed
                                              (fn [i op]
                                                (if (:id op) op (assoc op :id (str "auto-" i))))
                                              ops))
                           raw-result (multi/run-multi ops-with-ids)
                           mcp-result (multi/format-results raw-result)]
                       (is (= "text" (:type mcp-result)))
                       (is (string? (:text mcp-result)))
                       (let [parsed (json/read-str (:text mcp-result) :key-fn keyword)]
                         (is (:success parsed))
                         (is (pos? (get-in parsed [:summary :total])))))))))

(deftest full-pipeline-batch-dry-run-test
  (testing "Full pipeline: DSL → batch → dry-run shows plan without execution"
    (when-resolved compile-paragraph hive-mcp.dsl.verbs/compile-paragraph
                   (let [ops (compile-paragraph [["m+" {"c" "note 1"}]
                                                 ["m+" {"c" "note 2"}]])]
                     (let [result (multi/run-multi ops :dry-run true)]
                       (is (:success result))
                       (is (true? (:dry-run result)))
                       (is (= 0 (get-in result [:summary :success]))
                           "Dry-run should not execute any ops")
                       (is (pos? (get-in result [:summary :waves]))
                           "Dry-run should still compute wave structure"))))))

(deftest full-pipeline-format-results-preserves-ref-data-test
  (testing "Format-results preserves wave result structure including ref-resolved values"
    (let [captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-a" (fn [_] (json-ok {:id "res-a" :val 42}))
                        "mock-b" (fn [p] (reset! captured p) (json-ok {:received (:val p)}))
                        nil))]
        (let [raw (multi/run-multi
                   [{:id "a" :tool "mock-a" :command "produce"}
                    {:id "b" :tool "mock-b" :command "consume"
                     :val "$ref:a.data.val"
                     :depends_on ["a"]}])
              formatted (multi/format-results raw)]
          (is (= "text" (:type formatted)))
          (let [parsed (json/read-str (:text formatted) :key-fn keyword)]
            (is (:success parsed))
            (is (= 2 (get-in parsed [:summary :success])))
            (is (= 42 (:val @captured)))))))))

;; =============================================================================
;; Part 7: Edge Cases
;; =============================================================================

(deftest edge-case-single-op-no-deps-test
  (testing "Single op with no deps executes in wave 1"
    (with-redefs [multi/resolve-tool-handler (mock-handlers standard-mock-handlers)]
      (let [result (multi/run-multi
                    [{:id "solo" :tool "memory" :command "add" :content "alone"}])]
        (is (:success result))
        (is (= 1 (get-in result [:summary :waves])))
        (is (= 1 (get-in result [:summary :success])))))))

(deftest edge-case-ref-resolves-to-non-string-types-test
  (testing "$ref resolves to the source value's own type, not a string"
    (let [captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-src" (fn [_]
                                     (json-ok {:count 42
                                               :active true
                                               :meta {"nested" "value"}
                                               :tags ["a" "b"]}))
                        "mock-dst" (fn [p] (reset! captured p) (json-ok {:ok true}))
                        nil))]
        (let [result (multi/run-multi
                      [{:id "src" :tool "mock-src" :command "stats"}
                       {:id "dst" :tool "mock-dst" :command "process"
                        :count "$ref:src.data.count"
                        :active "$ref:src.data.active"
                        :meta "$ref:src.data.meta"
                        :tags "$ref:src.data.tags"
                        :depends_on ["src"]}])]
          (is (:success result))
          (is (= 42 (:count @captured)))
          (is (true? (:active @captured)))
          (is (= {:nested "value"} (:meta @captured)))
          (is (= ["a" "b"] (:tags @captured))))))))

(deftest edge-case-diamond-dependency-ref-test
  (testing "Diamond dependency threads both branch results into the merge op"
    (let [d-captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-a" (fn [_] (json-ok {:id "id-a"}))
                        "mock-b" (fn [_] (json-ok {:id "id-b"}))
                        "mock-c" (fn [_] (json-ok {:id "id-c"}))
                        "mock-d" (fn [p] (reset! d-captured p) (json-ok {:ok true}))
                        nil))]
        (let [result (multi/run-multi
                      [{:id "a" :tool "mock-a" :command "create"}
                       {:id "b" :tool "mock-b" :command "create" :depends_on ["a"]}
                       {:id "c" :tool "mock-c" :command "create" :depends_on ["a"]}
                       {:id "d" :tool "mock-d" :command "link"
                        :from "$ref:b.data.id"
                        :to "$ref:c.data.id"
                        :depends_on ["b" "c"]}])]
          (is (:success result))
          (is (= 4 (get-in result [:summary :success])))
          (is (= 3 (get-in result [:summary :waves])))
          (is (= "id-b" (:from @d-captured)))
          (is (= "id-c" (:to @d-captured))))))))

(deftest edge-case-failed-dep-skips-downstream-test
  (testing "A failed dependency skips its downstream op instead of running it"
    (with-redefs [multi/resolve-tool-handler
                  (fn [tool-name]
                    (case tool-name
                      "mock-fail" (fn [_] (throw (Exception. "intentional failure")))
                      "mock-ok"   (fn [_] (json-ok {:id "should-not-reach"}))
                      nil))]
      (let [result  (multi/run-multi
                     [{:id "a" :tool "mock-fail" :command "create"}
                      {:id "b" :tool "mock-ok" :command "use"
                       :from "$ref:a.data.id" :depends_on ["a"]}])
            results (mapcat :results (vals (:waves result)))
            b       (first (filter #(= "b" (:id %)) results))]
        (is (false? (:success result)))
        (is (= 0 (get-in result [:summary :success])))
        (is (= 2 (get-in result [:summary :failed])))
        (is (false? (:success b)))
        (is (str/includes? (str (:error b)) "dependencies failed"))))))

(deftest edge-case-large-batch-test
  (testing "Large batch (20 independent ops) executes in single wave"
    (with-redefs [multi/resolve-tool-handler
                  (fn [tool-name]
                    (case tool-name
                      "memory" (fn [p] (json-ok {:id (str "mem-" (:idx p)) :ok true}))
                      nil))]
      (let [ops (mapv (fn [i] {:id (str "op-" i) :tool "memory" :command "add" :idx i})
                      (range 20))
            result (multi/run-multi ops)]
        (is (:success result))
        (is (= 20 (get-in result [:summary :success])))
        (is (= 1 (get-in result [:summary :waves]))
            "20 independent ops should all be in wave 1")))))

(deftest edge-case-deep-ref-path-test
  (testing "$ref walks a deep path into the source op's data"
    (let [captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-src" (fn [_]
                                     (json-ok {:result {:content {:nested {:deep "treasure"}}}}))
                        "mock-dst" (fn [p] (reset! captured p) (json-ok {:ok true}))
                        nil))]
        (let [result (multi/run-multi
                      [{:id "src" :tool "mock-src" :command "fetch"}
                       {:id "dst" :tool "mock-dst" :command "use"
                        :value "$ref:src.data.result.content.nested.deep"
                        :depends_on ["src"]}])]
          (is (:success result))
          (is (= "treasure" (:value @captured))))))))

(deftest edge-case-ref-in-vector-params-test
  (testing "$ref inside a vector param resolves in place"
    (let [captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-src" (fn [_] (json-ok {:id "src-id" :tag "important"}))
                        "mock-dst" (fn [p] (reset! captured p) (json-ok {:ok true}))
                        nil))]
        (let [result (multi/run-multi
                      [{:id "src" :tool "mock-src" :command "create"}
                       {:id "dst" :tool "mock-dst" :command "tag"
                        :tags ["static-tag" "$ref:src.data.tag"]
                        :depends_on ["src"]}])]
          (is (:success result))
          (is (= ["static-tag" "important"] (:tags @captured))))))))

(deftest edge-case-ref-in-nested-map-params-test
  (testing "$ref inside a nested map param resolves in place"
    (let [captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-src" (fn [_] (json-ok {:id "src-id"}))
                        "mock-dst" (fn [p] (reset! captured p) (json-ok {:ok true}))
                        nil))]
        (let [result (multi/run-multi
                      [{:id "src" :tool "mock-src" :command "create"}
                       {:id "dst" :tool "mock-dst" :command "nested"
                        :config {:target_id "$ref:src.data.id"
                                 :label "static"}
                        :depends_on ["src"]}])]
          (is (:success result))
          (is (= "src-id" (get-in @captured [:config :target_id])))
          (is (= "static" (get-in @captured [:config :label]))))))))

(comment
  ;; Run all tests in this namespace via REPL
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'hive-mcp.tools.multi-dsl-e2e-test)

  ;; Run specific test
  (clojure.test/test-var #'hive-mcp.tools.multi-dsl-e2e-test/dsl-multi-ref-chain-test))
