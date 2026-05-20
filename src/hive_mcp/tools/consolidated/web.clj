(ns hive-mcp.tools.consolidated.web
  "Consolidated web tool — fetch (retrieve a URL) + search (web query).

   Folds the standalone `web_fetch` / `web_search` addon tools into a
   single command-dispatch root. Their handlers are resolved at call
   time from the extension registry, so hive-mcp core stays ignorant of
   which addon provides them (DIP) and there is no compile-time coupling.

   Commands: fetch, search"
  (:require [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.core :refer [mcp-error]]
            [hive-mcp.extensions.registry :as ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Delegation to standalone addon tools (resolved at call time — DIP)
;; =============================================================================

(defn delegate-to-standalone
  "Return a handler that forwards params to a standalone registered addon
   tool's handler, resolved by name from the extension registry on each
   call (hot-reload safe). Returns an mcp-error when the tool is absent."
  [tool-name]
  (fn [params]
    (if-let [h (->> (ext/get-registered-tools)
                    (some (fn [t] (when (= tool-name (:name t)) (:handler t)))))]
      (h params)
      (mcp-error (str tool-name " tool not available (addon not loaded)")))))

;; =============================================================================
;; Canonical Handlers (core — addons can override via contribute-commands!)
;; =============================================================================

(def canonical-handlers
  {:fetch  (delegate-to-standalone "web_fetch")
   :search (delegate-to-standalone "web_search")})

(def handlers canonical-handlers)

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tool-def
  {:name         "web"
   :consolidated true
   :description  "Web operations: fetch (retrieve the contents of a URL), search (run a web search query). Use command='help' to list all."
   :inputSchema  {:type       "object"
                  :properties {"command"     {:type "string"
                                              :enum ["fetch" "search" "help"]
                                              :description "Web operation to perform"}
                               "url"         {:type "string"
                                              :description "[fetch] URL to retrieve"}
                               "query"       {:type "string"
                                              :description "[search] Search query"}
                               "prompt"      {:type "string"
                                              :description "[fetch] Optional extraction prompt"}
                               "max_results" {:type "integer"
                                              :description "[search] Max results to return"}}
                  :required   ["command"]}
   :handler      (composite/build-merged-handler "web" canonical-handlers)})

(def tools [tool-def])
