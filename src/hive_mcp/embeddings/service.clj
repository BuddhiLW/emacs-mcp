(ns hive-mcp.embeddings.service
  "EmbeddingService - Collection-aware embedding domain service.


   Architecture:
   ```
   Application Layer
      memory-tools ─┬─→ EmbeddingService ─┬─→ OllamaProvider (768)
      presets      ─┘   (routes by        ├─→ OpenAIProvider (1536)
                         collection)       └─→ OpenRouterProvider (4096)
   ```

   Fallback Chain:
   1. Collection-specific config (if configured)
   2. Global fallback provider (if set)
   3. Default Ollama (if available)
   4. Error (no embedding available)

   Usage:
     ;; Initialize service
     (init!)

     ;; Configure per-collection embedding
     (configure-collection! \"hive-mcp-presets\" (config/openrouter-config))
     (configure-collection! \"hive-mcp-memory\" (config/ollama-config))

     ;; Embed text for a specific collection
     (embed-for-collection \"hive-mcp-presets\" \"query text\")

     ;; Get dimension for collection's provider
     (get-dimension-for \"hive-mcp-memory\")  ; => 768"
  (:require [hive-mcp.embeddings.config :as config]
            [hive-mcp.embeddings.registry :as registry]
            [hive-mcp.embeddings.cache :as embed-cache]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.config.core :as global-config]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Map of collection-name -> EmbeddingConfig
(defonce ^:private collection-configs (atom {}))

(defonce ^:private initialized? (atom false))

(defn init!
  "Initialize the EmbeddingService.
   Initializes the provider registry and marks service ready.
   Safe to call multiple times."
  []
  (when-not @initialized?
    (registry/init!)
    (reset! initialized? true)
    (log/info "EmbeddingService initialized"))
  true)

(defn initialized?-fn
  "Check if service is initialized."
  []
  @initialized?)

(defn configure-collection!
  "Configure embedding for a specific collection.

   Parameters:
     collection-name - Name of the Chroma collection
     config - EmbeddingConfig (from config/ollama-config etc.)

   Example:
     (configure-collection! \"hive-mcp-presets\" (config/openrouter-config))

   Note: Changing a collection's config may require re-embedding existing data
   if the dimension changes."
  [collection-name config]
  (when-not (config/valid-config? config)
    (throw (ex-info "Invalid EmbeddingConfig" {:collection collection-name
                                               :config config})))
  (let [existing (get @collection-configs collection-name)]
    (when (and existing (not (config/same-dimension? existing config)))
      (log/warn "Dimension change detected for" collection-name
                ":" (:dimension existing) "→" (:dimension config)
                "- Re-embedding may be required"))
    (swap! collection-configs assoc collection-name config)
    (log/info "Configured collection" collection-name
              "with" (config/describe config))))

(defn unconfigure-collection!
  "Remove collection-specific configuration. Collection will use fallback."
  [collection-name]
  (swap! collection-configs dissoc collection-name)
  (log/debug "Removed configuration for collection:" collection-name))

(defn get-collection-config
  "Get the EmbeddingConfig for a collection, or nil if not configured."
  [collection-name]
  (get @collection-configs collection-name))

(defn list-configured-collections
  "List all collections with explicit embedding configuration."
  []
  (into {} (for [[k v] @collection-configs]
             [k (config/describe v)])))

(defn- resolve-provider-for
  "Resolve embedding provider for a collection.

   Fallback chain:
   1. Collection-specific config
   2. Global fallback (chroma/get-embedding-provider)
   3. nil (no provider available)"
  [collection-name]
  (if-let [config (get @collection-configs collection-name)]
    ;; Collection has explicit config
    (do
      (log/debug "Using collection-specific provider for" collection-name
                 ":" (config/describe config))
      (registry/get-provider config))
    ;; Fall back to global provider
    (let [global (chroma/get-embedding-provider)]
      (when global
        (log/debug "Using global fallback provider for" collection-name))
      global)))

(defn get-provider-for
  "Get embedding provider for a collection.

   Returns the provider or throws if no provider is available.
   This is the primary API for getting providers per-collection."
  [collection-name]
  (let [provider (resolve-provider-for collection-name)]
    (when-not provider
      (throw (ex-info (str "No embedding provider available for collection: " collection-name
                           ". Configure with configure-collection! or set global fallback.")
                      {:collection collection-name
                       :configured-collections (keys @collection-configs)
                       :has-global-fallback? (chroma/embedding-configured?)})))
    provider))

(defn embed-for-collection
  "Embed text using the provider configured for the collection.

   Parameters:
     collection-name - Name of the Chroma collection
     text - Text to embed

   Returns: Vector of floats (embedding).

   Results are cached in an in-process LRU+TTL keyed by
   [collection-name, sha256(text)]. Repeat queries skip the provider."
  [collection-name text]
  (or (embed-cache/lookup collection-name text)
      (let [provider (get-provider-for collection-name)
            vec      (chroma/embed-text provider text)]
        (embed-cache/store! collection-name text vec))))

(defn embed-batch-for-collection
  "Embed multiple texts using the provider configured for the collection.

   Parameters:
     collection-name - Name of the Chroma collection
     texts - Seq of texts to embed

   Returns: Seq of embedding vectors"
  [collection-name texts]
  (let [provider (get-provider-for collection-name)]
    (chroma/embed-batch provider texts)))

(defn get-dimension-for
  "Get embedding dimension for a collection's provider.

   Parameters:
     collection-name - Name of the Chroma collection

   Returns: Integer dimension (e.g., 768, 1536, 4096)"
  [collection-name]
  (let [provider (get-provider-for collection-name)]
    (chroma/embedding-dimension provider)))

(defn provider-available-for?
  "Check if an embedding provider is available for a collection.
   Returns true if either collection-specific or global fallback is available."
  [collection-name]
  (boolean (rescue false (resolve-provider-for collection-name) true)))

(defn collection-embedding-status
  "Get embedding status for a specific collection."
  [collection-name]
  (let [config (get @collection-configs collection-name)
        global (chroma/get-embedding-provider)]
    {:collection collection-name
     :has-config? (some? config)
     :config (when config (config/describe config))
     :dimension (when config (:dimension config))
     :has-global-fallback? (some? global)
     :provider-available? (provider-available-for? collection-name)}))

(defn status
  "Get overall EmbeddingService status."
  []
  {:initialized? @initialized?
   :configured-collections (list-configured-collections)
   :collection-count (count @collection-configs)
   :global-fallback? (chroma/embedding-configured?)
   :registry (registry/cache-stats)})

;; =============================================================================
;; Type-based embedder routing (bridge — unblocks ollama 2048-tok ceiling)
;; =============================================================================

(def ^:private base-collection-name "hive-mcp-memory")

(defn- embedder-config
  "Read :embedder block from global config, with merge.clj defaults."
  []
  (get (global-config/get-global-config) :embedder))

(defn- resolve-provider-key
  "Look up provider key for a memory type string. Falls back to :default."
  [type-str]
  (let [cfg    (embedder-config)
        routes (:routes cfg)
        tk     (keyword "type" (name (or type-str "note")))]
    (or (get routes tk)
        (get routes (keyword (name (or type-str "note"))))
        (:default cfg)
        :ollama-nomic)))

(defn resolve-provider-for-type
  "Resolve embedding provider + metadata for a memory type.
   Returns {:provider EmbeddingProvider, :dimension int, :max-tokens int,
            :collection-name str, :provider-key keyword}."
  [memory-type]
  (let [cfg          (embedder-config)
        provider-key (resolve-provider-key memory-type)
        provider-spec (get-in cfg [:providers provider-key])]
    (if provider-spec
      (let [impl      (:impl provider-spec)
            model     (:model provider-spec)
            dimension (:dimension provider-spec)
            max-toks  (:max-tokens provider-spec)
            options   (case impl
                        :ollama     {:host (get provider-spec :host "http://localhost:11434")}
                        :openrouter {:api-key (global-config/get-secret :openrouter-api-key)}
                        :openai     {:api-key (global-config/get-secret :openai-api-key)}
                        {})
            emb-cfg   (config/->EmbeddingConfig impl model dimension options)
            coll-name (if (= dimension 768)
                        base-collection-name
                        (str base-collection-name "-" dimension "d"))]
        {:provider        (registry/get-provider emb-cfg)
         :dimension       dimension
         :max-tokens      max-toks
         :collection-name coll-name
         :provider-key    provider-key})
      ;; No embedder config → fall back to global provider
      (let [global (chroma/get-embedding-provider)]
        (when-not global
          (throw (ex-info "No embedding provider available" {:type memory-type})))
        {:provider        global
         :dimension       (chroma/embedding-dimension global)
         :max-tokens      2048
         :collection-name base-collection-name
         :provider-key    :fallback}))))

(defn validate-content-size!
  "Reject content exceeding the resolved provider's max-tokens.
   Uses chars/4 heuristic for token estimation."
  [doc-text {:keys [max-tokens provider-key] :as _resolved}]
  (let [estimated-tokens (quot (count doc-text) 4)]
    (when (> estimated-tokens max-tokens)
      (throw (ex-info (str "Content too large for embedder " (name provider-key)
                           " (" estimated-tokens " est. tokens > " max-tokens " max)")
                      {:error       :embedder/input-too-large
                       :provider    provider-key
                       :max-tokens  max-tokens
                       :actual      estimated-tokens
                       :fix         "Use a memory type routed to openrouter-qwen3 (decision, plan, etc.)"})))))

(defn type->collection-names
  "Return the collection name(s) to search for a given type.
   If type is nil (unscoped search), returns all known collection names."
  [memory-type]
  (if memory-type
    [(-> (resolve-provider-for-type memory-type) :collection-name)]
    [base-collection-name (str base-collection-name "-4096d")]))

(defn reset-service!
  "Reset all service state. For testing."
  []
  (clojure.core/reset! collection-configs {})
  (registry/clear-cache!)
  (clojure.core/reset! initialized? false)
  (log/info "EmbeddingService reset"))

(defn configure-defaults!
  "Configure default providers for well-known collections.

   Sets up:
   - hive-mcp-memory: Ollama (fast, local, 768 dims)
   - hive-mcp-presets: OpenRouter (accurate, 4096 dims) if API key available
   - hive-mcp-plans: OpenRouter (4096 dims) if API key available, Ollama fallback

   Call after init! for typical hive-mcp setup."
  []
  ;; Memory always uses Ollama (fast, free, local)
  (configure-collection! "hive-mcp-memory" (config/ollama-config))

  ;; Presets use OpenRouter if available (more accurate semantic search)
  (when (global-config/get-secret :openrouter-api-key)
    (try
      (configure-collection! "hive-mcp-presets" (config/openrouter-config))
      (catch Exception e
        (log/warn "Could not configure OpenRouter for presets:" (.getMessage e))
        ;; Fall back to Ollama for presets too
        (configure-collection! "hive-mcp-presets" (config/ollama-config)))))

  ;; Plans use OpenRouter if available (plans are 1000-5000+ chars, exceed Ollama limit)
  (if (global-config/get-secret :openrouter-api-key)
    (try
      (configure-collection! "hive-mcp-plans" (config/openrouter-config))
      (catch Exception e
        (log/warn "Could not configure OpenRouter for plans:" (.getMessage e))
        (configure-collection! "hive-mcp-plans" (config/ollama-config))))
    ;; Fallback: Ollama with truncation risk warning
    (do
      (configure-collection! "hive-mcp-plans" (config/ollama-config))
      (log/warn "Plans collection using Ollama (no OPENROUTER_API_KEY) - entries >1500 chars may be truncated")))

  (log/info "Default embedding configuration applied:"
            (list-configured-collections)))
