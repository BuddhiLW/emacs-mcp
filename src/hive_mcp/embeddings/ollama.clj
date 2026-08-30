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
            [hive-mcp.embeddings.model-spec :as spec]
            [hive-mcp.embeddings.protocol :as emb-proto]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def models
  "Model -> dimension for the models we ship a default spec for. Config may
   declare any other."
  (into {} (for [[m s] spec/built-in] [m (:dimension s)])))

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
  [host model text num-ctx]
  (try
    (let [response (make-request host "/api/embed"
                                 {:model model
                                  :input text
                                  :keep_alive "24h"
                                  :options {:num_ctx num-ctx}})]
      (first (:embeddings response)))
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
  "Embeddings for TEXTS, one request per text, fanned out over the shared IO
   pool. Returns a vector aligned with TEXTS.

   Ollama's /api/embed DOES accept an `input` array; the fan-out is measured
   faster than one array request, not a workaround for a missing API."
  [host model texts num-ctx]
  (let [futures (mapv (fn [text] (pool/with-io (get-embedding host model text num-ctx))) texts)]
    (mapv deref futures)))

(defn- executor-embed-one
  "Route a single embed call through executor-fn. Returns the embedding
   vector, or throws ex-info matching the direct-HTTP error contract."
  [executor-fn host model vram-mb text]
  (let [resp (executor-fn {:gpu/op       :embed
                           :gpu/vram-mb  vram-mb
                           :gpu/payload  {:text text}
                           :gpu/model    model})]
    (cond
      (:ok resp)
      (-> resp :ok :gpu/output :vectors first vec)

      (:error resp)
      (throw (ex-info "Executor embed failed"
                      {:executor :executor-fn
                       :host     host
                       :model    model
                       :error    (:error resp)
                       :data     (dissoc resp :error)}))

      :else
      (throw (ex-info "Executor returned non-Result shape"
                      {:resp resp})))))

(defrecord OllamaEmbedder [host model spec executor-fn]
  emb-proto/EmbeddingProvider
  (embed-text [_ text]
    (if executor-fn
      (executor-embed-one executor-fn host model (:vram-mb spec) text)
      (get-embedding host model text (:num-ctx spec))))
  (embed-batch [_ texts]
    (if executor-fn
      (mapv #(executor-embed-one executor-fn host model (:vram-mb spec) %) texts)
      (get-embeddings-batch host model texts (:num-ctx spec))))
  (embedding-dimension [_] (:dimension spec)))

(defn ->provider
  "Create an Ollama embedding provider.

   Options:
     :host        - Ollama server URL (default: http://localhost:11434)
     :model       - Embedding model (default: nomic-embed-text)
     :declared    - Partial ModelSpec from config {:dimension :num-ctx :vram-mb}.
                    Any key present overrides the built-in default for it.
     :catalog     - An IModelCatalog to resolve the model against. Defaults to
                    the built-in table with :declared layered over it.
     :executor-fn - Route embeds through the GPU executor instead of HTTP."
  ([] (->provider {}))
  ([overrides]
   (let [{:keys [host model]} (resolve-config! (select-keys overrides [:host :model]))
         catalog     (or (:catalog overrides)
                         (spec/layered (spec/built-in-catalog)
                                       (spec/table-catalog {(str model) (:declared overrides)})))
         resolved    (spec/spec-for catalog model)
         executor-fn (:executor-fn overrides)]
     (when-not (:dimension resolved)
       (throw (ex-info (str "Unknown model: " model
                            ". Declare its :dimension in the provider config, "
                            "or use one of: " (keys models))
                       {:type      :unknown-model
                        :model     model
                        :supported (keys models)})))
     ;; Test connection (skip when an executor-fn is bound — caller owns transport)
     (when-not executor-fn
       (try
         (let [test-result (make-request host "/api/tags" nil)]
           (log/info "Connected to Ollama at" host)
           (log/debug "Available models:" (mapv :name (:models test-result))))
         (catch Exception _e
           (log/warn "Could not connect to Ollama at" host "- ensure ollama is running"))))
     (log/info "Created Ollama embedder with model:" model
               "dimension:" (:dimension resolved)
               "num-ctx:" (:num-ctx resolved)
               "executor-routed?" (some? executor-fn))
     (->OllamaEmbedder host model resolved executor-fn))))

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
