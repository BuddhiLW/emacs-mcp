(ns hive-mcp.knowledge-graph.edges-property-test
  "Property-based tests for Knowledge Graph edge operations.

   Verifies algebraic properties that must hold for ALL valid inputs:

   Pure predicates (no DB):
   - P13: Staleness monotonicity (shorter window ⊇ stale set)
   - P14: Decay-rate totality (always returns positive number)
   - P15: Promotability monotonicity over threshold

   CRUD roundtrips (DB-backed):
   - P1:  add → get recovers core/optional fields
   - P2:  Every add-edge! returns distinct IDs
   - P5:  remove → get returns nil
   - P6:  update-confidence roundtrip
   - P7:  find-edge consistency after add
   - P17: Timestamps always inst?

   Structural invariants (DB-backed):
   - P3:  Directional correctness (from/to partitioning)
   - P4:  increment-confidence! clamping [0.0, 1.0]
   - P8:  edge-stats sum consistency
   - P11: remove-edges-for-node! completeness
   - P12: count-edges matches get-all-edges
   - P16: Scope migration preserves count
   - P18: get-edges-by-relation filter correctness

   Validation rejection (DB-backed):
   - P9:  Invalid relation always throws
   - P10: Confidence outside [0,1] always throws

   Each defspec runs within a fresh DataScript connection via fixture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.edges.decay :as edges.decay]
            [hive-mcp.knowledge-graph.edges.promotion :as edges.promotion]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each fixtures/datascript-fixture)

