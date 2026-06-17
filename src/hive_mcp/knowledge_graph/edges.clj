(ns hive-mcp.knowledge-graph.edges
  "CRUD operations for Knowledge Graph edges.

   Provides functions to create, read, update, and delete edges
   between knowledge nodes (memory entries) via the IGraphStore protocol."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edge-cycle :as edge-cycle]
            [hive-mcp.knowledge-graph.protocols :as p]
            [hive-mcp.knowledge-graph.schema :as schema]
            [taoensso.timbre :as log]
            [hive-mcp.knowledge-graph.edges.batch :as batch]
            [hive-mcp.knowledge-graph.edges.migration :as migration]
            [hive-mcp.knowledge-graph.edges.queries :as queries]
            [hive-mcp.events.core :as events]
            [hive-mcp.knowledge-graph.edges.stats :as stats]
            [hive-mcp.knowledge-graph.edges.stats-events]
            [hive-mcp.knowledge-graph.edges.decay :as decay]))

(declare semantic-decay-rate edge-stale? decay-step! prune-threshold remove-edge! decay-rate-for-edge)

(declare get-edge get-edges-from get-edges-to get-edges-by-relation get-edges-by-scope find-edge find-edges-between pull-edge-batch get-all-edges count-edges)

(declare migrate-edge-scopes!)

(declare batch-get-edges-from batch-get-edges-to batch-get-co-accessed)

(defn generate-edge-id
  "Generate a unique edge ID."
  []
  (str (random-uuid)))

