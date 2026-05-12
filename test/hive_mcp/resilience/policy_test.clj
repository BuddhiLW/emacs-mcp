(ns hive-mcp.resilience.policy-test
  "Tests for `hive-mcp.resilience.policy`.

   Pin the decision-table invariants:

   1. `:err/schema-mismatch` MUST set `:retry? false :escalate? true`
      (the bug-fix — never silently retry a schema mismatch).
   2. `:err/transient` is the only variant with `:retry? true`.
   3. Every `ErrorClass` variant has a row — no fallthrough surprises."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.resilience.policy :as policy]
            [hive-mcp.resilience.protocol :as proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest schema-mismatch-never-retries
  (testing ":err/schema-mismatch → no retry, no kick, escalate to caller"
    (let [d (policy/decide (proto/error-class :err/schema-mismatch
                                              {:message "1804" :details {}}))]
      (is (false? (:retry? d)))
      (is (zero? (:backoff-ms d)))
      (is (true? (:escalate? d))))))

(deftest transient-retries-with-budget
  (testing ":err/transient → retry within default budget"
    (let [d (policy/decide (proto/error-class :err/transient {:message "drop"}))]
      (is (true? (:retry? d)))
      (is (pos? (:backoff-ms d)))
      (is (false? (:escalate? d))))))

(deftest auth-validation-unknown-do-not-retry
  (testing "Non-transient classes share the same posture: surface, no retry"
    (doseq [variant [:err/auth :err/validation :err/unknown]]
      (let [d (policy/decide (proto/error-class variant {:message "x"}))]
        (is (false? (:retry? d)) (str variant " must not retry"))
        (is (true? (:escalate? d)) (str variant " must escalate"))))))

(deftest decide-is-total-over-known-variants
  (testing "Every ErrorClass variant has a decision-table row"
    (doseq [variant [:err/transient :err/schema-mismatch :err/auth
                     :err/validation :err/unknown]]
      (is (some? (policy/decide (proto/error-class variant {:message "x"})))))))

(deftest retry-helper-matches-decide
  (testing "(retry? cls) is sugar over (:retry? (decide cls))"
    (is (true?  (policy/retry? (proto/error-class :err/transient {:message "x"}))))
    (is (false? (policy/retry? (proto/error-class :err/schema-mismatch
                                                   {:message "x" :details {}}))))))
