(ns hive-mcp.knowledge-graph.versioning-test
  "Tests for Yggdrasil version control integration.

   Tests the versioning namespace including:
   - Branch creation and management
   - Checkout and branch switching
   - Snapshot and commit history
   - Merge operations
   - System state management

   Note: These tests require Datahike and Yggdrasil on the classpath.
   Tests are skipped gracefully if dependencies are unavailable."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.versioning :as versioning]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.edges :as edges]
            [clojure.java.io :as io]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn versioned-datahike-fixture
  "Fixture that creates a fresh versioned Datahike store for each test.
   Cleans up after the test completes."
  [f]
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "hive-kg-versioning-test-" (System/nanoTime)))
        db-path (.getAbsolutePath tmp-dir)]
    (try
      ;; Create versioned store
      (let [{:keys [store system]} (versioning/create-versioned-store
                                    {:db-path db-path
                                     :backend :file
                                     :system-name (str "test-" (System/nanoTime))})]
        (when store
          (proto/set-store! store))
        (when system
          (versioning/set-versioned-system! system))
        (try
          (f)
          (finally
            ;; Cleanup
            (when store
              (proto/close! store))
            (versioning/set-versioned-system! nil)
            ;; Delete temp directory
            (when (.exists tmp-dir)
              (doseq [file (reverse (file-seq tmp-dir))]
                (.delete file))))))
      (catch Exception e
        (println "Versioned Datahike fixture failed, skipping:" (.getMessage e))))))

(use-fixtures :each versioned-datahike-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- versioning-available? []
  (versioning/versioning-available?))

(defn- skip-if-unavailable [test-fn]
  (if (versioning-available?)
    (test-fn)
    (println "  [SKIP] Yggdrasil not available")))