;; =============================================================================
;; Edge Stats — kg.edges/* event facade
;; =============================================================================
;;
;; The actual cache + delta logic lives in `edges.stats`; the hive-events
;; handlers that drive it live in `edges.stats-events`. CRUD here only knows
;; how to dispatch the events — the Stage 2 decoupling of CRUD from metrics.
;; Public read API (refresh-stats!, reset-stats-cache!, edge-stats) is
;; re-exported below for backwards compatibility.

(def refresh-stats!
  "Re-export of `edges.stats/refresh!` for backwards compatibility."
  stats/refresh!)

(def reset-stats-cache!
  "Re-export of `edges.stats/reset-cache!` for backwards compatibility
   (test fixtures rebind the cache by calling this)."
  stats/reset-cache!)

(defn- emit-stats-event!
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
    (emit-stats-event! :kg.edges/added
                       {:relation relation :scope scope}
                       #(stats/apply-delta! relation scope 1))
    edge-id))

(defn get-edges-since
  "Query edges created since a given instant. For session-scoped wrap harvest.

   Args:
     since-instant - java.time.Instant (session start time)

   Options:
     :scope - Filter by project scope
     :limit - Max edges to return (default 200)
     :exclude-relations - Set of relation keywords to skip (e.g. #{:co-accessed})

   Returns:
     Seq of edge maps sorted by created-at (chronological)"
  [since-instant & {:keys [scope limit exclude-relations]
                    :or {limit 200 exclude-relations #{:co-accessed}}}]
  (let [since-date (java.util.Date/from since-instant)
        base-query '[:find [(pull ?e [*]) ...]
                     :in $ ?since
                     :where
                     [?e :kg-edge/id]
                     [?e :kg-edge/created-at ?t]
                     [(<= ?since ?t)]]
        scoped-query '[:find [(pull ?e [*]) ...]
                       :in $ ?since ?scope
                       :where
                       [?e :kg-edge/id]
                       [?e :kg-edge/created-at ?t]
                       [(<= ?since ?t)]
                       [?e :kg-edge/scope ?scope]]
        raw (if scope
              (conn/query scoped-query since-date scope)
              (conn/query base-query since-date))
        filtered (cond->> raw
                   (seq exclude-relations)
                   (remove #(contains? exclude-relations
                                       (keyword (:kg-edge/relation %)))))]
    (->> filtered
         (sort-by :kg-edge/created-at)
         (take limit)
         (map #(dissoc % :db/id)))))

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

(defn remove-edges-for-node!
  "Remove all edges connected to a node (both incoming and outgoing).
   Use when deleting a memory entry to clean up its KG relationships.
   Returns the count of edges removed."
  [node-id]
  (conn/with-tx-batch
    (let [outgoing (get-edges-from node-id)
          incoming (get-edges-to node-id)
          all-edges (distinct (into outgoing incoming))
          edge-ids (map :kg-edge/id all-edges)]
      (doseq [eid edge-ids]
        (remove-edge! eid))
      (count edge-ids))))

;; =============================================================================
;; Batch Edge Queries (N+1 elimination)
;; =============================================================================

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

(def edge-stats
  "Statistics about edges in the Knowledge Graph.

   Reads from the in-memory cache maintained by the kg.edges/* event
   handlers in `edges.stats-events`. Lazy-initialized on first call via
   full DB scan. Call `refresh-stats!` to rebuild after bulk operations
   that bypass the CRUD event path.

   Returns:
     {:total-edges <n>
      :by-relation {<relation-kw> <count>}
      :by-scope    {<scope-string> <count>}}"
  stats/snapshot)

;; =============================================================================
;; Edge Scope Migration
;; =============================================================================

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

(defn- promote-step!
  "Per-edge step for co-access promotion. Returns outcome keyword."
  [{:keys [threshold confidence scope created-by]} edge]
  (if-not (co-access-edge-promotable? edge threshold)
    :below
    (let [from-id (:kg-edge/from edge)
          to-id   (:kg-edge/to edge)]
      (if (depends-on-exists? from-id to-id)
        :skipped
        (do (add-edge! (cond-> {:from from-id
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
     {:fetch        #(get-edges-by-relation :co-accessed scope)
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

;; =============================================================================
;; Edge Confidence Decay for Unverified Edges (P2.9)
;; =============================================================================

(def ^:const default-decay-staleness-days
  "Minimum days since last-verified before an edge is considered stale.
   Edges verified within this window are untouched."
  30)

(def ^:const default-decay-limit
  "Maximum edges to evaluate per decay cycle.
   Bounded to prevent long-running cycles on large graphs."
  100)

(defn- last-verified-millis
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
       {:fetch        #(if scope (get-edges-by-scope scope) (get-all-edges))
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

;; =============================================================================
;; IEdgeReader default implementation (ISP read-side)
;; =============================================================================
;;
;; Purely additive: delegates every protocol arity to the plain fns defined
;; above. Callers that want the contractual read-side interface can depend on
;; `p/IEdgeReader` and inject this `default-reader` (or a stub in tests).

(def default-reader
  "Process-wide IEdgeReader delegating to the plain fns in this ns."
  (reify p/IEdgeReader
    (get-edges-from [_ id] (get-edges-from id))
    (get-edges-from [_ id scope] (get-edges-from id scope))
    (get-edges-to [_ id] (get-edges-to id))
    (get-edges-to [_ id scope] (get-edges-to id scope))
    (batch-get-edges-from [_ ids] (batch-get-edges-from ids))
    (batch-get-edges-from [_ ids scope] (batch-get-edges-from ids scope))
    (batch-get-edges-to [_ ids] (batch-get-edges-to ids))
    (batch-get-edges-to [_ ids scope] (batch-get-edges-to ids scope))))

(def batch-get-edges-from hive-mcp.knowledge-graph.edges.batch/batch-get-edges-from)

(def batch-get-edges-to hive-mcp.knowledge-graph.edges.batch/batch-get-edges-to)

(def batch-get-edges-from-with-db
  hive-mcp.knowledge-graph.edges.batch/batch-get-edges-from-with-db)

(def batch-get-edges-to-with-db
  hive-mcp.knowledge-graph.edges.batch/batch-get-edges-to-with-db)

(def batch-get-co-accessed hive-mcp.knowledge-graph.edges.batch/batch-get-co-accessed)

(def migrate-edge-scopes! hive-mcp.knowledge-graph.edges.migration/migrate-edge-scopes!)

(def get-edge hive-mcp.knowledge-graph.edges.queries/get-edge)

(def get-edges-from hive-mcp.knowledge-graph.edges.queries/get-edges-from)

(def get-edges-to hive-mcp.knowledge-graph.edges.queries/get-edges-to)

(def get-edges-by-relation hive-mcp.knowledge-graph.edges.queries/get-edges-by-relation)

(def get-edges-by-scope hive-mcp.knowledge-graph.edges.queries/get-edges-by-scope)

(def find-edge hive-mcp.knowledge-graph.edges.queries/find-edge)

(def find-edges-between hive-mcp.knowledge-graph.edges.queries/find-edges-between)

(def ^:private pull-edge-batch hive-mcp.knowledge-graph.edges.queries/pull-edge-batch)

(def get-all-edges hive-mcp.knowledge-graph.edges.queries/get-all-edges)

(def count-edges hive-mcp.knowledge-graph.edges.queries/count-edges)

(def co-access-decay-rate hive-mcp.knowledge-graph.edges.decay/co-access-decay-rate)

(def semantic-decay-rate hive-mcp.knowledge-graph.edges.decay/semantic-decay-rate)

(def ^:private edge-stale? hive-mcp.knowledge-graph.edges.decay/edge-stale?)

(def ^:private decay-step! hive-mcp.knowledge-graph.edges.decay/decay-step!)

(def prune-threshold hive-mcp.knowledge-graph.edges.decay/prune-threshold)

(def remove-edge! hive-mcp.knowledge-graph.edges.decay/remove-edge!)

(def ^:private decay-rate-for-edge hive-mcp.knowledge-graph.edges.decay/decay-rate-for-edge)

;; =============================================================================
;; Graph-Algos Node Facade (GAV2)
;; =============================================================================
;;
;; Resolved by hive-knowledge.graph-algos.adapters.default/DatahikeKgReader
;; via requiring-resolve — the fn names below are load-bearing across repos:
;;   get-all-node-ids | node-ids-by-tag | neighbors

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
   (let [out #(map :kg-edge/to (get-edges-from node-id))
         in  #(map :kg-edge/from (get-edges-to node-id))
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
