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

(defn resolve-effective-mode
  "Pure function: raw spawn inputs -> effective spawn mode keyword.
   Handles OpenRouter model detection and headless registry resolution."
  [{:keys [model spawn-mode]}]
  (let [non-claude? (and model (not (schema/claude-model? model)))
        raw-mode (if non-claude?
                   :openrouter
                   (or spawn-mode :claude))]
    (if (= raw-mode :headless)
      (or (headless-reg/best-headless-for-provider :claude)
          (do (log/warn "No headless backend registered for :claude, falling back to :headless"
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
    (:sliding-window-size ling) (assoc :sliding-window-size (:sliding-window-size ling))
    (:agents ling) (assoc :agents (:agents ling))))

(defn with-critical-op
  "Execute body while holding a critical operation guard."
  [ling-id op-type body-fn]
  (ds-lings/with-critical-op ling-id op-type
    (body-fn)))
