(ns hive-mcp.config.core
  "Global configuration orchestrator — Pipeline/Boundary layer.
   Owns config state atom, orchestrates IO and merge, re-exports accessors.
   Public API surface is unchanged — callers need no modifications."
  (:require [hive-mcp.config.merge :as merge]
            [hive-mcp.config.io :as config-io]
            [hive-mcp.config.resolve :as resolve]
            [hive-mcp.config.secrets :as secrets]
            [hive-dsl.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Re-exported Constants
;; =============================================================================

(def default-kg-backend merge/default-kg-backend)
(def default-config merge/default-config)

;; =============================================================================
;; State
;; =============================================================================

;; Cached global configuration atom.
;; nil = not loaded yet, map = loaded config.
(defonce ^:private global-config (atom nil))

;; =============================================================================
;; Loading (Orchestrator)
;; =============================================================================

(defn load-global-config!
  "Load global config from disk, deep-merge with default-config, cache in atom.
   Orchestrates: io/read → merge/deep-merge → atom reset → io/write."
  ([]
   (load-global-config! config-io/config-path))
  ([path]
   (let [;; Read config file, returning nil on error or missing
         read-result (config-io/read-config-file path)
         user-config (if (result/ok? read-result)
                       (:ok read-result)
                       (do (log/warn "Failed reading config:" read-result)
                           nil))
         ;; Try legacy path fallback
         user-config (or user-config
                         (when (= path config-io/config-path)
                           (let [legacy-result (config-io/read-config-file config-io/legacy-config-path)]
                             (when (and (result/ok? legacy-result) (:ok legacy-result))
                               (log/info "Migrating config: found legacy" config-io/legacy-config-path
                                         "-> please move to" config-io/config-path)
                               (:ok legacy-result)))))
         merged (if user-config
                  (merge/deep-merge merge/default-config user-config)
                  merge/default-config)
         ;; Resolve secrets: config → env → pass(1) → nil
         {:keys [secrets sources]} (secrets/resolve-all-secrets (:secrets merged))
         merged (assoc merged :secrets secrets)]
     (reset! global-config merged)
     ;; Persist merged config to disk (creates if missing, updates if incomplete)
     ;; NOTE: we persist the pre-secret-resolution config so pass: refs and nils
     ;; stay in the file — runtime secrets live only in the atom.
     (when (= path config-io/config-path)
       (let [disk-config (if user-config
                           (merge/deep-merge merge/default-config user-config)
                           merge/default-config)]
         (config-io/write-config! disk-config path))
       (when-not user-config
         (log/info "Auto-generated config.edn with defaults at" path)))
     (log/info "Global config loaded from" path
               (if user-config "(user config found, deep-merged with defaults)" "(using defaults)"))
     (secrets/log-secret-sources! sources)
     merged)))

;; =============================================================================
;; Accessors (all return defaults if not yet loaded)
;; =============================================================================

(defn get-global-config
  "Return the cached global config, or defaults if not yet loaded."
  []
  (or @global-config merge/default-config))

(defn get-project-roots
  "Return the :project-roots vector from global config."
  []
  (:project-roots (get-global-config)))

(defn get-defaults
  "Return the :defaults map from global config."
  []
  (:defaults (get-global-config)))

(defn get-project-overrides
  "Return overrides for a specific project-id."
  [project-id]
  (get-in (get-global-config) [:project-overrides project-id]))

(defn get-project-config
  "Return the effective config for a project-id."
  [project-id]
  (let [defaults (get-defaults)
        overrides (get-project-overrides project-id)]
    (if overrides
      (merge defaults overrides)
      defaults)))

(defn get-parent-rules
  "Return the :parent-rules vector from global config."
  []
  (:parent-rules (get-global-config)))

(defn get-parent-for-path
  "Resolve parent-id for a directory path via :parent-rules."
  [directory-path]
  (resolve/get-parent-for-path (get-parent-rules) directory-path))

;; =============================================================================
;; Service & Secret Accessors (delegate to resolve layer)
;; =============================================================================

(defn get-service-config
  "Return config map for a specific service."
  [service-key]
  (get-in (get-global-config) [:services service-key]))

(defn get-service-mode
  "Return the :mode for a service."
  [service-key]
  (get-in (get-global-config) [:services service-key :mode] :local))

(defn get-service-value
  "Get a field from service config with mode-aware resolution and env fallback."
  [service-key field-key & opts]
  (apply resolve/get-service-value (get-global-config) service-key field-key opts))

(defn get-secret
  "Return a secret from config or env var fallback."
  [secret-key]
  (resolve/get-secret (get-global-config) secret-key))

;; =============================================================================
;; Drone Defaults (Convenience Accessors)
;; =============================================================================

(defn default-drone-model
  "Resolve default drone model from config, env, or hardcoded fallback."
  []
  (get-service-value :drone :default-model
                     :env "DRONE_DEFAULT_MODEL"
                     :default "devstral-small:24b"))

(defn default-drone-backend
  "Resolve default drone backend from config, env, or hardcoded fallback."
  []
  (get-service-value :drone :default-backend
                     :env "DRONE_DEFAULT_BACKEND"
                     :default :openrouter))

;; =============================================================================
;; Dotted Key Path Access
;; =============================================================================

(defn parse-key-path
  "Parse a dotted key string into a keyword path vector."
  [key-str]
  (merge/parse-key-path key-str))

(defn get-config-value
  "Read a value at a dotted key path from the cached config."
  [key-str]
  (let [path (merge/parse-key-path key-str)]
    (get-in (get-global-config) path)))

;; =============================================================================
;; Set Config Value (Orchestrator)
;; =============================================================================

(defn set-config-value!
  "Update a value at a dotted key path and persist to disk."
  ([key-str value] (set-config-value! key-str value config-io/config-path))
  ([key-str value path]
   (let [kp (merge/parse-key-path key-str)]
     (when (empty? kp)
       (throw (ex-info "Invalid config key path" {:key key-str})))
     ;; Ensure config is loaded first
     (when-not @global-config
       (load-global-config! path))
     (let [updated (swap! global-config assoc-in kp value)]
       (config-io/write-config! updated path)
       (log/info "Config updated:" key-str "=" value)
       updated))))

;; =============================================================================
;; Reset (for testing)
;; =============================================================================

(defn reset-config!
  "Reset the cached config to nil. Useful for testing."
  []
  (reset! global-config nil))
