(ns hive-mcp.tools.consolidated.cider
  "Consolidated CIDER CLI tool."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.cider :as cider-handlers]
            [taoensso.timbre :as log]))

(def ^:private deprecated-aliases
  {:eval-explicit :eval
   :eval-session  :eval})

(defn- wrap-deprecated
  [alias-kw canonical-kw handler-fn]
  (fn [params]
    (log/warn (str "DEPRECATED: command '" (name alias-kw)
                   "' is deprecated, use '" (name canonical-kw) "' instead."))
    (handler-fn params)))

(def canonical-handlers
  {:eval          cider-handlers/handle-cider-eval
   :doc           cider-handlers/handle-cider-doc
   :info          cider-handlers/handle-cider-info
   :complete      cider-handlers/handle-cider-complete
   :apropos       cider-handlers/handle-cider-apropos
   :status        cider-handlers/handle-cider-status
   :spawn         cider-handlers/handle-cider-spawn-session
   :connect       cider-handlers/handle-cider-connect-session
   :sessions      cider-handlers/handle-cider-list-sessions
   :kill-session  cider-handlers/handle-cider-kill-session
   :kill-all      cider-handlers/handle-cider-kill-all-sessions})

(def handlers
  (merge canonical-handlers
         (reduce-kv (fn [m alias-kw canonical-kw]
                      (assoc m alias-kw
                             (wrap-deprecated alias-kw canonical-kw
                                              (get canonical-handlers canonical-kw))))
                    {} deprecated-aliases)))

(def handle-cider
  (make-cli-handler handlers))

(def tool-def
  {:name "cider"
   :consolidated true
   :description "CIDER REPL operations: eval (silent|explicit, optional session routing), doc (docstring), info (full metadata), complete (completions), apropos (search symbols), status (connection), spawn/connect/sessions/kill-session/kill-all (multi-REPL). connect joins an existing nREPL (e.g. shadow-cljs, lein). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["eval" "eval-explicit" "doc" "info" "complete" "apropos" "status" "spawn" "connect" "sessions" "eval-session" "kill-session" "kill-all" "help"]
                                         :description "CIDER operation to perform"}
                              "code" {:type "string"
                                      :description "Clojure code to evaluate"}
                              "mode" {:type "string"
                                      :enum ["silent" "explicit"]
                                      :description "Eval mode: 'silent' (default) or 'explicit' (shows in REPL buffer). Only used with eval command."}
                              "symbol" {:type "string"
                                        :description "Symbol name for doc/info lookup"}
                              "prefix" {:type "string"
                                        :description "Prefix for completion"}
                              "pattern" {:type "string"
                                         :description "Regex pattern for apropos search"}
                              "search_docs" {:type "boolean"
                                             :description "Also search docstrings"}
                              "host" {:type "string"
                                      :description "nREPL host for connect (default: localhost)"}
                              "port" {:type "integer"
                                      :description "nREPL port for connect"}
                              "name" {:type "string"
                                      :description "Session name for spawn/connect"}
                              "session_name" {:type "string"
                                              :description "Session name for eval-session/kill-session. When provided with eval command, routes to session eval."}
                              "project_dir" {:type "string"
                                             :description "Project directory for spawn"}
                              "agent_id" {:type "string"
                                          :description "Agent ID to link session"}
                              "repl_type" {:type "string"
                                           :enum ["clj" "cljs" "cljel"]
                                           :description "REPL type for connect/spawn: clj (default), cljs (shadow-cljs), or cljel (ClojureElisp)"}
                              "timeout" {:type "integer"
                                         :description "Eval timeout in seconds (default: 60)"}}
                 :required ["command"]}
   :handler handle-cider})

(def tools [tool-def])
