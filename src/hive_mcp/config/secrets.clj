(ns hive-mcp.config.secrets
  "Startup secret resolution with pass(1) fallback.
   Resolution chain: config value → env var → pass store → nil.
   Resolves all secrets once at startup, logs sources (never values)."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [hive-dsl.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Pass Store Registry
;; =============================================================================

(def secret-registry
  "Maps secret keyword → [ENV_VAR pass-path].
   Each entry defines the env var name and the pass(1) store path."
  {:openrouter-api-key  ["OPENROUTER_API_KEY"  "openrouter/hive-mcp"]
   :openai-api-key      ["OPENAI_API_KEY"      "openai/hive-mcp"]
   :anthropic-api-key   ["ANTHROPIC_API_KEY"    "Anthropic/api-key"]
   :venice-api-key      ["VENICE_API_KEY"       "Venice/key"]
   :groq-api-key        ["GROQ_API_KEY"         "groq/hive-mcp"]
   :together-api-key    ["TOGETHER_API_KEY"     "together/hive-mcp"]
   :fireworks-api-key   ["FIREWORKS_API_KEY"    "fireworks/hive-mcp"]})

;; =============================================================================
;; Pass Integration
;; =============================================================================

(defn- password-store-dir
  "Root of the pass(1) store.

   `pass` defaults to ~/.password-store. An XDG install lives under
   ~/.local/share/password-store and is invisible to a process that
   inherits no PASSWORD_STORE_DIR, so every lookup resolves to nil and the
   secret reads as absent rather than as unreachable."
  []
  (or (System/getenv "PASSWORD_STORE_DIR")
      (let [xdg (str (System/getProperty "user.home") "/.local/share/password-store")]
        (when (.isDirectory (java.io.File. xdg)) xdg))
      (str (System/getProperty "user.home") "/.password-store")))

(defn pass-show
  "Shell out to `pass show <path>`, return first line trimmed, nil on failure."
  [path]
  (rescue nil
    (let [env (assoc (into {} (System/getenv))
                     "PASSWORD_STORE_DIR" (password-store-dir))
          {:keys [exit out]} (shell/sh "pass" "show" path :env env)]
      (when (zero? exit)
        (let [line (str/trim (first (str/split-lines (str out))))]
          (when-not (str/blank? line)
            line))))))

;; =============================================================================
;; Resolution Chain
;; =============================================================================

(defn resolve-secret
  "Resolve a single secret: config-val → env → pass → nil.
   Returns {:value v :source :config|:env|:pass|:missing}."
  [config-val env-var pass-path]
  (let [;; 1. Config value (non-nil, non-blank string or non-string truthy)
        from-config (when config-val
                      (if (string? config-val)
                        (when-not (str/blank? config-val) config-val)
                        config-val))]
    (cond
      ;; Config value present (could be pass: prefixed — handled upstream by resolve.clj)
      from-config
      {:value from-config :source :config}

      ;; 2. Environment variable
      (when-let [ev (System/getenv env-var)]
        (not (str/blank? ev)))
      {:value (System/getenv env-var) :source :env}

      ;; 3. Pass store fallback
      :else
      (if-let [pv (when pass-path (pass-show pass-path))]
        {:value pv :source :pass}
        {:value nil :source :missing}))))

(defn resolve-all-secrets
  "Resolve all registered secrets against the current config's :secrets map.
   Returns {:secrets {kw value ...} :sources {kw :env|:pass|:config|:missing ...}}."
  [config-secrets]
  (reduce-kv
   (fn [acc secret-key [env-var pass-path]]
     (let [config-val (get config-secrets secret-key)
           {:keys [value source]} (resolve-secret config-val env-var pass-path)]
       (-> acc
           (assoc-in [:secrets secret-key] value)
           (assoc-in [:sources secret-key] source))))
   {:secrets {} :sources {}}
   secret-registry))

;; =============================================================================
;; Startup Logging
;; =============================================================================

(defn log-secret-sources!
  "Log which secrets resolved and from where. Never logs actual values."
  [sources]
  (let [grouped (group-by val sources)
        found   (remove #(= :missing (key %)) grouped)
        missing (get grouped :missing)]
    (doseq [[source entries] (sort-by key found)]
      (log/info (str "Secrets [" (name source) "]: "
                     (str/join ", " (map (comp name key) entries)))))
    (when (seq missing)
      (log/warn (str "Secrets [missing]: "
                     (str/join ", " (map (comp name key) missing)))))))
