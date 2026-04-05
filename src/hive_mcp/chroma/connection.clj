(ns hive-mcp.chroma.connection
  "Chroma connection configuration, collection management, and health status."
  (:require [clojure-chroma-client.api :as chroma]
            [clojure-chroma-client.config :as chroma-config]
            [hive-mcp.chroma.embeddings :as emb]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private default-config
  {:host "localhost"
   :port 8000
   :collection-name "hive-mcp-memory"})

(defonce ^:private config (atom default-config))

(defn get-config
  "Get current Chroma configuration map."
  []
  @config)

(defn configure!
  "Configure Chroma connection settings."
  [opts]
  (swap! config merge opts)
  (chroma-config/configure (select-keys opts [:host :port :api-version :tenant :database]))
  (log/info "Chroma configured:" (select-keys @config [:host :port :collection-name])))

(defonce ^:private collection-cache (atom nil))

(defn- try-get-collection
  "Try to get existing collection, returns nil on failure."
  [coll-name]
  (try (deref (chroma/get-collection coll-name) 10000 nil)
       (catch Exception _ nil)))

(defn- create-new-collection
  "Create a new Chroma collection with dimension metadata."
  [coll-name dim]
  (let [result (deref (chroma/create-collection coll-name {:metadata {:dimension dim :created-by "hive-mcp"}})
                      15000 ::timeout)]
    (when (= result ::timeout)
      (throw (ex-info "Chroma create-collection timed out" {:collection coll-name})))
    result))

(defn- cache-collection!
  "Cache and return collection, logging action."
  [coll log-msg]
  (reset! collection-cache coll)
  (log/info log-msg)
  coll)

(defn get-or-create-collection
  "Get existing collection or create new one."
  []
  (or @collection-cache
      (let [coll-name (:collection-name @config)
            _ (emb/require-embedding!)
            dim (emb/embedding-dimension (emb/get-embedding-provider))]
        (if-let [existing (try-get-collection coll-name)]
          (cache-collection! existing (str "Using existing Chroma collection: " coll-name))
          (cache-collection! (create-new-collection coll-name dim)
                             (str "Created Chroma collection: " coll-name " dimension: " dim))))))

(defn reset-collection-cache!
  "Reset the collection cache (for testing/reconnection)."
  []
  (reset! collection-cache nil))

(defn status
  "Get Chroma integration status."
  []
  {:configured? (emb/embedding-configured?)
   :provider (when-let [p (emb/get-embedding-provider)] (str (type p)))
   :collection (:collection-name @config)
   :host (:host @config)
   :port (:port @config)})

(defn chroma-available?
  "Check if Chroma is configured and reachable."
  []
  (when (emb/embedding-configured?)
    (try
      (get-or-create-collection)
      true
      (catch Exception e
        (log/debug "Chroma availability check failed:" (.getMessage e))
        false))))

(defn reinitialize-embeddings!
  "Fix hot-reload protocol mismatch by reloading namespaces and reinitializing."
  [& {:keys [provider-type] :or {provider-type :ollama}}]
  (log/info "Reinitializing embeddings due to protocol mismatch...")

  (remove-ns 'hive-mcp.embeddings.ollama)
  (remove-ns 'hive-mcp.embeddings.openai)
  (remove-ns 'hive-mcp.embeddings.openrouter)
  (remove-ns 'hive-mcp.embeddings.registry)

  (require 'hive-mcp.embeddings.ollama :reload)
  (require 'hive-mcp.embeddings.openai :reload)
  (require 'hive-mcp.embeddings.openrouter :reload)
  (require 'hive-mcp.embeddings.registry :reload)

  (reset-collection-cache!)
  (emb/reset-embedding-provider!)

  (let [registry-init (resolve 'hive-mcp.embeddings.registry/init!)
        registry-clear (resolve 'hive-mcp.embeddings.registry/clear-cache!)]
    (registry-clear)
    (registry-init))

  (let [provider (case provider-type
                   :ollama ((resolve 'hive-mcp.embeddings.ollama/->provider))
                   :openai ((resolve 'hive-mcp.embeddings.openai/->provider))
                   :openrouter ((resolve 'hive-mcp.embeddings.openrouter/->provider)))]
    (emb/set-embedding-provider! provider)

    (let [fixed? (satisfies? emb/EmbeddingProvider provider)]
      (if fixed?
        (log/info "Embeddings reinitialized successfully")
        (log/error "Reinitialization failed - protocol still mismatched"))
      {:fixed? fixed?
       :provider-type provider-type
       :dimension (when fixed? (emb/embedding-dimension provider))})))
