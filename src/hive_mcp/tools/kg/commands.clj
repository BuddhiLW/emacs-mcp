(ns hive-mcp.tools.kg.commands
  "KG write/mutate command handlers for edge creation, promotion, and grounding."
  (:require [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.tools.kg.queries :as q]
            [hive-mcp.tools.kg.synthetics :as synthetics]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.grounding :as grounding]
            [hive-mcp.knowledge-graph.schema :as schema]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- validate-relation
  "Validate relation is a valid keyword."
  [relation]
  (cond
    (nil? relation)
    {:error "relation is required"}

    (and (string? relation) (not (empty? relation)))
    ;; Convert string to keyword and validate
    (let [rel-kw (keyword relation)]
      (when-not (schema/valid-relation? rel-kw)
        {:error (str "Invalid relation '" relation "'. Valid: "
                     (pr-str (schema/relation-types)))}))

    (keyword? relation)
    (when-not (schema/valid-relation? relation)
      {:error (str "Invalid relation. Valid: " (pr-str (schema/relation-types)))})

    :else
    {:error "relation must be a string or keyword"}))

(defn with-kg-flush
  "Boundary decorator for KG mutation handlers that write through the async
   write-coalescing queue (edges/add-edge! -> conn/transact!). After the wrapped
   handler returns, drain the queue with conn/flush-pending! so the datom is
   durable BEFORE the tool call returns — read-your-writes at the tool boundary.
   The immediate beneficiary is the multi/k> DSL: a later op that traverses or
   reads the just-created edge must not race the ~25ms coalescing window.

   conn/flush-pending! is a cheap no-op when the queue is empty (returns :ok
   without blocking), so wrapping the validation/error paths costs nothing.

   Centralizes a policy that was previously inlined in handle-kg-add-edge alone
   — the gap that let kg_promote return before its edge was durable. NOT applied
   to handlers that write the *memory* store (kg_reground, kg_backfill_grounding
   via mem-proto): flush-pending! drains only the KG writer. kg_cleanup_synthetics
   drains internally. Kanban 20260629161156-76f4e486."
  [handler]
  (fn [params]
    (let [result (handler params)]
      (conn/flush-pending!)
      result)))

(defn- add-edge*
  "Create a relationship between two knowledge nodes. Raw impl — the public
   handle-kg-add-edge wraps this with with-kg-flush for durable-on-return."
  [{:keys [from to relation scope confidence created_by predicate]}]
  (log/info "kg_add_edge" {:from from :to to :relation relation})
  (try
    ;; Validate inputs
    (or (q/validate-node-id from "from")
        (q/validate-node-id to "to")
        (validate-relation relation)
        ;; Execute
        (let [relation-kw (if (keyword? relation) relation (keyword relation))
              opts (cond-> {:from from
                            :to to
                            :relation relation-kw}
                     scope (assoc :scope scope)
                     confidence (assoc :confidence confidence)
                     created_by (assoc :created-by created_by)
                     predicate (assoc :predicate predicate))
              edge-id (edges/add-edge! opts)]
          (mcp-json {:success true
                     :edge-id edge-id
                     :message (str "Created edge " edge-id)})))
    (catch AssertionError e
      (log/warn "kg_add_edge validation failed:" (.getMessage e))
      (mcp-error (str "Validation error: " (.getMessage e))))
    (catch Exception e
      (log/error e "kg_add_edge failed")
      (mcp-error (str "Failed to add edge: " (.getMessage e))))))

(def handle-kg-add-edge
  "Create a relationship between two knowledge nodes. Durable-on-return via
   with-kg-flush (read-your-writes at the tool boundary)."
  (with-kg-flush add-edge*))

(defn- promote*
  "Promote knowledge edge to a broader scope, preserving the original. Raw impl
   — the public handle-kg-promote wraps this with with-kg-flush so the newly
   created edge is durable before the tool returns."
  [{:keys [edge_id to_scope]}]
  (log/info "kg_promote" {:edge edge_id :to-scope to_scope})
  (try
    (cond
      (or (nil? edge_id) (empty? edge_id))
      (mcp-error "edge_id is required")

      (or (nil? to_scope) (empty? to_scope))
      (mcp-error "to_scope is required")

      :else
      (if-let [original-edge (edges/get-edge edge_id)]
        ;; Create new edge in target scope
        (let [new-edge-id (edges/add-edge!
                           {:from (:kg-edge/from original-edge)
                            :to (:kg-edge/to original-edge)
                            :relation (:kg-edge/relation original-edge)
                            :scope to_scope
                            :confidence (:kg-edge/confidence original-edge)
                            :created-by (str "promoted-from:" edge_id)})]
          (mcp-json {:success true
                     :original-edge-id edge_id
                     :promoted-edge-id new-edge-id
                     :to-scope to_scope
                     :message (str "Promoted edge to scope " to_scope)}))
        (mcp-error (str "Edge not found: " edge_id))))
    (catch Exception e
      (log/error e "kg_promote failed")
      (mcp-error (str "Promotion failed: " (.getMessage e))))))

