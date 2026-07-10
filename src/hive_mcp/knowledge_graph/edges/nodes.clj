(ns hive-mcp.knowledge-graph.edges.nodes
  "Node-level read facade over the KG edge set: distinct participating node
   ids, tag -> node lookup (Chroma-backed), and 1-hop neighbors."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges.queries :as queries]
            [taoensso.timbre :as log]))

(defn get-all-node-ids
  "Distinct node IDs participating in any KG edge — the union of
   :kg-edge/from and :kg-edge/to values.

   Index-only datalog queries (both attrs are :db/index true per norm 001);
   no entity pulls, so this stays cheap even at millions of edges.

   Arities:
     []      => all nodes
     [scope] => only nodes on edges with :kg-edge/scope = scope
                (nil scope behaves like the 0-arity)"
  ([]
   (let [froms (conn/query '[:find [?n ...] :where [_ :kg-edge/from ?n]])
         tos   (conn/query '[:find [?n ...] :where [_ :kg-edge/to ?n]])]
     (vec (distinct (concat froms tos)))))
  ([scope]
   (if (nil? scope)
     (get-all-node-ids)
     (let [froms (conn/query '[:find [?n ...]
                               :in $ ?scope
                               :where
                               [?e :kg-edge/scope ?scope]
                               [?e :kg-edge/from ?n]]
                             scope)
           tos   (conn/query '[:find [?n ...]
                               :in $ ?scope
                               :where
                               [?e :kg-edge/scope ?scope]
                               [?e :kg-edge/to ?n]]
                             scope)]
       (vec (distinct (concat froms tos)))))))

(def ^:const node-ids-by-tag-limit
  "Max entries fetched per tag by `node-ids-by-tag`. Tags with more entries
   than this are truncated at the Chroma layer."
  10000)

(defn node-ids-by-tag
  "Entry IDs (KG node IDs) tagged with `tag`.

   CROSS-CONTEXT DEPENDENCY: tags live in Chroma metadata, not in the
   Datahike KG — this delegates to `hive-mcp.chroma.crud/query-entries`
   (:tags [tag] :limit 10000) via requiring-resolve. The 10k cap
   (`node-ids-by-tag-limit`) means tags with more than 10000 entries are
   truncated at the Chroma layer.

   Degrades LOUDLY, not silently: when the Chroma/memory query layer is
   unavailable (e.g. no embedding provider configured — require-embedding!
   throws), logs WARN \"node-ids-by-tag degraded\" and returns [].
   Callers must treat [] as \"no signal\", never as \"no nodes tagged\"."
  [tag]
  (try
    (let [query-entries (requiring-resolve 'hive-mcp.chroma.crud/query-entries)]
      (into [] (keep :id) (query-entries :tags [tag] :limit node-ids-by-tag-limit)))
    (catch Exception e
      (log/warn "node-ids-by-tag degraded — chroma/memory query layer unavailable, returning []"
                {:tag tag :error (.getMessage e)})
      [])))

(defn neighbors
  "Distinct 1-hop neighbor node IDs of `node-id`.

   direction:
     :out  => targets of outgoing edges (node-id = :kg-edge/from)
     :in   => sources of incoming edges (node-id = :kg-edge/to)
     :both => union of both (default; nil treated as :both)

   Self-loops are excluded. Built on get-edges-from/get-edges-to."
  ([node-id]
   (neighbors node-id :both))
  ([node-id direction]
   (let [out #(map :kg-edge/to (queries/get-edges-from node-id))
         in  #(map :kg-edge/from (queries/get-edges-to node-id))
         ids (case direction
               :out        (out)
               :in         (in)
               (:both nil) (concat (out) (in))
               (throw (ex-info "neighbors: invalid direction"
                               {:direction direction
                                :valid #{:in :out :both}})))]
     (->> ids
          (remove #(= node-id %))
          distinct
          vec))))
