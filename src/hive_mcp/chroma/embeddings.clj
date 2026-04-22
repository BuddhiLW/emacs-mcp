(ns hive-mcp.chroma.embeddings
  "Embedding provider management (atom-backed registry, routing helpers).

   The EmbeddingProvider protocol itself has moved to
   hive-mcp.embeddings.protocol so that embedder implementations do not
   need to depend on this (chroma-layer) namespace. Aliases below keep
   backward compatibility for any existing callers that reach for
   `chroma.embeddings/EmbeddingProvider` or its method fns."
  (:require [hive-mcp.embeddings.protocol :as proto]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; --- Backward-compat re-exports for the relocated protocol ---
(def EmbeddingProvider proto/EmbeddingProvider)
(def embed-text proto/embed-text)
(def embed-batch proto/embed-batch)
(def embedding-dimension proto/embedding-dimension)

(defonce ^:private embedding-provider (atom nil))

(defn set-embedding-provider!
  "Set the embedding provider for vectorization."
  [provider]
  (reset! embedding-provider provider)
  (log/info "Embedding provider set:" (type provider)))

(defn embedding-configured?
  "Check if an embedding provider is configured."
  []
  (some? @embedding-provider))

(defn get-embedding-provider
  "Get the current embedding provider."
  []
  @embedding-provider)

(defn reset-embedding-provider!
  "Reset the embedding provider atom."
  []
  (reset! embedding-provider nil)
  (log/info "Embedding provider reset (was:" (type @embedding-provider) ")"))

(defn require-embedding!
  "Guard: throws if embedding provider not configured."
  []
  (when-not @embedding-provider
    (throw (ex-info "Embedding provider not configured. Call set-embedding-provider! first."
                    {:type :no-embedding-provider}))))

(defn get-provider-for
  "Get embedding provider for a specific collection."
  [collection-name]
  (try
    (require 'hive-mcp.embeddings.service)
    (let [get-provider (resolve 'hive-mcp.embeddings.service/get-provider-for)]
      (get-provider collection-name))
    (catch Exception _
      (or @embedding-provider
          (throw (ex-info "No embedding provider available" {:collection collection-name}))))))

(defn embed-text-for
  "Embed text using the provider configured for the collection."
  [collection-name text]
  (let [provider (get-provider-for collection-name)]
    (embed-text provider text)))

(defn embed-batch-for
  "Embed multiple texts using the provider configured for the collection."
  [collection-name texts]
  (let [provider (get-provider-for collection-name)]
    (embed-batch provider texts)))

(defn get-dimension-for
  "Get embedding dimension for a collection's provider."
  [collection-name]
  (let [provider (get-provider-for collection-name)]
    (embedding-dimension provider)))
