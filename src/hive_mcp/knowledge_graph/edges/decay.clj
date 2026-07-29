(ns hive-mcp.knowledge-graph.edges.decay
  "Edge confidence decay (P2.9). Extracted from edges.clj.

   Cyclic dep with parent: edges.clj requires this ns, so calls back into
   edges/{update-edge-confidence!,emit-stats-event!} go through
   `requiring-resolve` (same pattern as edges/migration.clj)."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges.stats :as stats]
            [taoensso.timbre :as log]
            [hive-mcp.knowledge-graph.edges.queries :as queries]
            [hive-mcp.knowledge-graph.edge-cycle :as edge-cycle]))

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

   Semantic (core structural) relations decay at semantic-decay-rate
   (slower). Everything else — :co-accessed, system-generated extension
   relations (:projects-to, :contains), the open :relates, and unknown
   relations — decays at co-access-decay-rate (faster): non-structural
   edges are re-derivable, so aggressive decay is safe.

   Pure function — no side effects."
  [edge]
  (if (contains? #{:implements :supersedes :refines :contradicts
                   :depends-on :derived-from :applies-to}
                 (:kg-edge/relation edge))
    semantic-decay-rate
    co-access-decay-rate))

(defn- update-edge-confidence!*
  "Lazy-resolved indirection to edges.write/update-edge-confidence! to break
   the write -> decay -> write circular dep at compile time."
  [edge-id new-confidence]
  ((requiring-resolve 'hive-mcp.knowledge-graph.edges.write/update-edge-confidence!)
   edge-id new-confidence))

(defn- emit-stats-event!*
  "Lazy-resolved indirection to edges.write/emit-stats-event!.
   Mirrors edges.migration's resolution pattern."
  [event-id payload fallback!]
  ((requiring-resolve 'hive-mcp.knowledge-graph.edges.write/emit-stats-event!)
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

(def ^:const default-decay-staleness-days
  "Minimum days since last-verified before an edge is considered stale.
   Edges verified within this window are untouched."
  30)

(def ^:const default-decay-limit
  "Maximum edges to evaluate per decay cycle.
   Bounded to prevent long-running cycles on large graphs."
  100)

(defn last-verified-millis
  "Extract :kg-edge/last-verified as millis, 0 if missing/non-Date."
  [edge]
  (if-let [lv (:kg-edge/last-verified edge)]
    (if (instance? java.util.Date lv)
      (.getTime ^java.util.Date lv)
      0)
    0))

(defn decay-unverified-edges!
  "Decay confidence of edges not verified within the staleness window.
   Edges falling below `prune-threshold` are removed entirely.

   See `decay-step!` for per-edge logic and `edge-cycle/run-cycle!` for
   the shared skeleton.

   Options:
     :staleness-days - Days before edge is considered stale (default: 30)
     :limit          - Max edges to evaluate (default: 100)
     :scope          - Optional scope filter
     :created-by     - Agent ID for attribution in logs

   Returns:
     {:decayed N :pruned N :fresh N :evaluated N :errors N}

   Idempotent. Non-blocking: per-edge errors are tallied, never thrown."
  [& [{:keys [staleness-days limit scope created-by]
       :or {staleness-days default-decay-staleness-days
            limit          default-decay-limit}}]]
  (conn/with-tx-batch
    (let [now-millis (System/currentTimeMillis)]
      (edge-cycle/run-cycle!
       {:fetch        #(if scope (queries/get-edges-by-scope scope) (queries/get-all-edges))
        :sort-key     last-verified-millis
        :limit        limit
        :outcome-keys [:decayed :pruned :fresh]
        :step!        #(decay-step! staleness-days now-millis %)
        :error-log-fn (fn [edge err]
                        (log/debug "Edge decay failed for edge"
                                   (:kg-edge/id edge) ":" (:message err)))
        :log-fn       (fn [tally]
                        (when (or (pos? (:decayed tally)) (pos? (:pruned tally)))
                          (log/info "Edge decay:" (:decayed tally) "decayed,"
                                    (:pruned tally) "pruned"
                                    (when created-by (str " by:" created-by)))))}))))