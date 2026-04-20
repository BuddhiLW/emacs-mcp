(ns clj-kondo-mcp.tools
  "MCP tool definitions for clj-kondo analysis.
   Uses the minimal MCP DSL from clj-kondo-mcp.mcp."
  (:require [clj-kondo-mcp.mcp :refer [deftool defparam]]
            [clj-kondo-mcp.core :as core]))

;; Tool definitions using the MCP DSL

(def analyze-tool
  (deftool :analyze
    "Analyze Clojure code and return var definitions, usages, namespace graph, and findings summary."
    [(defparam :path :string "Path to file or directory to analyze")]
    (fn [{:keys [path]}]
      (core/analyze path))))

(def find-callers-tool
  (deftool :find_callers
    "Find all functions that call a specific var. Returns call sites with filename, line, column, and caller info."
    [(defparam :path :string "Path to file or directory to search")
     (defparam :ns :string "Namespace of the target var (e.g., 'clojure.core')")
     (defparam :var_name :string "Name of the var to find callers for")]
    (fn [{:keys [path ns var_name]}]
      (core/find-callers path ns var_name))))

(def find-calls-tool
  (deftool :find_calls
    "Find all vars that a specific function calls. Returns what the function depends on."
    [(defparam :path :string "Path to file or directory to search")
     (defparam :ns :string "Namespace of the source function")
     (defparam :var_name :string "Name of the function to analyze")]
    (fn [{:keys [path ns var_name]}]
      (core/find-calls path ns var_name))))

(def find-var-tool
  (deftool :find_var
    "Find definition(s) of a var by name. Returns filename, position, namespace, docstring, and arglists."
    [(defparam :path :string "Path to file or directory to search")
     (defparam :var_name :string "Name of the var to find")
     (defparam :ns :string "Optional: namespace to filter results" :required false)]
    (fn [{:keys [path var_name ns]}]
      (if ns
        (core/find-var path var_name ns)
        (core/find-var path var_name)))))

(def namespace-graph-tool
  (deftool :namespace_graph
    "Get namespace dependency graph. Returns nodes (namespaces) and edges (requires/uses) suitable for visualization."
    [(defparam :path :string "Path to file or directory to analyze")]
    (fn [{:keys [path]}]
      (core/namespace-graph path))))

(def lint-tool
  (deftool :lint
    "Lint Clojure code and return findings. Filter by level to show only errors or include warnings. Respects budget (timeout in ms) to prevent indefinite hangs."
    [(defparam :path :string "Path to file or directory to lint")
     (defparam :level :string "Filter level: 'error' (errors only) or 'warning' (all findings)" :required false)
     (defparam :budget :integer "Timeout in milliseconds for analysis (default: 30000)" :required false)]
    (fn [{:keys [path level budget]}]
      (core/lint path
                 :level (keyword (or level "warning"))
                 :timeout-ms (or budget core/default-timeout-ms)))))

(def unused-vars-tool
  (deftool :unused_vars
    "Find unused private vars in a codebase. Helps identify dead code that can be removed."
    [(defparam :path :string "Path to file or directory to analyze")]
    (fn [{:keys [path]}]
      (core/unused-vars path))))

;; Aggregate all tools into a map for the server
(def kondo-tools
  {:analyze analyze-tool
   :find_callers find-callers-tool
   :find_calls find-calls-tool
   :find_var find-var-tool
   :namespace_graph namespace-graph-tool
   :lint lint-tool
   :unused_vars unused-vars-tool})
