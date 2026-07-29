(ns hive-mcp.agent.ling.lifecycle
  "Lifecycle helpers for Ling agents: mode/strategy resolution, API key
   bridging, ctx building, and critical-operation guards.

   Split from `hive-mcp.agent.ling` (hotspot #20) — pure helpers with no
   dependency on the Ling record or spawn path, safe to require from both
   the spawn and status submodules."
  (:require [hive-mcp.agent.ling.terminal-registry :as terminal-reg]
            [hive-mcp.agent.ling.headless-registry :as headless-reg]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.schema :as schema]
            [hive-mcp.config.core :as global-config]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private provider->secret-key
  "Maps provider keyword to config secret key for API key resolution."
  {:openrouter :openrouter-api-key
   :openai     :openai-api-key
   :venice     :venice-api-key
   :groq       :groq-api-key
   :together   :together-api-key
   :fireworks  :fireworks-api-key})

(defn resolve-api-key-for-provider
  "Resolve API key from hive-mcp config secrets for a given provider.
   Returns the key string or nil. Bridges config.edn secrets -> headless spawn."
  [provider]
  (when-let [secret-key (get provider->secret-key (keyword provider))]
    (r/rescue nil (global-config/get-secret secret-key))))

(def ^:const default-spawn-mode
  "Spawn mode applied when a caller supplies none.
   Single source of truth for the default — `resolve-effective-mode` and
   `hive-mcp.agent.ling.spawn/->ling` both read it, and tests assert against
   it rather than restating a literal."
  :claude)

(defn resolve-effective-mode
  "Pure function: raw spawn inputs -> effective spawn mode keyword.

   Provider/model are NOT inputs — they're orthogonal infrastructure
   concerns consumed by the backend's LLM router, not the spawn-mode
   resolver (DDD: spawn-mode is a domain decision, provider routing is
   infrastructure).

   When `:spawn-mode` is :headless, the concrete backend keyword is
   resolved via headless-registry/resolve-default-backend (priority-ranked
   addon backends, honoring operator override in
   ~/.config/hive-mcp/config.edn `[:headless :default-backend]`).
   hive-mcp never names a concrete backend — addons contribute keywords
   via META-INF discovery + register-headless! / register-mode!.

   When :spawn-mode is any other valid keyword, returned unchanged.
   When omitted, defaults to `default-spawn-mode`."
  [{:keys [spawn-mode]}]
  (let [raw-mode (or spawn-mode default-spawn-mode)]
    (if (= raw-mode :headless)
      (or (headless-reg/resolve-default-backend nil)
          (do (log/warn "No headless backend resolvable, leaving :headless abstract"
                        {:registered (headless-reg/registered-headless)})
              :headless))
      raw-mode)))

(defn resolve-strategy
  "Get the ILingStrategy implementation for a spawn mode.
   Checks terminal registry first, then headless registry.
   Pure OCP — no hardcoded strategy constructors."
  [mode]
  (or (terminal-reg/resolve-terminal-strategy mode)
      (headless-reg/resolve-headless-strategy mode)
      (throw (ex-info (str "No strategy registered for mode: " mode)
                      {:mode mode
                       :registered-terminals (terminal-reg/registered-terminals)
                       :registered-headless (headless-reg/registered-headless)}))))

(defn ling-ctx
  "Build a context map from a Ling record for strategy calls."
  [ling]
  (cond-> {:id (:id ling)
           :cwd (:cwd ling)
           :presets (:presets ling)
           :project-id (:project-id ling)
           :spawn-mode (:spawn-mode ling)
           :model (:model ling)}
    (:provider ling) (assoc :provider (:provider ling))
    (some? (:kg-compress? ling)) (assoc :kg-compress? (:kg-compress? ling))
    (some? (:verbose? ling)) (assoc :verbose? (:verbose? ling))
    (:llm-retries ling) (assoc :llm-retries (:llm-retries ling))
    (:sliding-window-size ling) (assoc :sliding-window-size (:sliding-window-size ling))
    (:agents ling) (assoc :agents (:agents ling))))

(defn with-critical-op
  "Execute body while holding a critical operation guard."
  [ling-id op-type body-fn]
  (ds-lings/with-critical-op ling-id op-type
    (body-fn)))
