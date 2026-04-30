(ns hive-mcp.knowledge-graph.edge-roundtrip-contract-test
  "Contract tests for the KG edge write/read roundtrip.

   These tests exist to pin down a silent data-loss bug in the cartography
   scan pipeline: `mcp__hive__kg stats` reports {:total-edges 22} against the
   live hive-mcp store even though `scan-project!` returned {:edges 11505
   :call-edges 11505}. `carto_callers` on heavily-called qns such as
   `hive-mcp.events.core/dispatch` and `hive-mcp.knowledge-graph.connection/query`
   returns zero callers, and `kg expand` on those snippets' entry-ids confirms
   {:total-edges 0}. LSP reports 114762 call-graph edges and 62 direct
   callers of `dispatch` — so the input to the edge collector is healthy.

   The two tests here isolate the fault domain:

   A. Pure edge storage roundtrip — drives `connection/transact!` and
      `edges/batch-get-edges-to` directly with synthetic ids in a random
      scope. If this PASSES the storage layer is fine and the bug is
      upstream (collector / id-map / scope). Cleans up after itself.

   B. End-to-end scan-path assertion — resolves a real qn via the same
      `resolve-qn` that `carto_callers` uses, then asks the live store for
      incoming edges under the production scope. Expected to FAIL on the
      current pipeline, pinpointing the write-path mismatch.

   Run against a LIVE nREPL (port 7910) via:

     (require '[clojure.test :as t])
     (load-file \"test/hive_mcp/knowledge_graph/edge_roundtrip_contract_test.clj\")
     (t/run-tests 'hive-mcp.knowledge-graph.edge-roundtrip-contract-test)

   Determinism note: the legacy `settle!` polled `batch-get-edges-to` with
   25ms sleeps to wait for the coalescing queue. That was the source of
   intermittent flakes (kanban 20260404134936-1b481a86). The current
   implementation calls `conn/flush-pending!` once — the writer's own
   sentinel — instead of guessing a polling interval."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-knowledge.carto-editing.queries :as queries]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State — edges created in test A, retracted in fixture
;; =============================================================================

(def ^:private created-edge-ids
  "Edge ids allocated by test A so the fixture can retract them even if
   the assertions throw mid-test."
  (atom #{}))

(defn- retract-tracked-edges!
  "Retract every edge id in `created-edge-ids`. Tolerates missing ids so
   the fixture is idempotent under partial failure."
  []
  (let [ids @created-edge-ids]
    (doseq [eid ids]
      (when-let [dbid (conn/entid [:kg-edge/id eid])]
        (conn/transact! [[:db/retractEntity dbid]])))
    (reset! created-edge-ids #{})))

(defn- cleanup-fixture
  "Per-test fixture. Runs the test and guarantees retraction of any
   edges accumulated in `created-edge-ids` regardless of outcome."
  [f]
  (try
    (f)
    (finally
      (retract-tracked-edges!))))

(use-fixtures :each cleanup-fixture)

;; =============================================================================
;; Settle helper — drains the coalescing queue deterministically
;; =============================================================================

(defn- settle!
  "Drain the coalescing queue deterministically and return the read result.

   Replaces the legacy 25ms polling loop. `conn/flush-pending!` is the
   sentinel the writer owns — it busy-waits on the in-flight counter
   reaching zero (or a 5s deadline) so we never assert against a half-
   drained queue. The `_timeout-ms` arg is retained for backwards
   compatibility with callers that already pass an explicit budget,
   but flush-pending! has its own deadline so we ignore it here."
  [to-id scope _timeout-ms]
  (conn/flush-pending!)
  (edges/batch-get-edges-to [to-id] scope))

;; =============================================================================
;; A. Pure roundtrip — storage-level contract
;; =============================================================================

(deftest edge-roundtrip-storage-contract-test
  (testing "transact! of a kg-edge is readable via batch-get-edges-to under the same scope"
    (let [scope    (str "contract-test-" (random-uuid))
          from-id  (str "contract-from-" (random-uuid))
          to-id    (str "contract-to-"   (random-uuid))
          edge-id  (str (random-uuid))
          now      (java.util.Date.)
          tx-data  [{:kg-edge/id            edge-id
                     :kg-edge/from          from-id
                     :kg-edge/to            to-id
                     :kg-edge/relation      :depends-on
                     :kg-edge/scope         scope
                     :kg-edge/confidence    0.9
                     :kg-edge/source-type   :automated
                     :kg-edge/created-by    "edge-roundtrip-contract-test"
                     :kg-edge/created-at    now
                     :kg-edge/last-verified now}]]
      ;; Track for cleanup BEFORE transacting — if transact! throws we still
      ;; want a best-effort retract attempt on the id we chose.
      (swap! created-edge-ids conj edge-id)
      (conn/transact! tx-data)
      (let [by-id (settle! to-id scope 2000)
            hits  (get by-id to-id)]
        (is (seq hits)
            (str "Edge written under scope "
                 (pr-str scope)
                 " but batch-get-edges-to returned nothing for to-id "
                 (pr-str to-id)
                 ". Storage-level write/read contract is broken."))
        (is (= edge-id (:kg-edge/id (first hits)))
            "First hit's :kg-edge/id should match the one we transacted")
        (is (= from-id (:kg-edge/from (first hits)))
            ":kg-edge/from should roundtrip unchanged")
        (is (= to-id (:kg-edge/to (first hits)))
            ":kg-edge/to should roundtrip unchanged")
        (is (= scope (:kg-edge/scope (first hits)))
            ":kg-edge/scope should roundtrip unchanged")))))

(deftest edge-roundtrip-scope-isolation-test
  (testing "edges written under scope A are not visible when reading under scope B"
    (let [scope-a  (str "contract-test-a-" (random-uuid))
          scope-b  (str "contract-test-b-" (random-uuid))
          to-id    (str "contract-to-" (random-uuid))
          edge-id  (str (random-uuid))
          now      (java.util.Date.)]
      (swap! created-edge-ids conj edge-id)
      (conn/transact! [{:kg-edge/id            edge-id
                        :kg-edge/from          (str "contract-from-" (random-uuid))
                        :kg-edge/to            to-id
                        :kg-edge/relation      :depends-on
                        :kg-edge/scope         scope-a
                        :kg-edge/confidence    0.9
                        :kg-edge/source-type   :automated
                        :kg-edge/created-by    "edge-roundtrip-contract-test"
                        :kg-edge/created-at    now
                        :kg-edge/last-verified now}])
      (settle! to-id scope-a 2000)
      (is (seq (get (edges/batch-get-edges-to [to-id] scope-a) to-id))
          "Edge should be visible under the scope it was written to")
      (is (empty? (get (edges/batch-get-edges-to [to-id] scope-b) to-id))
          "Edge must NOT leak across scopes"))))

;; =============================================================================
;; B. End-to-end scan-path — expected to FAIL, pinpoints the real bug
;; =============================================================================

(def ^:private probe-qns
  "Qns that are defined AND heavily called in the hive-mcp codebase.
   `carto_callers` on any of these should return >0 callers in a healthy KG.
   LSP reports 62 direct callers of `dispatch` alone, and `query` is called
   from queries.clj, impact analysis, and every read-path helper."
  ["hive-mcp.knowledge-graph.connection/query"
   "hive-mcp.events.core/dispatch"])

(defn- total-incoming
  "Sum the incoming-edge counts across a collection of entry-ids."
  [by-id]
  (reduce + 0 (map (comp count val) by-id)))

(deftest scan-path-edges-reach-resolved-entry-ids-test
  (testing (str "For each probe qn, resolve via queries/resolve-qn (the same "
                "path carto_callers uses) and assert batch-get-edges-to under "
                "the production scope returns at least one incoming edge.")
    (let [scope "hive-mcp"]
      (doseq [qn probe-qns]
        (testing (str "qn=" qn)
          (let [resolved       (queries/resolve-qn qn scope)
                entry-ids      (or (:entry-ids resolved)
                                   (some-> (:entry-id resolved) vector)
                                   [])
                by-id          (edges/batch-get-edges-to entry-ids scope)
                incoming-count (total-incoming by-id)]
            (is (seq entry-ids)
                (str "resolve-qn returned no entry-ids for " qn
                     " — the snippet store is missing this form entirely. "
                     "Scan never indexed it, or tag-filter regressed."))
            (is (pos? incoming-count)
                (str "Edges exist for scope but not for " qn "'s entry-ids ("
                     (pr-str entry-ids) "). Indicates write-path mismatch: "
                     "either LSP call-graph unresolvable, or name->id map miss, "
                     "or wrong scope at edge write. Raw by-id: "
                     (pr-str (into {}
                                   (map (fn [[k v]] [k (count v)]))
                                   by-id))))))))))

(deftest scan-path-scope-non-empty-test
  (testing "scope 'hive-mcp' has non-trivial edges — guards against a totally empty store"
    (let [total (edges/count-edges "hive-mcp")]
      (is (pos? total)
          "Zero edges under scope 'hive-mcp' means the scan pipeline never
           persisted anything. Either batch-add-kg-edges! silently swallowed
           an exception, transact! never committed, or the runtime store is
           the wrong backend."))))
