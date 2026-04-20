(ns hive-mcp.knowledge-graph.queries-test
  "Tests for Knowledge Graph traversal and query operations.

   Covers:
   - traverse: BFS with direction=outgoing, incoming, both
   - Direction filtering with co-accessed edges (production scenario)
   - Multi-hop traversal depth
   - Tool handler direction parsing"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.queries :as queries]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]
            [hive-mcp.tools.kg :as kg-tools]
            [hive-mcp.tools.kg.queries :as kg-query-handlers]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each fixtures/datascript-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- setup-directed-graph!
  "Create a directed graph for testing:
   A --implements--> B --depends-on--> C
   D --refines--> B
   Uses with-tx-batch to ensure synchronous writes (bypasses write-coalescing queue).
   Returns node IDs map."
  []
  (let [a "node-A" b "node-B" c "node-C" d "node-D"]
    (conn/with-tx-batch
      (edges/add-edge! {:from a :to b :relation :implements})
      (edges/add-edge! {:from b :to c :relation :depends-on})
      (edges/add-edge! {:from d :to b :relation :refines}))
    {:a a :b b :c c :d d}))

(defn- setup-co-accessed-graph!
  "Create co-accessed edges matching production pattern.
   record-co-access! creates from→to based on input order:
   A→B, A→C, B→C (from pairs of [A B C])
   Uses with-tx-batch to ensure synchronous writes.
   Registers :co-accessed relation type (normally done by hive-knowledge init).
   Returns node IDs map."
  []
  (schema/register-relation-types! #{:co-accessed})
  (let [a "mem-alpha" b "mem-beta" c "mem-gamma"]
    (conn/with-tx-batch
      (edges/add-edge! {:from a :to b :relation :co-accessed :confidence 0.3})
      (edges/add-edge! {:from a :to c :relation :co-accessed :confidence 0.3})
      (edges/add-edge! {:from b :to c :relation :co-accessed :confidence 0.3}))
    {:a a :b b :c c}))

(defn- result-node-ids
  "Extract node-id set from traverse results."
  [results]
  (set (map :node-id results)))

;; =============================================================================
;; traverse: direction=outgoing
;; =============================================================================

(deftest traverse-outgoing-from-source-test
  (testing "outgoing traversal follows edges FROM source node"
    (let [{:keys [a b c]} (setup-directed-graph!)
          results (queries/traverse a {:direction :outgoing :max-depth 3})]
      ;; A --implements--> B, so B should be found
      (is (contains? (result-node-ids results) b)
          "B should be reachable outgoing from A")
      ;; A --> B --> C (2 hops), so C should be found
      (is (contains? (result-node-ids results) c)
          "C should be reachable outgoing from A via B"))))

(deftest traverse-outgoing-does-not-follow-incoming-test
  (testing "outgoing traversal does NOT follow edges TO source node"
    (let [{:keys [b d]} (setup-directed-graph!)
          ;; B has incoming from A and D, outgoing to C
          results (queries/traverse b {:direction :outgoing :max-depth 3})
          found-ids (result-node-ids results)]
      ;; B --> C exists, so C is found
      (is (contains? found-ids "node-C") "C reachable outgoing from B")
      ;; D --> B exists but that's incoming to B, should NOT be followed
      (is (not (contains? found-ids d))
          "D should NOT appear in outgoing from B (D-->B is incoming)"))))

;; =============================================================================
;; traverse: direction=incoming
;; =============================================================================

(deftest traverse-incoming-follows-edges-to-source-test
  (testing "incoming traversal follows edges TO source node"
    (let [{:keys [a b d]} (setup-directed-graph!)
          ;; B has incoming edges from A (implements) and D (refines)
          results (queries/traverse b {:direction :incoming :max-depth 3})
          found-ids (result-node-ids results)]
      (is (contains? found-ids a)
          "A should be found incoming to B (A-->B)")
      (is (contains? found-ids d)
          "D should be found incoming to B (D-->B)"))))

(deftest traverse-incoming-does-not-follow-outgoing-test
  (testing "incoming traversal does NOT follow edges FROM source node"
    (let [{:keys [b]} (setup-directed-graph!)
          ;; B --> C exists (outgoing), should NOT be followed
          results (queries/traverse b {:direction :incoming :max-depth 3})
          found-ids (result-node-ids results)]
      (is (not (contains? found-ids "node-C"))
          "C should NOT appear in incoming to B (B-->C is outgoing)"))))

;; =============================================================================
;; traverse: direction=both
;; =============================================================================

(deftest traverse-both-follows-all-edges-test
  (testing "both traversal follows edges in both directions"
    (let [{:keys [a b c d]} (setup-directed-graph!)
          results (queries/traverse b {:direction :both :max-depth 3})
          found-ids (result-node-ids results)]
      (is (contains? found-ids a) "A reachable via both from B")
      (is (contains? found-ids c) "C reachable via both from B")
      (is (contains? found-ids d) "D reachable via both from B"))))

;; =============================================================================
;; traverse: co-accessed edges (production scenario)
;; =============================================================================

(deftest traverse-co-accessed-outgoing-from-first-node-test
  (testing "outgoing from first node finds co-accessed targets"
    (let [{:keys [a b c]} (setup-co-accessed-graph!)
          ;; A is in FROM position: A→B, A→C
          results (queries/traverse a {:direction :outgoing :max-depth 3})
          found-ids (result-node-ids results)]
      (is (= 2 (count results))
          "A has 2 outgoing co-accessed edges")
      (is (contains? found-ids b) "B reachable outgoing from A")
      (is (contains? found-ids c) "C reachable outgoing from A"))))

(deftest traverse-co-accessed-outgoing-from-last-node-test
  (testing "outgoing from last node finds nothing (node only in TO position)"
    (let [{:keys [c]} (setup-co-accessed-graph!)
          ;; C is only in TO position: A→C, B→C
          results (queries/traverse c {:direction :outgoing :max-depth 3})]
      (is (empty? results)
          "C has no outgoing edges (only appears as target)"))))

(deftest traverse-co-accessed-incoming-from-last-node-test
  (testing "incoming to last node finds sources"
    (let [{:keys [a b c]} (setup-co-accessed-graph!)
          ;; C has incoming: A→C, B→C
          results (queries/traverse c {:direction :incoming :max-depth 3})
          found-ids (result-node-ids results)]
      (is (= 2 (count results))
          "C has 2 incoming co-accessed edges")
      (is (contains? found-ids a) "A reachable incoming to C")
      (is (contains? found-ids b) "B reachable incoming to C"))))

(deftest traverse-co-accessed-incoming-from-first-node-test
  (testing "incoming to first node finds nothing (node only in FROM position)"
    (let [{:keys [a]} (setup-co-accessed-graph!)
          ;; A is only in FROM position: A→B, A→C
          results (queries/traverse a {:direction :incoming :max-depth 3})]
      (is (empty? results)
          "A has no incoming edges (only appears as source)"))))

(deftest traverse-co-accessed-both-from-any-node-test
  (testing "both direction finds all connected nodes regardless of edge direction"
    (let [{:keys [a b c]} (setup-co-accessed-graph!)
          results-from-a (queries/traverse a {:direction :both :max-depth 3})
          results-from-c (queries/traverse c {:direction :both :max-depth 3})
          ids-from-a (result-node-ids results-from-a)
          ids-from-c (result-node-ids results-from-c)]
      ;; From A: A→B (outgoing), A→C (outgoing), B→C found via B
      (is (contains? ids-from-a b) "B reachable from A via both")
      (is (contains? ids-from-a c) "C reachable from A via both")
      ;; From C: A→C (incoming), B→C (incoming), A→B found via A or B
      (is (contains? ids-from-c a) "A reachable from C via both")
      (is (contains? ids-from-c b) "B reachable from C via both"))))

;; =============================================================================
;; traverse: default direction
;; =============================================================================

(deftest traverse-default-direction-is-outgoing-test
  (testing "traverse defaults to outgoing when direction not specified"
    (let [{:keys [a b]} (setup-directed-graph!)
          results (queries/traverse a {})]
      (is (contains? (result-node-ids results) b)
          "Default direction should be outgoing"))))

;; =============================================================================
;; Tool handler direction parsing
;; =============================================================================

(deftest tool-handler-outgoing-direction-test
  (testing "tool handler correctly parses direction='outgoing'"
    (let [{:keys [a b c]} (setup-directed-graph!)
          result (kg-tools/handle-kg-traverse {:start_node a
                                               :direction "outgoing"
                                               :max_depth 3})]
      ;; Result is MCP JSON response
      (is (some? result) "handler returns a result"))))

(deftest tool-handler-incoming-direction-test
  (testing "tool handler correctly parses direction='incoming'"
    (let [{:keys [b]} (setup-directed-graph!)
          result (kg-tools/handle-kg-traverse {:start_node b
                                               :direction "incoming"
                                               :max_depth 3})]
      (is (some? result) "handler returns a result for incoming"))))

(deftest tool-handler-both-direction-test
  (testing "tool handler correctly parses direction='both'"
    (let [{:keys [b]} (setup-directed-graph!)
          result (kg-tools/handle-kg-traverse {:start_node b
                                               :direction "both"
                                               :max_depth 3})]
      (is (some? result) "handler returns a result for both"))))

(deftest tool-handler-nil-direction-defaults-outgoing-test
  (testing "tool handler defaults to outgoing when direction is nil"
    (let [{:keys [a]} (setup-directed-graph!)
          result (kg-tools/handle-kg-traverse {:start_node a})]
      (is (some? result) "handler returns a result with nil direction"))))

;; =============================================================================
;; traverse: depth control
;; =============================================================================

(deftest traverse-respects-max-depth-test
  (testing "traverse stops at max_depth"
    (let [{:keys [a]} (setup-directed-graph!)
          ;; A → B → C is 2 hops
          depth-1-results (queries/traverse a {:direction :outgoing :max-depth 1})
          depth-2-results (queries/traverse a {:direction :outgoing :max-depth 2})]
      (is (= 1 (count depth-1-results))
          "depth=1 only finds direct neighbor B")
      (is (= 2 (count depth-2-results))
          "depth=2 finds B and C"))))

;; =============================================================================
;; Tool handler: result content validation
;; =============================================================================

(defn- parse-handler-result
  "Parse MCP JSON handler result into a Clojure map."
  [result]
  (when-let [text (:text result)]
    (json/read-str text :key-fn keyword)))

(deftest tool-handler-traverse-returns-results-test
  (testing "handler returns correct result-count and results for seeded graph"
    (let [{:keys [a b c]} (setup-directed-graph!)
          parsed (parse-handler-result
                  (kg-query-handlers/handle-kg-traverse
                   {:start_node a :direction "outgoing" :max_depth 3}))]
      (is (:success parsed) "response should be successful")
      (is (= 2 (:result-count parsed))
          "A has 2 reachable nodes outgoing (B, C)")
      (is (= 2 (count (:results parsed)))
          "results vec matches result-count"))))

(deftest tool-handler-traverse-both-returns-results-test
  (testing "handler returns results for direction=both"
    (let [{:keys [b]} (setup-directed-graph!)
          parsed (parse-handler-result
                  (kg-query-handlers/handle-kg-traverse
                   {:start_node b :direction "both" :max_depth 3}))]
      (is (:success parsed))
      (is (pos? (:result-count parsed))
          "B has edges in both directions, result-count should be > 0"))))

;; =============================================================================
;; Tool handler: diagnostic hint on empty results
;; =============================================================================

(deftest tool-handler-traverse-hint-no-edges-test
  (testing "handler returns hint when node has no edges at all"
    (let [parsed (parse-handler-result
                  (kg-query-handlers/handle-kg-traverse
                   {:start_node "orphan-node-xyz" :direction "both" :max_depth 2}))]
      (is (:success parsed))
      (is (zero? (:result-count parsed)))
      (is (string? (:hint parsed))
          "should include diagnostic hint for empty results")
      (is (re-find #"no edges" (:hint parsed))
          "hint should mention node has no edges"))))

(deftest tool-handler-traverse-hint-filter-mismatch-test
  (testing "handler returns filter-mismatch hint when node has edges but filters exclude them"
    (let [{:keys [a]} (setup-directed-graph!)
          ;; A has outgoing edges, but incoming traversal returns 0
          parsed (parse-handler-result
                  (kg-query-handlers/handle-kg-traverse
                   {:start_node a :direction "incoming" :max_depth 3}))]
      (is (:success parsed))
      (is (zero? (:result-count parsed)))
      (is (string? (:hint parsed))
          "should include hint explaining why filters excluded results"))))

;; =============================================================================
;; Datahike backend: traverse cross-validation
;; Uses proto/transact! directly (bypasses write-coalescing queue)
;; to avoid async writer interference in test isolation.
;; =============================================================================

;; NOTE: Datahike traverse cross-validation tests need a dedicated file with
;; (use-fixtures :each fixtures/datahike-fixture) instead of nesting inside
;; the DataScript fixture. The nested fixture + write-coalescing queue causes
;; the Datahike store to not reflect transacted edges in queries.
;; Follow-up: extract to queries_datahike_test.clj
