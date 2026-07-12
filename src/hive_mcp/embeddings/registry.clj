(ns hive-mcp.embeddings.registry
  "ProviderRegistry for lazy instantiation and caching of embedding providers.


   The registry maintains:
   - Provider factories: Functions that create providers from EmbeddingConfig
   - Provider cache: Memoized provider instances (one per unique config)

   Usage:
     ;; Registry is initialized with built-in factories
     (init!)

     ;; Get provider for a config (lazy instantiation + cache)
     (get-provider (config/ollama-config))

     ;; Register custom factory
     (register-factory! :my-provider my-factory-fn)"
  (:require [hive-mcp.embeddings.config :as config]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; Map of provider-type -> factory function.
;; Factory fn takes EmbeddingConfig, returns EmbeddingProvider.
(defonce ^:private provider-factories (atom {}))

;; Cache of instantiated providers.
;; Key: [provider-type model options-hash]
;; Value: EmbeddingProvider instance
(defonce ^:private provider-cache (atom {}))


(defn- config->cache-key
  "Generate cache key from EmbeddingConfig.
   Uses provider-type, model, and options hash (excluding secrets)."
  [config]
  (let [safe-opts (dissoc (:options config) :api-key)]
    [(:provider-type config)
     (:model config)
     (hash safe-opts)]))


(defn- create-ollama-provider
  "Create Ollama embedding provider from config.
   When (get-in config [:options :executor-fn]) is set, embed calls route
   through the GPU executor (admission-controlled) instead of direct HTTP."
  [config]
  ;; Use existing factory from ollama namespace
  (require 'hive-mcp.embeddings.ollama)
  (let [factory (resolve 'hive-mcp.embeddings.ollama/->provider)]
    (factory {:host        (get-in config [:options :host])
              :model       (:model config)
              :executor-fn (get-in config [:options :executor-fn])
              ;; What config declared about this model. Overrides the built-in
              ;; spec key by key; absent keys fall through to the default.
              :declared    {:dimension (:dimension config)
                            :num-ctx   (get-in config [:options :num-ctx])
                            :vram-mb   (get-in config [:options :vram-mb])}})))

(defn- create-openai-provider
  "Create OpenAI embedding provider from config."
  [config]
  (require 'hive-mcp.embeddings.openai)
  (let [factory (resolve 'hive-mcp.embeddings.openai/->provider)]
    (factory {:api-key (get-in config [:options :api-key])
              :model (:model config)})))

(defn- create-openrouter-provider
  "Create OpenRouter embedding provider from config."
  [config]
  (require 'hive-mcp.embeddings.openrouter)
  (let [factory (resolve 'hive-mcp.embeddings.openrouter/->provider)]
    (factory {:api-key (get-in config [:options :api-key])
              :model (:model config)})))

(defn- create-venice-provider
  "Create Venice embedding provider from config."
  [config]
  (require 'hive-mcp.embeddings.venice)
  (let [factory (resolve 'hive-mcp.embeddings.venice/->provider)]
    (factory {:api-key (get-in config [:options :api-key])
              :model (:model config)})))


(defn register-factory!
  "Register a factory function for a provider type.

   Factory signature: (fn [EmbeddingConfig] -> EmbeddingProvider)

   This enables extending the system with new providers without
   modifying existing code (OCP)."
  [provider-type factory-fn]
  (swap! provider-factories assoc provider-type factory-fn)
  (log/debug "Registered embedding provider factory:" provider-type))

(defn unregister-factory!
  "Remove a registered factory. For testing."
  [provider-type]
  (swap! provider-factories dissoc provider-type))

(defn init!
  "Initialize registry with built-in provider factories.
   Safe to call multiple times."
  []
  (register-factory! :ollama create-ollama-provider)
  (register-factory! :openai create-openai-provider)
  (register-factory! :openrouter create-openrouter-provider)
  (register-factory! :venice create-venice-provider)
  (log/info "Embedding provider registry initialized with factories:"
            (keys @provider-factories))
  true)

(defn register-venice!
  "Idempotent registration of the Venice factory. Used by the addon manifest
   bridge in `hive-mcp.embeddings.init` so Venice self-registers on classpath
   presence without forcing core init! to know about it."
  []
  (register-factory! :venice create-venice-provider)
  true)

(defn get-provider
  "Get or create an embedding provider for the given config.

   Uses lazy instantiation - provider is only created on first access.
   Subsequent calls with equivalent config return cached instance.

   Returns EmbeddingProvider or throws if factory not found."
  [config]
  (when-not (config/valid-config? config)
    (throw (ex-info "Invalid EmbeddingConfig" {:config config})))
  (let [cache-key (config->cache-key config)]
    (if-let [cached (@provider-cache cache-key)]
      (do
        (log/debug "Using cached provider for" (config/describe config))
        cached)
      ;; Create new provider
      (let [provider-type (:provider-type config)
            factory (get @provider-factories provider-type)]
        (when-not factory
          (throw (ex-info (str "No factory registered for provider type: " provider-type)
                          {:provider-type provider-type
                           :registered (keys @provider-factories)})))
        (log/info "Creating embedding provider:" (config/describe config))
        (let [provider (factory config)]
          (swap! provider-cache assoc cache-key provider)
          provider)))))

(defn clear-cache!
  "Clear all cached providers. For testing or after config changes."
  []
  (reset! provider-cache {})
  (log/debug "Provider cache cleared"))

(defn cache-stats
  "Get statistics about the provider cache."
  []
  {:cached-count (count @provider-cache)
   :factories (vec (keys @provider-factories))})


(defn provider-available?
  "Check if a provider can be created for the given config.
   Tries to create the provider, returns true if successful."
  [config]
  (try
    (get-provider config)
    true
    (catch Exception e
      (log/debug "Provider unavailable:" (config/describe config) (.getMessage e))
      false)))

(defn list-factories
  "List all registered provider factories."
  []
  (keys @provider-factories))
