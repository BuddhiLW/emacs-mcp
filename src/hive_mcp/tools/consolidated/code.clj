(ns hive-mcp.tools.consolidated.code
  "Consolidated code intelligence tool — core: cider + clojure (basic-tools-mcp).

   Addons extend via contribute-commands! \"code\" at runtime.
   hive-mcp core has ZERO knowledge of addon tool names (OCP).

   Core subdomains:
     code cider eval          — REPL evaluation
     code cider doc           — docstring lookup
     code clojure check       — delimiter checking
     code clojure format      — cljfmt formatting

   Addon-contributed subdomains appear dynamically at runtime."
  (:require [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.consolidated.cider :as c-cider]
            [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Core Subdomain Handlers (only hive-mcp-owned tools)
;; =============================================================================

(defn- resolve-handler [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn- make-delegating-handler
  "Strip subdomain prefix and delegate to inner handler."
  [subdomain-name inner-handler-fn]
  (fn [params]
    (let [full-cmd (str (:command params))
          prefix (str subdomain-name " ")
          sub-cmd (if (str/starts-with? full-cmd prefix)
                    (subs full-cmd (count prefix))
                    full-cmd)]
      (inner-handler-fn (assoc params :command sub-cmd)))))

;; Clojure tool handler (basic-tools-mcp — AGPL, hive-mcp dep)
(defn- handle-clojure [params]
  (if-let [h (resolve-handler 'basic-tools-mcp.tools/handle-clojure)]
    (h params)
    {:content [{:type "text" :text "clojure handler not available"}] :isError true}))

;; =============================================================================
;; Canonical Handlers — only core-owned subdomains
;; Addon subdomains injected via contribute-commands! "code" (OCP)
;; =============================================================================

(def canonical-handlers
  "Core handler tree. Addons extend at runtime via contribute-commands! \"code\"."
  {:cider    c-cider/handlers
   :clojure  {:_handler (make-delegating-handler "clojure" handle-clojure)}})

(def handlers canonical-handlers)

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tool-def
  {:name "code"
   :consolidated true
   :description "Code intelligence: cider (REPL eval/doc/info/complete), clojure (check/repair/format/eval/wrap). Addons extend dynamically. Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command"   {:type "string"
                                           :description "Code operation. Core: 'cider eval', 'clojure check'. Addon subdomains appear dynamically. Use command='help' to list all."}
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
                              ;; Clojure params
                              "file_path" {:type "string"
                                           :description "Path to Clojure file"}
                              "line"      {:type "integer"
                                           :description "1-based line number (for wrap)"}
                              "template"  {:type "string"
                                           :description "Wrap template with %s placeholder"}
                              ;; Generic params (used by addon-contributed commands)
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
                                           :description "Max results"}}
                 :required ["command"]}
   :handler (composite/build-merged-handler "code" canonical-handlers)})

(def tools [tool-def])
