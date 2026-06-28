(ns hive-mcp.knowledge-graph.edges.queries
  (:require [hive-mcp.knowledge-graph.connection :as conn]))

(declare get-edge get-edges-from get-edges-to get-edges-by-relation get-edges-by-scope find-edge find-edges-between pull-edge-batch get-all-edges count-edges)

(def ^:private edge-pull-pattern
  "Narrow pull pattern for edge rows.
   Enumerates every attribute callers of get-all-edges actually read, so we
   never fall back to `(pull ?e [*])` — that was the 4GB OOM trigger on 1.2M
   edges (noted 2026-04-23)."
  [:kg-edge/id
   :kg-edge/from
   :kg-edge/to
   :kg-edge/relation
   :kg-edge/scope
   :kg-edge/confidence
   :kg-edge/last-verified
   :kg-edge/created-at
   :kg-edge/source-type
   :kg-edge/created-by])

(def ^:const default-edge-batch-size
  "Edges pulled per batch in the streaming get-all-edges path.
   500 keeps per-batch allocations well under the OOM threshold
   even at 1.2M edges, while amortizing per-call overhead."
  500)

(defn get-edge
  "Get an edge by its ID.
   Returns the edge entity map or nil if not found."
  [edge-id]
  (when-let [eid (conn/entid [:kg-edge/id edge-id])]
    (conn/pull-entity '[*] eid)))

(defn get-edges-from
  "Query all outgoing edges from a source node.
   Optional scope filter limits to edges visible from that scope."
  ([from-node-id]
   (get-edges-from from-node-id nil))
  ([from-node-id scope]
   (let [base-query '[:find [(pull ?e [*]) ...]
                      :in $ ?from
                      :where [?e :kg-edge/from ?from]]
         scoped-query '[:find [(pull ?e [*]) ...]
                        :in $ ?from ?scope
                        :where
                        [?e :kg-edge/from ?from]
                        [?e :kg-edge/scope ?scope]]]
     (if scope
       (conn/query scoped-query from-node-id scope)
       (conn/query base-query from-node-id)))))

(defn get-edges-to
  "Query all incoming edges to a target node.
   Optional scope filter limits to edges visible from that scope."
  ([to-node-id]
   (get-edges-to to-node-id nil))
  ([to-node-id scope]
   (let [base-query '[:find [(pull ?e [*]) ...]
                      :in $ ?to
                      :where [?e :kg-edge/to ?to]]
         scoped-query '[:find [(pull ?e [*]) ...]
                        :in $ ?to ?scope
                        :where
                        [?e :kg-edge/to ?to]
                        [?e :kg-edge/scope ?scope]]]
     (if scope
       (conn/query scoped-query to-node-id scope)
       (conn/query base-query to-node-id)))))

(defn get-edges-by-relation
  "Query all edges of a specific relation type.
   Optional scope filter."
  ([relation]
   (get-edges-by-relation relation nil))
  ([relation scope]
   (let [base-query '[:find [(pull ?e [*]) ...]
                      :in $ ?rel
                      :where [?e :kg-edge/relation ?rel]]
         scoped-query '[:find [(pull ?e [*]) ...]
                        :in $ ?rel ?scope
                        :where
                        [?e :kg-edge/relation ?rel]
                        [?e :kg-edge/scope ?scope]]]
     (if scope
       (conn/query scoped-query relation scope)
       (conn/query base-query relation)))))

(defn get-edges-by-scope
  "Query all edges within a specific scope.
   Returns all edges that have the given scope."
  [scope]
  (let [query '[:find [(pull ?e [*]) ...]
                :in $ ?scope
                :where
                [?e :kg-edge/scope ?scope]]]
    (conn/query query scope)))

