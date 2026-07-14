(ns hive-mcp.tools.consolidated.memory
  "Consolidated memory tool — core: memory CRUD + kg + migration.

   Memory's own commands stay flat (add, query, search, etc.).
   Core subdomains use nested prefixes: 'kg edge', 'migration backup'.
   Addons extend via contribute-commands! \"memory\" (OCP)."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler make-batch-handler]]
            [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.memory :as mem]
            [hive-mcp.memory.type-registry :as type-registry]
            [hive-mcp.events.core :as ev]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- dispatch-memory-read
  "Dispatch a memory read event and extract :mcp-response.
   Falls back to direct call if event system not initialized."
  [event-id params]
  (if (ev/handler-registered? event-id)
    (get-in (ev/dispatch-sync [event-id params]) [:effects :mcp-response])
    ;; Fallback for early startup before event handlers are registered
    (case event-id
      :memory/query  (mem/handle-mcp-memory-query params)
      :memory/search (mem/handle-mcp-memory-search-semantic params)
      :memory/get    (mem/handle-mcp-memory-get-full params)
      :memory/get-metadata (mem/handle-mcp-memory-get-metadata params))))

(def handlers
  {:add         mem/handle-mcp-memory-add
   :query       (fn [params] (dispatch-memory-read :memory/query params))
   ;; `metadata` is an alias onto the QUERY path, which cannot consume an :id
   ;; (handle-query's destructuring drops it) — so an id-bearing call must be
   ;; routed to the by-id RETRIEVAL path instead, or it degrades into an
   ;; unfiltered in-scope scan that answers with unrelated entries.
   :metadata    (fn [{:keys [id] :as params}]
                  (if (some? id)
                    (dispatch-memory-read :memory/get-metadata params)
                    (dispatch-memory-read :memory/query
                                          (assoc params :verbosity "metadata"))))
   :get         (fn [params] (dispatch-memory-read :memory/get params))
   :search      (fn [params] (dispatch-memory-read :memory/search params))
   :duration    mem/handle-mcp-memory-set-duration
   :promote     mem/handle-mcp-memory-promote
   :demote      mem/handle-mcp-memory-demote
   :log_access  mem/handle-mcp-memory-log-access
   :feedback    mem/handle-mcp-memory-feedback
   :helpfulness mem/handle-mcp-memory-helpfulness-ratio
   :tags        mem/handle-mcp-memory-update-tags
   :cleanup     mem/handle-mcp-memory-cleanup-expired
   :expiring    mem/handle-mcp-memory-expiring-soon
   :expire      mem/handle-mcp-memory-expire
   :migrate     mem/handle-mcp-memory-migrate-project
   :migrate-scoped mem/handle-mcp-memory-migrate-scoped
   :import      mem/handle-mcp-memory-import-json
   :decay             mem/handle-mcp-memory-decay
   :xpoll             mem/handle-mcp-memory-xpoll-promote
   :rename            mem/handle-mcp-memory-rename-project
   :batch-get         mem/handle-mcp-memory-batch-get
   :edit              mem/handle-mcp-memory-edit
   :batch-edit        mem/handle-mcp-memory-batch-edit
   :reembed           mem/handle-mcp-memory-reembed
   :batch-reembed     mem/handle-mcp-memory-batch-reembed})

(defn- make-single-command-batch
  "Wrap make-batch-handler for batch ops targeting one command."
  [cmd-kw handler-fn]
  (let [batch-fn (make-batch-handler {cmd-kw handler-fn})]
    (fn [{:keys [operations] :as params}]
      (batch-fn (assoc params :operations
                       (mapv #(assoc % :command (name cmd-kw)) operations))))))

;; =============================================================================
;; Canonical Handlers — memory flat + kg/migration nested (core-owned)
;; Addon subdomains (ingest, enrich) injected via contribute-commands! "memory"
;; =============================================================================

(def canonical-handlers
  (merge handlers
         {:batch-add      (make-single-command-batch :add (:add handlers))
          :batch-feedback (make-single-command-batch :feedback (:feedback handlers))
          ;; PR4.3 — additional batch siblings via the same iterator pattern.
          ;; A real single-store-call optimization for these is PR5+ work.
          :batch-tags     (make-single-command-batch :tags (:tags handlers))
          :batch-duration (make-single-command-batch :duration (:duration handlers))
          :batch-promote  (make-single-command-batch :promote (:promote handlers))
          :batch-demote   (make-single-command-batch :demote (:demote handlers))
          ;; Nested subdomains resolved lazily via composite/lazy-resolve-handlers
          ;; — drops the static `c-kg`/`c-mig` :requires that previously
          ;; coupled this ns to those consolidators (DIP).
          :kg         (composite/lazy-resolve-handlers 'hive-mcp.tools.consolidated.kg/handlers)
          :migration  (composite/lazy-resolve-handlers 'hive-mcp.tools.consolidated.migration/handlers)}))

(def write-commands
  "Memory commands that default to :async true."
  #{:add :batch-add
    :duration :promote :demote
    :log_access :feedback :helpfulness :tags
    :cleanup :expire :decay :xpoll
    :migrate :migrate-scoped :import :rename
    :batch-feedback :batch-tags :batch-duration
    :batch-promote :batch-demote
    :edit :batch-edit
    :reembed :batch-reembed})

(def handle-memory
  (composite/build-merged-handler "memory" canonical-handlers))

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tool-def
  {:name "memory"
   :consolidated true
   :default-async-commands write-commands
   :description "Consolidated memory operations. Commands: add, query, metadata, get, search, duration, promote, demote, log_access, feedback, helpfulness, tags, cleanup, expiring, expire, migrate, migrate-scoped, import, decay, xpoll, rename, edit, reembed, batch-add, batch-edit, batch-feedback, batch-get, batch-reembed. Use 'query' only for structured filters (type/tags/scope/duration); use 'search' for natural-language semantic retrieval. Use 'help' command to list all.\n\nEdit: 'edit' mutates an entry in place (id preserved → KG edges preserved). Params: id (required), type, content, tags, duration, abstraction_level, reason. Content change triggers re-embed. 'batch-edit' takes operations:[...] and optional dry-run:true for validation preview.\n\nReembed: 'reembed' re-vectorizes an entry without rewriting content (id required). Use after embedding-model swaps, vector-index rebuilds, or stale-vector recovery. Preserves id, content, tags, edges, duration, abstraction-level, project-id. 'batch-reembed' takes ids:[...] for sequential per-op processing.\n\nWrites default to async: they return {:queued true :task-id ...} immediately and deliver the real result via ---TOOLRESULT--- on the caller's next tool call. Pass async:false to force sync. Reads (query/metadata/get/search/expiring/batch-get) stay synchronous by default; pass async:true to queue them."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :description "Command to execute. Memory commands are flat (add, query, etc.). Subdomains: 'kg edge', 'kg traverse', 'migration backup', 'ingest file', 'enrich enrich'. Use command='help' to list all."}
                              "type" {:type "string"
                                      :description (str "[add/query] Type of memory entry. "
                                                        (type-registry/mcp-type-hint))}
                              "content" {:type "string"
                                         :description "[add] Content of the memory entry"}
                              "tags" {:type "array"
                                      :items {:type "string"}
                                      :description "[add/query/tags] Tags for categorization"}
                              "duration" {:type "string"
                                          :enum ["ephemeral" "short" "medium" "long" "permanent"]
                                          :description "[add/query] Duration/TTL category"}
                              "directory" {:type "string"
                                           :description "[add/query/search] Working directory for project scope"}
                              "agent_id" {:type "string"
                                          :description "[add] Agent identifier for attribution"}
                              "kg_implements" {:type "array"
                                               :items {:type "string"}
                                               :description "[add] Entry IDs this implements (KG edge)"}
                              "kg_supersedes" {:type "array"
                                               :items {:type "string"}
                                               :description "[add] Entry IDs this supersedes (KG edge)"}
                              "kg_depends_on" {:type "array"
                                               :items {:type "string"}
                                               :description "[add] Entry IDs this depends on (KG edge)"}
                              "kg_refines" {:type "array"
                                            :items {:type "string"}
                                            :description "[add] Entry IDs this refines (KG edge)"}
                              "abstraction_level" {:type "integer"
                                                   :minimum 1
                                                   :maximum 4
                                                   :description "[add] Abstraction level 1-4"}
                              "limit" {:type "integer"
                                       :description "[query/search/expiring] Maximum number of results"}
                              "scope" {:type "string"
                                       :description "[query/search] Scope filter: nil=auto, 'all', 'global', or specific"}
                              "verbosity" {:type "string"
                                           :enum ["full" "metadata"]
                                           :description "[query] Output detail: 'metadata' (default) or 'full'"}
                              "id" {:type "string"
                                    :description "[get/metadata/promote/demote/feedback/tags/reembed] Memory entry ID. On 'metadata' it is a by-id lookup: returns that one entry in metadata shape, or an error if it does not exist."}
                              "ids" {:type "array"
                                     :items {:type "string"}
                                     :description "[batch-get/batch-reembed] Array of memory entry IDs"}
                              "query" {:type "string"
                                       :description "[search only] Natural-language semantic query. command=query rejects this field; structured query uses type/tags/scope/duration."}
                              "exclude_tags" {:type "array"
                                              :items {:type "string"}
                                              :description "[query/search] Tags to exclude from results"}
                              "created_after" {:type "string"
                                               :description "[query] ISO-8601 threshold; only entries with :created strictly after survive. Counts as a filter on its own (since-timestamp browsing)."}
                              "updated_after" {:type "string"
                                               :description "[query] ISO-8601 threshold; only entries with :updated strictly after survive."}
                              "feedback" {:type "string"
                                          :enum ["helpful" "unhelpful"]
                                          :description "[feedback] Helpfulness rating"}
                              "days" {:type "integer"
                                      :description "[expiring] Days to look ahead (default: 7)"}
                              "include_descendants" {:type "boolean"
                                                     :description "[query/search] Include child project memories (HCR). Default true — pass false to restrict to current project only."}
                              "force" {:type "boolean"
                                       :description "[reembed] Force re-embed even if content-hash unchanged (currently no-op; reserved)"}
                              ;; KG params
                              "from" {:type "string" :description "[kg edge] Source node ID"}
                              "to" {:type "string" :description "[kg edge] Target node ID"}
                              "relation" {:type "string"
                                          :enum ["implements" "supersedes" "refines" "contradicts" "depends-on" "derived-from" "applies-to" "relates"]
                                          :description (str "[kg edge] Relation type. `relates` is the OPEN relation: pair it with `predicate` "
                                                            "to express arbitrary semantics instead of forcing a structural type. "
                                                            "Relations advertised beyond the core set (e.g. co-accessed, projects-to, contains) are "
                                                            "system-generated — do not hand-author them. "
                                                            "Server validates against kg-schema/relation-types; addons may register more.")}
                              "node_id" {:type "string" :description "[kg] Node ID for analysis"}
                              "start_node" {:type "string" :description "[kg traverse] Start node"}
                              "confidence" {:type "number" :description "[kg edge] Confidence 0.0-1.0"}
                              "max_depth" {:type "integer" :description "[kg] Max traversal depth"}
                              ;; Migration params
                              "path" {:type "string" :description "[migration] Backup file path"}
                              "backend" {:type "string" :description "[migration] Backend filter"}
                              ;; Shared
                              "operations" {:type "array"
                                            :items {:type "object"}
                                            :description "Array of operation objects for batch commands"}
                              "parallel" {:type "boolean"
                                          :description "Run batch operations in parallel (default: false)"}
                              "old-project-id" {:type "string" :description "[rename/migrate] Old project-id"}
                              "new-project-id" {:type "string" :description "[rename/migrate] New project-id"}
                              "dry-run" {:type "boolean" :description "[rename/migrate] Preview mode"}
                              "entry-ids" {:type "array" :items {:type "string"} :description "[migrate-scoped] Entry IDs"}
                              "tag-filter" {:type "string" :description "[migrate-scoped] Tag filter"}}
                 :required ["command"]}
   :handler handle-memory})

(defn tool-defs
  "Advertisement-time tool-defs for the memory supertool.

   A FN, not a def: the `relation` enum is registry-backed
   (hive-mcp.knowledge-graph.schema/relation-types) and addons register their
   relation types during initialize!, AFTER this namespace loads. A static enum
   freezes at ns-load and drifts from the set the store actually accepts — that
   is how the core `:relates` relation became a dead capability (advertised
   nowhere, therefore never chosen). Resolving here (registry/get-base-tools
   runs per tools/list, post-init) keeps the advertised enum a faithful mirror.

   Also advertises `predicate`, the free-text lane of the OPEN `:relates`
   relation. Falls back to the static `tool-def` (core enum) if the KG schema
   ns is unresolvable (KG addon absent).

   requiring-resolve, not a static :require — memory.clj lazily resolves the KG
   namespace by design (DIP; see canonical-handlers)."
  []
  (try
    (let [relation-names (mapv name ((requiring-resolve 'hive-mcp.knowledge-graph.schema/relation-types)))]
      [(-> tool-def
           (assoc-in [:inputSchema :properties "relation" :enum] relation-names)
           (assoc-in [:inputSchema :properties "predicate"]
                     {:type "string"
                      :description (str "[kg edge] Free-text semantic predicate for an OPEN `relates` edge "
                                        "(e.g. \"causes\", \"part-of\", \"motivates\"). Use with relation=\"relates\"; "
                                        "normalized to kebab-case; ignored for structural relations.")}))])
    (catch Exception _ [tool-def])))

(def tools [tool-def])
