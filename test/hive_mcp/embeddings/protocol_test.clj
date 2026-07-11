(ns hive-mcp.embeddings.protocol-test
  "Structural tests for the relocated EmbeddingProvider protocol.

   Verifies:
   - P1: protocol has exactly the 3 documented methods
   - P2: chroma.embeddings backward-compat alias points at the same var
   - P3: all 3 embedder records satisfy the protocol"
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.embeddings.protocol :as proto]
            [hive-mcp.chroma.embeddings :as chroma-emb]
            ;; The three embedder impls must be required HERE, at ns level.
            ;; A runtime (require ...) inside a deftest body does not help:
            ;; fully-qualified symbols in the body are resolved at COMPILE
            ;; time, so the ns must already be loaded when this file compiles.
            [hive-mcp.embeddings.ollama :as ollama]
            [hive-mcp.embeddings.openai :as openai]
            [hive-mcp.embeddings.openrouter :as openrouter]))

(deftest p1-protocol-shape
  (testing "EmbeddingProvider declares embed-text, embed-batch, embedding-dimension"
    (let [m (:sigs proto/EmbeddingProvider)]
      (is (contains? m :embed-text))
      (is (contains? m :embed-batch))
      (is (contains? m :embedding-dimension))
      (is (= 3 (count m))
          "No additional methods leaked into the protocol"))))

(deftest p2-chroma-alias-is-same-protocol
  (testing "chroma.embeddings/EmbeddingProvider IS the relocated protocol"
    (is (identical? proto/EmbeddingProvider chroma-emb/EmbeddingProvider)))

  (testing "method fns re-exported via chroma.embeddings resolve to same vars"
    (is (identical? proto/embed-text chroma-emb/embed-text))
    (is (identical? proto/embed-batch chroma-emb/embed-batch))
    (is (identical? proto/embedding-dimension chroma-emb/embedding-dimension))))

(deftest p3-embedder-records-implement-new-protocol
  (testing "OllamaEmbedder, OpenAIEmbedder, OpenRouterEmbedder satisfy the protocol"
    ;; Construct minimal instances via the positional record constructors —
    ;; no ->provider, so no network I/O (->provider pings /api/tags).
    ;; NOTE: OllamaEmbedder gained a 4th field, :executor-fn, in e99c016
    ;; ("optional :executor-fn delegation for ollama provider"). nil selects
    ;; the direct-HTTP path, which we never exercise here.
    (let [ollama-emb     (ollama/->OllamaEmbedder
                          "http://unused" "nomic-embed-text" 768 nil)
          openai-emb     (openai/->OpenAIEmbedder
                          "https://unused/v1" "test-key" "text-embedding-3-small" 1536)
          openrouter-emb (openrouter/->OpenRouterEmbedder
                          "https://unused/api/v1" "test-key" "qwen/qwen3-embedding-8b" 4096)]
      (is (satisfies? proto/EmbeddingProvider ollama-emb))
      (is (satisfies? proto/EmbeddingProvider openai-emb))
      (is (satisfies? proto/EmbeddingProvider openrouter-emb))
      ;; AND they satisfy the chroma alias (backward compat)
      (is (satisfies? chroma-emb/EmbeddingProvider ollama-emb))
      (is (satisfies? chroma-emb/EmbeddingProvider openai-emb))
      (is (satisfies? chroma-emb/EmbeddingProvider openrouter-emb))
      ;; embedding-dimension is pure — safe to call
      (is (= 768 (proto/embedding-dimension ollama-emb)))
      (is (= 1536 (proto/embedding-dimension openai-emb)))
      (is (= 4096 (proto/embedding-dimension openrouter-emb))))))
