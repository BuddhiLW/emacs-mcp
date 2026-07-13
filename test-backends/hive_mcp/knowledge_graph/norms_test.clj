(ns hive-mcp.knowledge-graph.norms-test
  "Tests for Datahike norms-based schema migration.

   Verifies that KG norms:
   - Apply cleanly on a fresh in-memory DB
   - Are idempotent (apply twice without error)
   - Support data roundtrip after application
   - Work on existing DB with pre-existing data"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [datahike.norm.norm :as norm]
            [clojure.java.io :as io]))

;; =============================================================================
;; In-Memory DB Helpers
;; =============================================================================

(def ^:private test-cfg
  {:store {:backend :memory
           :id (java.util.UUID/randomUUID)}
   :schema-flexibility :read
   :index :datahike.index/persistent-set})

(defn- fresh-conn []
  (let [cfg (assoc-in test-cfg [:store :id] (java.util.UUID/randomUUID))]
    (d/create-database cfg)
    (d/connect cfg)))

(defn- release-conn [conn]
  (let [cfg (:config @conn)]
    (d/release conn)
    (when (d/database-exists? cfg)
      (d/delete-database cfg))))

;; =============================================================================
;; KG Norms Tests
;; =============================================================================

(deftest kg-norms-apply-on-fresh-db-test
  (testing "KG norms apply cleanly on fresh in-memory DB"
    (let [conn (fresh-conn)]
      (try
        (norm/ensure-norms! conn (io/resource "hive_mcp/norms/kg"))
        (let [schema (d/schema (d/db conn))]
          (is (some? (schema :kg-edge/id))
              ":kg-edge/id should be in schema")
          (is (= :db.unique/identity
                 (:db/unique (schema :kg-edge/id)))
              ":kg-edge/id should have unique/identity")
          (is (some? (schema :knowledge/gaps))
              ":knowledge/gaps should be in schema")
          (is (= :db.cardinality/many
                 (:db/cardinality (schema :knowledge/gaps)))
              ":knowledge/gaps should have cardinality/many"))
        (finally
          (release-conn conn))))))

(deftest kg-norms-idempotent-test
  (testing "KG norms are idempotent — applying twice causes no error"
    (let [conn (fresh-conn)]
      (try
        (norm/ensure-norms! conn (io/resource "hive_mcp/norms/kg"))
        (norm/ensure-norms! conn (io/resource "hive_mcp/norms/kg"))
        (is true "Second application should succeed without error")
        (finally
          (release-conn conn))))))

(deftest kg-norms-data-roundtrip-test
  (testing "Data roundtrip works after KG norms are applied"
    (let [conn (fresh-conn)]
      (try
        (norm/ensure-norms! conn (io/resource "hive_mcp/norms/kg"))
        (let [edge-id (str "test-edge-" (random-uuid))]
          (d/transact conn {:tx-data [{:kg-edge/id edge-id
                                       :kg-edge/from "node-a"
                                       :kg-edge/to "node-b"
                                       :kg-edge/relation :implements
                                       :kg-edge/confidence 0.9}]})
          (let [results (d/q '[:find ?from ?to
                               :in $ ?eid
                               :where
                               [?e :kg-edge/id ?eid]
                               [?e :kg-edge/from ?from]
                               [?e :kg-edge/to ?to]]
                             (d/db conn) edge-id)]
            (is (= #{["node-a" "node-b"]} (set results)))))
        (finally
          (release-conn conn))))))

(deftest kg-norms-on-existing-data-test
  (testing "KG norms apply safely on DB with pre-existing data"
    (let [conn (fresh-conn)
          edge-id (str "pre-existing-" (random-uuid))]
      (try
        ;; Insert data BEFORE norms (schema-flexibility :read allows this)
        (d/transact conn {:tx-data [{:kg-edge/id edge-id
                                     :kg-edge/from "a"
                                     :kg-edge/to "b"}]})
        ;; Now apply norms
        (norm/ensure-norms! conn (io/resource "hive_mcp/norms/kg"))
        ;; Pre-existing data should still be queryable
        (let [results (d/q '[:find ?from .
                             :in $ ?eid
                             :where
                             [?e :kg-edge/id ?eid]
                             [?e :kg-edge/from ?from]]
                           (d/db conn) edge-id)]
          (is (= "a" results) "Pre-existing data should survive norm application"))
        (finally
          (release-conn conn))))))

(deftest kg-norms-tx-norm-tracking-test
  (testing ":tx/norm attribute tracks applied norms"
    (let [conn (fresh-conn)]
      (try
        (norm/ensure-norms! conn (io/resource "hive_mcp/norms/kg"))
        (let [norms (d/q '[:find [?n ...]
                           :where [_ :tx/norm ?n]]
                         (d/db conn))]
          (is (pos? (count norms)) "Should have recorded norm transactions")
          (is (some #(clojure.string/includes? (name %) "001") norms)
              "Should include the 001 norm"))
        (finally
          (release-conn conn))))))
