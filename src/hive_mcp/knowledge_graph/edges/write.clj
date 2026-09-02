(ns hive-mcp.knowledge-graph.edges.write
  "Edge mutation surface (CRUD write side). Extracted from edges.clj.

   Delegates persistence to `connection` and metric deltas to the
   `kg.edges/*` event facade (`emit-stats-event!` -> events / edges.stats).
   Reads it needs (get-edge, get-edges-from/to) come from `edges.queries`;
   `remove-edge!` lives in `edges.decay`. The parent façade re-exports every
   public var here under its historical name (zero caller changes)."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-mcp.knowledge-graph.edges.ids :as ids]
            [hive-mcp.knowledge-graph.edges.queries :as queries]
            [hive-mcp.knowledge-graph.edges.stats :as stats]
            [hive-mcp.knowledge-graph.edges.decay :as decay]
            [hive-mcp.events.core :as events]
            [taoensso.timbre :as log]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn emit-stats-event!
  "Dispatch a `:kg.edges/*` stats event so any registered observer (the
   stats cache wired by `edges.stats-events`, plus future telemetry /
   audit listeners) sees the mutation. Falls back to a direct call into
   `edges.stats` when no handler is registered yet (early namespace
   load, or unit tests that exercise CRUD without the events facade)."
  [event-id payload fallback!]
  (try
    (if (events/handler-registered? event-id)
      (events/dispatch [event-id payload])
      (fallback!))
    (catch Exception e
      (log/debug "Edge stats event dispatch failed; applying delta directly:"
                 (.getMessage e))
      (fallback!))))

(defn edge-tx-data
  "Validate one edge spec and return its transaction map. Pure apart from the
   generated id and timestamp; throws ex-info on any validation failure.

   Shared by `add-edge!` and `add-edges!` so the two cannot disagree about what
   a valid edge is."
  [{:keys [from to relation scope confidence created-by source-type last-verified predicate]
    :or {confidence 1.0}}]
  (when-not (and (schema/valid-node-id? from) (schema/valid-node-id? to))
    (throw (ex-info "Edge requires non-blank string :from and :to node IDs"
                    {:from from :to to})))
  (when-not (schema/valid-relation? relation)
    (throw (ex-info "Invalid relation type"
                    {:relation relation
                     :valid-relations (schema/relation-types)})))
  (when-not (schema/valid-confidence? confidence)
    (throw (ex-info "Invalid confidence score (must be 0.0-1.0)"
                    {:confidence confidence})))
  (when (and source-type (not (schema/valid-source-type? source-type)))
    (throw (ex-info "Invalid source type"
                    {:source-type source-type
                     :valid-source-types schema/source-types})))
  (when (and predicate (not= relation :relates))
    (log/warn "kg add-edge: :predicate ignored for non-:relates relation"
              {:relation relation :predicate predicate}))
  (let [now       (java.util.Date.)
        norm-pred (when (= relation :relates) (schema/normalize-predicate predicate))]
    (cond-> {:kg-edge/id            (ids/generate-edge-id)
             :kg-edge/from          from
             :kg-edge/to            to
             :kg-edge/relation      relation
             :kg-edge/confidence    confidence
             :kg-edge/created-at    now
             :kg-edge/last-verified (or last-verified now)}
      scope       (assoc :kg-edge/scope scope)
      created-by  (assoc :kg-edge/created-by created-by)
      source-type (assoc :kg-edge/source-type source-type)
      norm-pred   (assoc :kg-edge/predicate norm-pred))))

(defn add-edges!
  "Create every edge in EDGE-SPECS in ONE transaction. Returns a vector of edge
   ids in the order given.

   `add-edge!` transacts per edge, and at the tool boundary each call also pays a
   `flush-pending!` durability barrier. Synthesis writes edges in groups of tens
   — one ingest run issued thousands — so the per-edge transaction, not the edge
   itself, is the cost. Validation happens for ALL specs before anything is
   transacted, so a bad spec in the batch writes nothing.

   Empty input transacts nothing and returns []."
  [edge-specs]
  (if (empty? edge-specs)
    []
    (let [tx-data (mapv edge-tx-data edge-specs)]
      (conn/transact! tx-data)
      (doseq [d tx-data]
        (emit-stats-event! :kg.edges/added
                           {:relation (:kg-edge/relation d) :scope (:kg-edge/scope d)}
                           #(stats/apply-delta! (:kg-edge/relation d) (:kg-edge/scope d) 1)))
      (mapv :kg-edge/id tx-data))))

(defn add-edge!
  "Create a new edge between two knowledge nodes.

   Required keys:
   - :from       - Source node ID (memory entry ID)
   - :to         - Target node ID (memory entry ID)
   - :relation   - Relation type (must be in schema/relation-types)

   Optional keys:
   - :scope         - Scope where edge was discovered
   - :confidence    - Confidence score 0.0-1.0 (default: 1.0)
   - :created-by    - Agent ID that created edge
   - :source-type   - How edge was established (:manual, :automated, :inferred, :co-access)
   - :last-verified - Timestamp of last verification (defaults to creation time)
   - :predicate     - Free-text semantic predicate for OPEN :relates edges
                      (e.g. causes, part-of). Normalized to kebab-case via
                      schema/normalize-predicate. Ignored for non-:relates
                      relations (a warning is logged).

   :from and :to must be non-blank strings (schema/valid-node-id?).

   Returns the edge ID on success, throws on validation failure. Writing many
   edges? Use `add-edges!` — it transacts once for the whole batch."
  [edge-spec]
  (first (add-edges! [edge-spec])))

(defn update-edge-confidence!
  "Update the confidence score of an edge.
   Returns true on success, throws on validation failure."
  [edge-id new-confidence]
  (when-not (schema/valid-confidence? new-confidence)
    (throw (ex-info "Invalid confidence score (must be 0.0-1.0)"
                    {:confidence new-confidence})))
  (when-let [eid (conn/entid [:kg-edge/id edge-id])]
    (conn/transact! [[:db/add eid :kg-edge/confidence new-confidence]])
    true))

(defn verify-edge!
  "Update the last-verified timestamp of an edge.
   Call when an edge relationship is confirmed to still be valid.
   Returns true on success, nil if edge not found."
  [edge-id]
  (when-let [eid (conn/entid [:kg-edge/id edge-id])]
    (conn/transact! [[:db/add eid :kg-edge/last-verified (java.util.Date.)]])
    true))

(defn increment-confidence!
  "Increment the confidence score of an edge by delta.
   Clamps result to 0.0-1.0 range.
   Returns the new confidence score, or nil if edge not found."
  [edge-id delta]
  (when-let [edge (queries/get-edge edge-id)]
    (let [old-confidence (or (:kg-edge/confidence edge) 1.0)
          new-confidence (-> (+ old-confidence delta)
                             (max 0.0)
                             (min 1.0))]
      (update-edge-confidence! edge-id new-confidence)
      new-confidence)))

(defn remove-edges-for-node!
  "Remove all edges connected to a node (both incoming and outgoing).
   Use when deleting a memory entry to clean up its KG relationships.
   Returns the count of edges removed."
  [node-id]
  (conn/with-tx-batch
    (let [outgoing (queries/get-edges-from node-id)
          incoming (queries/get-edges-to node-id)
          all-edges (distinct (into outgoing incoming))
          edge-ids (map :kg-edge/id all-edges)]
      (doseq [eid edge-ids]
        (decay/remove-edge! eid))
      (count edge-ids))))
