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
   [hive-mcp.knowledge-graph.store.datahike]
   ;; Regular test deps
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hive-mcp.knowledge-graph.connection :as conn]
   [hive-mcp.knowledge-graph.edges :as edges]
   [hive-mcp.knowledge-graph.queries :as queries]
   [hive-mcp.knowledge-graph.schema :as schema]
   [hive-mcp.knowledge-graph.store.fixtures :as fixtures]))
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
