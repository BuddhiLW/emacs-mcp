(ns hive-mcp.embeddings.openai
  "OpenAI embedding provider for semantic memory search.
   
   Uses OpenAI's text-embedding-3-small model by default (1536 dimensions).
   
   Usage:
     (require '[hive-mcp.embeddings.openai :as openai])
     (require '[hive-mcp.chroma.core :as chroma])
     
     ;; Create provider with API key from env
     (chroma/set-embedding-provider! (openai/->provider))
     
     ;; Or with explicit key
     (chroma/set-embedding-provider! (openai/->provider {:api-key \"sk-...\"}))
     
     ;; Use different model
     (chroma/set-embedding-provider! 
       (openai/->provider {:model \"text-embedding-ada-002\"}))"
  (:require [hive-mcp.chroma.core :as chroma]
            [hive-mcp.config.core :as global-config]
            [hive-mcp.embeddings.http-client :as http]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


(def ^:private models
  "Supported OpenAI embedding models with their dimensions."
  {"text-embedding-3-small" 1536
   "text-embedding-3-large" 3072
   "text-embedding-ada-002" 1536})

(def ^:private default-model "text-embedding-3-small")
(def ^:private api-url "https://api.openai.com/v1/embeddings")


(defonce ^:private http-client
  ;; Self-healing HttpClient cache. See hive-mcp.embeddings.http-client.
  (http/mk-client
   (fn []
     (-> (HttpClient/newBuilder)
         (.connectTimeout (Duration/ofSeconds 30))
         (.build)))))

(defn- make-request
  "Make HTTP POST request to OpenAI API."
  [api-key body]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create api-url))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.timeout (Duration/ofSeconds 60))
                    (.build))
        response (http/send-with-retry http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        body-str (.body response)]
    (if (= status 200)
      (json/read-str body-str :key-fn keyword)
      (throw (ex-info "OpenAI API error"
                      {:status status
                       :body body-str})))))


(defn- get-embeddings
  "Get embeddings for one or more texts from OpenAI API."
  [api-key model texts]
  (log/debug "Getting embeddings for" (count texts) "texts using" model)
  (let [response (make-request api-key {:model model
                                        :input texts})
        data (:data response)]
    ;; Sort by index to ensure order matches input
    (->> data
         (sort-by :index)
         (mapv :embedding))))


(defrecord OpenAIEmbedder [api-key model dimension]
  chroma/EmbeddingProvider
  (embed-text [_ text]
    (first (get-embeddings api-key model [text])))
  (embed-batch [_ texts]
    ;; OpenAI has a limit of ~8000 tokens per batch
    ;; For safety, batch in groups of 100 texts
    (let [batches (partition-all 100 texts)]
      (vec (mapcat #(get-embeddings api-key model (vec %)) batches))))
  (embedding-dimension [_] dimension))


(defn ->provider
  "Create an OpenAI embedding provider.
   
   Options:
     :api-key - OpenAI API key (default: OPENAI_API_KEY env var)
     :model - Embedding model (default: text-embedding-3-small)
   
   Models:
     - text-embedding-3-small (1536 dims, cheapest, recommended)
     - text-embedding-3-large (3072 dims, higher quality)
     - text-embedding-ada-002 (1536 dims, legacy)"
  ([] (->provider {}))
  ([{:keys [api-key model] :or {model default-model}}]
   (let [api-key (or api-key (global-config/get-secret :openai-api-key))
         dimension (get models model)]
     (when-not api-key
       (throw (ex-info "OpenAI API key required. Set OPENAI_API_KEY env var or pass :api-key option."
                       {:type :missing-api-key})))
     (when-not dimension
       (throw (ex-info (str "Unknown model: " model ". Supported: " (keys models))
                       {:type :unknown-model
                        :model model
                        :supported (keys models)})))
     (log/info "Created OpenAI embedder with model:" model "dimension:" dimension)
     (->OpenAIEmbedder api-key model dimension))))
