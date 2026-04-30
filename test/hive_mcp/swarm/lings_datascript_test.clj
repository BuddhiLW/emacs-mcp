(ns hive-mcp.swarm.lings-datascript-test
  "TDD tests for migrating lings-registry atom to DataScript.

   ADR-002 Amendment: DataScript IS the unified Clojure registry.
   These tests verify that DataScript queries can replace lings-registry.

   Migration mapping:
   - register-ling!     → ds/add-slave!
   - unregister-ling!   → ds/remove-slave!
   - get-available-lings → ds/get-all-slaves
   - clear-registry!    → ds/reset-conn!

   SOLID: Tests guide implementation via TDD (red-green-refactor)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.test-fixtures :as tf]
            [hive-test.isolation :as iso]
            hive-mcp.isolation-methods))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each (iso/with-isolations :swarm-ds))

;; =============================================================================
;; SECTION 1: Ling Registration (register-ling! → add-slave!)
;; =============================================================================

(deftest ling-registration-basic-test
  (testing "Registering a ling creates a slave entity in DataScript"
    (let [slave-id "swarm-test-ling-123"
          result (ds/add-slave! slave-id {:name "test-worker"
                                          :presets ["tdd"]
                                          :cwd "/home/test/project"})]
      ;; add-slave! returns a transaction report
      (is (some? result) "add-slave! should return a tx report")
      (is (map? result) "tx report should be a map")

      ;; Verify the slave was created
      (let [slave (ds/get-slave slave-id)]
        (is (some? slave) "Slave should exist after registration")
        (is (= "test-worker" (:slave/name slave)) "Name should match")
        (is (= "/home/test/project" (:slave/cwd slave)) "CWD should match")
        (is (= ["tdd"] (vec (:slave/presets slave))) "Presets should match")))))

(deftest ling-registration-with-timestamp-test
  (testing "Registered ling has created-at timestamp"
    (let [slave-id "swarm-timestamped-ling"
          _ (ds/add-slave! slave-id {:name "timestamped"})
          slave (ds/get-slave slave-id)]
      (is (some? (:slave/created-at slave)) "Should have created-at timestamp")
      (is (inst? (:slave/created-at slave)) "Timestamp should be a Date"))))

(deftest ling-registration-empty-presets-test
  (testing "Registering a ling with no presets works"
    (let [slave-id "swarm-no-presets-ling"
          _ (ds/add-slave! slave-id {:name "no-presets"})
          slave (ds/get-slave slave-id)]
      (is (some? slave) "Slave should exist")
      (is (or (nil? (:slave/presets slave))
              (empty? (:slave/presets slave)))
          "Presets should be nil or empty"))))

(deftest ling-registration-multiple-presets-test
  (testing "Registering a ling with multiple presets"
    (let [slave-id "swarm-multi-preset-ling"
          presets ["tdd" "clarity" "reviewer"]
          _ (ds/add-slave! slave-id {:name "multi-preset"
                                     :presets presets})
          slave (ds/get-slave slave-id)]
      (is (= (set presets) (set (:slave/presets slave)))
          "All presets should be stored"))))

;; =============================================================================
;; SECTION 2: Ling Unregistration (unregister-ling! → remove-slave!)
;; =============================================================================

(deftest ling-unregistration-test
  (testing "Unregistering a ling removes it from DataScript"
    (let [slave-id "swarm-to-remove"]
      ;; First register
      (ds/add-slave! slave-id {:name "to-remove"})
      (is (some? (ds/get-slave slave-id)) "Should exist after registration")

      ;; Then unregister
      (ds/remove-slave! slave-id)
      (is (nil? (ds/get-slave slave-id)) "Should not exist after removal"))))

(deftest ling-unregistration-nonexistent-test
  (testing "Unregistering a non-existent ling is a no-op"
    ;; Should not throw
    (is (nil? (ds/remove-slave! "nonexistent-ling"))
        "Removing non-existent should return nil without error")))

;; =============================================================================
;; SECTION 3: List Lings (get-available-lings → get-all-slaves)
;; =============================================================================

(deftest list-lings-empty-test
  (testing "Empty registry returns empty collection"
    (let [slaves (ds/get-all-slaves)]
      (is (empty? slaves) "Should return empty when no slaves registered"))))

(deftest list-lings-single-test
  (testing "Single ling is returned"
    (ds/add-slave! "single-ling" {:name "single"
                                  :presets ["tdd"]
                                  :cwd "/project"})
    (let [slaves (ds/get-all-slaves)]
      (is (= 1 (count slaves)) "Should have exactly one slave")
      (is (= "single-ling" (:slave/id (first slaves)))))))

