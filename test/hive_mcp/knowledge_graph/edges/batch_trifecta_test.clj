(ns hive-mcp.knowledge-graph.edges.batch-trifecta-test
  "Trifecta tests for hive-mcp.knowledge-graph.edges.batch.

   Covers input-shape invariants (empty-input short-circuit) without hitting
   the DB. Live datahike coverage lives in edges-test and the edge_reader
   test suite — this file targets the extracted leaf's pure-ish surface."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.knowledge-graph.edges.batch :as batch]))

;; =============================================================================
;; Unit: empty-input short-circuit (no DB touch)
;; =============================================================================

(deftest batch-get-edges-from-empty-test
  (testing "batch-get-edges-from returns {} for empty node-ids"
    (is (= {} (batch/batch-get-edges-from [])))
    (is (= {} (batch/batch-get-edges-from [] "some-scope")))
    (is (= {} (batch/batch-get-edges-from nil)))))

(deftest batch-get-edges-to-empty-test
  (testing "batch-get-edges-to returns {} for empty node-ids"
    (is (= {} (batch/batch-get-edges-to [])))
    (is (= {} (batch/batch-get-edges-to [] "some-scope")))
    (is (= {} (batch/batch-get-edges-to nil)))))

(deftest batch-get-co-accessed-empty-test
  (testing "batch-get-co-accessed returns {} for empty entry-ids"
    (is (= {} (batch/batch-get-co-accessed [])))
    (is (= {} (batch/batch-get-co-accessed nil)))))

;; =============================================================================
;; Property: empty-input is {} regardless of shape
;; =============================================================================

(defspec batch-get-edges-from-empty-always-map 20
  (prop/for-all [x (gen/elements [nil [] '() #{}])]
    (= {} (batch/batch-get-edges-from x))))

(defspec batch-get-edges-to-empty-always-map 20
  (prop/for-all [x (gen/elements [nil [] '() #{}])]
    (= {} (batch/batch-get-edges-to x))))

(defspec batch-get-co-accessed-empty-always-map 20
  (prop/for-all [x (gen/elements [nil [] '() #{}])]
    (= {} (batch/batch-get-co-accessed x))))

;; =============================================================================
;; Golden: empty input → {}
;; =============================================================================

(deftest batch-empty-golden-test
  (testing "empty-input response shape snapshot"
    (is (= {} (batch/batch-get-edges-from [])))
    (is (= {} (batch/batch-get-edges-to [])))
    (is (= {} (batch/batch-get-co-accessed [])))))
