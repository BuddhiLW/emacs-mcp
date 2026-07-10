(ns hive-mcp.router.escalate
  "L1 pure — `(spec, doc-size-tokens) → Result<ProviderSpec>`.

   When `doc-size-tokens` exceeds `(:provider/max-tokens spec)`, look
   up the configured heavy-tier provider key
   (`[:escalation :heavy-tier-key]`) and resolve it. Otherwise return
   `(ok spec)` unchanged.

   Pure — no I/O, no global state. Caller passes the resolved
   `:embedder` config + the spec already chosen by `resolve/resolve-spec`.

   Why a separate fn rather than wiring into `resolve-spec`: SRP. Type
   resolution is one decision (which tier?); size escalation is a
   different decision (does this doc fit the tier?). They evolve
   independently — a future change might add per-type max-tokens
   override without touching escalation policy, or vice versa."
  (:require [hive-dsl.result :as r]
            [hive-mcp.router.resolve :as resolve]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private default-heavy-tier-key
  "Fallback heavy-tier provider key when `[:escalation :heavy-tier-key]`
   is absent from config. Matches the post-Ship 2 default in
   `config/merge.clj`."
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
  "Return `(ok spec)` if the doc fits; otherwise resolve the
   heavy-tier provider and return its spec. If the heavy-tier key
   itself is unknown, surface
   `:router/unknown-provider` from `resolve-spec` — escalation never
   silently falls back to the original spec, since that would defeat
   the whole point of escalation (we'd embed an oversize doc with the
   small-tier provider).

   Already-on-heavy-tier check: if the input spec's `:provider/key`
   already equals the heavy-tier key, return `(ok spec)` even when
   over budget — we have nowhere to escalate to and the caller's
   max-tokens is already the highest configured."
  [config spec doc-size-tokens]
  (cond
    (not (over-budget? spec doc-size-tokens))
    (r/ok spec)

    (= (:provider/key spec) (heavy-tier-key config))
    (r/ok spec)

    :else
    (let [heavy-key (heavy-tier-key config)
          ;; Resolve via the heavy-tier provider key by treating it as
          ;; the "default" route. resolve-spec walks routes first then
          ;; default; we want a direct provider lookup, so synthesise
          ;; a tiny config view that pins :default to heavy-key.
          heavy-config (assoc config :default heavy-key :routes nil)]
      (resolve/resolve-spec heavy-config nil))))
