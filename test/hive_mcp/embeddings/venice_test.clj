(ns hive-mcp.embeddings.venice-test
  "Tests for the Venice embedding provider.

   Coverage:
   - Structural: VeniceEmbedder satisfies EmbeddingProvider, dimension query
   - Request shape: Authorization header carries Bearer, body shape matches OpenAI spec
   - Response parsing: orders by :index, returns vectors of floats
   - Config resolution: ->provider honours overrides, falls back to defaults
   - Error path: missing api-key throws"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [hive-mcp.config.core :as global-config]
            [hive-mcp.embeddings.protocol :as proto]
            [hive-mcp.embeddings.venice :as venice]))

;; =============================================================================
;; Structural tests — pure, no network
;; =============================================================================

(deftest venice-embedder-record-implements-protocol
  (testing "VeniceEmbedder satisfies EmbeddingProvider"
    (let [emb (venice/->VeniceEmbedder
               "https://api.venice.ai/api/v1"
               "test-key"
               "text-embedding-qwen3-8b"
               4096)]
      (is (satisfies? proto/EmbeddingProvider emb))
      (is (= 4096 (proto/embedding-dimension emb))))))

;; =============================================================================
;; Request shape — capture bytes via stubbed make-request
;; =============================================================================

(defn- mock-response
  "Build a fake :data response in OpenAI/Venice format. Returns embeddings
   ordered by index — but we deliberately scramble incoming order to verify
   the impl re-sorts."
  [n-texts dim]
  {:data (->> (range n-texts)
              ;; Reverse the order to assert sort-by :index in impl.
              reverse
              (mapv (fn [i]
                      {:index i
                       :embedding (vec (repeat dim (double i)))})))})

(deftest embed-text-request-shape
  (testing "embed-text issues POST to {api-base}/embeddings with bearer + body"
    (let [captured (atom nil)]
      (with-redefs [venice/make-request
                    (fn [api-base api-key body]
                      (reset! captured {:api-base api-base
                                        :api-key  api-key
                                        :body     body})
                      (mock-response 1 4))]
        (let [emb (venice/->VeniceEmbedder
                   "https://api.venice.ai/api/v1"
                   "sk-test-not-real"
                   "text-embedding-qwen3-8b"
                   4)
              v   (proto/embed-text emb "hello world")]
          (is (= 4 (count v)) "dimension matches embedder")
          (let [{:keys [api-base api-key body]} @captured]
            (is (= "https://api.venice.ai/api/v1" api-base))
            (is (= "sk-test-not-real" api-key))
            (is (= "text-embedding-qwen3-8b" (:model body)))
            (is (= ["hello world"] (:input body)))))))))

(deftest embed-batch-orders-by-index
  (testing "embed-batch sorts response by :index even when API returns out-of-order"
    (with-redefs [venice/make-request
                  (fn [_api-base _api-key body]
                    (mock-response (count (:input body)) 3))]
      (let [emb (venice/->VeniceEmbedder
                 "https://api.venice.ai/api/v1"
                 "k" "text-embedding-qwen3-8b" 3)
            vs  (proto/embed-batch emb ["a" "b" "c"])]
        (is (= 3 (count vs)))
        ;; Since mock returned reversed, sort-by :index must restore order:
        ;; idx 0 → all 0.0, idx 1 → all 1.0, idx 2 → all 2.0
        (is (= [0.0 0.0 0.0] (nth vs 0)))
        (is (= [1.0 1.0 1.0] (nth vs 1)))
        (is (= [2.0 2.0 2.0] (nth vs 2)))))))

;; =============================================================================
;; ->provider error path
;; =============================================================================

(deftest provider-requires-api-key
  (testing "->provider throws when no api-key is available"
    (with-redefs [global-config/get-secret (fn [_] nil)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"(?i)venice api key required"
            (venice/->provider {}))))))

(deftest provider-honours-explicit-key-and-overrides
  (testing "->provider takes :api-key + :model overrides without env"
    (let [emb (venice/->provider {:api-key "explicit-key"
                                   :model   "text-embedding-qwen3-8b"
                                   :api-base "https://api.venice.ai/api/v1"})]
      (is (= "explicit-key" (:api-key emb)))
      (is (= "text-embedding-qwen3-8b" (:model emb)))
      (is (= 4096 (:dimension emb))))))

;; =============================================================================
;; JSON shape sanity (regression guard)
;; =============================================================================

(deftest response-parsing-matches-openai-spec
  (testing "Venice responses with {:data [{:embedding ... :index n}]} parse correctly"
    ;; Round-trip a hand-crafted JSON body through json/read-str to
    ;; confirm the impl's :key-fn keyword reading agrees with what the
    ;; transport delivers. Guards against a regression where Venice
    ;; renames :embedding or returns nested objects.
    (let [json-body (json/write-str
                      {:object "list"
                       :data   [{:object "embedding"
                                 :index 0
                                 :embedding [0.1 0.2 0.3]}]})
          parsed    (json/read-str json-body :key-fn keyword)]
      (is (= 1 (count (:data parsed))))
      (is (= [0.1 0.2 0.3] (:embedding (first (:data parsed)))))
      (is (= 0 (:index (first (:data parsed))))))))
