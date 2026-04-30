(ns hive-mcp.tools.kg-test
  "Tests for KG tool CQRS decomposition.

   Verifies:
   1. Facade re-exports all public handler vars
   2. Query handlers are in kg.queries namespace
   3. Command handlers are in kg.commands namespace
   4. Consolidated CLI facade still resolves all commands
   5. Tool definitions are complete and consistent
   6. Validation helpers work correctly"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set]
            [hive-mcp.tools.kg :as kg]
            [hive-mcp.tools.kg.queries :as kg-queries]
            [hive-mcp.tools.kg.commands :as kg-commands]
            [hive-mcp.tools.consolidated.kg :as consolidated-kg]))

;;; =============================================================================
;;; Helpers
;;; =============================================================================

(defn- error-response?
  "Check if a handler response indicates an error.
   Handlers may return {:error ...} from validate-* or {:isError true} from mcp-error."
  [result]
  (or (some? (:error result))
      (:isError result)))

;;; =============================================================================
;;; Facade Re-export Tests
;;; =============================================================================

(deftest facade-re-exports-query-handlers
  (testing "Facade re-exports all query handler vars"
    (is (fn? kg/handle-kg-traverse)        "traverse re-exported")
    (is (fn? kg/handle-kg-impact-analysis) "impact-analysis re-exported")
    (is (fn? kg/handle-kg-find-path)       "find-path re-exported")
    (is (fn? kg/handle-kg-subgraph)        "subgraph re-exported")
    (is (fn? kg/handle-kg-contradictions)  "contradictions re-exported")
    (is (fn? kg/handle-kg-node-context)    "node-context re-exported")
    (is (fn? kg/handle-kg-stats)           "stats re-exported")))

(deftest facade-re-exports-command-handlers
  (testing "Facade re-exports all command handler vars"
    (is (fn? kg/handle-kg-add-edge)            "add-edge re-exported")
    (is (fn? kg/handle-kg-promote)             "promote re-exported")
    (is (fn? kg/handle-kg-reground)            "reground re-exported")
    (is (fn? kg/handle-kg-backfill-grounding)  "backfill-grounding re-exported")
    (is (fn? kg/handle-kg-cleanup-synthetics)  "cleanup-synthetics re-exported")))

(deftest facade-re-exports-point-to-sub-namespaces
  (testing "Facade vars delegate to sub-namespace implementations"
    ;; Query handlers should be the same fn objects as in kg.queries
    (is (= kg/handle-kg-traverse        kg-queries/handle-kg-traverse))
    (is (= kg/handle-kg-impact-analysis kg-queries/handle-kg-impact-analysis))
    (is (= kg/handle-kg-find-path       kg-queries/handle-kg-find-path))
    (is (= kg/handle-kg-subgraph        kg-queries/handle-kg-subgraph))
    (is (= kg/handle-kg-contradictions  kg-queries/handle-kg-contradictions))
    (is (= kg/handle-kg-node-context    kg-queries/handle-kg-node-context))
    (is (= kg/handle-kg-stats           kg-queries/handle-kg-stats))
    ;; Command handlers should be the same fn objects as in kg.commands
    (is (= kg/handle-kg-add-edge            kg-commands/handle-kg-add-edge))
    (is (= kg/handle-kg-promote             kg-commands/handle-kg-promote))
    (is (= kg/handle-kg-reground            kg-commands/handle-kg-reground))
    (is (= kg/handle-kg-backfill-grounding  kg-commands/handle-kg-backfill-grounding))
    (is (= kg/handle-kg-cleanup-synthetics  kg-commands/handle-kg-cleanup-synthetics))))

;;; =============================================================================
;;; CQRS Separation Tests
;;; =============================================================================