(deftest list-lings-multiple-test
  (testing "Multiple lings are all returned"
    (ds/add-slave! "ling-1" {:name "worker-1" :presets ["tdd"]})
    (ds/add-slave! "ling-2" {:name "worker-2" :presets ["reviewer"]})
    (ds/add-slave! "ling-3" {:name "worker-3" :presets ["clarity"]})
    (let [slaves (ds/get-all-slaves)
          ids (set (map :slave/id slaves))]
      (is (= 3 (count slaves)) "Should have all three slaves")
      (is (= #{"ling-1" "ling-2" "ling-3"} ids) "All IDs should be present"))))

(deftest list-lings-metadata-intact-test
  (testing "Listed lings have all metadata intact"
    (ds/add-slave! "detailed-ling" {:name "detailed"
                                    :presets ["tdd" "clarity"]
                                    :cwd "/home/user/project"})
    (let [slaves (ds/get-all-slaves)
          slave (first (filter #(= "detailed-ling" (:slave/id %)) slaves))]
      (is (= "detailed" (:slave/name slave)))
      (is (= (set ["tdd" "clarity"]) (set (:slave/presets slave))))
      (is (= "/home/user/project" (:slave/cwd slave))))))

;; =============================================================================
;; SECTION 4: Clear Registry (clear-registry! → reset-conn!)
;; =============================================================================

(deftest clear-registry-test
  (testing "Resetting clears all lings"
    ;; Add some lings
    (ds/add-slave! "ling-a" {:name "a"})
    (ds/add-slave! "ling-b" {:name "b"})
    (ds/add-slave! "ling-c" {:name "c"})
    (is (= 3 (count (ds/get-all-slaves))) "Should have 3 before reset")

    ;; Reset (test-local conn — see hive-test.isolation)
    (tf/reset-isolated-swarm!)
    (is (empty? (ds/get-all-slaves)) "Should be empty after reset")))

(deftest clear-registry-idempotent-test
  (testing "Resetting an empty registry is safe"
    (tf/reset-isolated-swarm!)
    (tf/reset-isolated-swarm!) ;; Double reset
    (is (empty? (ds/get-all-slaves)) "Should remain empty")))

;; =============================================================================
;; SECTION 5: Registry Format Compatibility
;; =============================================================================

(deftest lings-format-compatibility-test
  (testing "DataScript format can be transformed to old registry format"
    ;; Old format: {slave-id {:name :presets :cwd :spawned-at}}
    ;; DataScript: {:slave/id :slave/name :slave/presets :slave/cwd :slave/created-at}
    (ds/add-slave! "compat-ling" {:name "compatible"
                                  :presets ["tdd"]
                                  :cwd "/project"})
    (let [slaves (ds/get-all-slaves)
          slave (first slaves)
          ;; Transform to old format
          old-format {(:slave/id slave)
                      {:name (:slave/name slave)
                       :presets (vec (:slave/presets slave))
                       :cwd (:slave/cwd slave)
                       :spawned-at (.getTime (:slave/created-at slave))}}]
      (is (map? old-format))
      (is (contains? old-format "compat-ling"))
      (is (= "compatible" (get-in old-format ["compat-ling" :name]))))))

;; =============================================================================
;; SECTION 6: Concurrent Registration (Thread Safety)
;; =============================================================================

(deftest concurrent-registration-test
  (testing "Multiple registrations don't corrupt state"
    (let [ids (map #(str "concurrent-ling-" %) (range 10))
          futures (doall (map #(future (ds/add-slave! % {:name %})) ids))]
      ;; Wait for all to complete
      (doseq [f futures] @f)
      ;; Verify all were registered
      (let [slaves (ds/get-all-slaves)]
        (is (= 10 (count slaves)) "All 10 should be registered")))))

;; =============================================================================
;; SECTION 7: Stats and Debugging
;; =============================================================================

(deftest db-stats-test
  (testing "db-stats reflects lings count"
    (ds/add-slave! "stats-ling-1" {:name "s1"})
    (ds/add-slave! "stats-ling-2" {:name "s2"})
    (let [stats (ds/db-stats)]
      (is (= 2 (:slaves stats)) "Should report 2 slaves"))))

;; =============================================================================
;; Test Summary
;; =============================================================================
;;
;; These tests verify DataScript can replace lings-registry atom.
;; Once passing, migration can proceed by:
;; 1. Updating register-ling! to call ds/add-slave!
;; 2. Updating unregister-ling! to call ds/remove-slave!
;; 3. Updating get-available-lings to call ds/get-all-slaves
;; 4. Updating clear-registry! to call ds/reset-conn!
;; 5. Removing lings-registry atom

(comment
  ;; Run these tests
  (clojure.test/run-tests 'hive-mcp.swarm.lings-datascript-test)

  ;; Run single test
  (clojure.test/test-vars [#'ling-registration-basic-test]))
