(ns hive-mcp.agent.spawn-mode
  "SpawnMode ADT — closed algebraic type for agent spawn modes.

   Built on hive-dsl.adt/defadt. Provides type-safe spawn mode dispatch
   with compile-time exhaustiveness checking via adt-case.

   Variants: :claude | :vterm | :headless | :agent-sdk | :openrouter

   This module is the type-safe counterpart to spawn-mode-registry.
   - spawn-mode-registry: SST for metadata (requires-emacs?, io-model, etc.)
   - spawn-mode (this ns): ADT for type-safe dispatch + coercion + exhaustiveness

   Usage:
     (require '[hive-mcp.agent.spawn-mode :as sm])

     ;; Construct
     (sm/spawn-mode :vterm)
     ;; => {:adt/type :SpawnMode, :adt/variant :vterm}

     ;; Coerce from keyword (nil if invalid)
     (sm/->spawn-mode :headless)
     ;; => {:adt/type :SpawnMode, :adt/variant :headless}

     ;; Predicate
     (sm/spawn-mode? x) ;; => true/false

     ;; Exhaustive dispatch
     (adt-case SpawnMode mode
       :vterm      :emacs-buffer
       :headless   :subprocess
       :agent-sdk  :sdk-subprocess
       :openrouter :api-call)

     ;; Extract keyword (for backward compat)
     (sm/to-keyword mode) ;; => :vterm

     ;; Alias resolution
     (sm/resolve-alias (sm/spawn-mode :headless))
     ;; => {:adt/type :SpawnMode, :adt/variant :agent-sdk}"
  (:require [hive-dsl.adt :refer [defadt adt-variant]]
            [hive-mcp.agent.spawn-mode-registry :as registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; ADT Definition
;; =============================================================================

(defadt SpawnMode
  "Agent spawn modes — closed sum type.
   :claude     — Claude Code terminal via hive-claude bridge (default)
   :vterm      — Raw Emacs vterm buffer via vterm-mcp
   :headless   — Alias for :agent-sdk (legacy name, auto-mapped since 0.12.0)
   :agent-sdk  — Claude Agent SDK subprocess (default for headless)
   :openrouter — Direct OpenRouter API calls (multi-model, no CLI)"
  :claude
  :vterm
  :headless
  :agent-sdk
  :openrouter)

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
  "Resolve a SpawnMode to its canonical form.
   :headless => :agent-sdk, others unchanged.
   Returns a new SpawnMode ADT value.

   (resolve-alias (spawn-mode :headless))
   => {:adt/type :SpawnMode, :adt/variant :agent-sdk}"
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
  "Set of subprocess/API variants (no Emacs required)."
  #{:headless :agent-sdk :openrouter})