(deftest queries-namespace-contains-only-reads
  (testing "kg.queries namespace has only read-only handlers"
    (let [query-names (set (map :name kg-queries/query-tools))]
      (is (contains? query-names "kg_traverse"))
      (is (contains? query-names "kg_impact_analysis"))
      (is (contains? query-names "kg_find_path"))
      (is (contains? query-names "kg_subgraph"))
      (is (contains? query-names "kg_contradictions"))
      (is (contains? query-names "kg_node_context"))
      (is (contains? query-names "kg_stats"))
      (is (= 7 (count query-names)) "exactly 7 query tools"))))

(deftest commands-namespace-contains-only-writes
  (testing "kg.commands namespace has only write/mutate handlers"
    (let [command-names (set (map :name kg-commands/command-tools))]
      (is (contains? command-names "kg_add_edge"))
      (is (contains? command-names "kg_promote"))
      (is (contains? command-names "kg_reground"))
      (is (contains? command-names "kg_backfill_grounding"))
      (is (contains? command-names "kg_cleanup_synthetics"))
      (is (= 5 (count command-names)) "exactly 5 command tools"))))

(deftest no-overlap-between-queries-and-commands
  (testing "No tool appears in both queries and commands"
    (let [query-names   (set (map :name kg-queries/query-tools))
          command-names (set (map :name kg-commands/command-tools))
          overlap       (clojure.set/intersection query-names command-names)]
      (is (empty? overlap) (str "Overlap found: " overlap)))))

;;; =============================================================================
;;; Tool Definition Completeness Tests
;;; =============================================================================

(deftest tools-merged-correctly
  (testing "Facade tools var merges queries + commands"
    (let [facade-names (set (map :name kg/tools))
          query-names  (set (map :name kg-queries/query-tools))
          command-names (set (map :name kg-commands/command-tools))]
      (is (= facade-names (clojure.set/union query-names command-names))
          "facade tools = queries ∪ commands")
      (is (= 12 (count kg/tools)) "12 core tools total (7 queries + 5 commands)"))))

(deftest all-tools-includes-versioning-and-migration
  (testing "all-tools includes core + versioning + migration"
    (let [all-names (set (map :name kg/all-tools))]
      ;; Core (11)
      (is (contains? all-names "kg_traverse"))
      (is (contains? all-names "kg_add_edge"))
      ;; Versioning (7)
      (is (contains? all-names "kg_branch"))
      (is (contains? all-names "kg_checkout"))
      (is (contains? all-names "kg_branches"))
      (is (contains? all-names "kg_snapshot_id"))
      (is (contains? all-names "kg_history"))
      (is (contains? all-names "kg_merge"))
      (is (contains? all-names "kg_versioning_status"))
      ;; Migration (4)
      (is (contains? all-names "kg_migrate"))
      (is (contains? all-names "kg_export"))
      (is (contains? all-names "kg_import"))
      (is (contains? all-names "kg_validate_migration"))
      ;; Total
      (is (= 23 (count kg/all-tools)) "23 total tools (12 core + 7 versioning + 4 migration)"))))

(deftest every-tool-has-handler
  (testing "Every tool definition has a :handler function"
    (doseq [tool kg/all-tools]
      (is (fn? (:handler tool))
          (str "Tool " (:name tool) " missing :handler")))))

(deftest every-tool-has-input-schema
  (testing "Every tool definition has an :inputSchema"
    (doseq [tool kg/all-tools]
      (is (map? (:inputSchema tool))
          (str "Tool " (:name tool) " missing :inputSchema"))
      (is (= "object" (get-in tool [:inputSchema :type]))
          (str "Tool " (:name tool) " schema type must be 'object'")))))

;;; =============================================================================
;;; Consolidated CLI Tests
;;; =============================================================================

(deftest consolidated-handlers-map-complete
  (testing "Consolidated KG handler map wires all commands"
    (let [commands (set (keys consolidated-kg/handlers))]
      (is (contains? commands :traverse))
      (is (contains? commands :edge))
      (is (contains? commands :impact))
      (is (contains? commands :subgraph))
      (is (contains? commands :stats))
      (is (contains? commands :path))
      (is (contains? commands :context))
      (is (contains? commands :promote))
      (is (contains? commands :reground))
      (is (contains? commands :cleanup-synthetics))
      (is (contains? commands :batch-edge))
      (is (contains? commands :batch-traverse))
      (is (= 12 (count commands)) "12 consolidated commands"))))

