(ns hive-mcp.tools.core-test
  "Tests for MCP tool response core utilities.
   
   Covers:
   - Basic response formatting (mcp-success, mcp-error, mcp-json)
   - Instruction queue management (push, drain)
   - Piggyback instructions on responses"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.tools.core :as core]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.test.stub.elisp :as elisp-stub]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn clear-instruction-queues!
  "Clear all instruction queues for test isolation."
  []
  (reset! piggyback/instruction-queues {}))

(use-fixtures :each (fn [f]
                      (clear-instruction-queues!)
                      (f)
                      (clear-instruction-queues!)))

;; =============================================================================
;; Test: Basic Response Formatting
;; =============================================================================

(deftest test-mcp-success-basic
  (testing "mcp-success creates correct response structure"
    (let [response (core/mcp-success "Hello")]
      (is (= "text" (:type response)))
      (is (= "Hello" (:text response)))
      (is (nil? (:isError response))))))

(deftest test-mcp-success-non-string
  (testing "mcp-success converts non-strings with pr-str"
    (let [response (core/mcp-success {:foo "bar"})]
      (is (= "text" (:type response)))
      (is (string? (:text response))))))

(deftest test-mcp-error-basic
  (testing "mcp-error creates error response structure"
    (let [response (core/mcp-error "Something failed")]
      (is (= "text" (:type response)))
      (is (= "Something failed" (:text response)))
      (is (true? (:isError response))))))

(deftest test-mcp-json-basic
  (testing "mcp-json creates JSON response"
    (let [response (core/mcp-json {:status "ok" :count 5})]
      (is (= "text" (:type response)))
      (let [parsed (json/read-str (:text response) :key-fn keyword)]
        (is (= "ok" (:status parsed)))
        (is (= 5 (:count parsed)))))))

;; =============================================================================
;; Test: Instruction Queue Management
;; =============================================================================

(deftest test-push-instruction-single
  (testing "push-instruction! adds instruction to agent queue"
    (core/push-instruction! "ling-1" {:action "pause" :reason "rate limit"})
    (is (= 1 (count (get @piggyback/instruction-queues "ling-1"))))))

(deftest test-push-instruction-multiple
  (testing "push-instruction! accumulates multiple instructions"
    (core/push-instruction! "ling-1" {:action "pause"})
    (core/push-instruction! "ling-1" {:action "resume"})
    (core/push-instruction! "ling-1" {:action "abort"})
    (is (= 3 (count (get @piggyback/instruction-queues "ling-1"))))))

(deftest test-push-instruction-multiple-agents
  (testing "Instructions are isolated per agent"
    (core/push-instruction! "ling-1" {:action "pause"})
    (core/push-instruction! "ling-2" {:action "continue"})
    (core/push-instruction! "ling-1" {:action "resume"})
    (is (= 2 (count (get @piggyback/instruction-queues "ling-1"))))
    (is (= 1 (count (get @piggyback/instruction-queues "ling-2"))))))

(deftest test-drain-instructions-returns-all
  (testing "drain-instructions! returns all queued instructions"
    (core/push-instruction! "ling-1" {:action "A"})
    (core/push-instruction! "ling-1" {:action "B"})
    (let [instructions (core/drain-instructions! "ling-1")]
      (is (= 2 (count instructions)))
      (is (= {:action "A"} (first instructions)))
      (is (= {:action "B"} (second instructions))))))

(deftest test-drain-instructions-clears-queue
  (testing "drain-instructions! clears the queue after draining"
    (core/push-instruction! "ling-1" {:action "test"})
    (core/drain-instructions! "ling-1")
    (is (empty? (get @piggyback/instruction-queues "ling-1" [])))))

(deftest test-drain-instructions-empty
  (testing "drain-instructions! returns empty vector for unknown agent"
    (let [instructions (core/drain-instructions! "unknown-agent")]
      (is (empty? instructions)))))

