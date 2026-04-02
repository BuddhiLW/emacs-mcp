(ns hive-mcp.tools.consolidated.memory
  "Consolidated memory tool using CLI dispatcher pattern."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler make-batch-handler]]
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
      :memory/get    (mem/handle-mcp-memory-get-full params))))

(def handlers
  {:add         mem/handle-mcp-memory-add
   :query       (fn [params] (dispatch-memory-read :memory/query params))
   :metadata    (fn [params] (dispatch-memory-read :memory/query
                                                   (assoc params :verbosity "metadata")))
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
   :import      mem/handle-mcp-memory-import-json
   :decay             mem/handle-mcp-memory-decay
   :xpoll             mem/handle-mcp-memory-xpoll-promote
   :rename            mem/handle-mcp-memory-rename-project
   :batch-get         mem/handle-mcp-memory-batch-get})

(defn- make-single-command-batch
  "Wrap make-batch-handler for batch ops targeting one command."
  [cmd-kw handler-fn]
  (let [batch-fn (make-batch-handler {cmd-kw handler-fn})]
    (fn [{:keys [operations] :as params}]
      (batch-fn (assoc params :operations
                       (mapv #(assoc % :command (name cmd-kw)) operations))))))

(def canonical-handlers
  (assoc handlers
         :batch-add      (make-single-command-batch :add (:add handlers))
         :batch-feedback (make-single-command-batch :feedback (:feedback handlers))))

(def handle-memory
  (make-cli-handler canonical-handlers))

(def tool-def
  {:name "memory"
   :consolidated true
   :description "Consolidated memory operations. Commands: add, query, metadata, get, search, duration, promote, demote, log_access, feedback, helpfulness, tags, cleanup, expiring, expire, migrate, import, decay, xpoll, rename, batch-add, batch-feedback, batch-get. Use 'help' command to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["add" "query" "metadata" "get" "search" "duration" "promote" "demote" "log_access" "feedback" "helpfulness" "tags" "cleanup" "expiring" "expire" "migrate" "import" "decay" "xpoll" "rename" "batch-add" "batch-feedback" "batch-get" "help"]
                                         :description "Command to execute"}
                              "type" {:type "string"
                                      :enum (type-registry/mcp-enum)
                                      :description "[add/query] Type of memory entry"}
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
                                           :description "[query] Output detail: 'metadata' (default) returns only id/type/preview/tags/created (~10x fewer tokens), 'full' returns complete entries"}
                              "id" {:type "string"
                                    :description "[get/promote/demote/feedback/tags] Memory entry ID"}
                              "ids" {:type "array"
                                     :items {:type "string"}
                                     :description "[batch-get] Array of memory entry IDs to retrieve"}
                              "query" {:type "string"
                                       :description "[search] Natural language query for semantic search"}
                              "exclude_tags" {:type "array"
                                              :items {:type "string"}
                                              :description "[query/search] Tags to exclude from results (search defaults to [\"carto\"] — pass [] to include codebase snippets)"}
                              "feedback" {:type "string"
                                          :enum ["helpful" "unhelpful"]
                                          :description "[feedback] Helpfulness rating"}
                              "days" {:type "integer"
                                      :description "[expiring] Days to look ahead (default: 7). Use 1-2 for short-duration memories"}
                              "include_descendants" {:type "boolean"
                                                     :description "[query/search] Include child project memories in results (HCR Wave 4). Default false."}
                              "old-project-id" {:type "string"
                                                :description "[rename/migrate] Old project-id to rename from"}
                              "new-project-id" {:type "string"
                                                :description "[rename/migrate] New project-id to rename to"}
                              "dry-run" {:type "boolean"
                                         :description "[rename] Preview what would happen without modifying (default: false)"}
                              "operations" {:type "array"
                                            :items {:type "object"}
                                            :description "[batch-add/batch-feedback] Array of operation objects. batch-add: [{type, content, tags, ...}]. batch-feedback: [{id, feedback}]."}
                              "parallel" {:type "boolean"
                                          :description "[batch-add/batch-feedback] Run batch operations in parallel (default: false)"}}
                 :required ["command"]}
   :handler handle-memory})

(def tools [tool-def])
