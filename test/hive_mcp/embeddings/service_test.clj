(ns hive-mcp.embeddings.service-test
  "Unit tests for the EmbeddingService - per-collection embedding routing.

   Tests cover:
   - Service initialization
   - Per-collection configuration
   - Provider resolution with fallback chain
   - Dimension queries
   - Integration with chroma/get-provider-for

   Uses MockEmbedder for deterministic testing without external dependencies."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.embeddings.service :as service]
            [hive-mcp.embeddings.config :as config]
            [hive-mcp.embeddings.registry :as registry]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.chroma.embeddings :as chroma-emb]
            [hive-mcp.test-fixtures :as fixtures]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn with-clean-service
  "Fixture that resets service state before each test and stubs the :ollama
   provider factory with a MockEmbedder sized from the config's declared
   dimension.

   Contract: no test in this namespace performs network I/O. EmbeddingConfig
   value objects stay real, so model->dimension coverage is unaffected — only
   the provider behind a config is a double. Teardown restores the built-in
   factories via registry/init! and drops the provider cache both ways, so a
   real provider cached by another namespace can neither leak in nor out."
  [f]
  (let [original-global @@#'chroma-emb/embedding-provider]
    (try
      (service/reset-service!)
      (service/init!)
      (registry/clear-cache!)
      (registry/register-factory!
       :ollama
       (fn [config] (fixtures/->MockEmbedder (:dimension config))))
      ;; Set global fallback for backward compatibility tests
      (chroma/set-embedding-provider! (fixtures/->MockEmbedder 384))
      (f)
      (finally
        (service/reset-service!)
        (registry/clear-cache!)
        (registry/init!)
        (reset! @#'chroma-emb/embedding-provider original-global)))))

(use-fixtures :each with-clean-service)

;; =============================================================================
;; Test: Initialization
;; =============================================================================

(deftest test-service-initialization
  (testing "Service initializes successfully"
    (service/reset-service!)
    (is (not (service/initialized?-fn)))
    (service/init!)
    (is (service/initialized?-fn)))

  (testing "Multiple init calls are safe (idempotent)"
    (service/init!)
    (service/init!)
    (is (service/initialized?-fn))))

(deftest test-service-status
  (testing "Status reports initialization state"
    (let [status (service/status)]
      (is (:initialized? status))
      (is (map? (:configured-collections status)))
      (is (number? (:collection-count status))))))

;; =============================================================================
;; Test: EmbeddingConfig Value Object
;; =============================================================================

(deftest test-config-ollama-valid
  (testing "Ollama config creation"
    (let [cfg (config/ollama-config)]
      (is (config/valid-config? cfg))
      (is (= :ollama (:provider-type cfg)))
      (is (= "nomic-embed-text" (:model cfg)))
      (is (= 768 (:dimension cfg))))))

(deftest test-config-ollama-custom-model
  (testing "Ollama config with custom model"
    (let [cfg (config/ollama-config {:model "mxbai-embed-large"})]
      (is (config/valid-config? cfg))
      (is (= "mxbai-embed-large" (:model cfg)))
      (is (= 1024 (:dimension cfg))))))

(deftest test-config-ollama-invalid-model
  (testing "Ollama config with invalid model throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/ollama-config {:model "nonexistent-model"})))))

(deftest test-config-dimension-query
  (testing "Get dimension for provider/model"
    (is (= 768 (config/get-dimension :ollama "nomic-embed-text")))
    (is (= 1536 (config/get-dimension :openai "text-embedding-3-small")))
    (is (= 4096 (config/get-dimension :openrouter "qwen/qwen3-embedding-8b")))
    (is (nil? (config/get-dimension :unknown "model")))))

(deftest test-config-same-dimension
  (testing "Check if two configs have same dimension"
    (let [cfg1 (config/ollama-config)
          cfg2 (config/ollama-config)]
      (is (config/same-dimension? cfg1 cfg2)))

    (let [cfg1 (config/ollama-config {:model "nomic-embed-text"})       ; 768
          cfg2 (config/ollama-config {:model "mxbai-embed-large"})]     ; 1024
      (is (not (config/same-dimension? cfg1 cfg2))))))

(deftest test-config-describe
  (testing "Human-readable config description"
    (let [cfg (config/ollama-config)]
      (is (= "ollama/nomic-embed-text (768 dims)" (config/describe cfg))))))

;; =============================================================================
;; Test: Per-Collection Configuration
;; =============================================================================

(deftest test-configure-collection
  (testing "Configure collection with valid config"
    (let [cfg (config/ollama-config)]
      (service/configure-collection! "test-collection" cfg)
      (is (= cfg (service/get-collection-config "test-collection"))))))

(deftest test-configure-collection-invalid-config
  (testing "Configure with invalid config throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (service/configure-collection! "test" {:invalid "config"})))))

(deftest test-unconfigure-collection
  (testing "Remove collection configuration"
    (let [cfg (config/ollama-config)]
      (service/configure-collection! "test-collection" cfg)
      (is (some? (service/get-collection-config "test-collection")))
      (service/unconfigure-collection! "test-collection")
      (is (nil? (service/get-collection-config "test-collection"))))))

(deftest test-list-configured-collections
  (testing "List all configured collections"
    (service/configure-collection! "collection-a" (config/ollama-config))
    (service/configure-collection! "collection-b" (config/ollama-config {:model "mxbai-embed-large"}))
    (let [collections (service/list-configured-collections)]
      (is (= 2 (count collections)))
      (is (contains? collections "collection-a"))
      (is (contains? collections "collection-b")))))

;; =============================================================================
;; Test: Provider Resolution (Fallback Chain)
;; =============================================================================

(deftest test-provider-resolution-with-collection-config
  (testing "Collection with config uses its specific provider"
    (let [cfg (config/ollama-config {:model "mxbai-embed-large"})]
      (service/configure-collection! "my-collection" cfg)
      (let [provider (service/get-provider-for "my-collection")]
        (is (some? provider))
        (is (= 1024 (chroma/embedding-dimension provider)))))))

(deftest test-provider-resolution-fallback-to-global
  (testing "Unconfigured collection falls back to global provider"
    ;; No collection config, but global provider is set
    (let [provider (service/get-provider-for "unconfigured-collection")]
      (is (some? provider))
      ;; Global provider is MockEmbedder with 384 dims
      (is (= 384 (chroma/embedding-dimension provider))))))

(deftest test-provider-resolution-no-fallback-throws
  (testing "No config and no global provider throws"
    (chroma/set-embedding-provider! nil)
    (is (thrown? clojure.lang.ExceptionInfo
                 (service/get-provider-for "no-provider-collection")))))

;; =============================================================================
;; Test: Embedding API
;; =============================================================================

(deftest test-get-dimension-for-collection
  (testing "Get dimension for configured collection"
    (service/configure-collection! "dim-test" (config/ollama-config))
    (is (= 768 (service/get-dimension-for "dim-test"))))

  (testing "Get dimension for unconfigured collection (fallback)"
    ;; Fallback to global MockEmbedder (384)
    (is (= 384 (service/get-dimension-for "unconfigured")))))

(deftest test-embed-for-collection
  (testing "Embed text using collection's provider"
    (service/configure-collection! "embed-test" (config/ollama-config))
    (let [embedding (service/embed-for-collection "embed-test" "test text")]
      (is (vector? embedding))
      (is (= 768 (count embedding))))))

(deftest test-embed-batch-for-collection
  (testing "Batch embed using collection's provider"
    (service/configure-collection! "batch-test" (config/ollama-config))
    (let [embeddings (service/embed-batch-for-collection "batch-test" ["one" "two" "three"])]
      (is (= 3 (count embeddings)))
      (is (every? #(= 768 (count %)) embeddings)))))

;; =============================================================================
;; Test: Provider Availability
;; =============================================================================

(deftest test-provider-available-for-configured
  (testing "Provider available for configured collection"
    (service/configure-collection! "avail-test" (config/ollama-config))
    (is (service/provider-available-for? "avail-test"))))

(deftest test-provider-available-for-unconfigured-with-fallback
  (testing "Provider available with global fallback"
    (is (service/provider-available-for? "some-collection"))))

(deftest test-collection-embedding-status
  (testing "Get detailed status for collection"
    (service/configure-collection! "status-test" (config/ollama-config))
    (let [status (service/collection-embedding-status "status-test")]
      (is (= "status-test" (:collection status)))
      (is (:has-config? status))
      (is (= 768 (:dimension status)))
      (is (:provider-available? status)))))

;; =============================================================================
;; Test: Registry
;; =============================================================================

(deftest test-registry-initialization
  (testing "Registry initialized with built-in factories"
    (let [factories (registry/list-factories)]
      (is (contains? (set factories) :ollama))
      (is (contains? (set factories) :openai))
      (is (contains? (set factories) :openrouter)))))

(deftest test-registry-cache
  (testing "Registry caches provider instances"
    (let [cfg (config/ollama-config)]
      (let [p1 (registry/get-provider cfg)
            p2 (registry/get-provider cfg)]
        ;; Same instance from cache
        (is (identical? p1 p2)))))

  (testing "Registry cache stats"
    (let [stats (registry/cache-stats)]
      (is (number? (:cached-count stats)))
      (is (seq (:factories stats))))))

(deftest test-registry-cache-clear
  (testing "Clear registry cache"
    (let [cfg (config/ollama-config)
          _ (registry/get-provider cfg)]
      (is (pos? (:cached-count (registry/cache-stats))))
      (registry/clear-cache!)
      (is (zero? (:cached-count (registry/cache-stats)))))))

;; =============================================================================
;; Test: chroma/get-provider-for Integration
;; =============================================================================

(deftest test-chroma-get-provider-for-integration
  (testing "chroma/get-provider-for delegates to service"
    (service/configure-collection! "chroma-test" (config/ollama-config {:model "mxbai-embed-large"}))
    (let [provider (chroma/get-provider-for "chroma-test")]
      (is (some? provider))
      (is (= 1024 (chroma/embedding-dimension provider))))))

(deftest test-chroma-embed-text-for
  (testing "chroma/embed-text-for uses collection's provider"
    (service/configure-collection! "embed-via-chroma" (config/ollama-config))
    (let [embedding (chroma/embed-text-for "embed-via-chroma" "test")]
      (is (= 768 (count embedding))))))

(deftest test-chroma-get-dimension-for
  (testing "chroma/get-dimension-for returns collection's dimension"
    (service/configure-collection! "dim-via-chroma" (config/ollama-config {:model "mxbai-embed-large"}))
    (is (= 1024 (chroma/get-dimension-for "dim-via-chroma")))))

;; =============================================================================
;; Test: Multi-Collection Scenario
;; =============================================================================

(deftest test-multi-collection-different-dimensions
  (testing "Different collections can have different dimensions (config-level)"
    ;; Configure with different dimension configs
    (service/configure-collection! "hive-mcp-memory" (config/ollama-config))
    (service/configure-collection! "hive-mcp-presets" (config/ollama-config {:model "mxbai-embed-large"}))

    ;; Verify different dimensions from config (not from actual embedding)
    (is (= 768 (:dimension (service/get-collection-config "hive-mcp-memory"))))
    (is (= 1024 (:dimension (service/get-collection-config "hive-mcp-presets")))))

  (testing "Unconfigured collections use fallback with consistent dimension"
    ;; Fallback (MockEmbedder 384) should work for unconfigured collections
    (let [emb1 (service/embed-for-collection "unconfigured-a" "test")
          emb2 (service/embed-for-collection "unconfigured-b" "test")]
      (is (= 384 (count emb1)))
      (is (= 384 (count emb2))))))

;; =============================================================================
;; Test: Service Reset
;; =============================================================================

(deftest test-service-reset
  (testing "Reset clears all state"
    (service/configure-collection! "will-be-cleared" (config/ollama-config))
    (is (some? (service/get-collection-config "will-be-cleared")))

    (service/reset-service!)

    (is (not (service/initialized?-fn)))
    (is (nil? (service/get-collection-config "will-be-cleared")))
    (is (= 0 (:collection-count (service/status))))))
