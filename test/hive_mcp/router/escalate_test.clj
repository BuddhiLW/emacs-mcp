(ns hive-mcp.router.escalate-test
  "Pure unit + property tests for `hive-mcp.router.escalate`.

   Pin the escalation invariants:

   1. Doc within budget → input spec returned unchanged.
   2. Doc over budget → heavy-tier spec returned (different
      `:provider/key`, larger `:provider/max-tokens`).
   3. Doc over budget AND already on heavy-tier → input spec returned
      (no infinite escalation; nowhere to escalate to).
   4. Heavy-tier key absent from `:providers` → err."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-dsl.result :as r]
            [hive-mcp.embedder.spec :as spec]
            [hive-mcp.router.escalate :as esc]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def small-spec
  (spec/make {:provider/key :ollama-qwen3-local :provider/impl :ollama
              :provider/model "qwen3-embedding:0.6b" :provider/dim 1024
              :provider/max-tokens 8192}))

(def heavy-spec-fields
  {:impl :venice :model "text-embedding-qwen3-8b"
   :dimension 4096 :max-tokens 28000})

(def config-with-heavy
  {:escalation {:heavy-tier-key :venice-qwen3}
   :providers  {:venice-qwen3 heavy-spec-fields}})

(deftest under-budget-returns-input-spec
  (testing "doc-size <= max-tokens → spec unchanged"
    (let [result (esc/maybe-escalate config-with-heavy small-spec 4000)]
      (is (r/ok? result))
      (is (= :ollama-qwen3-local (:provider/key (:ok result)))))))

(deftest at-budget-not-escalated
  (testing "doc-size == max-tokens → spec unchanged (strict over)"
    (let [result (esc/maybe-escalate config-with-heavy small-spec 8192)]
      (is (r/ok? result))
      (is (= :ollama-qwen3-local (:provider/key (:ok result)))))))

(deftest over-budget-escalates-to-heavy
  (testing "doc-size > max-tokens → heavy-tier spec"
    (let [result (esc/maybe-escalate config-with-heavy small-spec 12000)]
      (is (r/ok? result))
      (is (= :venice-qwen3 (:provider/key (:ok result))))
      (is (= 4096          (:provider/dim (:ok result))))
      ;; Escalated spec accommodates the doc; heavy-tier is the largest
      ;; configured max-tokens.
      (is (>= (:provider/max-tokens (:ok result)) 12000)))))

(deftest already-heavy-no-double-escalate
  (testing "spec is already heavy-tier; over-budget returns same spec"
    (let [heavy (spec/make {:provider/key :venice-qwen3 :provider/impl :venice
                            :provider/model "text-embedding-qwen3-8b"
                            :provider/dim 4096 :provider/max-tokens 28000})
          result (esc/maybe-escalate config-with-heavy heavy 100000)]
      (is (r/ok? result))
      (is (= :venice-qwen3 (:provider/key (:ok result)))))))

(deftest heavy-tier-missing-errs
  (testing "config without heavy-tier provider → escalation err"
    (let [bad-config {:escalation {:heavy-tier-key :nonexistent}
                      :providers  {}}
          result     (esc/maybe-escalate bad-config small-spec 30000)]
      (is (r/err? result))
      (is (= :router/unknown-provider (:error result))))))

(deftest nil-doc-size-no-escalation
  (testing "nil size = caller didn't estimate → spec unchanged"
    (let [result (esc/maybe-escalate config-with-heavy small-spec nil)]
      (is (r/ok? result))
      (is (= :ollama-qwen3-local (:provider/key (:ok result)))))))

;; ---------------------------------------------------------------------------
;; Property test
;; ---------------------------------------------------------------------------

(defspec escalation-monotonicity 200
  (prop/for-all [size (gen/large-integer* {:min 1 :max 200000})]
    (let [result (esc/maybe-escalate config-with-heavy small-spec size)
          spec'  (:ok result)]
      (and (r/ok? result)
           ;; Result max-tokens must accommodate the doc OR be the
           ;; ceiling we cannot exceed (already-heavy invariant).
           (or (>= (:provider/max-tokens spec') size)
               (= :venice-qwen3 (:provider/key spec')))))))
