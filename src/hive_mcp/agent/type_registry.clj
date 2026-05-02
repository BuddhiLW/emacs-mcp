(ns hive-mcp.agent.type-registry
  "Single source of truth for agent types and their properties.

   All consumers derive from this registry — no scattered enums.
   Leaf namespace: zero hive-mcp dependencies (safe to require anywhere).

   Design principle: Knowledge-Layer-First / SST (Single Source of Truth).
   Adding a new agent type = adding one entry here. All downstream
   validation, MCP schemas, depth mappings, and capabilities derive automatically.

   Sum type variants: coordinator, ling, drone.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry
;; =============================================================================

(def registry
  "Agent type registry. array-map preserves insertion order (= hierarchy depth).

   Each type has:
   - :description     Human-readable description
   - :depth           DataScript slave depth (0=coordinator, 1=ling, 2+=drone)
   - :spawn-modes     Set of statically-known supported spawn modes
                      (nil = not spawnable). Addon-contributed modes
                      (e.g. :hive-agent, :tmux) are also accepted at
                      runtime via valid-spawn-mode? consulting
                      hive-mcp.agent.spawn-mode-registry/valid-mode?.
   - :capabilities    Set of capability keywords
   - :permissions     Map of permission rules
   - :slot-limit      Max concurrent instances (nil = unlimited)
   - :model-tier      Default model cost tier (:free :economy :standard :premium)
   - :mcp?            Visible in MCP spawn tool enums (default false)
   - :can-chain?      Whether agent can chain multiple tool calls
   - :readiness       Map of readiness check config"
  (array-map
   ;; === Coordinator (depth 0) — orchestrator ===
   :coordinator {:description  "Hivemind coordinator/orchestrator — human-in-the-loop"
                 :depth        0
                 :spawn-modes  nil ;; not spawnable, always present
                 :capabilities #{:spawn :delegate :kill :review :broadcast :dag :approve-diffs}
                 :permissions  {:can-spawn?        true
                                :can-delegate?     true
                                :can-kill?         true
                                :can-broadcast?    true
                                :can-approve-diffs? true}
                 :slot-limit   1
                 :model-tier   :premium
                 :mcp?         false
                 :can-chain?   true
                 :readiness    {:requires-emacs? false}}

   ;; === Ling (depth 1) — worker agent ===
   :ling        {:description  "Worker agent spawned by coordinator"
                 :depth        1
                 :spawn-modes  #{:claude :vterm :headless :agent-sdk}
                 :capabilities #{:read :write :delegate :eval :search :commit :propose-diff}
                 :permissions  {:can-spawn?        false ;; child lings cannot spawn (anti-cascade)
                                :can-delegate?     true
                                :can-kill?         false
                                :can-broadcast?    false
                                :can-approve-diffs? false}
                 :slot-limit   6 ;; per Emacs daemon (5-6 max from operational convention)
                 :model-tier   :standard
                 :mcp?         true
                 :can-chain?   true
                 :readiness    {:requires-emacs? false}}

   ;; === Drone (depth 2+) — leaf worker ===
   :drone       {:description  "Lightweight leaf worker"
                 :depth        2
                 :spawn-modes  #{:headless}
                 :capabilities #{:read :propose-diff :search}
                 :permissions  {:can-spawn?        false
                                :can-delegate?     false
                                :can-kill?         false
                                :can-broadcast?    false
                                :can-approve-diffs? false}
                 :slot-limit   nil ;; unlimited (100-1000 scale target)
                 :model-tier   :economy
                 :mcp?         true
                 :can-chain?   false
                 :readiness    {:requires-emacs? false}}))

;; =============================================================================
;; Derived views (computed once at load time)
;; =============================================================================

(def all-types
  "Set of all valid agent type keywords."
  (set (keys registry)))

(def all-type-strings
  "Set of all valid agent type strings (for MCP/serialization)."
  (set (map name all-types)))

(def mcp-types
  "Ordered vector of type strings visible in MCP spawn tool enums."
  (->> registry
       (filter (fn [[_k v]] (:mcp? v)))
       (mapv (comp name key))))

(def type->depth
  "Map of agent type keyword -> DataScript depth."
  (into {} (map (fn [[k v]] [k (:depth v)])) registry))

(def depth->type
  "Map of DataScript depth -> agent type keyword.
   For depth >= 2, defaults to :drone."
  (into {} (map (fn [[k v]] [(:depth v) k])) registry))

(def type->capabilities
  "Map of agent type keyword -> capability set."
  (into {} (map (fn [[k v]] [k (:capabilities v)])) registry))

(def type->permissions
  "Map of agent type keyword -> permission map."
  (into {} (map (fn [[k v]] [k (:permissions v)])) registry))

(def type->spawn-modes
  "Map of agent type keyword -> set of supported spawn modes (nil if not spawnable)."
  (into {} (map (fn [[k v]] [k (:spawn-modes v)])) registry))

(def type->model-tier
  "Map of agent type keyword -> default model cost tier."
  (into {} (map (fn [[k v]] [k (:model-tier v)])) registry))

(def type->slot-limit
  "Map of agent type keyword -> max concurrent instances (nil = unlimited)."
  (into {} (map (fn [[k v]] [k (:slot-limit v)])) registry))

;; =============================================================================
;; Functions
;; =============================================================================

(defn valid-type?
  "Check if type (keyword or string) is a valid agent type."
  [t]
  (let [kw (cond
             (keyword? t) t
             (string? t)  (keyword t)
             :else        nil)]
    (contains? all-types kw)))

(defn type-depth
  "Get the DataScript depth for an agent type. Default: 2 (drone)."
  [t]
  (let [kw (if (keyword? t) t (keyword t))]
    (get type->depth kw 2)))

(defn depth->agent-type
  "Resolve DataScript depth to agent type keyword.
   Depth 0=coordinator, 1=ling, 2+=drone."
  [depth]
  (or (get depth->type depth)
      :drone))

(defn spawnable?
  "Check if an agent type can be spawned (has spawn-modes)."
  [agent-type]
  (some? (get type->spawn-modes (keyword agent-type))))

(defn valid-spawn-mode?
  "Check if spawn-mode is valid for the given agent type.

   Two acceptance paths:
   1. Static membership: mode is declared in the type's :spawn-modes set
      (the per-type allow-list). E.g., :ling accepts :claude, :vterm, …
   2. Addon-contributed mode: mode is registered via
      hive-mcp.agent.spawn-mode-registry/register-mode! AND is NOT
      claimed by any OTHER type's static set. Addon modes are global —
      they're not pinned to one type — but a mode statically owned by
      :ling (e.g., :claude) does not implicitly become valid for :drone.

   Coordinator stays restricted (its :spawn-modes is nil → not spawnable).

   The requiring-resolve keeps this ns dependency-free of
   spawn-mode-registry."
  [agent-type spawn-mode]
  (let [type-kw (keyword agent-type)
        mode-kw (keyword spawn-mode)
        modes   (get type->spawn-modes type-kw)
        static-set-of-mode (some (fn [s] (when (and s (contains? s mode-kw)) s))
                                 (vals type->spawn-modes))]
    (boolean
     (and modes
          (or (contains? modes mode-kw)
              (and (nil? static-set-of-mode)
                   (when-let [addon-valid? (requiring-resolve
                                            'hive-mcp.agent.spawn-mode-registry/valid-mode?)]
                     (addon-valid? mode-kw))))))))

(defn has-capability?
  "Check if an agent type has a specific capability."
  [agent-type capability]
  (let [caps (get type->capabilities (keyword agent-type))]
    (boolean (and caps (contains? caps (keyword capability))))))

(defn has-permission?
  "Check if an agent type has a specific permission."
  [agent-type permission-key]
  (get (get type->permissions (keyword agent-type)) permission-key false))

(defn can-chain-tools?
  "Check if an agent type can chain multiple tool calls."
  [agent-type]
  (get-in registry [(keyword agent-type) :can-chain?] false))

(defn slot-limit
  "Get the slot limit for an agent type. nil = unlimited."
  [agent-type]
  (get type->slot-limit (keyword agent-type)))

(defn default-model-tier
  "Get the default model cost tier for an agent type."
  [agent-type]
  (get type->model-tier (keyword agent-type) :standard))

(defn mcp-enum
  "Generate MCP JSON schema enum for agent type in tool definitions."
  []
  mcp-types)

(defn describe
  "Get the description for an agent type."
  [agent-type]
  (get-in registry [(keyword agent-type) :description]))
