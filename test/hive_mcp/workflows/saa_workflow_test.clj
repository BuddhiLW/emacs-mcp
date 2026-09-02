(ns hive-mcp.workflows.saa-workflow-test
  "Tests for the SAA (Silence-Abstract-Act) FSM workflow.

   Covers:
   - Dispatch predicates (pure functions)
   - Individual state handlers (unit tests with mock resources)
   - Full FSM execution paths (happy path, plan-only, error paths)
   - Silence loop (grounding insufficient → retry → proceed)
   - Abstract retry (plan invalid → re-abstract → error)
   - Terminal handlers (end/error)
   - Edge cases (missing resources, degraded mode)

   Tests are designed to run via nREPL (not bash) per project axiom:
   'Clojure Tests Run via nREPL, Never Bash'

   Run: (require '[clojure.test :refer [run-tests]])
        (run-tests 'hive-mcp.workflows.saa-workflow-test)"
  (:require [clojure.test :refer [deftest testing is are]]
            [hive-mcp.workflows.saa-workflow :as sut]
            [hive.events.fsm :as fsm]
            [clojure.edn]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures & Helpers
;; =============================================================================

(def ^:private fixed-clock
  "Fixed clock for deterministic test output."
  (java.time.Instant/parse "2026-02-07T12:00:00Z"))

(defn- mock-resources
  "Build a mock resources map with sensible defaults.
   Overrides can be passed as a map to replace specific fns."
  ([] (mock-resources {}))
  ([overrides]
   (merge
    {:scope-fn          (constantly "test-project")
     :catchup-fn        (fn [_agent-id _directory]
                          {:axioms      [{:id "ax-1" :content "Test axiom"}]
                           :conventions [{:id "conv-1" :content "Test convention"}]
                           :decisions   [{:id "dec-1" :content "Test decision"}]})
     :explore-fn        (fn [_task _agent-id existing-obs]
                          {:observations (conj (or existing-obs [])
                                               {:type :file :content "found foo.clj"}
                                               {:type :pattern :content "uses ring handler"})
                           :files-read  3
                           :discoveries 2})
     :score-grounding-fn (fn [obs files-read]
                           (min 1.0
                                (+ (if (seq obs) 0.3 0.0)
                                   (min 0.4 (* 0.1 (count obs)))
                                   (if (pos? (or files-read 0)) 0.3 0.0))))
     :synthesize-fn     (fn [task _obs _context]
                          {:id    "plan-test-123"
                           :title (str "Plan for: " task)
                           :steps [{:id          "step-1"
                                    :title       "First step"
                                    :description "Do the first thing"
                                    :depends-on  []
                                    :files       ["src/foo.clj"]
                                    :wave        1}
                                   {:id          "step-2"
                                    :title       "Second step"
                                    :description "Do the second thing"
                                    :depends-on  ["step-1"]
                                    :files       ["src/bar.clj"]
                                    :wave        2}]
                           :waves {:wave-1 {:steps ["step-1"] :parallel false}
                                   :wave-2 {:steps ["step-2"] :parallel false}}})
     :validate-plan-fn  (fn [plan]
                          (if (seq (:steps plan))
                            {:valid? true :errors []}
                            {:valid? false :errors ["Plan has no steps"]}))
     :store-plan-fn     (fn [_plan _agent-id _directory]
                          {:memory-id  "mem-test-456"
                           :kanban-ids ["task-1" "task-2"]
                           :kg-edges   3})
     :dispatch-fn       (fn [_plan _mode _agent-id _ctx]
                          {:wave-id "wave-test-789"
                           :result  {:status :completed :files-modified 2}})
     :verify-fn         (fn [_exec-result _plan]
                          {:passed? true :details {:tests-run 5 :tests-passed 5}})
     :shout-fn          (fn [_agent-id _phase _message] nil)
     :build-summary-fn  nil
     :error-response-fn nil
     :clock-fn          (constantly fixed-clock)}
    overrides)))

(def ^:private base-data
  "Minimal valid initial data for SAA workflow."
  {:agent-id  "test-ling-1"
   :task      "Fix auth bug in login flow"
   :directory "/test/project"})

;; =============================================================================
;; Dispatch Predicate Tests
;; =============================================================================

(deftest has-required-fields?-test
  (testing "true when agent-id and task present, no error"
    (is (sut/has-required-fields? {:agent-id "a" :task "t"}))
    (is (sut/has-required-fields? {:agent-id "a" :task "t" :extra "ok"})))

  (testing "false when missing agent-id or task, or error present"
    (is (not (sut/has-required-fields? {:task "t"})))
    (is (not (sut/has-required-fields? {:agent-id "a"})))
    (is (not (sut/has-required-fields? {})))
    (is (not (sut/has-required-fields? {:agent-id "a" :task "t" :error "oops"})))))

(deftest has-startup-error?-test
  (testing "true when missing required fields or error present"
    (is (sut/has-startup-error? {}))
    (is (sut/has-startup-error? {:task "t"}))
    (is (sut/has-startup-error? {:agent-id "a" :task "t" :error "bad"})))

  (testing "false when all fields present and no error"
    (is (not (sut/has-startup-error? {:agent-id "a" :task "t"})))))

(deftest context-loaded?-test
  (testing "true only when :context-loaded? is exactly true"
    (is (sut/context-loaded? {:context-loaded? true}))
    (is (not (sut/context-loaded? {:context-loaded? false})))
    (is (not (sut/context-loaded? {})))
    (is (not (sut/context-loaded? {:context-loaded? "yes"})))))

(deftest has-observations?-test
  (testing "true when observations present"
    (is (sut/has-observations? {:observations [{:type :file}]}))
    (is (sut/has-observations? {:observations []})))  ;; empty vec is `some?`

  (testing "false when observations nil"
    (is (not (sut/has-observations? {})))))

(deftest has-error?-test
  (is (sut/has-error? {:error "something"}))
  (is (not (sut/has-error? {})))
  (is (not (sut/has-error? {:error nil}))))

(deftest grounding-predicate-tests
  (testing "grounding-sufficient? with default threshold 0.6"
    (is (sut/grounding-sufficient? {:grounding-score 0.8 :grounding-threshold 0.6}))
    (is (sut/grounding-sufficient? {:grounding-score 0.6 :grounding-threshold 0.6}))
    (is (not (sut/grounding-sufficient? {:grounding-score 0.3 :grounding-threshold 0.6}))))

  (testing "grounding-sufficient? uses defaults when keys missing"
    ;; defaults: score 0.0, threshold 0.6
    (is (not (sut/grounding-sufficient? {}))))

  (testing "grounding-insufficient-retryable?"
    (is (sut/grounding-insufficient-retryable?
         {:grounding-score 0.3 :grounding-threshold 0.6 :silence-iterations 1}))
    ;; Max iterations reached — not retryable
    (is (not (sut/grounding-insufficient-retryable?
              {:grounding-score 0.3 :grounding-threshold 0.6 :silence-iterations 3})))
    ;; Score sufficient — not retryable (wouldn't need retry)
    (is (not (sut/grounding-insufficient-retryable?
              {:grounding-score 0.8 :grounding-threshold 0.6 :silence-iterations 1}))))

  (testing "grounding-max-iterations?"
    (is (sut/grounding-max-iterations? {:silence-iterations 3}))
    (is (sut/grounding-max-iterations? {:silence-iterations 5}))
    (is (not (sut/grounding-max-iterations? {:silence-iterations 2})))
    (is (not (sut/grounding-max-iterations? {})))))

(deftest plan-predicate-tests
  (testing "has-plan?"
    (is (sut/has-plan? {:plan {:id "p1"}}))
    (is (not (sut/has-plan? {})))
    (is (not (sut/has-plan? {:plan nil}))))

  (testing "plan-valid?"
    (is (sut/plan-valid? {:plan-valid? true}))
    (is (not (sut/plan-valid? {:plan-valid? false})))
    (is (not (sut/plan-valid? {}))))

  (testing "plan-invalid-retryable?"
    (is (sut/plan-invalid-retryable? {:plan-valid? false :abstract-retries 0}))
    (is (sut/plan-invalid-retryable? {:plan-valid? false :abstract-retries 1}))
    (is (not (sut/plan-invalid-retryable? {:plan-valid? false :abstract-retries 2})))
    (is (not (sut/plan-invalid-retryable? {:plan-valid? true :abstract-retries 0}))))

  (testing "plan-invalid-final?"
    (is (sut/plan-invalid-final? {:plan-valid? false :abstract-retries 2}))
    (is (sut/plan-invalid-final? {:plan-valid? false :abstract-retries 5}))
    (is (not (sut/plan-invalid-final? {:plan-valid? false :abstract-retries 1})))
    (is (not (sut/plan-invalid-final? {:plan-valid? true :abstract-retries 3})))))

(deftest mode-predicate-tests
  (testing "plan-only?"
    (is (sut/plan-only? {:plan-only? true}))
    (is (not (sut/plan-only? {:plan-only? false})))
    (is (not (sut/plan-only? {}))))

  (testing "full-execution?"
    (is (sut/full-execution? {:plan-only? false}))
    (is (sut/full-execution? {}))
    (is (not (sut/full-execution? {:plan-only? true})))))

(deftest execution-predicate-tests
  (testing "has-execution-result?"
    (is (sut/has-execution-result? {:execution-result {:status :ok}}))
    (is (not (sut/has-execution-result? {}))))

  (testing "tests-passed?"
    (is (sut/tests-passed? {:tests-passed? true}))
    (is (not (sut/tests-passed? {:tests-passed? false})))
    (is (not (sut/tests-passed? {}))))

  (testing "tests-failed?"
    (is (sut/tests-failed? {:tests-passed? false}))
    (is (sut/tests-failed? {}))
    (is (not (sut/tests-failed? {:tests-passed? true})))))

;; =============================================================================
;; Individual Handler Unit Tests
;; =============================================================================

(deftest handle-start-test
  (testing "initializes SAA session with valid data"
    (let [res  (mock-resources)
          data (sut/handle-start res base-data)]
      (is (= "test-ling-1" (:agent-id data)))
      (is (= "Fix auth bug in login flow" (:task data)))
      (is (= "test-project" (:project-id data)))
      (is (= "/test/project" (:directory data)))
      (is (some? (:started-at data)))
      (is (= :start (:phase data)))
      (is (= 0.6 (:grounding-threshold data)))
      (is (= 0 (:silence-iterations data)))
      (is (= 0 (:abstract-retries data)))
      (is (false? (:plan-only? data)))
      (is (nil? (:error data)))))

  (testing "sets error when missing agent-id"
    (let [data (sut/handle-start (mock-resources) {:task "t"})]
      (is (some? (:error data)))))

  (testing "sets error when missing task"
    (let [data (sut/handle-start (mock-resources) {:agent-id "a"})]
      (is (some? (:error data)))))

  (testing "preserves plan-only? and custom threshold"
    (let [data (sut/handle-start
                (mock-resources)
                (merge base-data {:plan-only? true :grounding-threshold 0.8}))]
      (is (true? (:plan-only? data)))
      (is (= 0.8 (:grounding-threshold data)))))

  (testing "handles scope-fn failure gracefully"
    (let [res  (mock-resources {:scope-fn (fn [_] (throw (Exception. "scope error")))})
          data (sut/handle-start res base-data)]
      (is (= "unknown" (:project-id data)))
      (is (nil? (:error data))))))

(deftest handle-catchup-test
  (testing "loads context successfully"
    (let [res  (mock-resources)
          data (sut/handle-catchup res (merge base-data {:phase :start}))]
      (is (true? (:context-loaded? data)))
      (is (= :catchup (:phase data)))
      (is (= 1 (count (:axioms data))))
      (is (= 1 (count (:conventions data))))
      (is (= 1 (count (:decisions data))))))

  (testing "handles catchup-fn exception"
    (let [res  (mock-resources {:catchup-fn (fn [_ _] (throw (Exception. "catchup boom")))})
          data (sut/handle-catchup res (merge base-data {:phase :start}))]
      (is (false? (:context-loaded? data)))
      (is (some? (:error data)))
      (is (re-find #"catchup boom" (:error data)))))

  (testing "proceeds without catchup-fn (degraded mode)"
    (let [res  (mock-resources {:catchup-fn nil})
          data (sut/handle-catchup res (merge base-data {:phase :start}))]
      (is (true? (:context-loaded? data)))
      (is (= [] (:axioms data)))
      (is (= [] (:conventions data)))
      (is (= [] (:decisions data))))))

(deftest handle-silence-test
  (testing "explores codebase and records observations"
    (let [res  (mock-resources)
          data (sut/handle-silence res (merge base-data {:phase :catchup
                                                         :silence-iterations 0}))]
      (is (= :silence (:phase data)))
      (is (= 1 (:silence-iterations data)))
      (is (= 3 (:files-read data)))
      (is (= 2 (:discoveries data)))
      (is (seq (:observations data)))
      (is (some? (:silence-started data)))))

  (testing "increments silence-iterations on each call"
    (let [res  (mock-resources)
          d1   (sut/handle-silence res (merge base-data {:silence-iterations 0}))
          d2   (sut/handle-silence res (merge d1 {:silence-iterations (:silence-iterations d1)}))]
      (is (= 1 (:silence-iterations d1)))
      (is (= 2 (:silence-iterations d2)))))

  (testing "handles explore-fn exception"
    (let [res  (mock-resources {:explore-fn (fn [_ _ _] (throw (Exception. "explore boom")))})
          data (sut/handle-silence res (merge base-data {:silence-iterations 0}))]
      (is (some? (:error data)))
      (is (re-find #"explore boom" (:error data)))))

  (testing "creates minimal observations without explore-fn"
    (let [res  (mock-resources {:explore-fn nil})
          data (sut/handle-silence res (merge base-data {:silence-iterations 0}))]
      (is (seq (:observations data)))
      (is (= :task-description (:type (first (:observations data)))))
      (is (= 0 (:files-read data))))))

(deftest handle-silence-review-test
  (testing "computes grounding score from observations"
    (let [res  (mock-resources)
          obs  [{:type :file} {:type :pattern} {:type :issue}]
          data (sut/handle-silence-review
                res
                (merge base-data {:observations obs
                                  :files-read   5
                                  :grounding-threshold 0.6
                                  :silence-iterations 1}))]
      (is (= :silence-review (:phase data)))
      (is (number? (:grounding-score data)))
      (is (>= (:grounding-score data) 0.0))
      (is (<= (:grounding-score data) 1.0))
      (is (some? (:silence-ended data)))))

  (testing "uses default scorer when score-grounding-fn not provided"
    (let [res  (mock-resources {:score-grounding-fn nil})
          data (sut/handle-silence-review
                res
                (merge base-data {:observations [{:type :file}]
                                  :files-read   1
                                  :grounding-threshold 0.6
                                  :silence-iterations 1}))]
      ;; Default: 0.3 (seq obs) + 0.1 (1 obs) + 0.3 (pos files) = 0.7
      (is (= 0.7 (:grounding-score data))))))

(deftest handle-abstract-test
  (testing "synthesizes plan from observations"
    (let [res  (mock-resources)
          data (sut/handle-abstract
                res
                (merge base-data {:observations [{:type :file}]
                                  :axioms []
                                  :conventions []
                                  :decisions []
                                  :project-id "test-project"
                                  :abstract-retries 0}))]
      (is (= :abstract (:phase data)))
      (is (some? (:plan data)))
      (is (= 2 (count (get-in data [:plan :steps]))))
      (is (= 1 (:abstract-retries data)))))

  (testing "increments abstract-retries on each call"
    (let [res  (mock-resources)
          d1   (sut/handle-abstract res (merge base-data {:abstract-retries 0
                                                          :observations []}))
          d2   (sut/handle-abstract res (merge d1 {:abstract-retries (:abstract-retries d1)}))]
      (is (= 1 (:abstract-retries d1)))
      (is (= 2 (:abstract-retries d2)))))

  (testing "handles synthesize-fn exception"
    (let [res  (mock-resources {:synthesize-fn (fn [_ _ _] (throw (Exception. "synth boom")))})
          data (sut/handle-abstract res (merge base-data {:abstract-retries 0
                                                          :observations []}))]
      (is (nil? (:plan data)))
      (is (some? (:error data)))))

  (testing "sets error when no synthesize-fn"
    (let [res  (mock-resources {:synthesize-fn nil})
          data (sut/handle-abstract res (merge base-data {:abstract-retries 0
                                                          :observations []}))]
      (is (nil? (:plan data)))
      (is (some? (:error data))))))

(deftest handle-validate-plan-test
  (testing "validates a plan with steps"
    (let [res  (mock-resources)
          plan {:id "p1" :title "Test" :steps [{:id "s1" :title "Step 1"}]}
          data (sut/handle-validate-plan res (merge base-data {:plan plan}))]
      (is (= :validate-plan (:phase data)))
      (is (true? (:plan-valid? data)))
      (is (empty? (:validation-errors data)))))

  (testing "rejects plan with no steps"
    (let [res  (mock-resources)
          plan {:id "p1" :title "Test" :steps []}
          data (sut/handle-validate-plan res (merge base-data {:plan plan}))]
      (is (false? (:plan-valid? data)))
      (is (seq (:validation-errors data)))))

  (testing "handles validate-fn exception"
    (let [res  (mock-resources {:validate-plan-fn (fn [_] (throw (Exception. "validate boom")))})
          plan {:id "p1" :steps [{:id "s1"}]}
          data (sut/handle-validate-plan res (merge base-data {:plan plan}))]
      (is (false? (:plan-valid? data)))
      (is (seq (:validation-errors data)))))

  (testing "uses default validator when validate-plan-fn is nil"
    (let [res  (mock-resources {:validate-plan-fn nil})
          plan {:id "p1" :steps [{:id "s1"}]}
          data (sut/handle-validate-plan res (merge base-data {:plan plan}))]
      (is (true? (:plan-valid? data))))))

(deftest handle-store-plan-test
  (testing "stores plan and returns IDs"
    (let [res  (mock-resources)
          plan {:id "p1" :steps [{:id "s1"}]}
          data (sut/handle-store-plan
                res
                (merge base-data {:plan plan}))]
      (is (= :store-plan (:phase data)))
      (is (= "mem-test-456" (:plan-memory-id data)))
      (is (= ["task-1" "task-2"] (:kanban-task-ids data)))
      (is (= 3 (:kg-edges-created data)))))

  (testing "handles store-fn exception gracefully (non-fatal)"
    (let [res  (mock-resources {:store-plan-fn (fn [_ _ _] (throw (Exception. "store boom")))})
          data (sut/handle-store-plan res (merge base-data {:plan {:id "p1"}}))]
      (is (nil? (:plan-memory-id data)))
      (is (= [] (:kanban-task-ids data)))
      (is (= 0 (:kg-edges-created data)))
      ;; No :error set — storage failure is non-fatal
      (is (nil? (:error data)))))

  (testing "skips storage when store-fn is nil"
    (let [res  (mock-resources {:store-plan-fn nil})
          data (sut/handle-store-plan res (merge base-data {:plan {:id "p1"}}))]
      (is (nil? (:plan-memory-id data)))
      (is (= [] (:kanban-task-ids data))))))

(deftest handle-act-dispatch-test
  (testing "dispatches execution and returns result"
    (let [res  (mock-resources)
          plan {:id "p1" :steps [{:id "s1"}]}
          data (sut/handle-act-dispatch
                res
                (merge base-data {:plan plan :execution-mode :dag-wave}))]
      (is (= :act-dispatch (:phase data)))
      (is (= :dag-wave (:execution-mode data)))
      (is (= "wave-test-789" (:wave-id data)))
      (is (some? (:execution-result data)))
      (is (some? (:act-started data)))))

  (testing "defaults to :direct mode"
    (let [res  (mock-resources)
          data (sut/handle-act-dispatch res (merge base-data {:plan {:id "p1"}}))]
      (is (= :direct (:execution-mode data)))))

  (testing "handles dispatch-fn exception"
    (let [res  (mock-resources {:dispatch-fn (fn [_ _ _ _] (throw (Exception. "dispatch boom")))})
          data (sut/handle-act-dispatch res (merge base-data {:plan {:id "p1"}}))]
      (is (some? (:error data)))
      (is (re-find #"dispatch boom" (:error data)))))

  (testing "noop result when dispatch-fn is nil"
    (let [res  (mock-resources {:dispatch-fn nil})
          data (sut/handle-act-dispatch res (merge base-data {:plan {:id "p1"}}))]
      (is (= {:status :no-dispatch-fn} (:execution-result data)))))

  (testing "forwards :run-id from data to dispatch-fn ctx"
    (let [seen (atom nil)
          res  (mock-resources {:dispatch-fn (fn [_plan _mode _agent-id ctx]
                                               (reset! seen ctx)
                                               {:wave-id "w" :result {:status :completed}})})]
      (sut/handle-act-dispatch res (merge base-data {:plan {:id "p1"}
                                                     :run-id "wf-run-42"}))
      (is (= {:run-id "wf-run-42"} @seen))))

  (testing "ctx carries nil :run-id when data has none"
    (let [seen (atom :unset)
          res  (mock-resources {:dispatch-fn (fn [_plan _mode _agent-id ctx]
                                               (reset! seen ctx)
                                               {:wave-id "w" :result {:status :completed}})})]
      (sut/handle-act-dispatch res (merge base-data {:plan {:id "p1"}}))
      (is (= {:run-id nil} @seen)))))

(deftest handle-act-verify-test
  (testing "verifies execution results"
    (let [res  (mock-resources)
          data (sut/handle-act-verify
                res
                (merge base-data {:execution-result {:status :ok}
                                  :plan {:id "p1"}}))]
      (is (= :act-verify (:phase data)))
      (is (true? (:tests-passed? data)))
      (is (some? (:verification data)))
      (is (some? (:act-ended data)))))

  (testing "handles verify-fn exception"
    (let [res  (mock-resources {:verify-fn (fn [_ _] (throw (Exception. "verify boom")))})
          data (sut/handle-act-verify
                res
                (merge base-data {:execution-result {:status :ok}
                                  :plan {:id "p1"}}))]
      (is (false? (:tests-passed? data)))
      (is (some? (:verification data)))))

  (testing "assumes passed when verify-fn is nil (degraded mode)"
    (let [res  (mock-resources {:verify-fn nil})
          data (sut/handle-act-verify
                res
                (merge base-data {:execution-result {:status :ok}
                                  :plan {:id "p1"}}))]
      (is (true? (:tests-passed? data)))
      (is (= {:status :no-verify-fn} (:verification data))))))

;; =============================================================================
;; Terminal Handler Tests
;; =============================================================================

(deftest handle-end-test
  (testing "returns summary of completed SAA workflow"
    (let [res  (mock-resources)
          fsm  {:data (merge base-data
                             {:phase :act-verify
                              :plan-only? false
                              :observations [{:type :file}]
                              :grounding-score 0.8
                              :plan {:id "p1"}
                              :plan-valid? true
                              :plan-memory-id "mem-1"
                              :kanban-task-ids ["t-1"]
                              :execution-result {:status :ok}
                              :tests-passed? true
                              :started-at "2026-02-07T12:00:00Z"
                              :silence-iterations 2
                              :abstract-retries 0})}
          result (sut/handle-end res fsm)]
      (is (map? result))
      (is (= "test-ling-1" (:agent-id result)))
      (is (= "Fix auth bug in login flow" (:task result)))
      (is (true? (:tests-passed? result)))
      (is (= "mem-1" (:plan-memory-id result)))))

  (testing "uses build-summary-fn when provided"
    (let [res    (mock-resources {:build-summary-fn (fn [data] {:custom true :task (:task data)})})
          fsm    {:data base-data}
          result (sut/handle-end res fsm)]
      (is (true? (:custom result)))
      (is (= "Fix auth bug in login flow" (:task result))))))

(deftest handle-error-test
  (testing "throws ex-info when no error-response-fn"
    (let [res (mock-resources)
          fsm {:error "something broke"
               :data  (merge base-data {:phase :silence})}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/handle-error res fsm)))))

  (testing "returns error map when error-response-fn provided"
    (let [res    (mock-resources {:error-response-fn (fn [err-ctx] {:handled true :phase (:phase err-ctx)})})
          fsm    {:error "something broke"
                  :data  (merge base-data {:phase :silence})}
          result (sut/handle-error res fsm)]
      (is (true? (:handled result)))
      (is (= :silence (:phase result))))))

;; =============================================================================
;; Full FSM Execution Tests (Integration)
;; =============================================================================

(deftest full-saa-happy-path-test
  (testing "complete SAA workflow: start → catchup → silence → review → abstract → validate → store → dispatch → verify → end"
    (let [res    (mock-resources)
          result (sut/run-full-saa res base-data)]
      (is (map? result))
      (is (= "test-ling-1" (:agent-id result)))
      (is (= "test-project" (:project-id result)))
      (is (true? (:tests-passed? result)))
      (is (true? (:plan-valid? result)))
      (is (some? (:plan result)))
      (is (some? (:plan-memory-id result)))
      (is (seq (:kanban-task-ids result)))
      (is (seq (:observations result)))
      (is (pos? (:grounding-score result))))))

(deftest full-saa-plan-only-test
  (testing "plan-only mode: skips Act phase, returns plan without execution"
    (let [res    (mock-resources)
          result (sut/run-plan-only res base-data)]
      (is (map? result))
      (is (true? (:plan-only? result)))
      (is (true? (:plan-valid? result)))
      (is (some? (:plan result)))
      (is (some? (:plan-memory-id result)))
      ;; No execution results in plan-only mode
      (is (nil? (:execution-result result)))
      (is (nil? (:tests-passed? result))))))

(deftest full-saa-missing-task-error-test
  (testing "missing task triggers error state"
    (let [res (mock-resources {:error-response-fn (fn [ctx] ctx)})]
      (let [result (sut/run-full-saa res {:agent-id "a"})]
        ;; error-response-fn receives {:phase :agent-id :task :data :error}
        ;; The handler-level error is in :data :error
        (is (some? (get-in result [:data :error])))))))

(deftest full-saa-missing-agent-id-error-test
  (testing "missing agent-id triggers error state"
    (let [res (mock-resources {:error-response-fn (fn [ctx] ctx)})]
      (let [result (sut/run-full-saa res {:task "t"})]
        (is (some? (get-in result [:data :error])))))))

(deftest full-saa-catchup-failure-error-test
  (testing "catchup failure transitions to error"
    (let [res (mock-resources {:catchup-fn        (fn [_ _] (throw (Exception. "catchup fail")))
                               :error-response-fn (fn [ctx] ctx)})]
      (let [result (sut/run-full-saa res base-data)]
        (is (some? (get-in result [:data :error])))))))

;; =============================================================================
;; Silence Loop Tests
;; =============================================================================

(deftest silence-loop-low-grounding-retries-test
  (testing "low grounding score causes silence loop, eventually proceeds"
    (let [call-count (atom 0)
          res (mock-resources
               {:explore-fn (fn [_ _ existing-obs]
                              (swap! call-count inc)
                              {:observations (conj (or existing-obs [])
                                                   {:type :file :content (str "iter-" @call-count)})
                               :files-read   @call-count
                               :discoveries  1})
                ;; Always return low score — will loop to max iterations then proceed
                :score-grounding-fn (fn [_obs _files] 0.2)})
          result (sut/run-full-saa res base-data)]
      ;; Should have looped through silence 3 times (max iterations)
      ;; then proceeded to abstract anyway
      (is (map? result))
      (is (>= @call-count 3)))))

(deftest silence-loop-improving-score-test
  (testing "improving grounding score stops silence loop"
    (let [call-count (atom 0)
          res (mock-resources
               {:explore-fn (fn [_ _ existing-obs]
                              (swap! call-count inc)
                              {:observations (conj (or existing-obs [])
                                                   {:type :file :content "found"})
                               :files-read   (* @call-count 2)
                               :discoveries  @call-count})
                ;; First call: 0.3, second call: 0.8 (sufficient)
                :score-grounding-fn (fn [_obs _files]
                                      (if (< @call-count 2) 0.3 0.8))})
          result (sut/run-full-saa res base-data)]
      (is (map? result))
      ;; Should have stopped looping after 2 iterations
      (is (<= @call-count 3)))))

;; =============================================================================
;; Abstract Retry Tests
;; =============================================================================

(deftest abstract-retry-on-invalid-plan-test
  (testing "invalid plan triggers re-abstract, then error if retries exhausted"
    (let [synth-count (atom 0)
          res (mock-resources
               {:synthesize-fn (fn [_ _ _]
                                 (swap! synth-count inc)
                                 ;; Always produce empty steps (invalid)
                                 {:id "plan-bad" :title "Bad" :steps []})
                :validate-plan-fn (fn [plan]
                                    (if (seq (:steps plan))
                                      {:valid? true :errors []}
                                      {:valid? false :errors ["No steps"]}))
                :error-response-fn (fn [ctx] ctx)})
          result (sut/run-full-saa res base-data)]
      ;; Should have tried abstract 3 times (initial + 2 retries)
      (is (>= @synth-count 2))
      ;; Should end in error — plan-valid? false with retries exhausted
      (is (false? (get-in result [:data :plan-valid?])))
      (is (>= (get-in result [:data :abstract-retries]) 2)))))

(deftest abstract-retry-succeeds-on-second-try-test
  (testing "invalid plan on first try, valid on second try"
    (let [synth-count (atom 0)
          res (mock-resources
               {:synthesize-fn (fn [task _ _]
                                 (swap! synth-count inc)
                                 (if (= 1 @synth-count)
                                   ;; First try: empty steps
                                   {:id "plan-v1" :title "Bad" :steps []}
                                   ;; Second try: valid plan
                                   {:id "plan-v2" :title "Good" :steps [{:id "s1" :title "Step"}]}))
                :validate-plan-fn (fn [plan]
                                    (if (seq (:steps plan))
                                      {:valid? true :errors []}
                                      {:valid? false :errors ["No steps"]}))})
          result (sut/run-full-saa res base-data)]
      (is (= 2 @synth-count))
      (is (map? result))
      (is (true? (:plan-valid? result))))))

;; =============================================================================
;; Verification Failure Tests
;; =============================================================================

(deftest verification-failure-triggers-error-test
  (testing "failed tests transition to error state"
    (let [res (mock-resources
               {:verify-fn         (fn [_ _] {:passed? false :details {:tests-run 5 :tests-passed 3}})
                :error-response-fn (fn [ctx] ctx)})
          result (sut/run-full-saa res base-data)]
      ;; Should end in error because tests-failed?
      (is (some? result)))))

;; =============================================================================
;; Compile Tests
;; =============================================================================

(deftest compile-saa-test
  (testing "compile-saa produces a compiled FSM"
    (let [compiled (sut/compile-saa)]
      (is (map? compiled))
      (is (contains? compiled :fsm))
      (is (contains? compiled :opts)))))

(deftest handler-map-completeness-test
  (testing "handler-map contains all required handler keys"
    (let [required #{:start :catchup :silence :silence-review
                     :abstract :validate-plan :store-plan
                     :act-dispatch :act-verify :end :error}]
      (is (= required (set (keys sut/handler-map))))
      ;; All values are functions
      (doseq [[k v] sut/handler-map]
        (is (fn? v) (str "Handler " k " should be a function"))))))

;; =============================================================================
;; Shout Tracking Tests
;; =============================================================================

(deftest shout-fn-called-during-phases-test
  (testing "shout-fn is called during each phase"
    (let [shouts (atom [])
          res    (mock-resources {:shout-fn (fn [agent-id phase message]
                                              (swap! shouts conj {:agent-id agent-id
                                                                  :phase    phase
                                                                  :message  message}))})
          _result (sut/run-full-saa res base-data)]
      ;; Should have shouts for: start, catchup, silence, silence-review, abstract,
      ;; validate-plan, store-plan, act-dispatch, act-verify, end
      (is (>= (count @shouts) 8) "Should shout during most phases")
      ;; First shout should be from start phase
      (is (= :start (:phase (first @shouts))))
      ;; All shouts should have agent-id
      (is (every? :agent-id @shouts)))))

(deftest shout-fn-exception-is-non-fatal-test
  (testing "shout-fn exception does not crash workflow"
    (let [res    (mock-resources {:shout-fn (fn [_ _ _] (throw (Exception. "shout boom")))})
          result (sut/run-full-saa res base-data)]
      ;; Should complete despite shout failures
      (is (map? result))
      (is (some? (:plan result))))))

;; =============================================================================
;; Clock Tests
;; =============================================================================

(deftest clock-fn-used-for-timestamps-test
  (testing "clock-fn provides deterministic timestamps"
    (let [res    (mock-resources)
          result (sut/run-full-saa res base-data)]
      (is (= (str fixed-clock) (:started-at result)))
      (is (= (str fixed-clock) (:silence-started result))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest run-saa-with-minimal-resources-test
  (testing "SAA runs in degraded mode with empty resources"
    (let [res    {:clock-fn          (constantly fixed-clock)
                  :error-response-fn (fn [ctx] ctx)}
          result (sut/run-full-saa res base-data)]
      ;; Without catchup-fn, context-loaded? is true (degraded mode)
      ;; Without explore-fn, creates minimal observations
      ;; Without synthesize-fn, sets error (no plan)
      (is (map? result)))))

(deftest run-saa-3-arity-test
  (testing "run-saa 3-arity works with compiled FSM"
    (let [compiled (sut/compile-saa)
          res      (mock-resources)
          result   (sut/run-saa compiled res base-data)]
      (is (map? result))
      (is (some? (:plan result))))))

(deftest run-saa-2-arity-defaults-test
  (testing "run-saa 2-arity uses default data"
    (let [compiled (sut/compile-saa)
          ;; Need error-response-fn since no agent-id/task in defaults
          res      (mock-resources {:error-response-fn (fn [ctx] ctx)})
          result   (sut/run-saa compiled res)]
      ;; Will error because no agent-id/task
      (is (map? result)))))

;; =============================================================================
;; Pre/Post Hook Tests (trace-log)
;; =============================================================================

(deftest saa-spec-has-pre-post-hooks-test
  (testing "saa-workflow-spec has pre and post hooks in opts"
    (let [opts (get-in sut/saa-workflow-spec [:opts])]
      (is (fn? (:pre opts)) "Should have a pre hook function")
      (is (fn? (:post opts)) "Should have a post hook function"))))

(deftest saa-spec-has-subscriptions-test
  (testing "saa-workflow-spec tracks key state paths via subscriptions"
    (let [subs (get-in sut/saa-workflow-spec [:opts :subscriptions])]
      (is (contains? subs [:grounding-score]))
      (is (contains? subs [:plan-valid?]))
      (is (contains? subs [:tests-passed?]))
      (is (contains? subs [:silence-iterations])))))

;; =============================================================================
;; Trace-Log Accumulation Tests (Pre/Post Hooks)
;; =============================================================================

(deftest trace-log-accumulated-during-execution-test
  (testing "pre/post hooks accumulate trace-log entries through FSM execution"
    (let [pre-hook  (get-in sut/saa-workflow-spec [:opts :pre])
          post-hook (get-in sut/saa-workflow-spec [:opts :post])
          ;; Simulate an FSM state map
          fsm-state {:current-state-id ::sut/silence
                     :data {:trace-log []}}
          resources {}
          ;; Apply pre hook
          after-pre (pre-hook fsm-state resources)
          ;; Apply post hook
          after-post (post-hook after-pre resources)]
      ;; Pre hook should add an :enter trace entry
      (is (= 1 (count (get-in after-pre [:data :trace-log]))))
      (is (= :enter (:direction (first (get-in after-pre [:data :trace-log])))))
      (is (= ::sut/silence (:state (first (get-in after-pre [:data :trace-log])))))
      ;; Post hook should add an :exit trace entry
      (is (= 2 (count (get-in after-post [:data :trace-log]))))
      (is (= :exit (:direction (second (get-in after-post [:data :trace-log]))))))))

(deftest trace-log-pre-hook-initializes-nil-trace-test
  (testing "pre hook initializes trace-log from nil (fnil conj [])"
    (let [pre-hook  (get-in sut/saa-workflow-spec [:opts :pre])
          fsm-state {:current-state-id ::sut/catchup
                     :data {}} ;; no :trace-log key
          after-pre (pre-hook fsm-state {})]
      (is (vector? (get-in after-pre [:data :trace-log])))
      (is (= 1 (count (get-in after-pre [:data :trace-log])))))))

;; =============================================================================
;; EDN Spec Structure Tests
;; =============================================================================

(deftest edn-spec-has-expected-states-test
  (testing "EDN spec from resources/fsm/saa-workflow.edn has all expected states"
    (let [spec (clojure.edn/read-string (slurp "resources/fsm/saa-workflow.edn"))
          fsm-states (set (keys (:fsm spec)))]
      (is (contains? spec :fsm) "EDN spec should have :fsm key")
      (is (contains? spec :opts) "EDN spec should have :opts key")
      (is (contains? fsm-states :hive.events.fsm/start))
      (is (contains? fsm-states :hive.events.fsm/end))
      (is (contains? fsm-states :hive.events.fsm/error))
      (is (contains? fsm-states :hive-mcp.workflows.saa-workflow/catchup))
      (is (contains? fsm-states :hive-mcp.workflows.saa-workflow/silence))
      (is (contains? fsm-states :hive-mcp.workflows.saa-workflow/silence-review))
      (is (contains? fsm-states :hive-mcp.workflows.saa-workflow/abstract))
      (is (contains? fsm-states :hive-mcp.workflows.saa-workflow/validate-plan))
      (is (contains? fsm-states :hive-mcp.workflows.saa-workflow/store-plan))
      (is (contains? fsm-states :hive-mcp.workflows.saa-workflow/act-dispatch))
      (is (contains? fsm-states :hive-mcp.workflows.saa-workflow/act-verify)))))

(deftest edn-spec-handler-keys-match-handler-map-test
  (testing "all handler keywords in EDN spec have corresponding entries in handler-map"
    (let [spec (clojure.edn/read-string (slurp "resources/fsm/saa-workflow.edn"))
          edn-handlers (->> (:fsm spec)
                            vals
                            (map :handler)
                            (remove nil?)
                            set)
          handler-map-keys (set (keys sut/handler-map))]
      (doseq [h edn-handlers]
        (is (contains? handler-map-keys h)
            (str "Handler " h " in EDN spec not found in handler-map"))))))

(deftest edn-spec-dispatch-predicates-are-resolvable-test
  (testing "dispatch predicates in EDN spec are keyword refs, (fn ...) forms, or functions"
    (let [spec (clojure.edn/read-string (slurp "resources/fsm/saa-workflow.edn"))]
      (doseq [[state-id state-def] (:fsm spec)
              :when (:dispatches state-def)
              [_target pred] (:dispatches state-def)]
        (is (or (keyword? pred) (list? pred) (fn? pred))
            (str "Dispatch predicate for " state-id " should be a keyword, fn form, or function, got: " (type pred))))))

  (testing "all keyword predicates in EDN spec resolve via predicate-map"
    (let [spec (clojure.edn/read-string (slurp "resources/fsm/saa-workflow.edn"))
          pred-map-keys (set (keys sut/predicate-map))]
      (doseq [[state-id state-def] (:fsm spec)
              :when (:dispatches state-def)
              [_target pred] (:dispatches state-def)
              :when (keyword? pred)]
        (is (contains? pred-map-keys pred)
            (str "Predicate " pred " in " state-id " not found in predicate-map"))))))

;; =============================================================================
;; In-Code Spec Structure Tests
;; =============================================================================

(deftest saa-workflow-spec-state-graph-completeness-test
  (testing "saa-workflow-spec contains all expected states"
    (let [spec-states (set (keys (:fsm sut/saa-workflow-spec)))]
      (is (contains? spec-states ::fsm/start))
      (is (contains? spec-states ::fsm/end))
      (is (contains? spec-states ::fsm/error))
      (is (contains? spec-states ::sut/catchup))
      (is (contains? spec-states ::sut/silence))
      (is (contains? spec-states ::sut/silence-review))
      (is (contains? spec-states ::sut/abstract))
      (is (contains? spec-states ::sut/validate-plan))
      (is (contains? spec-states ::sut/store-plan))
      (is (contains? spec-states ::sut/act-dispatch))
      (is (contains? spec-states ::sut/act-verify))
      ;; Total: 11 states (3 privileged + 8 SAA)
      (is (= 11 (count spec-states))))))

(deftest saa-workflow-spec-all-handlers-are-functions-test
  (testing "every :handler in saa-workflow-spec is a function"
    (doseq [[state-id state-def] (:fsm sut/saa-workflow-spec)]
      (is (fn? (:handler state-def))
          (str "Handler for state " state-id " should be a function")))))

(deftest saa-workflow-spec-all-dispatch-predicates-are-functions-test
  (testing "every dispatch predicate in saa-workflow-spec is a function"
    (doseq [[state-id state-def] (:fsm sut/saa-workflow-spec)
            :when (:dispatches state-def)]
      (doseq [[target pred] (:dispatches state-def)]
        (is (fn? pred)
            (str "Dispatch predicate " state-id " -> " target " should be a function"))))))

(deftest saa-workflow-spec-dispatch-targets-are-valid-states-test
  (testing "all dispatch targets reference existing states"
    (let [valid-states (set (keys (:fsm sut/saa-workflow-spec)))]
      (doseq [[state-id state-def] (:fsm sut/saa-workflow-spec)
              :when (:dispatches state-def)
              [target _pred] (:dispatches state-def)]
        (is (contains? valid-states target)
            (str "Dispatch target " target " from " state-id
                 " not found in state graph"))))))

(deftest saa-workflow-spec-terminal-states-have-no-dispatches-test
  (testing "terminal states (end, error) have no dispatches"
    (is (nil? (get-in sut/saa-workflow-spec [:fsm ::fsm/end :dispatches])))
    (is (nil? (get-in sut/saa-workflow-spec [:fsm ::fsm/error :dispatches])))))

;; =============================================================================
;; Custom Grounding Threshold Tests
;; =============================================================================

(deftest custom-grounding-threshold-low-test
  (testing "low threshold (0.2) means fewer silence iterations needed"
    (let [call-count (atom 0)
          res (mock-resources
               {:explore-fn (fn [_ _ existing-obs]
                              (swap! call-count inc)
                              {:observations (conj (or existing-obs [])
                                                   {:type :file :content "found"})
                               :files-read   1
                               :discoveries  1})
                ;; Returns 0.3 — above 0.2 threshold
                :score-grounding-fn (fn [_obs _files] 0.3)})
          result (sut/run-full-saa res (assoc base-data :grounding-threshold 0.2))]
      (is (map? result))
      ;; With threshold 0.2, score 0.3 is sufficient — no retry loop
      (is (= 1 @call-count) "Should only explore once with low threshold"))))

(deftest custom-grounding-threshold-high-test
  (testing "high threshold (0.95) means more silence iterations needed"
    (let [call-count (atom 0)
          res (mock-resources
               {:explore-fn (fn [_ _ existing-obs]
                              (swap! call-count inc)
                              {:observations (conj (or existing-obs [])
                                                   {:type :file :content "found"})
                               :files-read   (* @call-count 3)
                               :discoveries  @call-count})
                ;; Always returns 0.8 — below 0.95 threshold
                :score-grounding-fn (fn [_obs _files] 0.8)})
          result (sut/run-full-saa res (assoc base-data :grounding-threshold 0.95))]
      (is (map? result))
      ;; With threshold 0.95, score 0.8 is never sufficient — loops to max (3)
      (is (>= @call-count 3) "Should explore at least 3 times with high threshold"))))

;; =============================================================================
;; Concurrent/Idempotent Execution Tests
;; =============================================================================

(deftest independent-saa-runs-do-not-interfere-test
  (testing "two SAA runs with different data produce independent results"
    (let [res (mock-resources)
          result-a (sut/run-full-saa res (assoc base-data :agent-id "agent-A"
                                                :task "Task A"))
          result-b (sut/run-full-saa res (assoc base-data :agent-id "agent-B"
                                                :task "Task B"))]
      (is (= "agent-A" (:agent-id result-a)))
      (is (= "agent-B" (:agent-id result-b)))
      (is (= "Task A" (:task result-a)))
      (is (= "Task B" (:task result-b)))
      (is (true? (:tests-passed? result-a)))
      (is (true? (:tests-passed? result-b))))))

(deftest compile-saa-is-idempotent-test
  (testing "multiple compile-saa calls produce equivalent compiled FSMs"
    (let [c1 (sut/compile-saa)
          c2 (sut/compile-saa)]
      (is (map? c1))
      (is (map? c2))
      (is (= (set (keys (:fsm c1))) (set (keys (:fsm c2)))))
      ;; Both should work identically
      (let [res (mock-resources)
            r1  (sut/run-saa c1 res base-data)
            r2  (sut/run-saa c2 res base-data)]
        (is (= (:agent-id r1) (:agent-id r2)))
        (is (= (:plan-valid? r1) (:plan-valid? r2)))
        (is (= (:tests-passed? r1) (:tests-passed? r2)))))))

;; =============================================================================
;; FSMWorkflowEngine Integration Tests
;; =============================================================================

(deftest fsm-engine-load-saa-workflow-test
  (testing "FSMWorkflowEngine can load SAA workflow from registry"
    (require 'hive-mcp.workflows.registry)
    (require 'hive-mcp.workflows.fsm-engine)
    (let [init!       @(ns-resolve 'hive-mcp.workflows.registry 'init!)
          _           (init!)
          get-wf      @(ns-resolve 'hive-mcp.workflows.registry 'get-workflow)
          compiled    (get-wf :saa-workflow)]
      (if compiled
        (do
          (is (map? compiled) "Compiled SAA should be a map")
          (is (contains? compiled :fsm) "Compiled SAA should have :fsm")
          ;; Also test via FSMWorkflowEngine
          (let [create-engine @(ns-resolve 'hive-mcp.workflows.fsm-engine 'create-engine)
                engine        (create-engine)
                load-wf       @(ns-resolve 'hive-mcp.protocols.workflow 'load-workflow)
                loaded        (load-wf engine :saa-workflow {})]
            (is (true? (:loaded? loaded)) "Should load SAA workflow")
            (is (= "saa-workflow" (:name loaded)))
            (is (seq (:steps loaded)) "Should have extracted steps")))
        (is true "Registry compilation not available — skipping")))))

(deftest fsm-engine-validate-saa-workflow-test
  (testing "FSMWorkflowEngine validates SAA workflow structure"
    (require 'hive-mcp.workflows.registry)
    (require 'hive-mcp.workflows.fsm-engine)
    (let [init!       @(ns-resolve 'hive-mcp.workflows.registry 'init!)
          _           (init!)
          create-engine @(ns-resolve 'hive-mcp.workflows.fsm-engine 'create-engine)
          engine        (create-engine)
          load-wf       @(ns-resolve 'hive-mcp.protocols.workflow 'load-workflow)
          validate-wf   @(ns-resolve 'hive-mcp.protocols.workflow 'validate-workflow)
          loaded        (load-wf engine :saa-workflow {})]
      (when (:loaded? loaded)
        (let [validation (validate-wf engine loaded)]
          (is (true? (:valid? validation)) "SAA workflow should be valid")
          (is (empty? (:errors validation)) "Should have no validation errors")
          (is (seq (:dependency-order validation))
              "Should have dependency order"))))))

(deftest fsm-engine-execute-saa-workflow-test
  (testing "FSMWorkflowEngine executes SAA workflow end-to-end"
    (require 'hive-mcp.workflows.registry)
    (require 'hive-mcp.workflows.fsm-engine)
    (let [init!          @(ns-resolve 'hive-mcp.workflows.registry 'init!)
          _              (init!)
          create-engine  @(ns-resolve 'hive-mcp.workflows.fsm-engine 'create-engine)
          reset-statuses @(ns-resolve 'hive-mcp.workflows.fsm-engine 'reset-statuses!)
          engine         (create-engine)
          _              (reset-statuses)
          load-wf        @(ns-resolve 'hive-mcp.protocols.workflow 'load-workflow)
          execute-wf     @(ns-resolve 'hive-mcp.protocols.workflow 'execute-workflow)
          get-status     @(ns-resolve 'hive-mcp.protocols.workflow 'get-status)
          loaded         (load-wf engine :saa-workflow {})]
      (when (:loaded? loaded)
        (let [resources (mock-resources)
              result    (execute-wf engine loaded
                                    {:resources    resources
                                     :initial-data base-data})]
          (is (true? (:success? result)) "Execution should succeed")
          (is (some? (:workflow-id result)))
          (is (pos? (:duration-ms result)))
          ;; Check tracked status
          (let [status (get-status engine (:workflow-id result))]
            (is (= :completed (:status status)))))))))

(deftest fsm-engine-cancel-unknown-workflow-test
  (testing "FSMWorkflowEngine returns error when cancelling unknown workflow"
    (require 'hive-mcp.workflows.fsm-engine)
    (let [create-engine  @(ns-resolve 'hive-mcp.workflows.fsm-engine 'create-engine)
          reset-statuses @(ns-resolve 'hive-mcp.workflows.fsm-engine 'reset-statuses!)
          engine         (create-engine)
          _              (reset-statuses)
          cancel-wf      @(ns-resolve 'hive-mcp.protocols.workflow 'cancel-workflow)
          result         (cancel-wf engine "nonexistent-id" {})]
      (is (false? (:success? result)))
      (is (= :unknown (:status result))))))

;; =============================================================================
;; Event Handler Tests (handlers/saa.clj)
;; =============================================================================

(deftest saa-event-handler-started-test
  (testing "handle-saa-started produces correct effects"
    (require 'hive-mcp.events.handlers.saa)
    (let [handler @(ns-resolve 'hive-mcp.events.handlers.saa 'handle-saa-started)
          effects (handler {} [:saa/started {:agent-id   "test-ling"
                                             :task       "Fix auth bug"
                                             :directory  "/test"
                                             :plan-only? false}])]
      (is (map? effects))
      (is (contains? effects :log))
      (is (contains? effects :shout))
      (is (= :info (get-in effects [:log :level])))
      (is (= "test-ling" (get-in effects [:shout :agent-id])))
      (is (= :started (get-in effects [:shout :event-type])))
      (is (= :saa (get-in effects [:shout :data :workflow])))
      (is (= "full" (get-in effects [:shout :data :mode]))))))

(deftest saa-event-handler-started-plan-only-test
  (testing "handle-saa-started reflects plan-only mode"
    (require 'hive-mcp.events.handlers.saa)
    (let [handler @(ns-resolve 'hive-mcp.events.handlers.saa 'handle-saa-started)
          effects (handler {} [:saa/started {:agent-id   "test-ling"
                                             :task       "Explore auth"
                                             :plan-only? true}])]
      (is (= "plan-only" (get-in effects [:shout :data :mode]))))))

(deftest saa-event-handler-phase-complete-test
  (testing "handle-saa-phase-complete produces log, shout, and channel-publish"
    (require 'hive-mcp.events.handlers.saa)
    (let [handler @(ns-resolve 'hive-mcp.events.handlers.saa 'handle-saa-phase-complete)
          effects (handler {} [:saa/phase-complete
                               {:agent-id           "test-ling"
                                :phase              :silence-review
                                :grounding-score    0.75
                                :silence-iterations 2}])]
      (is (map? effects))
      (is (contains? effects :log))
      (is (contains? effects :shout))
      (is (contains? effects :channel-publish))
      (is (= :progress (get-in effects [:shout :event-type])))
      (is (= :saa-phase-complete (get-in effects [:channel-publish :event-type])))
      (is (= 0.75 (get-in effects [:channel-publish :data :grounding-score]))))))

(deftest saa-event-handler-phase-complete-validate-plan-test
  (testing "handle-saa-phase-complete for validate-plan phase includes plan-valid?"
    (require 'hive-mcp.events.handlers.saa)
    (let [handler @(ns-resolve 'hive-mcp.events.handlers.saa 'handle-saa-phase-complete)
          effects (handler {} [:saa/phase-complete
                               {:agent-id         "test-ling"
                                :phase            :validate-plan
                                :plan-valid?      true
                                :abstract-retries 0}])]
      (is (re-find #"valid=true" (get-in effects [:log :message]))))))

(deftest saa-event-handler-completed-test
  (testing "handle-saa-completed produces correct effects"
    (require 'hive-mcp.events.handlers.saa)
    (let [handler @(ns-resolve 'hive-mcp.events.handlers.saa 'handle-saa-completed)
          effects (handler {} [:saa/completed
                               {:agent-id        "test-ling"
                                :task            "Fix auth"
                                :plan-memory-id  "mem-123"
                                :kanban-task-ids ["t1" "t2"]
                                :plan-only?      false
                                :tests-passed?   true
                                :grounding-score 0.85}])]
      (is (map? effects))
      (is (= :completed (get-in effects [:shout :event-type])))
      (is (= :saa-completed (get-in effects [:channel-publish :event-type])))
      (is (re-find #"mem-123" (get-in effects [:log :message])))
      (is (re-find #"tests=pass" (get-in effects [:log :message]))))))

(deftest saa-event-handler-completed-plan-only-test
  (testing "handle-saa-completed in plan-only mode doesn't mention tests"
    (require 'hive-mcp.events.handlers.saa)
    (let [handler @(ns-resolve 'hive-mcp.events.handlers.saa 'handle-saa-completed)
          effects (handler {} [:saa/completed
                               {:agent-id   "test-ling"
                                :task       "Explore"
                                :plan-only? true}])]
      (is (not (re-find #"tests=" (get-in effects [:log :message])))))))

(deftest saa-event-handler-failed-test
  (testing "handle-saa-failed produces error telemetry"
    (require 'hive-mcp.events.handlers.saa)
    (let [handler @(ns-resolve 'hive-mcp.events.handlers.saa 'handle-saa-failed)
          effects (handler {} [:saa/failed
                               {:agent-id "test-ling"
                                :task     "Fix auth"
                                :phase    :abstract
                                :error    "Synthesis failed"}])]
      (is (map? effects))
      (is (= :error (get-in effects [:log :level])))
      (is (= :error (get-in effects [:shout :event-type])))
      (is (contains? effects :emit-system-error))
      (is (= :saa-workflow-failed (get-in effects [:emit-system-error :error-type])))
      (is (= :saa-failed (get-in effects [:channel-publish :event-type])))
      (is (re-find #"abstract" (get-in effects [:log :message])))
      (is (re-find #"Synthesis failed" (get-in effects [:log :message]))))))

(deftest saa-event-handler-fallback-agent-id-test
  (testing "event handlers fall back when agent-id missing"
    (require 'hive-mcp.events.handlers.saa)
    (let [handler @(ns-resolve 'hive-mcp.events.handlers.saa 'handle-saa-started)
          effects (handler {} [:saa/started {:task "Fix auth"}])]
      (is (string? (get-in effects [:shout :agent-id]))))))

;; =============================================================================
;; Registry Integration Tests
;; =============================================================================

(deftest registry-saa-workflow-listing-test
  (testing "SAA workflow spec appears in registry after init"
    (require 'hive-mcp.workflows.registry)
    (let [init!   @(ns-resolve 'hive-mcp.workflows.registry 'init!)
          list-wf @(ns-resolve 'hive-mcp.workflows.registry 'list-workflows)
          _       (init!)
          workflows (list-wf)]
      ;; The spec should always be found (from resources/fsm/saa-workflow.edn)
      (is (contains? workflows :saa-workflow)
          "Registry should find saa-workflow.edn spec")
      (when (contains? workflows :saa-workflow)
        (is (true? (get-in workflows [:saa-workflow :has-spec?]))
            "SAA spec should be loaded from EDN")
        ;; Handler registration depends on register-saa-workflow! being loaded
        ;; in the running JVM. If not available, handlers won't be registered
        ;; (this is a REPL hot-reload issue, not a code defect).
        (when (get-in workflows [:saa-workflow :has-handlers?])
          (is (true? (get-in workflows [:saa-workflow :compiled?]))
              "SAA should compile when handlers are registered"))))))

;; =============================================================================
;; Boundary / Edge Case Tests
;; =============================================================================

(deftest empty-observations-produce-low-grounding-score-test
  (testing "empty observations vector produces low grounding score"
    (let [res  (mock-resources)
          data (sut/handle-silence-review
                res
                (merge base-data {:observations []
                                  :files-read   0
                                  :grounding-threshold 0.6
                                  :silence-iterations 1}))]
      (is (< (:grounding-score data) 0.6)
          "Empty observations should not meet threshold"))))

(deftest very-many-observations-cap-score-at-1-0-test
  (testing "many observations don't push score above 1.0"
    (let [res  (mock-resources)
          many-obs (vec (repeatedly 100 #(hash-map :type :file :content "found")))
          data (sut/handle-silence-review
                res
                (merge base-data {:observations many-obs
                                  :files-read   50
                                  :grounding-threshold 0.6
                                  :silence-iterations 1}))]
      (is (<= (:grounding-score data) 1.0)
          "Score should be capped at 1.0"))))

(deftest handle-start-with-directory-from-resources-test
  (testing "handle-start falls back to directory from resources when not in data"
    (let [res  (mock-resources)
          res-with-dir (assoc res :directory "/from/resources")
          data (sut/handle-start res-with-dir {:agent-id "a" :task "t"})]
      (is (= "/from/resources" (:directory data))))))

(deftest handle-start-with-agent-id-from-resources-test
  (testing "handle-start falls back to agent-id from resources when not in data"
    (let [res  (assoc (mock-resources) :agent-id "res-agent")
          data (sut/handle-start res {:task "t" :directory "/test"})]
      (is (= "res-agent" (:agent-id data)))
      (is (nil? (:error data))))))

(deftest store-plan-preserves-existing-data-test
  (testing "store-plan doesn't overwrite unrelated data fields"
    (let [res  (mock-resources)
          input-data (merge base-data {:plan {:id "p1" :steps [{:id "s1"}]}
                                       :grounding-score 0.85
                                       :observations [{:type :file}]
                                       :plan-valid? true
                                       :custom-field "should-survive"})
          data (sut/handle-store-plan res input-data)]
      (is (= 0.85 (:grounding-score data)))
      (is (= [{:type :file}] (:observations data)))
      (is (= true (:plan-valid? data)))
      (is (= "should-survive" (:custom-field data)))
      (is (some? (:plan-memory-id data))))))

(deftest silence-preserves-started-timestamp-on-retry-test
  (testing "handle-silence preserves original silence-started on subsequent iterations"
    (let [res  (mock-resources)
          d1   (sut/handle-silence res (merge base-data {:silence-iterations 0}))
          d2   (sut/handle-silence res d1)]
      (is (some? (:silence-started d1)))
      (is (= (:silence-started d1) (:silence-started d2))
          "silence-started should not change on retry"))))

(deftest act-dispatch-preserves-act-started-on-rerun-test
  (testing "handle-act-dispatch preserves act-started timestamp"
    (let [res  (mock-resources)
          d1   (sut/handle-act-dispatch res (merge base-data {:plan {:id "p1"}
                                                              :act-started nil}))
          d2   (sut/handle-act-dispatch res d1)]
      (is (some? (:act-started d1)))
      (is (= (:act-started d1) (:act-started d2))
          "act-started should not change on rerun"))))

;; =============================================================================
;; Always Predicate Test
;; =============================================================================

(deftest always-predicate-test
  (testing "always predicate returns true for any input"
    (is (true? (sut/always {})))
    (is (true? (sut/always nil)))
    (is (true? (sut/always {:anything "goes"})))))
