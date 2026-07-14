(ns hive-mcp.knowledge-graph.queries-datahike-test
  "Datahike-backed cross-validation tests for KG traverse.

   Companion to queries-test.clj (which runs against DataScript).
   Exercises (queries/traverse ...) through the Datahike IKGStore
   implementation, ensuring BFS direction/depth semantics hold
   identically on the persistent backend.

   Axiom respected: 20260220155546-7a710da5 — konserve namespaces
   MUST be loaded in this order before datahike is required:
     konserve.protocols
     konserve.impl.storage-layout
     konserve.impl.defaults
     konserve.cache
     <then> datahike / hive-mcp.knowledge-graph.store.datahike

   If storage-layout is partially loaded (e.g. via a concurrent
   require of konserve.impl.defaults before storage-layout is
   fully interned) the -atomic-move multi-method var never gets
   created and datahike fails at store creation. We pre-load the
   chain at namespace load time in the :require block below — this
   runs BEFORE any deftest body touches datahike, keeping the
   axiom's ordering intact without regressing it from the fixture
   layer.

   Sentinel usage: drain pending writes via `conn/flush-pending!`
   before each assertion block. `flush-pending!` is the coalescing
   sentinel API owned by sibling task 20260404134936 (write-
   coalescing agent). We resolve it at call time — if the sibling
   has shipped it (as of 2026-04-24, they have), we call it; if
   it's absent, the stub throws `NotImplemented — expected from
   sibling task` so CI surfaces the coordination gap instead of
   silently passing on a racy sleep. See COORDINATION block below."
  (:require
   ;; NOTE(axiom 20260220155546-7a710da5): konserve pre-load order.
   ;; These requires MUST stay in this order, and MUST be listed
   ;; before hive-mcp.knowledge-graph.store.datahike.
   [konserve.protocols]
   [konserve.impl.storage-layout]
   [konserve.impl.defaults]
   [konserve.cache]
   ;; Regular test deps
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hive-mcp.knowledge-graph.connection :as conn]
   [hive-mcp.knowledge-graph.edges :as edges]
   [hive-mcp.knowledge-graph.queries :as queries]
   [hive-mcp.knowledge-graph.schema :as schema]
   [hive-mcp.knowledge-graph.store.fixtures :as fixtures]
   [hive-datahike.kg.store]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; COORDINATION — sibling task 20260404134936 owns `flush-pending!`
;; =============================================================================
;;
;; The write-coalescing agent owns the sentinel API that deterministically
;; drains the async transact queue. The target symbol is
;; `hive-mcp.knowledge-graph.connection/flush-pending!` — a blocking
;; sentinel that enqueues a marker, waits for the consumer loop to
;; observe it, and returns only after all preceding tx-data has been
;; flushed to the store.
;;
;; Until the sibling ships it, we resolve it dynamically:
;;   - if present  → call it (real sentinel drain)
;;   - if absent   → call the NotImplemented stub below, which throws
;;                   so CI surfaces the missing coordination contract
;;                   instead of silently passing with a racy sleep.
;;
;; We intentionally do NOT fall back to `drain-writer!` (the existing
;; Thread/sleep 50 helper): that is the bug this follow-up exists to
;; replace, and silently accepting it would hide regressions in the
;; sibling's work.

(defn- flush-pending-stub!
  "Stub for the coalescing sentinel owned by sibling task 20260404134936.
   Replace with a real `require`/alias once the sibling publishes
   `hive-mcp.knowledge-graph.connection/flush-pending!`."
  []
  (throw (ex-info "NotImplemented — expected from sibling task"
                  {:owner-task "20260404134936"
                   :expected-symbol 'hive-mcp.knowledge-graph.connection/flush-pending!
                   :contract "Blocking sentinel: enqueue marker, return after drain"})))

(defn- flush-pending!
  "Resolve the sibling-owned sentinel at call time; fall back to stub
   so the test fails loudly (not silently) when the API is missing."
  []
  (if-let [v (resolve 'hive-mcp.knowledge-graph.connection/flush-pending!)]
    (v)
    (flush-pending-stub!)))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- datahike-with-writer-reset-fixture
  "Wraps fixtures/datahike-fixture and fully resets the coalescing writer
   before and after each test. The writer-state atom is defonce and
   persists across fixture runs; without an explicit stop-writer! the
   consumer loop from a previous test can hold a stale tx-chan/go-chan
   and race with the new store's teardown."
  [f]
  (conn/stop-writer!)
  (fixtures/datahike-fixture
   (fn []
     (conn/stop-writer!)
     (try
       (f)
       (finally
         (flush-pending!)
         (conn/stop-writer!))))))

(use-fixtures :each datahike-with-writer-reset-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- setup-directed-graph!
  "A --implements--> B --depends-on--> C
   D --refines--> B

   Seed path: with-tx-batch binds *tx-batch*, causing edges/add-edge!
   to accumulate into a single synchronous proto/transact! at block
   end — the same pattern queries_test.clj uses for DataScript, and
   datahike_test.clj uses for direct proto writes. This bypasses the
   write-coalescing queue entirely for seed data, so queries observe
   the edges immediately (datahike d/transact! on :self is sync).

   flush-pending! is still called afterwards as the COALESCING
   sentinel: any incidental transact! that did route through the
   async queue (e.g., edges/add-edge!'s stats-apply-delta path, or
   future interceptors) is drained deterministically before reads.
   This is the contract handshake with sibling task 20260404134936."
  []
  (let [a "dh-A" b "dh-B" c "dh-C" d "dh-D"]
    (conn/with-tx-batch
      (edges/add-edge! {:from a :to b :relation :implements})
      (edges/add-edge! {:from b :to c :relation :depends-on})
      (edges/add-edge! {:from d :to b :relation :refines}))
    (flush-pending!)
    {:a a :b b :c c :d d}))

(defn- setup-co-accessed-graph!
  "Production-style co-accessed edges: A→B, A→C, B→C.
   Uses with-tx-batch for sync seed (see setup-directed-graph! docstring)."
  []
  (schema/register-relation-types! #{:co-accessed})
  (let [a "dh-mem-alpha" b "dh-mem-beta" c "dh-mem-gamma"]
    (conn/with-tx-batch
      (edges/add-edge! {:from a :to b :relation :co-accessed :confidence 0.3})
      (edges/add-edge! {:from a :to c :relation :co-accessed :confidence 0.3})
      (edges/add-edge! {:from b :to c :relation :co-accessed :confidence 0.3}))
    (flush-pending!)
    {:a a :b b :c c}))

(defn- result-node-ids [results]
  (set (map :node-id results)))

;; =============================================================================
;; traverse: direction semantics [datahike]
;; =============================================================================

(deftest traverse-outgoing-from-source-test
  (testing "outgoing traversal follows edges FROM source node [datahike]"
    (let [{:keys [a b c]} (setup-directed-graph!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing :max-depth 3})
            ids (result-node-ids results)]
        (is (contains? ids b) "B reachable outgoing from A")
        (is (contains? ids c) "C reachable outgoing from A via B")))))

(deftest traverse-outgoing-does-not-follow-incoming-test
  (testing "outgoing traversal does NOT follow incoming edges [datahike]"
    (let [{:keys [b d]} (setup-directed-graph!)]
      (flush-pending!)
      (let [results (queries/traverse b {:direction :outgoing :max-depth 3})
            ids (result-node-ids results)]
        (is (contains? ids "dh-C") "C reachable outgoing from B")
        (is (not (contains? ids d))
            "D should NOT appear in outgoing from B (D-->B is incoming)")))))

(deftest traverse-incoming-follows-edges-to-source-test
  (testing "incoming traversal follows edges TO source node [datahike]"
    (let [{:keys [a b d]} (setup-directed-graph!)]
      (flush-pending!)
      (let [results (queries/traverse b {:direction :incoming :max-depth 3})
            ids (result-node-ids results)]
        (is (contains? ids a) "A found incoming to B (A-->B)")
        (is (contains? ids d) "D found incoming to B (D-->B)")))))

(deftest traverse-both-follows-all-edges-test
  (testing "both traversal follows edges in both directions [datahike]"
    (let [{:keys [a b c d]} (setup-directed-graph!)]
      (flush-pending!)
      (let [results (queries/traverse b {:direction :both :max-depth 3})
            ids (result-node-ids results)]
        (is (contains? ids a) "A reachable via both from B")
        (is (contains? ids c) "C reachable via both from B")
        (is (contains? ids d) "D reachable via both from B")))))

;; =============================================================================
;; traverse: depth control [datahike]
;; =============================================================================

(deftest traverse-respects-max-depth-test
  (testing "traverse stops at max_depth [datahike]"
    (let [{:keys [a]} (setup-directed-graph!)]
      (flush-pending!)
      (let [depth-1 (queries/traverse a {:direction :outgoing :max-depth 1})
            depth-2 (queries/traverse a {:direction :outgoing :max-depth 2})]
        (is (= 1 (count depth-1)) "depth=1 only finds direct neighbor B")
        (is (= 2 (count depth-2)) "depth=2 finds B and C")))))

;; =============================================================================
;; traverse: co-accessed edges (production scenario) [datahike]
;; =============================================================================

(deftest traverse-co-accessed-outgoing-test
  (testing "outgoing from first co-accessed node finds targets [datahike]"
    (let [{:keys [a b c]} (setup-co-accessed-graph!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing :max-depth 3})
            ids (result-node-ids results)]
        (is (= 2 (count results)) "A has 2 outgoing co-accessed edges")
        (is (contains? ids b))
        (is (contains? ids c))))))

(deftest traverse-co-accessed-incoming-test
  (testing "incoming to last co-accessed node finds sources [datahike]"
    (let [{:keys [a b c]} (setup-co-accessed-graph!)]
      (flush-pending!)
      (let [results (queries/traverse c {:direction :incoming :max-depth 3})
            ids (result-node-ids results)]
        (is (= 2 (count results)) "C has 2 incoming co-accessed edges")
        (is (contains? ids a))
        (is (contains? ids b))))))

;; =============================================================================
;; Sentinel contract [datahike]
;; =============================================================================

(deftest flush-pending-sentinel-contract-test
  (testing "flush-pending! is the sibling-owned drain sentinel"
    ;; This test documents the coordination contract: when sibling task
    ;; 20260404134936 lands, resolve returns the real var and the stub is
    ;; never reached. Until then, the stub throws `NotImplemented` so CI
    ;; surfaces the coordination gap rather than letting downstream tests
    ;; pass on a racy sleep.
    (let [resolved (resolve 'hive-mcp.knowledge-graph.connection/flush-pending!)]
      (if resolved
        (is (some? resolved)
            "sibling task shipped flush-pending! — contract fulfilled")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"NotImplemented — expected from sibling task"
             (flush-pending-stub!))
            "stub must throw until sibling task 20260404134936 lands")))))

;; =============================================================================
;; Empty graph [datahike]
;; =============================================================================

(deftest traverse-empty-graph-returns-empty-test
  (testing "traverse on a node with no edges returns empty results [datahike]"
    ;; No setup-* helper — store has migrations applied but no kg-edge datoms.
    ;; flush-pending! is still called: even an empty seed pass may have routed
    ;; non-edge writes (schema migrations) through paths that touch the queue.
    (flush-pending!)
    (let [results (queries/traverse "dh-orphan-node" {:direction :outgoing :max-depth 3})]
      (is (empty? results) "empty graph yields zero traversal results"))
    (let [results-incoming (queries/traverse "dh-orphan-node" {:direction :incoming :max-depth 3})]
      (is (empty? results-incoming) "empty graph yields zero incoming results"))
    (let [results-both (queries/traverse "dh-orphan-node" {:direction :both :max-depth 3})]
      (is (empty? results-both) "empty graph yields zero :both results"))))

;; =============================================================================
;; Single edge [datahike]
;; =============================================================================

(defn- setup-single-edge!
  "Single edge: A --implements--> B.
   Smallest non-trivial graph — exercises the edge-write path through
   the coalescing queue boundary, then the BFS read path through queries/traverse.
   See setup-directed-graph! for the seed-via-with-tx-batch + flush-pending!
   handshake explanation."
  []
  (let [a "dh-single-A" b "dh-single-B"]
    (conn/with-tx-batch
      (edges/add-edge! {:from a :to b :relation :implements}))
    (flush-pending!)
    {:a a :b b}))

(deftest traverse-single-edge-outgoing-test
  (testing "traverse a single outgoing edge [datahike]"
    (let [{:keys [a b]} (setup-single-edge!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing :max-depth 3})
            ids (result-node-ids results)]
        (is (= 1 (count results)) "single edge yields exactly one result")
        (is (contains? ids b) "B reachable outgoing from A")
        (is (= 1 (:depth (first results))) "single edge is at depth 1")))))

(deftest traverse-single-edge-incoming-test
  (testing "traverse a single incoming edge [datahike]"
    (let [{:keys [a b]} (setup-single-edge!)]
      (flush-pending!)
      (let [results (queries/traverse b {:direction :incoming :max-depth 3})
            ids (result-node-ids results)]
        (is (= 1 (count results)) "single edge yields exactly one incoming result")
        (is (contains? ids a) "A reachable incoming from B")))))

(deftest traverse-single-edge-target-has-no-outgoing-test
  (testing "traversing FROM the target of a single edge yields empty [datahike]"
    (let [{:keys [b]} (setup-single-edge!)]
      (flush-pending!)
      (let [results (queries/traverse b {:direction :outgoing :max-depth 3})]
        (is (empty? results)
            "B has no outgoing edges — outgoing traverse from B is empty")))))

;; =============================================================================
;; Multi-hop chain [datahike]
;; =============================================================================

(defn- setup-multi-hop-chain!
  "Linear 5-node chain: A --> B --> C --> D --> E (all :depends-on).
   Exercises BFS depth control across multiple hops on the persistent
   backend. See setup-directed-graph! for the seed handshake explanation."
  []
  (let [a "dh-chain-A" b "dh-chain-B" c "dh-chain-C"
        d "dh-chain-D" e "dh-chain-E"]
    (conn/with-tx-batch
      (edges/add-edge! {:from a :to b :relation :depends-on})
      (edges/add-edge! {:from b :to c :relation :depends-on})
      (edges/add-edge! {:from c :to d :relation :depends-on})
      (edges/add-edge! {:from d :to e :relation :depends-on}))
    (flush-pending!)
    {:a a :b b :c c :d d :e e}))

(deftest traverse-multi-hop-finds-all-reachable-test
  (testing "traverse with sufficient max-depth finds the full chain [datahike]"
    (let [{:keys [a b c d e]} (setup-multi-hop-chain!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing :max-depth 10})
            ids (result-node-ids results)]
        (is (= 4 (count results)) "4 reachable nodes from A in a 5-node chain")
        (is (contains? ids b) "B reachable from A")
        (is (contains? ids c) "C reachable from A")
        (is (contains? ids d) "D reachable from A")
        (is (contains? ids e) "E reachable from A")))))

(deftest traverse-multi-hop-respects-intermediate-depth-test
  (testing "max-depth caps multi-hop chain at the requested depth [datahike]"
    (let [{:keys [a b c d e]} (setup-multi-hop-chain!)]
      (flush-pending!)
      (let [d2-results (queries/traverse a {:direction :outgoing :max-depth 2})
            d2-ids (result-node-ids d2-results)
            d3-results (queries/traverse a {:direction :outgoing :max-depth 3})
            d3-ids (result-node-ids d3-results)]
        (is (= 2 (count d2-results)) "depth=2 reaches B and C only")
        (is (contains? d2-ids b))
        (is (contains? d2-ids c))
        (is (not (contains? d2-ids d)) "depth=2 does NOT reach D")
        (is (not (contains? d2-ids e)) "depth=2 does NOT reach E")
        (is (= 3 (count d3-results)) "depth=3 reaches B, C, D")
        (is (contains? d3-ids d))
        (is (not (contains? d3-ids e)) "depth=3 does NOT reach E")))))

(deftest traverse-multi-hop-records-depth-on-each-result-test
  (testing "each result's :depth matches its hop count from start [datahike]"
    (let [{:keys [a b c d e]} (setup-multi-hop-chain!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing :max-depth 10})
            depth-by-id (into {} (map (juxt :node-id :depth) results))]
        (is (= 1 (get depth-by-id b)) "B is 1 hop from A")
        (is (= 2 (get depth-by-id c)) "C is 2 hops from A")
        (is (= 3 (get depth-by-id d)) "D is 3 hops from A")
        (is (= 4 (get depth-by-id e)) "E is 4 hops from A")))))

;; =============================================================================
;; Scope-set fan-in [datahike]
;; =============================================================================
;;
;; Background — see digest scope-set fix (memory 20260428125330-56ec181a):
;; the empty-digest bug came from per-scope concurrent fan-out (one query per
;; scope, results merged); the fix routes ALL edges through ONE query and
;; filters with an in-memory scope-set. queries/traverse already implements
;; that pattern via `visible-scope-tags` + `edge-matches-scope?`. These tests
;; lock in the fan-in semantics on the persistent backend so a regression
;; back to fan-out would be caught.

(defn- setup-multi-scope-graph!
  "Three-scope fan-in graph rooted at A:
     A --:implements--> B   (scope=scope:project:hive-mcp)
     A --:implements--> C   (scope=scope:project:hive-knowledge)
     A --:implements--> D   (scope=scope:global)
     A --:implements--> E   (no scope set — visible everywhere)

   Visible-scope-tags of `hive-mcp` resolves to
   #{\"scope:project:hive-mcp\" \"scope:global\"} (self + ancestors), so a
   scope-filtered traverse from A under scope=hive-mcp must:
     - INCLUDE B (matches scope:project:hive-mcp)
     - INCLUDE D (matches scope:global ancestor)
     - INCLUDE E (no scope on edge — see edge-matches-scope?: nil edge-scope
                  is visible everywhere)
     - EXCLUDE C (scope:project:hive-knowledge is a sibling, not visible)

   This is the fan-IN pattern: one BFS, one in-memory set check per edge."
  []
  (let [a "dh-scoped-A" b "dh-scoped-B" c "dh-scoped-C"
        d "dh-scoped-D" e "dh-scoped-E"]
    (conn/with-tx-batch
      (edges/add-edge! {:from a :to b :relation :implements :scope "scope:project:hive-mcp"})
      (edges/add-edge! {:from a :to c :relation :implements :scope "scope:project:hive-knowledge"})
      (edges/add-edge! {:from a :to d :relation :implements :scope "scope:global"})
      (edges/add-edge! {:from a :to e :relation :implements}))
    (flush-pending!)
    {:a a :b b :c c :d d :e e}))

(deftest traverse-scope-set-fan-in-includes-self-and-ancestors-test
  (testing "scope-filtered traverse fans IN edges from self+ancestor scopes [datahike]"
    (let [{:keys [a b d e]} (setup-multi-scope-graph!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing
                                         :max-depth 3
                                         :scope "hive-mcp"})
            ids (result-node-ids results)]
        (is (contains? ids b) "B (own scope) included")
        (is (contains? ids d) "D (global ancestor) included")
        (is (contains? ids e) "E (no edge scope — visible everywhere) included")))))

(deftest traverse-scope-set-fan-in-excludes-sibling-scope-test
  (testing "scope-filtered traverse EXCLUDES edges in unrelated sibling scope [datahike]"
    (let [{:keys [a c]} (setup-multi-scope-graph!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing
                                         :max-depth 3
                                         :scope "hive-mcp"})
            ids (result-node-ids results)]
        (is (not (contains? ids c))
            "C (sibling scope hive-knowledge) excluded — fan-in must not leak siblings")))))

(deftest traverse-scope-set-fan-in-no-scope-sees-all-test
  (testing "traverse without :scope sees all edges regardless of scope [datahike]"
    (let [{:keys [a b c d e]} (setup-multi-scope-graph!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing :max-depth 3})
            ids (result-node-ids results)]
        (is (= 4 (count results))
            "no scope filter — all 4 outgoing edges from A are followed")
        (is (contains? ids b))
        (is (contains? ids c))
        (is (contains? ids d))
        (is (contains? ids e))))))

(deftest traverse-scope-set-fan-in-single-scope-only-self-test
  (testing "global scope filter sees global edges + nil-scope edges only [datahike]"
    (let [{:keys [a b c d e]} (setup-multi-scope-graph!)]
      (flush-pending!)
      (let [results (queries/traverse a {:direction :outgoing
                                         :max-depth 3
                                         :scope "global"})
            ids (result-node-ids results)]
        ;; visible-scope-tags(global) = #{"scope:global"}, so edge-matches-scope?
        ;; admits the global edge (D) and the no-scope edge (E) but excludes B and C.
        (is (contains? ids d) "D (scope:global) included for global scope filter")
        (is (contains? ids e) "E (no edge scope) included — nil scope is visible everywhere")
        (is (not (contains? ids b)) "B (project scope) excluded under global filter")
        (is (not (contains? ids c)) "C (project scope) excluded under global filter")))))
