(ns hive-mcp.plan.generators
  "Property-test generators for hive-mcp plan structures, steps, and DAG
   dependencies.

   Lives with the plan domain (hive-mcp), not in hive-test: hive-test stays
   generic (OCP — hive-test is closed to domain-specific extensions), while
   plan-specific generators depend on hive-mcp.plan.schema (DIP — the test
   ns depends on the concrete schema it validates against).

   Consumers compose these with hive-test.properties / hive-test.trifecta to
   spec plan parser roundtrip, plan-to-kanban invariants, and normalizer
   coverage."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-mcp.plan.schema :as schema]
            [hive-test.generators.core :as gen-core]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Enumeration value sets (sourced from schema — single source of truth)
;; =============================================================================

(def priorities
  "Canonical priorities — matches schema/Priority enum."
  [:high :medium :low])

(def priority-aliases
  "Aliases that normalize-priority must fold onto canonical values.
   Exercised by gen-priority-lenient for normalizer property coverage."
  [:normal :default :med :critical :urgent :blocker :trivial :minor :none])

(def estimates
  "Canonical estimates — matches schema/Estimate enum."
  [:small :medium :large])

(def step-statuses
  "Lifecycle statuses for a plan step — matches schema/StepStatus."
  [:todo :in-progress :done :blocked])

(def source-formats
  "Plan source formats — matches schema/SourceFormat."
  [:edn :markdown])

;; =============================================================================
;; Scalar generators
;; =============================================================================

(def gen-priority
  "Canonical priority keyword."
  (gen/elements priorities))

(def gen-priority-lenient
  "Priority including common aliases. Feed into tests that assert
   normalize-priority clamps every variant onto a canonical value."
  (gen/one-of [(gen/elements priorities)
               (gen/elements priority-aliases)]))

(def gen-estimate
  "Canonical estimate keyword."
  (gen/elements estimates))

(def gen-step-status
  "Step lifecycle status."
  (gen/elements step-statuses))

(def gen-source-format
  "Plan source format."
  (gen/elements source-formats))

(def gen-step-id
  "Step ids of the form \"step-N\"."
  (gen/fmap #(str "step-" %) (gen/choose 1 999)))

(def gen-plan-id
  "Plan ids of the form \"plan-<slug>\"."
  (gen/fmap #(str "plan-" %) gen-core/gen-non-blank-string))

(def gen-file-path
  "File path with 1-4 segments and a source extension."
  (gen/let [segs (gen/vector gen-core/gen-non-blank-string 1 4)
            ext  (gen/elements ["clj" "cljs" "cljc" "edn" "md"])]
    (str (str/join "/" segs) "." ext)))

(def gen-tag
  "Short tag string (prefix distinguishes from memory tags)."
  (gen/fmap #(str "plan-tag-" %) gen-core/gen-non-blank-string))

;; =============================================================================
;; Step generators
;; =============================================================================

(def gen-step-minimal
  "Smallest valid step — only :id and :title."
  (gen/let [id    gen-step-id
            title gen-core/gen-non-blank-string]
    {:id id :title title}))

(def gen-step-full
  "Step with every optional field populated. :depends-on is empty — use
   gen-step-with-deps when referencing existing steps."
  (gen/let [id          gen-step-id
            title       gen-core/gen-non-blank-string
            description gen-core/gen-non-blank-string
            priority    gen-priority
            estimate    gen-estimate
            files       (gen/vector gen-file-path 0 3)
            tags        (gen/vector gen-tag 0 3)]
    {:id          id
     :title       title
     :description description
     :priority    priority
     :estimate    estimate
     :files       files
     :depends-on  []
     :tags        tags}))

(defn gen-step-with-deps
  "Full step that may depend on a subset of `candidate-ids`.
   Returns gen-step-full unchanged when no candidates are available."
  [candidate-ids]
  (if (empty? candidate-ids)
    gen-step-full
    (gen/let [base gen-step-full
              deps (gen/vector (gen/elements (seq candidate-ids))
                               0
                               (min 3 (count candidate-ids)))]
      (assoc base :depends-on (vec (distinct deps))))))

;; =============================================================================
;; Plan generators
;; =============================================================================

(defn- gen-dag-steps
  "Generate n steps forming a DAG — step i may only depend on steps 0..i-1,
   so cycles are impossible by construction."
  [n]
  (let [ids (mapv #(str "step-" (inc %)) (range n))]
    (apply gen/tuple
           (map-indexed
            (fn [i step-id]
              (let [prior (set (take i ids))]
                (gen/fmap #(assoc % :id step-id)
                          (gen-step-with-deps prior))))
            ids))))

(def gen-plan-minimal
  "Plan with only the required schema fields. Step ids are renumbered to
   avoid collisions from the underlying gen-step-id range."
  (gen/let [id    gen-plan-id
            title gen-core/gen-non-blank-string
            n     (gen/choose 1 3)
            steps (gen/vector gen-step-minimal n)]
    {:id    id
     :title title
     :steps (vec (map-indexed #(assoc %2 :id (str "step-" (inc %1))) steps))}))

(def gen-plan
  "Plan with a valid DAG of 1-5 steps and all optional fields populated.
   Guaranteed to satisfy schema/valid-plan? and schema/validate-dependencies."
  (gen/let [id            gen-plan-id
            title         gen-core/gen-non-blank-string
            description   gen-core/gen-non-blank-string
            source-format gen-source-format
            tags          (gen/vector gen-tag 0 3)
            n             (gen/choose 1 5)
            steps         (gen-dag-steps n)]
    {:id            id
     :title         title
     :description   description
     :steps         (vec steps)
     :source-format source-format
     :tags          tags}))

(def gen-plan-with-cycle
  "Plan with a deliberate 2-step cycle between the first two steps — for
   negative tests of schema/detect-cycles. NOT a valid DAG."
  (gen/let [base (gen/such-that #(>= (count (:steps %)) 2) gen-plan)]
    (let [[s0 s1 & rest-steps] (:steps base)
          s0* (assoc s0 :depends-on [(:id s1)])
          s1* (assoc s1 :depends-on [(:id s0)])]
      (assoc base :steps (into [s0* s1*] rest-steps)))))

;; =============================================================================
;; Schema-validated generators (sanity contracts)
;; =============================================================================

(def gen-valid-plan
  "gen-plan filtered through the schema validator. Expensive — prefer
   gen-plan unless you're specifically hardening against schema drift."
  (gen/such-that schema/valid-plan? gen-plan 25))

;; =============================================================================
;; Rendered-form generators (plan + rendered string paired)
;; =============================================================================

(defn- render-step-edn-overlay
  "Render a step's metadata as a leading EDN map for markdown hybrid mode."
  [step]
  (pr-str (select-keys step [:id :priority :estimate :depends-on
                             :files :tags])))

(defn- render-plan-md-hybrid
  "Render plan: `# Title` + per-step `## Title` followed by inline EDN map
   `{:id ... :priority ... :depends-on [...] :files [...]}` carrying
   metadata, then prose `:description`."
  [plan]
  (str "# " (:title plan) "\n\n"
       (->> (:steps plan)
            (map (fn [step]
                   (str "## " (:title step) "\n"
                        (render-step-edn-overlay step) "\n"
                        (when (:description step)
                          (str (:description step) "\n")))))
            (str/join "\n"))))

(def gen-plan-md-with-edn-overlay
  "Pair of [plan rendered-markdown]. The markdown renders each step as an
   H2 header followed by an inline EDN metadata map, then prose description.
   Feeds the hybrid-mode parser property tests."
  (gen/fmap (fn [plan] [plan (render-plan-md-hybrid plan)])
            gen-plan))
