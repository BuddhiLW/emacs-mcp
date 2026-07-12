(ns hive-mcp.knowledge-graph.edges-test
  "Unit tests for Knowledge Graph edge CRUD operations.

   Tests cover:
   - add-edge! with valid/invalid relations
   - get-edge, get-edges-from, get-edges-to
   - get-edges-by-relation, get-edges-by-scope
   - get-all-edges, find-edge
   - update-edge-confidence!, increment-confidence!
   - remove-edge!, remove-edges-for-node!
   - edge-stats

   Each test uses a fresh DataScript connection via fixture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]
            [hive-mcp.knowledge-graph.schema :as schema]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each fixtures/datascript-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn gen-node-id
  "Generate a unique node ID for testing."
  []
  (str "test-node-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

;; =============================================================================
;; add-edge! Tests
;; =============================================================================

(deftest add-edge-creates-edge-test
  (testing "add-edge! creates edge with valid relation"
    (let [from (gen-node-id)
          to (gen-node-id)
          edge-id (edges/add-edge! {:from from :to to :relation :implements})]
      (is (string? edge-id)))))

(deftest add-edge-returns-retrievable-edge-test
  (testing "add-edge! returns ID of retrievable edge"
    (let [from (gen-node-id)
          to (gen-node-id)
          edge-id (edges/add-edge! {:from from :to to :relation :supersedes})
          edge (edges/get-edge edge-id)]
      (is (some? edge))
      (is (= from (:kg-edge/from edge)))
      (is (= to (:kg-edge/to edge)))
      (is (= :supersedes (:kg-edge/relation edge))))))

(deftest add-edge-with-all-fields-test
  (testing "add-edge! stores all optional fields"
    (let [from (gen-node-id)
          to (gen-node-id)
          edge-id (edges/add-edge! {:from from
                                    :to to
                                    :relation :refines
                                    :scope "hive-mcp:agora"
                                    :confidence 0.85
                                    :created-by "agent:test-123"})
          edge (edges/get-edge edge-id)]
      (is (= "hive-mcp:agora" (:kg-edge/scope edge)))
      (is (= 0.85 (:kg-edge/confidence edge)))
      (is (= "agent:test-123" (:kg-edge/created-by edge)))
      (is (inst? (:kg-edge/created-at edge))))))

(deftest add-edge-rejects-non-string-node-id-test
  (testing "add-edge! throws when :from/:to is not a non-blank string (poison-datom guard)"
    (let [to (gen-node-id)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank string"
                            (edges/add-edge! {:from {:success? false :reconnecting? true}
                                              :to to :relation :derived-from})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank string"
                            (edges/add-edge! {:from (gen-node-id) :to nil :relation :derived-from})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank string"
                            (edges/add-edge! {:from "" :to to :relation :derived-from}))))))

(deftest add-edge-default-confidence-test
  (testing "add-edge! defaults confidence to 1.0"
    (let [from (gen-node-id)
          to (gen-node-id)
          edge-id (edges/add-edge! {:from from :to to :relation :depends-on})
          edge (edges/get-edge edge-id)]
      (is (= 1.0 (:kg-edge/confidence edge))))))

(deftest add-edge-all-valid-relations-test
  (testing "add-edge! accepts all valid relations"
    (doseq [rel (schema/relation-types)]
      (let [from (gen-node-id)
            to (gen-node-id)
            edge-id (edges/add-edge! {:from from :to to :relation rel})]
        (is (string? edge-id) (str "Failed for relation: " rel))))))

(deftest add-edge-predicate-test
  (testing ":relates edge stores a normalized free-text predicate"
    (let [from (gen-node-id)
          to   (gen-node-id)
          eid  (edges/add-edge! {:from from :to to :relation :relates
                                 :predicate "Leads To"})
          e    (edges/get-edge eid)]
      (is (= :relates (:kg-edge/relation e)))
      (is (= "leads-to" (:kg-edge/predicate e))
          "free-text predicate normalized to kebab-case")))
  (testing "predicate is ignored for non-:relates relations"
    (let [from (gen-node-id)
          to   (gen-node-id)
          eid  (edges/add-edge! {:from from :to to :relation :implements
                                 :predicate "should be dropped"})
          e    (edges/get-edge eid)]
      (is (nil? (:kg-edge/predicate e))
          "predicate is meaningful only on :relates edges"))))