(def handle-kg-promote
  "Promote knowledge edge to a broader scope, preserving the original.
   Durable-on-return via with-kg-flush."
  (with-kg-flush promote*))

(defn handle-kg-reground
  "Re-ground a knowledge entry by verifying against its source file."
  [{:keys [entry_id force]}]
  (log/info "kg_reground" {:entry-id entry_id :force force})
  (try
    (or (q/validate-node-id entry_id "entry_id")
        (let [result (grounding/reground-entry! entry_id)]
          (mcp-json {:success true
                     :status (name (:status result))
                     :drift? (:drift? result)
                     :entry-id entry_id
                     :source-file (:source-file result)
                     :updated? (:updated? result)})))
    (catch Exception e
      (log/error e "kg_reground failed")
      (mcp-error (str "Re-grounding failed: " (.getMessage e))))))

(def handle-kg-cleanup-synthetics
  "Delete or demote synthetic-pattern nodes whose targets are mostly
   expired/missing memory entries. See `synthetics/cleanup-synthetics!`."
  synthetics/handle-kg-cleanup-synthetics)

(defn handle-kg-backfill-grounding
  "Batch-discover and ground all Chroma entries with source-file metadata."
  [{:keys [project_id limit force max_age_days]}]
  (log/info "kg_backfill_grounding" {:project-id project_id :limit limit :force force})
  (try
    (let [opts (cond-> {}
                 project_id (assoc :project-id project_id)
                 limit (assoc :limit limit)
                 force (assoc :force? force)
                 max_age_days (assoc :max-age-days max_age_days))
          result (grounding/backfill-grounding! opts)]
      (if (:error result)
        (mcp-error (str "Backfill failed: " (:error result)))
        (mcp-json {:success true
                   :total-scanned (:total-scanned result)
                   :with-source (:with-source result)
                   :processed (:processed result)
                   :by-status (:by-status result)
                   :drifted-entries (:drifted-entries result)})))
    (catch Exception e
      (log/error e "kg_backfill_grounding failed")
      (mcp-error (str "Backfill grounding failed: " (.getMessage e))))))

(def command-tools
  "Tool definitions for KG write/mutate operations."
  [{:name "kg_add_edge"
    :description "Create a relationship (edge) between two knowledge nodes in the Knowledge Graph. Relations: implements (realizes principle), supersedes (replaces), refines (improves), contradicts (conflicts), depends-on (requires), derived-from (synthesis origin), applies-to (scope applicability)."
    :inputSchema {:type "object"
                  :properties {"from" {:type "string"
                                       :description "Source node ID (memory entry ID)"}
                               "to" {:type "string"
                                     :description "Target node ID (memory entry ID)"}
                               "relation" {:type "string"
                                           :enum ["implements" "supersedes" "refines"
                                                  "contradicts" "depends-on"
                                                  "derived-from" "applies-to"]
                                           :description "Relation type"}
                               "scope" {:type "string"
                                        :description "Scope where edge was discovered (optional)"}
                               "confidence" {:type "number"
                                             :description "Confidence score 0.0-1.0 (default: 1.0)"}
                               "created_by" {:type "string"
                                             :description "Agent ID creating edge (optional)"}}
                  :required ["from" "to" "relation"]}
    :handler handle-kg-add-edge}

   {:name "kg_promote"
    :description "Promote knowledge (edge) to a broader scope. Creates a new edge in target scope, preserving original. Use to 'bubble up' valuable knowledge from submodule to parent."
    :inputSchema {:type "object"
                  :properties {"edge_id" {:type "string"
                                          :description "Edge ID to promote"}
                               "to_scope" {:type "string"
                                           :description "Target scope (e.g., 'hive-mcp' or 'global')"}}
                  :required ["edge_id" "to_scope"]}
    :handler handle-kg-promote}

   {:name "kg_reground"
    :description "Re-verify a knowledge entry against its source file and update grounding timestamp. Detects drift when source content has changed since last grounding. Returns status: regrounded (success), needs-review (drift detected), source-missing (file not found)."
    :inputSchema {:type "object"
                  :properties {"entry_id" {:type "string"
                                           :description "Entry ID to re-ground"}
                               "force" {:type "boolean"
                                        :description "Force re-ground even if recently grounded (optional)"}}
                  :required ["entry_id"]}
    :handler handle-kg-reground}

   {:name "kg_backfill_grounding"
    :description "Batch-discover and ground all memory entries with source-file metadata. Scans Chroma, computes content hashes, sets grounded-at timestamps, and detects drift. Use to bootstrap grounding for existing entries or periodically refresh staleness."
    :inputSchema {:type "object"
                  :properties {"project_id" {:type "string"
                                             :description "Filter to specific project (optional, default: all)"}
                               "limit" {:type "integer"
                                        :description "Max entries to process (optional, default: 500)"}
                               "force" {:type "boolean"
                                        :description "Re-ground even if already grounded (optional, default: false)"}
                               "max_age_days" {:type "integer"
                                               :description "Only re-ground entries older than N days (optional, default: 7)"}}
                  :required []}
    :handler handle-kg-backfill-grounding}

   synthetics/tool-def])
