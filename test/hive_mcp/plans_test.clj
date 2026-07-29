(ns hive-mcp.plans-test
  "Unit tests for plans Chroma integration.

   Tests cover:
   - Plan indexing and retrieval
   - Semantic search over plans
   - Query with metadata filtering
   - Plan status updates
   - Flex embedding dimensions (auto-recreate)
   - Plan-specific metadata (steps-count, decision-id, etc.)

   Uses MockEmbedder for deterministic testing without external dependencies."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.plan.plans :as plans]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.test-fixtures :as fixtures]
            [clojure-chroma-client.api :as chroma-api]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Constants and Helpers
;; =============================================================================

(def ^:private test-collection-name "hive-mcp-plans")

(defn delete-test-collection!
  "Delete the test collection if it exists."
  []
  (try
    (when-let [coll (try @(chroma-api/get-collection test-collection-name)
                         (catch Exception _ nil))]
      @(chroma-api/delete-collection coll)
      (Thread/sleep 100))
    (catch Exception _ nil)))

(defn setup-embedder!
  "Configure embedding provider with given dimension."
  [dim]
  (chroma/set-embedding-provider! (fixtures/->MockEmbedder dim))
  (chroma/configure! {:host "localhost" :port 8000})
  (plans/reset-collection-cache!))

(defn get-collection-dimension
  "Get the dimension metadata from collection, if it exists."
  []
  (try
    (let [coll @(chroma-api/get-collection test-collection-name)]
      (get-in coll [:metadata :dimension]))
    (catch Exception _ nil)))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn with-clean-state
  "Fixture that ensures clean state before each test."
  [f]
  (let [original-provider (chroma/get-embedding-provider)]
    (try
      (delete-test-collection!)
      (plans/reset-collection-cache!)
      (f)
      (finally
        (delete-test-collection!)
        (plans/reset-collection-cache!)
        (chroma/set-embedding-provider! original-provider)))))

(use-fixtures :each with-clean-state)

;; =============================================================================
;; Sample Plan Data
;; =============================================================================

(def ^:private sample-plan
  {:type "plan"
   :content "# Plan: Implement Authentication\n\n## Step 1: Add JWT library\nAdd ring-jwt dependency to project.clj\n\n## Step 2: Create auth middleware\nWrap routes with JWT validation\n\n## Step 3: Add login endpoint\nCreate POST /api/login handler\n\n## Step 4: Add token refresh\nImplement refresh token rotation"
   :tags ["auth" "jwt" "security" "scope:project:test-project"]
   :project-id "test-project"
   :duration "long"
   :content-hash "abc123"
   :plan-status "active"
   :decision-id "20260206-decision-auth"
   :wave-count 2})

(def ^:private sample-plan-2
  {:type "plan"
   :content "# Plan: Database Migration\n\n1. Backup existing data\n2. Create new schema\n3. Migrate records\n4. Verify integrity\n5. Switch traffic"
   :tags ["database" "migration" "scope:project:test-project"]
   :project-id "test-project"
   :duration "medium"
   :plan-status "draft"})

;; =============================================================================
;; Test: Index and Retrieve
;; =============================================================================

(deftest ^:integration test-index-and-get-plan
  (testing "Can index a plan and retrieve it by ID"
    (setup-embedder! 384)
    (let [plan-id (plans/index-plan! (assoc sample-plan :id "test-plan-1"))]
      (is (= "test-plan-1" plan-id)
          "index-plan! should return the plan ID")

      (let [retrieved (plans/get-plan "test-plan-1")]
        (is (some? retrieved)
            "get-plan should find the indexed plan")
        (is (= "plan" (:type retrieved))
            "Type should be 'plan'")
        (is (= "active" (:plan-status retrieved))
            "Plan status should be preserved")
        (is (= "20260206-decision-auth" (:decision-id retrieved))
            "Decision ID should be preserved")
        (is (string? (:content retrieved))
            "Content should be a string")))))

(deftest ^:integration test-index-plan-auto-generates-id
  (testing "index-plan! auto-generates ID when not provided"
    (setup-embedder! 384)
    (let [plan-id (plans/index-plan! (dissoc sample-plan :id))]
      (is (string? plan-id)
          "Auto-generated ID should be a string")
      (is (pos? (count plan-id))
          "Auto-generated ID should not be empty"))))

(deftest ^:integration test-get-plan-not-found
  (testing "get-plan returns nil for non-existent plan"
    (setup-embedder! 384)
    ;; Force collection creation by indexing something first
    (plans/index-plan! (assoc sample-plan :id "existing"))
    (is (nil? (plans/get-plan "non-existent-plan-id"))
        "Should return nil for non-existent plan")))

;; =============================================================================
;; Test: Plan Metadata
;; =============================================================================

(deftest ^:integration test-steps-count-auto-detection
  (testing "Steps count is auto-detected from content"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "steps-test"))
    (let [plan (plans/get-plan "steps-test")]
      (is (some? plan) "Plan should be retrievable")
      (is (number? (:steps-count plan))
          "Steps count should be auto-detected")
      (is (pos? (:steps-count plan))
          "Steps count should be positive for plan with ## Step headers"))))

