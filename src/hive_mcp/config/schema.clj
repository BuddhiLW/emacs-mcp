(ns hive-mcp.config.schema
  "Malli schemas for config.edn validation.

   Validates config shape at load time with actionable error messages.
   Primary use case: memory routing config for per-type backend routing.

   Usage:
     (validate-config cfg)       ;; => {:valid? true :config cfg} or {:valid? false :errors [...]}
     (validate-section :memory cfg) ;; validate just the :memory section"
  (:require [malli.core :as m]
            [malli.error :as me]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Memory Routing Schemas
;; =============================================================================

(def StoreRef
  "Reference to a named store — keyword like :milvus, :chroma, :proximum."
  :keyword)

(def RouteTarget
  "A route target: either a simple store keyword or a map with primary + projection."
  [:or
   :keyword
   [:map
    [:primary :keyword]
    [:projection {:optional true} :keyword]]])

(def StoreDefinition
  "A store definition: must have :addon keyword, rest is store-specific opts."
  [:map
   [:addon :keyword]
   [:host {:optional true} :string]
   [:port {:optional true} :int]
   [:collection {:optional true} :string]
   [:api-key {:optional true} :string]
   [:timeout-ms {:optional true} :int]
   [:embedding {:optional true} [:map
                                  [:provider {:optional true} :keyword]
                                  [:model {:optional true} :string]]]])

(def MemoryConfig
  "Schema for the :memory section of config.edn.
   Defines default store, per-type routes, and store connection definitions."
  [:map
   [:default-store :keyword]
   [:routes {:optional true} [:map-of :keyword RouteTarget]]
   [:stores {:optional true} [:map-of :keyword StoreDefinition]]])

;; =============================================================================
;; Top-level Config Schema (extensible — validates known sections)
;; =============================================================================

(def ConfigSchema
  "Top-level config.edn schema. Open map — unknown keys pass through.
   Only validates structure of known sections."
  [:map
   [:memory {:optional true} MemoryConfig]
   [:project-roots {:optional true} [:vector :string]]
   [:defaults {:optional true} [:map
                                 [:kg-backend {:optional true} :keyword]
                                 [:hot-reload {:optional true} :boolean]
                                 [:presets-path {:optional true} [:maybe :string]]]]
   [:project-overrides {:optional true} :map]
   [:parent-rules {:optional true} [:vector :map]]
   [:embeddings {:optional true} :map]
   [:services {:optional true} :map]
   [:secrets {:optional true} :map]
   [:models {:optional true} :map]])

;; =============================================================================
;; Validation API
;; =============================================================================

(defn validate-config
  "Validate a full config map against ConfigSchema.
   Returns {:valid? true :config cfg} on success,
   {:valid? false :errors [...] :humanized {...}} on failure."
  [cfg]
  (if (m/validate ConfigSchema cfg)
    {:valid? true :config cfg}
    (let [explanation (m/explain ConfigSchema cfg)]
      {:valid? false
       :errors (:errors explanation)
       :humanized (me/humanize explanation)})))

(defn validate-section
  "Validate a specific config section by key.
   Supported sections: :memory
   Returns {:valid? true :value v} or {:valid? false :errors [...] :humanized {...}}"
  [section-key value]
  (let [schema (case section-key
                 :memory MemoryConfig
                 (throw (ex-info (str "Unknown config section: " section-key)
                                 {:section section-key})))]
    (if (m/validate schema value)
      {:valid? true :value value}
      (let [explanation (m/explain schema value)]
        {:valid? false
         :errors (:errors explanation)
         :humanized (me/humanize explanation)}))))

(defn validate-memory-config
  "Convenience: validate just the :memory section from a full config.
   Returns nil if :memory section is absent (valid — it's optional)."
  [cfg]
  (when-let [mem (:memory cfg)]
    (validate-section :memory mem)))

(comment
  ;; Valid memory config
  (validate-section :memory
                    {:default-store :milvus
                     :routes {:decision :milvus
                              :snippet {:primary :milvus :projection :chroma}
                              :preference :milvus}
                     :stores {:milvus {:addon :hive-milvus
                                       :host "localhost"
                                       :port 19530}
                              :chroma {:addon :hive-chroma
                                       :host "localhost"
                                       :port 8000}}})
  ;; => {:valid? true :value {...}}

  ;; Invalid — missing :default-store
  (validate-section :memory {:routes {:decision :milvus}})
  ;; => {:valid? false :errors [...] :humanized {...}}

  ;; Full config validation
  (validate-config {:memory {:default-store :milvus}
                    :project-roots ["/home/user/PP"]})
  ;; => {:valid? true :config {...}}
  )
