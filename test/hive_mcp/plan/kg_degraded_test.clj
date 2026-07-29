(ns hive-mcp.plan.kg-degraded-test
  "Trifecta tests for hive-mcp.plan.kg-degraded — the KG-degraded escape
   hatch in plan-to-kanban. Stratified by CPPB layer:

   - Unit:        Plan layer (warning-tag pure shaping)
                  Process layer (call-with-timeout — ok / timeout / throw)
                  Build layer (apply-kg-calls aggregation)
   - Property:    For any seq of (ok | hang | throw) call behaviors,
                  apply-kg-calls returns within budget, edges = ok
                  flatten, warnings count = degraded count, never throws.
   - Integration: build-execute-fn with simulated hanging KG via
                  with-redefs — tasks land, kg-degraded? true, warnings
                  surface, total time bounded."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-dsl.result :as r]
            [hive-mcp.plan.kg-degraded :as sut]
            [hive-mcp.plan.tool :as plan-tool]
            [clojure.data.json :as json]
            [hive-spi.memory.registry :as sreg]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.test.stub.memory-store :as mem-stub]
            [hive-mcp.plan.schema :as schema]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixture — pin timeout to a fast value so tests stay sub-second.
;; =============================================================================

(use-fixtures :each
  ;; The kanban collaborator is reached through its real port: an
  ;; atom-backed IMemoryStore stub registered in the store registry, so
  ;; the integration tests drive the production create path instead of a
  ;; hand-rolled fn whose arity can drift.
  mem-stub/with-stub-store
  (iso/with-isolations :kg-conn)
  (fn [f]
    ;; 200ms is fast enough to keep tests sub-second yet generous enough
    ;; to absorb agent-thread-pool scheduling jitter under load. Tighter
    ;; budgets caused flaky failures when other tests had populated the
    ;; pool with sleeping workers.
    (with-redefs [sut/kg-call-timeout-ms (constantly 200)]
      (f))))

;; =============================================================================
;; Helper thunks — represent each behavior class.
;; =============================================================================

(defn- thunk-ok
  "Return a thunk that yields `v`."
  [v] (fn [] v))

(defn- thunk-hang
  "Return a thunk that hangs (interruptible — Thread/sleep responds to
   future-cancel; @(promise) does NOT and would leak agent-pool threads
   across test runs)."
  []
  (fn []
    (try (Thread/sleep 60000)
         (catch InterruptedException _ ::interrupted))))

(defn- thunk-throw
  "Return a thunk that throws an ExceptionInfo with msg `m`."
  [m]
  (fn [] (throw (ex-info m {:fixture true}))))

;; =============================================================================
;; Unit — Plan layer (pure shape)
;; =============================================================================

(deftest warning-tag-test
  (testing "weave/timeout error → kg-timeout:<label>:<ms>ms"
    (is (= "kg-timeout:foo:75ms"
           (sut/warning-tag "foo" (r/err :weave/timeout {:timeout-ms 75 :name "foo"})))))

  (testing "weave/exception error → kg-error:<label>:<message>"
    (is (= "kg-error:bar:boom"
           (sut/warning-tag "bar" (r/err :weave/exception
                                         {:message "boom" :class "X" :name "bar"})))))

  (testing "weave/exception with nil message falls back to class"
    (is (= "kg-error:baz:java.lang.RuntimeException"
           (sut/warning-tag "baz" (r/err :weave/exception
                                         {:message nil
                                          :class "java.lang.RuntimeException"})))))

  (testing "unknown error category preserved with kg-degraded prefix"
    (is (re-matches #"kg-degraded:weird:.*"
                    (sut/warning-tag "weird" (r/err :something/odd {:detail 1}))))))

;; =============================================================================
;; Unit — Process layer
;; =============================================================================

(deftest call-with-timeout-ok-test
  (testing "successful thunk returns Result/ok with value"
    (let [result (sut/call-with-timeout "ok-call" (thunk-ok [:edge-1 :edge-2]))]
      (is (r/ok? result))
      (is (= [:edge-1 :edge-2] (:ok result))))))

(deftest call-with-timeout-throw-test
  (testing "throwing thunk returns Result/err :weave/exception"
    (let [result (sut/call-with-timeout "boom-call" (thunk-throw "kaboom"))]
      (is (r/err? result))
      (is (= :weave/exception (:error result)))
      (is (= "kaboom" (:message result))))))

(deftest call-with-timeout-hang-test
  (testing "hanging thunk returns Result/err :weave/timeout within budget"
    (let [t0 (System/currentTimeMillis)
          result (sut/call-with-timeout "hang-call" (thunk-hang))
          elapsed (- (System/currentTimeMillis) t0)]
      (is (r/err? result))
      (is (= :weave/timeout (:error result)))
      (is (< elapsed 2000)
          (str "elapsed " elapsed "ms must be near the configured timeout")))))

