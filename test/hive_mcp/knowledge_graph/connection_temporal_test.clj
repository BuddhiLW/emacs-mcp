(ns hive-mcp.knowledge-graph.connection-temporal-test
  "Unit tests for Knowledge Graph temporal query facade.

   Tests the W3 temporal query functions in connection.clj:
   - temporal-store? predicate
   - history-db, as-of-db, since-db
   - query-history, query-as-of

   Covers the DataScript (non-temporal) path — graceful nil/false when
   temporal features are unavailable. The Datahike (temporal) path lives in
   connection-temporal-datahike-test under the :test-backends alias."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Non-Temporal Backend Tests (DataScript)
;; =============================================================================

(deftest temporal-store?-datascript-returns-false-test
  (testing "temporal-store? returns false for DataScript"
    (fixtures/datascript-fixture
     (fn []
       (is (false? (conn/temporal-store?)))))))

(deftest history-db-datascript-returns-nil-test
  (testing "history-db returns nil for non-temporal store"
    (fixtures/datascript-fixture
     (fn []
       (is (nil? (conn/history-db)))))))

(deftest as-of-db-datascript-returns-nil-test
  (testing "as-of-db returns nil for non-temporal store"
    (fixtures/datascript-fixture
     (fn []
       (is (nil? (conn/as-of-db (java.util.Date.))))))))

(deftest since-db-datascript-returns-nil-test
  (testing "since-db returns nil for non-temporal store"
    (fixtures/datascript-fixture
     (fn []
       (is (nil? (conn/since-db (java.util.Date.))))))))

(deftest query-history-datascript-returns-nil-test
  (testing "query-history returns nil for non-temporal store"
    (fixtures/datascript-fixture
     (fn []
       (is (nil? (conn/query-history '[:find ?e :where [?e :kg-edge/id _]])))))))

(deftest query-as-of-datascript-returns-nil-test
  (testing "query-as-of returns nil for non-temporal store"
    (fixtures/datascript-fixture
     (fn []
       (is (nil? (conn/query-as-of (java.util.Date.)
                                   '[:find ?e :where [?e :kg-edge/id _]])))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest temporal-functions-handle-nil-store-gracefully-test
  (testing "Temporal functions handle missing store gracefully"
    ;; This test verifies the facade doesn't crash when store is not set
    ;; (though in practice ensure-store! will auto-initialize)
    (fixtures/datascript-fixture
     (fn []
       ;; All these should return nil safely for non-temporal store
       (is (false? (conn/temporal-store?)))
       (is (nil? (conn/history-db)))
       (is (nil? (conn/as-of-db (java.util.Date.))))
       (is (nil? (conn/since-db (java.util.Date.))))
       (is (nil? (conn/query-history '[:find ?e :where [?e :kg-edge/id _]])))
       (is (nil? (conn/query-as-of (java.util.Date.)
                                   '[:find ?e :where [?e :kg-edge/id _]])))))))
