(ns hive-mcp.agent.spawn-mode
  "SpawnMode ADT — closed algebraic type for agent spawn modes.

   Built on hive-dsl.adt/defadt. Provides type-safe spawn mode dispatch
   with compile-time exhaustiveness checking via adt-case.

   Variants: :claude | :vterm | :headless | :agent-sdk

   This module is the type-safe counterpart to spawn-mode-registry.
   - spawn-mode-registry: SST for metadata (requires-emacs?, io-model, etc.),
     including addon-contributed modes registered at runtime.
   - spawn-mode (this ns): ADT for type-safe dispatch + coercion +
     exhaustiveness over the abstract/legacy modes hive-mcp owns.

   Addon-contributed modes (e.g. :hive-agent from hive-agent, :tmux from
   hive-tmux) are valid runtime keywords but are NOT ADT variants. Code
   that needs to handle addon modes works on plain keywords + the
   merged registry, not on this ADT.

   Usage:
     (require '[hive-mcp.agent.spawn-mode :as sm])

     ;; Construct
     (sm/spawn-mode :vterm)
     ;; => {:adt/type :SpawnMode, :adt/variant :vterm}

     ;; Coerce from keyword (nil if not an ADT variant)
     (sm/->spawn-mode :headless)
     ;; => {:adt/type :SpawnMode, :adt/variant :headless}
     (sm/->spawn-mode :hive-agent)  ;; addon mode
     ;; => nil  (use registry/valid-mode? instead)

     ;; Predicate
     (sm/spawn-mode? x) ;; => true/false

     ;; Exhaustive dispatch over ADT variants
     (adt-case SpawnMode mode
       :claude     :emacs-claude
       :vterm      :emacs-buffer
       :headless   :abstract-headless
       :agent-sdk  :sdk-subprocess)

     ;; Extract keyword (for backward compat)
     (sm/to-keyword mode) ;; => :vterm

     ;; Alias resolution — :headless is now abstract (resolved dynamically
     ;; at spawn time via headless-registry); resolve-alias returns it
     ;; unchanged unless an explicit static alias is set in the registry.
     (sm/resolve-alias (sm/spawn-mode :headless))
     ;; => {:adt/type :SpawnMode, :adt/variant :headless}"
  (:require [hive-dsl.adt :refer [defadt adt-variant]]
            [hive-mcp.agent.spawn-mode-registry :as registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; ADT Definition
;; =============================================================================

(defadt SpawnMode
  "Agent spawn modes — closed sum type over hive-mcp's abstract/legacy modes.

   :claude    — Claude Code terminal via hive-claude bridge
   :vterm     — Raw Emacs vterm buffer via vterm-mcp
   :headless  — Abstract headless mode; concrete backend resolved at spawn
                time via headless-registry/resolve-default-backend.
   :agent-sdk — Claude Agent SDK subprocess

   Addon-contributed modes (e.g. :hive-agent, :tmux) are valid runtime
   spawn-mode keywords but are NOT ADT variants — they flow as plain
   keywords through registry-based validation."
  :claude
  :vterm
  :headless
  :agent-sdk)

;; =============================================================================
;; Keyword Coercion (backward compatibility bridge)
;; =============================================================================

(defn from-keyword
  "Coerce a keyword or string to a SpawnMode ADT value.
   Returns nil if the input is not a valid spawn mode.

   (from-keyword :vterm)     => {:adt/type :SpawnMode, :adt/variant :vterm}
   (from-keyword \"headless\") => {:adt/type :SpawnMode, :adt/variant :headless}
   (from-keyword :bogus)     => nil"
  [k]
  (let [kw (cond
             (keyword? k) k
             (string? k) (keyword k)
             :else nil)]
    (when kw (->spawn-mode kw))))

(defn to-keyword
  "Extract the variant keyword from a SpawnMode ADT value.
   This is the inverse of from-keyword for round-trip serialization.

   (to-keyword (spawn-mode :vterm)) => :vterm"
  [sm]
  (adt-variant sm))

;; =============================================================================
;; Alias Resolution
;; =============================================================================

(defn resolve-alias
  "Resolve a SpawnMode to its canonical form via the registry's static
   :alias-of metadata. Returns a new SpawnMode ADT value.

   :headless has no static alias post-cleanup — it's an abstract mode
   resolved dynamically at spawn time via
   hive-mcp.agent.ling.headless-registry/resolve-default-backend. This
   function returns :headless unchanged.

   (resolve-alias (spawn-mode :headless)) => SpawnMode :headless
   (resolve-alias (spawn-mode :vterm))    => SpawnMode :vterm"
  [sm]
  (let [canonical (registry/resolve-alias (adt-variant sm))]
    (spawn-mode canonical)))

(defn canonical?
  "True if this SpawnMode is already canonical (not an alias)."
  [sm]
  (let [kw (adt-variant sm)]
    (= kw (registry/resolve-alias kw))))

;; =============================================================================
;; Registry-Backed Metadata Access
;; =============================================================================

(defn requires-emacs?
  "Does this spawn mode require an Emacs daemon?"
  [sm]
  (registry/requires-emacs? (adt-variant sm)))

(defn io-model
  "Get the I/O model for this spawn mode: :buffer, :stdin-stdout, or :api."
  [sm]
  (registry/io-model (adt-variant sm)))

(defn slot-limit
  "Get the slot limit for this spawn mode (nil = unlimited)."
  [sm]
  (registry/slot-limit (adt-variant sm)))

(defn capabilities
  "Get the capability set for this spawn mode."
  [sm]
  (registry/capabilities (adt-variant sm)))

(defn has-capability?
  "Check if this spawn mode has a specific capability."
  [sm capability]
  (registry/has-capability? (adt-variant sm) capability))

(defn mcp-visible?
  "Is this spawn mode visible in MCP tool enums?"
  [sm]
  (get-in (registry/registry) [(adt-variant sm) :mcp?] false))

;; =============================================================================
;; Variant Sets (for iteration / validation)
;; =============================================================================

(def all-variants
  "Set of all SpawnMode variant keywords."
  (:variants SpawnMode))

(def mcp-variants
  "Set of MCP-visible variant keywords."
  (set (map keyword registry/mcp-modes)))

(def emacs-variants
  "Set of variants requiring Emacs."
  #{:claude :vterm})

(def headless-variants
  "Set of subprocess/API variants (no Emacs required) within the closed
   ADT. Addon-contributed headless modes (e.g. :hive-agent) are tracked
   in `hive-mcp.agent.spawn-mode-registry/headless-modes` at runtime."
  #{:headless :agent-sdk})
