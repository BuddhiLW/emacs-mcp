(ns hive-mcp.tools.multi-test
  "Tests for hive-mcp.tools.multi — cross-tool batch multiplexer.

   Tests cover:
   1. validate-ops — required fields, duplicate IDs, dependency refs, circular deps
   2. assign-waves — topological wave assignment
   3. execute-op — single operation execution with error isolation
   4. run-multi — full pipeline: validate → assign-waves → execute-per-wave
   5. format-results — MCP response formatting
   6. resolve-tool-handler — tool resolution via requiring-resolve"
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hive-mcp.tools.multi :as multi]
            [hive-mcp.test.stub.batch-extensions :as bx]))

;; =============================================================================
;; Part 1: validate-ops Tests
;; =============================================================================

;; The batch engine reaches cycle detection, wave assignment and $ref
;; resolution through the :bx/* extension registry. A cold JVM has that
;; registry empty; the live server has it filled. Arrange the collaborator
;; rather than assert its absence.
(use-fixtures :each bx/with-batch-extensions)

(deftest validate-ops-valid-basic-test
  (testing "Simple valid ops pass validation"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add"}
                   {:id "b" :tool "kg" :command "stats"}])]
      (is (:valid result))
      (is (nil? (:errors result))))))

(deftest validate-ops-valid-with-deps-test
  (testing "Valid ops with dependencies pass validation"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add"}
                   {:id "b" :tool "kg" :command "stats" :depends_on ["a"]}
                   {:id "c" :tool "preset" :command "list" :depends_on ["a" "b"]}])]
      (is (:valid result)))))

(deftest validate-ops-empty-vector-test
  (testing "Empty ops vector passes validation (no ops to check)"
    (let [result (multi/validate-ops [])]
      (is (:valid result)))))

(deftest validate-ops-missing-id-test
  (testing "Op without :id fails validation"
    (let [result (multi/validate-ops
                  [{:tool "memory" :command "add"}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "missing :id") (:errors result))))))

(deftest validate-ops-blank-id-test
  (testing "Op with blank :id fails validation"
    (let [result (multi/validate-ops
                  [{:id "" :tool "memory" :command "add"}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "missing :id") (:errors result))))))

(deftest validate-ops-missing-tool-test
  (testing "Op without :tool fails validation"
    (let [result (multi/validate-ops
                  [{:id "a" :command "add"}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "missing :tool") (:errors result))))))

(deftest validate-ops-blank-tool-test
  (testing "Op with blank :tool fails validation"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "" :command "add"}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "missing :tool") (:errors result))))))

(deftest validate-ops-duplicate-ids-test
  (testing "Duplicate IDs fail validation"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add"}
                   {:id "a" :tool "kg" :command "stats"}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "Duplicate") (:errors result))))))

(deftest validate-ops-nonexistent-dependency-test
  (testing "Reference to non-existent dependency fails"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add" :depends_on ["z"]}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "non-existent") (:errors result))))))

(deftest validate-ops-self-dependency-test
  (testing "Self-dependency fails validation"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add" :depends_on ["a"]}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "depends on itself") (:errors result))))))

(deftest validate-ops-circular-dependency-test
  (testing "Two-node cycle is rejected"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add" :depends_on ["b"]}
                   {:id "b" :tool "kg" :command "stats" :depends_on ["a"]}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "Circular dependency") (:errors result))))))

(deftest validate-ops-three-node-cycle-test
  (testing "Three-node cycle a -> b -> c -> a is rejected"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add" :depends_on ["c"]}
                   {:id "b" :tool "kg" :command "stats" :depends_on ["a"]}
                   {:id "c" :tool "preset" :command "list" :depends_on ["b"]}])]
      (is (not (:valid result)))
      (is (some #(str/includes? % "Circular dependency") (:errors result))))))

(deftest validate-ops-multiple-errors-test
  (testing "Multiple errors collected simultaneously"
    (let [result (multi/validate-ops
                  [{:id "" :tool "" :command "add"}
                   {:id "a" :tool "memory" :command "add"}
                   {:id "a" :tool "kg" :command "stats"}])]
      (is (not (:valid result)))
      ;; Should have at least: missing id, missing tool, duplicate id
      (is (>= (count (:errors result)) 2)))))

(deftest validate-ops-no-deps-key-test
  (testing "Ops without depends_on field are fine"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add"}
                   {:id "b" :tool "kg" :command "stats"}])]
      (is (:valid result)))))

(deftest validate-ops-empty-deps-test
  (testing "Ops with empty depends_on vector are fine"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add" :depends_on []}
                   {:id "b" :tool "kg" :command "stats" :depends_on []}])]
      (is (:valid result)))))

;; =============================================================================
;; Part 2: assign-waves Tests
;; =============================================================================

(deftest assign-waves-independent-ops-test
  (testing "Independent ops all in wave 1"
    (let [ops [{:id "a" :tool "memory"}
               {:id "b" :tool "kg"}
               {:id "c" :tool "preset"}]
          result (multi/assign-waves ops)]
      (is (= 3 (count result)))
      (is (every? #(= 1 (:wave %)) result)))))

(deftest assign-waves-linear-chain-test
  (testing "Linear dependency chain gets one wave per link"
    (let [ops [{:id "a" :tool "memory"}
               {:id "b" :tool "kg" :depends_on ["a"]}
               {:id "c" :tool "preset" :depends_on ["b"]}]
          result (multi/assign-waves ops)]
      (is (= 3 (count result)))
      (let [by-id (into {} (map (juxt :id identity) result))]
        (is (= 1 (:wave (get by-id "a"))))
        (is (= 2 (:wave (get by-id "b"))))
        (is (= 3 (:wave (get by-id "c"))))))))

(deftest assign-waves-diamond-test
  (testing "Diamond dependency: the two middle ops share a wave"
    ;; a → b, a → c, b → d, c → d
    (let [ops [{:id "a" :tool "memory"}
               {:id "b" :tool "kg" :depends_on ["a"]}
               {:id "c" :tool "preset" :depends_on ["a"]}
               {:id "d" :tool "agora" :depends_on ["b" "c"]}]
          result (multi/assign-waves ops)
          by-id (into {} (map (juxt :id identity) result))]
      (is (= 1 (:wave (get by-id "a"))))
      (is (= 2 (:wave (get by-id "b"))))
      (is (= 2 (:wave (get by-id "c"))))
      (is (= 3 (:wave (get by-id "d")))))))

(deftest assign-waves-empty-ops-test
  (testing "Empty ops returns empty result"
    (is (= [] (multi/assign-waves [])))))

(deftest assign-waves-single-op-test
  (testing "Single op gets wave 1"
    (let [result (multi/assign-waves [{:id "a" :tool "memory"}])]
      (is (= 1 (count result)))
      (is (= 1 (:wave (first result)))))))

(deftest assign-waves-preserves-op-data-test
  (testing "Wave assignment preserves all original op keys"
    (let [ops [{:id "a" :tool "memory" :command "add" :content "hello"}]
          result (multi/assign-waves ops)]
      (is (= "a" (:id (first result))))
      (is (= "memory" (:tool (first result))))
      (is (= "add" (:command (first result))))
      (is (= "hello" (:content (first result))))
      (is (= 1 (:wave (first result)))))))

(deftest assign-waves-multi-root-test
  (testing "Independent roots share wave 1; their dependent lands in wave 2"
    (let [ops [{:id "a" :tool "memory"}
               {:id "b" :tool "kg"}
               {:id "c" :tool "preset" :depends_on ["a" "b"]}]
          result (multi/assign-waves ops)
          by-id (into {} (map (juxt :id identity) result))]
      (is (= 1 (:wave (get by-id "a"))))
      (is (= 1 (:wave (get by-id "b"))))
      (is (= 2 (:wave (get by-id "c")))))))

;; =============================================================================
;; Part 3: execute-op Tests
;; =============================================================================

(deftest execute-op-unknown-tool-test
  (testing "Unknown tool returns error result"
    (let [result (multi/execute-op {:id "x" :tool "nonexistent-tool-xyz" :command "noop"})]
      (is (= "x" (:id result)))
      (is (false? (:success result)))
      (is (str/includes? (:error result) "not found")))))

(deftest execute-op-result-structure-test
  (testing "execute-op always returns :id and :success"
    (let [result (multi/execute-op {:id "test-1" :tool "nonexistent" :command "x"})]
      (is (contains? result :id))
      (is (contains? result :success)))))

;; =============================================================================
;; Part 4: run-multi Tests (integration)
;; =============================================================================

(deftest run-multi-validation-failure-test
  (testing "run-multi returns error on invalid ops"
    (let [result (multi/run-multi [{:id "" :tool ""}])]
      (is (false? (:success result)))
      (is (seq (:errors result)))
      (is (= 0 (get-in result [:summary :success]))))))

(deftest run-multi-dry-run-test
  (testing "run-multi dry-run returns plan without executing"
    (let [result (multi/run-multi
                  [{:id "a" :tool "memory" :command "help"}
                   {:id "b" :tool "kg" :command "help" :depends_on ["a"]}]
                  :dry-run true)]
      (is (:success result))
      (is (true? (:dry-run result)))
      ;; Should have wave plan
      (is (some? (:waves result)))
      ;; Summary should show 0 success/0 failed (no execution)
      (is (= 0 (get-in result [:summary :success])))
      (is (= 0 (get-in result [:summary :failed])))
      (is (> (get-in result [:summary :waves]) 0)))))

(deftest run-multi-dry-run-wave-structure-test
  (testing "dry-run waves have ops with expected keys"
    (let [result (multi/run-multi
                  [{:id "a" :tool "memory" :command "help"}
                   {:id "b" :tool "kg" :command "help"}]
                  :dry-run true)
          wave-1 (get-in result [:waves 1])]
      (is (some? wave-1))
      (is (vector? (:ops wave-1)))
      ;; Each op in plan should have at least :id :tool :command
      (doseq [op (:ops wave-1)]
        (is (contains? op :id))
        (is (contains? op :tool))
        (is (contains? op :command))))))

(deftest run-multi-empty-ops-test
  (testing "run-multi with empty ops passes validation (0 ops is trivially valid)"
    (let [result (multi/run-multi [])]
      ;; Empty ops should pass validation and return success with 0 ops
      (is (:success result))
      (is (= 0 (get-in result [:summary :total]))))))

(deftest run-multi-summary-structure-test
  (testing "run-multi summary always has :total :success :failed :waves"
    (let [result (multi/run-multi [{:id "a" :tool "memory" :command "help"}])]
      (is (contains? (:summary result) :total))
      (is (contains? (:summary result) :success))
      (is (contains? (:summary result) :failed))
      (is (contains? (:summary result) :waves)))))

(deftest run-multi-circular-dep-rejects-test
  (testing "run-multi rejects a circular batch before executing anything"
    (let [result (multi/run-multi
                  [{:id "a" :tool "memory" :command "add" :depends_on ["b"]}
                   {:id "b" :tool "kg" :command "stats" :depends_on ["a"]}])]
      (is (false? (:success result)))
      (is (some #(str/includes? % "Circular dependency") (:errors result)))
      (is (= 0 (get-in result [:summary :waves]))))))

;; =============================================================================
;; Part 5: format-results Tests
;; =============================================================================

(deftest format-results-dry-run-test
  (testing "format-results for dry-run includes plan"
    (let [input {:success true
                 :dry-run true
                 :waves {1 {:ops [{:id "a" :tool "memory" :command "add"}]}}
                 :summary {:total 1 :success 0 :failed 0 :waves 1}}
          result (multi/format-results input)]
      (is (= "text" (:type result)))
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (:success parsed))
        (is (true? (:dry_run parsed)))
        (is (some? (:plan parsed)))))))

(deftest format-results-execution-test
  (testing "format-results for execution includes wave results"
    (let [input {:success true
                 :waves {1 {:ops [{:id "a" :tool "memory" :command "add"}]
                            :results [{:id "a" :success true :result {:type "text" :text "ok"}}]}}
                 :summary {:total 1 :success 1 :failed 0 :waves 1}}
          result (multi/format-results input)]
      (is (= "text" (:type result)))
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (:success parsed))
        (is (nil? (:dry_run parsed)))
        (is (some? (:waves parsed)))))))

(deftest format-results-with-errors-test
  (testing "format-results includes errors when present"
    (let [input {:success false
                 :errors ["Op missing :id"]
                 :summary {:total 1 :success 0 :failed 0 :waves 0}}
          result (multi/format-results input)]
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (false? (:success parsed)))
        (is (seq (:errors parsed)))))))

(deftest format-results-summary-preserved-test
  (testing "format-results preserves summary fields"
    (let [input {:success true
                 :waves {}
                 :summary {:total 5 :success 3 :failed 2 :waves 2}}
          result (multi/format-results input)]
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (= 5 (get-in parsed [:summary :total])))
        (is (= 3 (get-in parsed [:summary :success])))
        (is (= 2 (get-in parsed [:summary :failed])))
        (is (= 2 (get-in parsed [:summary :waves])))))))

(deftest format-results-string-result-test
  (testing "format-results handles string result values"
    (let [input {:success true
                 :waves {1 {:ops [{:id "a" :tool "memory" :command "add"}]
                            :results [{:id "a" :success true :result "plain-text"}]}}
                 :summary {:total 1 :success 1 :failed 0 :waves 1}}
          result (multi/format-results input)]
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (:success parsed))))))

(deftest format-results-error-op-test
  (testing "format-results includes error for failed ops"
    (let [input {:success false
                 :waves {1 {:ops [{:id "a" :tool "bad" :command "x"}]
                            :results [{:id "a" :success false :error "Tool not found: bad"}]}}
                 :summary {:total 1 :success 0 :failed 1 :waves 1}}
          result (multi/format-results input)]
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (false? (:success parsed)))
        (let [wave-results (get-in parsed [:waves :wave_1])]
          (is (= 1 (count wave-results)))
          (is (false? (:success (first wave-results))))
          (is (some? (:error (first wave-results)))))))))

;; =============================================================================
;; Part 6: resolve-tool-handler Tests
;; =============================================================================

(deftest resolve-tool-handler-unknown-tool-test
  (testing "Resolving unknown tool returns nil"
    (is (nil? (multi/resolve-tool-handler "completely-nonexistent-tool-name")))))

;; =============================================================================
;; Part 7: Edge Cases
;; =============================================================================

(deftest validate-ops-nil-depends-on-test
  (testing "nil depends_on is treated as no dependencies"
    (let [result (multi/validate-ops
                  [{:id "a" :tool "memory" :command "add" :depends_on nil}])]
      (is (:valid result)))))

(deftest assign-waves-preserves-depends-on-test
  (testing "assign-waves preserves :depends_on in output"
    (let [result (multi/assign-waves
                  [{:id "a" :tool "memory"}
                   {:id "b" :tool "kg" :depends_on ["a"]}])]
      (let [b (first (filter #(= "b" (:id %)) result))]
        (is (= ["a"] (:depends_on b)))))))

(deftest run-multi-dep-failure-skips-downstream-test
  (testing "A failed op's dependents are skipped, never executed"
    (let [ran (atom [])]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (when (= "mock-b" tool-name)
                        (fn [_] (swap! ran conj "b")
                          {:type "text" :text "{\"ok\":true}"})))]
        ;; "a" resolves to no handler at all → guaranteed failure
        (let [result (multi/run-multi
                      [{:id "a" :tool "nonexistent-tool-xyz" :command "noop"}
                       {:id "b" :tool "mock-b" :command "noop" :depends_on ["a"]}])]
          (is (false? (:success result)))
          (is (= 2 (get-in result [:summary :total])))
          (is (= 0 (get-in result [:summary :success])))
          (is (= 2 (get-in result [:summary :failed])))
          (is (empty? @ran)
              "the dependent handler must not be invoked once its dep failed"))))))

;; =============================================================================
;; Part 8: FX Effect Registration Tests (Step 4)
;; =============================================================================

(deftest fx-handlers-registered-test
  (testing ":multi/wave-complete and :multi/op-error are registered in hive.events.fx"
    (require 'hive.events.fx)
    ;; Registration is an explicit boot step (`register-fx!`, documented safe to
    ;; call repeatedly), not a load side effect — so ARRANGE it rather than
    ;; depending on whether something else booted first in this JVM.
    (multi/register-fx!)
    (let [get-fx (resolve 'hive.events.fx/get-fx)]
      (is (fn? (get-fx :multi/wave-complete))
          ":multi/wave-complete should be registered as an FX handler")
      (is (fn? (get-fx :multi/op-error))
          ":multi/op-error should be registered as an FX handler"))))

(deftest register-fx-idempotent-test
  (testing "register-fx! can be called multiple times without error"
    (is (true? (multi/register-fx!)))
    (is (true? (multi/register-fx!)))))

(deftest fx-wave-complete-emitted-on-execution-test
  (testing "run-multi emits :multi/wave-complete FX for each wave"
    (require 'hive.events.fx)
    (let [wave-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      ;; Install capturing handler
      (reg-fx :multi/wave-complete (fn [data] (swap! wave-calls conj data)))
      (try
        ;; 2 independent ops = 1 wave
        (multi/run-multi
         [{:id "a" :tool "fake-tool-xyz" :command "noop"}
          {:id "b" :tool "fake-tool-xyz" :command "noop"}])
        (is (= 1 (count @wave-calls))
            "Should emit exactly 1 wave-complete for 1 wave")
        (let [wc (first @wave-calls)]
          (is (= 1 (:wave-num wc)))
          (is (= 2 (:op-count wc)))
          (is (= 1 (:total-waves wc))))
        (finally
          ;; Restore real handler
          (multi/register-fx!))))))

(deftest fx-wave-complete-multi-wave-test
  (testing "run-multi emits one :multi/wave-complete per dependency wave"
    (require 'hive.events.fx)
    (let [wave-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      (reg-fx :multi/wave-complete (fn [data] (swap! wave-calls conj data)))
      (try
        ;; b depends on a → two waves
        (multi/run-multi
         [{:id "a" :tool "fake-tool-xyz" :command "noop"}
          {:id "b" :tool "fake-tool-xyz" :command "noop" :depends_on ["a"]}])
        (is (= 2 (count @wave-calls))
            "a dependent op executes in its own wave")
        (is (= [1 2] (mapv :wave-num @wave-calls)))
        (is (every? #(= 2 (:total-waves %)) @wave-calls))
        (finally
          (multi/register-fx!))))))

(deftest fx-op-error-emitted-on-failure-test
  (testing "run-multi emits :multi/op-error FX for each failed op"
    (require 'hive.events.fx)
    (let [error-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      (reg-fx :multi/op-error (fn [data] (swap! error-calls conj data)))
      (try
        (multi/run-multi
         [{:id "a" :tool "fake-tool-xyz" :command "noop"}
          {:id "b" :tool "fake-tool-xyz" :command "noop"}])
        (is (= 2 (count @error-calls))
            "Should emit 2 op-error events for 2 failed ops")
        (is (= #{"a" "b"} (set (map :op-id @error-calls))))
        (is (every? #(= 1 (:wave-num %)) @error-calls))
        (is (every? #(string? (:error %)) @error-calls))
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
         [{:id "a" :tool "memory" :command "help"}]
         :dry-run true)
        (is (= 0 (count @wave-calls))
            "Dry-run should not emit wave-complete FX")
        (is (= 0 (count @error-calls))
            "Dry-run should not emit op-error FX")
        (finally
          (multi/register-fx!))))))

(deftest fx-wave-complete-counts-correct-test
  (testing ":multi/wave-complete reports correct success/failed counts"
    (require 'hive.events.fx)
    (let [wave-calls (atom [])
          reg-fx (resolve 'hive.events.fx/reg-fx)]
      (reg-fx :multi/wave-complete (fn [data] (swap! wave-calls conj data)))
      (try
        ;; All ops fail (fake tools)
        (multi/run-multi
         [{:id "a" :tool "fake-xyz" :command "noop"}
          {:id "b" :tool "fake-xyz" :command "noop"}
          {:id "c" :tool "fake-xyz" :command "noop"}])
        (let [wc (first @wave-calls)]
          (is (= 3 (:op-count wc)))
          (is (= 0 (:success-count wc)))
          (is (= 3 (:failed-count wc))))
        (finally
          (multi/register-fx!))))))

;; =============================================================================
;; Part 9: $ref Resolution Tests (Phase 1)
;; =============================================================================

;; --- 9a: ref? predicate ---

(deftest ref?-positive-test
  (testing "ref? returns true for $ref: strings"
    (is (true? (multi/ref? "$ref:op-1.data.id")))
    (is (true? (multi/ref? "$ref:a")))
    (is (true? (multi/ref? "$ref:op-1.success")))))

(deftest ref?-negative-test
  (testing "ref? returns false for non-ref values"
    (is (false? (multi/ref? "plain-string")))
    (is (false? (multi/ref? "")))
    (is (false? (multi/ref? nil)))
    (is (false? (multi/ref? 42)))
    (is (false? (multi/ref? :keyword)))
    (is (false? (multi/ref? "$REF:op-1")))))

;; --- 9b: parse-ref ---

;; The contract these pin lives in hive-mcp.batch.ref-contract and is checked
;; against every :bx/a provider by hive-mcp.batch.ref-conformance-test.

(deftest parse-ref-with-path-test
  (testing "parse-ref splits the source op-id from a STRING path — the result
            map's key encoding is resolve-ref's business, not the parser's"
    (is (= {:op-id "op-1" :path ["data" "id"]}
           (multi/parse-ref "$ref:op-1.data.id")))))

(deftest parse-ref-no-path-test
  (testing "A $ref carrying no path designates the whole op-result"
    (is (= {:op-id "op-1" :path []}
           (multi/parse-ref "$ref:op-1")))))

(deftest parse-ref-deep-path-test
  (testing "parse-ref keeps every path segment, numeric ones included"
    (is (= {:op-id "a" :path ["result" "content" "0" "text"]}
           (multi/parse-ref "$ref:a.result.content.0.text")))))

(deftest parse-ref-non-ref-test
  (testing "parse-ref returns nil for non-ref strings"
    (is (nil? (multi/parse-ref "not-a-ref")))
    (is (nil? (multi/parse-ref nil)))))

;; --- 9c: extract-result-data ---

(deftest extract-result-data-mcp-text-test
  (testing "Parses the JSON body out of an MCP :text response"
    (let [r (multi/extract-result-data
             {:type "text" :text "{\"id\":\"mem-123\",\"ok\":true}"})]
      (is (= "mem-123" (:id r)))
      (is (true? (:ok r))))))

(deftest extract-result-data-mcp-content-test
  (testing "Parses the JSON body out of an MCP :content response"
    (let [r (multi/extract-result-data
             {:content [{:type "text" :text "{\"id\":\"mem-456\"}"}]})]
      (is (= "mem-456" (:id r))))))

(deftest extract-result-data-structured-test
  (testing "Passes through already-structured data"
    (let [r (multi/extract-result-data {:already "structured" :num 42})]
      (is (= "structured" (:already r)))
      (is (= 42 (:num r))))))

(deftest extract-result-data-nil-test
  (testing "Returns nil for nil input"
    (is (nil? (multi/extract-result-data nil)))))

(deftest extract-result-data-invalid-json-test
  (testing "Falls back to raw result when JSON parse fails"
    (let [r (multi/extract-result-data {:type "text" :text "not-json"})]
      (is (= {:type "text" :text "not-json"} r)))))

;; --- 9d: enrich-op-result ---

(deftest enrich-op-result-test
  (testing "Adds :data key with parsed content"
    (let [r (multi/enrich-op-result
             {:id "a" :success true
              :result {:type "text" :text "{\"id\":\"mem-123\"}"}})]
      (is (= "mem-123" (get-in r [:data :id])))
      (is (= "a" (:id r)))
      (is (true? (:success r))))))

(deftest enrich-op-result-nil-result-test
  (testing "Handles nil :result gracefully"
    (let [r (multi/enrich-op-result {:id "a" :success false :error "failed"})]
      (is (nil? (:data r)))
      (is (false? (:success r))))))

;; --- 9e: resolve-ref ---

;; The parsed-ref shape is parse-ref's output, so build it with parse-ref
;; rather than restating the path encoding here.

(deftest resolve-ref-data-path-test
  (testing "Resolves into the source op's parsed :data"
    (let [results {"a" {:id "a" :success true :data {:id "mem-123" :status "ok"}}}]
      (is (= "mem-123"
             (multi/resolve-ref (multi/parse-ref "$ref:a.data.id") results))))))

(deftest resolve-ref-top-level-test
  (testing "Resolves into a top-level op-result key"
    (let [results {"a" {:id "a" :success true :data {:id "mem-123"}}}]
      (is (true?
           (multi/resolve-ref (multi/parse-ref "$ref:a.success") results))))))

(deftest resolve-ref-full-result-test
  (testing "An empty path designates the whole op-result"
    (let [results {"a" {:id "a" :success true :data {:id "mem-123"}}}]
      (is (= (get results "a")
             (multi/resolve-ref {:op-id "a" :path []} results))))))

(deftest resolve-ref-missing-op-test
  (testing "Returns the sentinel for a missing op-id"
    (let [results {"a" {:id "a" :success true}}]
      (is (= multi/ref-not-found
             (multi/resolve-ref {:op-id "missing" :path ["data"]} results))))))

(deftest resolve-ref-nil-path-value-test
  (testing "A path that exists but holds nil resolves to nil, not the sentinel"
    (let [results {"a" {:id "a" :success true :data {:field nil}}}]
      (is (nil? (multi/resolve-ref (multi/parse-ref "$ref:a.data.field") results))))))

;; --- 9f: resolve-refs-in-value ---

(deftest resolve-refs-in-value-string-test
  (testing "A $ref string is replaced by the value it designates"
    (let [results {"a" {:data {:id "mem-123"}}}]
      (is (= "mem-123" (multi/resolve-refs-in-value "$ref:a.data.id" results))))))

(deftest resolve-refs-in-value-map-test
  (testing "Refs inside a map are resolved, other entries left alone"
    (let [results {"a" {:data {:id "mem-123"}}}
          r (multi/resolve-refs-in-value {:from "$ref:a.data.id" :to "static"} results)]
      (is (= {:from "mem-123" :to "static"} r)))))

(deftest resolve-refs-in-value-vector-test
  (testing "Refs inside a vector are resolved element-wise"
    (let [results {"a" {:data {:id "mem-123"}}}
          r (multi/resolve-refs-in-value ["$ref:a.data.id" "static" 42] results)]
      (is (= ["mem-123" "static" 42] r)))))

(deftest resolve-refs-in-value-nested-test
  (testing "Refs resolve at any depth, keeping the resolved value's type"
    (let [results {"a" {:data {:id "mem-123"}} "b" {:success true}}
          r (multi/resolve-refs-in-value
             {:outer {:inner "$ref:a.data.id"}
              :list ["$ref:b.success" "plain"]}
             results)]
      (is (= {:outer {:inner "mem-123"}
              :list [true "plain"]}
             r)))))

(deftest resolve-refs-in-value-passthrough-test
  (testing "Non-ref values pass through unchanged"
    (let [results {}]
      (is (= "plain" (multi/resolve-refs-in-value "plain" results)))
      (is (= 42 (multi/resolve-refs-in-value 42 results)))
      (is (= true (multi/resolve-refs-in-value true results)))
      (is (nil? (multi/resolve-refs-in-value nil results))))))

(deftest resolve-refs-in-value-unresolvable-test
  (testing "Unresolvable $ref keeps original string"
    (let [results {}]
      (is (= "$ref:missing.data.id"
             (multi/resolve-refs-in-value "$ref:missing.data.id" results))))))

;; --- 9g: resolve-op-refs ---

(deftest resolve-op-refs-preserves-meta-keys-test
  (testing "Only ref-walkable params are resolved — meta keys stay literal"
    (let [results {"a" {:data {:id "mem-123"}}}
          op {:id "$ref:a.data.id" :tool "$ref:a.data.id"
              :depends_on ["$ref:a.data.id"] :wave "$ref:a.data.id"
              :from "$ref:a.data.id"}
          meta-keys [:id :tool :depends_on :wave]
          r (multi/resolve-op-refs op results)]
      (is (= "mem-123" (:from r)))
      (is (= (select-keys op meta-keys) (select-keys r meta-keys))))))

;; --- 9h: collect-ref-op-ids ---

(deftest collect-ref-op-ids-test
  (testing "Collects every op-id the ref-walkable params reference"
    (let [op {:id "b" :tool "kg" :command "edge"
              :from "$ref:a.data.id"
              :tags ["$ref:c.data.tag" "static"]
              :depends_on ["a" "c"]}
          refs (multi/collect-ref-op-ids op)]
      (is (= #{"a" "c"} refs)))))

(deftest collect-ref-op-ids-no-refs-test
  (testing "Returns empty set when no $refs present"
    (let [op {:id "a" :tool "memory" :command "add" :content "hello"}
          refs (multi/collect-ref-op-ids op)]
      (is (= #{} refs)))))

(deftest collect-ref-op-ids-skips-meta-test
  (testing "Meta keys are not walked — only real params contribute op-ids"
    (let [op {:id "$ref:fake" :tool "$ref:fake"
              :depends_on ["$ref:fake"] :wave "$ref:fake"
              :real-param "$ref:a.data.id"}
          refs (multi/collect-ref-op-ids op)]
      (is (= #{"a"} refs)))))

;; --- 9i: validate-ops with $ref validation ---

(deftest validate-ops-ref-with-dep-test
  (testing "Valid: $ref to declared dependency"
    (let [r (multi/validate-ops
             [{:id "a" :tool "memory" :command "add"}
              {:id "b" :tool "kg" :command "edge"
               :from "$ref:a.data.id" :depends_on ["a"]}])]
      (is (:valid r)))))

(deftest validate-ops-ref-without-dep-test
  (testing "Invalid: a $ref is an ORDERING EDGE, not merely a name — an op
            referenced but not declared in depends_on may not have run yet,
            so the batch is refused even though the op is in it"
    (let [r (multi/validate-ops
             [{:id "a" :tool "memory" :command "add"}
              {:id "b" :tool "kg" :command "edge"
               :from "$ref:a.data.id"}])]
      (is (not (:valid r)))
      (is (some #(str/includes? % "doesn't declare it in depends_on")
                (:errors r))))))

(deftest validate-ops-ref-to-absent-op-test
  (testing "Invalid: a $ref naming an op the batch does not contain"
    (let [r (multi/validate-ops
             [{:id "b" :tool "kg" :command "edge"
               :from "$ref:ghost.data.id"}])]
      (is (not (:valid r)))
      (is (some #(str/includes? % "ghost") (:errors r))))))

(deftest validate-ops-ref-multiple-deps-test
  (testing "Valid: multiple $refs to different declared dependencies"
    (let [r (multi/validate-ops
             [{:id "a" :tool "memory" :command "add"}
              {:id "b" :tool "memory" :command "add"}
              {:id "c" :tool "kg" :command "edge"
               :from "$ref:a.data.id" :to "$ref:b.data.id"
               :depends_on ["a" "b"]}])]
      (is (:valid r)))))

(deftest validate-ops-ref-partial-dep-test
  (testing "Invalid: one $ref declared, the other not — the undeclared ref is
            refused and the declared one is not"
    (let [r (multi/validate-ops
             [{:id "a" :tool "memory" :command "add"}
              {:id "b" :tool "memory" :command "add"}
              {:id "c" :tool "kg" :command "edge"
               :from "$ref:a.data.id" :to "$ref:b.data.id"
               :depends_on ["a"]}])]
      (is (not (:valid r)))
      (is (some #(str/includes? % "$ref to 'b'") (:errors r)))
      (is (not-any? #(str/includes? % "$ref to 'a'") (:errors r))))))

;; --- 9j: Integration — run-multi with $ref threading ---

(deftest ref-integration-basic-test
  (testing "run-multi threads a producer's data into its dependent's params"
    (let [captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-a" (fn [_params]
                                   {:type "text" :text "{\"id\":\"mem-123\",\"status\":\"created\"}"})
                        "mock-b" (fn [params]
                                   (reset! captured params)
                                   {:type "text" :text "{\"ok\":true}"})
                        nil))]
        (let [result (multi/run-multi
                      [{:id "a" :tool "mock-a" :command "add"}
                       {:id "b" :tool "mock-b" :command "edge"
                        :from "$ref:a.data.id"
                        :status "$ref:a.data.status"
                        :depends_on ["a"]}])]
          (is (:success result))
          (is (= 2 (get-in result [:summary :success])))
          ;; the handler receives resolved values, never the $ref string
          (is (= "mem-123" (:from @captured)))
          (is (= "created" (:status @captured))))))))

(deftest ref-integration-chained-test
  (testing "A three-op chain threads each producer's id into the next op"
    (let [c-captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-a" (fn [_] {:type "text" :text "{\"id\":\"id-a\"}"})
                        "mock-b" (fn [p] {:type "text" :text (str "{\"id\":\"id-b\",\"parent\":\"" (:parent p) "\"}")})
                        "mock-c" (fn [p] (reset! c-captured p) {:type "text" :text "{\"ok\":true}"})
                        nil))]
        (let [result (multi/run-multi
                      [{:id "a" :tool "mock-a" :command "create"}
                       {:id "b" :tool "mock-b" :command "create"
                        :parent "$ref:a.data.id" :depends_on ["a"]}
                       {:id "c" :tool "mock-c" :command "link"
                        :from "$ref:a.data.id" :to "$ref:b.data.id"
                        :depends_on ["a" "b"]}])]
          (is (:success result))
          (is (= 3 (get-in result [:summary :success])))
          (is (= "id-a" (:from @c-captured)))
          (is (= "id-b" (:to @c-captured))))))))

(deftest ref-integration-dep-failure-test
  (testing "When dep fails, downstream op is skipped (not ref-resolved)"
    (with-redefs [multi/resolve-tool-handler
                  (fn [tool-name]
                    (case tool-name
                      "mock-fail" (fn [_] (throw (Exception. "boom")))
                      "mock-b"    (fn [_] {:type "text" :text "{\"ok\":true}"})
                      nil))]
      (let [result (multi/run-multi
                    [{:id "a" :tool "mock-fail" :command "add"}
                     {:id "b" :tool "mock-b" :command "edge"
                      :from "$ref:a.data.id" :depends_on ["a"]}])]
        (is (false? (:success result)))
        (is (= 0 (get-in result [:summary :success])))
        (is (= 2 (get-in result [:summary :failed])))))))

(deftest ref-integration-validation-rejects-undeclared-ref-test
  (testing "run-multi refuses a batch whose $ref names an op present in the
            batch but undeclared in depends_on — the ref would be read from a
            same-wave op that has not run"
    (let [result (multi/run-multi
                  [{:id "a" :tool "memory" :command "add"}
                   {:id "b" :tool "kg" :command "edge"
                    :from "$ref:a.data.id"}])]
      (is (false? (:success result)))
      (is (some #(str/includes? % "doesn't declare it in depends_on")
                (:errors result)))
      ;; refused at compile time — nothing was scheduled, let alone executed
      (is (= 0 (get-in result [:summary :waves]))))))

(deftest ref-integration-validation-rejects-ref-to-absent-op-test
  (testing "run-multi refuses a batch whose $ref names an op it does not contain"
    (let [result (multi/run-multi
                  [{:id "a" :tool "memory" :command "add"}
                   {:id "b" :tool "kg" :command "edge"
                    :from "$ref:ghost.data.id"}])]
      (is (false? (:success result)))
      (is (some #(str/includes? % "ghost") (:errors result)))
      (is (= 0 (get-in result [:summary :waves]))))))

(deftest ref-integration-non-string-resolution-test
  (testing "A resolved $ref keeps its JSON type — number, bool, nested map"
    (let [captured (atom nil)]
      (with-redefs [multi/resolve-tool-handler
                    (fn [tool-name]
                      (case tool-name
                        "mock-a" (fn [_]
                                   {:type "text"
                                    :text "{\"count\":42,\"active\":true,\"meta\":{\"key\":\"val\"}}"})
                        "mock-b" (fn [p] (reset! captured p) {:type "text" :text "{\"ok\":true}"})
                        nil))]
        (let [result (multi/run-multi
                      [{:id "a" :tool "mock-a" :command "stats"}
                       {:id "b" :tool "mock-b" :command "process"
                        :count "$ref:a.data.count"
                        :active "$ref:a.data.active"
                        :meta "$ref:a.data.meta"
                        :depends_on ["a"]}])]
          (is (:success result))
          (is (= 42 (:count @captured)))
          (is (true? (:active @captured)))
          (is (= {:key "val"} (:meta @captured))))))))

(comment
  ;; Run all tests in this namespace via REPL
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'hive-mcp.tools.multi-test))
