(ns hive-mcp.knowledge-graph.edges.promotion
  "Co-access recording + co-access -> :depends-on promotion (P1.6). Extracted
   from edges.clj.

   Co-access edges are reinforced on repeat recall and, once confident enough,
   promoted to semantic :depends-on edges via `edge-cycle/run-cycle!`. Mutations
   go through `edges.write`; reads through `edges.queries`. The parent façade
   re-exports the public vars here under their historical names."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edge-cycle :as edge-cycle]
            [hive-mcp.knowledge-graph.edges.queries :as queries]
            [hive-mcp.knowledge-graph.edges.write :as write]
            [taoensso.timbre :as log]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; Co-Access Recording
;; =============================================================================

(defn record-co-access!
  "Record co-access pattern between a batch of memory entries.
   Creates :co-accessed edges between pairs that were recalled together.
   If an edge already exists between a pair, increments its confidence instead.

   Arguments:
     entry-ids - Collection of entry IDs recalled in the same batch (min 2)
     opts      - Optional map with:
                 :scope      - Scope where co-access occurred
                 :created-by - Agent/tool that triggered the recall

   Returns the count of edges created or reinforced."
  [entry-ids & [{:keys [scope created-by]}]]
  (let [ids (vec (distinct entry-ids))]
    (when (>= (count ids) 2)
      (conn/with-tx-batch
        (let [pairs (for [i (range (count ids))
                          j (range (inc i) (count ids))]
                      [(nth ids i) (nth ids j)])
             ;; Limit pairs to avoid quadratic explosion on large batches
              limited-pairs (take 50 pairs)]
          (count
           (for [[from-id to-id] limited-pairs]
             (if-let [existing (queries/find-edge from-id to-id :co-accessed)]
              ;; Reinforce existing co-access edge
               (do (write/increment-confidence! (:kg-edge/id existing) 0.1)
                   (write/verify-edge! (:kg-edge/id existing))
                   :reinforced)
              ;; Create new co-access edge with low initial confidence
               (do (write/add-edge! (cond-> {:from from-id
                                             :to to-id
                                             :relation :co-accessed
                                             :confidence 0.3
                                             :source-type :co-access}
                                      scope (assoc :scope scope)
                                      created-by (assoc :created-by created-by)))
                   :created)))))))))

(defn get-co-accessed
  "Get entries co-accessed with the given entry.
   Returns entry IDs sorted by confidence (strongest co-access first).

   Arguments:
     entry-id - Entry ID to find co-accessed entries for

   Returns:
     Vector of {:entry-id <id> :confidence <score>}"
  [entry-id]
  (let [outgoing (queries/get-edges-from entry-id)
        incoming (queries/get-edges-to entry-id)
        co-access-edges (->> (into outgoing incoming)
                             (filter #(= :co-accessed (:kg-edge/relation %))))
        neighbors (map (fn [edge]
                         {:entry-id (if (= (:kg-edge/from edge) entry-id)
                                      (:kg-edge/to edge)
                                      (:kg-edge/from edge))
                          :confidence (or (:kg-edge/confidence edge) 0.3)})
                       co-access-edges)]
    (->> neighbors
         (sort-by :confidence >)
         vec)))

;; =============================================================================
;; Co-Access -> Depends-On Promotion (P1.6)
;; =============================================================================

(def ^:const default-promotion-threshold
  "Minimum confidence for co-access edge promotion to :depends-on.
   At 0.3 start + 0.1 per reinforce, 0.7 requires ~5 co-accesses."
  0.7)

(def ^:const default-promoted-confidence
  "Confidence score for newly promoted :depends-on edges.
   Lower than manual (1.0) since this is inferred from co-access patterns."
  0.5)

(def ^:const default-promotion-limit
  "Maximum co-access edges to evaluate per promotion cycle.
   Bounded to prevent long-running cycles on large graphs."
  20)

(defn- co-access-edge-promotable?
  "Check if a co-access edge is eligible for promotion to :depends-on.

   An edge is promotable when:
   1. It is a :co-accessed relation
   2. Its confidence >= threshold

   Pure predicate — no side effects."
  [edge threshold]
  (and (= :co-accessed (:kg-edge/relation edge))
       (>= (or (:kg-edge/confidence edge) 0.0) threshold)))

(defn- depends-on-exists?
  "Check if a :depends-on edge already exists between two nodes (either direction).
   Returns true if found, false otherwise.

   Checks both directions because co-access is undirected:
   A co-accessed with B could mean A depends-on B or B depends-on A."
  [from-id to-id]
  (or (some? (queries/find-edge from-id to-id :depends-on))
      (some? (queries/find-edge to-id from-id :depends-on))))

(defn- promote-step!
  "Per-edge step for co-access promotion. Returns outcome keyword."
  [{:keys [threshold confidence scope created-by]} edge]
  (if-not (co-access-edge-promotable? edge threshold)
    :below
    (let [from-id (:kg-edge/from edge)
          to-id   (:kg-edge/to edge)]
      (if (depends-on-exists? from-id to-id)
        :skipped
        (do (write/add-edge! (cond-> {:from from-id
                                      :to to-id
                                      :relation :depends-on
                                      :confidence confidence
                                      :source-type :inferred}
                               scope      (assoc :scope scope)
                               created-by (assoc :created-by created-by)))
            :promoted)))))

(defn promote-co-access-edges!
  "Promote high-confidence co-access edges to :depends-on semantic edges.

   See `promote-step!` for per-edge logic and `edge-cycle/run-cycle!` for
   the shared skeleton (fetch -> sort -> limit -> tally).

   Options:
     :threshold  - Minimum confidence for promotion (default: 0.7)
     :confidence - Confidence for new :depends-on edges (default: 0.5)
     :limit      - Max edges to evaluate (default: 20)
     :scope      - Optional scope filter for co-access edges
     :created-by - Agent ID for attribution

   Returns:
     {:promoted N :skipped N :below N :evaluated N :errors N}

   Idempotent. Non-blocking: per-edge errors are tallied, never thrown."
  [& [{:keys [threshold confidence limit scope created-by]
       :or {threshold  default-promotion-threshold
            confidence default-promoted-confidence
            limit      default-promotion-limit}
       :as opts}]]
  (conn/with-tx-batch
    (edge-cycle/run-cycle!
     {:fetch        #(queries/get-edges-by-relation :co-accessed scope)
      :sort-key     #(or (:kg-edge/confidence %) 0.0)
      :sort-desc?   true
      :limit        limit
      :outcome-keys [:promoted :skipped :below]
      :step!        #(promote-step! (assoc opts
                                           :threshold threshold
                                           :confidence confidence
                                           :scope scope
                                           :created-by created-by)
                                    %)
      :error-log-fn (fn [edge err]
                      (log/debug "Co-access promotion failed for edge"
                                 (:kg-edge/id edge) ":" (:message err)))
      :log-fn       (fn [tally]
                      (when (pos? (:promoted tally))
                        (log/info "Promoted" (:promoted tally)
                                  "co-access edges to :depends-on")))})))
