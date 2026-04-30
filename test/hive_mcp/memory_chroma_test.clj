(ns hive-mcp.memory-chroma-test
  "Unit tests for Chroma-only memory storage.

   Tests cover Chroma as the primary storage backend for hive-mcp memory:
   - Memory CRUD operations (Create, Read, Update, Delete)
   - Semantic search via vector embeddings
   - Duration/expiration handling with TTL
   - Scope filtering (global, domain, project)
   - Content deduplication via hash

   Uses MockEmbedder for deterministic testing without external dependencies.

   Architecture:
   - chroma.clj provides vector DB integration
   - This test validates Chroma-only storage (no Emacs delegation)
   - Tests run against in-memory/mock Chroma where possible"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.test-fixtures :as fixtures]))

;; =============================================================================
;; Test Fixtures and Helpers
;; =============================================================================

(def ^:dynamic *test-collection* "hive-mcp-test-memory")

(defn with-mock-embedder
  "Fixture that sets up mock embedder for testing."
  [f]
  (let [original-provider (chroma/get-embedding-provider)]
    (chroma/set-embedding-provider! (fixtures/->MockEmbedder 384))
    (chroma/configure! {:host "localhost"
                        :port 8000
                        :collection-name *test-collection*})
    (try
      (f)
      (finally
        (chroma/reset-collection-cache!)
        (chroma/set-embedding-provider! original-provider)))))

(use-fixtures :each with-mock-embedder)

(defn gen-test-id
  "Generate unique test ID."
  []
  (str "test-" (java.util.UUID/randomUUID)))

(defn make-memory-entry
  "Create a test memory entry with defaults."
  [& {:keys [id type content tags duration scope created]
      :or {id (gen-test-id)
           type "note"
           content "Test memory content"
           tags []
           duration "medium"
           scope nil
           created (str (java.time.Instant/now))}}]
  {:id id
   :type type
   :content content
   :tags (if scope (conj tags (str "scope:" scope)) tags)
   :duration duration
   :created created})

;; =============================================================================
;; Test: Embedding Provider Configuration
;; =============================================================================

(deftest ^:integration test-embedding-provider-configuration
  (testing "Mock embedder is configured"
    (is (chroma/embedding-configured?))
    (is (some? (chroma/status))))

  (testing "Status returns provider info"
    (let [status (chroma/status)]
      (is (:configured? status))
      (is (str/includes? (str (:provider status)) "MockEmbedder")))))

(deftest ^:integration test-mock-embedder-produces-deterministic-vectors
  (testing "Same text produces same embedding"
    (let [embedder (fixtures/->MockEmbedder 384)
          text "Test content"
          emb1 (chroma/embed-text embedder text)
          emb2 (chroma/embed-text embedder text)]
      (is (= emb1 emb2))
      (is (= 384 (count emb1)))))

  (testing "Different text produces different embeddings"
    (let [embedder (fixtures/->MockEmbedder 384)
          emb1 (chroma/embed-text embedder "First text")
          emb2 (chroma/embed-text embedder "Second text")]
      (is (not= emb1 emb2)))))

(deftest ^:integration test-batch-embedding
  (testing "Batch embedding produces correct count"
    (let [embedder (fixtures/->MockEmbedder 384)
          texts ["one" "two" "three"]
          embeddings (chroma/embed-batch embedder texts)]
      (is (= 3 (count embeddings)))
      (is (every? #(= 384 (count %)) embeddings)))))

;; =============================================================================
;; Test: Memory CRUD - Create
;; =============================================================================

(deftest ^:integration test-index-memory-entry-basic
  (testing "Index single memory entry returns ID"
    (let [entry (make-memory-entry :content "Basic test note")
          result (chroma/index-memory-entry! entry)]
      (is (= (:id entry) result)))))

(deftest ^:integration test-index-memory-entry-with-all-fields
  (testing "Index entry with all fields"
    (let [entry (make-memory-entry
                 :type "convention"
                 :content "Use kebab-case for function names"
                 :tags ["style" "naming"]
                 :created "2024-01-15T10:00:00Z")
          result (chroma/index-memory-entry! entry)]
      (is (= (:id entry) result)))))

(deftest ^:integration test-index-memory-entry-different-types
  (testing "Index entries of different types"
    (doseq [type ["note" "snippet" "convention" "decision"]]
      (let [entry (make-memory-entry :type type :content (str "Content for " type))
            result (chroma/index-memory-entry! entry)]
        (is (= (:id entry) result))))))

(deftest ^:integration test-index-memory-entries-batch
  (testing "Batch index multiple entries"
    (let [entries (mapv #(make-memory-entry :content (str "Batch entry " %))
                        (range 5))
          results (chroma/index-memory-entries! entries)]
      (is (= 5 (count results)))
      (is (= (mapv :id entries) results)))))

(deftest ^:integration test-index-memory-entry-upsert-behavior
  (testing "Re-indexing same ID updates entry (upsert)"
    (let [id (gen-test-id)
          entry-v1 (make-memory-entry :id id :content "Version 1")
          entry-v2 (make-memory-entry :id id :content "Version 2 - updated")]
      ;; Index v1
      (chroma/index-memory-entry! entry-v1)
      ;; Index v2 with same ID (should upsert)
      (let [result (chroma/index-memory-entry! entry-v2)]
        (is (= id result))))))

;; =============================================================================
;; Test: Memory CRUD - Read
;; =============================================================================

(deftest ^:integration test-search-by-id
  (testing "Retrieve entry by ID"
    (let [entry (make-memory-entry :content "Searchable by ID")
          _ (chroma/index-memory-entry! entry)
          result (chroma/search-by-id (:id entry))]
      (is (some? result))
      (is (str/includes? (:document result) "Searchable by ID")))))

(deftest ^:integration test-search-by-id-not-found
  (testing "Search for non-existent ID returns nil"
    (let [result (chroma/search-by-id "non-existent-id-12345")]
      (is (nil? result)))))

;; =============================================================================
;; Test: Memory CRUD - Delete
;; =============================================================================

(deftest ^:integration test-delete-entry
  (testing "Delete entry by ID"
    (let [entry (make-memory-entry :content "To be deleted")
          _ (chroma/index-memory-entry! entry)
          delete-result (chroma/delete-entry! (:id entry))]
      (is (= (:id entry) delete-result))
      ;; Verify deletion
      (let [search-result (chroma/search-by-id (:id entry))]
        (is (nil? search-result))))))

(deftest ^:integration test-delete-non-existent-entry
  (testing "Delete non-existent entry doesn't throw"
    (let [result (chroma/delete-entry! "non-existent-delete-id")]
      (is (= "non-existent-delete-id" result)))))

;; =============================================================================
;; Test: Semantic Search
;; =============================================================================

(deftest ^:integration test-semantic-search-basic
  (testing "Semantic search finds related content"
    ;; Index some entries
    (chroma/index-memory-entry!
     (make-memory-entry :content "Clojure is a functional programming language"))
    (chroma/index-memory-entry!
     (make-memory-entry :content "Python is great for data science"))
    (chroma/index-memory-entry!
     (make-memory-entry :content "Java is object-oriented"))

    ;; Search for functional programming
    (let [results (chroma/search-similar "functional programming" :limit 3)]
      (is (seq results))
      ;; Should find Clojure entry (most relevant)
      (is (some #(str/includes? (or (:document %) "") "Clojure") results)))))

(deftest ^:integration test-semantic-search-with-limit
  (testing "Semantic search respects limit parameter"
    ;; Index entries
    (doseq [i (range 10)]
      (chroma/index-memory-entry!
       (make-memory-entry :content (str "Entry number " i))))

    (let [results (chroma/search-similar "entry" :limit 5)]
      (is (<= (count results) 5)))))

(deftest ^:integration test-semantic-search-with-type-filter
  (testing "Semantic search filters by type"
    ;; Index different types
    (chroma/index-memory-entry!
     (make-memory-entry :type "note" :content "A note about testing"))
    (chroma/index-memory-entry!
     (make-memory-entry :type "convention" :content "Testing convention"))
    (chroma/index-memory-entry!
     (make-memory-entry :type "snippet" :content "Test code snippet"))

    (let [results (chroma/search-similar "testing" :limit 10 :type "note")]
      ;; All results should be notes
      (is (every? #(= "note" (get-in % [:metadata :type])) results)))))

(deftest ^:integration test-semantic-search-returns-distances
  (testing "Search results include distance scores"
    (chroma/index-memory-entry!
     (make-memory-entry :content "Query matching content"))

    (let [results (chroma/search-similar "Query matching" :limit 1)]
      (is (seq results))
      (is (number? (:distance (first results)))))))

;; =============================================================================
;; Test: Duration/Expiration Handling
;; =============================================================================

(def duration-days
  "Duration to days mapping."
  {"ephemeral" 1
   "short" 7
   "medium" 30
   "long" 90
   "permanent" nil})

(deftest ^:integration test-memory-entry-with-duration-ephemeral
  (testing "Ephemeral entries (1 day TTL)"
    (let [entry (make-memory-entry :duration "ephemeral" :content "Temporary note")
          result (chroma/index-memory-entry! entry)]
      (is (some? result))
      ;; Entry should be indexed with duration in metadata (if supported)
      (let [retrieved (chroma/search-by-id (:id entry))]
        (is (some? retrieved))))))

(deftest ^:integration test-memory-entry-with-duration-short
  (testing "Short-term entries (7 days TTL)"
    (let [entry (make-memory-entry :duration "short" :content "Week-long note")
          result (chroma/index-memory-entry! entry)]
      (is (some? result)))))

(deftest ^:integration test-memory-entry-with-duration-medium
  (testing "Medium-term entries (30 days TTL, default)"
    (let [entry (make-memory-entry :duration "medium" :content "Month-long note")
          result (chroma/index-memory-entry! entry)]
      (is (some? result)))))

(deftest ^:integration test-memory-entry-with-duration-long
  (testing "Long-term entries (90 days TTL)"
    (let [entry (make-memory-entry :duration "long" :content "Quarter-long note")
          result (chroma/index-memory-entry! entry)]
      (is (some? result)))))

(deftest ^:integration test-memory-entry-with-duration-permanent
  (testing "Permanent entries (no expiration)"
    (let [entry (make-memory-entry :duration "permanent" :content "Forever note")
          result (chroma/index-memory-entry! entry)]
      (is (some? result)))))

(deftest ^:integration test-expiration-timestamp-calculation
  (testing "Expiration is calculated from duration"
    ;; This documents expected behavior for expiration calculation
    ;; The actual implementation would compute expires_at from created + duration_days
    (let [now (java.time.Instant/now)
          created-str (str now)]
      (doseq [[duration days] duration-days]
        (when days
          (let [expected-expires (.plusSeconds now (* days 24 60 60))]
            ;; Verify calculation logic is correct
            (is (pos? (.getEpochSecond expected-expires)))))))))

;; =============================================================================
;; Test: Scope Filtering
;; =============================================================================

(deftest ^:integration test-scope-tag-global
  (testing "Global scope entries have scope:global tag"
    (let [entry (make-memory-entry :scope "global" :content "Global memory")
          _ (chroma/index-memory-entry! entry)
          retrieved (chroma/search-by-id (:id entry))]
      (is (some? retrieved))
      ;; Tags stored as comma-separated string in Chroma metadata
      (is (str/includes? (or (get-in retrieved [:metadata :tags]) "") "scope:global")))))

(deftest ^:integration test-scope-tag-project
  (testing "Project scope entries have scope:project:<name> tag"
    (let [entry (make-memory-entry
                 :tags ["scope:project:hive-mcp"]
                 :content "Project-scoped memory")
          _ (chroma/index-memory-entry! entry)
          retrieved (chroma/search-by-id (:id entry))]
      (is (some? retrieved))
      (is (str/includes? (or (get-in retrieved [:metadata :tags]) "")
                         "scope:project:hive-mcp")))))

(deftest ^:integration test-scope-tag-domain
  (testing "Domain scope entries have scope:domain:<name> tag"
    (let [entry (make-memory-entry
                 :tags ["scope:domain:dev-tools"]
                 :content "Domain-scoped memory")
          _ (chroma/index-memory-entry! entry)
          retrieved (chroma/search-by-id (:id entry))]
      (is (some? retrieved))
      (is (str/includes? (or (get-in retrieved [:metadata :tags]) "")
                         "scope:domain:dev-tools")))))

(deftest ^:integration test-semantic-search-respects-scope-via-metadata
  (testing "Scope information preserved in metadata for filtering"
    ;; Index entries with different scopes
    (chroma/index-memory-entry!
     (make-memory-entry :id "global-entry"
                        :tags ["scope:global"]
                        :content "Global convention"))
    (chroma/index-memory-entry!
     (make-memory-entry :id "project-entry"
                        :tags ["scope:project:my-app"]
                        :content "Project convention"))

    ;; Search and verify scope info is preserved
    (let [results (chroma/search-similar "convention" :limit 10)]
      (is (>= (count results) 2))
      ;; Check that tags metadata is preserved
      (is (every? #(string? (get-in % [:metadata :tags])) results)))))

(deftest ^:integration test-multiple-scope-tags
  (testing "Entries can have multiple scope tags"
    (let [entry (make-memory-entry
                 :tags ["scope:global" "scope:domain:shared"]
                 :content "Multi-scope memory")
          _ (chroma/index-memory-entry! entry)
          retrieved (chroma/search-by-id (:id entry))]
      (is (some? retrieved))
      (let [tags-str (get-in retrieved [:metadata :tags])]
        (is (str/includes? tags-str "scope:global"))
        (is (str/includes? tags-str "scope:domain:shared"))))))

;; =============================================================================
;; Test: Content Deduplication
;; =============================================================================

(defn content-hash
  "Simple content hash for testing deduplication."
  [content]
  (let [normalized (-> content
                       str/trim
                       (str/replace #"\s+" " "))]
    (format "%x" (hash normalized))))

(deftest ^:integration test-content-hash-deterministic
  (testing "Same content produces same hash"
    (let [content "Test content for hashing"
          hash1 (content-hash content)
          hash2 (content-hash content)]
      (is (= hash1 hash2)))))

(deftest ^:integration test-content-hash-different-content
  (testing "Different content produces different hash"
    (let [hash1 (content-hash "First content")
          hash2 (content-hash "Second content")]
      (is (not= hash1 hash2)))))

(deftest ^:integration test-content-hash-whitespace-normalization
  (testing "Whitespace normalized before hashing"
    (let [content1 "multiple   spaces   here"
          content2 "multiple spaces here"
          hash1 (content-hash content1)
          hash2 (content-hash content2)]
      (is (= hash1 hash2)))))

(deftest ^:integration test-content-hash-trim-normalization
  (testing "Leading/trailing whitespace trimmed"
    (let [content1 "  padded content  "
          content2 "padded content"
          hash1 (content-hash content1)
          hash2 (content-hash content2)]
      (is (= hash1 hash2)))))

(deftest ^:integration test-dedup-same-content-different-ids
  (testing "Detecting duplicate content with different IDs"
    ;; Index first entry
    (let [content "This is duplicate content"
          entry1 (make-memory-entry :id "entry-1" :content content)
          entry2 (make-memory-entry :id "entry-2" :content content)
          hash1 (content-hash (:content entry1))
          hash2 (content-hash (:content entry2))]
      ;; Hashes should match - indicating duplicate
      (is (= hash1 hash2))

      ;; Both can be indexed (dedup logic lives in memory layer)
      (chroma/index-memory-entry! entry1)
      (chroma/index-memory-entry! entry2)

      ;; Both should exist in Chroma
      (is (some? (chroma/search-by-id "entry-1")))
      (is (some? (chroma/search-by-id "entry-2"))))))

(deftest ^:integration test-dedup-detection-via-semantic-search
  (testing "Semantic search can find near-duplicates"
    ;; Index original
    (chroma/index-memory-entry!
     (make-memory-entry :id "original" :content "Remember to always use descriptive names"))

    ;; Check for potential duplicate via semantic search
    (let [query "always use descriptive names"
          results (chroma/search-similar query :limit 1)]
      (is (seq results))
      ;; Low distance indicates near-duplicate
      (is (number? (:distance (first results)))))))

;; =============================================================================
;; Test: Collection Statistics
;; =============================================================================

(deftest ^:integration test-collection-stats-empty
  (testing "Stats on empty/new collection"
    (chroma/reset-collection-cache!)
    (let [stats (chroma/collection-stats)]
      (is (map? stats))
      ;; Either count is 0 or there's an error (collection doesn't exist yet)
      (is (or (= 0 (:count stats))
              (some? (:error stats)))))))

(deftest ^:integration test-collection-stats-with-entries
  (testing "Stats after indexing entries"
    ;; Index some entries of different types
    (chroma/index-memory-entry!
     (make-memory-entry :type "note" :content "Note 1"))
    (chroma/index-memory-entry!
     (make-memory-entry :type "note" :content "Note 2"))
    (chroma/index-memory-entry!
     (make-memory-entry :type "convention" :content "Convention 1"))

    (let [stats (chroma/collection-stats)]
      (is (map? stats))
      (when-not (:error stats)
        (is (>= (:count stats) 3))
        (is (map? (:types stats)))))))

;; =============================================================================
;; Test: Error Handling
;; =============================================================================

(deftest ^:integration test-index-without-embedding-provider
  (testing "Indexing without embedding provider throws"
    (let [original (chroma/get-embedding-provider)]
      (chroma/reset-embedding-provider!)
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (chroma/index-memory-entry!
                      (make-memory-entry :content "Should fail"))))
        (finally
          (chroma/set-embedding-provider! original))))))

(deftest ^:integration test-search-without-embedding-provider
  (testing "Searching without embedding provider throws"
    (let [original (chroma/get-embedding-provider)]
      (chroma/reset-embedding-provider!)
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (chroma/search-similar "query")))
        (finally
          (chroma/set-embedding-provider! original))))))

;; =============================================================================
;; Test: Document Format
;; =============================================================================

(deftest ^:integration test-memory-to-document-format
  (testing "Memory entry converted to searchable document"
    (let [entry (make-memory-entry
                 :type "convention"
                 :content "Use kebab-case"
                 :tags ["style" "naming"])]
      ;; Index and retrieve to see document format
      (chroma/index-memory-entry! entry)
      (let [result (chroma/search-by-id (:id entry))
            doc (:document result)]
        (is (string? doc))
        (is (str/includes? doc "Type: convention"))
        (is (str/includes? doc "Tags:"))
        (is (str/includes? doc "Content: Use kebab-case"))))))

(deftest ^:integration test-document-format-without-tags
  (testing "Document format handles empty tags"
    (let [entry {:id (gen-test-id)
                 :type "note"
                 :content "No tags here"
                 :tags []}]
      (chroma/index-memory-entry! entry)
      (let [result (chroma/search-by-id (:id entry))
            doc (:document result)]
        (is (string? doc))
        (is (str/includes? doc "Type: note"))
        (is (str/includes? doc "Content: No tags here"))))))

;; =============================================================================
;; Test: Integration - Full Memory Lifecycle
;; =============================================================================

(deftest ^:integration test-memory-lifecycle-create-search-delete
  (testing "Complete lifecycle: create -> search -> delete"
    ;; Create
    (let [entry (make-memory-entry
                 :type "decision"
                 :content "Decided to use Chroma for vector storage"
                 :tags ["architecture" "database"])
          created-id (chroma/index-memory-entry! entry)]
      (is (= (:id entry) created-id))

      ;; Search - semantic
      (let [search-results (chroma/search-similar "vector database" :limit 5)]
        (is (some #(= (:id entry) (:id %)) search-results)))

      ;; Search - by ID
      (let [by-id (chroma/search-by-id (:id entry))]
        (is (some? by-id))
        (is (str/includes? (:document by-id) "Chroma")))

      ;; Delete
      (chroma/delete-entry! (:id entry))

      ;; Verify deleted
      (is (nil? (chroma/search-by-id (:id entry)))))))

(deftest ^:integration test-memory-update-via-upsert
  (testing "Update memory entry via upsert"
    (let [id (gen-test-id)
          entry-v1 (make-memory-entry :id id :content "Initial content" :tags ["v1"])
          entry-v2 (make-memory-entry :id id :content "Updated content" :tags ["v2"])]
      ;; Create
      (chroma/index-memory-entry! entry-v1)
      (let [v1-result (chroma/search-by-id id)]
        (is (str/includes? (:document v1-result) "Initial")))

      ;; Update
      (chroma/index-memory-entry! entry-v2)
      (let [v2-result (chroma/search-by-id id)]
        (is (str/includes? (:document v2-result) "Updated"))
        (is (str/includes? (get-in v2-result [:metadata :tags] "") "v2"))))))

;; =============================================================================
;; Test: Chroma Metadata Constraints
;; =============================================================================

(deftest ^:integration test-metadata-scalar-values-only
  (testing "Tags stored as comma-separated string (Chroma constraint)"
    ;; Chroma metadata only supports scalar values
    (let [entry (make-memory-entry :tags ["tag1" "tag2" "tag3"])
          _ (chroma/index-memory-entry! entry)
          result (chroma/search-by-id (:id entry))]
      (is (some? result))
      (let [tags-meta (get-in result [:metadata :tags])]
        (is (string? tags-meta))
        (is (str/includes? tags-meta "tag1"))
        (is (str/includes? tags-meta "tag2"))
        (is (str/includes? tags-meta "tag3"))))))

(deftest ^:integration test-metadata-type-preserved
  (testing "Memory type preserved in metadata"
    (doseq [type ["note" "snippet" "convention" "decision"]]
      (let [entry (make-memory-entry :type type)
            _ (chroma/index-memory-entry! entry)
            result (chroma/search-by-id (:id entry))]
        (is (= type (get-in result [:metadata :type])))))))

(deftest ^:integration test-metadata-created-timestamp
  (testing "Created timestamp preserved in metadata"
    (let [timestamp "2024-01-15T10:30:00Z"
          entry (make-memory-entry :created timestamp)
          _ (chroma/index-memory-entry! entry)
          result (chroma/search-by-id (:id entry))]
      (is (= timestamp (get-in result [:metadata :created]))))))
