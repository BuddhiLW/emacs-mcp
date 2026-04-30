(ns hive-mcp.plan.generators-test
  "Structural sanity tests for plan generators. Production-shape properties
   (parser roundtrip, plan-to-kanban invariants) live in their own test ns
   and consume these generators."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-mcp.plan.generators :as gen-plan]
            [hive-mcp.plan.schema :as schema]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- sample [g n] (gen/sample g n))

;; --- Scalars ---

(deftest gen-priority-test
  (testing "canonical priority keywords"
    (doseq [p (sample gen-plan/gen-priority 30)]
      (is (contains? (set gen-plan/priorities) p)))))

(deftest gen-priority-lenient-test
  (testing "canonical priorities and aliases"
    (let [ps (sample gen-plan/gen-priority-lenient 100)
          valid (set (concat gen-plan/priorities gen-plan/priority-aliases))]
      (is (every? valid ps))
      (is (some (set gen-plan/priority-aliases) ps)
          "100 samples should hit at least one alias"))))

(deftest gen-estimate-test
  (testing "canonical estimate keywords"
    (doseq [e (sample gen-plan/gen-estimate 30)]
      (is (contains? (set gen-plan/estimates) e)))))

(deftest gen-step-status-test
  (testing "valid step statuses"
    (doseq [s (sample gen-plan/gen-step-status 30)]
      (is (contains? (set gen-plan/step-statuses) s)))))

(deftest gen-source-format-test
  (testing "source format is :edn or :markdown"
    (doseq [f (sample gen-plan/gen-source-format 30)]
      (is (contains? #{:edn :markdown} f)))))

(deftest gen-step-id-test
  (testing "step ids match step-N pattern"
    (doseq [id (sample gen-plan/gen-step-id 30)]
      (is (re-matches #"step-\d+" id)))))

(deftest gen-plan-id-test
  (testing "plan ids are prefixed with plan-"
    (doseq [id (sample gen-plan/gen-plan-id 20)]
      (is (str/starts-with? id "plan-")))))

(deftest gen-file-path-test
  (testing "file paths end in a source extension"
    (doseq [p (sample gen-plan/gen-file-path 30)]
      (is (re-matches #".+\.(clj|cljs|cljc|edn|md)" p)))))

;; --- Step generators ---

(deftest gen-step-minimal-test
  (testing "minimal step has only required fields"
    (doseq [s (sample gen-plan/gen-step-minimal 20)]
      (is (map? s))
      (is (string? (:id s)))
      (is (string? (:title s))))))

(deftest gen-step-full-test
  (testing "full step satisfies schema/valid-step?"
    (doseq [s (sample gen-plan/gen-step-full 20)]
      (is (schema/valid-step? s)
          (str "invalid: " s " — " (schema/explain-step s))))))

(deftest gen-step-with-deps-test
  (testing "deps are a subset of candidate ids"
    (let [candidates #{"step-1" "step-2" "step-3"}]
      (doseq [s (sample (gen-plan/gen-step-with-deps candidates) 30)]
        (is (every? candidates (:depends-on s))))))
  (testing "empty candidate pool yields empty deps"
    (doseq [s (sample (gen-plan/gen-step-with-deps #{}) 10)]
      (is (= [] (:depends-on s))))))

;; --- Plan generators ---

(deftest gen-plan-minimal-test
  (testing "minimal plan has required fields and unique step ids"
    (doseq [p (sample gen-plan/gen-plan-minimal 20)]
      (is (string? (:id p)))
      (is (string? (:title p)))
      (is (vector? (:steps p)))
      (is (pos? (count (:steps p))))
      (let [ids (map :id (:steps p))]
        (is (= (count ids) (count (distinct ids))))))))

(deftest gen-plan-dag-test
  (testing "deps resolve to strictly-earlier step ids (DAG invariant)"
    (doseq [p (sample gen-plan/gen-plan 30)]
      (is (contains? #{:edn :markdown} (:source-format p)))
      (is (pos? (count (:steps p))))
      (let [ids (mapv :id (:steps p))
            index (zipmap ids (range))]
        (doseq [step (:steps p)
                dep  (:depends-on step)]
          (is (contains? index dep) (str "unknown dep: " dep))
          (is (< (index dep) (index (:id step))) (str "forward dep: " dep)))))))

(deftest gen-plan-schema-test
  (testing "gen-plan output satisfies schema/valid-plan? and has no cycles"
    (doseq [p (sample gen-plan/gen-plan 20)]
      (is (schema/valid-plan? p)
          (str "invalid plan: " (schema/explain-plan p)))
      (is (:valid (schema/validate-dependencies p)))
      (is (:valid (schema/detect-cycles p))))))

(deftest gen-plan-with-cycle-test
  (testing "2-step cycle between the first two steps"
    (doseq [p (sample gen-plan/gen-plan-with-cycle 15)]
      (let [[s0 s1] (:steps p)]
        (is (some #{(:id s1)} (:depends-on s0)))
        (is (some #{(:id s0)} (:depends-on s1))))))
  (testing "detect-cycles flags the generated cycle"
    (doseq [p (sample gen-plan/gen-plan-with-cycle 10)]
      (is (false? (:valid (schema/detect-cycles p)))))))
