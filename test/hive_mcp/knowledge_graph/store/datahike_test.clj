(ns hive-mcp.knowledge-graph.store.datahike-test
  "Tests for Datahike IGraphStore implementation.

   Tests the Datahike backend including:
   - Basic IGraphStore protocol operations
   - Temporal query extensions (history, as-of, since)
   - Database lifecycle (create, connect, reset, close)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.schema :as schema]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each fixtures/datahike-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn gen-node-id []
  (str "test-node-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

;; =============================================================================
;; Basic Protocol Operations
;; =============================================================================

(deftest ensure-conn-returns-non-nil-test
  (testing "ensure-conn! returns non-nil connection [datahike]"
    (let [store (proto/get-store)]
      (is (some? (proto/ensure-conn! store))))))

(deftest transact-and-query-roundtrip-test
  (testing "transact! + query roundtrip [datahike]"
    (let [store (proto/get-store)
          edge-id (str "test-edge-" (random-uuid))]
      (proto/transact! store [{:kg-edge/id edge-id
                               :kg-edge/from "node-a"
                               :kg-edge/to "node-b"
                               :kg-edge/relation :implements
                               :kg-edge/confidence 1.0}])
      (let [results (proto/query store
                                 '[:find ?from ?to
                                   :in $ ?eid
                                   :where
                                   [?e :kg-edge/id ?eid]
                                   [?e :kg-edge/from ?from]
                                   [?e :kg-edge/to ?to]]
                                 [edge-id])]
        (is (= #{["node-a" "node-b"]} (set results)))))))

(deftest entid-resolves-lookup-ref-test
  (testing "entid resolves lookup ref [datahike]"
    (let [store (proto/get-store)
          edge-id (str "test-edge-" (random-uuid))]
      (proto/transact! store [{:kg-edge/id edge-id
                               :kg-edge/from "a"
                               :kg-edge/to "b"
                               :kg-edge/relation :implements
                               :kg-edge/confidence 1.0}])
      (let [eid (proto/entid store [:kg-edge/id edge-id])]
        (is (some? eid))
        (is (number? eid))))))

(deftest pull-entity-returns-full-entity-test
  (testing "pull-entity returns full entity [datahike]"
    (let [store (proto/get-store)
          edge-id (str "test-edge-" (random-uuid))]
      (proto/transact! store [{:kg-edge/id edge-id
                               :kg-edge/from "a"
                               :kg-edge/to "b"
                               :kg-edge/relation :refines
                               :kg-edge/confidence 0.7}])
      (let [eid (proto/entid store [:kg-edge/id edge-id])
            pulled (proto/pull-entity store '[*] eid)]
        (is (= edge-id (:kg-edge/id pulled)))
        (is (= "a" (:kg-edge/from pulled)))
        (is (= :refines (:kg-edge/relation pulled)))
        (is (= 0.7 (:kg-edge/confidence pulled)))))))

(deftest db-snapshot-returns-value-test
  (testing "db-snapshot returns immutable value [datahike]"
    (let [store (proto/get-store)
          snap (proto/db-snapshot store)]
      (is (some? snap)))))

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
;; Edge Operations
;; =============================================================================

(deftest edges-add-and-get-test
  (testing "edges add and get [datahike]"
    (let [from (gen-node-id)
          to (gen-node-id)
          edge-id (edges/add-edge! {:from from :to to :relation :implements})]
      (is (string? edge-id))
      (let [edge (edges/get-edge edge-id)]
        (is (some? edge))
        (is (= from (:kg-edge/from edge)))
        (is (= to (:kg-edge/to edge)))
        (is (= :implements (:kg-edge/relation edge)))))))

(deftest edges-all-relations-test
  (testing "all relation types work [datahike]"
    (doseq [rel (schema/relation-types)]
      (let [edge-id (edges/add-edge! {:from (gen-node-id)
                                      :to (gen-node-id)
                                      :relation rel})]
        (is (string? edge-id) (str "Failed for relation: " rel))))))

;; =============================================================================
;; Temporal Query Extensions (Datahike-specific)
;; =============================================================================

(deftest history-db-available-test
  (testing "history-db returns a database value [datahike]"
    (require 'hive-mcp.knowledge-graph.store.datahike)
    (let [history-db-fn (resolve 'hive-mcp.knowledge-graph.store.datahike/history-db)
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
    (require 'hive-mcp.knowledge-graph.store.datahike)
    (let [query-history-fn (resolve 'hive-mcp.knowledge-graph.store.datahike/query-history)
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
    (require 'hive-mcp.knowledge-graph.store.datahike)
    (let [as-of-db-fn (resolve 'hive-mcp.knowledge-graph.store.datahike/as-of-db)
          store (proto/get-store)]
      ;; Get a timestamp before changes
      (let [before-time (java.util.Date.)]
        (Thread/sleep 10) ; Ensure time difference
        ;; Add data after the timestamp
        (proto/transact! store [{:kg-edge/id "asof-test"
                                 :kg-edge/from "a"
                                 :kg-edge/to "b"
                                 :kg-edge/relation :implements
                                 :kg-edge/confidence 1.0}])
        ;; The as-of database should exist
        (let [past-db (as-of-db-fn store before-time)]
          (is (some? past-db)))))))
