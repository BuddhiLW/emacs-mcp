(ns hive-mcp.tools.memory
  "MCP tool handlers for memory operations.

   Chroma-only storage: All memory is stored in Chroma vector database.
   Elisp is only used for getting current project context (not storage).

   Architecture (Facade Pattern - SOLID/DDD):
   - This namespace is a thin facade over extracted submodules
   - Each submodule has a single responsibility (SRP)
   - Handlers are organized by operation category:
     * crud.clj: add, query, get, check-duplicate
     * search.clj: semantic vector search
     * lifecycle.clj: duration, promote, demote, cleanup, expiring
     * analytics.clj: access tracking, feedback
     * migration.clj: project migration, JSON import

   Supporting modules:
   - core.clj: with-store, with-entry macros (error handling)
   - scope.clj: project scope utilities
   - format.clj: JSON formatting utilities
   - duration.clj: duration constants and calculations"
  (:require [hive-mcp.tools.memory.crud :as crud]
            [hive-mcp.tools.memory.search :as search]
            [hive-mcp.tools.memory.lifecycle :as lifecycle]
            [hive-mcp.tools.memory.analytics :as analytics]
            [hive-mcp.tools.memory.migration :as migration]
            [hive-mcp.memory.type-registry :as type-registry]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ============================================================
;; Handler Re-exports (Facade)
;; ============================================================

;; CRUD Operations
(def handle-mcp-memory-add crud/handle-add)
(def handle-mcp-memory-query crud/handle-query)
(def handle-mcp-memory-query-metadata crud/handle-query-metadata)
(def handle-mcp-memory-get-full crud/handle-get-full)
(def handle-mcp-memory-batch-get crud/handle-batch-get)
(def handle-mcp-memory-check-duplicate crud/handle-check-duplicate)
(def handle-mcp-memory-update-tags crud/handle-update-tags)

;; Search Operations
(def handle-mcp-memory-search-semantic search/handle-search-semantic)

;; Lifecycle Operations
(def handle-mcp-memory-set-duration lifecycle/handle-set-duration)
(def handle-mcp-memory-promote lifecycle/handle-promote)
(def handle-mcp-memory-demote lifecycle/handle-demote)
(def handle-mcp-memory-cleanup-expired lifecycle/handle-cleanup-expired)
(def handle-mcp-memory-expiring-soon lifecycle/handle-expiring-soon)
(def handle-mcp-memory-expire lifecycle/handle-expire)
(def handle-mcp-memory-decay lifecycle/handle-decay)
(def handle-mcp-memory-xpoll-promote lifecycle/handle-xpoll-promote)

;; Analytics Operations
(def handle-mcp-memory-log-access analytics/handle-log-access)
(def handle-mcp-memory-feedback analytics/handle-feedback)
(def handle-mcp-memory-helpfulness-ratio analytics/handle-helpfulness-ratio)

;; Migration Operations
(def handle-mcp-memory-migrate-project migration/handle-migrate-project)
(def handle-mcp-memory-migrate-scoped migration/handle-migrate-scoped)
(def handle-mcp-memory-import-json migration/handle-import-json)
(def handle-mcp-memory-rename-project migration/handle-rename-project)

;; ============================================================
;; Tool Definitions
;; ============================================================

(def tools
  [{:name "mcp_memory_add"
    :description "Add an entry to project memory (Chroma storage). Types: note, snippet, convention, decision, axiom, plan. Optionally specify duration for TTL: ephemeral (1 day), short (7 days), medium (30 days), long (90 days), permanent (never expires). For ling attribution, pass agent_id to tag entry with agent:<id>. Knowledge Graph: Use kg_* params to create edges linking this entry to existing entries (implements, supersedes, depends-on, refines). Plans (type=plan) are stored in a separate collection with OpenRouter embeddings for large content support."
    :inputSchema {:type "object"
                  :properties {"type" {:type "string"
                                       :enum (type-registry/mcp-enum)
                                       :description "Type of memory entry. Use 'principle' for architectural design principles. Use 'plan' for large implementation plans (1000-5000+ chars) - stored in dedicated collection with OpenRouter embeddings."}
                               "content" {:type "string"
                                          :description "Content of the memory entry"}
                               "tags" {:type "array"
                                       :items {:type "string"}
                                       :description "Optional tags for categorization"}
                               "duration" {:type "string"
                                           :enum ["ephemeral" "short" "medium" "long" "permanent"]
                                           :description "Duration/TTL category (default: long)"}
                               "directory" {:type "string"
                                            :description "Working directory to determine project scope (pass your cwd to ensure correct scoping)"}
                               "agent_id" {:type "string"
                                           :description "Agent identifier for attribution. Lings should pass CLAUDE_SWARM_SLAVE_ID. Entry will be tagged with agent:<id> for tracking which agent created it."}
                               "kg_implements" {:type "array"
                                                :items {:type "string"}
                                                :description "List of entry IDs this implements. Creates :implements edges in Knowledge Graph."}
                               "kg_supersedes" {:type "array"
                                                :items {:type "string"}
                                                :description "List of entry IDs this supersedes (replaces). Creates :supersedes edges in Knowledge Graph."}
                               "kg_depends_on" {:type "array"
                                                :items {:type "string"}
                                                :description "List of entry IDs this depends on. Creates :depends-on edges in Knowledge Graph."}
                               "kg_refines" {:type "array"
                                             :items {:type "string"}
                                             :description "List of entry IDs this refines (improves without replacing). Creates :refines edges in Knowledge Graph."}
                               "abstraction_level" {:type "integer"
                                                    :minimum 1
                                                    :maximum 4
                                                    :description "Abstraction level (1=Disc/files, 2=Semantic/behavior, 3=Pattern/conventions, 4=Intent/ADRs). Auto-derived from type if not specified: axiom/decision→4, convention→3, snippet/note→2."}}
                  :required ["type" "content"]}
    :handler handle-mcp-memory-add}

   {:name "mcp_memory_query"
    :description "Query project memory by type with scope filtering (Chroma storage). Returns stored notes, snippets, conventions, decisions, axioms, or plans filtered by scope (auto-filters by current project + global unless specified). HCR Wave 4: Use include_descendants=true to also see child project memories."
    :inputSchema {:type "object"
                  :properties {"type" {:type "string"
                                       :enum (type-registry/mcp-enum {:include-conversation? true})
                                       :description "Type of memory entries to query"}
                               "tags" {:type "array"
                                       :items {:type "string"}
                                       :description "Optional tags to filter by"}
                               "limit" {:type "integer"
                                        :description "Maximum number of results (default: 20)"}
                               "duration" {:type "string"
                                           :enum ["ephemeral" "short" "medium" "long" "permanent"]
                                           :description "Filter by duration category"}
                               "scope" {:type "string"
                                        :description "Scope filter: nil=auto (project+global), 'all'=no filter, 'global'=only global, or specific scope tag"}
                               "directory" {:type "string"
                                            :description "Working directory to determine project scope (pass your cwd to ensure correct scoping)"}
                               "include_descendants" {:type "boolean"
                                                      :description "HCR Wave 4: Include child project memories in results (default: false). Use for coordinator-level queries that need visibility into sub-project memories."}}
                  :required ["type"]}
    :handler handle-mcp-memory-query}

   {:name "mcp_memory_query_metadata"
    :description "Query project memory by type, returning only metadata (id, type, preview, tags, created). Use this for efficient browsing - returns ~10x fewer tokens than full query. Follow up with mcp_memory_get_full to fetch specific entries."
    :inputSchema {:type "object"
                  :properties {"type" {:type "string"
                                       :enum (type-registry/mcp-enum {:include-conversation? true})
                                       :description "Type of memory entries to query"}
                               "tags" {:type "array"
                                       :items {:type "string"}
                                       :description "Optional tags to filter by"}
                               "limit" {:type "integer"
                                        :description "Maximum number of results (default: 20)"}
                               "scope" {:type "string"
                                        :description "Scope filter: nil=auto (project+global), 'all'=no filter, 'global'=only global, or specific scope tag"}
                               "directory" {:type "string"
                                            :description "Working directory to determine project scope (pass your cwd to ensure correct scoping)"}}
                  :required ["type"]}
    :handler handle-mcp-memory-query-metadata}

   {:name "mcp_memory_get_full"
    :description "Get full content of a memory entry by ID. Use after mcp_memory_query_metadata to fetch specific entries when you need the full content. Includes Knowledge Graph edges: kg_outgoing (edges from this entry) and kg_incoming (edges to this entry) when present."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "ID of the memory entry to retrieve"}}
                  :required ["id"]}
    :handler handle-mcp-memory-get-full}

   {:name "mcp_memory_update_tags"
    :description "Update tags on an existing memory entry. Replaces existing tags with the new tags list. Use this to add catchup-priority or other tags without recreating entries."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "ID of the memory entry to update"}
                               "tags" {:type "array"
                                       :items {:type "string"}
                                       :description "New tags list (replaces existing tags)"}}
                  :required ["id" "tags"]}
    :handler handle-mcp-memory-update-tags}

   {:name "mcp_memory_search_semantic"
    :description "Search project memory using semantic similarity (vector search). Finds conceptually related entries even without exact keyword matches. Requires Chroma to be configured with an embedding provider."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Natural language query to search for semantically similar memory entries"}
                               "limit" {:type "integer"
                                        :description "Maximum number of results to return (default: 10)"}
                               "type" {:type "string"
                                       :enum (type-registry/mcp-enum)
                                       :description "Optional filter by memory type. Use 'plan' to search only plan entries (stored in dedicated collection)."}
                               "directory" {:type "string"
                                            :description "Working directory to determine project scope (pass your cwd to ensure correct scoping)"}
                               "scope" {:type "string"
                                        :description "Scope filter: nil=auto (project+global), 'all'=no filter, 'global'=only global, or specific scope tag"}
                               "include_descendants" {:type "boolean"
                                                      :description "Include child project memories in results (HCR Wave 4, default: false)"}}
                  :required ["query"]}
    :handler handle-mcp-memory-search-semantic}

   {:name "mcp_memory_set_duration"
    :description "Set the duration/TTL category for a memory entry. Use to manually adjust how long a memory persists."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "ID of the memory entry"}
                               "duration" {:type "string"
                                           :enum ["ephemeral" "short" "medium" "long" "permanent"]
                                           :description "New duration category"}}
                  :required ["id" "duration"]}
    :handler handle-mcp-memory-set-duration}

   {:name "mcp_memory_promote"
    :description "Promote a memory entry to a longer duration category. Moves ephemeral->short->medium->long->permanent."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "ID of the memory entry to promote"}}
                  :required ["id"]}
    :handler handle-mcp-memory-promote}

   {:name "mcp_memory_demote"
    :description "Demote a memory entry to a shorter duration category. Moves permanent->long->medium->short->ephemeral."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "ID of the memory entry to demote"}}
                  :required ["id"]}
    :handler handle-mcp-memory-demote}

   {:name "mcp_memory_cleanup_expired"
    :description "Remove all expired memory entries. Call periodically to clean up old ephemeral and short-lived memories."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-memory-cleanup-expired}

   {:name "mcp_memory_expiring_soon"
    :description "List memory entries expiring within the specified number of days. Useful for reviewing and promoting important memories before they expire."
    :inputSchema {:type "object"
                  :properties {"days" {:type "integer"
                                       :description "Number of days to look ahead (default: 7)"}}
                  :required []}
    :handler handle-mcp-memory-expiring-soon}

   {:name "mcp_memory_log_access"
    :description "Log access to a memory entry. Increments access-count and updates last-accessed timestamp. Use when retrieving a memory to track usage patterns."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "ID of the memory entry accessed"}}
                  :required ["id"]}
    :handler handle-mcp-memory-log-access}

   {:name "mcp_memory_feedback"
    :description "Submit helpfulness feedback for a memory entry. Tracks whether memories are useful for future retrieval decisions."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "ID of the memory entry"}
                               "feedback" {:type "string"
                                           :enum ["helpful" "unhelpful"]
                                           :description "Whether the memory was helpful"}}
                  :required ["id" "feedback"]}
    :handler handle-mcp-memory-feedback}

   {:name "mcp_memory_helpfulness_ratio"
    :description "Get the helpfulness ratio for a memory entry. Returns helpful/(helpful+unhelpful) or null if no feedback has been submitted."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "ID of the memory entry"}}
                  :required ["id"]}
    :handler handle-mcp-memory-helpfulness-ratio}

   {:name "mcp_memory_migrate_project"
    :description "Migrate memory from one project-id to another. Use after renaming a project directory or moving to stable .hive-project.edn based IDs. Optionally updates scope tags in entries."
    :inputSchema {:type "object"
                  :properties {"old-project-id" {:type "string"
                                                 :description "Source project ID (e.g., SHA hash or old name)"}
                               "new-project-id" {:type "string"
                                                 :description "Target project ID (e.g., stable ID from .hive-project.edn)"}
                               "update-scopes" {:type "boolean"
                                                :description "If true, update scope:project:* tags in migrated entries"}}
                  :required ["old-project-id" "new-project-id"]}
    :handler handle-mcp-memory-migrate-project}

   {:name "mcp_memory_import_json"
    :description "Import memory entries from elisp JSON storage to Chroma. Use for one-time migration from the old JSON-based storage. Set dry-run to preview without importing."
    :inputSchema {:type "object"
                  :properties {"project-id" {:type "string"
                                             :description "Project ID to import (defaults to current project)"}
                               "dry-run" {:type "boolean"
                                          :description "If true, only show what would be imported without actually importing"}}
                  :required []}
    :handler handle-mcp-memory-import-json}])
