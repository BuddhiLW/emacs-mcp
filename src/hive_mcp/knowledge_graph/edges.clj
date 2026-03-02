(ns hive-mcp.knowledge-graph.edges
  "CRUD operations for Knowledge Graph edges.

   Provides functions to create, read, update, and delete edges
   between knowledge nodes (memory entries) via the IGraphStore protocol."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

(defn generate-edge-id
  "Generate a unique edge ID."
  []
  (str (random-uuid)))

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

   Returns the edge ID on success, throws on validation failure."
  [{:keys [from to relation scope confidence created-by source-type last-verified]
    :or {confidence 1.0}}]
  ;; Validate required fields
  (when (or (nil? from) (nil? to))
    (throw (ex-info "Edge requires :from and :to node IDs"
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

  (let [edge-id (generate-edge-id)
        now (java.util.Date.)
        edge-data (cond-> {:kg-edge/id edge-id
                           :kg-edge/from from
                           :kg-edge/to to
                           :kg-edge/relation relation
                           :kg-edge/confidence confidence
                           :kg-edge/created-at now
                           :kg-edge/last-verified (or last-verified now)}
                    scope (assoc :kg-edge/scope scope)
                    created-by (assoc :kg-edge/created-by created-by)
                    source-type (assoc :kg-edge/source-type source-type))]
    (conn/transact! [edge-data])
    edge-id))

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
                [?e :kg-edge/id]
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
  (when-let [edge (get-edge edge-id)]
    (let [old-confidence (or (:kg-edge/confidence edge) 1.0)
          new-confidence (-> (+ old-confidence delta)
                             (max 0.0)
                             (min 1.0))]
      (update-edge-confidence! edge-id new-confidence)
      new-confidence)))

(defn remove-edge!
  "Delete an edge by its ID.
   Returns true if edge was removed, false if not found."
  [edge-id]
  (if-let [eid (conn/entid [:kg-edge/id edge-id])]
    (do
      (conn/transact! [[:db/retractEntity eid]])
      true)
    false))

(defn remove-edges-for-node!
  "Remove all edges connected to a node (both incoming and outgoing).
   Use when deleting a memory entry to clean up its KG relationships.
   Returns the count of edges removed."
  [node-id]
  (conn/with-tx-batch
    (let [outgoing (get-edges-from node-id)
          incoming (get-edges-to node-id)
          all-edges (distinct (concat outgoing incoming))
          edge-ids (map :kg-edge/id all-edges)]
      (doseq [eid edge-ids]
        (remove-edge! eid))
      (count edge-ids))))

(defn get-all-edges
  "Get all edges in the KG. Use with caution on large graphs.
   Optional scope filter."
  ([]
   (get-all-edges nil))
  ([scope]
   (let [base-query '[:find [(pull ?e [*]) ...]
                      :where [?e :kg-edge/id]]
         scoped-query '[:find [(pull ?e [*]) ...]
                        :in $ ?scope
                        :where
                        [?e :kg-edge/id]
                        [?e :kg-edge/scope ?scope]]]
     (if scope
       (conn/query scoped-query scope)
       (conn/query base-query)))))

(defn count-edges
  "Count total edges, optionally filtered by scope."
  ([]
   (count-edges nil))
  ([scope]
   (count (get-all-edges scope))))

;; =============================================================================
;; Batch Edge Queries (N+1 elimination)
;; =============================================================================

(defn batch-get-edges-from
  "Batch query: get all outgoing edges from a collection of source node IDs.
   Uses DataScript/Datalevin/Datahike collection binding `[?from ...]` for a
   single query instead of N individual queries.

   Returns a map of {node-id -> [edges]}."
  [node-ids]
  (if (empty? node-ids)
    {}
    (let [ids-vec (vec (distinct node-ids))
          q '[:find [(pull ?e [*]) ...]
              :in $ [?from ...]
              :where [?e :kg-edge/from ?from]]
          all-edges (conn/query q ids-vec)]
      (group-by :kg-edge/from all-edges))))

