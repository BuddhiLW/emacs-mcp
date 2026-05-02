(ns hive-mcp.agent.spawn-mode-registry
  "Single source of truth for ling spawn modes and their properties.

   All consumers derive from this registry — no scattered enums.
   Leaf namespace: zero hive-mcp dependencies (safe to require anywhere).

   Design principle: Knowledge-Layer-First / SST (Single Source of Truth).
   Adding a new spawn mode = adding one entry here. All downstream
   validation, MCP schemas, strategy dispatch, and slot limits derive automatically.

   Extensible at runtime via register-mode! for addon-contributed modes
   (e.g. :tmux from hive-tmux). Core modes are baked in; addon modes
   are registered during IAddon initialize! lifecycle.

   Sum type: claude | vterm | headless | agent-sdk | <addon-contributed>
   MCP surface: claude | vterm | headless | <addon-contributed with :mcp? true>

   NOTE: hive-mcp owns ONLY abstract/generic modes here. Concrete provider-
   or implementation-specific modes (e.g. :hive-agent, :tmux) are
   contributed by addons via `register-mode!`.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private core-modes
  "Core spawn modes baked in at compile time. array-map preserves insertion order.

   hive-mcp owns ONLY abstract / generic spawn modes here. Concrete
   provider-specific or implementation-specific modes (e.g. :hive-agent,
   :tmux) are contributed by addons via `register-mode!` during their
   IAddon initialize! lifecycle. Provider keywords (:openrouter, :venice,
   …) are NEVER spawn modes — they belong to the provider/LLM-router
   layer behind the backend.

   Each mode has:
   - :description     Human-readable description
   - :requires-emacs? Whether this mode needs an Emacs daemon
   - :io-model        How I/O works (:buffer, :stdin-stdout, :api)
   - :slot-limit      Max concurrent instances (nil = unlimited)
   - :mcp?            Visible in MCP tool enums (default true)
   - :alias-of        If this mode is a static alias for another canonical
                      mode. nil = abstract; resolved dynamically via
                      headless-registry/resolve-default-backend.
   - :capabilities    Set of capability keywords this mode supports"
  (array-map
   ;; === Emacs-bound modes ===
   :claude      {:description   "Claude Code terminal — hive-claude bridge (default)"
                 :requires-emacs? true
                 :io-model      :buffer
                 :slot-limit    6
                 :mcp?          true
                 :alias-of      nil
                 :capabilities  #{:interactive :emacs-visible :dispatch :kill :interrupt}}

   :vterm       {:description   "Emacs vterm buffer — raw vterm-mcp backend"
                 :requires-emacs? true
                 :io-model      :buffer
                 :slot-limit    6
                 :mcp?          true
                 :alias-of      nil
                 :capabilities  #{:interactive :emacs-visible :dispatch :kill}}

   ;; === Subprocess / API modes ===
   :headless    {:description   "Abstract headless mode — concrete backend resolved at spawn time via headless-registry (operator config or priority-ranked addon backends)"
                 :requires-emacs? false
                 :io-model      :stdin-stdout
                 :slot-limit    nil
                 :mcp?          true
                 :alias-of      nil
                 :capabilities  #{:dispatch :kill :stdin :stdout-ring}}

   :agent-sdk   {:description   "Claude Agent SDK via subprocess"
                 :requires-emacs? false
                 :io-model      :stdin-stdout
                 :slot-limit    nil
                 :mcp?          false
                 :alias-of      nil
                 :capabilities  #{:dispatch :kill :stdin :stdout-ring :subagents}}))

;; Addon-contributed spawn modes registered at runtime via register-mode!
(defonce ^:private addon-modes (atom {}))

(defn registry
  "Full spawn mode registry: core + addon-contributed modes."
  []
  (merge core-modes @addon-modes))

;; =============================================================================
;; Derived views (dynamic — include addon-contributed modes)
;; =============================================================================

(def all-modes
  "Set of all valid spawn mode keywords (core only, for backward compat)."
  (set (keys core-modes)))

(def all-mode-strings
  "Set of all valid spawn mode strings (core only, for backward compat)."
  (set (map name all-modes)))

(def mcp-modes
  "Ordered vector of core mode strings visible in MCP tool enums."
  (->> core-modes
       (filter (fn [[_k v]] (:mcp? v)))
       (mapv (comp name key))))

(def internal-modes
  "Set of core mode keywords that are internal (not MCP-visible)."
  (->> core-modes
       (remove (fn [[_k v]] (:mcp? v)))
       (map key)
       set))

(def mode->slot-limit
  "Map of mode keyword -> slot limit (nil = unlimited). Core only."
  (into {} (map (fn [[k v]] [k (:slot-limit v)])) core-modes))

(def emacs-modes
  "Set of modes that require an Emacs daemon."
  (->> core-modes
       (filter (fn [[_k v]] (:requires-emacs? v)))
       (map key)
       set))

(def headless-modes
  "Set of modes that do NOT require Emacs (subprocess or API)."
  (->> core-modes
       (remove (fn [[_k v]] (:requires-emacs? v)))
       (map key)
       set))

(def alias-map
  "Map of alias keyword -> canonical keyword. Only entries with :alias-of."
  (->> core-modes
       (filter (fn [[_k v]] (:alias-of v)))
       (into {} (map (fn [[k v]] [k (:alias-of v)])))))

;; =============================================================================
;; Functions
;; =============================================================================

(defn valid-mode?
  "Check if mode (keyword or string) is a valid spawn mode.
   Checks both core and addon-contributed modes."
  [m]
  (let [kw (if (keyword? m) m (keyword m))]
    (or (contains? all-modes kw)
        (contains? @addon-modes kw))))

(defn resolve-alias
  "Resolve a mode to its canonical form. :headless -> :agent-sdk, others unchanged."
  [mode]
  (get alias-map mode mode))

(defn requires-emacs?
  "Does this spawn mode require an Emacs daemon?"
  [mode]
  (get-in (registry) [mode :requires-emacs?] false))

(defn slot-limit
  "Get the slot limit for a mode (nil = unlimited)."
  [mode]
  (or (get mode->slot-limit mode)
      (get-in @addon-modes [mode :slot-limit])))

(defn io-model
  "Get the I/O model for a mode (:buffer, :stdin-stdout, :api)."
  [mode]
  (get-in (registry) [mode :io-model]))

(defn capabilities
  "Get the capability set for a mode."
  [mode]
  (get-in (registry) [mode :capabilities] #{}))

(defn has-capability?
  "Check if a mode has a specific capability."
  [mode capability]
  (contains? (capabilities mode) capability))

(defn mcp-enum
  "Generate MCP JSON schema enum for spawn_mode tool definitions.
   Includes both core and addon-contributed MCP-visible modes."
  []
  (let [addon-mcp (->> @addon-modes
                       (filter (fn [[_k v]] (:mcp? v)))
                       (mapv (comp name key)))]
    (into mcp-modes addon-mcp)))

;; =============================================================================
;; Runtime Extension (for addons)
;; =============================================================================

(defn register-mode!
  "Register an addon-contributed spawn mode at runtime.
   Called during IAddon initialize! lifecycle.
   Mode-spec follows the same schema as core modes.
   Idempotent: re-registration replaces silently.

   Returns the mode keyword."
  [mode-kw mode-spec]
  {:pre [(keyword? mode-kw) (map? mode-spec)]}
  (swap! addon-modes assoc mode-kw mode-spec)
  mode-kw)

(defn deregister-mode!
  "Remove an addon-contributed spawn mode. For addon shutdown.
   No-op if mode is a core mode or not registered."
  [mode-kw]
  (swap! addon-modes dissoc mode-kw)
  mode-kw)
