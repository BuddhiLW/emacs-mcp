(ns hive-mcp.tools.swarm.wave.validation-schema-test
  "Schema-driven coverage for wave pre-flight validation.

   The contract lives in the SOURCE namespace as malli value objects
   (`validation/Tasks`, `validation/PathValidationSummary`, ...). This file
   only names the subject and the schemas it is defined over —
   `hive-schemas.test/deftrifecta-from-schema` synthesizes conformance,
   relation and mutation facets from them, so extending the source contract
   extends the coverage without editing a test.

   The writing entry points (`ensure-parent-dirs!`) are pinned by explicit
   cases rather than generated ones: a generative input would create
   directories on disk."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.swarm.wave.validation :as validation]
            [hive-schemas.test :refer [deftrifecta-from-schema]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; path-validation-summary — total, never throws, self-consistent
;; =============================================================================

(deftrifecta-from-schema path-validation-summary-trifecta
  hive-mcp.tools.swarm.wave.validation/path-validation-summary
  {:in  validation/Tasks
   :out validation/PathValidationSummary
   :rel (fn [_in out]
          (and (= (:invalid-count out) (count (:invalid-paths out)))
               (= (:valid? out) (zero? (:invalid-count out)))))
   :num-tests 200})

;; =============================================================================
;; valid-parent-path? — total predicate, nil-safe
;; =============================================================================

;; `:mutation false` is the macro's documented answer to its own
;; mutants-present guard: a bare `:boolean` output admits no schema-derived
;; mutant, so the facet would be vacuous. Totality is the property worth
;; generating; the decision itself is pinned by the golden cases below.
(deftrifecta-from-schema valid-parent-path-trifecta
  hive-mcp.tools.swarm.wave.validation/valid-parent-path?
  {:in        [:maybe :string]
   :out       :boolean
   :mutation  false
   :num-tests 200})

(deftest valid-parent-path-decisions
  (testing "a nil path is vacuously valid"
    (is (true? (validation/valid-parent-path? nil))))
  (testing "a bare filename has no parent and is valid"
    (is (true? (validation/valid-parent-path? "core.clj"))))
  (testing "an existing parent is valid"
    (is (true? (validation/valid-parent-path? "src/hive_mcp/core.clj"))))
  (testing "a missing parent is invalid"
    (is (false? (validation/valid-parent-path? "/nonexistent-abc/core.clj")))))

;; =============================================================================
;; The throwing / writing entry points — contracts that drifted, pinned
;; =============================================================================

(deftest validate-task-paths-returns-true-on-success
  (testing "validate-task-paths answers true, not nil, when every path is valid"
    (is (true? (validation/validate-task-paths [{:file "src/hive_mcp/core.clj"}])))
    (is (true? (validation/validate-task-paths [])))
    (is (true? (validation/validate-task-paths [{:file nil}]))
        "nil files are skipped, not treated as invalid")))

(deftest validate-task-paths-throws-with-every-invalid-path
  (testing "an invalid path throws ex-info carrying ALL offenders"
    (let [e (try
              (validation/validate-task-paths [{:file "/nonexistent-abc/a.clj"}
                                               {:file "/nonexistent-xyz/b.clj"}])
              nil
              (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "must throw on invalid paths")
      (is (= :validation (:error-type (ex-data e))))
      (is (= 2 (count (:invalid-paths (ex-data e))))))))

(deftest ensure-parent-dirs-returns-created-count
  (testing "ensure-parent-dirs! answers the number of parent dirs it created"
    (is (zero? (validation/ensure-parent-dirs! []))
        "nothing to create")
    (is (zero? (validation/ensure-parent-dirs! [{:file nil}]))
        "nil files are skipped")
    (is (zero? (validation/ensure-parent-dirs! [{:file "src/hive_mcp/core.clj"}]))
        "an existing parent is not recreated")))
