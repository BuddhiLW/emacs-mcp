(ns hive-mcp.agent.agent-definition-golden-test
  "Golden/characterization tests for agent definition schema.

   Pins known-good behavior to detect unintended changes during refactoring.
   Run with UPDATE_GOLDEN=true to regenerate snapshots after intentional changes.

   Golden files: test/golden/agent_definition/"
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [hive-test.golden :refer [deftest-golden]]
            [hive-mcp.agent.agent-definition :as ad]))

;; =============================================================================
;; Test Data
;; =============================================================================

(def ^:private sample-frontmatter-md
  "Sample agent definition markdown with YAML frontmatter.
   Mirrors the example from agent-definition ns docstring."
  "---\nname: reviewer\ndescription: Code review specialist that checks for bugs and style issues.\ntools:\n  - Read\n  - Grep\n  - Glob\nmodel: inherit\nmax-turns: 15\nskills:\n  - simplify\n---\nYou are a code reviewer. Focus on correctness, clarity, and idiomatic style.")

(def ^:private known-good-agent-def
  "A fully-populated valid agent definition for golden testing."
  {:agent-type    "reviewer"
   :description   "Code review specialist"
   :system-prompt "You review code for correctness and style."
   :source        :built-in
   :tools         ["Read" "Grep" "Glob"]
   :model         :inherit
   :max-turns     15})

(def ^:private known-bad-agent-def
  "An invalid agent definition: wrong types, missing required fields."
  {:agent-type 42
   :description nil
   :max-turns -1})

;; =============================================================================
;; Golden: Schema shape snapshot
;; =============================================================================

(deftest-golden schema-entry-keys
  "test/golden/agent_definition/schema-entry-keys.edn"
  (->> (m/entries ad/AgentDefinition)
       (mapv (fn [entry]
               (let [k     (nth entry 0)
                     props (let [p (nth entry 1 nil)]
                             (when (map? p)
                               (select-keys p [:optional :default])))]
                 [k (or props {})])))))

;; =============================================================================
;; Golden: Validate known-good agent definition
;; =============================================================================

(deftest-golden validate-known-good
  "test/golden/agent_definition/validate-known-good.edn"
  (ad/validate known-good-agent-def))

;; =============================================================================
;; Golden: Validate known-bad agent definition (pin error shape)
;; =============================================================================

(deftest-golden validate-known-bad
  "test/golden/agent_definition/validate-known-bad.edn"
  (ad/validate known-bad-agent-def))

;; =============================================================================
;; Golden: Parse sample frontmatter YAML
;; =============================================================================

(deftest-golden parse-frontmatter-sample
  "test/golden/agent_definition/parse-frontmatter-sample.edn"
  (ad/parse-frontmatter sample-frontmatter-md))

;; =============================================================================
;; Golden: frontmatter -> agent-def conversion
;; =============================================================================

(deftest-golden frontmatter-to-agent-def
  "test/golden/agent_definition/frontmatter-to-agent-def.edn"
  (let [{:keys [frontmatter content]} (ad/parse-frontmatter sample-frontmatter-md)]
    (ad/frontmatter->agent-def frontmatter content :project)))
