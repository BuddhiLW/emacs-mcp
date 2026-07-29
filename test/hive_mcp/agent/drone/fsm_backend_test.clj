(ns hive-mcp.agent.drone.fsm-backend-test
  "Integration tests for FSMAgenticBackend → drone-loop FSM full stack.

   These tests exercise the real FSM pipeline end-to-end:
     FSMAgenticBackend.execute-drone → run-drone-loop → compiled drone-loop FSM
       → sub-FSMs (llm-call, tool-execution, context-gather)

   **Not** mocked at the run-drone-loop level (that's in fsm_agentic_test.clj).
   Instead, we mock only the leaf resources (LLM backend, tool executor, KG recorders)
   and let the FSM engine drive the state machine through real transitions.

   Covers:
   1. Three-turn scenario: tool_call → tool_call → text completion
   2. KG operations verification (observations + reasoning recorded)
   3. Termination heuristics: max-turns, completion language, consecutive failures
   4. Token accumulation across turns
   5. Error recovery: LLM errors mid-loop
   6. Single-turn text completion (fast path)
   7. Empty tool calls routing
   8. Backend → FSM → result adaptation (full contract)

   Plan reference: step-14 of plan-20260208-fsm-drone-loop"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.workflows.sub-fsms.drone-loop :as drone-loop]
            [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.agent.drone.backend.fsm-agentic :as fsm-backend]
            [hive-mcp.agent.protocol :as proto]
            [hive.events.fsm :as fsm]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Helpers — Mock Infrastructure
;; =============================================================================

(defn- make-sequential-backend
  "Create an LLMBackend that returns responses in sequence.

   Each call to `chat` pops the next response from the queue.
   When queue is exhausted, returns a default text completion."
  [responses]
  (let [queue (atom (vec responses))]
    (reify proto/LLMBackend
      (model-name [_] "integration-test-model")
      (chat [_ _messages _tools]
        (let [resp (first @queue)]
          (swap! queue #(vec (rest %)))
          (or resp {:type :text
                    :content "Default completion response"
                    :usage {:input 10 :output 5 :total 15}}))))))

(defn- tool-calls-response
  "Build a mock LLM tool_calls response with given tool names."
  [& tool-names]
  {:type :tool_calls
   :calls (mapv (fn [n] {:id (str "call-" n) :name n :arguments {}})
                tool-names)
   :usage {:input 20 :output 15 :total 35}})

(defn- text-response
  "Build a mock LLM text response."
  [content]
  {:type :text :content content :usage {:input 10 :output 5 :total 15}})

(defn- error-response
  "Build a mock LLM error response."
  [msg]
  {:type :error :error msg :usage {:input 5 :output 0 :total 5}})

(defn- register-recording-fx!
  "Register FX handlers that append the drone-loop's recording effects to
   OBS-LOG / REASON-LOG.

   `hive-mcp.events.effects.drone-loop/register-drone-loop-effects!` is a no-op
   stub, so nothing interprets these effects in production either — see the
   kanban card. The FSM's own contract is that it DECLARES them, which is what
   these tests verify."
  [obs-log reason-log]
  (require 'hive.events.fx)
  (let [reg-fx (resolve 'hive.events.fx/reg-fx)]
    (reg-fx :drone/record-obs
            (fn [{:keys [turn tool-name result opts]}]
              (swap! obs-log conj
                     {:turn turn :tool tool-name
                      :result result :file (:file opts)})))
    (reg-fx :drone/record-reason
            (fn [{:keys [turn action detail]}]
              (swap! reason-log conj
                     {:turn turn :action action :detail detail})))
    :registered))

(defn- register-emit-fx!
  "Register the :drone/emit FX handler onto EMIT-LOG.

   Same story as the recorders: `:emit-fn` in resources is dead, progress is
   published as a [:drone/emit {:event-type … :data …}] effect."
  [emit-log]
  (require 'hive.events.fx)
  (let [reg-fx (resolve 'hive.events.fx/reg-fx)]
    (reg-fx :drone/emit
            (fn [{:keys [event-type data]}]
              (swap! emit-log conj {:event event-type :data data})))
    :registered))

(defn- make-test-resources
  "Build a full resources map for integration testing.

   Wires mock LLM backend and traceable recording fns.
   Returns resources map + atom logs for assertions.

   Options:
     :responses  — LLM response sequence (required)
     :tool-fn    — tool execution fn (default: success echo)
     :record?    — enable KG recording mocks (default: true)
     :emit?      — enable emit-fn tracing (default: false)"
  [{:keys [responses tool-fn record? emit?]
    :or {record? true emit? false}}]
  (let [obs-log     (atom [])
        reason-log  (atom [])
        emit-log    (atom [])
        backend     (make-sequential-backend responses)]
    {:resources
     {:backend          backend
      :tool-schemas     [{:name "read_file" :description "Read a file"}
                         {:name "propose_diff" :description "Propose a diff"}
                         {:name "grep" :description "Search file contents"}]
      :session-store    (when record? :mock-session-store)
      :agent-id         "integration-test-agent"
      :execute-tools-fn (or tool-fn
                            (fn [_aid calls _perms]
                              (mapv (fn [c]
                                      {:content (str "Result: " (:name c) " executed")})
                                    calls)))
      ;; The handlers no longer take recorder FNS from resources — they DECLARE
      ;; [:drone/record-obs ...] / [:drone/record-reason ...] effects. These
      ;; resource keys were dead, so every observation assertion ran against an
      ;; empty atom. Register the FX instead; see register-recording-fx! below.
      :_recording       (when record? (register-recording-fx! obs-log reason-log))
      :reconstruct-fn   (when record?
                          (fn [_store task turn]
                            (str task " [KG-context-turn-" turn "]")))
      :seed-kg-fn       nil
      :_emitting        (when emit? (register-emit-fx! emit-log))}
     ;; Expose logs for test assertions
     :obs-log    obs-log
     :reason-log reason-log
     :emit-log   emit-log}))

(defn- base-input
  "Build standard input data for the drone-loop FSM."
  [& [overrides]]
  (merge {:task      "Fix the authentication bug in auth.clj"
          :files     ["src/auth.clj"]
          :drone-id  "drone-integ-001"
          :max-turns 10
          :model     "integration-test-model"}
         overrides))

;; =============================================================================
;; 1. Three-Turn Scenario: tool_call → tool_call → text completion
;; =============================================================================

(deftest three-turn-tool-tool-completion
  (testing "Full 3-turn scenario: read_file → propose_diff → completion text"
    (let [{:keys [resources obs-log reason-log]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")
                        (tool-calls-response "propose_diff")
                        (text-response "I have successfully completed the fix")]})
          result (drone-loop/run-drone-loop (base-input) resources)]

      (testing "returns completed status"
        (is (= :completed (:status result))))

      (testing "executes exactly 3 turns"
        (is (= 3 (:turns result))))

      (testing "records 2 tool calls total"
        (is (= 2 (:tool_calls_made result))))

      (testing "returns completion text as result"
        (is (str/includes? (:result result) "successfully completed")))

      (testing "step history has 3 entries"
        (is (= 3 (count (:steps result)))))

      (testing "model is preserved"
        (is (= "integration-test-model" (:model result))))

      (testing "tokens accumulated across all 3 LLM calls"
        (let [tokens (:tokens result)]
          ;; 2 tool_calls (35 each) + 1 text (15) = 85
          (is (= 85 (:total tokens)))
          ;; 2*20 + 10 = 50
          (is (= 50 (:input tokens)))
          ;; 2*15 + 5 = 35
          (is (= 35 (:output tokens))))))))

;; =============================================================================
;; 2. KG Operations Verification
;; =============================================================================

(deftest kg-observations-recorded-correctly
  (testing "KG observations are recorded for each tool execution"
    (let [{:keys [resources obs-log reason-log]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")
                        (tool-calls-response "propose_diff" "grep")
                        (text-response "All changes applied")]})
          _result (drone-loop/run-drone-loop (base-input) resources)]

      (testing "observations recorded for every tool call"
        ;; Turn 0: 1 tool (read_file)
        ;; Turn 1: 2 tools (propose_diff, grep)
        ;; Total: 3 observations
        (is (= 3 (count @obs-log))))

      (testing "observations have correct tool names"
        (let [tools (mapv :tool @obs-log)]
          (is (= "read_file" (first tools)))
          (is (some #{"propose_diff"} tools))
          (is (some #{"grep"} tools))))

      (testing "observations have correct turn numbers"
        (is (= 0 (:turn (first @obs-log))))
        ;; Turn 1 tools
        (is (every? #(= 1 (:turn %))
                    (filter #(#{"propose_diff" "grep"} (:tool %)) @obs-log))))

      (testing "reasoning recorded for tool execution turns"
        ;; At least 2 reasoning entries (one per tool-call turn)
        ;; plus potentially one for the text response
        (is (>= (count @reason-log) 2)))

      (testing "reasoning actions mention tool execution"
        (is (some #(str/includes? (:action %) "Execute tools") @reason-log))))))

(deftest kg-context-reconstruction-on-continuation
  (testing "Context reconstruction is called for turns after turn 0"
    (let [reconstruct-calls (atom [])
          {:keys [resources]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")
                        (text-response "Done")]})
          resources (assoc resources
                           :reconstruct-fn
                           (fn [_store task turn]
                             (swap! reconstruct-calls conj {:task task :turn turn})
                             (str task " [reconstructed-" turn "]")))
          _result (drone-loop/run-drone-loop (base-input) resources)]

      (testing "reconstruct-fn NOT called for turn 0 (raw task used)"
        (is (empty? (filter #(= 0 (:turn %)) @reconstruct-calls))))

      (testing "reconstruct-fn called for turn 1"
        (is (= 1 (count (filter #(= 1 (:turn %)) @reconstruct-calls))))))))

;; =============================================================================
;; 3. Termination Heuristics
;; =============================================================================

(deftest termination-max-turns
  (testing "FSM terminates at max-turns and returns :max_steps status"
    (let [{:keys [resources]}
          (make-test-resources
           {:responses (repeat 20 (tool-calls-response "read_file"))})
          result (drone-loop/run-drone-loop
                  (base-input {:max-turns 3})
                  resources)]

      (is (= :max_steps (:status result)))
      (is (<= (:turns result) 3)))))

(deftest termination-completion-language
  (testing "FSM terminates on text response with completion language"
    (let [{:keys [resources]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")
                        (text-response "I have successfully implemented the fix")]})
          result (drone-loop/run-drone-loop (base-input) resources)]

      (is (= :completed (:status result)))
      (is (= 2 (:turns result))))))

(deftest termination-text-only-first-turn
  (testing "FSM terminates on turn-0 text (LLM decides task is already done)"
    (let [{:keys [resources]}
          (make-test-resources
           {:responses [(text-response "The code looks correct, no changes needed")]})
          result (drone-loop/run-drone-loop (base-input) resources)]

      (is (= :completed (:status result)))
      (is (= 1 (:turns result)))
      (is (= 0 (:tool_calls_made result))))))

(deftest termination-consecutive-failures
  (testing "FSM terminates after 3 consecutive tool failures"
    (let [failing-tool-fn (fn [_aid calls _perms]
                            (mapv (fn [_] {:content "Error: permission denied"})
                                  calls))
          {:keys [resources]}
          (make-test-resources
           {:responses (repeat 10 (tool-calls-response "read_file"))
            :tool-fn   failing-tool-fn})
          result (drone-loop/run-drone-loop
                  (base-input {:max-turns 10})
                  resources)]

      (is (some? (:status result)))
      ;; Should terminate well before max-turns due to failure threshold
      (is (<= (:turns result) 5)
          "Should terminate early due to consecutive failures"))))

(deftest termination-no-progress-detection
  (testing "FSM terminates when last 3 turns all had failures"
    (let [call-count (atom 0)
          failing-tool-fn (fn [_aid calls _perms]
                            (swap! call-count + (count calls))
                            (mapv (fn [_] {:content "Error: file not found"})
                                  calls))
          {:keys [resources]}
          (make-test-resources
           {:responses (repeat 10 (tool-calls-response "read_file"))
            :tool-fn   failing-tool-fn})
          result (drone-loop/run-drone-loop
                  (base-input {:max-turns 10})
                  resources)]

      (is (some? (:status result)))
      ;; The no-progress check kicks in after 3 consecutive all-failed turns
      (is (<= (:turns result) 6)))))

;; =============================================================================
;; 4. Token Accumulation
;; =============================================================================

(deftest tokens-accumulate-across-all-turns
  (testing "Token usage is accumulated correctly across multiple turns"
    (let [{:keys [resources]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")      ;; 35 total
                        (tool-calls-response "propose_diff")   ;; 35 total
                        (tool-calls-response "grep")           ;; 35 total
                        (text-response "Complete")]})          ;; 15 total
          result (drone-loop/run-drone-loop
                  (base-input {:max-turns 10})
                  resources)
          tokens (:tokens result)]

      ;; 3 tool_calls * 35 + 1 text * 15 = 120
      (is (= 120 (:total tokens)))
      ;; 3 * 20 + 1 * 10 = 70
      (is (= 70 (:input tokens)))
      ;; 3 * 15 + 1 * 5 = 50
      (is (= 50 (:output tokens))))))

;; =============================================================================
;; 5. Error Recovery: LLM Errors Mid-Loop
;; =============================================================================

(deftest llm-error-mid-loop
  (testing "FSM handles LLM error response mid-loop and continues"
    (let [{:keys [resources]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")
                        (error-response "Rate limit exceeded")
                        (text-response "Retried and completed")]})
          result (drone-loop/run-drone-loop (base-input) resources)]

      ;; The FSM should handle the error (routes via check-done) and may terminate
      ;; due to error being treated as text response type in check-done
      (is (some? (:status result)))
      (is (keyword? (:status result)))
      (is (string? (:result result))))))

(deftest llm-backend-exception-mid-loop
  (testing "FSM handles LLM backend throwing exception"
    (let [call-count (atom 0)
          bad-backend (reify proto/LLMBackend
                        (model-name [_] "exploding-model")
                        (chat [_ _ _]
                          (swap! call-count inc)
                          (if (= 1 @call-count)
                            ;; First call: return tool calls
                            {:type :tool_calls
                             :calls [{:id "c1" :name "read_file" :arguments {}}]
                             :usage {:input 20 :output 10 :total 30}}
                            ;; Second call: throw
                            (throw (Exception. "LLM service unavailable")))))
          resources {:backend          bad-backend
                     :tool-schemas     [{:name "read_file" :description "Read"}]
                     :agent-id         "test-agent"
                     :execute-tools-fn (fn [_ calls _]
                                         (mapv (fn [c] {:content "OK"}) calls))
                     :emit-fn          nil}
          result (drone-loop/run-drone-loop (base-input) resources)]

      ;; Should not throw — handles gracefully
      (is (some? (:status result)))
      (is (keyword? (:status result))))))

;; =============================================================================
;; 6. Single-Turn Text Completion (Fast Path)
;; =============================================================================

(deftest single-turn-fast-path
  (testing "Single-turn text response completes efficiently"
    (let [{:keys [resources obs-log]}
          (make-test-resources
           {:responses [(text-response "No changes needed, code is correct")]})
          result (drone-loop/run-drone-loop (base-input) resources)]

      (is (= :completed (:status result)))
      (is (= 1 (:turns result)))
      (is (= 0 (:tool_calls_made result)))
      (is (str/includes? (:result result) "No changes needed"))
      ;; No tool execution → no observations
      (is (zero? (count @obs-log))))))

;; =============================================================================
;; 7. Empty Tool Calls Routing
;; =============================================================================

(deftest empty-tool-calls-skip-execution
  (testing "LLM returning empty tool_calls routes to check-done, not execute"
    (let [tool-calls-count (atom 0)
          {:keys [resources]}
          (make-test-resources
           {:responses [{:type :tool_calls
                         :calls []
                         :usage {:input 10 :output 5 :total 15}}
                        (text-response "Done")]
            :tool-fn   (fn [_ calls _]
                         (swap! tool-calls-count + (count calls))
                         (mapv (fn [_] {:content "OK"}) calls))})
          result (drone-loop/run-drone-loop (base-input) resources)]

      (is (some? (:status result)))
      ;; No tool execution should have happened
      (is (= 0 @tool-calls-count)))))

;; =============================================================================
;; 8. Backend → FSM → Result Adaptation (Full Contract)
;; =============================================================================

(deftest backend-execute-full-stack
  (testing "FSMAgenticBackend.execute-drone → real run-drone-loop → result adaptation"
    (let [{:keys [resources]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")
                        (text-response "Successfully fixed the bug")]})
          ;; Wire the real run-drone-loop via with-redefs on the resolution fn
          run-fn drone-loop/run-drone-loop
          mock-run-fn (fn [data resources-arg]
                        ;; The backend passes different resource shapes —
                        ;; merge in the real resources for integration
                        (run-fn data (merge resources-arg resources)))
          backend-impl (fsm-backend/make-fsm-agentic-backend)]

      (with-redefs [fsm-backend/get-run-drone-loop (constantly mock-run-fn)]
        (let [result (backend/execute-drone
                      backend-impl
                      {:task      "Fix auth bug"
                       :model     "integration-test-model"
                       :max-steps 10
                       :drone-id  "drone-backend-integ"
                       :sandbox   {:allowed-files #{"src/auth.clj"}
                                   :allowed-dirs #{"/project"}
                                   :blocked-tools #{}}})]

          (testing "result has correct contract shape"
            (is (map? result))
            (is (contains? result :status))
            (is (contains? result :result))
            (is (contains? result :tokens))
            (is (contains? result :model))
            (is (contains? result :steps))
            (is (contains? result :metadata)))

          (testing "status is :completed or valid keyword"
            (is (keyword? (:status result))))

          (testing "model preserved through the pipeline"
            (is (= "integration-test-model" (:model result))))

          (testing "metadata includes backend type"
            (is (= :fsm-agentic (get-in result [:metadata :backend])))))))))

;; =============================================================================
;; 9. Multi-Tool Calls Per Turn
;; =============================================================================

(deftest multi-tool-single-turn
  (testing "Multiple tools in one turn are all executed and tracked"
    (let [{:keys [resources obs-log]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file" "grep" "propose_diff")
                        (text-response "All done")]})
          result (drone-loop/run-drone-loop (base-input) resources)]

      (is (= :completed (:status result)))
      (is (= 2 (:turns result)))
      (is (= 3 (:tool_calls_made result)))
      ;; 3 observations for the 3 tools
      (is (= 3 (count @obs-log))))))

;; =============================================================================
;; 10. KG Stats in Result
;; =============================================================================

(deftest kg-stats-reported
  (testing "KG stats (observations, reasoning) included in result"
    (let [{:keys [resources]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")
                        (text-response "Done")]})
          result (drone-loop/run-drone-loop (base-input) resources)]

      (is (map? (:kg-stats result)))
      (is (pos? (get-in result [:kg-stats :observations])))
      (is (pos? (get-in result [:kg-stats :reasoning]))))))

;; =============================================================================
;; 11. No Backend → Noop (Public API Guard)
;; =============================================================================

(deftest no-backend-returns-noop
  (testing "run-drone-loop with nil backend returns :noop immediately"
    (let [result (drone-loop/run-drone-loop
                  (base-input)
                  {:backend nil})]
      (is (= :noop (:status result)))
      (is (= "No LLM backend provided" (:result result)))
      (is (= 0 (:turns result))))))

;; =============================================================================
;; 12. Emit Events (Progress Tracing)
;; =============================================================================

(deftest emit-events-traced
  (testing "emit-fn receives progress events during FSM execution"
    (let [{:keys [resources emit-log]}
          (make-test-resources
           {:responses [(tool-calls-response "read_file")
                        (text-response "Done")]
            :emit?     true})
          ;; The :drone/emit FX is only declared when the INPUT sets :trace?.
          ;; Without it the FSM emits nothing and this test asserted an
          ;; always-empty log.
          _result (drone-loop/run-drone-loop (base-input {:trace? true}) resources)]

      (testing "started event emitted"
        (is (some #(= :agentic-loop-started (:event %)) @emit-log)))

      (testing "turn events emitted"
        (is (some #(= :agentic-loop-turn (:event %)) @emit-log)))

      (testing "completed event emitted"
        (is (some #(= :agentic-loop-completed (:event %)) @emit-log))))))

;; =============================================================================
;; 13. Tool Results Fed Back to LLM
;; =============================================================================

(deftest tool-results-feed-messages
  (testing "Tool execution results are available in subsequent LLM context"
    (let [messages-seen (atom [])
          response-count (atom 0)
          tracking-backend
          (reify proto/LLMBackend
            (model-name [_] "tracking-model")
            (chat [_ messages _tools]
              (swap! messages-seen conj messages)
              (let [n (swap! response-count inc)]
                (if (< n 3)
                  {:type :tool_calls
                   :calls [{:id (str "c" n) :name "read_file" :arguments {}}]
                   :usage {:input 10 :output 10 :total 20}}
                  {:type :text
                   :content "All done"
                   :usage {:input 10 :output 5 :total 15}}))))
          resources {:backend          tracking-backend
                     :tool-schemas     [{:name "read_file" :description "Read"}]
                     :agent-id         "msg-test"
                     :execute-tools-fn (fn [_ calls _]
                                         (mapv (fn [c] {:content "file-contents"}) calls))
                     :emit-fn          nil}
          _result (drone-loop/run-drone-loop (base-input) resources)]

      ;; The LLM was called at least 3 times
      (is (>= (count @messages-seen) 3)))))

;; =============================================================================
;; 14. Idempotent FSM Compilation
;; =============================================================================

(deftest fsm-compilation-idempotent
  (testing "Multiple runs reuse the same compiled FSM (delay deref)"
    (let [compiled-1 @drone-loop/compiled
          compiled-2 @drone-loop/compiled]
      (is (identical? compiled-1 compiled-2)
          "Compiled FSM should be the same instance (delay caching)"))))

;; =============================================================================
;; 15. Stress: Many Turns Before Completion
;; =============================================================================

(deftest many-turns-before-completion
  (testing "FSM handles many tool-call turns before text completion"
    (let [n-tool-turns 8
          responses (concat
                     (repeat n-tool-turns (tool-calls-response "read_file"))
                     [(text-response "Finally done after many reads")])
          {:keys [resources]}
          (make-test-resources {:responses responses})
          result (drone-loop/run-drone-loop
                  (base-input {:max-turns 15})
                  resources)]

      (is (= :completed (:status result)))
      (is (= (inc n-tool-turns) (:turns result)))
      (is (= n-tool-turns (:tool_calls_made result))))))

(comment
  ;; Run all integration tests via REPL
  (clojure.test/run-tests 'hive-mcp.agent.drone.fsm-backend-test))
