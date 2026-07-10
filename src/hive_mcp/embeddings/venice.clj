(ns hive-mcp.embeddings.venice
  "Venice embedding provider for semantic memory search.

   Venice (api.venice.ai) is OpenAI-spec compatible — same /embeddings
   contract as OpenAI/OpenRouter (POST {:model :input}, response shaped
   {:data [{:embedding [...] :index n}]}).

   Default model: text-embedding-qwen3-8b (4096 dims, 32k context). Used
   for memory types whose payloads (long EDN plans) blow past the
   default Ollama embedder's 2048-token ceiling.

   Usage:
     (require '[hive-mcp.embeddings.venice :as venice])
     (require '[hive-mcp.chroma.core :as chroma])

     ;; Default: env-driven (VENICE_API_KEY required)
     (chroma/set-embedding-provider! (venice/->provider))

     ;; Override model
     (chroma/set-embedding-provider!
       (venice/->provider {:model \"text-embedding-qwen3-8b\"}))"
  (:require [hive-mcp.config.core :as global-config]
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
  "Supported Venice embedding models with their dimensions.
   See https://docs.venice.ai for the live list. Venice exposes its
   embedding catalogue via /models — Qwen3-Embedding-8B emits up to 4096
   dims (matrioshka-trained, supports 32..4096). We default to full 4096."
  {"text-embedding-qwen3-8b" 4096})

(def ^:private default-dimension
  "Fallback dimension used when the configured model is not in `models`.
   Matches the qwen3-8b default rather than text-embedding-3-small (1536)
   because Venice's primary embedding offering today is qwen3-8b."
  4096)

(defn- resolve-config!
  "Resolve Venice api-base + model via hive-di (env → overrides → defaults).
   Throws ex-info on :config/invalid."
  [overrides]
  (let [result (env-cfg/resolve-VeniceConfig overrides)]
    (or (:ok result)
        (throw (ex-info "Invalid Venice config"
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
  "Make HTTP POST request to Venice embeddings API."
  [api-base api-key body]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (embeddings-url api-base)))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.timeout (Duration/ofSeconds 30))
                    (.build))
        response (http/send-with-retry http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        body-str (.body response)]
    (if (= status 200)
      (json/read-str body-str :key-fn keyword)
      (throw (ex-info "Venice API error"
                      {:status status
                       :body body-str})))))


(defn- get-embeddings
  "Get embeddings for one or more texts from Venice API."
  [api-base api-key model texts]
  (log/debug "Getting embeddings for" (count texts) "texts using" model)
  (let [response (make-request api-base api-key {:model model
                                                 :input texts})
        data (:data response)]
    ;; Sort by index to ensure order matches input.
    (->> data
         (sort-by :index)
         (mapv :embedding))))


(defrecord VeniceEmbedder [api-base api-key model dimension]
  emb-proto/EmbeddingProvider
  (embed-text [_ text]
    (first (get-embeddings api-base api-key model [text])))
  (embed-batch [_ texts]
    ;; Conservative batch size — Venice has not published a hard cap, so
    ;; partition like OpenRouter (50) to dodge timeouts on long EDN inputs.
    (let [batches (partition-all 50 texts)]
      (vec (mapcat #(get-embeddings api-base api-key model (vec %)) batches))))
  (embedding-dimension [_] dimension))


(defn ->provider
  "Create a Venice embedding provider.

   Options:
     :api-key  - Venice API key (default: global-config :venice-api-key,
                 sourced from VENICE_API_KEY env or pass store).
     :api-base - API base URL (default: env VENICE_API_BASE or
                 https://api.venice.ai/api/v1).
     :model    - Embedding model (default: env VENICE_EMBEDDING_MODEL or
                 text-embedding-qwen3-8b).

   Models:
     - text-embedding-qwen3-8b (4096 dims, 32k context — default)"
  ([] (->provider {}))
  ([{:keys [api-key] :as overrides}]
   (let [{:keys [api-base model]} (resolve-config! (select-keys overrides [:api-base :model]))
         api-key (or api-key (global-config/get-secret :venice-api-key))
         dimension (get models model default-dimension)]
     (when-not api-key
       (throw (ex-info "Venice API key required. Set VENICE_API_KEY env var or pass :api-key option."
                       {:type :missing-api-key})))
     (log/info "Created Venice embedder with model:" model
               "dimension:" dimension "api-base:" api-base)
     (->VeniceEmbedder api-base api-key model dimension))))