(deftest test-drain-instructions-isolated
  (testing "Draining one agent doesn't affect others"
    (core/push-instruction! "ling-1" {:action "A"})
    (core/push-instruction! "ling-2" {:action "B"})
    (core/drain-instructions! "ling-1")
    (is (= 1 (count (get @piggyback/instruction-queues "ling-2"))))))

;; =============================================================================
;; Test: Piggyback Instructions in Responses
;; =============================================================================

(deftest test-mcp-success-without-agent-id
  (testing "mcp-success without agent-id has no pending_instructions"
    (core/push-instruction! "ling-1" {:action "test"})
    (let [response (core/mcp-success "Hello")]
      (is (nil? (:pending_instructions response)))
      ;; Queue should still have the instruction
      (is (= 1 (count (get @piggyback/instruction-queues "ling-1")))))))

(deftest test-mcp-success-with-agent-id-no-instructions
  (testing "mcp-success with agent-id but no instructions omits pending_instructions"
    (let [response (core/mcp-success "Hello" :agent-id "ling-1")]
      (is (nil? (:pending_instructions response))))))

(deftest test-mcp-success-with-agent-id-has-instructions
  (testing "mcp-success with agent-id includes pending_instructions"
    (core/push-instruction! "ling-1" {:action "pause" :reason "rate limit"})
    (core/push-instruction! "ling-1" {:action "change-priority" :level "high"})
    (let [response (core/mcp-success "Task completed" :agent-id "ling-1")]
      (is (= "Task completed" (:text response)))
      (is (= 2 (count (:pending_instructions response))))
      (is (= {:action "pause" :reason "rate limit"}
             (first (:pending_instructions response)))))))

(deftest test-mcp-success-drains-queue
  (testing "mcp-success with agent-id drains the instruction queue"
    (core/push-instruction! "ling-1" {:action "test"})
    (core/mcp-success "Done" :agent-id "ling-1")
    (is (empty? (get @piggyback/instruction-queues "ling-1" [])))))

(deftest test-mcp-json-without-agent-id
  (testing "mcp-json without agent-id has no pending_instructions"
    (core/push-instruction! "ling-1" {:action "test"})
    (let [response (core/mcp-json {:status "ok"})]
      (is (nil? (:pending_instructions response))))))

(deftest test-mcp-json-with-agent-id-has-instructions
  (testing "mcp-json with agent-id includes pending_instructions"
    (core/push-instruction! "ling-1" {:action "redirect" :target "drone-2"})
    (let [response (core/mcp-json {:status "ok"} :agent-id "ling-1")]
      (is (= 1 (count (:pending_instructions response))))
      (is (= {:action "redirect" :target "drone-2"}
             (first (:pending_instructions response)))))))

(deftest test-mcp-json-drains-queue
  (testing "mcp-json with agent-id drains the instruction queue"
    (core/push-instruction! "ling-1" {:action "test"})
    (core/mcp-json {:data "value"} :agent-id "ling-1")
    (is (empty? (get @piggyback/instruction-queues "ling-1" [])))))

;; =============================================================================
;; Test: Instruction Types (examples of what can be piggybacked)
;; =============================================================================

(deftest test-instruction-types
  (testing "Various instruction types can be queued"
    ;; Flow control
    (core/push-instruction! "ling-1" {:type "flow" :action "pause"})
    (core/push-instruction! "ling-1" {:type "flow" :action "abort"})

    ;; Priority changes
    (core/push-instruction! "ling-1" {:type "priority" :level "urgent"})

    ;; Context updates
    (core/push-instruction! "ling-1" {:type "context"
                                      :file "/src/changed.clj"
                                      :message "File modified externally"})

    ;; Coordination
    (core/push-instruction! "ling-1" {:type "coordinate"
                                      :wait-for "ling-2"
                                      :timeout-ms 5000})

    (let [instructions (core/drain-instructions! "ling-1")]
      (is (= 5 (count instructions)))
      (is (= "flow" (:type (first instructions))))
      (is (= "coordinate" (:type (last instructions)))))))

