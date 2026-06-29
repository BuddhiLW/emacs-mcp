(ns hive-mcp.knowledge-graph.connection-test
  "Unit tests for Knowledge Graph connection lifecycle.

   Tests cover:
   - get-conn / ensure-conn: Connection management via protocol
   - reset-conn!: Connection reset and cleanup
   - set-backend!: Backend configuration
   - gen-edge-id: Unique ID generation with timestamp
   - now: Timestamp generation
   - Protocol delegation: transact!, query, entity, entid, pull-entity"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.datascript :as ds-store]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn isolated-store-fixture
  "Per-test isolated KG store with auto setup + teardown.

   Installs a fresh in-memory DataScript store as the GLOBAL store
   (proto/set-store!) — not a thread-local *test-store* — so every thread
   resolves the same ephemeral instance and can never fall through to a
   real/live backend. Binds *sync-writes* so writes complete on the calling
   thread (these tests read immediately after transact!, with no async
   go-loop store-resolution divergence). Restores the prior store (or clears)
   on teardown. See convention 20260629150125-3a07e787."
  [f]
  (let [prior (when (proto/store-set?) (proto/get-store))
        store (ds-store/create-store)]
    (proto/ensure-conn! store)
    (proto/set-store! store)
    (binding [conn/*sync-writes* true]
      (try
        (f)
        (finally
          (conn/stop-writer!)
          (if prior (proto/set-store! prior) (proto/clear-store!)))))))

(use-fixtures :each isolated-store-fixture)

;; =============================================================================
;; get-conn / ensure-conn Tests
;; =============================================================================

(deftest get-conn-returns-non-nil-test
  (testing "get-conn returns a non-nil connection"
    (let [c (conn/get-conn)]
      (is (some? c)))))

(deftest get-conn-returns-singleton-test
  (testing "get-conn returns same connection on repeated calls"
    (let [c1 (conn/get-conn)
          c2 (conn/get-conn)]
      (is (= c1 c2)))))

(deftest ensure-conn-returns-same-as-get-conn-test
  (testing "ensure-conn is equivalent to get-conn"
    (let [c1 (conn/get-conn)
          c2 (conn/ensure-conn)]
      (is (= c1 c2)))))

;; =============================================================================
;; reset-conn! Tests
;; =============================================================================

(deftest reset-conn-clears-data-test
  (testing "reset-conn! clears all data"
    ;; Add some data via protocol
    (conn/transact! [{:kg-edge/id "test-edge"
                      :kg-edge/from "node-1"
                      :kg-edge/to "node-2"
                      :kg-edge/relation :implements
                      :kg-edge/confidence 1.0}])
    ;; Verify data exists
    (let [results (conn/query '[:find ?e :where [?e :kg-edge/id "test-edge"]])]
      (is (= 1 (count results))))
    ;; Reset
    (conn/reset-conn!)
    ;; Verify data is gone
    (let [results (conn/query '[:find ?e :where [?e :kg-edge/id _]])]
      (is (= 0 (count results))))))

(deftest reset-conn-creates-new-connection-test
  (testing "reset-conn! creates a new connection instance"
    (let [c1 (conn/ensure-conn)]
      (conn/reset-conn!)
      (let [c2 (conn/ensure-conn)]
        (is (not= c1 c2))))))

;; =============================================================================
;; Protocol Delegation Tests
;; =============================================================================

(deftest transact-and-query-via-conn-test
  (testing "transact! and query work via connection layer"
    (conn/transact! [{:kg-edge/id "delegate-test"
                      :kg-edge/from "a"
                      :kg-edge/to "b"
                      :kg-edge/relation :implements
                      :kg-edge/confidence 1.0}])
    (let [results (conn/query '[:find ?from ?to
                                :in $ ?eid
                                :where
                                [?e :kg-edge/id ?eid]
                                [?e :kg-edge/from ?from]
                                [?e :kg-edge/to ?to]]
                              "delegate-test")]
      (is (= #{["a" "b"]} (set results))))))

(deftest entid-via-conn-test
  (testing "entid resolves lookup refs via connection layer"
    (conn/transact! [{:kg-edge/id "entid-test"
                      :kg-edge/from "x"
                      :kg-edge/to "y"
                      :kg-edge/relation :refines
                      :kg-edge/confidence 0.5}])
    (let [eid (conn/entid [:kg-edge/id "entid-test"])]
      (is (some? eid))
      (is (number? eid)))))

(deftest pull-entity-via-conn-test
  (testing "pull-entity works via connection layer"
    (conn/transact! [{:kg-edge/id "pull-test"
                      :kg-edge/from "p"
                      :kg-edge/to "q"
                      :kg-edge/relation :supersedes
                      :kg-edge/confidence 0.8}])
    (let [eid (conn/entid [:kg-edge/id "pull-test"])
          pulled (conn/pull-entity '[*] eid)]
      (is (= "pull-test" (:kg-edge/id pulled)))
      (is (= "p" (:kg-edge/from pulled)))
      (is (= :supersedes (:kg-edge/relation pulled))))))

(deftest entity-via-conn-test
  (testing "entity works via connection layer"
    (conn/transact! [{:kg-edge/id "entity-test"
                      :kg-edge/from "m"
                      :kg-edge/to "n"
                      :kg-edge/relation :implements
                      :kg-edge/confidence 1.0}])
    (let [eid (conn/entid [:kg-edge/id "entity-test"])
          e (conn/entity eid)]
      (is (some? e))
      (is (= "m" (:kg-edge/from e))))))

(deftest db-snapshot-via-conn-test
  (testing "db-snapshot returns immutable value"
    (let [snap (conn/db-snapshot)]
      (is (some? snap)))))

;; =============================================================================
;; Schema Integration Tests (via protocol)
;; =============================================================================

(deftest schema-enforces-unique-edge-id-test
  (testing "Schema enforces unique :kg-edge/id"
    ;; Add first edge
    (conn/transact! [{:kg-edge/id "unique-edge-1"
                      :kg-edge/from "a"
                      :kg-edge/to "b"
                      :kg-edge/relation :implements
                      :kg-edge/confidence 1.0}])
    ;; Upsert same ID with different values
    (conn/transact! [{:kg-edge/id "unique-edge-1"
                      :kg-edge/from "c"
                      :kg-edge/to "d"
                      :kg-edge/relation :supersedes
                      :kg-edge/confidence 0.9}])
    ;; Should still have only one entity
    (let [edges (conn/query '[:find ?e :where [?e :kg-edge/id "unique-edge-1"]])]
      (is (= 1 (count edges))))
    ;; Entity should have updated values
    (let [eid (conn/entid [:kg-edge/id "unique-edge-1"])
          pulled (conn/pull-entity '[*] eid)]
      (is (= "c" (:kg-edge/from pulled)))
      (is (= "d" (:kg-edge/to pulled)))
      (is (= :supersedes (:kg-edge/relation pulled))))))

(deftest schema-supports-all-edge-attributes-test
  (testing "Schema supports all KG edge attributes"
    (let [now (conn/now)]
      (conn/transact! [{:kg-edge/id "full-edge"
                        :kg-edge/from "memory-123"
                        :kg-edge/to "memory-456"
                        :kg-edge/relation :implements
                        :kg-edge/scope "hive-mcp:agora"
                        :kg-edge/confidence 0.85
                        :kg-edge/created-by "agent:coordinator"
                        :kg-edge/created-at now}])
      (let [eid (conn/entid [:kg-edge/id "full-edge"])
            e (conn/entity eid)]
        (is (= "memory-123" (:kg-edge/from e)))
        (is (= "memory-456" (:kg-edge/to e)))
        (is (= :implements (:kg-edge/relation e)))
        (is (= "hive-mcp:agora" (:kg-edge/scope e)))
        (is (= 0.85 (:kg-edge/confidence e)))
        (is (= "agent:coordinator" (:kg-edge/created-by e)))
        (is (= now (:kg-edge/created-at e)))))))

;; =============================================================================
;; set-backend! Tests
;; =============================================================================

(deftest set-backend-datascript-test
  (testing "set-backend! :datascript configures DataScript store"
    (conn/set-backend! :datascript)
    (is (proto/store-set?))
    (is (some? (conn/get-conn)))))

(deftest set-backend-invalid-throws-test
  (testing "set-backend! rejects invalid backends"
    (is (thrown? Exception (conn/set-backend! :invalid-backend)))))

;; =============================================================================
;; gen-edge-id Tests
;; =============================================================================

(deftest gen-edge-id-returns-string-test
  (testing "gen-edge-id returns a string"
    (let [id (conn/gen-edge-id)]
      (is (string? id)))))

(deftest gen-edge-id-starts-with-edge-prefix-test
  (testing "gen-edge-id starts with 'edge-' prefix"
    (let [id (conn/gen-edge-id)]
      (is (string/starts-with? id "edge-")))))

(deftest gen-edge-id-contains-timestamp-test
  (testing "gen-edge-id contains timestamp in format yyyyMMdd'T'HHmmss"
    (let [id (conn/gen-edge-id)]
      (is (re-matches #"edge-\d{8}T\d{6}-[a-f0-9]{6}" id)))))

(deftest gen-edge-id-unique-test
  (testing "gen-edge-id generates unique IDs"
    (let [ids (repeatedly 100 conn/gen-edge-id)
          unique-ids (set ids)]
      (is (= 100 (count unique-ids))))))

(deftest gen-edge-id-sortable-test
  (testing "gen-edge-id generates chronologically sortable IDs"
    (let [id1 (conn/gen-edge-id)
          _ (Thread/sleep 1)
          id2 (conn/gen-edge-id)]
      (is (<= (compare id1 id2) 0)))))

;; =============================================================================
;; now Tests
;; =============================================================================

(deftest now-returns-date-test
  (testing "now returns java.util.Date"
    (let [t (conn/now)]
      (is (instance? java.util.Date t)))))

(deftest now-returns-current-time-test
  (testing "now returns approximately current time"
    (let [before (System/currentTimeMillis)
          t (conn/now)
          after (System/currentTimeMillis)]
      (is (<= before (.getTime t)))
      (is (>= after (.getTime t))))))

(deftest now-returns-different-instances-test
  (testing "now returns new Date instances each call"
    (let [t1 (conn/now)
          t2 (conn/now)]
      (is (not (identical? t1 t2))))))
