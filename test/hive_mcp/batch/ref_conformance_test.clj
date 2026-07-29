(ns hive-mcp.batch.ref-conformance-test
  "Every :bx/* $ref provider, checked against `hive-mcp.batch.ref-contract`.

   `hive-mcp.batch` does not implement $ref parsing, resolution or
   ref-dependency validation — it delegates them through the :bx/* extension
   registry. Two implementations therefore exist:

     hive-mcp.test.stub.batch-extensions   always (it ships with the tests)
     hive-knowledge.agent.multi-batch      only when local deps put it on the
                                           classpath — it is NOT in hive-mcp's
                                           committed deps.edn, so cold CI has
                                           it absent as a matter of fact

   This namespace runs ONE corpus, owned by the contract, against every
   provider it can resolve. That is what makes the test-tree port a proven
   stand-in rather than a claimed one: a port that drifts from the provider
   fails here, instead of quietly re-teaching the tests its own semantics.

   `providers-are-not-vacuous` guards the loop itself — a `doseq` over an
   empty provider list or an empty corpus passes without asserting anything,
   which is exactly the failure mode this suite exists to prevent."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.generator :as mg]
            [hive-mcp.batch :as batch]
            [hive-mcp.batch.ref-contract :as contract]
            [hive-mcp.test.stub.batch-extensions :as stub]
            [hive-schemas.test :refer [deftrifecta-from-schema]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Provider resolution
;; =============================================================================

(defn- try-resolve
  "Resolve SYM, or nil when its namespace is not on the classpath."
  [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn- hive-knowledge-provider
  "The production :bx/* provider's seam fns, or nil when hive-knowledge is
   not on the classpath."
  []
  (let [ns-name "hive-knowledge.agent.multi-batch"
        fns     (into {} (map (fn [[k n]] [k (try-resolve (symbol ns-name n))]))
                      {:parse-ref          "parse-ref"
                       :resolve-ref        "resolve-ref"
                       :collect-ref-op-ids "collect-ref-op-ids"
                       :validate-ref-deps  "validate-ref-deps"})]
    (when (every? some? (vals fns)) fns)))

(def ^:private stub-provider
  {:parse-ref          stub/parse-ref
   :resolve-ref        stub/resolve-ref
   :collect-ref-op-ids stub/collect-ref-op-ids
   :validate-ref-deps  stub/validate-ref-deps})

(def providers
  "[{:name label :fns {...}}] — every seam implementation on this classpath."
  (into [{:name "hive-mcp.test.stub.batch-extensions (test-tree port)"
          :fns  stub-provider}]
        (keep identity)
        [(when-let [fns (hive-knowledge-provider)]
           {:name "hive-knowledge.agent.multi-batch (production provider)"
            :fns  fns})]))

(defn- provider-names [] (mapv :name providers))

;; =============================================================================
;; Non-vacuity — the loops below must actually assert something
;; =============================================================================

(deftest providers-are-not-vacuous
  (testing "at least the test-tree port is resolvable"
    (is (seq providers))
    (is (some #(re-find #"test-tree port" (:name %)) providers)
        (str "resolved providers: " (provider-names))))
  (testing "every corpus carries rows, so the per-provider doseqs assert"
    (is (<= 8 (count contract/parse-ref-cases)))
    (is (<= 4 (count contract/resolve-ref-cases)))
    (is (<= 5 (count contract/collect-ref-op-ids-cases)))
    (is (<= 8 (count contract/validate-ref-deps-cases))))
  (testing "the :bx/g corpus contains rows that MUST be refused — a corpus of only-accepted batches would pass against a constantly-[] provider"
    (is (<= 3 (count (filter (fn [[_ ops]] (seq (contract/expected-ref-dep-errors ops)))
                             contract/validate-ref-deps-cases))))))

;; =============================================================================
;; :bx/a — parse-ref
;; =============================================================================

(deftest parse-ref-conformance
  (doseq [{:keys [name fns]} providers
          [label in expected] contract/parse-ref-cases]
    (testing (str name " — " label)
      (is (= expected ((:parse-ref fns) in))))))

(deftest parse-ref-structural-law
  (testing "over generated $ref-shaped and ordinary strings, every provider's parse satisfies the contract's judge"
    (let [candidates (mg/sample contract/RefCandidate {:size 30 :seed 20260729})]
      (is (<= 20 (count candidates)))
      (is (seq (filter contract/ref-string? candidates))
          "the generator must actually produce $ref strings")
      (doseq [{:keys [name fns]} providers
              in candidates]
        (is (contract/parsed-ref-faithful? in ((:parse-ref fns) in))
            (str name " — " (pr-str in)))))))

;; =============================================================================
;; :bx/c — resolve-ref
;; =============================================================================

(deftest resolve-ref-conformance
  (doseq [{:keys [name fns]} providers
          [label parsed results expected] contract/resolve-ref-cases]
    (testing (str name " — " label)
      (is (= expected ((:resolve-ref fns) parsed results))))))

(deftest resolve-ref-missing-op-yields-a-sentinel
  (testing "a missing op-id resolves to a not-found sentinel, never to nil — nil is a legitimate resolution and must stay distinguishable"
    (doseq [{:keys [name fns]} providers]
      (let [v ((:resolve-ref fns) {:op-id "missing" :path ["data"]}
               contract/sample-results)]
        (is (contract/not-found-sentinel? v) name)))))

(deftest installed-provider-not-found-sentinel-is-hive-mcps
  (testing "hive-mcp.batch/classify-op-refs decides broken-ref by `identical?` against batch/ref-not-found, so the INSTALLED provider must return that very keyword — a same-named sentinel from another namespace would leave broken refs unclassified"
    (stub/with-batch-extensions
      (fn []
        (is (identical? batch/ref-not-found
                        (batch/resolve-ref {:op-id "missing" :path ["data"]}
                                           contract/sample-results)))))))

;; =============================================================================
;; :bx/f — collect-ref-op-ids
;; =============================================================================

(deftest collect-ref-op-ids-conformance
  (doseq [{:keys [name fns]} providers
          [label op] contract/collect-ref-op-ids-cases]
    (testing (str name " — " label)
      (is (= (contract/op-ref-op-ids op) ((:collect-ref-op-ids fns) op))))))

;; =============================================================================
;; :bx/g — validate-ref-deps
;; =============================================================================

(deftest validate-ref-deps-conformance
  (doseq [{:keys [name fns]} providers
          [label ops] contract/validate-ref-deps-cases]
    (testing (str name " — " label)
      (let [expected (contract/expected-ref-dep-errors ops)
            actual   ((:validate-ref-deps fns) ops)]
        (is (= expected (set actual)))
        (is (= (count expected) (count actual))
            "one error per violation, no duplicates")))))

(deftest validate-ref-deps-generative-conformance
  (testing "over generated batches, every provider's ref-dep decision is the contract's decision"
    (let [batches (mg/sample contract/RefOps {:size 40 :seed 20260729})]
      (is (<= 30 (count batches)))
      (is (<= 10 (count (filter #(seq (contract/expected-ref-dep-errors %)) batches)))
          "the generator must actually produce undeclared refs")
      (doseq [{:keys [name fns]} providers
              ops batches]
        (is (= (contract/expected-ref-dep-errors ops)
               (set ((:validate-ref-deps fns) ops)))
            (str name " — " (pr-str ops)))))))

;; =============================================================================
;; Schema-synthesized coverage of the port itself
;; =============================================================================

(deftrifecta-from-schema parse-ref-trifecta
  hive-mcp.test.stub.batch-extensions/parse-ref
  {:in        contract/RefCandidate
   :out       contract/MaybeParsedRef
   :rel       contract/parsed-ref-faithful?
   :num-tests 200})

;; `:mutation false` is the macro's documented answer to its own
;; mutants-present guard: `[:vector :string]` admits no schema-derived mutant,
;; so the facet would be vacuous. The teeth live in :rel, which recomputes the
;; decision from the contract's judge rather than from any implementation.
(deftrifecta-from-schema validate-ref-deps-trifecta
  hive-mcp.test.stub.batch-extensions/validate-ref-deps
  {:in        contract/RefOps
   :out       contract/ErrorStrings
   :rel       (fn [ops out] (= (contract/expected-ref-dep-errors ops) (set out)))
   :mutation  false
   :num-tests 200})
