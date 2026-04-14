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
   :secrets {:openrouter-api-key nil
             :openai-api-key nil
             :anthropic-api-key nil
             :venice-api-key nil
             :groq-api-key nil
             :together-api-key nil
             :fireworks-api-key nil}
   :models {:task-models {:coding "x-ai/grok-code-fast-1"
                          :coding-alt "deepseek/deepseek-v3.2"
                          :testing "x-ai/grok-code-fast-1"
                          :bugfix "x-ai/grok-code-fast-1"
                          :general "x-ai/grok-code-fast-1"
                          :arch "deepseek/deepseek-v3.2"
                          :docs "deepseek/deepseek-v3.2"}
            :routing {:testing {:primary "x-ai/grok-code-fast-1"
                                :secondary "deepseek/deepseek-v3.2"}
                      :refactoring {:primary "x-ai/grok-code-fast-1"
                                    :secondary "deepseek/deepseek-v3.2"}
                      :implementation {:primary "x-ai/grok-code-fast-1"
                                       :secondary "deepseek/deepseek-v3.2"}
                      :bugfix {:primary "x-ai/grok-code-fast-1"
                               :secondary "deepseek/deepseek-v3.2"}
                      :documentation {:primary "deepseek/deepseek-v3.2"
                                      :secondary "x-ai/grok-code-fast-1"}
                      :general {:primary "x-ai/grok-code-fast-1"
                                :secondary "deepseek/deepseek-v3.2"}}
            :default-model "x-ai/grok-code-fast-1"}})

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
