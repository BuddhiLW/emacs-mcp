(ns hive-mcp.config.merge
  "Pure config transformations — no IO, no atoms, no logging.
   Collect/Promote layer: defaults, deep-merge, key-path parsing."
  (:require [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Defaults
;; =============================================================================

(def default-kg-backend
  "Canonical default KG backend. Referenced by config defaults and connection fallback."
  :datahike)

(def default-config
  "Default configuration. Used as base — user config.edn is deep-merged on top."
  {:project-roots []
   :defaults {:kg-backend default-kg-backend
              :hot-reload false
              :presets-path nil}
   :project-overrides {}
   :parent-rules []
   :memory {:default-store :chroma
            :routes {:decision    :chroma
                     :snippet     :chroma
                     :preference  :chroma
                     :pattern     :chroma
                     :system      :chroma
                     :context     :chroma
                     :reflection  :chroma
                     :note        :chroma}
            ;; Routes can also be maps for dual-write scenarios:
            ;; :snippet {:primary :milvus :projection :chroma}
            :stores {:chroma {:addon :hive-chroma
                              :host "localhost"
                              :port 8000}
                     ;; Example: Milvus store (uncomment to enable)
                     ;; :milvus {:addon :hive-milvus
                     ;;          :host "localhost"
                     ;;          :port 19530
                     ;;          :collection "hive_memory"}
                     ;; Example: Proximum store (uncomment to enable)
                     ;; :proximum {:addon :hive-proximum
                     ;;            :host "localhost"
                     ;;            :port 50051}
                     }}
   :embeddings {:ollama {:host "http://localhost:11434"
                         :model "nomic-embed-text"}
                :openrouter {:model "qwen/qwen3-embedding-8b"}}
   :embedder {:default :ollama-nomic
              :routes {:type/conversation-turn :openrouter-qwen3
                       :type/turn-summary      :openrouter-qwen3
                       :type/decision          :openrouter-qwen3
                       :type/plan              :openrouter-qwen3
                       :type/session-summary   :openrouter-qwen3
                       :type/convention        :openrouter-qwen3
                       :type/axiom             :ollama-nomic
                       :type/principle         :ollama-nomic
                       :type/snippet           :ollama-nomic
                       :type/note              :ollama-nomic}
              :providers {:ollama-nomic     {:impl :ollama
                                             :model "nomic-embed-text"
                                             :max-tokens 2048
                                             :dimension 768}
                          :openrouter-qwen3 {:impl :openrouter
                                             :model "qwen/qwen3-embedding-8b"
                                             :max-tokens 32768
                                             :dimension 4096}}}
   :services {:chroma {:mode :local :host "localhost" :port 8000}
              :ollama {:mode :local :host "http://localhost:11434" :model "nomic-embed-text"}
              :datahike {:mode :local :path "data/kg"}
              :nrepl {:mode :local :port 7910}
              :prometheus {:mode :local :url "http://localhost:9090"}
              :loki {:mode :local :url "http://localhost:3100"}
              :websocket {:mode :local :enabled false :port nil :project-dir nil}
              :ws-channel {:mode :local :port 9999}
              :channel {:mode :local :port 9998}
              :olympus {:mode :local :ws-port 7911}
              :overarch {:mode :local :jar nil}
              :presets {:mode :local :dir nil}
              :kg {:mode :local :backend default-kg-backend
                   :writer {:backend :self}}
              :project {:mode :local :id nil :dir nil :src-dirs ["src"]}
              :forge {:mode :local :legacy false :budget-routing false
                      ;; Max ms to wait for a ling to register in DataScript + pass CLI
                      ;; check before dispatch is attempted. Claude CLI can take 10-30s
                      ;; to start, so 60s is the safe default. Configurable via:
                      ;;   {:services {:forge {:readiness-timeout-ms 90000}}}
                      :readiness-timeout-ms 60000}
              :drone {:mode :local :default-model "devstral-small:24b"}
              :nats {:mode :local
                     :enabled false
                     :url "nats://localhost:4222"
                     :connection-timeout 5000
                     :max-reconnects 5
                     :reconnect-wait 1000}
              :scheduler {:mode :local :enabled true :interval-minutes 60
                          :memory-limit 50 :edge-limit 100 :disc-enabled true}
              :memory-store {:backend :chroma}
              :qdrant-carto {:mode :local
                             :host "localhost"
                             :port 6333
                             :collection "carto-snippets"
                             :embedding {:provider :ollama
                                         :model "nomic-embed-code"}}
              :carto-store {:backend :qdrant-carto}}
   :cartography {:sentinel-path (str (System/getProperty "user.home")
                                     "/.config/hive-mcp/data/carto/preferred-backend.edn")
                 :strict-mode?  true}
   :secrets {:openrouter-api-key nil
             :openai-api-key nil
             :anthropic-api-key nil
             :venice-api-key nil
             :groq-api-key nil
             :together-api-key nil
             :fireworks-api-key nil}
   :llm-providers {:openrouter {:api-url       "https://openrouter.ai/api/v1/chat/completions"
                                :secret-key    :openrouter-api-key
                                :default-model "anthropic/claude-opus-4-7"
                                :available-models ["moonshotai/kimi-k2.5"
                                                   "minimax/minimax-m2.7"
                                                   "qwen/qwen3.6-plus"
                                                   "z-ai/glm-5.1"
                                                   "xiaomi/mimo-v2-pro"
                                                   "anthropic/claude-opus-4-7"
                                                   "anthropic/claude-opus-4-6"
                                                   "anthropic/claude-sonnet-4-6"]}
                   :venice     {:api-url       "https://api.venice.ai/api/v1/chat/completions"
                                :secret-key    :venice-api-key
                                :default-model "venice-uncensored"
                                :available-models ["venice-uncensored"
                                                   "qwen-3-6-plus"]}
                   :groq       {:api-url       "https://api.groq.com/openai/v1/chat/completions"
                                :secret-key    :groq-api-key
                                :default-model "llama-3.3-70b-versatile"
                                :available-models ["llama-3.3-70b-versatile"]}
                   :together   {:api-url       "https://api.together.xyz/v1/chat/completions"
                                :secret-key    :together-api-key
                                :default-model "meta-llama/Llama-3.3-70B-Instruct-Turbo"
                                :available-models ["meta-llama/Llama-3.3-70B-Instruct-Turbo"]}
                   :fireworks  {:api-url       "https://api.fireworks.ai/inference/v1/chat/completions"
                                :secret-key    :fireworks-api-key
                                :default-model "accounts/fireworks/models/llama-v3p3-70b-instruct"
                                :available-models ["accounts/fireworks/models/llama-v3p3-70b-instruct"]}
                   :openai     {:api-url       "https://api.openai.com/v1/chat/completions"
                                :secret-key    :openai-api-key
                                :default-model "gpt-4o-mini"
                                :available-models ["gpt-4o-mini" "gpt-4o"]}
                   :ollama-compat {:api-url       "http://localhost:11434/v1/chat/completions"
                                   :secret-key    nil
                                   :default-model "devstral-small:24b"
                                   :available-models ["devstral-small:24b"]}}
   :hivemind {;; Max chars preserved in a shout :message / :task before truncation.
              ;; One bad shout fans out (per-agent ring × backbone × subscribers),
              ;; so aggressive bound protects every downstream context window.
              :shout-message-cap 2048}
   :headless {;; Default concrete backend keyword for the abstract :headless
              ;; spawn-mode. :auto = registry-driven preference per provider.
              ;; Concrete keys (e.g. :hive-agent) come from addons that register
              ;; via META-INF/hive-addons/*.edn + register-headless!.
              ;; hive-mcp source MUST NOT name concrete backends — keywords here
              ;; are inert operator data.
              :default-backend :auto}
   :agent-defaults {:ling       {:provider :openrouter :model "anthropic/claude-opus-4-7"}
                    :drone      {:provider :openrouter :model "minimax/minimax-m2.7"}
                    :compressor {:provider :venice     :model "venice-uncensored"}}
   :models {:task-models {:coding     "moonshotai/kimi-k2.5"
                          :coding-alt "minimax/minimax-m2.7"
                          :testing    "moonshotai/kimi-k2.5"
                          :bugfix     "moonshotai/kimi-k2.5"
                          :general    "moonshotai/kimi-k2.5"
                          :arch       "qwen/qwen3.6-plus"
                          :docs       "z-ai/glm-5.1"}
            :routing {:testing        {:primary "moonshotai/kimi-k2.5"
                                       :secondary "minimax/minimax-m2.7"}
                      :refactoring    {:primary "moonshotai/kimi-k2.5"
                                       :secondary "minimax/minimax-m2.7"}
                      :implementation {:primary "moonshotai/kimi-k2.5"
                                       :secondary "minimax/minimax-m2.7"}
                      :bugfix         {:primary "moonshotai/kimi-k2.5"
                                       :secondary "minimax/minimax-m2.7"}
                      :documentation  {:primary "z-ai/glm-5.1"
                                       :secondary "qwen/qwen3.6-plus"}
                      :general        {:primary "moonshotai/kimi-k2.5"
                                       :secondary "qwen/qwen3.6-plus"}}
            :default-model "moonshotai/kimi-k2.5"}})

;; =============================================================================
;; Pure Transformations
;; =============================================================================

(defn deep-merge
  "Recursively merge maps. User values take priority at every level.
   Missing keys in user-config are filled from defaults at any depth."
  [defaults user-config]
  (reduce-kv
   (fn [acc k default-val]
     (if (contains? acc k)
       (let [user-val (get acc k)]
         (if (and (map? default-val) (map? user-val))
           (assoc acc k (deep-merge default-val user-val))
           acc)) ; user value wins
       (assoc acc k default-val))) ; fill missing from default
   user-config
   defaults))

(defn parse-key-path
  "Parse a dotted key string into a keyword path vector."
  [key-str]
  (when (and key-str (not (str/blank? key-str)))
    (mapv keyword (str/split key-str #"\."))))
