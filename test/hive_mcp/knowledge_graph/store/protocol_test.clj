(ns hive-mcp.knowledge-graph.store.protocol-test
  "DataScript application of the backend-agnostic IKGStore contract.

   The shared protocol/edge/disc behaviour lives ONCE, as data, in
   hive-mcp.knowledge-graph.store.contract. This ns binds that suite to a
   single driver — the in-memory DataScript store — and sits on the DEFAULT
   test path, so the contract is exercised in cold CI on every run with no
   backend driver on the classpath.

   The same suite is applied to Datalevin and Datahike by the thin namespaces
   under test-backends/ (:test-backends alias). Adding a backend means adding a
   StoreFactory, not a test.

   DataScript-SPECIFIC semantics that the shared contract deliberately cannot
   state are asserted below: reset-conn! is destructive for an in-memory store
   (there is no on-disk state to preserve), whereas the persistent backends
   must survive it."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.contract :as contract]
            [hive-mcp.knowledge-graph.store.harness :as harness]
            [hive-mcp.knowledge-graph.connection.store :as cstore]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; The shared contract, applied to DataScript
;; =============================================================================

(deftest datascript-ikgstore-contract
  (contract/kg-store-contract-tests
   (harness/datascript-factory)
   :label "datascript"))

;; =============================================================================
;; DataScript-specific semantics (NOT part of the shared contract)
;; =============================================================================

(deftest datascript-reset-conn-clears-data-test
  (testing "reset-conn! clears all data [datascript — in-memory, no on-disk state to preserve]"
    (harness/with-disposable-store
      (harness/datascript-factory)
      (harness/global-isolation :sync-writes? true)
      (fn []
        (let [store (cstore/ensure-store!)]
          (proto/transact! store [{:kg-edge/id (str (random-uuid))
                                   :kg-edge/from "a"
                                   :kg-edge/to "b"
                                   :kg-edge/relation :implements
                                   :kg-edge/confidence 1.0}])
          (is (= 1 (count (proto/query store '[:find ?e :where [?e :kg-edge/id]]))))
          (proto/reset-conn! store)
          (is (= 0 (count (proto/query store '[:find ?e :where [?e :kg-edge/id]])))))))))