(defn find-edge
  "Find an edge between two nodes.
   Optional relation filter only returns edge if it matches.
   Returns the edge entity map or nil if not found."
  ([from-node-id to-node-id]
   (find-edge from-node-id to-node-id nil))
  ([from-node-id to-node-id relation]
   (let [base-query '[:find [(pull ?e [*]) ...]
                      :in $ ?from ?to
                      :where
                      [?e :kg-edge/from ?from]
                      [?e :kg-edge/to ?to]]
         relation-query '[:find [(pull ?e [*]) ...]
                          :in $ ?from ?to ?rel
                          :where
                          [?e :kg-edge/from ?from]
                          [?e :kg-edge/to ?to]
                          [?e :kg-edge/relation ?rel]]
         results (if relation
                   (conn/query relation-query from-node-id to-node-id relation)
                   (conn/query base-query from-node-id to-node-id))]
     (first results))))

(defn find-edges-between
  "Find all edges where both :from and :to are within the given node-id set.
   Single Datahike query + post-filter instead of O(n²) individual queries.
   Returns a vector of edge entity maps."
  [node-id-set]
  (if (< (count node-id-set) 2)
    []
    (let [query '[:find [(pull ?e [*]) ...]
                  :in $ [?node ...]
                  :where
                  [?e :kg-edge/from ?node]]
          ;; Query: all edges originating from any node in the set
          ;; Post-filter: :to must also be in the set
          candidates (conn/query query (vec node-id-set))]
      (filterv #(contains? node-id-set (:kg-edge/to %)) candidates))))

(defn pull-edge-batch
  "Pull `edge-pull-pattern` for a batch of entity IDs.
   Drops nils (e.g. if an edge was retracted between enumeration and pull)
   and strips :db/id so callers see the same shape as the old query path."
  [eids]
  (into []
        (keep (fn [eid]
                (when-let [pulled (conn/pull-entity edge-pull-pattern eid)]
                  ;; Datahike returns an empty map on retracted eids; treat
                  ;; missing :kg-edge/id as "not an edge anymore".
                  (when (:kg-edge/id pulled)
                    (dissoc pulled :db/id)))))
        eids))

(defn get-all-edges
  "Get all edges in the KG, streamed via batched narrow pulls.

   Iterates the attribute-first index on :kg-edge/id to enumerate edge
   entity IDs without materializing values, then pulls a fixed set of
   edge attributes in batches of `:batch-size` entities. Avoids the OOM
   that `(pull ?e [*])` on 1.2M edges hit against a 4GB heap.

   Returns a lazy sequence of edge maps with :kg-edge/* keys. Callers that
   need realized collections (counting, sort-by, seq-into-vec) will naturally
   force the sequence — that's fine and expected; the savings come from
   per-batch allocation, not from deferring the whole walk.

   Options:
     :batch-size - Edges pulled per batch (default 500)

   Arities:
     (get-all-edges)                => all edges
     (get-all-edges scope)          => edges with :kg-edge/scope = scope
     (get-all-edges scope opts-map) => scope + options"
  ([]
   (get-all-edges nil nil))
  ([scope]
   (get-all-edges scope nil))
  ([scope {:keys [batch-size] :or {batch-size default-edge-batch-size}}]
   (let [batch (max 1 (long batch-size))
         eids (conn/eids-by-attr :kg-edge/id)
         batches (partition-all batch eids)
         ;; mapcat over batches keeps the outer seq lazy — one batch at
         ;; a time is materialized. Callers that do (doall all-edges) will
         ;; walk everything; callers that do (take 1000 all-edges) only
         ;; pay for 2 batches.
         all (mapcat pull-edge-batch batches)]
     (if scope
       (filter #(= scope (:kg-edge/scope %)) all)
       all))))

(defn count-edges
  "Count total edges, optionally filtered by scope.
   Uses aggregate query — does not load all edges into memory."
  ([]
   (count-edges nil))
  ([scope]
   (let [base-query '[:find (count ?e) .
                       :where [?e :kg-edge/id]]
         scoped-query '[:find (count ?e) .
                        :in $ ?scope
                        :where
                        [?e :kg-edge/id]
                        [?e :kg-edge/scope ?scope]]]
     (or (if scope
           (conn/query scoped-query scope)
           (conn/query base-query))
         0))))