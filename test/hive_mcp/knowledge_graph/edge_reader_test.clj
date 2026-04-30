(ns hive-mcp.knowledge-graph.edge-reader-test
  "Unit tests for the IEdgeReader protocol (ISP read-side of edge store).

   Two axes of coverage:
   1. StubReader defrecord — verifies the contract (scope is a hard filter,
      batch unions across multiple ids, empty ids -> empty map).
   2. default-reader smoke test — verifies the reify in edges.clj delegates
      to the plain fns by stubbing conn/query with a constant fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.protocols :as p]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; StubReader — in-memory IEdgeReader for contract tests
;; =============================================================================

(defn- filter-scope
  "When scope is non-nil, keep only edges whose :kg-edge/scope equals it.
   Scope is a HARD filter — not a hint (this is the contract)."
  [edges scope]
  (if scope
    (filterv #(= scope (:kg-edge/scope %)) edges)
    (vec edges)))

(defrecord StubReader [edges]
  ;; edges is a plain vector of edge maps. Slices are derived on demand
  ;; so tests can reason about exactly what the contract returns.
  p/IEdgeReader
  (get-edges-from [_ id]
    (filter-scope (filterv #(= id (:kg-edge/from %)) edges) nil))
  (get-edges-from [_ id scope]
    (filter-scope (filterv #(= id (:kg-edge/from %)) edges) scope))
  (get-edges-to [_ id]
    (filter-scope (filterv #(= id (:kg-edge/to %)) edges) nil))
  (get-edges-to [_ id scope]
    (filter-scope (filterv #(= id (:kg-edge/to %)) edges) scope))
  (batch-get-edges-from [_ ids]
    (if (empty? ids)
      {}
      (let [id-set (set ids)]
        (group-by :kg-edge/from
                  (filterv #(contains? id-set (:kg-edge/from %)) edges)))))
  (batch-get-edges-from [_ ids scope]
    (if (empty? ids)
      {}
      (let [id-set (set ids)
            matching (filterv #(and (contains? id-set (:kg-edge/from %))
                                    (= scope (:kg-edge/scope %)))
                              edges)]
        (group-by :kg-edge/from matching))))
  (batch-get-edges-to [_ ids]
    (if (empty? ids)
      {}
      (let [id-set (set ids)]
        (group-by :kg-edge/to
                  (filterv #(contains? id-set (:kg-edge/to %)) edges)))))
  (batch-get-edges-to [_ ids scope]
    (if (empty? ids)
      {}
      (let [id-set (set ids)
            matching (filterv #(and (contains? id-set (:kg-edge/to %))
                                    (= scope (:kg-edge/scope %)))
                              edges)]
        (group-by :kg-edge/to matching)))))

;; =============================================================================
;; Fixture data
;; =============================================================================

(def ^:private sample-edges
  [{:kg-edge/id "e1" :kg-edge/from "a" :kg-edge/to "x"
    :kg-edge/relation :depends-on :kg-edge/scope "proj-1"}
   {:kg-edge/id "e2" :kg-edge/from "b" :kg-edge/to "x"
    :kg-edge/relation :depends-on :kg-edge/scope "proj-1"}
   {:kg-edge/id "e3" :kg-edge/from "c" :kg-edge/to "x"
    :kg-edge/relation :depends-on :kg-edge/scope "proj-2"}
   {:kg-edge/id "e4" :kg-edge/from "a" :kg-edge/to "y"
    :kg-edge/relation :calls :kg-edge/scope "proj-1"}
   {:kg-edge/id "e5" :kg-edge/from "d" :kg-edge/to "y"
    :kg-edge/relation :calls :kg-edge/scope "proj-2"}
   ;; Scopeless edge — must be filtered out when scope is provided
   {:kg-edge/id "e6" :kg-edge/from "e" :kg-edge/to "x"
    :kg-edge/relation :depends-on}])

(defn- reader [] (->StubReader sample-edges))

;; =============================================================================
;; Contract tests
;; =============================================================================

(deftest get-edges-to-unscoped-test
  (testing "get-edges-to returns every edge pointing at the target when no scope"
    (let [r (reader)
          result (p/get-edges-to r "x")]
      (is (= 4 (count result)))
      (is (= #{"e1" "e2" "e3" "e6"} (set (map :kg-edge/id result)))))))

(deftest get-edges-to-scope-is-hard-filter-test
  (testing "scope filter excludes wrong-scope edges AND scopeless edges"
    (let [r (reader)
          proj1 (p/get-edges-to r "x" "proj-1")
          proj2 (p/get-edges-to r "x" "proj-2")]
      (is (= #{"e1" "e2"} (set (map :kg-edge/id proj1)))
          "proj-1 sees only its edges — the carto_callers regression")
      (is (= #{"e3"} (set (map :kg-edge/id proj2))))
      (is (every? #(= "proj-1" (:kg-edge/scope %)) proj1))
      (is (every? #(= "proj-2" (:kg-edge/scope %)) proj2))
      (is (not (contains? (set (map :kg-edge/id proj1)) "e6"))
          "scopeless edges must NOT leak into a scoped query"))))

(deftest get-edges-from-scope-filter-test
  (testing "get-edges-from applies scope as a hard filter"
    (let [r (reader)]
      (is (= #{"e1" "e4"}
             (set (map :kg-edge/id (p/get-edges-from r "a")))))
      ;; Both e1 and e4 are from "a" with scope "proj-1" in the fixture
      (is (= #{"e1" "e4"}
             (set (map :kg-edge/id (p/get-edges-from r "a" "proj-1")))))
      (is (empty? (p/get-edges-from r "a" "proj-nope"))))))

(deftest batch-get-edges-to-unions-ids-test
  (testing "batch-get-edges-to unions across multiple ids into a {id -> edges} map"
    (let [r (reader)
          result (p/batch-get-edges-to r ["x" "y"])]
      (is (= #{"x" "y"} (set (keys result))))
      (is (= 4 (count (get result "x"))))
      (is (= 2 (count (get result "y"))))
      (is (= #{"e4" "e5"} (set (map :kg-edge/id (get result "y"))))))))

(deftest batch-get-edges-to-scope-filter-test
  (testing "batch scope filter is applied uniformly across all ids"
    (let [r (reader)
          result (p/batch-get-edges-to r ["x" "y"] "proj-1")]
      (is (= #{"e1" "e2"} (set (map :kg-edge/id (get result "x")))))
      (is (= #{"e4"} (set (map :kg-edge/id (get result "y"))))))))

(deftest batch-empty-ids-returns-empty-map-test
  (testing "empty ids vector returns empty map (no full-scan fallback)"
    (let [r (reader)]
      (is (= {} (p/batch-get-edges-to r [])))
      (is (= {} (p/batch-get-edges-to r [] "proj-1")))
      (is (= {} (p/batch-get-edges-from r [])))
      (is (= {} (p/batch-get-edges-from r [] "proj-1"))))))

(deftest batch-get-edges-from-unions-ids-test
  (testing "batch-get-edges-from unions across multiple source ids"
    (let [r (reader)
          result (p/batch-get-edges-from r ["a" "b" "c"])]
      (is (= #{"a" "b" "c"} (set (keys result))))
      (is (= 2 (count (get result "a"))))
      (is (= 1 (count (get result "b"))))
      (is (= 1 (count (get result "c")))))))

;; =============================================================================
;; default-reader smoke test — delegation via with-redefs on conn/query
;; =============================================================================

(deftest default-reader-delegates-to-plain-fns-test
  (testing "default-reader.get-edges-to calls the plain fn, which calls conn/query"
    (let [fixture [{:kg-edge/id "real-1" :kg-edge/from "src" :kg-edge/to "tgt"
                    :kg-edge/relation :calls :kg-edge/scope "proj-1"}]
          calls (atom 0)]
      (with-redefs [conn/query (fn [& _args]
                                 (swap! calls inc)
                                 fixture)]
        (let [r edges/default-reader
              unscoped (p/get-edges-to r "tgt")
              scoped (p/get-edges-to r "tgt" "proj-1")
              from-edges (p/get-edges-from r "src")
              batch (p/batch-get-edges-to r ["tgt"] "proj-1")]
          (is (= fixture unscoped))
          (is (= fixture scoped))
          (is (= fixture from-edges))
          (is (= {"tgt" fixture} batch))
          (is (>= @calls 4)
              "each protocol arity should have bottomed out in conn/query"))))))
