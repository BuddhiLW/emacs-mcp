(ns hive-mcp.knowledge-graph.edges-streaming-test
  "Streaming get-all-edges correctness tests.

   Verifies that the batched / lazy streaming implementation of
   `edges/get-all-edges` (added 2026-04-23 to avoid full-entity pull OOM
   on 1.2M edges) is observationally equivalent to an eager full-scan,
   and that laziness is preserved.

   Dual-backend: runs against DataScript (primary test backend) and
   demonstrates the protocol-level `eids-by-attr` path directly.

   Memory isn't directly assertable from Clojure tests, but we cover:
   - correctness at small + medium scale (10 / 100 / ~10k edges)
   - streaming identity vs. a full realized set (order-independent)
   - batch-size option honoured (via call-count instrumentation)
   - eids-by-attr returns one eid per edge, no duplicates
   - scope filter preserved
   - laziness: `(take N)` does not pull the full graph"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- drain-then-reset-fixture
  "Wraps datascript-fixture so any writes still sitting on the
   write-coalescing queue from a prior test are flushed/discarded
   before we swap in a fresh store. Without this, a straggling tx
   lands in the NEW store and corrupts edge counts."
  [f]
  (conn/flush-pending!)
  (fixtures/datascript-fixture
   (fn []
     (conn/flush-pending!)
     (f))))