(defn batch-get-edges-to
  "Batch query: get all incoming edges to a collection of target node IDs.
   Uses collection binding `[?to ...]` for a single query.

   Returns a map of {node-id -> [edges]}."
  [node-ids]
  (if (empty? node-ids)
    {}
    (let [ids-vec (vec (distinct node-ids))
          q '[:find [(pull ?e [*]) ...]
              :in $ [?to ...]
              :where [?e :kg-edge/to ?to]]
          all-edges (conn/query q ids-vec)]
      (group-by :kg-edge/to all-edges))))

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
             (if-let [existing (find-edge from-id to-id :co-accessed)]
              ;; Reinforce existing co-access edge
               (do (increment-confidence! (:kg-edge/id existing) 0.1)
                   (verify-edge! (:kg-edge/id existing))
                   :reinforced)
              ;; Create new co-access edge with low initial confidence
               (do (add-edge! (cond-> {:from from-id
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
  (let [outgoing (get-edges-from entry-id)
        incoming (get-edges-to entry-id)
        co-access-edges (->> (concat outgoing incoming)
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

(defn edge-stats
  "Get statistics about edges in the Knowledge Graph.

   Returns:
     {:total-edges  <n>
      :by-relation  {<relation-kw> <count>}
      :by-scope     {<scope-string> <count>}}"
  []
  (let [all-edges (get-all-edges)
        by-relation (frequencies (map :kg-edge/relation all-edges))
        by-scope (frequencies (keep :kg-edge/scope all-edges))]
    {:total-edges (count all-edges)
     :by-relation by-relation
     :by-scope by-scope}))

;; =============================================================================
;; Edge Scope Migration
;; =============================================================================

(defn migrate-edge-scopes!
  "Migrate all edges from one scope to another.

   Queries edges with :kg-edge/scope = old-scope and batch-updates
   their scope to new-scope via a single conn/transact! call.

   Arguments:
     old-scope - The scope string to migrate from
     new-scope - The scope string to migrate to

   Returns:
     {:migrated <count of edges updated>
      :old-scope old-scope
      :new-scope new-scope}

   Idempotent: calling when no old-scope edges exist returns {:migrated 0}.
   Throws on nil or same old/new scope."
  [old-scope new-scope]
  (when (or (nil? old-scope) (nil? new-scope))
    (throw (ex-info "migrate-edge-scopes! requires old-scope and new-scope"
                    {:old-scope old-scope :new-scope new-scope})))
  (when (= old-scope new-scope)
    (throw (ex-info "old-scope and new-scope must be different"
                    {:old-scope old-scope :new-scope new-scope})))
  (let [edges (get-edges-by-scope old-scope)
        tx-data (vec (for [edge edges
                           :let [eid (conn/entid [:kg-edge/id (:kg-edge/id edge)])]
                           :when eid]
                       [:db/add eid :kg-edge/scope new-scope]))]
    (when (seq tx-data)
      (conn/transact! tx-data)
      (log/info "Migrated" (count tx-data) "KG edge scopes from" old-scope "to" new-scope))
    {:migrated (count tx-data)
     :old-scope old-scope
     :new-scope new-scope}))

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
  (or (some? (find-edge from-id to-id :depends-on))
      (some? (find-edge to-id from-id :depends-on))))

(defn promote-co-access-edges!
  "Promote high-confidence co-access edges to :depends-on semantic edges.

   Scans co-access edges above the confidence threshold and creates
   :depends-on edges for pairs that don't already have one.

   Options:
     :threshold  - Minimum confidence for promotion (default: 0.7)
     :confidence - Confidence for new :depends-on edges (default: 0.5)
     :limit      - Max edges to evaluate (default: 20)
     :scope      - Optional scope filter for co-access edges
     :created-by - Agent ID for attribution

   Returns:
     {:promoted   <count of new :depends-on edges created>
      :skipped    <count of edges already having :depends-on>
      :below      <count of edges below threshold>
      :evaluated  <count of co-access edges checked>}

   Idempotent: calling multiple times with same state produces same result.
   Non-blocking: errors on individual edges don't stop the cycle."
  [& [{:keys [threshold confidence limit scope created-by]
       :or {threshold  default-promotion-threshold
            confidence default-promoted-confidence
            limit      default-promotion-limit}}]]
  (conn/with-tx-batch
    (let [;; Query co-access edges, optionally filtered by scope
          co-access-edges (get-edges-by-relation :co-accessed scope)
         ;; Sort by confidence descending to promote highest-confidence first
          sorted-edges (->> co-access-edges
                            (sort-by #(or (:kg-edge/confidence %) 0.0) >)
                            (take limit))
         ;; Track promotion results
          results (reduce
                   (fn [acc edge]
                     (let [v (r/guard Exception nil
                                      (if-not (co-access-edge-promotable? edge threshold)
                                        :below
                                        (let [from-id (:kg-edge/from edge)
                                              to-id (:kg-edge/to edge)]
                                          (if (depends-on-exists? from-id to-id)
                                            :skipped
                                            (do
                                              (add-edge! (cond-> {:from from-id
                                                                  :to to-id
                                                                  :relation :depends-on
                                                                  :confidence confidence
                                                                  :source-type :inferred}
                                                           scope (assoc :scope scope)
                                                           created-by (assoc :created-by created-by)))
                                              :promoted)))))]
                       (case v
                         :below (update acc :below inc)
                         :skipped (update acc :skipped inc)
                         :promoted (update acc :promoted inc)
                         (do (when-let [err (::r/error (meta v))]
                               (log/debug "Co-access promotion failed for edge"
                                          (:kg-edge/id edge) ":" (:message err)))
                             (update acc :errors inc)))))
                   {:promoted 0 :skipped 0 :below 0 :errors 0}
                   sorted-edges)]
      (when (pos? (:promoted results))
        (log/info "Promoted" (:promoted results) "co-access edges to :depends-on"))
      (assoc results :evaluated (count sorted-edges)))))

;; =============================================================================
;; Edge Confidence Decay for Unverified Edges (P2.9)
;; =============================================================================

(def ^:const default-decay-staleness-days
  "Minimum days since last-verified before an edge is considered stale.
   Edges verified within this window are untouched."
  30)

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

(def ^:const default-decay-limit
  "Maximum edges to evaluate per decay cycle.
   Bounded to prevent long-running cycles on large graphs."
  100)

(defn- edge-stale?
  "Check if an edge's last-verified timestamp is older than staleness-days.

   An edge is stale when:
   1. It has a :kg-edge/last-verified timestamp
   2. That timestamp is older than staleness-days ago

   Edges without :last-verified are considered stale (they were never verified).

   Pure predicate — no side effects."
  [edge staleness-days now-millis]
  (let [last-verified (:kg-edge/last-verified edge)]
    (if (nil? last-verified)
      true ;; Never verified = stale
      (let [verified-millis (if (instance? java.util.Date last-verified)
                              (.getTime ^java.util.Date last-verified)
                              0)
            staleness-millis (* staleness-days 24 60 60 1000)]
        (> (- now-millis verified-millis) staleness-millis)))))

(defn- decay-rate-for-edge
  "Return the decay rate for an edge based on its relation type.

   Co-access edges decay at co-access-decay-rate (faster).
   All other edges (semantic) decay at semantic-decay-rate (slower).

   Pure function — no side effects."
  [edge]
  (if (= :co-accessed (:kg-edge/relation edge))
    co-access-decay-rate
    semantic-decay-rate))

(defn decay-unverified-edges!
  "Decay confidence of edges not verified within the staleness window.

   Scans all edges (optionally filtered by scope), finds those with
   :last-verified older than staleness-days, and reduces their confidence
   by the appropriate decay rate. Edges that fall below prune-threshold
   are removed entirely.

   Options:
     :staleness-days - Days before edge is considered stale (default: 30)
     :limit          - Max edges to evaluate (default: 100)
     :scope          - Optional scope filter
     :created-by     - Agent ID for attribution in logs

   Returns:
     {:decayed  <count of edges with reduced confidence>
      :pruned   <count of edges removed (below threshold)>
      :fresh    <count of edges still within staleness window>
      :evaluated <count of edges checked>}

   Idempotent: calling multiple times reduces confidence incrementally.
   Non-blocking: errors on individual edges don't stop the cycle."
  [& [{:keys [staleness-days limit scope created-by]
       :or {staleness-days default-decay-staleness-days
            limit          default-decay-limit}}]]
  (conn/with-tx-batch
    (let [;; Query all edges, optionally filtered by scope
          all-edges (if scope
                      (get-edges-by-scope scope)
                      (get-all-edges))
          now-millis (System/currentTimeMillis)
         ;; Sort by last-verified ascending (oldest first = most stale first)
          sorted-edges (->> all-edges
                            (sort-by (fn [e]
                                       (if-let [lv (:kg-edge/last-verified e)]
                                         (if (instance? java.util.Date lv)
                                           (.getTime ^java.util.Date lv)
                                           0)
                                         0)))
                            (take limit))
         ;; Process each edge
          results (reduce
                   (fn [acc edge]
                     (let [v (r/guard Exception nil
                                      (if-not (edge-stale? edge staleness-days now-millis)
                                        :fresh
                                        (let [edge-id (:kg-edge/id edge)
                                              rate (decay-rate-for-edge edge)
                                              old-confidence (or (:kg-edge/confidence edge) 1.0)
                                              new-confidence (- old-confidence rate)]
                                          (if (< new-confidence prune-threshold)
                                            (do
                                              (remove-edge! edge-id)
                                              (log/debug "Pruned stale edge" edge-id
                                                         "confidence:" old-confidence "->" new-confidence
                                                         "relation:" (:kg-edge/relation edge))
                                              :pruned)
                                            (do
                                              (update-edge-confidence! edge-id new-confidence)
                                              :decayed)))))]
                       (case v
                         :fresh (update acc :fresh inc)
                         :pruned (update acc :pruned inc)
                         :decayed (update acc :decayed inc)
                         (do (when-let [err (::r/error (meta v))]
                               (log/debug "Edge decay failed for edge"
                                          (:kg-edge/id edge) ":" (:message err)))
                             (update acc :errors inc)))))
                   {:decayed 0 :pruned 0 :fresh 0 :errors 0}
                   sorted-edges)]
      (when (or (pos? (:decayed results)) (pos? (:pruned results)))
        (log/info "Edge decay:" (:decayed results) "decayed,"
                  (:pruned results) "pruned"
                  (when created-by (str " by:" created-by))))
      (assoc results :evaluated (count sorted-edges)))))
