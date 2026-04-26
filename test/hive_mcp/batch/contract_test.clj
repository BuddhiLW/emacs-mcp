(ns hive-mcp.batch.contract-test
  "Generative contract suite for the `Batchable` protocol.

   Any implementor of `hive-mcp.batch.protocol/Batchable` can exercise
   this suite by rebinding `*batchable-factory*` to a zero-arg fn that
   yields a fresh instance. The suite verifies the LSP contract:

     - Return shape is always `{:success :waves :summary}` (+ optional
       `:errors`), regardless of input.
     - `batch-execute` NEVER throws — pathological / garbage inputs
       surface as `{:success false :errors [...]}`.
     - `:summary` counts agree with per-op `:success` flags.
     - Op ordering is preserved for a no-dep sequential batch.

   A runner test at the bottom binds `*batchable-factory*` to
   `hive-mcp.batch/make-default-runner` and runs the full contract.
   Phase 3 implementors plug in the same way."
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.batch :as batch]
            [hive-mcp.batch.protocol :as bp]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Dynamic factory binding
;; =============================================================================

(declare ^:private default-factory)

(def ^:dynamic *batchable-factory*
  "Zero-arg fn → a fresh Batchable impl. Defaults to the reference
   `DefaultBatchRunner`. Downstream suites rebind to exercise their own
   implementor against the shared contract."
  (fn [] (default-factory)))

(defn- factory []
  (when-not *batchable-factory*
    (throw (ex-info "contract-test requires *batchable-factory* to be bound"
                    {})))
  (*batchable-factory*))

;; =============================================================================
;; Shared helpers / fixtures
;; =============================================================================