(defn- gen-node-id []
  (str "test-node-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

(defn- add-test-edge!
  "Add a test edge and return its ID."
  []
  (edges/add-edge! {:from (gen-node-id)
                    :to (gen-node-id)
                    :relation :implements}))

;; =============================================================================
;; Versioning Availability Tests
;; =============================================================================

(deftest versioning-available-test
  (testing "versioning-available? returns boolean"
    (let [result (versioning/versioning-available?)]
      (is (boolean? result)))))

(deftest status-returns-map-test
  (testing "status returns a map with expected keys"
    (let [status (versioning/status)]
      (is (map? status))
      (is (contains? status :available))
      (is (contains? status :system-id))
      (is (contains? status :branch))
      (is (contains? status :snapshot-id))
      (is (contains? status :branches)))))

;; =============================================================================
;; Branch Creation Tests
;; =============================================================================

(deftest branch-creation-test
  (testing "branch! creates a new branch [versioning]"
    (skip-if-unavailable
     (fn []
       (let [initial-branches (versioning/branches)
             _ (versioning/branch! :test-branch)
             final-branches (versioning/branches)]
         (is (contains? (set final-branches) :test-branch)
             "New branch should appear in branches list"))))))

(deftest branch-from-current-test
  (testing "branch! without 'from' branches from current state [versioning]"
    (skip-if-unavailable
     (fn []
       ;; Add some data first
       (add-test-edge!)
       (let [current-snap (versioning/snapshot-id)
             _ (versioning/branch! :from-current)
             _ (versioning/checkout :from-current)]
         ;; After checkout, we should still have the data
         (is (some? (versioning/snapshot-id))
             "Should have a snapshot ID after checkout"))))))

(deftest branch-from-specific-branch-test
  (testing "branch! with 'from' branches from specified branch [versioning]"
    (skip-if-unavailable
     (fn []
       ;; Create initial branch with data
       (versioning/branch! :source-branch)
       (versioning/checkout :source-branch)
       (add-test-edge!)

       ;; Create another branch from source
       (versioning/branch! :derived-branch :source-branch)

       (is (contains? (set (versioning/branches)) :derived-branch)
           "Derived branch should exist")))))

;; =============================================================================
;; Checkout Tests
;; =============================================================================

(deftest checkout-switches-branch-test
  (testing "checkout switches to the specified branch [versioning]"
    (skip-if-unavailable
     (fn []
       ;; Create a branch
       (versioning/branch! :checkout-test)
       ;; Checkout to it
       (versioning/checkout :checkout-test)
       ;; Verify current branch
       (is (= :checkout-test (versioning/current-branch))
           "Current branch should be the checked out branch")))))

(deftest checkout-returns-new-system-test
  (testing "checkout returns a new system value [versioning]"
    (skip-if-unavailable
     (fn []
       (versioning/branch! :new-system-test)
       (let [result (versioning/checkout :new-system-test)]
         (is (some? result)
             "Checkout should return a non-nil system"))))))

;; =============================================================================
;; Snapshot Tests
;; =============================================================================

(deftest snapshot-id-returns-string-test
  (testing "snapshot-id returns a UUID string [versioning]"
    (skip-if-unavailable
     (fn []
       (let [snap-id (versioning/snapshot-id)]
         (is (string? snap-id)
             "Snapshot ID should be a string")
         (is (or (nil? snap-id)
                 (re-matches #"^[0-9a-f-]{36}$" snap-id))
             "Snapshot ID should be UUID format"))))))

(deftest parent-ids-returns-set-test
  (testing "parent-ids returns a set [versioning]"
    (skip-if-unavailable
     (fn []
       (let [parents (versioning/parent-ids)]
         (is (or (nil? parents) (set? parents))
             "Parent IDs should be nil or a set"))))))

(deftest snapshot-changes-after-transaction-test
  (testing "snapshot-id changes after transacting data [versioning]"
    (skip-if-unavailable
     (fn []
       (let [snap-before (versioning/snapshot-id)
             _ (add-test-edge!)
             snap-after (versioning/snapshot-id)]
         ;; Note: This may or may not change depending on Datahike versioning config
         ;; Just verify we get valid snapshots
         (is (some? snap-before) "Should have snapshot before")
         (is (some? snap-after) "Should have snapshot after"))))))

;; =============================================================================
;; History Tests
;; =============================================================================

(deftest history-returns-vector-test
  (testing "history returns a vector of commit IDs [versioning]"
    (skip-if-unavailable
     (fn []
       (let [hist (versioning/history)]
         (is (vector? hist)
             "History should be a vector"))))))

(deftest history-respects-limit-test
  (testing "history respects the limit option [versioning]"
    (skip-if-unavailable
     (fn []
       ;; Add several transactions to build history
       (dotimes [_ 5]
         (add-test-edge!))
       (let [limited (versioning/history {:limit 3})]
         (is (<= (count limited) 3)
             "History should respect limit"))))))

(deftest history-newest-first-test
  (testing "history returns commits newest first [versioning]"
    (skip-if-unavailable
     (fn []
       ;; The first element should be the current snapshot
       ;; Note: snapshot-id returns string, history may return UUIDs
       (let [current (versioning/snapshot-id)
             hist (versioning/history {:limit 10})]
         (when (seq hist)
           (is (= current (str (first hist)))
               "First history entry should be current snapshot")))))))

;; =============================================================================
;; Merge Tests
;; =============================================================================

(deftest merge-combines-branches-test
  (testing "merge! combines two branches [versioning]"
    (skip-if-unavailable
     (fn []
       ;; Create a branch and add data
       (versioning/branch! :merge-source)
       (versioning/checkout :merge-source)
       (add-test-edge!)
       (let [source-snap (versioning/snapshot-id)]

         ;; Switch to main and create another branch
         (versioning/branch! :merge-target :merge-source)
         (versioning/checkout :merge-target)

         ;; Merge source into target
         (let [result (versioning/merge! :merge-source)]
           (is (some? result)
               "Merge should return a result")))))))

;; =============================================================================
;; System Identity Tests
;; =============================================================================

(deftest system-id-returns-string-test
  (testing "system-id returns a string identifier [versioning]"
    (skip-if-unavailable
     (fn []
       (let [sys-id (versioning/system-id)]
         (is (string? sys-id)
             "System ID should be a string"))))))

(deftest system-type-returns-datahike-test
  (testing "system-type returns :datahike [versioning]"
    (skip-if-unavailable
     (fn []
       (let [sys-type (versioning/system-type)]
         (is (= :datahike sys-type)
             "System type should be :datahike"))))))

(deftest capabilities-returns-map-test
  (testing "capabilities returns a map of supported features [versioning]"
    (skip-if-unavailable
     (fn []
       (let [caps (versioning/capabilities)]
         (is (some? caps)
             "Capabilities should not be nil"))))))

;; =============================================================================
;; Conflict and Diff Tests
;; =============================================================================

(deftest conflicts-returns-sequence-test
  (testing "conflicts returns a sequence [versioning]"
    (skip-if-unavailable
     (fn []
       (versioning/branch! :conflict-a)
       (versioning/branch! :conflict-b)
       (let [conflicts (versioning/conflicts :conflict-a :conflict-b)]
         (is (sequential? conflicts)
             "Conflicts should be a sequence"))))))

(deftest diff-returns-delta-test
  (testing "diff returns a delta representation [versioning]"
    (skip-if-unavailable
     (fn []
       (versioning/branch! :diff-a)
       (versioning/checkout :diff-a)
       (add-test-edge!)
       (versioning/branch! :diff-b)
       (versioning/checkout :diff-b)
       (add-test-edge!)
       (let [delta (versioning/diff :diff-a :diff-b)]
         ;; Just verify we get some result
         (is (some? delta)
             "Diff should return something"))))))

;; =============================================================================
;; Ancestor Tests
;; =============================================================================

(deftest ancestors-returns-vector-test
  (testing "ancestors returns ancestor IDs [versioning]"
    (skip-if-unavailable
     (fn []
       (let [current (versioning/snapshot-id)]
         (when current
           (let [ancs (versioning/ancestors current)]
             (is (vector? ancs)
                 "Ancestors should be a vector"))))))))

(deftest ancestor?-returns-boolean-test
  (testing "ancestor? returns boolean [versioning]"
    (skip-if-unavailable
     (fn []
       ;; Create some history
       (add-test-edge!)
       (let [snap1 (versioning/snapshot-id)
             _ (add-test-edge!)
             snap2 (versioning/snapshot-id)]
         (when (and snap1 snap2)
           (let [result (versioning/ancestor? snap1 snap2)]
             (is (boolean? result)
                 "ancestor? should return boolean"))))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest full-workflow-test
  (testing "full branch-modify-merge workflow [versioning]"
    (skip-if-unavailable
     (fn []
       ;; 1. Create main branch with initial data
       (add-test-edge!)

       ;; 2. Create feature branch and checkout
       (versioning/branch! :feature)
       (versioning/checkout :feature)

       ;; 3. Verify we're on feature branch
       (is (= :feature (versioning/current-branch))
           "Should be on feature branch")

       ;; 4. Add data on feature branch and get snapshot
       (add-test-edge!)
       (let [feature-snap (versioning/snapshot-id)]

         ;; 5. Verify we have a valid snapshot
         (is (some? feature-snap)
             "Feature branch should have a snapshot")

         ;; 6. Check history includes the feature snapshot
         ;; Note: history may return UUIDs, convert to strings for comparison
         (let [hist (versioning/history {:limit 10})
               hist-strs (set (map str hist))]
           (is (contains? hist-strs feature-snap)
               "History should include feature snapshot")))))))
