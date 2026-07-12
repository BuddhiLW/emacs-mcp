(ns hive-mcp.embeddings.resilient
  "Bounded, failover EmbeddingProvider decorator over a same-dimension provider
   chain. Satisfies EmbeddingProvider; callers cannot distinguish it from a raw
   provider."
  (:require [hive-mcp.embeddings.protocol :as proto]
            [hive-dsl.result :as r]
            [hive-weave.gate :as gate]
            [hive-weave.safe :as safe]
            [taoensso.timbre :as log]
            [hive-mcp.embeddings.deadline :as dl]
            [hive-dsl.adt :as adt]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def default-budget-ms
  "Per-attempt wall-clock budget (ms). Capped by whatever the deadline has left."
  12000)

(def default-total-budget-ms
  "Wall-clock budget for the WHOLE chain — permit wait included.

   MUST stay strictly below the caller's write budget. A chain that can outlive
   its caller cannot report failure: the caller times out first, and the entry
   it was embedding is silently dropped."
  20000)

(def default-permits
  "Max concurrent embedding calls across the process."
  8)

(defonce ^:private embed-gate
  (gate/gate {:permits    default-permits
              :timeout-ms default-total-budget-ms
              :name       "embedding-provider"}))

(defn- describe
  "Log label for a chain entry."
  [{:keys [provider-key provider]}]
  (or provider-key
      (some-> provider class .getSimpleName)
      :unknown))

(adt/defadt AttemptOutcome
  "What one provider attempt can yield. Closed, so the chain loop cannot
   silently forget a case."
  [:attempt/ok     {:value any?}]
  [:attempt/failed {:provider any? :error any? :message any?}])

(defn- attempt
  "One bounded call against `entry`'s provider. `call` is (fn [provider] -> x).
   `budget-ms` is already capped by the deadline."
  [entry call budget-ms]
  (let [pk  (describe entry)
        res (safe/safe-future-call
             {:timeout-ms budget-ms :name (str "embed:" pk)}
             #(call (:provider entry)))]
    (if (r/ok? res)
      (attempt-outcome :attempt/ok {:value (:ok res)})
      (do (log/warn "embedding provider" pk "failed:" (:error res)
                    (or (:message res) ""))
          (attempt-outcome :attempt/failed {:provider pk
                                            :error    (:error res)
                                            :message  (:message res)})))))

(defn- run-chain
  "Try each provider in `chain` under one gate permit, bounded by ONE deadline.

   The deadline starts before the permit wait, so a slow queue eats the same
   budget a slow provider would. Each attempt may spend only what is left, so
   the chain always returns — successfully, or with an exception — inside
   `total-budget-ms`.

   Returns the first success value; throws ex-info :embedder/chain-exhausted
   (:exhausted-by :providers | :deadline) when none succeeds."
  [chain call budget-ms total-budget-ms]
  (let [dl (dl/deadline total-budget-ms)]
    (gate/with-gate embed-gate
      (loop [[entry & more] chain
             failures       []]
        (cond
          (nil? entry)
          (throw (ex-info "All embedding providers in the chain failed"
                          {:error        :embedder/chain-exhausted
                           :exhausted-by :providers
                           :failures     failures}))

          (dl/expired? dl)
          (throw (ex-info "Embedding chain ran out of time before a provider succeeded"
                          {:error          :embedder/chain-exhausted
                           :exhausted-by   :deadline
                           :total-budget-ms total-budget-ms
                           :untried        (mapv describe (cons entry more))
                           :failures       failures}))

          :else
          (let [outcome (attempt entry call (dl/attempt-budget-ms dl budget-ms))]
            (adt/adt-case AttemptOutcome outcome
                          :attempt/ok     (:value outcome)
                          :attempt/failed (recur more (conj failures
                                                           (select-keys outcome
                                                                        [:provider :error :message]))))))))))

(defrecord ResilientEmbedder [chain budget-ms total-budget-ms]
  proto/EmbeddingProvider
  (embed-text [_ text]
    (run-chain chain (fn [p] (proto/embed-text p text)) budget-ms total-budget-ms))
  (embed-batch [_ texts]
    (run-chain chain (fn [p] (proto/embed-batch p texts)) budget-ms total-budget-ms))
  (embedding-dimension [_]
    (proto/embedding-dimension (:provider (first chain)))))

(defn resilient-embedder
  "Wrap an ordered, primary-first chain of resolved providers
   ({:provider EmbeddingProvider, :provider-key kw?} …) in a bounded failover
   EmbeddingProvider.

   `total-budget-ms` bounds the whole chain; `budget-ms` bounds one attempt
   within it. Throws on an empty chain."
  ([chain] (resilient-embedder chain default-budget-ms default-total-budget-ms))
  ([chain budget-ms] (resilient-embedder chain budget-ms default-total-budget-ms))
  ([chain budget-ms total-budget-ms]
   (when (empty? chain)
     (throw (ex-info "resilient-embedder: empty provider chain" {})))
   (->ResilientEmbedder (vec chain) budget-ms total-budget-ms)))