(ns hive-mcp.tools.consolidated.code
  "Consolidated code intelligence tool — merges cider, analysis, codebase-map, clojure.

   Uses nested command namespacing:
     code cider eval          — REPL evaluation
     code cider doc           — docstring lookup
     code analysis lint       — clj-kondo linting (addon-contributed)
     code analysis outline    — namespace outline (addon-contributed)
     code carto scan          — codebase indexing (addon-contributed)
     code clojure check       — delimiter checking
     code clojure format      — cljfmt formatting

   analysis and carto commands are contributed dynamically by addons.
   Addons can extend via contribute-commands! \"code\"."
  (:require [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.consolidated.cider :as c-cider]
            [hive-mcp.extensions.registry :as ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Lazy Resolution for addon-registered tools
;; =============================================================================

(defn- resolve-handler [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn- get-addon-tool-handler
  "Get handler from addon-registered tool by name."
  [tool-name]
  (when-let [tools (ext/get-registered-tools)]
    (some (fn [t] (when (= tool-name (:name t)) (:handler t))) tools)))

;; Clojure tool handler (basic-tools-mcp)
(defn- handle-clojure [params]
  (if-let [h (resolve-handler 'basic-tools-mcp.tools/handle-clojure)]
    (h params)
    {:content [{:type "text" :text "clojure handler not available"}] :isError true}))

;; Codebase-map handler (addon-registered, resolved at call time)
(defn- handle-carto [params]
  (if-let [h (get-addon-tool-handler "codebase-map")]
    (h params)
    {:content [{:type "text" :text "codebase-map handler not available (addon not loaded)"}] :isError true}))

;; Analysis handler (composite, built from addon contributions)
(defn- handle-analysis [params]
  (let [handler (composite/build-composite-handler "analysis")]
    (handler params)))

;; =============================================================================
;; Canonical Handlers — nested by subdomain
;; =============================================================================

(def canonical-handlers
  "Nested handler tree. Dispatch via 'cider eval', 'analysis lint', etc."
  {:cider    c-cider/handlers
   :analysis {:_handler handle-analysis}
   :carto    {:_handler handle-carto}
   :clojure  {:_handler handle-clojure}})

(def handlers canonical-handlers)

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tool-def
  {:name "code"
   :consolidated true
   :description "Code analysis: analyze, audit, bridge-status, callers, calls, codebase-map, compare, cursor-info, definition, definitions, extract, file, find_var, graph, hotspots, hover, impact, lint, live-references, navigate, ns-graph, outline, references, resolve, scc, search, search-forms, semantic-glob, server-info, smart-read, status, structural-grep, symbols, sync, unused_vars, workspaces. Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command"   {:type "string"
                                           :description "Code operation. Prefix with subdomain: 'cider eval', 'analysis lint', 'carto scan', 'clojure check'. Use command='help' to list all."}
                              ;; Cider params
                              "code"      {:type "string"
                                           :description "Clojure code to evaluate"}
                              "symbol"    {:type "string"
                                           :description "Symbol name for doc/info lookup"}
                              "prefix"    {:type "string"
                                           :description "Prefix for completion"}
                              "pattern"   {:type "string"
                                           :description "Regex pattern for apropos search"}
                              "mode"      {:type "string"
                                           :enum ["silent" "explicit"]
                                           :description "Eval mode: 'silent' (default) or 'explicit'"}
                              "session_name" {:type "string"
                                              :description "Session name for eval-session/kill-session"}
                              "name"      {:type "string"
                                           :description "Session name or form name"}
                              "port"      {:type "integer"
                                           :description "nREPL port for connect"}
                              "host"      {:type "string"
                                           :description "nREPL host for connect (default: localhost)"}
                              "timeout"   {:type "integer"
                                           :description "Eval timeout in seconds (default: 60)"}
                              "project_dir" {:type "string"
                                             :description "Project directory for spawn"}
                              "repl_type" {:type "string"
                                           :enum ["clj" "cljs" "cljel"]
                                           :description "REPL type: clj (default), cljs, or cljel"}
                              ;; Analysis/carto params
                              "file"      {:type "string"
                                           :description "File path"}
                              "path"      {:type "string"
                                           :description "Path to analyze"}
                              "namespace" {:type "string"
                                           :description "Namespace filter"}
                              "function"  {:type "string"
                                           :description "Qualified function name ns/fn"}
                              "query"     {:type "string"
                                           :description "Search query"}
                              "depth"     {:type "integer"
                                           :description "Traversal depth (default: 2)"}
                              "limit"     {:type "integer"
                                           :description "Max results"}
                              ;; Clojure params
                              "file_path" {:type "string"
                                           :description "Path to Clojure file"}
                              "line"      {:type "integer"
                                           :description "1-based line number (for wrap)"}
                              "template"  {:type "string"
                                           :description "Wrap template with %s placeholder"}}
                 :required ["command"]}
   :handler (composite/build-merged-handler "code" canonical-handlers)})

(def tools [tool-def])