(deftest consolidated-handlers-resolve-correctly
  (testing "Consolidated handler map resolves to sub-namespace fns"
    ;; Queries
    (is (= (:traverse consolidated-kg/handlers) kg-queries/handle-kg-traverse))
    (is (= (:impact consolidated-kg/handlers)   kg-queries/handle-kg-impact-analysis))
    (is (= (:path consolidated-kg/handlers)     kg-queries/handle-kg-find-path))
    (is (= (:subgraph consolidated-kg/handlers) kg-queries/handle-kg-subgraph))
    (is (= (:context consolidated-kg/handlers)  kg-queries/handle-kg-node-context))
    (is (= (:stats consolidated-kg/handlers)    kg-queries/handle-kg-stats))
    ;; Commands
    (is (= (:edge consolidated-kg/handlers)     kg-commands/handle-kg-add-edge))
    (is (= (:promote consolidated-kg/handlers)  kg-commands/handle-kg-promote))
    (is (= (:reground consolidated-kg/handlers) kg-commands/handle-kg-reground))))

;;; =============================================================================
;;; Validation Helper Tests
;;; =============================================================================

(deftest validate-node-id-tests
  (testing "validate-node-id returns error for invalid inputs"
    (is (some? (kg-queries/validate-node-id nil "test"))    "nil is invalid")
    (is (some? (kg-queries/validate-node-id "" "test"))     "empty is invalid")
    (is (some? (kg-queries/validate-node-id 123 "test"))    "non-string is invalid"))
  (testing "validate-node-id returns nil for valid inputs"
    (is (nil? (kg-queries/validate-node-id "node-1" "test")) "non-empty string is valid")))

(deftest parse-relations-filter-tests
  (testing "parse-relations-filter handles various inputs"
    (is (nil? (kg-queries/parse-relations-filter nil))             "nil → nil")
    (is (= #{:implements} (kg-queries/parse-relations-filter "implements"))
        "string → singleton set")
    (is (= #{:refines :supersedes}
           (kg-queries/parse-relations-filter ["refines" "supersedes"]))
        "vector → keyword set")
    (is (= #{:depends-on} (kg-queries/parse-relations-filter #{:depends-on}))
        "set passthrough")))

;;; =============================================================================
;;; Handler Smoke Tests (input validation only, no backend needed)
;;; =============================================================================

(deftest query-handlers-validate-inputs
  (testing "Query handlers reject missing required params"
    ;; traverse requires start_node
    (let [result (kg-queries/handle-kg-traverse {})]
      (is (error-response? result) "traverse rejects nil start_node"))
    ;; impact requires node_id
    (let [result (kg-queries/handle-kg-impact-analysis {})]
      (is (error-response? result) "impact rejects nil node_id"))
    ;; find-path requires from_node and to_node
    (let [result (kg-queries/handle-kg-find-path {})]
      (is (error-response? result) "find-path rejects nil from_node"))
    ;; subgraph requires scope
    (let [result (kg-queries/handle-kg-subgraph {})]
      (is (error-response? result) "subgraph rejects nil scope"))
    ;; node-context requires node_id
    (let [result (kg-queries/handle-kg-node-context {})]
      (is (error-response? result) "node-context rejects nil node_id"))))

(deftest command-handlers-validate-inputs
  (testing "Command handlers reject missing required params"
    ;; add-edge requires from, to, relation
    (let [result (kg-commands/handle-kg-add-edge {})]
      (is (error-response? result) "add-edge rejects nil from"))
    ;; promote requires edge_id and to_scope
    (let [result (kg-commands/handle-kg-promote {})]
      (is (error-response? result) "promote rejects nil edge_id"))
    ;; reground requires entry_id
    (let [result (kg-commands/handle-kg-reground {})]
      (is (error-response? result) "reground rejects nil entry_id"))))
