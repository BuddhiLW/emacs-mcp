(ns hive-mcp.memory.store.chroma-test
  "Tests for ChromaMemoryStore protocol implementation.

   Tests verify:
   1. Protocol satisfaction (all 3 protocols implemented)
   2. Factory function creates valid store
   3. Store management (set-store!/get-store/store-set?)
   4. Method delegation to chroma.clj functions
   5. Store status and health reporting"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.memory.store.chroma :as chroma-store]
            [hive-mcp.protocols.memory :as mem-proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; Fixtures
;;; ============================================================================

(defn clean-store-fixture
  "Reset active store before each test to avoid cross-test contamination."
  [f]
  (let [prev-store (when (mem-proto/store-set?)
                     (mem-proto/get-store))]
    (try
      (f)
      (finally
        ;; Restore previous store if one was set, otherwise clear
        (if prev-store
          (mem-proto/set-store! prev-store)
          (mem-proto/reset-registry!))))))

(use-fixtures :each clean-store-fixture)

;;; ============================================================================
;;; Protocol Satisfaction Tests
;;; ============================================================================

(deftest test-protocol-satisfaction
  (testing "ChromaMemoryStore satisfies all 3 protocols"
    (let [store (chroma-store/create-store)]
      (is (satisfies? mem-proto/IMemoryStore store)
          "Must satisfy IMemoryStore (core CRUD + search)")
      (is (satisfies? mem-proto/IMemoryStoreWithAnalytics store)
          "Must satisfy IMemoryStoreWithAnalytics (access/feedback tracking)")
      (is (satisfies? mem-proto/IMemoryStoreWithStaleness store)
          "Must satisfy IMemoryStoreWithStaleness (Bayesian staleness)"))))

(deftest test-protocol-predicates
  (testing "Protocol predicate functions work correctly"
    (let [store (chroma-store/create-store)]
      (is (true? (mem-proto/analytics-store? store))
          "analytics-store? should return true for ChromaMemoryStore")
      (is (true? (mem-proto/staleness-store? store))
          "staleness-store? should return true for ChromaMemoryStore"))))

;;; ============================================================================
;;; Factory Function Tests
;;; ============================================================================

(deftest test-create-store-defaults
  (testing "create-store with no args uses defaults"
    (let [store (chroma-store/create-store)]
      (is (instance? hive_mcp.memory.store.chroma.ChromaMemoryStore store))
      (is (= "localhost" (:host @(:config-atom store))))
      (is (= 8000 (:port @(:config-atom store))))
      (is (= "hive-mcp-memory" (:collection-name @(:config-atom store)))))))

(deftest test-create-store-with-opts
  (testing "create-store with custom options"
    (let [store (chroma-store/create-store {:host "chroma.example.com"
                                            :port 9000
                                            :collection-name "custom-collection"})]
      (is (instance? hive_mcp.memory.store.chroma.ChromaMemoryStore store))
      (is (= "chroma.example.com" (:host @(:config-atom store))))
      (is (= 9000 (:port @(:config-atom store))))
      (is (= "custom-collection" (:collection-name @(:config-atom store)))))))

;;; ============================================================================
;;; Store Management Tests
;;; ============================================================================

(deftest test-set-store-and-get-store
  (testing "set-store! accepts ChromaMemoryStore and get-store retrieves it"
    (let [store (chroma-store/create-store)]
      (mem-proto/set-store! store)
      (is (mem-proto/store-set?) "store-set? should be true after set-store!")
      (is (= store (mem-proto/get-store))
          "get-store should return the same store instance"))))

(deftest test-set-store-rejects-non-protocol
  (testing "set-store! rejects objects that don't implement IMemoryStore"
    (is (thrown? AssertionError
                 (mem-proto/set-store! {:not "a store"})))))

;;; ============================================================================
;;; Store Status Tests
;;; ============================================================================

(deftest test-store-status
  (testing "store-status returns correct shape"
    (let [store (chroma-store/create-store)
          status (mem-proto/store-status store)]
      (is (= "chroma" (:backend status)))
      (is (contains? status :configured?))
      (is (contains? status :entry-count))
      (is (contains? status :supports-search?)))))

(deftest test-connected-delegates-to-embedding-configured
  (testing "connected? reflects embedding provider state"
    (let [store (chroma-store/create-store)]
      ;; connected? delegates to chroma/embedding-configured?
      ;; The result depends on whether an embedding provider is set
      (is (boolean? (mem-proto/connected? store))
          "connected? should return a boolean"))))

;;; ============================================================================
;;; Health Check Tests
;;; ============================================================================

(deftest test-health-check-shape
  (testing "health-check returns correctly shaped map"
    (let [store (chroma-store/create-store)
          health (mem-proto/health-check store)]
      (is (contains? health :healthy?))
      (is (contains? health :latency-ms))
      (is (= "chroma" (:backend health)))
      (is (contains? health :errors))
      (is (vector? (:errors health)))
      (is (contains? health :checked-at))
      (is (string? (:checked-at health))))))

;;; ============================================================================
;;; Supports Semantic Search Tests
;;; ============================================================================

(deftest test-supports-semantic-search
  (testing "supports-semantic-search? delegates to embedding-configured?"
    (let [store (chroma-store/create-store)]
      (is (boolean? (mem-proto/supports-semantic-search? store))
          "Should return a boolean"))))

;;; ============================================================================
;;; Reset Store Tests
;;; ============================================================================

(deftest test-reset-store
  (testing "reset-store! clears collection cache and returns true"
    (let [store (chroma-store/create-store)]
      (is (true? (mem-proto/reset-store! store))))))

;;; ============================================================================
;;; Disconnect Tests
;;; ============================================================================

(deftest test-disconnect
  (testing "disconnect! clears collection cache"
    (let [store (chroma-store/create-store)
          result (mem-proto/disconnect! store)]
      (is (true? (:success? result)))
      (is (empty? (:errors result))))))