;; =============================================================================
;; Test: Thread Safety (basic)
;; =============================================================================

(deftest test-concurrent-push-drain
  (testing "Concurrent pushes don't lose instructions"
    (let [agent-id "concurrent-test"
          push-count 100
          futures (doall
                   (for [i (range push-count)]
                     (future
                       (core/push-instruction! agent-id {:index i}))))]
      ;; Wait for all pushes
      (doseq [f futures] @f)
      ;; Drain and verify count
      (let [instructions (core/drain-instructions! agent-id)]
        (is (= push-count (count instructions)))))))

;; =============================================================================
;; Test: call-elisp-safe Macro
;; =============================================================================

(deftest test-call-elisp-safe-success-default
  (testing "call-elisp-safe returns mcp-json wrapped result on success"
    (elisp-stub/with-eval-elisp
      (fn [_elisp] {:success true :result {:status "ok"}})
      (fn []
        (let [response (core/call-elisp-safe "(some-elisp)")]
          (is (= "text" (:type response)))
          (let [parsed (json/read-str (:text response) :key-fn keyword)]
            (is (= {:status "ok"} parsed))))))))

(deftest test-call-elisp-safe-error-default
  (testing "call-elisp-safe returns mcp-json with :error on failure"
    (elisp-stub/with-eval-elisp
      (fn [_elisp] {:success false :error "Elisp error occurred"})
      (fn []
        (let [response (core/call-elisp-safe "(failing-elisp)")]
          (is (= "text" (:type response)))
          (let [parsed (json/read-str (:text response) :key-fn keyword)]
            (is (= "Elisp error occurred" (:error parsed)))))))))