;; =============================================================================
;; Unit — Build layer (aggregation)
;; =============================================================================

(deftest apply-kg-calls-all-ok-test
  (testing "all-ok calls produce flattened edges, no warnings"
    (let [result (sut/apply-kg-calls
                  [["a" (thunk-ok [:e1 :e2])]
                   ["b" (thunk-ok :e3)]
                   ["c" (thunk-ok nil)]])]
      (is (= [:e1 :e2 :e3] (:edges result)))
      (is (= [] (:warnings result)))
      (is (false? (:degraded? result))))))

(deftest apply-kg-calls-mixed-test
  (testing "mix of ok/hang/throw — ok edges flow through, warnings tag rest"
    (let [result (sut/apply-kg-calls
                  [["a" (thunk-ok [:e1])]
                   ["b" (thunk-hang)]
                   ["c" (thunk-throw "no")]])]
      (is (= [:e1] (:edges result)))
      (is (true? (:degraded? result)))
      (is (= 2 (count (:warnings result))))
      (is (some #(re-find #"kg-timeout:b:" %) (:warnings result)))
      (is (some #(re-find #"kg-error:c:no" %) (:warnings result))))))

(deftest apply-kg-calls-map-form-test
  (testing "map form {:label … :thunk …} accepted"
    (let [result (sut/apply-kg-calls
                  [{:label "a" :thunk (thunk-ok [:e1])}
                   {:label "b" :thunk (thunk-throw "x")}])]
      (is (= [:e1] (:edges result)))
      (is (= 1 (count (:warnings result)))))))

(deftest apply-kg-calls-empty-test
  (testing "empty calls list — empty edges, empty warnings, not degraded"
    (let [result (sut/apply-kg-calls [])]
      (is (= [] (:edges result)))
      (is (= [] (:warnings result)))
      (is (false? (:degraded? result))))))

;; =============================================================================
;; Property — Build layer invariants
;; =============================================================================

(def gen-behavior
  "Generator for one of three thunk behaviors."
  (gen/elements [:ok :hang :throw]))

(defn- behavior->thunk [b]
  (case b
    :ok    (thunk-ok [:edge])
    :hang  (thunk-hang)
    :throw (thunk-throw "boom")))

(defn- behavior->labelled [idx b]
  [(str "call-" idx) (behavior->thunk b)])

(defspec apply-kg-calls-invariants 20
  (prop/for-all [behaviors (gen/vector gen-behavior 0 4)]
    (let [calls    (map-indexed behavior->labelled behaviors)
          ok-count (count (filter #{:ok} behaviors))
          deg-cnt  (count (remove #{:ok} behaviors))
          result   (sut/apply-kg-calls calls)]
      (and
        ;; Edge count = ok count (each ok thunk contributes 1 edge).
        (= ok-count (count (:edges result)))
        ;; Warning count = degraded count.
        (= deg-cnt (count (:warnings result)))
        ;; degraded? iff at least one warning.
        (= (pos? deg-cnt) (:degraded? result))
        ;; Always returns a map with the contract keys.
        (every? #(contains? result %) [:edges :warnings :degraded?])
        ;; Never throws — :degraded? is always boolean.
        (boolean? (:degraded? result))))))

;; Latency bound is a separate test, not part of the correctness property —
;; agent-pool scheduling jitter under repeated runs makes a tight latency
;; claim flaky inside `defspec`. Asserted once with a generous ceiling here:
(deftest apply-kg-calls-bounded-latency-test
  (testing "apply-kg-calls returns within a generous ceiling — does NOT hang"
    (let [t0 (System/currentTimeMillis)
          _  (sut/apply-kg-calls
              [["a" (thunk-hang)] ["b" (thunk-hang)] ["c" (thunk-hang)]])
          elapsed (- (System/currentTimeMillis) t0)]
      (is (< elapsed 30000)
          (str "elapsed " elapsed "ms — should complete near 3×timeout, definitely not hang")))))

;; =============================================================================
;; Integration — build-execute-fn end-to-end with hanging KG.
;; =============================================================================
;;
;; Pin: tasks land + kg-degraded? true + warnings emitted, even when
;; every KG batch hangs. Faster KG = ok-edges, no warnings.

(defn- kanban-rows
  "What the kanban collaborator actually received, read back off the
   registered IMemoryStore stub.

   Returns {title -> {:id assigned-id :tags #{tag ...}}}."
  []
  (into {}
        (map (fn [entry]
               [(:title (json/read-str (:content entry) :key-fn keyword))
                {:id (:id entry) :tags (set (:tags entry))}]))
        (vals (mem-stub/entries (sreg/get-store :default)))))

(defn- plan-with
  "Plan whose STEPS ran through the schema layer's own normalizer, so
   build-execute-fn sees the shape production hands it (defaults come from
   the source, not from this test)."
  [steps]
  {:steps (mapv schema/normalize-step steps)
   :decision-id "dec-1"})

(deftest integration-build-execute-fn-kg-hangs-test
  (testing "all KG batches hang → tasks still land, warnings populated"
    (with-redefs [;; Only the KG batches are faked — they are the subject.
                  ;; Kanban creation runs for real against the registered
                  ;; IMemoryStore stub (see the :each fixture).
                  plan-tool/create-plan-decision-edge!    (fn [& _] ((thunk-hang)))
                  plan-tool/create-plan-task-edges!       (fn [& _] ((thunk-hang)))
                  plan-tool/create-task-dependency-edges! (fn [& _] ((thunk-hang)))]
      (let [execute-fn (#'plan-tool/build-execute-fn
                        "/tmp/nodir" "plan-id" "test-project" "test-agent")
            plan      (plan-with [{:id "s1" :title "T1" :depends-on []}
                                  {:id "s2" :title "T2" :depends-on ["s1"]}])
            t0        (System/currentTimeMillis)
            result    (execute-fn {:plan plan})
            elapsed   (- (System/currentTimeMillis) t0)
            rows      (kanban-rows)]
        (is (= #{"T1" "T2"} (set (keys rows)))
            "every step must reach the kanban port regardless of KG status")
        (is (= [(get-in rows ["T1" :id]) (get-in rows ["T2" :id])]
               (:task-ids result))
            "returned ids are the ids the store assigned, in step order")
        (is (contains? (get-in rows ["T1" :tags]) "wave:0"))
        (is (contains? (get-in rows ["T2" :tags]) "wave:1")
            "wave number threads through to the created task's tags")
        (is (true? (:kg-degraded? result)))
        (is (= 3 (count (:kg-warnings result)))
            "one warning per stuck KG batch (decision + plan-task + task-dep)")
        (is (every? #(re-find #"kg-timeout:" %) (:kg-warnings result)))
        (is (= [] (:kg-edges result)))
        (is (< elapsed 5000)
            (str "must complete bounded by timeout, not hang. Got " elapsed "ms"))))))

(deftest integration-build-execute-fn-kg-ok-test
  (testing "KG returns edges → kg-degraded? false, edges flow through"
    (with-redefs [plan-tool/create-plan-decision-edge!    (fn [& _] :decision-edge-id)
                  plan-tool/create-plan-task-edges!       (fn [& _] [:e1 :e2])
                  plan-tool/create-task-dependency-edges! (fn [& _] [:e3])]
      (let [execute-fn (#'plan-tool/build-execute-fn
                        "/tmp/nodir" "plan-id" "test-project" "test-agent")
            plan      (plan-with [{:id "s1" :title "T1" :depends-on []}
                                  {:id "s2" :title "T2" :depends-on ["s1"]}])
            result    (execute-fn {:plan plan})
            rows      (kanban-rows)]
        (is (= #{"T1" "T2"} (set (keys rows))))
        (is (= [(get-in rows ["T1" :id]) (get-in rows ["T2" :id])]
               (:task-ids result)))
        (is (= {"s1" (get-in rows ["T1" :id]) "s2" (get-in rows ["T2" :id])}
               (:step-mapping result))
            "step-id → task-id mapping names the real created tasks")
        (is (false? (:kg-degraded? result)))
        (is (= [] (:kg-warnings result)))
        ;; All three batches contributed edges.
        (is (= #{:e1 :e2 :e3 :decision-edge-id} (set (:kg-edges result))))))))

(deftest integration-build-execute-fn-kg-partial-test
  (testing "one batch hangs, others ok → partial edges + 1 warning"
    (with-redefs [plan-tool/create-plan-decision-edge!    (fn [& _] :ok-decision)
                  plan-tool/create-plan-task-edges!       (fn [& _] ((thunk-hang)))
                  plan-tool/create-task-dependency-edges! (fn [& _] [:dep-edge])]
      (let [execute-fn (#'plan-tool/build-execute-fn
                        "/tmp/nodir" "plan-id" "test-project" "test-agent")
            plan      (plan-with [{:id "s1" :title "T1" :depends-on []}])
            result    (execute-fn {:plan plan})
            rows      (kanban-rows)]
        (is (= #{"T1"} (set (keys rows))))
        (is (= [(get-in rows ["T1" :id])] (:task-ids result)))
        (is (true? (:kg-degraded? result)))
        (is (= 1 (count (:kg-warnings result))))
        (is (re-find #"plan-task-edges" (first (:kg-warnings result))))
        ;; ok batches still contribute.
        (is (= #{:ok-decision :dep-edge} (set (:kg-edges result))))))))
