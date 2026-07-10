(ns hive-mcp.embeddings.resilient
  "Bounded, failover EmbeddingProvider decorator over a same-dimension provider
   chain. Satisfies EmbeddingProvider; callers cannot distinguish it from a raw
   provider."
  (:require [hive-mcp.embeddings.protocol :as proto]
            [hive-dsl.result :as r]
            [hive-weave.gate :as gate]
            [hive-weave.safe :as safe]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def default-budget-ms
  "Per-provider wall-clock budget (ms)."
  12000)

(def default-permits
  "Max concurrent embedding calls across the process."
  8)

(defonce ^:private embed-gate
  (gate/gate {:permits default-permits :timeout-ms 30000 :name "embedding-provider"}))

(defn- describe
  "Log label for a chain entry."
  [{:keys [provider-key provider]}]
  (or provider-key
      (some-> provider class .getSimpleName)
      :unknown))

(defn- attempt
  "One bounded attempt against `entry`'s provider. `call` is (fn [provider] -> x).
   Returns [:ok x] or [:err {…}]."
  [entry call budget-ms]
  (let [pk  (describe entry)
        res (safe/safe-future-call
             {:timeout-ms budget-ms :name (str "embed:" pk)}
             #(call (:provider entry)))]
    (if (r/ok? res)
      [:ok (:ok res)]
      (do (log/warn "embedding provider" pk "failed:" (:error res)
                    (or (:message res) ""))
          [:err {:provider pk :error (:error res) :message (:message res)}]))))

(defn- run-chain
  "Try each provider in `chain` under one gate permit, bounded per attempt.
   Returns the first success value; throws ex-info :embedder/chain-exhausted
   when all fail."
  [chain call budget-ms]
  (gate/with-gate embed-gate
    (loop [[entry & more] chain
           failures       []]
      (if (nil? entry)
        (throw (ex-info "All embedding providers in the chain failed"
                        {:error    :embedder/chain-exhausted
                         :failures failures}))
        (let [[tag v] (attempt entry call budget-ms)]
          (if (= tag :ok)
            v
            (recur more (conj failures v))))))))

(defrecord ResilientEmbedder [chain budget-ms]
  proto/EmbeddingProvider
  (embed-text [_ text]
    (run-chain chain (fn [p] (proto/embed-text p text)) budget-ms))
  (embed-batch [_ texts]
    (run-chain chain (fn [p] (proto/embed-batch p texts)) budget-ms))
  (embedding-dimension [_]
    (proto/embedding-dimension (:provider (first chain)))))

(defn resilient-embedder
  "Wrap an ordered, primary-first chain of resolved providers
   ({:provider EmbeddingProvider, :provider-key kw?} …) in a bounded failover
   EmbeddingProvider. Throws on an empty chain."
  ([chain] (resilient-embedder chain default-budget-ms))
  ([chain budget-ms]
   (when (empty? chain)
     (throw (ex-info "resilient-embedder: empty provider chain" {})))
   (->ResilientEmbedder (vec chain) budget-ms)))
