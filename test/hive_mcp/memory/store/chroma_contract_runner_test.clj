(ns hive-mcp.memory.store.chroma-contract-runner-test
  "Runs the backend-agnostic contract tests against ChromaMemoryStore.

   This runner binds contract/*store-factory* so the parameterized tests
   in contract-test execute against the Chroma implementation.

   When Milvus is ready, create an analogous milvus-contract-runner-test
   that binds the same factory to MilvusMemoryStore/create-store."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.memory.store.contract-test :as contract]
            [hive-mcp.memory.store.chroma :as chroma-store]
            [hive-mcp.protocols.memory :as proto]))

;; =============================================================================
;; Factory Binding
;; =============================================================================

(defn bind-chroma-factory
  "Fixture: bind *store-factory* to ChromaMemoryStore for the duration of tests."
  [f]
  (binding [contract/*store-factory* #(chroma-store/create-store)]
    (f)))

(use-fixtures :each bind-chroma-factory)

;; =============================================================================
;; Protocol Satisfaction (runner-specific — verify Chroma implements all 3)
;; =============================================================================

(deftest chroma-satisfies-all-protocols
  (let [store (chroma-store/create-store)]
    (testing "ChromaMemoryStore satisfies IMemoryStore"
      (is (satisfies? proto/IMemoryStore store)))
    (testing "ChromaMemoryStore satisfies IMemoryStoreWithAnalytics"
      (is (satisfies? proto/IMemoryStoreWithAnalytics store)))
    (testing "ChromaMemoryStore satisfies IMemoryStoreWithStaleness"
      (is (satisfies? proto/IMemoryStoreWithStaleness store)))))

;; =============================================================================
;; Contract Test Invocations — IMemoryStore: Connection Lifecycle
;; =============================================================================

(deftest chroma-lifecycle-connected
  (contract/test-lifecycle-connected))

(deftest chroma-lifecycle-health-check-shape
  (contract/test-lifecycle-health-check-shape))

(deftest chroma-lifecycle-store-status-shape
  (contract/test-lifecycle-store-status-shape))

(deftest chroma-lifecycle-connect-then-connected
  (contract/test-lifecycle-connect-then-connected))

(deftest chroma-disconnect-shape
  (contract/test-disconnect-shape))

;; =============================================================================
;; Contract Test Invocations — IMemoryStore: CRUD
;; =============================================================================

(deftest chroma-add-get-roundtrip
  (contract/test-add-get-roundtrip))

(deftest chroma-add-delete-get
  (contract/test-add-delete-get))

(deftest chroma-add-update-get
  (contract/test-add-update-get))

(deftest chroma-add-delete-count-invariant
  (contract/test-add-delete-count-invariant))

;; =============================================================================
;; Contract Test Invocations — IMemoryStore: Query & Search
;; =============================================================================

(deftest chroma-query-entries-by-type
  (contract/test-query-entries-by-type))

(deftest chroma-search-similar-behavioral
  (contract/test-search-similar-behavioral))

;; =============================================================================
;; Contract Test Invocations — IMemoryStore: Duplicate Detection
;; =============================================================================

(deftest chroma-find-duplicate-same-content
  (contract/test-find-duplicate-same-content))

(deftest chroma-find-duplicate-different-content
  (contract/test-find-duplicate-different-content))

;; =============================================================================
;; Contract Test Invocations — IMemoryStore: Expiration
;; =============================================================================

(deftest chroma-expiration-cleanup
  (contract/test-expiration-cleanup))

(deftest chroma-cleanup-expired-idempotent
  (contract/test-cleanup-expired-idempotent))

(deftest chroma-entries-expiring-soon
  (contract/test-entries-expiring-soon))

;; =============================================================================
;; Contract Test Invocations — IMemoryStore: Reset
;; =============================================================================

(deftest chroma-reset-store-idempotent
  (contract/test-reset-store-idempotent))

;; =============================================================================
;; Contract Test Invocations — IMemoryStoreWithAnalytics
;; =============================================================================

(deftest chroma-analytics-log-access
  (contract/test-analytics-log-access))

(deftest chroma-analytics-record-feedback
  (contract/test-analytics-record-feedback))

(deftest chroma-analytics-helpfulness-ratio
  (contract/test-analytics-helpfulness-ratio))

;; =============================================================================
;; Contract Test Invocations — IMemoryStoreWithStaleness
;; =============================================================================

(deftest chroma-staleness-update
  (contract/test-staleness-update))

(deftest chroma-staleness-get-stale-entries
  (contract/test-staleness-get-stale-entries))

(deftest chroma-staleness-propagate
  (contract/test-staleness-propagate))
