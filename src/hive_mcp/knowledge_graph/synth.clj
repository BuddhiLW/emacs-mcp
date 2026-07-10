(ns hive-mcp.knowledge-graph.synth
  "Writer facade for :synth/* derived KG metrics produced by hive-knowledge
   graph-algos synth loops (community detection, centrality, k-core, etc).

   Schema is declared in hive-mcp.knowledge-graph.schema/synth-schema.
   :synth/node-id is the unique identity anchor — upserts are idempotent.

   Destructive-guard (axiom 20260429130205-2bdab16d):
   - This namespace ONLY transacts :synth/* attrs (not :kg-edge/*, :knowledge/*,
     or :disc/*). The synth-attr? predicate enforces that.
   - There is NO bulk-delete operation. Retract-by-attr requires explicit
     id+attr pair.
   - Schema-changing transacts are not exposed.

   Called by hive-knowledge.graph-algos.adapters.default/DatahikeKgWriter via
   requiring-resolve."
  (:require [hive-mcp.knowledge-graph.connection :as kg-conn]
            [hive-mcp.knowledge-graph.schema :as kg-schema]
            [taoensso.timbre :as log]))

(defn- guarded
  "Reject non-:synth/* attrs at this layer too (defence in depth — the writer
   side guards, but a direct caller of this ns must also be blocked)."
  [attr]
  (when-not (kg-schema/synth-attr? attr)
    (log/warn "hive-mcp.knowledge-graph.synth: refusing non-:synth/* attr"
              {:attr attr})
    (throw (ex-info "synth writer rejects non-:synth/* attr"
                    {:attr attr :guard :destructive-guard-20260429130205}))))

(defn write-attr!
  "Upsert a single :synth/<attr> on node `id`. Idempotent via :synth/node-id
   identity. Returns the transact result.

   Guards: attr must be a :synth/* keyword (not :synth/node-id itself)."
  [id attr value]
  (guarded attr)
  (kg-conn/transact! [{:synth/node-id id, attr value}]))

(defn batch-write!
  "Upsert a batch of {:id :attr :value} maps in one transaction.
   Skips entries whose attr fails the synth-attr? guard."
  [updates]
  (let [tx (into []
                 (keep (fn [{:keys [id attr value]}]
                         (when (kg-schema/synth-attr? attr)
                           {:synth/node-id id, attr value})))
                 updates)
        rejected (- (count updates) (count tx))]
    (when (pos? rejected)
      (log/warn "synth/batch-write!: rejected non-:synth/* entries"
                {:rejected rejected :total (count updates)}))
    (when (seq tx)
      (kg-conn/transact! tx))))

(defn delete-attr!
  "Retract :synth/<attr> on node `id`. Requires both id and attr."
  [id attr]
  (guarded attr)
  (kg-conn/transact! [[:db/retract [:synth/node-id id] attr]]))
