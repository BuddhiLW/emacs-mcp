(ns hive-mcp.workflows.sub-fsms.drone-loop-test
  "Tests for the parent drone-loop FSM.

   Covers:
   1. Termination logic (should-terminate?)
   2. Handler unit tests (each handler with mock resources)
   3. Dispatch predicates
   4. FSM integration — full loop with mock LLM
   5. Loop edge verification (multi-turn execution)
   6. Edge cases and error handling
   7. Public API (run-drone-loop)"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-mcp.workflows.sub-fsms.drone-loop :as drone-loop]
            [hive-mcp.workflows.sub-fsms.drone-loop-handlers :as handlers]
            [hive.events.fsm :as fsm]
            [hive-mcp.agent.protocol]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- mock-backend
  "Create a mock LLMBackend that returns responses in sequence.
   Each call pops the next response from the atom."
  [responses]
  (let [remaining (atom (vec responses))]
    (reify
      hive-mcp.agent.protocol/LLMBackend
      (model-name [_] "mock-model")
      (chat [_ _messages _tools]
        (let [resp (first @remaining)]
          (swap! remaining #(vec (rest %)))
          (or resp {:type :text :content "Done" :usage {:input 10 :output 5 :total 15}}))))))

(defn- tool-call-response
  "Build a mock tool_calls response."
  [& tool-names]
  {:type :tool_calls
   :calls (mapv (fn [name]
                  {:id (str "call-" name)
                   :name name
                   :arguments {}})
                tool-names)
   :usage {:input 20 :output 15 :total 35}})

(defn- text-response
  "Build a mock text response."
  [content]
  {:type :text
   :content content
   :usage {:input 10 :output 5 :total 15}})

(defn- error-response
  "Build a mock error response."
  [error-msg]
  {:type :error
   :error error-msg
   :usage {:input 5 :output 0 :total 5}})

(defn- make-mock-resources
  "Create mock resources for testing.
   Opts:
     :backend    - LLMBackend (required)
     :tool-fn    - tool execution fn (default: always success)
     :record?    - whether to record to session store

   Note: Fire-and-forget effects (seed, emit, record-obs, record-reason)
   are now declared as FX data by handlers and executed by registered
   FX handlers. Resources only contain value-producing fns."
  [{:keys [backend tool-fn record?]}]
  {:backend          backend
   :tool-schemas     [{:name "read_file" :description "Read a file"}
                      {:name "propose_diff" :description "Propose a diff"}]
   :session-store    (when record? :mock-store)
   :agent-id         "test-agent"
   :execute-tools-fn (or tool-fn
                         (fn [_agent-id calls _perms]
                           (mapv (fn [call]
                                   {:content (str "Success: " (:name call))})
                                 calls)))
   :reconstruct-fn   (when record?
                       (fn [_store task turn]
                         (str task " [context-turn-" turn "]")))})

(defn- base-data
  "Build minimal input data for the drone-loop FSM."
  [& [overrides]]
  (merge {:task "Fix the bug in auth.clj"
          :files ["src/auth.clj"]
          :drone-id "drone-test-1"
          :max-turns 5
          :model "mock-model"}
         overrides))

;; =============================================================================
;; 1. Termination Logic Tests
;; =============================================================================

(deftest test-should-terminate-max-turns
  (testing "terminates when max turns reached"
    (let [result (drone-loop/should-terminate?
                  {:turn 10 :max-turns 10 :steps [] :consecutive-failures 0})]
      (is (true? (:terminate? result)))
      (is (re-find #"Max turns" (:reason result))))))

(deftest test-should-terminate-text-response
  (testing "terminates on text-only response (no tool calls)"
    (let [result (drone-loop/should-terminate?
                  {:turn 2 :max-turns 10 :steps []
                   :consecutive-failures 0
                   :last-response-type :text})]
      (is (true? (:terminate? result)))
      (is (re-find #"Text-only" (:reason result))))))

(deftest test-should-terminate-consecutive-failures
  (testing "terminates after 3 consecutive failures"
    (let [result (drone-loop/should-terminate?
                  {:turn 3 :max-turns 10 :steps []
                   :consecutive-failures 3
                   :last-response-type :tool_calls})]
      (is (true? (:terminate? result)))
      (is (re-find #"consecutive failures" (:reason result))))))

(deftest test-should-terminate-completion-language
  (testing "terminates on completion language detection"
    (let [result (drone-loop/should-terminate?
                  {:turn 2 :max-turns 10 :steps []
                   :consecutive-failures 0
                   :last-response-type :tool_calls
                   :completion? true})]
      (is (true? (:terminate? result)))
      (is (re-find #"Completion language" (:reason result))))))

(deftest test-should-terminate-no-progress
  (testing "terminates when last 3 turns all failed"
    (let [result (drone-loop/should-terminate?
                  {:turn 3 :max-turns 10
                   :consecutive-failures 0
                   :last-response-type :tool_calls
                   :steps [{:response-type :error :all-failed? true}
                           {:response-type :error :all-failed? true}
                           {:response-type :error :all-failed? true}]})]
      (is (true? (:terminate? result)))
      (is (re-find #"No progress" (:reason result))))))

(deftest test-should-terminate-continue
  (testing "continues when no termination condition met"
    (let [result (drone-loop/should-terminate?
                  {:turn 2 :max-turns 10 :steps []
                   :consecutive-failures 0
                   :last-response-type :tool_calls})]
      (is (false? (:terminate? result)))
      (is (= "Continuing" (:reason result))))))

(deftest test-should-terminate-defaults
  (testing "handles nil/missing fields gracefully"
    (let [result (drone-loop/should-terminate? {})]
      (is (false? (:terminate? result))))))

;; =============================================================================
;; 2. Handler Unit Tests
;; =============================================================================

(deftest test-handle-init
  (testing "initializes all loop state fields (FX-enhanced return)"
    (let [resources {:session-store nil}
          data (base-data)
          result (handlers/handle-init resources data)
          d (:data result)]
      (is (contains? result :data) "returns FX-enhanced shape")
      (is (contains? result :fx) "returns FX-enhanced shape")
      (is (= 0 (:turn d)))
      (is (= [] (:steps d)))
      (is (= 0 (:tool-calls-made d)))
      (is (= {:input 0 :output 0 :total 0} (:total-tokens d)))
      (is (= 0 (:consecutive-failures d)))
      (is (nil? (:last-response-type d)))
      (is (= 5 (:max-turns d)))
      (is (string? (:system-prompt d))))))

(deftest test-handle-init-declares-seed-fx
  (testing "declares :drone/seed-session FX when session-store available"
    (let [resources {:session-store :mock-store}
          data (base-data)
          result (handlers/handle-init resources data)
          fx-types (mapv first (:fx result))]
      (is (some #{:drone/seed-session} fx-types))))

  (testing "declares :drone/emit FX when trace? is true"
    (let [resources {:session-store :mock-store}
          data (base-data {:trace? true})
          result (handlers/handle-init resources data)
          fx-types (mapv first (:fx result))]
      (is (some #{:drone/emit} fx-types))))

  (testing "no FX when session-store nil and no trace"
    (let [resources {:session-store nil}
          data (base-data)
          result (handlers/handle-init resources data)]
      (is (empty? (:fx result))))))

(deftest test-handle-reconstruct-context-turn-0
  (testing "uses raw task on turn 0"
    (let [resources {:reconstruct-fn (fn [_ _ _] "SHOULD NOT BE CALLED")
                     :session-store :mock}
          data (assoc (base-data) :turn 0)
          result (handlers/handle-reconstruct-context resources data)]
      (is (= "Fix the bug in auth.clj" (:context result))))))

(deftest test-handle-reconstruct-context-turn-n
  (testing "delegates to reconstruct-fn on turn > 0"
    (let [resources {:reconstruct-fn (fn [_store task turn]
                                       (str task " [compressed-turn-" turn "]"))
                     :session-store :mock}
          data (assoc (base-data) :turn 3)
          result (handlers/handle-reconstruct-context resources data)]
      (is (= "Fix the bug in auth.clj [compressed-turn-3]" (:context result))))))

(deftest test-handle-reconstruct-context-graceful-degradation
  (testing "falls back to raw task when reconstruct-fn throws"
    (let [resources {:reconstruct-fn (fn [_ _ _] (throw (Exception. "boom")))
                     :session-store :mock}
          data (assoc (base-data) :turn 2)
          result (handlers/handle-reconstruct-context resources data)]
      (is (= "Fix the bug in auth.clj" (:context result))))))

(deftest test-handle-llm-call-tool-calls
  (testing "delegates to llm-call sub-FSM and merges response"
    (let [backend (mock-backend [(tool-call-response "read_file")])
          resources {:backend backend :tool-schemas []}
          data (assoc (base-data)
                      :context "Fix the bug"
                      :system-prompt "You are a drone"
                      :turn 0
                      :total-tokens {:input 0 :output 0 :total 0})
          result (handlers/handle-llm-call resources data)]
      (is (= :tool_calls (:response-type result)))
      (is (= 1 (count (:tool-calls result))))
      (is (= "read_file" (-> result :tool-calls first :name)))
      (is (false? (:completion? result)))
      (is (> (:total (:total-tokens result)) 0)))))

(deftest test-handle-llm-call-text-response
  (testing "handles text response with completion detection"
    (let [backend (mock-backend [(text-response "I have successfully completed the task")])
          resources {:backend backend :tool-schemas []}
          data (assoc (base-data)
                      :context "Fix the bug"
                      :system-prompt "You are a drone"
                      :turn 0
                      :total-tokens {:input 0 :output 0 :total 0})
          result (handlers/handle-llm-call resources data)]
      (is (= :text (:response-type result)))
      (is (true? (:completion? result)))
      (is (= "I have successfully completed the task" (:text-content result))))))

(deftest test-handle-llm-call-no-backend
  (testing "graceful error when no backend"
    (let [resources {:backend nil :tool-schemas []}
          data (assoc (base-data)
                      :context "Fix the bug"
                      :system-prompt "You are a drone"
                      :turn 0
                      :total-tokens {:input 0 :output 0 :total 0})
          result (handlers/handle-llm-call resources data)]
      (is (= :error (:response-type result)))
      (is (some? (:error result))))))

(deftest test-handle-execute-tools
  (testing "executes tool calls and tracks metrics (FX-enhanced return)"
    (let [resources {:execute-tools-fn (fn [_aid calls _perms]
                                         (mapv (fn [c]
                                                 {:content (str "OK: " (:name c))})
                                               calls))
                     :agent-id "test-agent"}
          data (assoc (base-data)
                      :tool-calls [{:id "c1" :name "read_file" :arguments {}}
                                   {:id "c2" :name "propose_diff" :arguments {}}]
                      :turn 0
                      :tool-calls-made 0
                      :consecutive-failures 0
                      :permissions #{})
          result (handlers/handle-execute-tools resources data)
          d (:data result)]
      (is (contains? result :fx) "returns FX-enhanced shape")
      (is (= 2 (count (:tool-results d))))
      (is (false? (:all-failed? d)))
      (is (= 2 (:tool-calls-made d)))
      (is (= 0 (:consecutive-failures d))))))

(deftest test-handle-execute-tools-all-fail
  (testing "tracks consecutive failures when all tools fail (FX-enhanced)"
    (let [resources {:execute-tools-fn (fn [_aid calls _perms]
                                         (mapv (fn [_] {:content "Error: boom"})
                                               calls))
                     :agent-id "test-agent"}
          data (assoc (base-data)
                      :tool-calls [{:id "c1" :name "read_file" :arguments {}}]
                      :turn 0
                      :tool-calls-made 0
                      :consecutive-failures 1
                      :permissions #{})
          result (handlers/handle-execute-tools resources data)
          d (:data result)]
      (is (true? (:all-failed? d)))
      (is (= 2 (:consecutive-failures d))))))

(deftest test-handle-compress
  (testing "declares record FX and increments turn (FX-enhanced)"
    (let [resources {:session-store :mock}
          data {:turn 1
                :files ["src/auth.clj"]
                :response-type :tool_calls
                :tool-results [[{:name "read_file"} {:content "file contents"}]
                               [{:name "propose_diff"} {:content "diff applied"}]]
                :all-failed? false
                :obs-count 0
                :reason-count 0
                :steps []}
          result (handlers/handle-compress resources data)
          d (:data result)
          fx (:fx result)
          fx-types (mapv first fx)]
      ;; Data assertions
      (is (= 2 (:obs-count d)))
      (is (= 1 (:reason-count d)))
      (is (= 2 (:turn d)))
      (is (= 1 (count (:steps d))))
      ;; FX assertions: 2 record-obs + 1 record-reason
      (is (= 3 (count fx)))
      (is (= 2 (count (filter #{:drone/record-obs} fx-types))))
      (is (= 1 (count (filter #{:drone/record-reason} fx-types))))))

  (testing "no FX when session-store nil"
    (let [resources {:session-store nil}
          data {:turn 0 :files [] :response-type :tool_calls
                :tool-results [[{:name "read_file"} {:content "ok"}]]
                :all-failed? false :obs-count 0 :reason-count 0 :steps []}
          result (handlers/handle-compress resources data)]
      (is (empty? (:fx result))))))

(deftest test-handle-check-done-continue
  (testing "marks continue when no termination condition met (FX-enhanced)"
    (let [resources {:session-store nil}
          data {:turn 1 :max-turns 10
                :steps [{:response-type :tool_calls :all-failed? false}]
                :consecutive-failures 0
                :last-response-type :tool_calls
                :completion? false
                :response-type :tool_calls}
          result (handlers/handle-check-done resources data)
          d (:data result)]
      (is (contains? result :fx) "returns FX-enhanced shape")
      (is (false? (get-in d [:termination :terminate?]))))))

(deftest test-handle-check-done-text-advances-turn
  (testing "text responses advance turn counter (FX-enhanced)"
    (let [resources {:session-store :mock}
          data {:turn 1 :max-turns 10
                :steps []
                :consecutive-failures 0
                :last-response-type nil
                :response-type :text
                :text-content "All done"
                :completion? false}
          result (handlers/handle-check-done resources data)
          d (:data result)
          fx-types (mapv first (:fx result))]
      (is (= 2 (:turn d)))
      (is (= :text (:last-response-type d)))
      (is (= 1 (count (:steps d))))
      ;; FX: record-reason for text response
      (is (some #{:drone/record-reason} fx-types)))))

(deftest test-handle-finalize
  (testing "builds result in correct shape (FX-enhanced)"
    (let [data {:turn 3 :max-turns 10
                :last-response-type :text
                :last-text "Task completed"
                :steps [{:turn 0} {:turn 1} {:turn 2}]
                :tool-calls-made 5
                :total-tokens {:input 100 :output 50 :total 150}
                :model "mock-model"
                :obs-count 4
                :reason-count 3
                :drone-id "drone-1"
                :termination {:terminate? true :reason "Text-only"}}
          result (handlers/handle-finalize {} data)
          d (:data result)
          fsm-result (:fsm-result d)]
      (is (contains? result :fx) "returns FX-enhanced shape")
      (is (= :completed (:status fsm-result)))
      (is (= "Task completed" (:result fsm-result)))
      (is (= 3 (count (:steps fsm-result))))
      (is (= 5 (:tool_calls_made fsm-result)))
      (is (= 150 (:total (:tokens fsm-result))))
      (is (= 3 (:turns fsm-result)))
      (is (= "mock-model" (:model fsm-result)))
      (is (= 4 (get-in fsm-result [:kg-stats :observations])))))

  (testing "declares :drone/emit FX when trace?"
    (let [data {:turn 3 :max-turns 10 :last-response-type :text
                :last-text "Done" :steps [] :tool-calls-made 0
                :total-tokens {:input 0 :output 0 :total 0}
                :model "m" :obs-count 0 :reason-count 0
                :drone-id "d1" :trace? true
                :termination {:terminate? true :reason "text"}}
          result (handlers/handle-finalize {} data)]
      (is (some #{:drone/emit} (mapv first (:fx result)))))))

(deftest test-handle-finalize-max-steps
  (testing "status is :max_steps when turn >= max-turns (FX-enhanced)"
    (let [data {:turn 10 :max-turns 10
                :last-response-type :tool_calls
                :last-text "still working"
                :steps (vec (repeat 10 {:turn 0}))
                :tool-calls-made 20
                :total-tokens {:input 200 :output 100 :total 300}
                :model "mock-model"
                :obs-count 8 :reason-count 5
                :drone-id "drone-1"
                :termination {:terminate? true :reason "Max turns"}}
          result (handlers/handle-finalize {} data)
          d (:data result)]
      (is (= :max_steps (get-in d [:fsm-result :status]))))))

;; =============================================================================
;; 3. Dispatch Predicate Tests
;; =============================================================================

(deftest test-dispatch-predicates
  (testing "initialized?"
    (is (true? (drone-loop/initialized? {:turn 0})))
    (is (false? (drone-loop/initialized? {}))))

  (testing "has-context?"
    (is (true? (drone-loop/has-context? {:context "hello"})))
    (is (false? (drone-loop/has-context? {}))))

  (testing "llm-responded?"
    (is (true? (drone-loop/llm-responded? {:response-type :text})))
    (is (false? (drone-loop/llm-responded? {}))))

  (testing "has-tool-calls?"
    (is (true? (drone-loop/has-tool-calls?
                {:response-type :tool_calls
                 :tool-calls [{:name "read_file"}]})))
    (is (false? (drone-loop/has-tool-calls?
                 {:response-type :text})))
    (is (false? (drone-loop/has-tool-calls?
                 {:response-type :tool_calls :tool-calls []}))))

  (testing "no-tool-calls?"
    (is (true? (drone-loop/no-tool-calls? {:response-type :text})))
    (is (true? (drone-loop/no-tool-calls? {:response-type :error})))
    (is (false? (drone-loop/no-tool-calls?
                 {:response-type :tool_calls
                  :tool-calls [{:name "read_file"}]}))))

  (testing "tools-executed?"
    (is (true? (drone-loop/tools-executed? {:tool-results [[:c1 :r1]]})))
    (is (false? (drone-loop/tools-executed? {}))))

  (testing "done? and continue?"
    (is (true? (drone-loop/done? {:termination {:terminate? true}})))
    (is (false? (drone-loop/done? {:termination {:terminate? false}})))
    (is (true? (drone-loop/continue? {:termination {:terminate? false}})))
    (is (false? (drone-loop/continue? {:termination {:terminate? true}}))))

  (testing "finalized?"
    (is (true? (drone-loop/finalized? {:fsm-result {:status :completed}})))
    (is (false? (drone-loop/finalized? {})))))

;; =============================================================================
;; 4. FSM Spec Compilation
;; =============================================================================

(deftest test-fsm-compiles
  (testing "drone-loop FSM spec compiles without errors"
    (is (some? @drone-loop/compiled))
    (is (not (nil? @drone-loop/compiled)))))

(deftest test-fsm-spec-structure
  (testing "FSM spec has all expected states"
    (let [states (set (keys (:fsm drone-loop/drone-loop-spec)))]
      (is (contains? states :hive.events.fsm/start))
      (is (contains? states :hive-mcp.workflows.sub-fsms.drone-loop/reconstruct-context))
      (is (contains? states :hive-mcp.workflows.sub-fsms.drone-loop/llm-call))
      (is (contains? states :hive-mcp.workflows.sub-fsms.drone-loop/dispatch-response))
      (is (contains? states :hive-mcp.workflows.sub-fsms.drone-loop/execute-tools))
      (is (contains? states :hive-mcp.workflows.sub-fsms.drone-loop/compress))
      (is (contains? states :hive-mcp.workflows.sub-fsms.drone-loop/check-done))
      (is (contains? states :hive-mcp.workflows.sub-fsms.drone-loop/finalize))
      (is (contains? states :hive.events.fsm/end))
      (is (contains? states :hive.events.fsm/error)))))

;; =============================================================================
;; 5. FSM Integration — Full Loop
;; =============================================================================

(deftest test-fsm-single-turn-text
  (testing "FSM completes in 1 turn when LLM returns text"
    (let [backend (mock-backend [(text-response "Task is complete")])
          resources (make-mock-resources {:backend backend})
          data (base-data)
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      (is (some? (:fsm-result result-data)))
      (is (= :completed (get-in result-data [:fsm-result :status])))
      (is (= "Task is complete" (get-in result-data [:fsm-result :result])))
      (is (= 1 (get-in result-data [:fsm-result :turns]))))))

(deftest test-fsm-tool-then-text
  (testing "FSM loops: tool calls → text completion"
    (let [backend (mock-backend [(tool-call-response "read_file")
                                 (text-response "All done, I've fixed the bug")])
          resources (make-mock-resources {:backend backend})
          data (base-data)
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      (is (= :completed (get-in result-data [:fsm-result :status])))
      (is (= 2 (get-in result-data [:fsm-result :turns])))
      (is (= 1 (get-in result-data [:fsm-result :tool_calls_made]))))))

(deftest test-fsm-multi-turn-loop
  (testing "FSM loops through multiple tool call turns"
    (let [backend (mock-backend [(tool-call-response "read_file")
                                 (tool-call-response "propose_diff")
                                 (text-response "Successfully implemented the fix")])
          resources (make-mock-resources {:backend backend})
          data (base-data)
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      (is (= :completed (get-in result-data [:fsm-result :status])))
      (is (= 3 (get-in result-data [:fsm-result :turns])))
      (is (= 2 (get-in result-data [:fsm-result :tool_calls_made]))))))

(deftest test-fsm-max-turns-termination
  (testing "FSM terminates at max turns"
    (let [;; Always returns tool calls — would loop forever without max-turns
          backend (mock-backend (repeat 10 (tool-call-response "read_file")))
          resources (make-mock-resources {:backend backend})
          data (base-data {:max-turns 3})
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      (is (= :max_steps (get-in result-data [:fsm-result :status])))
      (is (<= (get-in result-data [:fsm-result :turns]) 3)))))

(deftest test-fsm-error-response
  (testing "FSM handles LLM error response"
    (let [backend (mock-backend [(error-response "Rate limited")])
          resources (make-mock-resources {:backend backend})
          data (base-data)
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      ;; Error response has :text last-response-type after check-done processes it
      (is (some? (get-in result-data [:fsm-result :status]))))))

(deftest test-fsm-consecutive-failures-termination
  (testing "FSM terminates after 3 consecutive failures"
    (let [backend (mock-backend (repeat 5 (tool-call-response "read_file")))
          ;; All tool executions fail
          failing-tool-fn (fn [_aid calls _perms]
                            (mapv (fn [_] {:content "Error: permission denied"})
                                  calls))
          resources (make-mock-resources {:backend backend :tool-fn failing-tool-fn})
          data (base-data {:max-turns 10})
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      (is (some? (get-in result-data [:fsm-result :status])))
      (is (<= (get-in result-data [:fsm-result :turns]) 5)))))

;; =============================================================================
;; 6. Token Tracking
;; =============================================================================

(deftest test-fsm-token-accumulation
  (testing "tokens accumulate across turns"
    (let [backend (mock-backend [(tool-call-response "read_file")
                                 (text-response "Done")])
          resources (make-mock-resources {:backend backend})
          data (base-data)
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          tokens (get-in (:data fsm-result) [:fsm-result :tokens])]
      (is (pos? (:input tokens)))
      (is (pos? (:output tokens)))
      (is (pos? (:total tokens)))
      ;; Two LLM calls: tool_calls (35 total) + text (15 total) = 50
      (is (= 50 (:total tokens))))))

;; =============================================================================
;; 7. Session Store Recording
;; =============================================================================

(deftest test-fsm-records-observations
  (testing "FSM tracks observation count via FX declarations"
    (let [backend (mock-backend [(tool-call-response "read_file")
                                 (text-response "All done")])
          resources (make-mock-resources {:backend backend :record? true})
          data (base-data)
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      (is (= :completed (get-in result-data [:fsm-result :status])))
      ;; obs-count is tracked in data (1 tool = 1 observation)
      (is (= 1 (get-in result-data [:fsm-result :kg-stats :observations])))
      ;; reasoning count: 1 from compress (tool execution) + 1 from check-done (text)
      (is (pos? (get-in result-data [:fsm-result :kg-stats :reasoning]))))))

;; =============================================================================
;; 8. Public API Tests
;; =============================================================================

(deftest test-run-drone-loop-no-backend
  (testing "returns :noop when no backend provided"
    (let [result (drone-loop/run-drone-loop (base-data) {:backend nil})]
      (is (= :noop (:status result)))
      (is (= "No LLM backend provided" (:result result))))))

(deftest test-run-drone-loop-success
  (testing "run-drone-loop returns clean result"
    (let [backend (mock-backend [(tool-call-response "read_file")
                                 (text-response "Task complete")])
          resources (make-mock-resources {:backend backend})
          result (drone-loop/run-drone-loop (base-data) resources)]
      (is (= :completed (:status result)))
      (is (string? (:result result)))
      (is (= 2 (:turns result)))
      (is (= 1 (:tool_calls_made result)))
      (is (map? (:tokens result)))
      (is (= "mock-model" (:model result))))))

(deftest test-run-drone-loop-handles-exception
  (testing "run-drone-loop handles LLM backend exceptions without crashing"
    (let [;; Backend that throws on chat — caught by llm-call sub-FSM
          bad-backend (reify hive-mcp.agent.protocol/LLMBackend
                        (model-name [_] "bad-model")
                        (chat [_ _ _] (throw (Exception. "LLM exploded"))))
          resources {:backend bad-backend :tool-schemas []}
          result (drone-loop/run-drone-loop (base-data) resources)]
      ;; Should not throw — returns some result (error or completed)
      (is (some? (:status result)))
      (is (keyword? (:status result)))
      (is (string? (:result result))))))

;; =============================================================================
;; 9. Context Reconstruction Across Turns
;; =============================================================================

(deftest test-context-reconstruction-per-turn
  (testing "context is reconstructed from KG on turn > 0"
    (let [reconstruct-calls (atom [])
          backend (mock-backend [(tool-call-response "read_file")
                                 (text-response "Done")])
          resources (assoc (make-mock-resources {:backend backend :record? true})
                           :reconstruct-fn
                           (fn [_store task turn]
                             (swap! reconstruct-calls conj turn)
                             (str task " [turn-" turn "]")))
          data (base-data)
          _result (fsm/run @drone-loop/compiled resources {:data data})]
      ;; Turn 0 should NOT call reconstruct-fn (uses raw task)
      ;; Turn 1+ should call reconstruct-fn
      (is (not (contains? (set @reconstruct-calls) 0)))
      (is (contains? (set @reconstruct-calls) 1)))))

;; =============================================================================
;; 10. Edge Cases
;; =============================================================================

(deftest test-empty-tool-calls
  (testing "empty tool-calls list routes to check-done, not execute-tools"
    (let [backend (mock-backend [{:type :tool_calls :calls []
                                  :usage {:input 5 :output 5 :total 10}}
                                 (text-response "Done")])
          resources (make-mock-resources {:backend backend})
          data (base-data)
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      (is (some? (get-in result-data [:fsm-result :status]))))))

(deftest test-multiple-tool-calls-per-turn
  (testing "multiple tools in one turn are all executed"
    (let [backend (mock-backend [(tool-call-response "read_file" "propose_diff")
                                 (text-response "Done")])
          resources (make-mock-resources {:backend backend})
          data (base-data)
          fsm-result (fsm/run @drone-loop/compiled resources {:data data})
          result-data (:data fsm-result)]
      (is (= :completed (get-in result-data [:fsm-result :status])))
      (is (= 2 (get-in result-data [:fsm-result :tool_calls_made]))))))
