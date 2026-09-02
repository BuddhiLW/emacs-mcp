(ns hive-mcp.tools.consolidated.kg
  "Consolidated Knowledge Graph CLI tool."
  (:require [hive-mcp.batch.cli-adapter :as bca]
            [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.kg :as kg-handlers]
            [hive-mcp.tools.kg.batch :as kg-batch]))

(def handle-batch-edge
  "Batch edge creation. Every op in a batch-edge call is an `edge` op by
   construction, so this goes straight to the bulk writer: ONE datahike
   transaction and ONE flush for the whole batch, instead of the generic
   runner's N of each.

   Returns the same `{:results :summary}` envelope as before (decision
   20260429230453-7e7627cc)."
  kg-handlers/handle-kg-add-edges)

(def handle-batch-traverse
  "Batch traversal via the `Batchable` protocol."
  (bca/cli-batch-handler {:run-fn kg-batch/run-batch
                          :cmd-kw :traverse}))

(def handlers
  {:traverse           kg-handlers/handle-kg-traverse
   :edge               kg-handlers/handle-kg-add-edge
   :impact             kg-handlers/handle-kg-impact-analysis
   :subgraph           kg-handlers/handle-kg-subgraph
   :stats              kg-handlers/handle-kg-stats
   :path               kg-handlers/handle-kg-find-path
   :context            kg-handlers/handle-kg-node-context
   :promote            kg-handlers/handle-kg-promote
   :reground           kg-handlers/handle-kg-reground
   :cleanup-synthetics kg-handlers/handle-kg-cleanup-synthetics
   :batch-edge         handle-batch-edge
   :batch-traverse     handle-batch-traverse})

(def ^:private coerce-schema
  "MCP boundary coercion — string params to declared types."
  {:max_depth   [:int]
   :confidence  [:double]
   :direction   [:enum #{:outgoing :incoming :both}]
   :force       [:boolean]
   :parallel    [:boolean]
   :relations   [:vec]
   :threshold   [:double]
   :limit       [:int]
   :dry_run     [:boolean]})

(def handle-kg
  (make-cli-handler handlers coerce-schema))

(def tool-def
  {:name "kg"
   :consolidated true
   :description "Knowledge Graph operations: traverse (walk graph), edge (add relationship), impact (find dependents), subgraph (extract scope), stats (counts), path (shortest path), context (node details), promote (bubble up scope), reground (verify source), cleanup-synthetics (prune dead synthetic patterns). Batch: batch-edge (multiple edges), batch-traverse (multiple traversals). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["traverse" "edge" "impact" "subgraph" "stats" "path" "context" "promote" "reground" "cleanup-synthetics" "batch-edge" "batch-traverse" "help"]
                                         :description "KG operation to perform"}
                              "start_node" {:type "string"
                                            :description "Node ID to start traversal from"}
                              "direction" {:type "string"
                                           :enum ["outgoing" "incoming" "both"]
                                           :description "Edge direction for traversal"}
                              "max_depth" {:type "integer"
                                           :description "Maximum traversal/search depth"}
                              "relations" {:type "array"
                                           :items {:type "string"}
                                           :description "Relation types to follow"}
                              "scope" {:type "string"
                                       :description "Scope for filtering"}
                              "from" {:type "string"
                                      :description "Source node ID for edge"}
                              "to" {:type "string"
                                    :description "Target node ID for edge"}
                              "relation" {:type "string"
                                          :enum ["implements" "supersedes" "refines" "contradicts" "depends-on" "derived-from" "applies-to" "relates"]
                                          :description (str "Relation type for edge. `relates` is the OPEN relation: pair it with `predicate` "
                                                            "for arbitrary semantics. Server validates against kg-schema/relation-types; "
                                                            "addons may register more.")}
                              "predicate" {:type "string"
                                           :description (str "[edge] Free-text semantic predicate for an OPEN `relates` edge "
                                                             "(e.g. \"causes\", \"part-of\", \"motivates\"). Use with relation=\"relates\"; "
                                                             "normalized to kebab-case; ignored for structural relations.")}
                              "confidence" {:type "number"
                                            :description "Confidence score 0.0-1.0"}
                              "node_id" {:type "string"
                                         :description "Node ID for impact/context analysis"}
                              "from_node" {:type "string"
                                           :description "Source node for path finding"}
                              "to_node" {:type "string"
                                         :description "Target node for path finding"}
                              "edge_id" {:type "string"
                                         :description "Edge ID to promote"}
                              "to_scope" {:type "string"
                                          :description "Target scope for promotion"}
                              "entry_id" {:type "string"
                                          :description "Entry ID to reground"}
                              "force" {:type "boolean"
                                       :description "Force reground even if recent"}
                              "operations" {:type "array"
                                            :items {:type "object"}
                                            :description "Array of {command, ...} objects for batch-edge/batch-traverse. Each op needs its own :command ('edge' or 'traverse') plus per-op params."}
                              "parallel" {:type "boolean"
                                          :description "Run batch operations in parallel (default: false)"}
                              "min_live_members" {:type "integer"
                                                  :description "cleanup-synthetics: act when fewer than this many targets are still live (default: 2)"}
                              "threshold" {:type "number"
                                           :description "cleanup-synthetics: additional live-ratio criterion; act below it (default: 0.2)"}
                              "action" {:type "string"
                                        :enum ["delete" "demote"]
                                        :description "cleanup-synthetics: action on sub-threshold synthetics (default: delete)"}
                              "limit" {:type "integer"
                                       :description "cleanup-synthetics: max synthetics per cycle (default: 50)"}
                              "dry_run" {:type "boolean"
                                         :description "cleanup-synthetics: preview without mutating (default: false)"}}
                 :required ["command"]}
   :handler handle-kg})

(def tools [tool-def])
