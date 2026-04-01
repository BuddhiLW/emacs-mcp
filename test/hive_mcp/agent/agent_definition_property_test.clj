(ns hive-mcp.agent.agent-definition-property-test
  "Property-based tests for agent definition schema and operations.

   Tests:
   - Totality: validate never throws on any input
   - Idempotency: merge-definitions with same source is idempotent
   - Round-trip: agent-def -> EDN string -> read-string -> still valid
   - Required fields present after merge
   - Generated valid defs pass validation
   - Merge produces unique agent-types

   Convention: 200 iterations per property (per hive-mcp testing convention)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [clojure.edn :as edn]
            [hive-test.generators.core :as hgen]
            [hive-test.properties :as hprop]
            [hive-mcp.agent.agent-definition :as ad]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-source
  "Generator for valid agent definition sources."
  (gen/elements [:built-in :project :user :plugin]))

(def gen-tool-spec
  "Generator for tool specification vectors."
  (gen/vector hgen/gen-non-blank-string 1 5))

(def gen-agent-def
  "Generator for valid agent definition maps matching the Malli schema.
   Only generates EDN-safe values (string :system-prompt, not fn?)."
  (gen/let [agent-type  hgen/gen-non-blank-string
            description hgen/gen-non-blank-string
            prompt      hgen/gen-non-blank-string
            source      gen-source
            has-tools   gen/boolean
            tools       gen-tool-spec
            has-turns   gen/boolean
            max-turns   (gen/choose 1 100)
            has-model   gen/boolean
            model-str   (gen/elements ["gpt-4" "claude-3" "sonnet" "haiku"])
            use-inherit gen/boolean
            has-skills  gen/boolean
            skills      (gen/vector hgen/gen-non-blank-string 1 3)]
    (cond-> {:agent-type    agent-type
             :description   description
             :system-prompt prompt
             :source        source}
      has-tools              (assoc :tools tools)
      has-turns              (assoc :max-turns max-turns)
      (and has-model
           (not use-inherit)) (assoc :model model-str)
      (and has-model
           use-inherit)       (assoc :model :inherit)
      has-skills              (assoc :skills skills))))

(def gen-agent-def-list
  "Generator for non-empty lists of valid agent definitions (1-6 items)."
  (gen/vector gen-agent-def 1 6))

;; =============================================================================
;; Property: Totality — validate never throws
;; =============================================================================

(hprop/defprop-total validate-never-throws-on-valid
  ad/validate gen-agent-def)

(hprop/defprop-total validate-never-throws-on-any
  ad/validate gen/any-printable)

;; =============================================================================
;; Property: Idempotency — merge-definitions with same source
;; =============================================================================

(defn merge-normalized
  "Merge a single group and sort by :agent-type for deterministic comparison.
   Sorting is necessary because merge-definitions returns (vec (vals map)),
   and PersistentHashMap val ordering is not guaranteed across different
   insertion orders."
  [defs]
  (vec (sort-by :agent-type (ad/merge-definitions defs))))

(hprop/defprop-idempotent merge-definitions-idempotent
  merge-normalized gen-agent-def-list)

;; =============================================================================
;; Property: Round-trip — agent-def -> EDN string -> read-string = identity
;; =============================================================================

(hprop/defprop-roundtrip agent-def-edn-roundtrip
  pr-str edn/read-string gen-agent-def)

;; =============================================================================
;; Property: All required fields present after merge
;; =============================================================================

(defspec required-fields-present-after-merge 200
  (prop/for-all [defs gen-agent-def-list]
    (let [merged (ad/merge-definitions defs)]
      (every? (fn [d]
                (and (contains? d :agent-type)
                     (string? (:agent-type d))
                     (contains? d :description)
                     (string? (:description d))
                     (contains? d :system-prompt)
                     (some? (:system-prompt d))))
              merged))))

;; =============================================================================
;; Property: Generated valid defs pass validation
;; =============================================================================

(defspec generated-defs-are-valid 200
  (prop/for-all [d gen-agent-def]
    (:valid (ad/validate d))))

;; =============================================================================
;; Property: Merge produces unique agent-types
;; =============================================================================

(defspec merge-produces-unique-agent-types 200
  (prop/for-all [defs gen-agent-def-list]
    (let [merged (ad/merge-definitions defs)
          types  (mapv :agent-type merged)]
      (= (count types) (count (set types))))))

;; =============================================================================
;; Property: Higher-priority source wins in merge
;; =============================================================================

(defspec higher-priority-source-wins 200
  (prop/for-all [agent-type hgen/gen-non-blank-string
                 prompt-lo  hgen/gen-non-blank-string
                 prompt-hi  hgen/gen-non-blank-string
                 desc       hgen/gen-non-blank-string]
    (let [lo-def {:agent-type agent-type :description desc
                  :system-prompt prompt-lo :source :built-in}
          hi-def {:agent-type agent-type :description desc
                  :system-prompt prompt-hi :source :user}
          merged (ad/merge-definitions [lo-def] [hi-def])
          winner (first (filter #(= agent-type (:agent-type %)) merged))]
      (= :user (:source winner)))))

;; =============================================================================
;; Deterministic unit tests (edge cases)
;; =============================================================================

(deftest validate-minimal-def
  (testing "Minimal valid agent definition validates"
    (is (:valid (ad/validate {:agent-type    "test"
                              :description   "A test"
                              :system-prompt "You test."})))))

(deftest validate-missing-required-fields
  (testing "Missing required fields produce validation errors"
    (is (not (:valid (ad/validate {}))))
    (is (not (:valid (ad/validate {:agent-type "test"}))))
    (is (not (:valid (ad/validate {:agent-type    "test"
                                   :description   "test"}))))))

(deftest merge-empty-groups
  (testing "Merging empty groups returns empty"
    (is (= [] (ad/merge-definitions [] [])))
    (is (= [] (ad/merge-definitions)))))