(deftest test-call-elisp-safe-custom-on-success
  (testing "call-elisp-safe uses custom :on-success handler"
    (elisp-stub/with-eval-elisp
      (fn [_elisp] {:success true :result {:count 42}})
      (fn []
        (let [response (core/call-elisp-safe "(some-elisp)"
                                             :on-success #(core/mcp-json {:transformed (:count %)}))]
          (is (= "text" (:type response)))
          (let [parsed (json/read-str (:text response) :key-fn keyword)]
            (is (= 42 (:transformed parsed)))))))))

(deftest test-call-elisp-safe-custom-on-error
  (testing "call-elisp-safe uses custom :on-error handler"
    (elisp-stub/with-eval-elisp
      (fn [_elisp] {:success false :error "custom error"})
      (fn []
        (let [response (core/call-elisp-safe "(failing-elisp)"
                                             :on-error #(core/mcp-error (str "Custom: " %)))]
          (is (= "text" (:type response)))
          (is (= "Custom: custom error" (:text response)))
          (is (true? (:isError response))))))))

(deftest test-call-elisp-safe-string-result
  (testing "call-elisp-safe handles string results correctly"
    (elisp-stub/with-eval-elisp
      (fn [_elisp] {:success true :result "plain string result"})
      (fn []
        (let [response (core/call-elisp-safe "(string-returning-elisp)")]
          (is (= "text" (:type response)))
          (let [parsed (json/read-str (:text response) :key-fn keyword)]
            (is (= "plain string result" parsed))))))))

(deftest test-call-elisp-safe-nil-result
  (testing "call-elisp-safe handles nil result gracefully"
    (elisp-stub/with-eval-elisp
      (fn [_elisp] {:success true :result nil})
      (fn []
        (let [response (core/call-elisp-safe "(nil-returning-elisp)")]
          (is (= "text" (:type response)))
          (is (= "null" (:text response))))))))

(deftest test-call-elisp-safe-macroexpand
  (testing "call-elisp-safe macro expands correctly"
    (let [expanded (macroexpand `(core/call-elisp-safe "(test)"))]
      ;; Should expand to a let binding with the elisp result
      (is (seq? expanded))
      (is (= 'let* (first expanded))))))

(deftest test-call-elisp-safe-macroexpand-with-opts
  (testing "call-elisp-safe macro expands correctly with options"
    (let [expanded (macroexpand `(core/call-elisp-safe "(test)"
                                                       :on-success identity))]
      (is (seq? expanded))
      (is (= 'let* (first expanded))))))

(deftest test-call-elisp-safe-elisp-code-passed-through
  (testing "call-elisp-safe passes elisp code to the transport"
    (let [captured-elisp (atom nil)]
      (elisp-stub/with-eval-elisp
        (fn [elisp]
          (reset! captured-elisp elisp)
          {:success true :result :ok})
        (fn []
          (core/call-elisp-safe "(my-elisp-code)")
          (is (= "(my-elisp-code)" @captured-elisp)))))))

;; =============================================================================
;; Test: with-validation Macro
;; =============================================================================

(defn sample-validator
  "Sample validator: returns nil if valid, error map if invalid.
   Validates that :name is present and non-empty."
  [{:keys [name]}]
  (cond
    (nil? name) {:error "Missing required field: name"}
    (empty? name) {:error "name cannot be empty"}
    :else nil))

(deftest test-with-validation-valid-params
  (testing "with-validation executes body when validation passes"
    (let [result (core/with-validation [{:name "test"} sample-validator]
                   (core/mcp-success "Body executed"))]
      (is (= "text" (:type result)))
      (is (= "Body executed" (:text result)))
      (is (nil? (:isError result))))))

(deftest test-with-validation-invalid-params-nil
  (testing "with-validation returns error when param is missing"
    (let [result (core/with-validation [{} sample-validator]
                   (core/mcp-success "Should not execute"))]
      (is (= "text" (:type result)))
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (= "Missing required field: name" (:error parsed)))))))

(deftest test-with-validation-invalid-params-empty
  (testing "with-validation returns error when param is empty"
    (let [result (core/with-validation [{:name ""} sample-validator]
                   (core/mcp-success "Should not execute"))]
      (is (= "text" (:type result)))
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (= "name cannot be empty" (:error parsed)))))))

(deftest test-with-validation-body-not-executed-on-failure
  (testing "with-validation does not execute body when validation fails"
    (let [executed (atom false)]
      (core/with-validation [{} sample-validator]
        (reset! executed true)
        (core/mcp-success "Body"))
      (is (false? @executed)))))

(deftest test-with-validation-body-returns-value
  (testing "with-validation returns body result on success"
    (let [result (core/with-validation [{:name "valid"} sample-validator]
                   {:custom "response" :data 123})]
      (is (= {:custom "response" :data 123} result)))))

(deftest test-with-validation-macroexpand
  (testing "with-validation macro expands correctly"
    (let [expanded (macroexpand `(core/with-validation [~'params ~'validator]
                                   (+ 1 2)))]
      ;; if-let expands to let*, so we check the structure is a valid form
      (is (seq? expanded))
      (is (= 'let* (first expanded))))))

(deftest test-with-validation-error-passthrough
  (testing "with-validation passes validator error directly to mcp-json"
    ;; Validator that returns a complex error structure
    (let [complex-validator (fn [_] {:error "complex" :details {:field "x" :code 42}})
          result (core/with-validation [{:any "params"} complex-validator]
                   (core/mcp-success "Never"))]
      (is (= "text" (:type result)))
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (= {:error "complex" :details {:field "x" :code 42}} parsed))))))

;; =============================================================================
;; Test: Parameter Coercion (Elm-style helpful errors)
;; =============================================================================

(deftest test-coerce-int-valid-integer
  (testing "coerce-int returns {:ok value} for valid integer"
    (is (= {:ok 42} (core/coerce-int 42 :limit)))
    (is (= {:ok 0} (core/coerce-int 0 :limit)))
    (is (= {:ok -5} (core/coerce-int -5 :offset)))))

(deftest test-coerce-int-valid-string
  (testing "coerce-int parses string numbers to integers"
    (is (= {:ok 42} (core/coerce-int "42" :limit)))
    (is (= {:ok 0} (core/coerce-int "0" :limit)))
    (is (= {:ok -10} (core/coerce-int "-10" :offset)))
    (is (= {:ok 1000000} (core/coerce-int "1000000" :limit)))))

(deftest test-coerce-int-invalid-string
  (testing "coerce-int returns Elm-style error for non-numeric strings"
    (let [{:keys [ok error]} (core/coerce-int "abc" :limit)]
      (is (nil? ok))
      (is (string? error))
      (is (re-find #"I was expecting a number" error))
      (is (re-find #"`limit`" error))
      (is (re-find #"\"abc\"" error))
      (is (re-find #"HINT:" error)))))

(deftest test-coerce-int-nil-with-default
  (testing "coerce-int uses default when value is nil"
    (is (= {:ok 20} (core/coerce-int nil :limit 20)))
    (is (= {:ok 0} (core/coerce-int nil :offset 0)))))

(deftest test-coerce-int-nil-without-default
  (testing "coerce-int returns error when nil and no default"
    (let [{:keys [ok error]} (core/coerce-int nil :limit)]
      (is (nil? ok))
      (is (string? error))
      (is (re-find #"I was expecting a number" error))
      (is (re-find #"got nothing" error)))))

(deftest test-coerce-int-wrong-type
  (testing "coerce-int returns error for wrong types"
    (let [{:keys [ok error]} (core/coerce-int {:foo "bar"} :limit)]
      (is (nil? ok))
      (is (re-find #"I was expecting a number" error))
      (is (re-find #"PersistentArrayMap" error)))))

(deftest test-coerce-int!-valid-input
  (testing "coerce-int! returns value directly on valid input"
    (is (= 42 (core/coerce-int! 42 :limit)))
    (is (= 42 (core/coerce-int! "42" :limit)))
    (is (= 20 (core/coerce-int! nil :limit 20)))))

(deftest test-coerce-int!-throws-on-invalid
  (testing "coerce-int! throws ExceptionInfo on invalid input"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"I was expecting a number"
         (core/coerce-int! "abc" :limit)))

    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"I was expecting a number"
         (core/coerce-int! nil :limit)))))

(deftest test-coerce-int!-exception-data
  (testing "coerce-int! exception includes useful ex-data"
    (try
      (core/coerce-int! "abc" :limit)
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :coercion-error (:type data)))
          (is (= :limit (:param data)))
          (is (= "abc" (:value data))))))))

(deftest test-coerce-int-edge-cases
  (testing "coerce-int handles edge cases"
    ;; Empty string is invalid
    (is (:error (core/coerce-int "" :limit)))

    ;; Whitespace-only string is invalid
    (is (:error (core/coerce-int "  " :limit)))

    ;; Float strings are invalid (we want integers)
    (is (:error (core/coerce-int "3.14" :limit)))

    ;; Very large numbers work
    (is (= {:ok 9223372036854775807}
           (core/coerce-int "9223372036854775807" :limit)))))

(deftest test-coerce-int-error-message-quality
  (testing "Error messages are helpful Elm-style"
    (let [{:keys [error]} (core/coerce-int "1" :limit)]
      ;; String "1" should succeed, not error
      (is (nil? error)))

    (let [{:keys [error]} (core/coerce-int "one" :limit)]
      ;; Should mention what was expected
      (is (re-find #"expecting a number" error))
      ;; Should mention the parameter name
      (is (re-find #"limit" error))
      ;; Should show what was received
      (is (re-find #"one" error))
      ;; Should have a hint
      (is (re-find #"HINT" error))
      ;; Should show right vs wrong
      (is (re-find #"WRONG" error))
      (is (re-find #"RIGHT" error)))))
