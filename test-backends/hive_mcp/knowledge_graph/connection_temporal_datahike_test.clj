(ns hive-mcp.knowledge-graph.connection-temporal-datahike-test
  "Datahike-backed temporal-query tests for the KG connection facade.

   Backend-coupled companion to connection-temporal-test (which covers the
   non-temporal DataScript path). Exercises temporal-store?/history-db/
   as-of-db/since-db/query-history/query-as-of against a real Datahike store.
   Requires datahike on the classpath — runs under the :test-backends alias."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest temporal-store?-datahike-returns-true-test
  (testing "temporal-store? returns true for Datahike"
    (fixtures/datahike-fixture
     (fn []
       (is (true? (conn/temporal-store?)))))))

(deftest history-db-datahike-returns-db-test
  (testing "history-db returns a DB value for Datahike"
    (fixtures/datahike-fixture
     (fn []
       (let [hdb (conn/history-db)]
         (is (some? hdb)))))))

(deftest as-of-db-datahike-returns-past-state-test
  (testing "as-of-db returns past state for Datahike"
    (fixtures/datahike-fixture
     (fn []
       ;; Record time before inserting
       (let [before-time (java.util.Date.)]
         ;; Small delay to ensure timestamp ordering
         (Thread/sleep 10)

         ;; Insert first edge
         (conn/transact! [{:kg-edge/id "edge-1"
                           :kg-edge/from "a"
                           :kg-edge/to "b"
                           :kg-edge/relation :implements
                           :kg-edge/confidence 1.0}])

         (Thread/sleep 10)
         (let [after-first (java.util.Date.)]
           (Thread/sleep 10)

           ;; Insert second edge
           (conn/transact! [{:kg-edge/id "edge-2"
                             :kg-edge/from "c"
                             :kg-edge/to "d"
                             :kg-edge/relation :refines
                             :kg-edge/confidence 0.8}])

           ;; Current state should have both edges
           (let [current-results (conn/query '[:find ?id :where [?e :kg-edge/id ?id]])]
             (is (= 2 (count current-results))))

           ;; as-of-db after first edge should have only edge-1
           (let [past-db (conn/as-of-db after-first)]
             (is (some? past-db))
             (when past-db
               (require 'datahike.api)
               (let [past-results ((resolve 'datahike.api/q)
                                   '[:find ?id :where [?e :kg-edge/id ?id]]
                                   past-db)]
                 (is (= 1 (count past-results)))
                 (is (= #{["edge-1"]} (set past-results))))))

           ;; as-of-db before any edges should have none
           (let [empty-db (conn/as-of-db before-time)]
             (is (some? empty-db))
             (when empty-db
               (require 'datahike.api)
               (let [empty-results ((resolve 'datahike.api/q)
                                    '[:find ?id :where [?e :kg-edge/id ?id]]
                                    empty-db)]
                 (is (= 0 (count empty-results))))))))))))

(deftest since-db-datahike-returns-recent-changes-test
  (testing "since-db returns only recent changes for Datahike"
    (fixtures/datahike-fixture
     (fn []
       ;; Insert first edge
       (conn/transact! [{:kg-edge/id "old-edge"
                         :kg-edge/from "x"
                         :kg-edge/to "y"
                         :kg-edge/relation :implements
                         :kg-edge/confidence 1.0}])

       (Thread/sleep 10)
       (let [split-time (java.util.Date.)]
         (Thread/sleep 10)

         ;; Insert second edge
         (conn/transact! [{:kg-edge/id "new-edge"
                           :kg-edge/from "p"
                           :kg-edge/to "q"
                           :kg-edge/relation :refines
                           :kg-edge/confidence 0.5}])

         ;; since-db should only see the new edge
         (let [since-db (conn/since-db split-time)]
           (is (some? since-db))
           (when since-db
             (require 'datahike.api)
             (let [since-results ((resolve 'datahike.api/q)
                                  '[:find ?id :where [?e :kg-edge/id ?id]]
                                  since-db)]
               ;; since-db contains datoms added AFTER the timestamp
               (is (= 1 (count since-results)))
               (is (= #{["new-edge"]} (set since-results)))))))))))

(deftest query-history-datahike-includes-retracted-test
  (testing "query-history includes retracted facts for Datahike"
    (fixtures/datahike-fixture
     (fn []
       ;; Insert an edge
       (let [tx-result (conn/transact! [{:kg-edge/id "history-edge"
                                         :kg-edge/from "a"
                                         :kg-edge/to "b"
                                         :kg-edge/relation :implements
                                         :kg-edge/confidence 1.0}])
             eid (-> tx-result :tempids (get :db/current-tx) (- 1))]

         ;; Get the actual entity ID
         (let [actual-eid (conn/entid [:kg-edge/id "history-edge"])]
           ;; Retract the :kg-edge/from value and add a new one
           (conn/transact! [[:db/retract actual-eid :kg-edge/from "a"]
                            [:db/add actual-eid :kg-edge/from "updated-a"]])

           ;; Current query should show only the new value
           (let [current (conn/query '[:find ?from
                                       :in $ ?eid
                                       :where
                                       [?e :kg-edge/id ?eid]
                                       [?e :kg-edge/from ?from]]
                                     "history-edge")]
             (is (= #{["updated-a"]} (set current))))

           ;; history-db query should include both the old and new values
           (let [history-results (conn/query-history
                                  '[:find ?from
                                    :in $ ?eid
                                    :where
                                    [?e :kg-edge/id ?eid]
                                    [?e :kg-edge/from ?from]]
                                  "history-edge")]
             ;; History includes all values ever held (both "a" and "updated-a")
             (is (some? history-results))
             (is (= 2 (count history-results)))
             (is (= #{["a"] ["updated-a"]} (set history-results))))))))))

(deftest query-as-of-datahike-returns-point-in-time-test
  (testing "query-as-of returns point-in-time results for Datahike"
    (fixtures/datahike-fixture
     (fn []
       ;; Insert first edge
       (conn/transact! [{:kg-edge/id "time-edge-1"
                         :kg-edge/from "a"
                         :kg-edge/to "b"
                         :kg-edge/relation :implements
                         :kg-edge/confidence 1.0}])

       (Thread/sleep 10)
       (let [snapshot-time (java.util.Date.)]
         (Thread/sleep 10)

         ;; Insert second edge
         (conn/transact! [{:kg-edge/id "time-edge-2"
                           :kg-edge/from "c"
                           :kg-edge/to "d"
                           :kg-edge/relation :refines
                           :kg-edge/confidence 0.5}])

         ;; query-as-of at snapshot time should only return first edge
         (let [past-results (conn/query-as-of
                             snapshot-time
                             '[:find ?id :where [?e :kg-edge/id ?id]])]
           (is (some? past-results))
           (is (= 1 (count past-results)))
           (is (= #{["time-edge-1"]} (set past-results)))))))))
