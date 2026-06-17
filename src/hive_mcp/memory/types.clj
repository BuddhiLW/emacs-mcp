(ns hive-mcp.memory.types
  "MemoryType ADT — closed sum type for memory entry classification.

   Defines the canonical set of memory types used across hive-mcp.
   Wraps the existing type-registry SST with compile-time type safety
   via `defadt` from hive-dsl.

   ## Relationship to type-registry
   `hive-mcp.memory.type-registry` remains the SST for metadata (abstraction
   levels, duration defaults, catchup configs). This namespace provides:
   - Closed variant set (defadt — no new types without modifying this ns)
   - Type-safe dispatch via `adt-case` (compile-time exhaustiveness)
   - Coercion functions for string/keyword boundaries

   ## Coercion at Boundaries
   Chroma stores types as strings (\"axiom\"). MCP params arrive as strings.
   Internal Clojure code uses keywords. The ADT bridges all three:

     (from-string \"axiom\")     => {:adt/type :MemoryType :adt/variant :axiom}
     (->memory-type :axiom)     => {:adt/type :MemoryType :adt/variant :axiom}
     (variant->string mt)       => \"axiom\"
     (variant->keyword mt)      => :axiom"
  (:require [hive-dsl.adt :refer [defadt]]
            [hive-mcp.memory.type-registry :as type-registry]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defadt MemoryType
  "Memory entry type — the ADVISORY set of well-known classifications.

   No longer a *closed* validation authority: unknown-but-safe type tokens are
   accepted at the boundary (see hive-mcp.memory.type-registry/valid-type? +
   ensure-type!) and auto-registered with sane defaults. This variant set
   documents the curated/known types and powers typed dispatch + the subset
   sets below; it does NOT gate which types may be stored.

   Core types: visible in MCP tool enums. Extended/ingestion types: stored,
   not first-class in MCP. Synced with the type-registry SST."
  :axiom :principle :decision :convention :snippet :note :plan
  :knowledge :ingestion
  :doc :todo :question :answer :warning :error
  :pattern :lesson :rule :guideline :workflow :recipe)

;; =============================================================================
;; String/Keyword Coercion (system boundary helpers)
;; =============================================================================

(defn from-string
  "Coerce a string to a MemoryType ADT value. Returns nil if invalid."
  [s]
  (when (string? s)
    (->memory-type (keyword s))))

(defn valid?
  "Permissive: true when `x` is a SAFE memory type token (any sanitized token
   matching the safe charset/length), delegating to the registry SST gate. The
   defadt variant set below is advisory documentation, not the validation
   authority — unknown-but-safe types are valid and auto-registered on use."
  [x]
  (type-registry/valid-type? x))

(defn variant->keyword
  "Extract the variant keyword from a MemoryType ADT value."
  [mt]
  (:adt/variant mt))

(defn variant->string
  "Extract the variant name as a string from a MemoryType ADT value."
  [mt]
  (some-> (:adt/variant mt) name))

;; =============================================================================
;; Type Subsets (for common dispatch patterns)
;; =============================================================================

(def core-types #{:axiom :principle :decision :convention :snippet :note :plan})
(def intent-types #{:axiom :principle :decision :plan})
(def pattern-types #{:convention :pattern :lesson :rule :guideline :workflow :recipe})
(def semantic-types #{:snippet :note :doc :todo :question :answer :warning :error})
(def scope-piercing-types #{:axiom})
(def high-abstraction-routing-types #{:plan})
(def promotion-worthy-types #{:axiom :decision})

(def all-type-keywords (:variants MemoryType))
(def all-type-strings (set (map name all-type-keywords)))
