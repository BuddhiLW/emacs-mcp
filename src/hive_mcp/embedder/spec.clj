(ns hive-mcp.embedder.spec
  "L0 — `ProviderSpec` ADT + malli schema for the embedder context.

   A `ProviderSpec` is the canonical descriptor of an embedding provider
   instance: which impl, which model, what dimension, what input ceiling.
   It is the unit of currency between router (decides the spec) and
   collection-locator (maps spec → collection ref) and embedder (uses
   the spec's provider to embed).

   Pure data. Constructed only via `make` — kept as plain map so it
   can survive EDN serialization and registry-cache key derivation.
   The malli schema is closed: unknown keys are a programming error,
   not a forward-compat opportunity."
  (:require [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const known-impls
  "Closed set of provider implementations. Adding a new backend
   (e.g. cohere, jina) requires extending this set AND adding an
   `IEmbedder` impl under `embedder/providers/`."
  #{:ollama :venice :openai :openrouter})

(def schema
  "Malli closed-map schema for a `ProviderSpec`."
  [:map {:closed true}
   [:provider/key        keyword?]
   [:provider/impl       (into [:enum] known-impls)]
   [:provider/model      string?]
   [:provider/dim        pos-int?]
   [:provider/max-tokens pos-int?]])

(def validator (m/validator schema))
(def explainer (m/explainer schema))

(defn make
  "Construct a `ProviderSpec`. Validates the resulting map against
   `schema`; throws `:embedder/invalid-spec` on schema violation so
   programming errors fail at the construction site, not three hops
   downstream when a downstream tries to use a malformed spec."
  [{:keys [provider/key provider/impl provider/model
           provider/dim provider/max-tokens] :as fields}]
  (let [spec {:provider/key        key
              :provider/impl       impl
              :provider/model      model
              :provider/dim        dim
              :provider/max-tokens max-tokens}]
    (when-not (validator spec)
      (throw (ex-info "Invalid ProviderSpec"
                      {:err/tag    :embedder/invalid-spec
                       :spec       spec
                       :input      fields
                       :explain    (explainer spec)})))
    spec))

(defn cache-key
  "Stable cache key for a `ProviderSpec`. Excludes nothing — the spec
   itself is already free of secrets (api keys live in provider opts,
   not in the spec). Two specs hash equal iff they describe the same
   provider instance."
  [spec]
  [(:provider/impl spec) (:provider/model spec) (:provider/dim spec)])
