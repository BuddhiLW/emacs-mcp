(ns hive-mcp.embeddings.ollama
  "Ollama embedding provider for semantic memory search.
   
   Uses local Ollama server for free, private embeddings.
   No API key required - all data stays on your machine.
   
   Recommended model: nomic-embed-text (768 dimensions, fast, good quality)
   
   Setup:
     ollama pull nomic-embed-text
   
   Usage:
     (require '[hive-mcp.embeddings.ollama :as ollama])
     (require '[hive-mcp.chroma.core :as chroma])
     
     ;; Create provider with default model
     (chroma/set-embedding-provider! (ollama/->provider))
     
     ;; Or specify model
     (chroma/set-embedding-provider! 
       (ollama/->provider {:model \"mxbai-embed-large\"}))"
  (:require [hive-mcp.concurrency.pool :as pool]
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
  "Supported Ollama embedding models with their dimensions.
   Run `ollama pull <model>` to download."
  {"nomic-embed-text" 768 ; Good balance of speed/quality
   "mxbai-embed-large" 1024 ; Higher quality, slower
   "all-minilm" 384 ; Fastest, lower quality
   "snowflake-arctic-embed" 1024})

(defn- resolve-config!
  "Resolve Ollama host + model via hive-di (env → overrides → defaults).
   Throws ex-info on :config/invalid; callers expect a plain map."
  [overrides]
  (let [result (env-cfg/resolve-OllamaConfig overrides)]
    (or (:ok result)
        (throw (ex-info "Invalid Ollama config"
                        {:type :invalid-config :result result})))))

(defonce ^:private http-client
  ;; Self-healing HttpClient cache. Rebuilds on fatal selector/shutdown errors
  ;; instead of leaving a dead client wedged for the rest of the JVM lifetime.
  (http/mk-client
   (fn []
     (-> (HttpClient/newBuilder)
         (.connectTimeout (Duration/ofSeconds 30))
         (.build)))))

(defn- make-request
  "Make HTTP POST request to Ollama API."
  [host endpoint body]
  (let [url (str host endpoint)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.timeout (Duration/ofSeconds 120)) ; Embeddings can be slow on first run
                    (.build))
        response (http/send-with-retry http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        body-str (.body response)]
    (if (= status 200)
      (json/read-str body-str :key-fn keyword)
      (throw (ex-info "Ollama API error"
                      {:status status
                       :body body-str
                       :url url})))))

(defn- context-length-error?
  "Check if exception message indicates content exceeded token limit."
  [ex]
  (when-let [msg (or (ex-message ex)
                     (some-> (ex-data ex) :body))]
    (boolean (re-find #"(?i)context.*(length|limit|size|exceed)|too (long|large|many)|token.*(limit|exceed)|input.*(too|exceed)" msg))))

(defn- get-embedding
  "Get embedding for a single text from Ollama.
   Throws helpful error when content exceeds embedding token limit."
  [host model text]
  (try
    (let [response (make-request host "/api/embeddings"
                                 {:model model
                                  :prompt text
                                  :keep_alive "24h"})]
      (:embedding response))
    (catch Exception e
      (if (context-length-error? e)
        (throw (ex-info
                (format "Content too long for embedding (~%d chars, ~%d estimated tokens). nomic-embed-text has an 8192 token limit. Please split into smaller memories at section headers or paragraphs."
                        (count text)
                        (quot (count text) 4))
                {:type :embedding-too-long
                 :char-count (count text)
                 :estimated-tokens (quot (count text) 4)
                 :model model
                 :cause e}))
        (throw e)))))

(defn- get-embeddings-batch
  "Get embeddings for multiple texts from Ollama.
   Ollama doesn't have native batch API, so we parallelize requests.
   Bounded by shared IO pool — no unbounded thread fan-out."
  [host model texts]
  (let [futures (mapv (fn [text] (pool/with-io (get-embedding host model text))) texts)]
    (mapv deref futures)))

(defrecord OllamaEmbedder [host model dimension]
  emb-proto/EmbeddingProvider
  (embed-text [_ text]
    (get-embedding host model text))
  (embed-batch [_ texts]
    (get-embeddings-batch host model texts))
  (embedding-dimension [_] dimension))

(defn ->provider
  "Create an Ollama embedding provider.
   
   Options:
     :host - Ollama server URL (default: http://localhost:11434)
     :model - Embedding model (default: nomic-embed-text)
   
   Available models (run `ollama pull <model>` first):
     - nomic-embed-text (768 dims, recommended - good balance)
     - mxbai-embed-large (1024 dims, higher quality)
     - all-minilm (384 dims, fastest)
     - snowflake-arctic-embed (1024 dims)"
  ([] (->provider {}))
  ([overrides]
   (let [{:keys [host model]} (resolve-config! (select-keys overrides [:host :model]))
         dimension (get models model)]
     (when-not dimension
       (throw (ex-info (str "Unknown model: " model ". Supported: " (keys models)
                            "\nYou can also add custom models to the `models` map.")
                       {:type :unknown-model
                        :model model
                        :supported (keys models)})))
     ;; Test connection
     (try
       (let [test-result (make-request host "/api/tags" nil)]
         (log/info "Connected to Ollama at" host)
         (log/debug "Available models:" (mapv :name (:models test-result))))
       (catch Exception _e
         (log/warn "Could not connect to Ollama at" host "- ensure ollama is running")))
     (log/info "Created Ollama embedder with model:" model "dimension:" dimension)
     (->OllamaEmbedder host model dimension))))

(defn list-models
  "List available models on the Ollama server."
  ([] (list-models (:host (resolve-config! {}))))
  ([host]
   (let [response (make-request host "/api/tags" nil)]
     (mapv :name (:models response)))))

(defn pull-model
  "Pull a model from Ollama (downloads if not present).
   This is a convenience wrapper - you can also run `ollama pull <model>` in terminal."
  ([model] (pull-model (:host (resolve-config! {})) model))
  ([host model]
   (log/info "Pulling model:" model "(this may take a while...)")
   (make-request host "/api/pull" {:name model :stream false})))
