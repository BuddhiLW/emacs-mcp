(ns hive-mcp.tools.consolidated.code
  "Consolidated code intelligence tool — core: clojure (basic-tools-mcp).

   Addons extend via contribute-commands! \"code\" at runtime.
   hive-mcp core has ZERO knowledge of addon tool names (OCP).

   Core subdomains:
     code clojure check       — delimiter checking
     code clojure format      — cljfmt formatting

   Addon-contributed subdomains appear dynamically at runtime (e.g. the
   hive.emacs addon owns the whole :cider subtree — REPL eval, introspection,
   session lifecycle)."
  (:require [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.core :refer [mcp-error]]
            [hive-mcp.extensions.registry :as ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Core Subdomain Handlers (only hive-mcp-owned tools)
;; =============================================================================

(defn- resolve-handler [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn- make-delegating-handler
  "Strip subdomain prefix and delegate to inner handler.
   The same wrapper an addon uses for a contributed subdomain."
  [subdomain-name inner-handler-fn]
  (composite/subdomain-handler subdomain-name inner-handler-fn))

(defn- delegate-to-standalone
  "Return a handler that forwards params to a standalone addon tool's handler,
   resolved by :name from the extension registry on each call (DIP, hot-reload
   safe). Addons register at server boot, so this is nil in a bare REPL/test —
   returns a clean mcp-error then, and the live handler once the addon loads.
   Mirrors hive-mcp.tools.consolidated.web/delegate-to-standalone."
  [tool-name]
  (fn [params]
    (if-let [h (->> (ext/get-registered-tools)
                    (some (fn [t] (when (= tool-name (:name t)) (:handler t)))))]
      (h params)
      (mcp-error (str tool-name " not available (addon not loaded)")))))

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
  "Core handler tree. Addons extend at runtime via contribute-commands! \"code\".
   The :cider subtree is NOT core: the hive.emacs addon contributes it
   (addon-wins whole-subtree replacement)."
  {:clojure  {:_handler (make-delegating-handler "clojure" handle-clojure)}
   ;; Folded standalone addon tool re-exposed as an ergonomic subdomain:
   ;;   `code analysis <cmd>`  → lsp-mcp analysis tool
   ;; Routes on :command, so prefix-strip then delegate to the standalone
   ;; handler resolved from the ext registry at call time (live-only; clean
   ;; error in bare test where the addon isn't loaded). Further subdomains are
   ;; addon-contributed at runtime via contribute-commands! "code" (OCP) — core
   ;; hive-mcp holds ZERO addon tool names.
   :analysis     {:_handler (make-delegating-handler "analysis"     (delegate-to-standalone "analysis"))}})

(def handlers canonical-handlers)

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tool-def
  {:name "code"
   :consolidated true
   :description "Code intelligence: clojure (check/repair/format/eval/wrap), analysis (lsp-mcp). CIDER/REPL operations come from the hive.emacs addon. Addons extend dynamically. Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command"   {:type "string"
                                           :description "Code operation. Core: 'clojure check'. Folded subdomain: 'analysis <cmd>'. Addon subdomains appear at runtime (live when the addon is loaded): 'cider eval' etc. Use command='help' to list all."}
                              ;; Generic params (shared by core + addon-contributed commands)
                              "name"      {:type "string"
                                           :description "Session name or form name"}
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
