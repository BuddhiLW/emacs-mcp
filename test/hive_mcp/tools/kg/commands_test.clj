(ns hive-mcp.tools.kg.commands-test
  "Boundary-flush (read-your-writes) regression tests for KG mutation handlers.

   KG writes default to the async write-coalescing queue (edges/add-edge! ->
   conn/transact!), which returns the client-side edge-id BEFORE the datom is
   durable. `with-kg-flush` wraps the mutation handlers so the queue is drained
   (conn/flush-pending!) before the tool call returns — i.e. a read issued
   immediately after the handler (such as the next op in a multi/k> batch that
   traverses the just-created edge) sees the edge instead of racing the ~25ms
   coalescing window.

   These tests run in DEFAULT async mode (no *sync-writes* binding) so the
   boundary flush is the only thing making the write durable on return — remove
   it and `immediate-read` goes empty (verified: a raw, unwrapped handler leaves
   the edge invisible until a manual flush). Kanban 20260629161156-76f4e486."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.datascript :as ds-store]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.tools.kg.commands :as cmd]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn isolated-async-store-fixture
  "Per-test isolated KG store with auto setup + teardown.

   Installs a fresh in-memory DataScript store as the GLOBAL store
   (proto/set-store!) — not a thread-local *test-store* — so the writer
   go-loop and any spawned threads resolve the SAME ephemeral store and can
   never fall through to a real/live backend. Stops the writer before and
   after, and restores the prior store (or clears) on teardown.

   Deliberately does NOT bind *sync-writes*: these tests must exercise the
   async coalescing path so the handler's boundary flush is what provides
   read-your-writes. See convention 20260629150125-3a07e787."
  [f]
  (let [prior (when (proto/store-set?) (proto/get-store))
        store (ds-store/create-store)]
    (proto/ensure-conn! store)
    (proto/set-store! store)
    (conn/stop-writer!)
    (try
      (f)
      (finally
        (conn/stop-writer!)
        (if prior (proto/set-store! prior) (proto/clear-store!))))))

(use-fixtures :each isolated-async-store-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- edges-from
  "Set of [?to] tuples for edges whose :kg-edge/from = `from`."
  [from]
  (set (conn/query '[:find ?to
                     :in $ ?from
                     :where
                     [?e :kg-edge/from ?from]
                     [?e :kg-edge/to ?to]]
                   from)))

;; =============================================================================
;; Durable-on-return Tests (read-your-writes at the tool boundary)
;; =============================================================================

(deftest add-edge-durable-on-return-test
  (testing "kg_add_edge: edge is queryable immediately on return (async mode, no manual flush)"
    (let [resp (cmd/handle-kg-add-edge {:from "ryw-a" :to "ryw-b" :relation "implements"})]
      (is (not (:isError resp)) "handler reports success")
      ;; NO conn/flush-pending! here — the handler's boundary flush must have run.
      (is (= #{["ryw-b"]} (edges-from "ryw-a"))
          "edge must be durable on return — a later op reading it must not race the coalescing window"))))

(deftest promote-durable-on-return-test
  (testing "kg_promote: promoted edge is queryable immediately on return (async mode, no manual flush)"
    ;; Seed an original edge and flush so promote's get-edge sees it.
    (let [orig-id (edges/add-edge! {:from "prom-a" :to "prom-b"
                                    :relation :implements :scope "narrow"})
          _ (conn/flush-pending!)
          resp (cmd/handle-kg-promote {:edge_id orig-id :to_scope "broader"})
          ;; NO flush here — promote's boundary flush must have run.
          rows (conn/query '[:find ?scope ?cb
                             :in $ ?from
                             :where
                             [?e :kg-edge/from ?from]
                             [?e :kg-edge/scope ?scope]
                             [?e :kg-edge/created-by ?cb]]
                           "prom-a")]
      (is (not (:isError resp)) "promote reports success")
      (is (some (fn [[scope cb]]
                  (and (= "broader" scope)
                       (= (str "promoted-from:" orig-id) cb)))
                rows)
          "promoted edge (broader scope) must be durable on return"))))

;; =============================================================================
;; Boundary-decorator Wiring
;; =============================================================================

(deftest mutation-handlers-are-flush-wrapped-test
  (testing "with-kg-flush is the public boundary decorator and is a fn"
    (is (fn? cmd/with-kg-flush))
    ;; Validation failures still return an error envelope without throwing —
    ;; the wrapped flush is a cheap no-op when nothing was enqueued.
    (is (some? (:error (cmd/handle-kg-add-edge {}))) "add-edge {} → bare :error envelope")
    (is (:isError (cmd/handle-kg-promote {}))        "promote {} → mcp-error envelope")))
