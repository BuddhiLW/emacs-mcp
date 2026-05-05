(ns hive-mcp.knowledge-graph.edges.decay
  "Edge confidence decay (P2.9). Extracted from edges.clj.

   Cyclic dep with parent: edges.clj requires this ns, so calls back into
   edges/{update-edge-confidence!,emit-stats-event!} go through
   `requiring-resolve` (same pattern as edges/migration.clj)."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges.stats :as stats]
            [taoensso.timbre :as log]))

(def ^:const co-access-decay-rate
  "Confidence decay per wrap cycle for co-access edges.
   Co-access edges are less intentional, so decay faster."
  0.05)

(def ^:const semantic-decay-rate
  "Confidence decay per wrap cycle for semantic edges.
   Semantic edges are more intentional (explicitly created), so decay slower."
  0.02)

(def ^:const prune-threshold
  "Confidence below which edges are removed entirely.
   Prevents near-zero ghost edges from accumulating."
  0.1)

(defn edge-stale?
  "Check if an edge's last-verified timestamp is older than staleness-days.

   An edge is stale when:
   1. It has a :kg-edge/last-verified timestamp
   2. That timestamp is older than staleness-days ago

   Edges without :last-verified are considered stale (they were never verified).

   Pure predicate — no side effects."
  [edge staleness-days now-millis]
  (let [last-verified (:kg-edge/last-verified edge)]
    (if (nil? last-verified)
      true
      (let [verified-millis  (if (instance? java.util.Date last-verified)
                               (.getTime ^java.util.Date last-verified)
                               0)
            staleness-millis (* staleness-days 24 60 60 1000)]
        (> (- now-millis verified-millis) staleness-millis)))))

(defn decay-rate-for-edge
  "Return the decay rate for an edge based on its relation type.

   Co-access edges decay at co-access-decay-rate (faster).
   All other edges (semantic) decay at semantic-decay-rate (slower).

   Pure function — no side effects."
  [edge]
  (if (= :co-accessed (:kg-edge/relation edge))
    co-access-decay-rate
    semantic-decay-rate))

(defn- update-edge-confidence!*
  "Lazy-resolved indirection to edges/update-edge-confidence! to break
   the edges → decay → edges circular dep at compile time."
  [edge-id new-confidence]
  ((requiring-resolve 'hive-mcp.knowledge-graph.edges/update-edge-confidence!)
   edge-id new-confidence))

(defn- emit-stats-event!*
  "Lazy-resolved indirection to the private edges/emit-stats-event!.
   Mirrors edges/migration.clj's resolution pattern."
  [event-id payload fallback!]
  ((requiring-resolve 'hive-mcp.knowledge-graph.edges/emit-stats-event!)
   event-id payload fallback!))

(defn remove-edge!
  "Delete an edge by its ID.
   Returns true if edge was removed, false if not found."
  [edge-id]
  (if-let [eid (conn/entid [:kg-edge/id edge-id])]
    (let [edge     (conn/pull-entity '[*] eid)
          relation (:kg-edge/relation edge)
          scope    (:kg-edge/scope edge)]
      (conn/transact! [[:db/retractEntity eid]])
      (emit-stats-event!* :kg.edges/removed
                          {:relation relation :scope scope}
                          #(stats/apply-delta! relation scope -1))
      true)
    false))

(defn decay-step!
  "Per-edge step for decay. Returns :fresh, :decayed, or :pruned."
  [staleness-days now-millis edge]
  (if-not (edge-stale? edge staleness-days now-millis)
    :fresh
    (let [edge-id        (:kg-edge/id edge)
          rate           (decay-rate-for-edge edge)
          old-confidence (or (:kg-edge/confidence edge) 1.0)
          new-confidence (- old-confidence rate)]
      (if (< new-confidence prune-threshold)
        (do (remove-edge! edge-id)
            (log/debug "Pruned stale edge" edge-id
                       "confidence:" old-confidence "->" new-confidence
                       "relation:" (:kg-edge/relation edge))
            :pruned)
        (do (update-edge-confidence!* edge-id new-confidence)
            :decayed)))))