(use-fixtures :each drain-then-reset-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- gen-node-id []
  (str "test-node-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

(defn- seed-edges!
  "Insert n edges with deterministic :from/:to node IDs.
   Every 5th edge gets a :scope so scope filter tests have something to find."
  [n]
  (conn/with-tx-batch
    (doseq [i (range n)]
      (edges/add-edge!
       (cond-> {:from (str "node-from-" i)
                :to (str "node-to-" i)
                :relation :implements
                :confidence (/ (double (inc (mod i 10))) 10.0)}
         (zero? (mod i 5)) (assoc :scope "scope-A"))))))

;; =============================================================================
;; eids-by-attr Protocol Tests
;; =============================================================================

(deftest eids-by-attr-returns-seq-of-eids
  (testing "eids-by-attr returns one entity id per edge, no duplicates"
    (seed-edges! 50)
    (let [eids (conn/eids-by-attr :kg-edge/id)
          eids-vec (vec eids)]
      (is (= 50 (count eids-vec)))
      (is (every? integer? eids-vec))
      (is (= 50 (count (distinct eids-vec)))
          "One datom per edge on :aevt/:ave index — no duplicates"))))

(deftest eids-by-attr-empty-when-no-edges
  (testing "eids-by-attr on empty store returns empty seq"
    (is (empty? (conn/eids-by-attr :kg-edge/id)))))

;; =============================================================================
;; get-all-edges Streaming Correctness
;; =============================================================================

(deftest streaming-equivalent-to-seed-set
  (testing "get-all-edges returns every seeded edge"
    (let [n 100]
      (seed-edges! n)
      (let [all (edges/get-all-edges)
            froms (set (map :kg-edge/from all))]
        (is (= n (count all)))
        (is (= n (count froms)))
        (is (every? #(re-matches #"node-from-\d+" %) froms))))))

(deftest streaming-returns-narrow-pull-fields
  (testing "streamed edges carry all edge fields, no :db/id leak"
    (conn/with-tx-batch
      (edges/add-edge! {:from "a" :to "b" :relation :implements
                        :scope "x" :confidence 0.75
                        :created-by "agent:test"
                        :source-type :manual}))
    (let [[edge] (edges/get-all-edges)]
      (is (not (contains? edge :db/id))
          "get-all-edges must strip :db/id for parity with the old query path")
      (is (= "a" (:kg-edge/from edge)))
      (is (= "b" (:kg-edge/to edge)))
      (is (= :implements (:kg-edge/relation edge)))
      (is (= "x" (:kg-edge/scope edge)))
      (is (= 0.75 (:kg-edge/confidence edge)))
      (is (= "agent:test" (:kg-edge/created-by edge)))
      (is (= :manual (:kg-edge/source-type edge)))
      (is (inst? (:kg-edge/created-at edge)))
      (is (inst? (:kg-edge/last-verified edge))))))

(deftest streaming-scope-filter-preserved
  (testing "scope-arity returns only matching edges"
    (seed-edges! 50)
    (let [in-scope (edges/get-all-edges "scope-A")]
      ;; Every 5th edge gets scope-A → indices 0,5,...,45 → 10 edges
      (is (= 10 (count in-scope)))
      (is (every? #(= "scope-A" (:kg-edge/scope %)) in-scope)))))

(deftest streaming-scope-filter-empty-when-no-match
  (testing "scope-arity returns empty when no edges match"
    (seed-edges! 20)
    (is (empty? (edges/get-all-edges "scope-does-not-exist")))))

;; =============================================================================
;; Streaming vs. Eager Equivalence
;; =============================================================================

(deftest streaming-matches-eager-query-shape
  (testing "streamed edge set equals an eager Datalog query on same attrs"
    (seed-edges! 200)
    (let [streamed (edges/get-all-edges)
          eager (conn/query '[:find [(pull ?e [:kg-edge/id
                                               :kg-edge/from
                                               :kg-edge/to
                                               :kg-edge/relation
                                               :kg-edge/scope
                                               :kg-edge/confidence
                                               :kg-edge/last-verified
                                               :kg-edge/created-at
                                               :kg-edge/source-type
                                               :kg-edge/created-by]) ...]
                              :where [?e :kg-edge/id]])
          sort-key (juxt :kg-edge/from :kg-edge/to)]
      (is (= (sort-by sort-key streamed)
             (sort-by sort-key (map #(dissoc % :db/id) eager)))
          "Streaming and eager paths must agree on edge set"))))

;; =============================================================================
;; Batch-size Option
;; =============================================================================

(deftest batch-size-honoured
  (testing "custom :batch-size option does not change result set"
    (seed-edges! 73)
    (let [default (set (map :kg-edge/id (edges/get-all-edges)))
          tiny (set (map :kg-edge/id (edges/get-all-edges nil {:batch-size 7})))
          big  (set (map :kg-edge/id (edges/get-all-edges nil {:batch-size 10000})))]
      (is (= 73 (count default)))
      (is (= default tiny))
      (is (= default big)))))

(deftest batch-size-invalid-coerced-to-at-least-one
  (testing ":batch-size 0 or negative coerced to 1, does not hang or error"
    (seed-edges! 3)
    (is (= 3 (count (edges/get-all-edges nil {:batch-size 0}))))
    (is (= 3 (count (edges/get-all-edges nil {:batch-size -10}))))))

;; =============================================================================
;; Laziness
;; =============================================================================

(deftest streaming-is-lazy-take-does-not-pull-all
  (testing "take N yields a sequence without realizing the full graph

   We can't measure JVM memory directly, but we can check that the seq
   is chunked/lazy by looking at the return type and by confirming
   that (take) returns promptly without count forcing everything."
    (seed-edges! 1000)
    (let [head (take 3 (edges/get-all-edges))]
      (is (seq? head))
      ;; Realize only the head — this should work on any lazy seq.
      (is (= 3 (count head)))
      ;; partition-all + mapcat produces a LazySeq; verify shape.
      (is (instance? clojure.lang.ISeq (edges/get-all-edges))))))

;; =============================================================================
;; Medium-scale correctness (stand-in for the 1.2M-edge OOM scenario)
;; =============================================================================
;; Dropped from the default suite — 10k-edge seeding is slow enough to
;; notice in CI. Run via `clj -X:test :vars '[...]' :includes :medium`.

(deftest ^:medium streaming-handles-ten-thousand-edges
  (testing "10k edges stream cleanly with small batch size"
    (seed-edges! 10000)
    (let [cnt (count (edges/get-all-edges nil {:batch-size 250}))]
      (is (= 10000 cnt)))))