(deftest ^:integration test-plan-metadata-preserved
  (testing "Plan-specific metadata is preserved through index/get cycle"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "meta-test"))
    (let [plan (plans/get-plan "meta-test")]
      (is (= "test-project" (:project-id plan)))
      (is (= "long" (:duration plan)))
      (is (= "active" (:plan-status plan)))
      (is (= "20260206-decision-auth" (:decision-id plan)))
      (is (= 2 (:wave-count plan)))
      (is (= 4 (:abstraction-level plan))
          "Plans should have abstraction level 4 (Intent)"))))

;; =============================================================================
;; Test: Search
;; =============================================================================

(deftest ^:integration test-search-plans
  (testing "Can search plans by semantic similarity"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "search-auth"))
    (plans/index-plan! (assoc sample-plan-2 :id "search-db"))

    (let [results (plans/search-plans "authentication JWT"
                                      :limit 5)]
      (is (sequential? results)
          "Search should return a sequence")
      (is (pos? (count results))
          "Search should find results")
      (is (every? :id results)
          "Each result should have an ID")
      (is (every? :distance results)
          "Each result should have a distance score"))))

(deftest ^:integration test-search-plans-with-project-filter
  (testing "Can filter search results by project-id"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "proj-1" :project-id "project-a"))
    (plans/index-plan! (assoc sample-plan-2 :id "proj-2" :project-id "project-b"))

    (let [results (plans/search-plans "plan"
                                      :project-id "project-a"
                                      :limit 10)]
      (is (sequential? results))
      ;; All results should be from project-a
      (is (every? #(= "project-a" (:project-id %)) results)
          "Results should only contain project-a plans"))))

;; =============================================================================
;; Test: Query
;; =============================================================================

(deftest ^:integration test-query-plans
  (testing "Can query plans with metadata filtering"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "query-1"))
    (plans/index-plan! (assoc sample-plan-2 :id "query-2"))

    (let [all-plans (plans/query-plans :limit 20)]
      (is (sequential? all-plans))
      (is (= 2 (count all-plans))
          "Should find both plans"))))

(deftest ^:integration test-query-plans-by-status
  (testing "Can filter plans by status"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "active-1" :plan-status "active"))
    (plans/index-plan! (assoc sample-plan-2 :id "draft-1" :plan-status "draft"))

    (let [active (plans/query-plans :plan-status "active")]
      (is (every? #(= "active" (:plan-status %)) active)
          "Should only return active plans"))))

;; =============================================================================
;; Test: Status Update
;; =============================================================================

(deftest ^:integration test-update-plan-status
  (testing "Can update plan status"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "status-update" :plan-status "draft"))

    ;; Verify initial status
    (let [plan (plans/get-plan "status-update")]
      (is (= "draft" (:plan-status plan))))

    ;; Update status
    (plans/update-plan-status! "status-update" "active")

    ;; Verify new status
    (let [updated (plans/get-plan "status-update")]
      (is (= "active" (:plan-status updated))
          "Plan status should be updated to active"))))

(deftest ^:integration test-update-plan-status-invalid
  (testing "Invalid status throws exception"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "invalid-status"))
    (is (thrown? clojure.lang.ExceptionInfo
                 (plans/update-plan-status! "invalid-status" "bogus"))
        "Should throw for invalid status")))

;; =============================================================================
;; Test: Delete
;; =============================================================================

(deftest ^:integration test-delete-plan
  (testing "Can delete a plan"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "to-delete"))

    ;; Verify exists
    (is (some? (plans/get-plan "to-delete")))

    ;; Delete
    (plans/delete-plan! "to-delete")

    ;; Verify gone
    (is (nil? (plans/get-plan "to-delete"))
        "Deleted plan should not be retrievable")))

;; =============================================================================
;; Test: Flex Embedding Dimensions
;; =============================================================================

(deftest ^:integration test-dimension-mismatch-triggers-recreation
  (testing "When embedding dimension changes, plans collection is recreated"
    (setup-embedder! 384)

    (plans/index-plan! (assoc sample-plan :id "dim-test"))
    (is (= 384 (get-collection-dimension))
        "Initial collection should have 384 dimensions")

    (plans/reset-collection-cache!)
    (setup-embedder! 4096)

    (plans/index-plan! (assoc sample-plan-2 :id "dim-test-2"))
    (is (= 4096 (get-collection-dimension))
        "Collection should be recreated with new dimension")))

;; =============================================================================
;; Test: Status Reporting
;; =============================================================================

(deftest ^:integration test-status-reports-info
  (testing "Status includes plan collection information"
    (setup-embedder! 384)
    (plans/index-plan! (assoc sample-plan :id "status-info"))

    (let [status (plans/status)]
      (is (:chroma-configured? status))
      (is (= test-collection-name (:collection status)))
      (is (pos? (:count status))
          "Count should be positive after indexing"))))
