(ns hive-mcp.knowledge-graph.edges.batch
  "Batch edge queries.

   Each accessor has two arities: one against the live KG conn, one
   against an explicit db value (snapshot). The db-aware variants enable
   branch-compare verification (capture db-pre / db-post around a tx,
   diff the two edge sets) without forcing callers to reach into the
   backend-specific Datalog API."
  (:require [hive-mcp.knowledge-graph.connection :as conn]))

(declare batch-get-edges-from batch-get-edges-to batch-get-co-accessed)

(defn batch-get-edges-from
  "Batch query: get all outgoing edges from a collection of source node IDs.
   Uses DataScript/Datalevin/Datahike collection binding `[?from ...]` for a
   single query instead of N individual queries.

   Optional scope filter limits to edges whose :kg-edge/scope matches.

   Returns a map of {node-id -> [edges]}."
  ([node-ids] (batch-get-edges-from node-ids nil))
  ([node-ids scope]
   (if (empty? node-ids)
     {}
     (let [ids-vec (vec (distinct node-ids))
           base-q '[:find [(pull ?e [*]) ...]
                    :in $ [?from ...]
                    :where [?e :kg-edge/from ?from]]
           scoped-q '[:find [(pull ?e [*]) ...]
                      :in $ [?from ...] ?scope
                      :where
                      [?e :kg-edge/from ?from]
                      [?e :kg-edge/scope ?scope]]
           all-edges (if scope
                       (conn/query scoped-q ids-vec scope)
                       (conn/query base-q ids-vec))]
       (group-by :kg-edge/from all-edges)))))

(defn batch-get-edges-to
  "Batch query: get all incoming edges to a collection of target node IDs.
   Uses collection binding `[?to ...]` for a single query.

   Optional scope filter limits to edges whose :kg-edge/scope matches.

   Returns a map of {node-id -> [edges]}."
  ([node-ids] (batch-get-edges-to node-ids nil))
  ([node-ids scope]
   (if (empty? node-ids)
     {}
     (let [ids-vec (vec (distinct node-ids))
           base-q '[:find [(pull ?e [*]) ...]
                    :in $ [?to ...]
                    :where [?e :kg-edge/to ?to]]
           scoped-q '[:find [(pull ?e [*]) ...]
                      :in $ [?to ...] ?scope
                      :where
                      [?e :kg-edge/to ?to]
                      [?e :kg-edge/scope ?scope]]
           all-edges (if scope
                       (conn/query scoped-q ids-vec scope)
                       (conn/query base-q ids-vec))]
       (group-by :kg-edge/to all-edges)))))

(defn batch-get-edges-from-with-db
  "Like `batch-get-edges-from`, but queries against an EXPLICIT db value
   (snapshot). Used by carto verify-isomorphism's branch-compare path
   to query db-pre / db-post without the live conn racing the rescan."
  ([db node-ids] (batch-get-edges-from-with-db db node-ids nil))
  ([db node-ids scope]
   (if (empty? node-ids)
     {}
     (let [ids-vec (vec (distinct node-ids))
           base-q '[:find [(pull ?e [*]) ...]
                    :in $ [?from ...]
                    :where [?e :kg-edge/from ?from]]
           scoped-q '[:find [(pull ?e [*]) ...]
                      :in $ [?from ...] ?scope
                      :where
                      [?e :kg-edge/from ?from]
                      [?e :kg-edge/scope ?scope]]
           all-edges (if scope
                       (conn/query-with-db db scoped-q ids-vec scope)
                       (conn/query-with-db db base-q ids-vec))]
       (group-by :kg-edge/from all-edges)))))

(defn batch-get-edges-to-with-db
  "Like `batch-get-edges-to`, but queries against an EXPLICIT db value
   (snapshot). Used by carto verify-isomorphism's branch-compare path."
  ([db node-ids] (batch-get-edges-to-with-db db node-ids nil))
  ([db node-ids scope]
   (if (empty? node-ids)
     {}
     (let [ids-vec (vec (distinct node-ids))
           base-q '[:find [(pull ?e [*]) ...]
                    :in $ [?to ...]
                    :where [?e :kg-edge/to ?to]]
           scoped-q '[:find [(pull ?e [*]) ...]
                      :in $ [?to ...] ?scope
                      :where
                      [?e :kg-edge/to ?to]
                      [?e :kg-edge/scope ?scope]]
           all-edges (if scope
                       (conn/query-with-db db scoped-q ids-vec scope)
                       (conn/query-with-db db base-q ids-vec))]
       (group-by :kg-edge/to all-edges)))))

(defn batch-get-co-accessed
  "Batch query: get co-accessed entries for a collection of entry IDs.
   Uses two collection-binding queries (outgoing + incoming) instead of
   2*N individual queries.

   Returns a map of {entry-id -> [{:entry-id <neighbor> :confidence <score>}]}."
  [entry-ids]
  (if (empty? entry-ids)
    {}
    (let [ids-vec (vec (distinct entry-ids))
          ids-set (set ids-vec)
          ;; Single query for all outgoing co-access edges from any of the IDs
          outgoing-q '[:find [(pull ?e [*]) ...]
                       :in $ [?from ...]
                       :where
                       [?e :kg-edge/from ?from]
                       [?e :kg-edge/relation :co-accessed]]
          ;; Single query for all incoming co-access edges to any of the IDs
          incoming-q '[:find [(pull ?e [*]) ...]
                       :in $ [?to ...]
                       :where
                       [?e :kg-edge/to ?to]
                       [?e :kg-edge/relation :co-accessed]]
          outgoing (conn/query outgoing-q ids-vec)
          incoming (conn/query incoming-q ids-vec)
          ;; Build per-entry neighbor maps
          add-neighbor
          (fn [acc source-id neighbor-id confidence]
            (if (contains? ids-set source-id)
              (update acc source-id
                      (fnil conj [])
                      {:entry-id neighbor-id
                       :confidence (or confidence 0.3)})
              acc))
          result (as-> {} $
                   (reduce (fn [acc edge]
                             (add-neighbor acc
                                           (:kg-edge/from edge)
                                           (:kg-edge/to edge)
                                           (:kg-edge/confidence edge)))
                           $ outgoing)
                   (reduce (fn [acc edge]
                             (add-neighbor acc
                                           (:kg-edge/to edge)
                                           (:kg-edge/from edge)
                                           (:kg-edge/confidence edge)))
                           $ incoming))]
      ;; Sort each entry's neighbors by confidence descending
      (into {} (map (fn [[k v]] [k (vec (sort-by :confidence > v))]) result)))))
