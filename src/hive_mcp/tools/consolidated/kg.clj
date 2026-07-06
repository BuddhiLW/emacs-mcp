(ns hive-mcp.tools.consolidated.kg
  "Consolidated Knowledge Graph CLI tool."
  (:require [hive-mcp.batch.cli-adapter :as bca]
            [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.kg :as kg-handlers]
            [hive-mcp.tools.kg.batch :as kg-batch]))

(def handle-batch-edge
  "Batch edge creation via the `Batchable` protocol (decision
   20260429230453-7e7627cc). Returns the legacy `{:results :summary}`
   envelope so existing CLI callers stay compatible."
  (bca/cli-batch-handler {:run-fn kg-batch/run-batch
                          :cmd-kw :edge}))

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
                                          :enum ["implements" "supersedes" "refines" "contradicts" "depends-on" "derived-from" "applies-to" "projects-to" "co-accessed"]
                                          :description "Relation type for edge (server validates against kg-schema/relation-types; addons may register more)"}
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
                              "threshold" {:type "number"
                                           :description "cleanup-synthetics: live-ratio below which to act (default: 0.2)"}
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