(defn- stub-resolve-handler
  "Return a resolve-handler that succeeds for known tool names and fails
   loudly for unknown ones (so unknown-tool tests exercise the failure
   path, not the happy path)."
  ([] (stub-resolve-handler #{"echo" "ok" "probe"}))
  ([known]
   (fn [tool-name]
     (when (contains? known tool-name)
       (fn [args] {:ok tool-name :args args})))))

(defn- base-opts []
  {:resolve-handler (stub-resolve-handler)})

(defn- default-factory
  "Reference Batchable — built from hive-mcp.batch/make-default-runner."
  []
  (batch/make-default-runner
   {:resolve-handler (stub-resolve-handler)}))

(defn- result-shape-ok?
  "Invariant: every batch-execute return must be a map with these keys."
  [r]
  (and (map? r)
       (contains? r :success)
       (contains? r :summary)
       (boolean? (:success r))
       (map? (:summary r))
       (every? #(contains? (:summary r) %) [:total :success :failed :waves])))

;; =============================================================================
;; Generators
;; =============================================================================

(def ^:private gen-tool-name
  (gen/elements ["echo" "ok" "probe" "nonexistent-tool"]))

(def ^:private gen-op
  (gen/let [id   (gen/fmap #(str "op-" %) gen/nat)
            tool gen-tool-name
            cmd  (gen/elements ["run" "go" "noop"])]
    {:id id :tool tool :command cmd}))

(def ^:private gen-ops
  "Well-formed op vectors — unique IDs, no deps."
  (gen/let [ops (gen/vector gen-op 0 6)]
    (->> ops
         (map-indexed (fn [i op] (assoc op :id (str "op-" i))))
         vec)))

(def ^:private gen-garbage
  "Pathological inputs: nils, strings, numbers, empty maps, ops with no
   :tool or :id, deeply nested nonsense. All must be tolerated."
  (gen/frequency
   [[2 (gen/return [])]
    [2 (gen/return nil)]
    [2 (gen/return [{}])]
    [1 (gen/return [{:tool "echo"}])]                 ; missing :id is auto-gen'd
    [1 (gen/return [{:id "x"}])]                      ; missing :tool → validation err
    [1 (gen/return [{:id "dup"} {:id "dup" :tool "echo"}])] ; dup ids
    [1 (gen/vector (gen/one-of [gen/small-integer gen/string-ascii (gen/return nil)]) 0 5)]
    [1 gen-ops]]))

;; =============================================================================
;; Contract deftest — shape invariants
;; =============================================================================

(deftest returns-result-shape
  (testing "batch-execute returns a well-shaped result map for happy input"
    (let [impl (factory)
          r    (bp/batch-execute impl
                                 [{:id "a" :tool "echo" :command "run"}]
                                 (base-opts))]
      (is (result-shape-ok? r))
      (is (contains? r :waves))))
  (testing "batch-execute returns a well-shaped result map on empty ops"
    (let [impl (factory)
          r    (bp/batch-execute impl [] (base-opts))]
      (is (result-shape-ok? r))))
  (testing "batch-execute returns a well-shaped result map on dry-run"
    (let [impl (factory)
          r    (bp/batch-execute impl
                                 [{:id "a" :tool "echo" :command "run"}]
                                 (assoc (base-opts) :dry-run? true))]
      (is (result-shape-ok? r)))))

;; =============================================================================
;; Contract deftest — totality (never throws)
;; =============================================================================

(deftest never-throws
  (testing "batch-execute tolerates pathological inputs without throwing"
    (let [impl (factory)
          garbage-inputs [nil
                          []
                          [{}]
                          [{:id "a"}]
                          [{:tool "echo"}]
                          [{:id "dup"} {:id "dup" :tool "echo"}]
                          [{:id "bad" :tool "nonexistent-tool" :command "x"}]
                          [{:id "x" :tool "echo" :command "y"
                            :depends_on ["does-not-exist"]}]]]
      (doseq [input garbage-inputs]
        (testing (str "input: " (pr-str input))
          (let [r (try (bp/batch-execute impl input (base-opts))
                       (catch Throwable t
                         {:threw? true :ex (ex-message t)}))]
            (is (not (:threw? r)) "must not throw")
            (is (result-shape-ok? r))))))))

;; =============================================================================
;; Contract deftest — summary counts agree with per-op flags
;; =============================================================================

(deftest success-matches-summary
  (testing "(count successful per-op results) == (:success summary)"
    (let [impl (factory)
          ops  [{:id "a" :tool "echo" :command "go"}
                {:id "b" :tool "ok"   :command "go"}
                {:id "c" :tool "nonexistent-tool" :command "go"}]
          r    (bp/batch-execute impl ops (base-opts))
          all-results (->> (vals (:waves r))
                           (mapcat :results))
          succ-cnt    (count (filter :success all-results))
          fail-cnt    (count (remove :success all-results))]
      (is (result-shape-ok? r))
      (is (= succ-cnt (get-in r [:summary :success])))
      (is (= fail-cnt (get-in r [:summary :failed])))
      (is (= (count ops) (get-in r [:summary :total]))))))

;; =============================================================================
;; Contract defspec — order preserved in sequential (no-dep) batches
;; =============================================================================

(defspec order-preserved-in-sequential-batch 50
  (prop/for-all [ops gen-ops]
    (let [impl (factory)
          r    (bp/batch-execute impl ops (base-opts))
          ;; All ops land in wave 1 when no deps — preserve input order
          wave-1-results (get-in r [:waves 1 :results])
          expected-ids   (mapv :id ops)
          actual-ids     (mapv :id wave-1-results)]
      (or (empty? ops)
          (= expected-ids actual-ids)))))

;; =============================================================================
;; Contract defspec — totality under generator pressure
;; =============================================================================

(defspec totality-under-random-input 50
  (prop/for-all [ops gen-garbage]
    (let [impl (factory)
          r    (try (bp/batch-execute impl ops (base-opts))
                    (catch Throwable _ ::threw))]
      (and (not= ::threw r)
           (result-shape-ok? r)))))

;; =============================================================================
;; Schema contract — batch-schema returns a JSONSchema props map
;; =============================================================================

(deftest batch-schema-returns-properties-map
  (testing "batch-schema returns a map with :operations and :dry_run props"
    (let [impl   (factory)
          schema (bp/batch-schema impl)]
      (is (map? schema))
      (is (contains? schema :operations))
      (is (contains? schema :dry_run)))))

;; =============================================================================
;; Runner — bind the default reference impl and run the contract
;; =============================================================================

(deftest default-runner-satisfies-contract
  (testing "DefaultBatchRunner satisfies every contract test above"
    (binding [*batchable-factory*
              (fn []
                (batch/make-default-runner
                 {:resolve-handler (stub-resolve-handler)}))]
      ;; Re-invoke the invariants explicitly. (defspec runs at load; the
      ;; deftests above need an active factory binding.)
      (returns-result-shape)
      (never-throws)
      (success-matches-summary)
      (batch-schema-returns-properties-map))))
