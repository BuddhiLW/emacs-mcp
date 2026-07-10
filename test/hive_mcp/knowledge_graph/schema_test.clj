(ns hive-mcp.knowledge-graph.schema-test
  "Unit tests for Knowledge Graph schema definitions.

   Tests cover:
   - Abstraction level validation
   - Type to abstraction level derivation
   - Disc entity schema fields
   - Full schema combination"
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.schema :as schema]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Abstraction Level Validation Tests
;; =============================================================================

(deftest valid-abstraction-level-test
  (testing "valid-abstraction-level? accepts levels 1-4"
    (is (true? (schema/valid-abstraction-level? 1)))
    (is (true? (schema/valid-abstraction-level? 2)))
    (is (true? (schema/valid-abstraction-level? 3)))
    (is (true? (schema/valid-abstraction-level? 4)))))

(deftest valid-abstraction-level-rejects-invalid-test
  (testing "valid-abstraction-level? rejects invalid values"
    (is (false? (schema/valid-abstraction-level? 0)))  ; L0 is runtime, not stored
    (is (false? (schema/valid-abstraction-level? 5)))
    (is (false? (schema/valid-abstraction-level? -1)))
    (is (false? (schema/valid-abstraction-level? nil)))
    (is (false? (schema/valid-abstraction-level? "2")))))

(deftest valid-node-id-test
  (testing "valid-node-id? accepts non-blank strings"
    (is (true? (schema/valid-node-id? "20260629-abc")))
    (is (true? (schema/valid-node-id? "x"))))
  (testing "valid-node-id? rejects non-strings and blanks (poison-datom guard)"
    (is (false? (schema/valid-node-id? nil)))
    (is (false? (schema/valid-node-id? "")))
    (is (false? (schema/valid-node-id? "   ")))
    (is (false? (schema/valid-node-id? 42)))
    (is (false? (schema/valid-node-id? {:success? false :reconnecting? true})))))

(deftest abstraction-level-keyword-test
  (testing "abstraction-level-keyword converts integer to keyword"
    (is (= :L1 (schema/abstraction-level-keyword 1)))
    (is (= :L2 (schema/abstraction-level-keyword 2)))
    (is (= :L3 (schema/abstraction-level-keyword 3)))
    (is (= :L4 (schema/abstraction-level-keyword 4)))))

(deftest abstraction-level-keyword-invalid-test
  (testing "abstraction-level-keyword returns nil for invalid"
    (is (nil? (schema/abstraction-level-keyword 0)))
    (is (nil? (schema/abstraction-level-keyword 5)))
    (is (nil? (schema/abstraction-level-keyword nil)))))

(deftest abstraction-level-info-test
  (testing "abstraction-level-info returns full info map"
    (let [l1-info (schema/abstraction-level-info 1)]
      (is (= 1 (:level l1-info)))
      (is (= "Disc" (:name l1-info))))
    (let [l4-info (schema/abstraction-level-info 4)]
      (is (= 4 (:level l4-info)))
      (is (= "Intent" (:name l4-info))))))

;; =============================================================================
;; Type to Abstraction Level Derivation Tests
;; =============================================================================

(deftest derive-abstraction-level-semantic-types-test
  (testing "derive-abstraction-level returns L2 for semantic types"
    (is (= 2 (schema/derive-abstraction-level "snippet")))
    (is (= 2 (schema/derive-abstraction-level "note")))
    (is (= 2 (schema/derive-abstraction-level "doc")))
    (is (= 2 (schema/derive-abstraction-level "todo")))))

(deftest derive-abstraction-level-pattern-types-test
  (testing "derive-abstraction-level returns L3 for pattern types"
    (is (= 3 (schema/derive-abstraction-level "convention")))
    (is (= 3 (schema/derive-abstraction-level "pattern")))
    (is (= 3 (schema/derive-abstraction-level "lesson")))
    (is (= 3 (schema/derive-abstraction-level "workflow")))))

(deftest derive-abstraction-level-intent-types-test
  (testing "derive-abstraction-level returns L4 for intent types"
    (is (= 4 (schema/derive-abstraction-level "decision")))
    (is (= 4 (schema/derive-abstraction-level "axiom")))
    (is (= 4 (schema/derive-abstraction-level "principle")))))

