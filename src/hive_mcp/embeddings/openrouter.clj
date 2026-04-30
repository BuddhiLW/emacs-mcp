(ns hive-mcp.embeddings.openrouter
  "OpenRouter embedding provider for semantic memory search.

   Uses OpenRouter's embedding models - includes free tier options!

   Recommended: qwen/qwen3-embedding-8b (33k context, free tier)

   Usage:
     (require '[hive-mcp.embeddings.openrouter :as openrouter])
     (require '[hive-mcp.chroma.core :as chroma])

     ;; Create provider with API key from env
     (chroma/set-embedding-provider! (openrouter/->provider))

     ;; Or with explicit key and model
     (chroma/set-embedding-provider!
       (openrouter/->provider {:api-key \"sk-or-...\"
                               :model \"qwen/qwen3-embedding-8b\"}))"
  (:require [hive-mcp.chroma.core :as chroma]
            [hive-mcp.config.core :as global-config]
            [hive-mcp.embeddings.env-config :as env-cfg]
            [hive-mcp.embeddings.http-client :as http]
            [hive-mcp.embeddings.protocol :as emb-proto]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


(def ^:private models
  "Supported OpenRouter embedding models with their dimensions.
   See https://openrouter.ai/models for full list."
  {"qwen/qwen3-embedding-8b" 4096      ; 33k context, free tier!
   "openai/text-embedding-3-small" 1536
   "openai/text-embedding-3-large" 3072
   "cohere/embed-english-v3.0" 1024
   "cohere/embed-multilingual-v3.0" 1024})

(defn- resolve-config!
  "Resolve OpenRouter api-base + model via hive-di (env → overrides → defaults).
   Throws ex-info on :config/invalid."
  [overrides]
  (let [result (env-cfg/resolve-OpenRouterConfig overrides)]
    (or (:ok result)
        (throw (ex-info "Invalid OpenRouter config"
                        {:type :invalid-config :result result})))))

(defn- embeddings-url
  "Return the full /embeddings URL for a given api-base."
  [api-base]
  (str api-base "/embeddings"))


(defonce ^:private http-client
  ;; Self-healing HttpClient cache. See hive-mcp.embeddings.http-client.
  (http/mk-client
   (fn []
     (-> (HttpClient/newBuilder)
         (.connectTimeout (Duration/ofSeconds 30))
         (.build)))))

(defn- make-request
  "Make HTTP POST request to OpenRouter embeddings API."
  [api-base api-key body]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (embeddings-url api-base)))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.header "HTTP-Referer" "https://github.com/BuddhiLW/hive-mcp")
                    (.header "X-Title" "hive-mcp")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.timeout (Duration/ofSeconds 120)) ; Embeddings can be slow
                    (.build))
        response (http/send-with-retry http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        body-str (.body response)]
    (if (= status 200)
      (json/read-str body-str :key-fn keyword)
      (throw (ex-info "OpenRouter API error"
                      {:status status
                       :body body-str})))))


(defn- get-embeddings
  "Get embeddings for one or more texts from OpenRouter API."
  [api-base api-key model texts]
  (log/debug "Getting embeddings for" (count texts) "texts using" model)
  (let [response (make-request api-base api-key {:model model
                                                 :input texts})
        data (:data response)]
    ;; Sort by index to ensure order matches input
    (->> data
         (sort-by :index)
         (mapv :embedding))))


(defrecord OpenRouterEmbedder [api-base api-key model dimension]
  emb-proto/EmbeddingProvider
  (embed-text [_ text]
    (first (get-embeddings api-base api-key model [text])))
  (embed-batch [_ texts]
    ;; Batch in groups of 50 texts to avoid timeouts
    (let [batches (partition-all 50 texts)]
      (vec (mapcat #(get-embeddings api-base api-key model (vec %)) batches))))
  (embedding-dimension [_] dimension))


(defn ->provider
  "Create an OpenRouter embedding provider.

   Options:
     :api-key  - OpenRouter API key (default: global-config :openrouter-api-key)
     :api-base - API base URL (default: config [:embeddings :openrouter :api-base]
                 or https://openrouter.ai/api/v1)
     :model    - Embedding model (default: config [:embeddings :openrouter :model]
                 or qwen/qwen3-embedding-8b)

   Recommended models:
     - qwen/qwen3-embedding-8b (4096 dims, 33k context, FREE!)
     - openai/text-embedding-3-small (1536 dims, paid)
     - cohere/embed-english-v3.0 (1024 dims, paid)"
  ([] (->provider {}))
  ([{:keys [api-key] :as overrides}]
   (let [{:keys [api-base model]} (resolve-config! (select-keys overrides [:api-base :model]))
         api-key (or api-key (global-config/get-secret :openrouter-api-key))
         dimension (get models model 4096)] ; Default dimension if unknown model
     (when-not api-key
       (throw (ex-info "OpenRouter API key required. Set OPENROUTER_API_KEY env var or pass :api-key option."
                       {:type :missing-api-key})))
     (log/info "Created OpenRouter embedder with model:" model "dimension:" dimension "api-base:" api-base)
     (->OpenRouterEmbedder api-base api-key model dimension))))


(defn set-as-default!
  "Convenience function to set OpenRouter as the default embedding provider.
   Uses qwen/qwen3-embedding-8b (33k context, free tier)."
  ([] (set-as-default! {}))
  ([opts]
   (chroma/set-embedding-provider! (->provider opts))
   (log/info "OpenRouter embeddings enabled with" (or (:model opts) (:model (resolve-config! {}))))))
