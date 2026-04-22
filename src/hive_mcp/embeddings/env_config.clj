(ns hive-mcp.embeddings.env-config
  "Typed env-var / override resolution for embedding providers, via hive-di.

   Separate from hive-mcp.embeddings.config (which is the per-collection
   EmbeddingConfig value-object ns). This ns carries only the provider
   defaults sourced from env + overrides:

     OllamaConfig         / resolve-OllamaConfig
     OpenAIConfig         / resolve-OpenAIConfig
     OpenRouterConfig     / resolve-OpenRouterConfig

   Resolution order (per hive-di):
     1. Explicit overrides map passed to (resolve-*Config overrides)
     2. Environment variable lookup
     3. blank->nil normalization (\"\" → trigger default)
     4. Pre-typed default (skips coercion)
     5. hive-dsl.coerce on string env values"
  (:require [hive-di.core :refer [defconfig env]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defconfig OllamaConfig
  :host  (env "OLLAMA_HOST"
              :default "http://localhost:11434"
              :type    :string
              :doc     "Ollama server base URL (no trailing slash)")
  :model (env "OLLAMA_MODEL"
              :default "nomic-embed-text"
              :type    :string
              :doc     "Embedding model name. Must be present in ollama.clj/models."))

(defconfig OpenAIConfig
  :api-base (env "OPENAI_API_BASE"
                 :default "https://api.openai.com/v1"
                 :type    :string
                 :doc     "OpenAI API base URL — /embeddings is appended.")
  :model    (env "OPENAI_EMBEDDING_MODEL"
                 :default "text-embedding-3-small"
                 :type    :string
                 :doc     "Embedding model name. Must be present in openai.clj/models."))

(defconfig OpenRouterConfig
  :api-base (env "OPENROUTER_API_BASE"
                 :default "https://openrouter.ai/api/v1"
                 :type    :string
                 :doc     "OpenRouter API base URL — /embeddings is appended.")
  :model    (env "OPENROUTER_EMBEDDING_MODEL"
                 :default "qwen/qwen3-embedding-8b"
                 :type    :string
                 :doc     "Embedding model name. Must be present in openrouter.clj/models."))
