(ns hive-mcp.router.escalate
  "L1 pure — `(spec, doc-size-tokens) → Result<ProviderSpec>`.

   When `doc-size-tokens` exceeds `(:provider/max-tokens spec)`, look
   up the configured heavy-tier provider key
   (`[:escalation :heavy-tier-key]`) and resolve it. Otherwise return
   `(ok spec)` unchanged.

   Pure — no I/O, no global state. Caller passes the resolved
   `:embedder` config + the spec already chosen by `resolve/resolve-spec`."
  (:require [hive-dsl.result :as r]
            [hive-mcp.router.resolve :as resolve]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private default-heavy-tier-key
  "Fallback heavy-tier provider key when `[:escalation :heavy-tier-key]`
   is absent from config."
  :venice-qwen3)

(defn- heavy-tier-key
  "Heavy-tier provider key from `[:escalation :heavy-tier-key]`,
   defaulting to the canonical venice-qwen3."
  [config]
  (or (get-in config [:escalation :heavy-tier-key])
      default-heavy-tier-key))

(defn over-budget?
  "Pure predicate — true iff `doc-size-tokens` exceeds the spec's
   `:provider/max-tokens` ceiling. Treats nil sizes as fitting (caller
   supplied no estimate, escalation is opt-in)."
  [spec doc-size-tokens]
  (and (some? doc-size-tokens)
       (number? doc-size-tokens)
       (> doc-size-tokens (:provider/max-tokens spec))))

(defn maybe-escalate
  "Return `(ok spec)` if the doc fits; otherwise resolve the heavy-tier
   provider and return its spec. An unknown heavy-tier key surfaces
   `:router/unknown-provider` from `resolve-spec` (never falls back to
   the original spec). If the input spec is already on the heavy-tier
   key, returns `(ok spec)` even when over budget."
  [config spec doc-size-tokens]
  (cond
    (not (over-budget? spec doc-size-tokens))
    (r/ok spec)

    (= (:provider/key spec) (heavy-tier-key config))
    (r/ok spec)

    :else
    (let [heavy-key (heavy-tier-key config)
          ;; pin :default to the heavy-tier key for a direct provider lookup
          heavy-config (assoc config :default heavy-key :routes nil)]
      (resolve/resolve-spec heavy-config nil))))
