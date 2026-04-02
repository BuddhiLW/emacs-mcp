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

;; =============================================================================
;; Record: AgentDef (defrecord boundary tests)
;; =============================================================================

(deftest make-agent-def-creates-record
  (testing "make-agent-def returns an AgentDef record"
    (let [m {:agent-type "test" :description "A test" :system-prompt "You test."}
          r (ad/make-agent-def m)]
      (is (ad/agent-def? r))
      (is (instance? hive_mcp.agent.agent_definition.AgentDef r)))))

(deftest make-agent-def-applies-defaults
  (testing "make-agent-def applies :source and :hivemind-role defaults"
    (let [r (ad/make-agent-def {:agent-type "x" :description "d" :system-prompt "p"})]
      (is (= :built-in (:source r)))
      (is (= :role/standalone (:hivemind-role r))))))

(deftest make-agent-def-preserves-explicit-values
  (testing "make-agent-def preserves explicitly provided values"
    (let [r (ad/make-agent-def {:agent-type    "explorer"
                                :description   "Fast search"
                                :system-prompt "You explore."
                                :source        :project
                                :tools         ["Read" "Grep"]
                                :model         :inherit
                                :max-turns     10
                                :hivemind-role :role/worker})]
      (is (= "explorer" (:agent-type r)))
      (is (= :project (:source r)))
      (is (= ["Read" "Grep"] (:tools r)))
      (is (= :inherit (:model r)))
      (is (= 10 (:max-turns r)))
      (is (= :role/worker (:hivemind-role r))))))

(deftest make-agent-def-rejects-invalid
  (testing "make-agent-def throws on invalid map"
    (is (thrown? clojure.lang.ExceptionInfo
                (ad/make-agent-def {:agent-type 42})))))

(deftest record-keyword-access-matches-map
  (testing "Keyword access on record matches original map"
    (let [m {:agent-type "test" :description "d" :system-prompt "p" :source :user}
          r (ad/make-agent-def m)]
      (is (= (:agent-type m) (:agent-type r)))
      (is (= (:description m) (:description r)))
      (is (= (:system-prompt m) (:system-prompt r)))
      (is (= (:source m) (:source r))))))

(deftest roundtrip-map-record-map
  (testing "map → record → map roundtrip preserves data"
    (let [m {:agent-type    "test"
             :description   "A test"
             :system-prompt "You test."
             :source        :project
             :tools         ["Read"]
             :max-turns     5}
          r (ad/make-agent-def m)
          m' (ad/->map r)]
      ;; m' has defaults applied that m didn't have
      (is (= (:agent-type m) (:agent-type m')))
      (is (= (:description m) (:description m')))
      (is (= (:tools m) (:tools m')))
      (is (= (:source m) (:source m')))
      ;; Validate m' is still valid
      (is (:valid (ad/validate m'))))))

(deftest validate-accepts-records
  (testing "validate works on AgentDef records"
    (let [r (ad/make-agent-def {:agent-type "x" :description "d" :system-prompt "p"})]
      (is (:valid (ad/validate r)))
      (is (ad/valid? r))
      (is (nil? (ad/explain r))))))

(deftest to-map-strips-nils
  (testing "->map strips nil optional fields"
    (let [r (ad/make-agent-def {:agent-type "x" :description "d" :system-prompt "p"})
          m (ad/->map r)]
      (is (not (contains? m :tools)))
      (is (not (contains? m :hooks)))
      (is (not (contains? m :mcp-servers)))
      (is (contains? m :agent-type))
      (is (contains? m :source)))))

(deftest to-map-noop-on-plain-map
  (testing "->map is no-op on plain maps"
    (let [m {:agent-type "x" :description "d" :system-prompt "p"}]
      (is (identical? m (ad/->map m))))))

;; =============================================================================
;; Property: make-agent-def roundtrip
;; =============================================================================

(defspec make-agent-def-roundtrip 200
  (prop/for-all [d gen-agent-def]
    (let [r  (ad/make-agent-def d)
          m' (ad/->map r)]
      (and (ad/agent-def? r)
           (:valid (ad/validate m'))
           (= (:agent-type d) (:agent-type m'))
           (= (:description d) (:description m'))
           (= (:system-prompt d) (:system-prompt m'))))))
