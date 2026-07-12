(ns hive-mcp.router.resolve
  "L1 pure — `(config, memory-type) → Result<ProviderSpec>`.

   Zero I/O. Zero global state. Takes the resolved `:embedder` config
   map verbatim and the memory-type keyword/string; returns a
   `hive-dsl.result/Result` of `ProviderSpec`.

   This is the canonical type→spec resolver; the L2 `IRouter` impl
   (`router/default.clj`) wraps it.

   Resolution order (highest to lowest precedence):
     1. `[:routes <:type/X>]`                  — explicit kw-namespaced route
     2. `[:routes <:X>]`                       — bare-keyword route (legacy)
     3. `[:default]`                           — fallback
     4. err `:router/no-default`               — nothing usable

   Once the provider key is known, `[:providers <key>]` produces the
   `ProviderSpec` via `embedder.spec/make`. Unknown provider keys
   surface as `:router/unknown-provider`."
  (:require [hive-dsl.result :as r]
            [hive-mcp.embedder.spec :as spec]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- type->keys
  "Sequence of route-lookup keys to try, in precedence order, for a
   given memory-type input. Accepts keyword (`:type/note`, `:note`),
   string (`\"note\"`, `\"type/note\"`), or nil. Returns up to two
   keywords."
  [memory-type]
  (let [bare (cond
               (keyword? memory-type) (name memory-type)
               (string? memory-type)  (let [s memory-type]
                                        (if-let [slash (some-> s (.indexOf "/") (#(when (>= % 0) %)))]
                                          (subs s (inc slash))
                                          s))
               :else                   nil)]
    (when bare
      [(keyword "type" bare)
       (keyword bare)])))

(defn resolve-provider-key
  "Pure — return the provider key for `memory-type` per the resolution
   order in this ns docstring. Returns a keyword or nil."
  [config memory-type]
  (let [routes (:routes config)]
    (or (some #(get routes %) (type->keys memory-type))
        (:default config))))

(defn resolve-spec
  "Pure — return `(ok ProviderSpec)` for `memory-type`, or an err
   variant naming the failure mode. Validates the spec via
   `embedder.spec/make` so a malformed `:providers` entry surfaces
   here rather than at the embed call site."
  [config memory-type]
  (if-let [provider-key (resolve-provider-key config memory-type)]
    (if-let [provider (get-in config [:providers provider-key])]
      (try
        (r/ok (spec/make
                {:provider/key        provider-key
                 :provider/impl       (:impl provider)
                 :provider/model      (:model provider)
                 :provider/dim        (:dimension provider)
                 :provider/max-tokens (:max-tokens provider)}))
        (catch Exception e
          (r/err :router/invalid-provider-spec
                 {:provider-key provider-key
                  :provider     provider
                  :cause        (.getMessage e)})))
      (r/err :router/unknown-provider
             {:provider-key provider-key
              :memory-type  memory-type
              :known        (-> config :providers keys vec)}))
    (r/err :router/no-default
           {:memory-type memory-type
            :hint        "Set [:embedder :default <provider-key>] or [:embedder :routes <type>]"})))
