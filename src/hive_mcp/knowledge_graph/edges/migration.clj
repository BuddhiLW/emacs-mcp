(ns hive-mcp.knowledge-graph.edges.migration
  "Edge scope migration. Uses `requiring-resolve` for the back-references to
   hive-mcp.knowledge-graph.edges (get-edges-by-scope, emit-stats-event!) and
   hive-mcp.knowledge-graph.edges.stats (migrate-scope! fallback) because the
   parent ns re-exports this one's public vars — a direct require would be
   circular. Runtime resolution breaks the cycle safely."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [taoensso.timbre :as log]))

(defn migrate-edge-scopes!
  "Migrate all edges from one scope to another.

   Queries edges with :kg-edge/scope = old-scope and batch-updates
   their scope to new-scope via a single conn/transact! call. After the
   transaction commits, dispatches `:kg.edges/scope-migrated` so the
   stats cache (and any future observer) updates the scope buckets.

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
  (let [get-by-scope (requiring-resolve 'hive-mcp.knowledge-graph.edges/get-edges-by-scope)
        emit!        (requiring-resolve 'hive-mcp.knowledge-graph.edges/emit-stats-event!)
        stats-mig!   (requiring-resolve 'hive-mcp.knowledge-graph.edges.stats/migrate-scope!)
        edges        (get-by-scope old-scope)
        tx-data      (vec (for [edge edges
                                :let [eid (conn/entid [:kg-edge/id (:kg-edge/id edge)])]
                                :when eid]
                            [:db/add eid :kg-edge/scope new-scope]))
        n            (count tx-data)]
    (when (seq tx-data)
      (conn/transact! tx-data)
      (when emit!
        (emit! :kg.edges/scope-migrated
               {:old-scope old-scope :new-scope new-scope :n n}
               #(when stats-mig! (stats-mig! old-scope new-scope n))))
      (log/info "Migrated" n "KG edge scopes from" old-scope "to" new-scope))
    {:migrated n
     :old-scope old-scope
     :new-scope new-scope}))
