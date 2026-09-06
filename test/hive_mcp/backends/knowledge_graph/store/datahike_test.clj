(ns hive-mcp.backends.knowledge-graph.store.datahike-test
  "Datahike-SPECIFIC store behaviour — the semantics no other backend shares.

   The shared IKGStore behaviour (ensure-conn!, transact!/query roundtrip,
   entid/lookup-ref, pull-entity, db-snapshot, every relation kind, edge and
   disc CRUD) is NOT duplicated here: it lives once in
   hive-mcp.knowledge-graph.store.contract and is applied to this driver by
   store.datahike-contract-test.

   What remains is what only Datahike does:
   - reset-conn! is NON-destructive (close + reopen; on-disk data survives)
   - delete-database! is guarded behind an explicit confirm token
   - it satisfies IPersistentKGStore
   - temporal queries: history-db, query-history, as-of-db"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each fixtures/datahike-fixture)

;; =============================================================================
;; Durability / destruction guards (on-disk backend)
;; =============================================================================

(deftest reset-conn-is-non-destructive-test
  (testing "reset-conn! preserves on-disk data [datahike] — close + reopen, NOT delete"
    ;; Regression for 2026-04-28 incident where reset-conn! deleted the live KG.
    ;; See AXIOM "Never NUKE Data — Destruction Requires Explicit, Loud, Guarded Consent".
    (let [store (proto/get-store)
          edge-id (str (random-uuid))]
      (proto/transact! store [{:kg-edge/id edge-id
                               :kg-edge/from "a"
                               :kg-edge/to "b"
                               :kg-edge/relation :implements
                               :kg-edge/confidence 1.0}])
      (let [before (proto/query store '[:find ?e :where [?e :kg-edge/id]])]
        (is (= 1 (count before)) "data exists pre-reset"))
      (proto/reset-conn! store)
      (let [after (proto/query store '[:find ?e :where [?e :kg-edge/id]])]
        (is (= 1 (count after))
            "reset-conn! MUST preserve on-disk data — close + reopen, never delete")))))

(deftest datahike-extends-persistent-protocol-test
  (testing "Datahike satisfies IPersistentKGStore (has on-disk state)"
    (is (proto/persistent-store? (proto/get-store)))))

(deftest delete-database-requires-confirm-guard-test
  (testing "delete-database! throws unless confirm=:i-mean-it"
    (let [store (proto/get-store)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires confirm=:i-mean-it"
                            (proto/delete-database! store nil)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires confirm=:i-mean-it"
                            (proto/delete-database! store :yes)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires confirm=:i-mean-it"
                            (proto/delete-database! store true))))))

(deftest delete-database-with-confirm-actually-deletes-test
  (testing "delete-database! with :i-mean-it actually wipes (against temp store from fixture)"
    (let [store (proto/get-store)]
      (proto/transact! store [{:kg-edge/id (str (random-uuid))
                               :kg-edge/from "a"
                               :kg-edge/to "b"
                               :kg-edge/relation :implements
                               :kg-edge/confidence 1.0}])
      (let [before (proto/query store '[:find ?e :where [?e :kg-edge/id]])]
        (is (= 1 (count before)) "data exists pre-delete"))
      (proto/delete-database! store :i-mean-it)
      (let [after (proto/query store '[:find ?e :where [?e :kg-edge/id]])]
        (is (= 0 (count after)) "data wiped after explicit delete-database! call")))))

;; =============================================================================
;; Temporal Query Extensions (Datahike-specific)
;; =============================================================================

(deftest history-db-available-test
  (testing "history-db returns a database value [datahike]"
    (require 'hive-datahike.kg.store)
    (let [history-db-fn (resolve 'hive-datahike.kg.store/history-db)
          store (proto/get-store)]
      ;; Add some data first
      (proto/transact! store [{:kg-edge/id "hist-test"
                               :kg-edge/from "a"
                               :kg-edge/to "b"
                               :kg-edge/relation :implements
                               :kg-edge/confidence 1.0}])
      (let [hist-db (history-db-fn store)]
        (is (some? hist-db))))))

(deftest query-history-returns-results-test
  (testing "query-history queries historical data [datahike]"
    (require 'hive-datahike.kg.store)
    (let [query-history-fn (resolve 'hive-datahike.kg.store/query-history)
          store (proto/get-store)
          edge-id (str "hist-edge-" (random-uuid))]
      ;; Add and then update an edge
      (proto/transact! store [{:kg-edge/id edge-id
                               :kg-edge/from "a"
                               :kg-edge/to "b"
                               :kg-edge/relation :implements
                               :kg-edge/confidence 0.5}])
      ;; History should include the entity
      (let [hist-results (query-history-fn store
                                           '[:find ?e
                                             :in $ ?eid
                                             :where [?e :kg-edge/id ?eid]]
                                           edge-id)]
        (is (pos? (count hist-results)))))))

(deftest as-of-db-returns-past-state-test
  (testing "as-of-db returns database at past point [datahike]"
    (let [store       (proto/get-store)
          before-time (java.util.Date.)]
      (is (proto/temporal-store? store) "datahike satisfies ITemporalKGStore")
      (Thread/sleep 10) ; ensure the tx lands strictly after before-time
      (proto/transact! store [{:kg-edge/id "asof-test"
                               :kg-edge/from "a"
                               :kg-edge/to "b"
                               :kg-edge/relation :implements
                               :kg-edge/confidence 1.0}])
      (is (some? (proto/as-of-db store before-time))))))
