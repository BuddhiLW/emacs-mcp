(ns hive-mcp.config.core
  "Global configuration orchestrator — Pipeline/Boundary layer.
   Owns config state atom, orchestrates IO and merge, re-exports accessors.
   Public API surface is unchanged — callers need no modifications."
  (:require [hive-mcp.config.merge :as merge]
            [hive-mcp.config.io :as config-io]
            [hive-mcp.config.resolve :as resolve]
            [hive-mcp.config.secrets :as secrets]
            [hive-mcp.config.schema :as schema]
            [hive-dsl.result :as result]
            [taoensso.timbre :as log]
            [hive-mcp.config.source :as src]))
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

(def ^:dynamic *config-source*
  "Bind to an IConfigSource to override where config is read from.

   nil (production) — the disk-loaded `global-config` atom.
   Tests bind `source/static-source` so they assert against config they declare
   rather than whatever the developer last wrote to ~/.config/hive-mcp."
  nil)

(defn- active-config-atom
  "The atom writes land in: the bound source's when one is bound, else the
   process-wide config atom. Throws when the bound source is read-only — a
   write that cannot land must not pretend it did."
  []
  (if-let [source *config-source*]
    (if (satisfies? src/IMutableConfigSource source)
      (src/-config-atom source)
      (throw (ex-info "Config source is read-only — bind a mutable source to write"
                      {:error :config/read-only-source
                       :source (class source)})))
    global-config))

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
         ;; Validate config schema (warn on invalid, don't block startup)
         validation (schema/validate-config merged)
         _ (when-not (:valid? validation)
             (log/warn "Config validation errors (startup continues with merged config):"
                       (:humanized validation)))
         ;; Validate :memory section specifically for actionable errors
         mem-validation (schema/validate-memory-config merged)
         _ (when (and mem-validation (not (:valid? mem-validation)))
             (log/error "Memory routing config is invalid — memory router may fail:"
                        (:humanized mem-validation)
                        "\nExpected shape: {:default-store :keyword"
                        ":routes {type-kw :store-kw-or-{:primary :kw :projection :kw}}"
                        ":stores {store-kw {:addon :kw ...}}}"))
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
  "Return the effective global config.

   Reads through *config-source* when one is bound (tests bind a static EDN map,
   so they never depend on the developer's ~/.config). Unbound — production —
   this is the disk-loaded atom, falling back to defaults."
  []
  (if-let [source *config-source*]
    (src/-config source)
    (or @global-config merge/default-config)))

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
;; Memory Config Accessors
;; =============================================================================

(defn get-memory-config
  "Return the :memory map from global config."
  []
  (:memory (get-global-config)))

(defn get-memory-route
  "Return the route for a given memory type keyword.
   Falls back to :default-store if no specific route exists."
  [type-kw]
  (let [mem (get-memory-config)]
    (or (get-in mem [:routes type-kw])
        (:default-store mem))))

(defn get-memory-store
  "Return the store definition for a given store keyword."
  [store-kw]
  (get-in (get-memory-config) [:stores store-kw]))

(defn get-kanban-store-mode
  "Return the kanban-store routing mode (:default | :dual-read | :kanban).
   Drives hive-mcp.vectordb.kanban-facade backend selection. Defaults to
   :default when the key is absent so legacy configs keep routing through
   the :default slot (milvus)."
  []
  (or (:kanban-store (get-memory-config)) :default))

;; =============================================================================
;; Generic Path Accessor
;; =============================================================================

(defn get-in-config
  "Read a value at a keyword path vector from the cached config.
   Example: (config/get-in-config [:memory :routes :decision]) => :chroma"
  [path]
  (get-in (get-global-config) path))

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
  "Resolve default drone backend from config, env, or hardcoded fallback.
   Falls back to :agentic-loop (the provider-agnostic drone execution
   backend, post-:openrouter-loop rename) when no config/env value is
   set so that downstream dispatch always has a usable backend keyword."
  []
  (get-service-value :drone :default-backend
                     :env "DRONE_DEFAULT_BACKEND"
                     :parse keyword
                     :default :agentic-loop))

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
  "Update a value at a dotted key path and persist to disk.

   Under a bound `*config-source*` the write stays in memory: a test declares
   config, it does not rewrite the developer's ~/.config/hive-mcp."
  ([key-str value] (set-config-value! key-str value config-io/config-path))
  ([key-str value path]
   (let [kp (merge/parse-key-path key-str)]
     (when (empty? kp)
       (throw (ex-info "Invalid config key path" {:key key-str})))
     (if *config-source*
       (swap! (active-config-atom) assoc-in kp value)
       (do
         (when-not @global-config
           (load-global-config! path))
         (let [updated (swap! global-config assoc-in kp value)]
           (config-io/write-config! updated path)
           (log/info "Config updated:" key-str "=" value)
           updated))))))

;; =============================================================================
;; Runtime Mutation (in-memory only — does NOT persist to disk)
;; =============================================================================

(defn update-in-config!
  "In-memory `update-in` on the effective config. Does NOT persist to disk —
   use `set-config-value!` for that.

   Writes land in whichever atom `*config-source*` designates, so a test that
   binds a source sees its own writes and leaves the real config alone.

   IMPORTANT (production): this bypasses `~/.config/hive-mcp/config.edn`, so the
   change is per-JVM. On the next boot, `load-global-config!` deep-merges the
   on-disk user config over defaults — meaning any value the user explicitly
   placed at `path` via `hive config set` will resurface. Treat this as a
   guarded-override mechanism, not a substitute for persisted user intent."
  [path f & args]
  (let [a (active-config-atom)]
    (when-not @a
      (reset! a merge/default-config))
    (apply swap! a update-in path f args)))

;; =============================================================================
;; Reset (for testing)
;; =============================================================================

(defn reset-config!
  "Reset the cached config to nil. Useful for testing."
  []
  (reset! global-config nil))