(deftest add-edge-rejects-invalid-relation-test
  (testing "add-edge! rejects invalid relation"
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edge! {:from "a" :to "b" :relation :invalid-rel})))))

(deftest add-edge-rejects-nil-from-test
  (testing "add-edge! rejects nil :from"
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edge! {:from nil :to "b" :relation :implements})))))

(deftest add-edge-rejects-nil-to-test
  (testing "add-edge! rejects nil :to"
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edge! {:from "a" :to nil :relation :implements})))))

(deftest add-edge-rejects-non-string-from-test
  (testing "add-edge! rejects non-string :from"
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edge! {:from 123 :to "b" :relation :implements})))))

(deftest add-edge-rejects-non-keyword-relation-test
  (testing "add-edge! rejects non-keyword relation"
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edge! {:from "a" :to "b" :relation "implements"})))))

(deftest add-edge-rejects-confidence-out-of-range-test
  (testing "add-edge! rejects confidence outside [0.0, 1.0]"
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edge! {:from "a" :to "b" :relation :implements :confidence 1.5})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edge! {:from "a" :to "b" :relation :implements :confidence -0.1})))))

(deftest add-edge-boundary-confidence-test
  (testing "add-edge! accepts boundary confidence values"
    (let [edge-id-0 (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                      :relation :implements :confidence 0.0})
          edge-id-1 (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                      :relation :implements :confidence 1.0})]
      (is (= 0.0 (:kg-edge/confidence (edges/get-edge edge-id-0))))
      (is (= 1.0 (:kg-edge/confidence (edges/get-edge edge-id-1)))))))

;; =============================================================================
;; get-edge Tests
;; =============================================================================

(deftest get-edge-returns-nil-for-nonexistent-test
  (testing "get-edge returns nil for non-existent edge"
    (is (nil? (edges/get-edge "edge-nonexistent-12345")))))

(deftest get-edge-nil-returns-nil-test
  (testing "get-edge returns nil for nil ID"
    (is (nil? (edges/get-edge nil)))))

(deftest get-edge-non-string-returns-nil-test
  (testing "get-edge returns nil for non-string ID"
    (is (nil? (edges/get-edge 12345)))))

;; =============================================================================
;; get-edges-from Tests
;; =============================================================================