;; =============================================================================
;; Pure Predicate References
;; =============================================================================
;;
;; Repointed after 608e8b4 ("refactor(kg/edges): decompose edges.clj into
;; stratified re-export façade"). The pure predicates moved out of edges.clj
;; into the slices, and the façade deliberately re-exports *public vars only*
;; ("the façade carries zero private shims"):
;;
;;   edge-stale?               -> edges.decay     (now public)
;;   decay-rate-for-edge       -> edges.decay     (now public)
;;   co-access-edge-promotable? -> edges.promotion (still private: defn-)
;;
;; So `@#'edges/co-access-edge-promotable?` no longer resolves through the
;; façade — it is reached via @#' on its real home in edges.promotion.

(def edge-stale? edges.decay/edge-stale?)
(def decay-rate-for-edge edges.decay/decay-rate-for-edge)
(def co-access-edge-promotable? @#'edges.promotion/co-access-edge-promotable?)

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-node-id
  "Generator for unique node IDs (UUID-based strings)."
  (gen/fmap #(str "node-" %) gen/uuid))

(def gen-relation
  "Generator for valid relation types from schema."
  (gen/elements (vec (schema/relation-types))))

(def gen-confidence
  "Generator for valid confidence scores in [0.0, 1.0]."
  (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false}))

(def gen-invalid-confidence
  "Generator for confidence scores outside [0.0, 1.0]."
  (gen/one-of
   [(gen/double* {:min 1.001 :max 100.0 :NaN? false :infinite? false})
    (gen/double* {:min -100.0 :max -0.001 :NaN? false :infinite? false})]))

(def gen-source-type
  "Generator for valid source types from schema."
  (gen/elements (vec schema/source-types)))

(def gen-scope
  "Generator for scope strings."
  (gen/fmap #(str "scope-" %) gen/string-alphanumeric))

(def gen-delta
  "Generator for confidence delta values in [-2.0, 2.0]."
  (gen/double* {:min -2.0 :max 2.0 :NaN? false :infinite? false}))

(def gen-invalid-relation
  "Generator for keywords NOT in schema/relation-types."
  (gen/such-that #(not (schema/valid-relation? %))
                 gen/keyword-ns
                 100))

(def gen-edge-params
  "Generator for valid add-edge! parameter maps (required + confidence)."
  (gen/let [from gen-node-id
            to gen-node-id
            relation gen-relation
            confidence gen-confidence]
    {:from from :to to :relation relation :confidence confidence}))

(def gen-edge-params-full
  "Generator for add-edge! parameter maps with all optional fields."
  (gen/let [from gen-node-id
            to gen-node-id
            relation gen-relation
            confidence gen-confidence
            scope gen-scope
            source-type gen-source-type
            created-by (gen/fmap #(str "agent:" %) gen/string-alphanumeric)]
    {:from from :to to :relation relation :confidence confidence
     :scope scope :source-type source-type :created-by created-by}))

;; =============================================================================
;; P1: Roundtrip — add-edge! → get-edge recovers core fields
;; =============================================================================

(defspec p1-roundtrip-core-fields 50
  (prop/for-all [params gen-edge-params]
                (let [edge-id (edges/add-edge! params)
                      edge (edges/get-edge edge-id)]
                  (and (some? edge)
                       (= (:from params) (:kg-edge/from edge))
                       (= (:to params) (:kg-edge/to edge))
                       (= (:relation params) (:kg-edge/relation edge))
                       (= (:confidence params) (:kg-edge/confidence edge))))))

(defspec p1-roundtrip-optional-fields 50
  (prop/for-all [params gen-edge-params-full]
                (let [edge-id (edges/add-edge! params)
                      edge (edges/get-edge edge-id)]
                  (and (some? edge)
                       (= (:scope params) (:kg-edge/scope edge))
                       (= (:source-type params) (:kg-edge/source-type edge))
                       (= (:created-by params) (:kg-edge/created-by edge))
                       (inst? (:kg-edge/created-at edge))
                       (inst? (:kg-edge/last-verified edge))))))

;; =============================================================================
;; P2: ID Uniqueness — every add-edge! returns a distinct ID
;; =============================================================================

(defspec p2-id-uniqueness 50
  (prop/for-all [params1 gen-edge-params
                 params2 gen-edge-params]
                (let [id1 (edges/add-edge! params1)
                      id2 (edges/add-edge! params2)]
                  (not= id1 id2))))

;; =============================================================================
;; P3: Directional correctness — from/to edge partitioning
;; =============================================================================

(defspec p3-directional-from 50
  (prop/for-all [params gen-edge-params]
                (let [_ (edges/add-edge! params)
                      from-edges (edges/get-edges-from (:from params))]
                  (every? #(= (:from params) (:kg-edge/from %)) from-edges))))

(defspec p3-directional-to 50
  (prop/for-all [params gen-edge-params]
                (let [_ (edges/add-edge! params)
                      to-edges (edges/get-edges-to (:to params))]
                  (every? #(= (:to params) (:kg-edge/to %)) to-edges))))

;; =============================================================================
;; P4: increment-confidence! clamping — result always in [0.0, 1.0]
;; =============================================================================

(defspec p4-increment-clamping 50
  (prop/for-all [confidence gen-confidence
                 delta gen-delta]
                (let [edge-id (edges/add-edge! {:from (str "inc-from-" (random-uuid))
                                                :to (str "inc-to-" (random-uuid))
                                                :relation :implements
                                                :confidence confidence})
                      result (edges/increment-confidence! edge-id delta)]
                  (and (some? result)
                       (<= 0.0 result)
                       (<= result 1.0)))))

;; =============================================================================
;; P5: Remove post-condition — get-edge returns nil after remove
;; =============================================================================

(defspec p5-remove-postcondition 50
  (prop/for-all [params gen-edge-params]
                (let [edge-id (edges/add-edge! params)]
                  (edges/remove-edge! edge-id)
                  (nil? (edges/get-edge edge-id)))))

;; =============================================================================
;; P6: Confidence update roundtrip
;; =============================================================================

(defspec p6-update-confidence-roundtrip 50
  (prop/for-all [params gen-edge-params
                 new-conf gen-confidence]
                (let [edge-id (edges/add-edge! params)]
                  (edges/update-edge-confidence! edge-id new-conf)
                  (= new-conf (:kg-edge/confidence (edges/get-edge edge-id))))))

;; =============================================================================
;; P7: find-edge consistency — add then find returns matching edge
;; =============================================================================

(defspec p7-find-edge-consistency 50
  (prop/for-all [params gen-edge-params]
                (let [_ (edges/add-edge! params)
                      found (edges/find-edge (:from params) (:to params) (:relation params))]
                  (and (some? found)
                       (= (:from params) (:kg-edge/from found))
                       (= (:to params) (:kg-edge/to found))
                       (= (:relation params) (:kg-edge/relation found))))))

;; =============================================================================
;; P8: edge-stats sum consistency — by-relation values sum to total
;; =============================================================================

(deftest p8-stats-sum-consistency
  (testing "Sum of by-relation values equals total-edges for any set of edges"
    (dotimes [_ 20]
      (edges/add-edge! {:from (str "stat-" (random-uuid))
                        :to (str "stat-" (random-uuid))
                        :relation (rand-nth (vec (schema/relation-types)))}))
    (let [stats (edges/edge-stats)
          relation-sum (reduce + 0 (vals (:by-relation stats)))]
      (is (= (:total-edges stats) relation-sum)
          "Sum of per-relation counts must equal total"))))

;; =============================================================================
;; P9: Validation — invalid relation always throws
;; =============================================================================

(defspec p9-invalid-relation-throws 50
  (prop/for-all [bad-rel gen-invalid-relation]
                (try
                  (edges/add-edge! {:from "a" :to "b" :relation bad-rel})
                  false ;; should have thrown
                  (catch clojure.lang.ExceptionInfo _ true)
                  (catch AssertionError _ true))))

;; =============================================================================
;; P10: Confidence bounds — values outside [0,1] always rejected
;; =============================================================================

(defspec p10-invalid-confidence-add-throws 30
  (prop/for-all [bad-conf gen-invalid-confidence]
                (try
                  (edges/add-edge! {:from "a" :to "b" :relation :implements :confidence bad-conf})
                  false
                  (catch clojure.lang.ExceptionInfo _ true)
                  (catch AssertionError _ true))))

(defspec p10-invalid-confidence-update-throws 30
  (prop/for-all [bad-conf gen-invalid-confidence]
                (let [edge-id (edges/add-edge! {:from (str "conf-" (random-uuid))
                                                :to (str "conf-" (random-uuid))
                                                :relation :implements})]
                  (try
                    (edges/update-edge-confidence! edge-id bad-conf)
                    false
                    (catch clojure.lang.ExceptionInfo _ true)
                    (catch AssertionError _ true)))))

;; =============================================================================
;; P11: remove-edges-for-node! completeness — no edges remain
;; =============================================================================

(defspec p11-remove-for-node-completeness 30
  (prop/for-all [node-id gen-node-id
                 targets (gen/vector gen-node-id 1 5)
                 sources (gen/vector gen-node-id 1 5)]
                (doseq [t targets]
                  (edges/add-edge! {:from node-id :to t :relation :implements}))
                (doseq [s sources]
                  (edges/add-edge! {:from s :to node-id :relation :refines}))
                (edges/remove-edges-for-node! node-id)
                (and (empty? (edges/get-edges-from node-id))
                     (empty? (edges/get-edges-to node-id)))))

;; =============================================================================
;; P12: count-edges consistency — matches (count (get-all-edges))
;; =============================================================================

(deftest p12-count-matches-get-all
  (testing "count-edges always matches (count (get-all-edges))"
    (dotimes [_ 15]
      (edges/add-edge! {:from (str "cnt-" (random-uuid))
                        :to (str "cnt-" (random-uuid))
                        :relation (rand-nth (vec (schema/relation-types)))}))
    (is (= (edges/count-edges) (count (edges/get-all-edges))))))

;; =============================================================================
;; P13: edge-stale? monotonicity — shorter window ⊇ stale set
;;
;; If an edge is stale with a LARGER staleness window, it must also
;; be stale with any SMALLER window (smaller = stricter).
;; =============================================================================

(defspec p13-staleness-monotonicity 100
  (prop/for-all [days-since (gen/choose 0 365)
                 window-a (gen/choose 1 365)
                 window-b (gen/choose 1 365)]
                (let [now (System/currentTimeMillis)
                      verified-at (java.util.Date. (- now (* (long days-since) 24 60 60 1000)))
                      edge {:kg-edge/last-verified verified-at}
                      small-window (min window-a window-b)
                      large-window (max window-a window-b)]
      ;; If stale with LARGER window, must also be stale with SMALLER window
                  (if (edge-stale? edge large-window now)
                    (edge-stale? edge small-window now)
                    true))))

;; =============================================================================
;; P14: decay-rate-for-edge totality — always returns positive number
;; =============================================================================

(defspec p14-decay-rate-totality 100
  (prop/for-all [relation gen/keyword]
                (let [rate (decay-rate-for-edge {:kg-edge/relation relation})]
                  (and (number? rate) (pos? rate)))))

;; =============================================================================
;; P15: co-access-edge-promotable? monotonicity over threshold
;;
;; If an edge is promotable at a HIGH threshold, it must also be
;; promotable at any LOWER threshold.
;; =============================================================================

(defspec p15-promotability-monotonicity 100
  (prop/for-all [confidence gen-confidence
                 threshold-a gen-confidence
                 threshold-b gen-confidence]
                (let [edge {:kg-edge/relation :co-accessed
                            :kg-edge/confidence confidence}
                      low-threshold (min threshold-a threshold-b)
                      high-threshold (max threshold-a threshold-b)]
      ;; If promotable at HIGH threshold, must also be promotable at LOW threshold
                  (if (co-access-edge-promotable? edge high-threshold)
                    (co-access-edge-promotable? edge low-threshold)
                    true))))

;; =============================================================================
;; P16: Scope migration preserves total edge count
;; =============================================================================

(deftest p16-scope-migration-preserves-count
  (testing "migrate-edge-scopes! preserves total edge count"
    (let [from-scope "old-project"
          to-scope "new-project"]
      ;; Create scoped edges
      (dotimes [_ 5]
        (edges/add-edge! {:from (str "mig-" (random-uuid))
                          :to (str "mig-" (random-uuid))
                          :relation :implements
                          :scope from-scope}))
      ;; Create unscoped edges (should not be affected)
      (dotimes [_ 3]
        (edges/add-edge! {:from (str "mig-" (random-uuid))
                          :to (str "mig-" (random-uuid))
                          :relation :refines}))
      (let [count-before (edges/count-edges)]
        (edges/migrate-edge-scopes! from-scope to-scope)
        (is (= count-before (edges/count-edges))
            "Total edge count unchanged after migration")
        (is (= 5 (count (edges/get-edges-by-scope to-scope)))
            "All scoped edges moved to new scope")
        (is (= 0 (count (edges/get-edges-by-scope from-scope)))
            "No edges remain in old scope")))))

;; =============================================================================
;; P17: add-edge! always produces valid timestamp fields
;; =============================================================================

(defspec p17-timestamps-always-inst 50
  (prop/for-all [params gen-edge-params]
                (let [edge-id (edges/add-edge! params)
                      edge (edges/get-edge edge-id)]
                  (and (inst? (:kg-edge/created-at edge))
                       (inst? (:kg-edge/last-verified edge))))))

;; =============================================================================
;; P18: get-edges-by-relation returns only matching relation
;; =============================================================================

(defspec p18-relation-filter-correctness 30
  (prop/for-all [relation gen-relation]
                (let [_ (edges/add-edge! {:from (str "rel-" (random-uuid))
                                          :to (str "rel-" (random-uuid))
                                          :relation relation})
                      found-edges (edges/get-edges-by-relation relation)]
                  (every? #(= relation (:kg-edge/relation %)) found-edges))))
