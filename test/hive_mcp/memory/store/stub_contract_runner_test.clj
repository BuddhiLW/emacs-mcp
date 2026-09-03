(ns hive-mcp.memory.store.stub-contract-runner-test
  "Runs the backend-agnostic contract tests against the in-repo stub store.

   Unlike chroma-contract-runner-test these are NOT ^:integration: the stub
   needs no driver, so the IMemoryStore contract is exercised on every cold CI
   run. A stub that drifts from the contract stops being a valid substitute for
   a real backend in the tests that inject it."
  (:require [clojure.test :refer [deftest use-fixtures is testing]]
            [hive-test.memory.store-contract :as contract]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.test.stub.memory-store :as stub]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn bind-stub-factory
  "Fixture: bind *store-factory* to the stub for the duration of tests."
  [f]
  (binding [contract/*store-factory* #(stub/->stub)]
    (f)))

(use-fixtures :each bind-stub-factory)

;; =============================================================================
;; Protocol satisfaction
;; =============================================================================

(deftest stub-satisfies-all-protocols
  (let [store (stub/->stub)]
    (testing "satisfies IMemoryStore"
      (is (satisfies? proto/IMemoryStore store)))
    (testing "satisfies IMemoryStoreWithAnalytics"
      (is (satisfies? proto/IMemoryStoreWithAnalytics store)))
    (testing "satisfies IMemoryStoreWithStaleness"
      (is (satisfies? proto/IMemoryStoreWithStaleness store)))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(deftest stub-lifecycle-connected (contract/test-lifecycle-connected))
(deftest stub-lifecycle-health-check-shape (contract/test-lifecycle-health-check-shape))
(deftest stub-lifecycle-store-status-shape (contract/test-lifecycle-store-status-shape))
(deftest stub-lifecycle-connect-then-connected (contract/test-lifecycle-connect-then-connected))
(deftest stub-disconnect-shape (contract/test-disconnect-shape))

;; =============================================================================
;; CRUD
;; =============================================================================

(deftest stub-add-get-roundtrip (contract/test-add-get-roundtrip))
(deftest stub-add-delete-get (contract/test-add-delete-get))
(deftest stub-add-update-get (contract/test-add-update-get))
(deftest stub-add-delete-count-invariant (contract/test-add-delete-count-invariant))

;; =============================================================================
;; Query & search
;; =============================================================================

(deftest stub-query-entries-by-type (contract/test-query-entries-by-type))
(deftest stub-search-similar-behavioral (contract/test-search-similar-behavioral))

;; =============================================================================
;; Duplicate detection
;; =============================================================================

(deftest stub-find-duplicate-same-content (contract/test-find-duplicate-same-content))
(deftest stub-find-duplicate-different-content (contract/test-find-duplicate-different-content))

;; =============================================================================
;; Expiration
;; =============================================================================

(deftest stub-expiration-cleanup (contract/test-expiration-cleanup))
(deftest stub-cleanup-expired-shape (contract/test-cleanup-expired-shape))
(deftest stub-cleanup-expired-idempotent (contract/test-cleanup-expired-idempotent))
(deftest stub-entries-expiring-soon (contract/test-entries-expiring-soon))

;; =============================================================================
;; Reset
;; =============================================================================

(deftest stub-reset-store-idempotent (contract/test-reset-store-idempotent))

;; =============================================================================
;; Analytics
;; =============================================================================

(deftest stub-analytics-log-access (contract/test-analytics-log-access))
(deftest stub-analytics-record-feedback (contract/test-analytics-record-feedback))
(deftest stub-analytics-helpfulness-ratio (contract/test-analytics-helpfulness-ratio))

;; =============================================================================
;; Staleness
;; =============================================================================

(deftest stub-staleness-update (contract/test-staleness-update))
(deftest stub-staleness-get-stale-entries (contract/test-staleness-get-stale-entries))
(deftest stub-staleness-propagate (contract/test-staleness-propagate))
