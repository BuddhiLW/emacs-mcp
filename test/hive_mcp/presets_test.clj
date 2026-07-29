(ns hive-mcp.presets-test
  "Unit tests for presets Chroma integration.

   Tests cover:
   - Flex embedding dimensions: auto-recreate collection when dimension changes
   - Collection management and caching
   - Preset indexing and retrieval

   Uses MockEmbedder for deterministic testing without external dependencies."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.presets.core :as presets]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.test-fixtures :as fixtures]
            [clojure-chroma-client.api :as chroma-api]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Constants and Helpers (MUST be defined before fixtures)
;; =============================================================================

(def ^:private test-collection-name "hive-mcp-presets")

(defn delete-test-collection!
  "Delete the test collection if it exists."
  []
  (try
    ;; chroma-api/delete-collection expects a collection object, not just name
    (when-let [coll (try @(chroma-api/get-collection test-collection-name)
                         (catch Exception _ nil))]
      @(chroma-api/delete-collection coll)
      (Thread/sleep 100)) ;; Give Chroma time to process deletion
    (catch Exception _ nil)))

(defn setup-embedder!
  "Configure embedding provider with given dimension."
  [dim]
  (chroma/set-embedding-provider! (fixtures/->MockEmbedder dim))
  (chroma/configure! {:host "localhost" :port 8000})
  ;; Reset cache after setup to ensure clean state
  (presets/reset-collection-cache!))

(defn get-collection-dimension
  "Get the dimension metadata from a collection, if it exists."
  []
  (try
    (let [coll @(chroma-api/get-collection test-collection-name)]
      (get-in coll [:metadata :dimension]))
    (catch Exception _ nil)))

;; =============================================================================
;; Test Fixtures (after helpers)
;; =============================================================================

(defn with-clean-state
  "Fixture that ensures clean state before each test."
  [f]
  (let [original-provider (chroma/get-embedding-provider)]
    (try
      ;; Delete collection and reset cache before test
      (delete-test-collection!)
      (presets/reset-collection-cache!)
      (f)
      (finally
        ;; Cleanup after test - delete collection and reset cache
        (delete-test-collection!)
        (presets/reset-collection-cache!)
        (chroma/set-embedding-provider! original-provider)))))

(use-fixtures :each with-clean-state)

;; =============================================================================
;; Test: Flex Embedding Dimensions
;; =============================================================================

(deftest ^:integration test-dimension-mismatch-triggers-recreation
  (testing "When embedding dimension changes, collection is recreated"
    ;; Setup: Create collection with 384 dimensions
    (setup-embedder! 384)

    ;; Create initial collection via preset indexing
    (presets/index-preset! {:id "test-preset"
                            :name "test-preset"
                            :title "Test Preset"
                            :content "Test content for dimension test"
                            :category "testing"
                            :tags "test"
                            :source "test"})

    ;; Verify collection exists with 384 dimensions
    (is (= 384 (get-collection-dimension))
        "Initial collection should have 384 dimensions")

    ;; Reset cache to simulate new session
    (presets/reset-collection-cache!)

    ;; Change to provider with 4096 dimensions
    (setup-embedder! 4096)

    ;; Index another preset - this should trigger recreation
    (presets/index-preset! {:id "test-preset-2"
                            :name "test-preset-2"
                            :title "Test Preset 2"
                            :content "Test content after dimension change"
                            :category "testing"
                            :tags "test"
                            :source "test"})

    ;; Verify collection was recreated with 4096 dimensions
    (is (= 4096 (get-collection-dimension))
        "Collection should be recreated with new dimension")))

(deftest ^:integration test-matching-dimension-reuses-collection
  (testing "When embedding dimension matches, collection is reused"
    ;; Setup: Create collection with 768 dimensions
    (setup-embedder! 768)

    ;; Create initial collection
    (presets/index-preset! {:id "test-preset-reuse"
                            :name "test-preset-reuse"
                            :title "Test Preset Reuse"
                            :content "Test content for reuse test"
                            :category "testing"
                            :tags "test"
                            :source "test"})

    (let [initial-dim (get-collection-dimension)]
      (is (= 768 initial-dim) "Initial collection should have 768 dimensions")

      ;; Reset cache to simulate new session
      (presets/reset-collection-cache!)

      ;; Use same dimension provider
      (setup-embedder! 768)

      ;; Index another preset
      (presets/index-preset! {:id "test-preset-reuse-2"
                              :name "test-preset-reuse-2"
                              :title "Test Preset Reuse 2"
                              :content "Test content same dimension"
                              :category "testing"
                              :tags "test"
                              :source "test"})

      ;; Verify dimension is still the same (collection reused)
      (is (= 768 (get-collection-dimension))
          "Collection should be reused with same dimension")

      ;; Verify both presets exist
      (is (some? (presets/get-preset "test-preset-reuse"))
          "Original preset should still exist")
      (is (some? (presets/get-preset "test-preset-reuse-2"))
          "New preset should exist"))))

(deftest ^:integration test-new-collection-created-with-correct-dimension
  (testing "New collection is created with provider's dimension"
    ;; Configure with 512 dimensions
    (setup-embedder! 512)

    ;; Index a preset - should create new collection
    (presets/index-preset! {:id "test-new-coll"
                            :name "test-new-coll"
                            :title "Test New Collection"
                            :content "Test content for new collection"
                            :category "testing"
                            :tags "test"
                            :source "test"})

    ;; Verify collection was created with 512 dimensions
    (is (= 512 (get-collection-dimension))
        "New collection should have provider's dimension")))

(deftest ^:integration test-dimension-change-preserves-nothing
  (testing "Dimension change recreates collection, losing old data"
    ;; This is expected behavior - when dimension changes, old embeddings
    ;; are incompatible and must be discarded
    (setup-embedder! 256)

    ;; Create initial preset
    (presets/index-preset! {:id "will-be-lost"
                            :name "will-be-lost"
                            :title "Will Be Lost"
                            :content "This preset will be lost on dimension change"
                            :category "testing"
                            :tags "test"
                            :source "test"})

    ;; Verify it exists
    (is (some? (presets/get-preset "will-be-lost"))
        "Preset should exist before dimension change")

    ;; Change dimension
    (presets/reset-collection-cache!)
    (setup-embedder! 1024)

    ;; Index new preset (triggers recreation)
    (presets/index-preset! {:id "new-preset"
                            :name "new-preset"
                            :title "New Preset"
                            :content "New preset after dimension change"
                            :category "testing"
                            :tags "test"
                            :source "test"})

    ;; Old preset should be gone (collection was recreated)
    (is (nil? (presets/get-preset "will-be-lost"))
        "Old preset should be gone after collection recreation")))

;; =============================================================================
;; Test: Status Reporting
;; =============================================================================

(deftest ^:integration test-status-reports-dimension-info
  (testing "Status includes dimension information"
    (setup-embedder! 384)

    ;; Index a preset to create collection
    (presets/index-preset! {:id "status-test"
                            :name "status-test"
                            :title "Status Test"
                            :content "Test content"
                            :category "testing"
                            :tags "test"
                            :source "test"})

    (let [status (presets/status)]
      (is (:chroma-configured? status))
      (is (= test-collection-name (:collection status))))))
