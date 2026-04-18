(ns hive-mcp.schema.memory
  "Malli schemas for memory entries and related types."

  (:require [malli.core :as m]
            [hive-mcp.memory.type-registry :as type-registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Enums
;; =============================================================================

(def MemoryType
  "Valid memory entry types. Derived from type-registry (SST).
   Note: captures core types at load time. Extension types are validated
   dynamically via type-registry/valid-type? at runtime."
  (into [:enum] (type-registry/all-type-strings)))

(def MemoryDuration
  "Valid duration values for memory entries.
   Aligned with graph/schema.clj duration-types (SST)."
  [:enum "ephemeral" "short" "medium" "long" "permanent"])

;; =============================================================================
;; Tags
;; =============================================================================

(def MemoryTag
  "A single tag - non-empty string."
  [:string {:min 1}])

(def MemoryTags
  "Vector of tags - may be empty."
  [:vector MemoryTag])

;; =============================================================================
;; Scope
;; =============================================================================

(def ProjectScope
  "Project scope identifier - non-empty string like 'scope:hive-mcp'."
  [:and :string [:fn #(or (= % "scope:global")
                          (re-matches #"scope:[a-zA-Z0-9_-]+" %))]])

;; =============================================================================
;; Abstraction Level
;; =============================================================================

(def AbstractionLevel
  "Knowledge abstraction level (1-4)."
  [:int {:min 1 :max 4}])

;; =============================================================================
;; Memory Entry
;; =============================================================================

(def MemoryEntryId
  "Memory entry ID - timestamp-based identifier."
  [:string {:min 1}])

(def MemoryEntry
  "Complete memory entry schema."
  [:map
   [:id MemoryEntryId]
   [:type MemoryType]
   [:content :string]
   [:tags {:optional true} MemoryTags]
   [:duration {:optional true} MemoryDuration]
   [:project-id {:optional true} [:maybe :string]]
   [:created-at {:optional true} [:maybe inst?]]
   [:expires {:optional true} [:maybe :string]]
   [:content-hash {:optional true} [:maybe :string]]
   [:abstraction-level {:optional true} [:maybe AbstractionLevel]]
   [:kg-outgoing {:optional true} [:maybe [:vector :string]]]
   [:kg-incoming {:optional true} [:maybe [:vector :string]]]])

(def MemoryEntryMinimal
  "Minimal memory entry for creation - only required fields."
  [:map
   [:type MemoryType]
   [:content [:string {:min 1}]]])

(def MemoryMetadata
  "Memory entry metadata for lightweight queries."
  [:map
   [:id MemoryEntryId]
   [:type MemoryType]
   [:preview {:optional true} [:maybe :string]]
   [:tags {:optional true} MemoryTags]
   [:created {:optional true} [:maybe :string]]])

;; =============================================================================
;; Query Results
;; =============================================================================

(def MemoryQueryResult
  "Result from memory query - vector of entries."
  [:vector MemoryEntry])

(def MemoryMetadataResult
  "Result from metadata query - vector of metadata records."
  [:vector MemoryMetadata])

;; =============================================================================
;; Validators
;; =============================================================================

(defn valid-type?
  "Check if type string is a valid MemoryType."
  [type-str]
  (m/validate MemoryType type-str))

(defn valid-duration?
  "Check if duration string is a valid MemoryDuration."
  [duration-str]
  (m/validate MemoryDuration duration-str))

(defn valid-entry?
  "Check if entry map is a valid MemoryEntry."
  [entry]
  (m/validate MemoryEntry entry))

(defn explain-entry
  "Explain validation errors for a MemoryEntry."
  [entry]
  (m/explain MemoryEntry entry))

;; =============================================================================
;; Schema Registry Entry
;; =============================================================================

(def registry
  "Schema registry entries for memory types."
  {:memory/type MemoryType
   :memory/duration MemoryDuration
   :memory/tag MemoryTag
   :memory/tags MemoryTags
   :memory/entry MemoryEntry
   :memory/entry-minimal MemoryEntryMinimal
   :memory/metadata MemoryMetadata
   :memory/abstraction-level AbstractionLevel
   :memory/project-scope ProjectScope})

;; =============================================================================
;; Boundary Validation
;; =============================================================================

(defn validate-add-request
  "Validate an add-entry request at the IO boundary. Returns nil on success,
   or a map {:errors [...]} with humanized Malli errors on failure.
   Designed as opt-in guard for callers of vectordb.facade/index-memory-entry!."
  [entry]
  (let [explanation (m/explain MemoryEntryMinimal entry)]
    (when explanation
      {:errors (mapv (fn [{:keys [path value]}]
                       {:path path :value value})
                     (:errors explanation))})))

(comment
  ;; Example usage

  (m/validate MemoryType "decision")
  ;; => true

  (m/validate MemoryDuration "long")
  ;; => true

  (m/validate MemoryEntry
              {:id "20260131-abc123"
               :type "decision"
               :content "Use Malli for all schema validation"
               :tags ["architecture" "tooling"]
               :duration "long"})
  ;; => true

  (m/validate MemoryEntryMinimal
              {:type "snippet"
               :content "(defn hello [] \"world\")"})
  ;; => true

  (m/explain MemoryEntry {:type "invalid" :content "test"})
  ;; => {:errors [...]}
  )
