(ns hive-mcp.embeddings.env-config-test
  "Property + example tests for the hive-di-backed embedder configs.

   Properties:
   - P1: resolve-*Config is TOTAL — never throws for any override map
   - P2: defaults suffice — (resolve-*Config {}) returns {:ok ...} with all fields
   - P3: explicit override wins over defaults

   Examples:
   - E1: each provider's default resolves to documented values
   - E2: override for one field leaves other fields at defaults"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.generators.core :as gen-core]
            [hive-mcp.embeddings.env-config :as ec]))

;; =============================================================================
;; Generators — arbitrary override maps
;; =============================================================================

(def gen-any-override
  "Random overrides: any keys (mostly irrelevant), some of which may be
   our known config keys. Used to exercise totality."
  (gen/map gen/keyword (gen/one-of [gen/string-alphanumeric
                                    gen/small-integer
                                    (gen/return nil)])
           {:max-elements 5}))

(def gen-ollama-override
  (gen/hash-map
   :host  (gen/one-of [gen-core/gen-non-blank-string (gen/return nil)])
   :model (gen/one-of [gen-core/gen-non-blank-string (gen/return nil)])))

(def gen-openai-override
  (gen/hash-map
   :api-base (gen/one-of [gen-core/gen-non-blank-string (gen/return nil)])
   :model    (gen/one-of [gen-core/gen-non-blank-string (gen/return nil)])))

(def gen-openrouter-override
  (gen/hash-map
   :api-base (gen/one-of [gen-core/gen-non-blank-string (gen/return nil)])
   :model    (gen/one-of [gen-core/gen-non-blank-string (gen/return nil)])))

;; =============================================================================
;; Properties
;; =============================================================================

(defspec p1-ollama-resolver-is-total 50
  (prop/for-all [overrides gen-any-override]
    (try (let [r (ec/resolve-OllamaConfig overrides)]
           (or (:ok r) (:error r)))
         true
         (catch Throwable _ false))))

(defspec p1-openai-resolver-is-total 50
  (prop/for-all [overrides gen-any-override]
    (try (let [r (ec/resolve-OpenAIConfig overrides)]
           (or (:ok r) (:error r)))
         true
         (catch Throwable _ false))))

(defspec p1-openrouter-resolver-is-total 50
  (prop/for-all [overrides gen-any-override]
    (try (let [r (ec/resolve-OpenRouterConfig overrides)]
           (or (:ok r) (:error r)))
         true
         (catch Throwable _ false))))

(defspec p3-ollama-explicit-override-wins 50
  (prop/for-all [host gen-core/gen-non-blank-string
                 model gen-core/gen-non-blank-string]
    (let [r (:ok (ec/resolve-OllamaConfig {:host host :model model}))]
      (and (= host (:host r))
           (= model (:model r))))))

(defspec p3-openai-explicit-override-wins 50
  (prop/for-all [api-base gen-core/gen-non-blank-string
                 model gen-core/gen-non-blank-string]
    (let [r (:ok (ec/resolve-OpenAIConfig {:api-base api-base :model model}))]
      (and (= api-base (:api-base r))
           (= model (:model r))))))

(defspec p3-openrouter-explicit-override-wins 50
  (prop/for-all [api-base gen-core/gen-non-blank-string
                 model gen-core/gen-non-blank-string]
    (let [r (:ok (ec/resolve-OpenRouterConfig {:api-base api-base :model model}))]
      (and (= api-base (:api-base r))
           (= model (:model r))))))

;; =============================================================================
;; Example / unit tests
;; =============================================================================

(deftest e1-defaults-resolve-cleanly
  (testing "OllamaConfig defaults"
    (let [r (:ok (ec/resolve-OllamaConfig))]
      (is (= "http://localhost:11434" (:host r)))
      (is (= "nomic-embed-text" (:model r)))))

  (testing "OpenAIConfig defaults"
    (let [r (:ok (ec/resolve-OpenAIConfig))]
      (is (= "https://api.openai.com/v1" (:api-base r)))
      (is (= "text-embedding-3-small" (:model r)))))

  (testing "OpenRouterConfig defaults"
    (let [r (:ok (ec/resolve-OpenRouterConfig))]
      (is (= "https://openrouter.ai/api/v1" (:api-base r)))
      (is (= "qwen/qwen3-embedding-8b" (:model r))))))

(deftest e2-partial-override-preserves-other-defaults
  (testing "Ollama — override :host, :model stays default"
    (let [r (:ok (ec/resolve-OllamaConfig {:host "http://custom:1234"}))]
      (is (= "http://custom:1234" (:host r)))
      (is (= "nomic-embed-text" (:model r)))))

  (testing "OpenAI — override :model, :api-base stays default"
    (let [r (:ok (ec/resolve-OpenAIConfig {:model "text-embedding-3-large"}))]
      (is (= "https://api.openai.com/v1" (:api-base r)))
      (is (= "text-embedding-3-large" (:model r))))))