(deftest derive-abstraction-level-unknown-defaults-test
  (testing "derive-abstraction-level defaults to L2 for unknown types"
    (is (= 2 (schema/derive-abstraction-level "unknown-type")))
    (is (= 2 (schema/derive-abstraction-level nil)))
    (is (= 2 (schema/derive-abstraction-level "")))))

;; =============================================================================
;; Disc Schema Tests
;; =============================================================================

(deftest disc-schema-has-required-fields-test
  (testing "disc-schema contains all required fields"
    (is (contains? schema/disc-schema :disc/path))
    (is (contains? schema/disc-schema :disc/content-hash))
    (is (contains? schema/disc-schema :disc/analyzed-at))
    (is (contains? schema/disc-schema :disc/git-commit))
    (is (contains? schema/disc-schema :disc/project-id))))

(deftest disc-path-is-unique-identity-test
  (testing "disc/path has unique identity constraint"
    (is (= :db.unique/identity
           (get-in schema/disc-schema [:disc/path :db/unique])))))

;; =============================================================================
;; Knowledge Schema Tests
;; =============================================================================

(deftest knowledge-schema-has-grounding-fields-test
  (testing "knowledge-schema contains all grounding fields"
    (is (contains? schema/knowledge-schema :knowledge/abstraction-level))
    (is (contains? schema/knowledge-schema :knowledge/grounded-at))
    (is (contains? schema/knowledge-schema :knowledge/grounded-from))
    (is (contains? schema/knowledge-schema :knowledge/gaps))
    (is (contains? schema/knowledge-schema :knowledge/source-hash))))

(deftest knowledge-gaps-has-many-cardinality-test
  (testing "knowledge/gaps has cardinality many"
    (is (= :db.cardinality/many
           (get-in schema/knowledge-schema [:knowledge/gaps :db/cardinality])))))

;; =============================================================================
;; Full Schema Tests
;; =============================================================================

(deftest full-schema-includes-all-schemas-test
  (testing "full-schema includes kg-schema, knowledge-schema, and disc-schema"
    (let [full (schema/full-schema)]
      ;; KG edge fields
      (is (contains? full :kg-edge/id))
      (is (contains? full :kg-edge/from))
      (is (contains? full :kg-edge/relation))
      ;; Knowledge fields
      (is (contains? full :knowledge/abstraction-level))
      (is (contains? full :knowledge/grounded-at))
      ;; Disc fields
      (is (contains? full :disc/path))
      (is (contains? full :disc/content-hash)))))

;; =============================================================================
;; Relation Type Registry Tests
;; =============================================================================
;;
;; NOTE: The synthetic KG schema (valid-synthetic-type?, synthetic-schema,
;; :kg-synthetic/*) moved out of hive-mcp into the hive-knowledge addon
;; (hive-knowledge.graph.synthetic), which registers it via
;; schema/register-kg-schema!. Those tests now live in hive-knowledge.

(deftest relation-types-registry-test
  (testing "relation-types returns core types"
    (is (contains? (schema/relation-types) :implements))
    (is (contains? (schema/relation-types) :depends-on)))
  (testing "register-relation-type! adds to relation-types"
    (schema/register-relation-type! :test-relation)
    (is (contains? (schema/relation-types) :test-relation))))

(deftest relates-and-normalize-predicate-test
  (testing ":relates is a core relation (open semantic lane)"
    (is (contains? (schema/relation-types) :relates))
    (is (schema/valid-relation? :relates)))
  (testing "normalize-predicate → kebab-case token"
    (is (= "causes"  (schema/normalize-predicate "Causes")))
    (is (= "part-of" (schema/normalize-predicate "  part of  ")))
    (is (= "leads-to" (schema/normalize-predicate "leads_to")))
    (is (= "is-a"    (schema/normalize-predicate "is   a")))
    (is (= "weird"   (schema/normalize-predicate "--weird--"))))
  (testing "normalize-predicate → nil for nil/blank/non-string"
    (is (nil? (schema/normalize-predicate nil)))
    (is (nil? (schema/normalize-predicate "")))
    (is (nil? (schema/normalize-predicate "   ")))
    (is (nil? (schema/normalize-predicate :not-a-string)))))