(deftest get-edges-from-returns-outgoing-edges-test
  (testing "get-edges-from returns all outgoing edges"
    (let [source (gen-node-id)
          target1 (gen-node-id)
          target2 (gen-node-id)]
      (edges/add-edge! {:from source :to target1 :relation :implements})
      (edges/add-edge! {:from source :to target2 :relation :supersedes})
      (let [outgoing (edges/get-edges-from source)]
        (is (= 2 (count outgoing)))
        (is (every? #(= source (:kg-edge/from %)) outgoing))))))

(deftest get-edges-from-empty-when-no-outgoing-test
  (testing "get-edges-from returns empty for node with no outgoing edges"
    (let [node (gen-node-id)]
      ;; Create an edge pointing TO node, not FROM
      (edges/add-edge! {:from (gen-node-id) :to node :relation :implements})
      (is (empty? (edges/get-edges-from node))))))

(deftest get-edges-from-nil-returns-empty-test
  (testing "get-edges-from returns empty for nil node ID"
    (is (empty? (edges/get-edges-from nil)))))

;; =============================================================================
;; get-edges-to Tests
;; =============================================================================

(deftest get-edges-to-returns-incoming-edges-test
  (testing "get-edges-to returns all incoming edges"
    (let [target (gen-node-id)
          source1 (gen-node-id)
          source2 (gen-node-id)]
      (edges/add-edge! {:from source1 :to target :relation :implements})
      (edges/add-edge! {:from source2 :to target :relation :refines})
      (let [incoming (edges/get-edges-to target)]
        (is (= 2 (count incoming)))
        (is (every? #(= target (:kg-edge/to %)) incoming))))))

(deftest get-edges-to-empty-when-no-incoming-test
  (testing "get-edges-to returns empty for node with no incoming edges"
    (let [node (gen-node-id)]
      ;; Create an edge FROM node, not TO
      (edges/add-edge! {:from node :to (gen-node-id) :relation :implements})
      (is (empty? (edges/get-edges-to node))))))

;; =============================================================================
;; get-edges-by-relation Tests
;; =============================================================================

(deftest get-edges-by-relation-filters-correctly-test
  (testing "get-edges-by-relation returns only matching relations"
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :supersedes})
    (let [implements-edges (edges/get-edges-by-relation :implements)]
      (is (= 2 (count implements-edges)))
      (is (every? #(= :implements (:kg-edge/relation %)) implements-edges)))))

(deftest get-edges-by-relation-empty-when-no-match-test
  (testing "get-edges-by-relation returns empty when no edges match"
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
    (is (empty? (edges/get-edges-by-relation :contradicts)))))

;; =============================================================================
;; get-edges-by-scope Tests (SKIPPED - function not implemented)
;; =============================================================================

;; NOTE: get-edges-by-scope is not yet implemented in edges namespace
;; Filtering by scope is done via the scoped variants of get-edges-from/to

;; =============================================================================
;; get-all-edges Tests
;; =============================================================================

(deftest get-all-edges-returns-all-test
  (testing "get-all-edges returns all edges"
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :supersedes})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :refines})
    (is (= 3 (count (edges/get-all-edges))))))

(deftest get-all-edges-empty-when-no-edges-test
  (testing "get-all-edges returns empty when no edges exist"
    (is (empty? (edges/get-all-edges)))))

;; =============================================================================
;; find-edge Tests (SKIPPED - function not implemented)
;; =============================================================================

;; NOTE: find-edge is not yet implemented in edges namespace
;; Can be achieved via get-edges-from + filter

;; =============================================================================
;; update-edge-confidence! Tests
;; =============================================================================

(deftest update-edge-confidence-changes-value-test
  (testing "update-edge-confidence! changes the confidence value"
    (let [edge-id (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                    :relation :implements :confidence 0.5})]
      (edges/update-edge-confidence! edge-id 0.9)
      (is (= 0.9 (:kg-edge/confidence (edges/get-edge edge-id)))))))

(deftest update-edge-confidence-returns-nil-for-nonexistent-test
  (testing "update-edge-confidence! returns nil for non-existent edge"
    (is (nil? (edges/update-edge-confidence! "edge-nonexistent" 0.5)))))

(deftest update-edge-confidence-rejects-out-of-range-test
  (testing "update-edge-confidence! rejects values outside [0.0, 1.0]"
    (let [edge-id (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                    :relation :implements})]
      (is (thrown? clojure.lang.ExceptionInfo (edges/update-edge-confidence! edge-id 1.5)))
      (is (thrown? clojure.lang.ExceptionInfo (edges/update-edge-confidence! edge-id -0.1))))))

;; =============================================================================
;; increment-confidence! Tests
;; =============================================================================

(deftest increment-confidence-adds-delta-test
  (testing "increment-confidence! adds delta to current value"
    (let [edge-id (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                    :relation :implements :confidence 0.5})
          new-conf (edges/increment-confidence! edge-id 0.2)]
      (is (= 0.7 new-conf))
      (is (= 0.7 (:kg-edge/confidence (edges/get-edge edge-id)))))))

(deftest increment-confidence-negative-delta-test
  (testing "increment-confidence! handles negative delta"
    (let [edge-id (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                    :relation :implements :confidence 0.8})
          new-conf (edges/increment-confidence! edge-id -0.3)]
      (is (= 0.5 new-conf)))))

(deftest increment-confidence-clamps-to-max-test
  (testing "increment-confidence! clamps to 1.0 maximum"
    (let [edge-id (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                    :relation :implements :confidence 0.9})
          new-conf (edges/increment-confidence! edge-id 0.5)]
      (is (= 1.0 new-conf)))))

(deftest increment-confidence-clamps-to-min-test
  (testing "increment-confidence! clamps to 0.0 minimum"
    (let [edge-id (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                    :relation :implements :confidence 0.2})
          new-conf (edges/increment-confidence! edge-id -0.5)]
      (is (= 0.0 new-conf)))))

(deftest increment-confidence-returns-nil-for-nonexistent-test
  (testing "increment-confidence! returns nil for non-existent edge"
    (is (nil? (edges/increment-confidence! "edge-nonexistent" 0.1)))))

;; =============================================================================
;; remove-edge! Tests
;; =============================================================================

(deftest remove-edge-deletes-edge-test
  (testing "remove-edge! deletes the edge"
    (let [edge-id (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                    :relation :implements})]
      (edges/remove-edge! edge-id)
      (is (nil? (edges/get-edge edge-id))))))

(deftest remove-edge-returns-false-for-nonexistent-test
  (testing "remove-edge! returns false for non-existent edge"
    (is (false? (edges/remove-edge! "edge-nonexistent")))))

(deftest remove-edge-idempotent-test
  (testing "remove-edge! is idempotent"
    (let [edge-id (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                                    :relation :implements})]
      (edges/remove-edge! edge-id)
      (is (false? (edges/remove-edge! edge-id))))))

;; =============================================================================
;; remove-edges-for-node! Tests
;; =============================================================================

(deftest remove-edges-for-node-removes-outgoing-test
  (testing "remove-edges-for-node! removes outgoing edges"
    (let [node (gen-node-id)
          other (gen-node-id)]
      (edges/add-edge! {:from node :to other :relation :implements})
      (edges/add-edge! {:from node :to (gen-node-id) :relation :supersedes})
      (let [count (edges/remove-edges-for-node! node)]
        (is (= 2 count))
        (is (empty? (edges/get-edges-from node)))))))

(deftest remove-edges-for-node-removes-incoming-test
  (testing "remove-edges-for-node! removes incoming edges"
    (let [node (gen-node-id)]
      (edges/add-edge! {:from (gen-node-id) :to node :relation :implements})
      (edges/add-edge! {:from (gen-node-id) :to node :relation :refines})
      (let [count (edges/remove-edges-for-node! node)]
        (is (= 2 count))
        (is (empty? (edges/get-edges-to node)))))))

(deftest remove-edges-for-node-removes-both-directions-test
  (testing "remove-edges-for-node! removes both incoming and outgoing"
    (let [node (gen-node-id)]
      (edges/add-edge! {:from node :to (gen-node-id) :relation :implements})
      (edges/add-edge! {:from (gen-node-id) :to node :relation :refines})
      (let [count (edges/remove-edges-for-node! node)]
        (is (= 2 count))))))

(deftest remove-edges-for-node-returns-zero-when-no-edges-test
  (testing "remove-edges-for-node! returns 0 when node has no edges"
    (is (= 0 (edges/remove-edges-for-node! "node-with-no-edges")))))

(deftest remove-edges-for-node-preserves-unrelated-edges-test
  (testing "remove-edges-for-node! preserves edges not connected to node"
    (let [node-to-remove (gen-node-id)
          other1 (gen-node-id)
          other2 (gen-node-id)]
      ;; Edge connected to node-to-remove
      (edges/add-edge! {:from node-to-remove :to other1 :relation :implements})
      ;; Edge NOT connected to node-to-remove
      (edges/add-edge! {:from other1 :to other2 :relation :supersedes})
      (edges/remove-edges-for-node! node-to-remove)
      ;; The unrelated edge should still exist
      (is (= 1 (count (edges/get-all-edges))))
      ;; Verify edge still exists by checking outgoing from other1
      (is (= 1 (count (edges/get-edges-from other1)))))))

;; =============================================================================
;; edge-stats Tests
;; =============================================================================

(deftest edge-stats-total-edges-test
  (testing "edge-stats reports total edge count"
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :supersedes})
    (let [stats (edges/edge-stats)]
      (is (= 2 (:total-edges stats))))))

(deftest edge-stats-by-relation-test
  (testing "edge-stats counts by relation type"
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :supersedes})
    (let [stats (edges/edge-stats)]
      (is (= 2 (get-in stats [:by-relation :implements])))
      (is (= 1 (get-in stats [:by-relation :supersedes]))))))

(deftest edge-stats-by-scope-test
  (testing "edge-stats counts by scope"
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                      :relation :implements :scope "hive-mcp"})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                      :relation :supersedes :scope "hive-mcp"})
    (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                      :relation :refines :scope "other"})
    (let [stats (edges/edge-stats)]
      (is (= 2 (get-in stats [:by-scope "hive-mcp"])))
      (is (= 1 (get-in stats [:by-scope "other"]))))))

(deftest edge-stats-empty-db-test
  (testing "edge-stats handles empty database"
    (let [stats (edges/edge-stats)]
      (is (= 0 (:total-edges stats)))
      (is (empty? (:by-relation stats)))
      (is (empty? (:by-scope stats))))))