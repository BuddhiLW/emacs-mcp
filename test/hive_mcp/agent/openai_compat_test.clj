(ns hive-mcp.agent.openai-compat-test
  "Tests for the generalized OpenAI-compatible provider system.

   Tests cover:
   - Provider registry structure
   - Provider discovery (available-providers, best-available-provider)
   - Factory functions (openai-compat-backend, auto-backend, openrouter-backend)
   - Backward compatibility"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [hive-mcp.agent.openrouter :as openrouter]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.config.core :as config]))

;; =============================================================================
;; Provider Registry
;; =============================================================================

(deftest provider-registry-structure-test
  (testing "All providers have required keys"
    (doseq [[provider-key entry] openrouter/provider-registry]
      (is (contains? entry :api-url)
          (str provider-key " must have :api-url"))
      (is (contains? entry :default-model)
          (str provider-key " must have :default-model"))
      (is (string? (:api-url entry))
          (str provider-key " :api-url must be a string"))
      (is (string? (:default-model entry))
          (str provider-key " :default-model must be a string"))))

  (testing "Expected providers are registered"
    (let [providers (set (keys openrouter/provider-registry))]
      (is (contains? providers :openrouter))
      (is (contains? providers :venice))
      (is (contains? providers :groq))
      (is (contains? providers :together))
      (is (contains? providers :fireworks))
      (is (contains? providers :openai))
      (is (contains? providers :ollama-compat)))))

(deftest provider-registry-urls-test
  (testing "Provider URLs end with /chat/completions"
    (doseq [[k entry] openrouter/provider-registry]
      (is (clojure.string/ends-with? (:api-url entry) "/chat/completions")
          (str k " URL should end with /chat/completions")))))

(deftest provider-registry-secret-keys-test
  (testing "ollama-compat has nil secret-key (no auth needed)"
    (is (nil? (get-in openrouter/provider-registry [:ollama-compat :secret-key]))))
  (testing "All other providers have non-nil secret-key"
    (doseq [[k entry] (dissoc openrouter/provider-registry :ollama-compat)]
      (is (keyword? (:secret-key entry))
          (str k " should have a keyword :secret-key")))))

;; =============================================================================
;; Provider Discovery
;; =============================================================================

(deftest available-providers-returns-seq-test
  (testing "available-providers returns a seq (possibly empty)"
    (let [result (openrouter/available-providers)]
      (is (sequential? (vec result))
          "Should return something seqable"))))

(deftest available-providers-includes-ollama-compat-test
  (testing "ollama-compat is always available (no key needed)"
    (with-redefs [hive-mcp.config.core/get-secret (fn [_] nil)]
      (let [result (set (openrouter/available-providers))]
        (is (contains? result :ollama-compat)
            "ollama-compat needs no API key, should always be available")))))

(deftest available-providers-includes-keyed-providers-test
  (testing "Providers with keys set are included"
    (with-redefs [hive-mcp.config.core/get-secret
                  (fn [k] (case k
                            :venice-api-key "sk-test-venice"
                            :groq-api-key "sk-test-groq"
                            nil))]
      (let [result (set (openrouter/available-providers))]
        (is (contains? result :venice))
        (is (contains? result :groq))
        (is (not (contains? result :openrouter)))
        (is (contains? result :ollama-compat))))))

(deftest best-available-provider-respects-priority-test
  (testing "Returns highest-priority provider with a key"
    (with-redefs [hive-mcp.config.core/get-secret
                  (fn [k] (case k
                            :venice-api-key "sk-venice"
                            :groq-api-key "sk-groq"
                            nil))]
      (is (= :venice (openrouter/best-available-provider))
          "Venice is higher priority than Groq")))

  (testing "Returns :openrouter when it has a key"
    (with-redefs [hive-mcp.config.core/get-secret
                  (fn [k] (case k
                            :openrouter-api-key "sk-or"
                            :venice-api-key "sk-venice"
                            nil))]
      (is (= :openrouter (openrouter/best-available-provider))
          "OpenRouter is highest priority"))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(deftest openai-compat-backend-creates-record-test
  (testing "Creates OpenAICompatBackend with explicit values"
    (let [b (openrouter/openai-compat-backend
             {:provider :venice
              :api-key "sk-test"
              :model "test-model"})]
      (is (satisfies? proto/LLMBackend b))
      (is (= "test-model" (proto/model-name b)))
      (is (= "venice" (:provider-name b)))
      (is (= "https://api.venice.ai/api/v1/chat/completions" (:api-url b)))))

  (testing "Creates with custom URL"
    (let [b (openrouter/openai-compat-backend
             {:api-url "http://my-server:8080/v1/chat/completions"
              :api-key "sk-custom"
              :model "my-model"})]
      (is (= "http://my-server:8080/v1/chat/completions" (:api-url b)))
      (is (= "custom" (:provider-name b))))))

(deftest openai-compat-backend-uses-defaults-test
  (testing "Falls back to provider default model"
    (let [b (openrouter/openai-compat-backend
             {:provider :groq :api-key "sk-test"})]
      (is (= "llama-3.3-70b-versatile" (proto/model-name b))))))

(deftest openai-compat-backend-ollama-no-key-test
  (testing "ollama-compat works without API key"
    (let [b (openrouter/openai-compat-backend
             {:provider :ollama-compat :model "devstral"})]
      (is (satisfies? proto/LLMBackend b))
      (is (= "" (:api-key b))))))

(deftest openai-compat-backend-throws-without-key-test
  (testing "Throws when provider requires key but none available"
    (with-redefs [hive-mcp.config.core/get-secret (fn [_] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"API key required"
            (openrouter/openai-compat-backend {:provider :venice}))))))

(deftest openai-compat-backend-throws-without-url-test
  (testing "Throws when no URL and no provider"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"API URL required"
          (openrouter/openai-compat-backend {:api-key "sk-test" :model "m"})))))

;; =============================================================================
;; auto-backend
;; =============================================================================

(deftest auto-backend-selects-provider-test
  (testing "auto-backend picks first available provider"
    (with-redefs [hive-mcp.config.core/get-secret
                  (fn [k] (when (= k :venice-api-key) "sk-venice"))]
      (let [b (openrouter/auto-backend {:model "my-model"})]
        (is (= "venice" (:provider-name b)))
        (is (= "my-model" (proto/model-name b)))))))

(deftest auto-backend-throws-when-no-providers-test
  (testing "auto-backend throws when no provider has keys (except ollama-compat)"
    ;; ollama-compat is always available, so this should NOT throw
    (with-redefs [hive-mcp.config.core/get-secret (fn [_] nil)]
      (let [b (openrouter/auto-backend {:model "devstral"})]
        (is (= "ollama-compat" (:provider-name b)))))))

;; =============================================================================
;; Backward Compatibility
;; =============================================================================

(deftest openrouter-backend-backward-compat-test
  (testing "openrouter-backend still works as before"
    (let [b (openrouter/openrouter-backend {:api-key "sk-test" :model "gpt-4o"})]
      (is (satisfies? proto/LLMBackend b))
      (is (= "gpt-4o" (proto/model-name b)))
      (is (= "openrouter" (:provider-name b)))
      (is (= "https://openrouter.ai/api/v1/chat/completions" (:api-url b))))))

(comment
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'hive-mcp.agent.openai-compat-test))
