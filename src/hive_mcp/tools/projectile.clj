(ns hive-mcp.tools.projectile
  "Projectile integration handlers for MCP.

   Provides project management capabilities through Emacs projectile,
   including project info, file listing, search, and navigation.

   Requires the hive-mcp-projectile addon to be loaded in Emacs."
  (:require [hive-mcp.agent.context :as ctx]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.emacs-ext.elisp :as el]
            [hive-mcp.tools.core :refer [mcp-error]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Projectile Integration Handlers (requires hive-mcp-projectile addon)
;; =============================================================================

(defn projectile-addon-available?
  "Check if hive-mcp-projectile addon is loaded."
  []
  (let [{:keys [success result]} (ec/eval-elisp "(featurep 'hive-mcp-projectile)")]
    (and success (= result "t"))))

(defn- eval-projectile
  "Evaluate projectile elisp, returning MCP response."
  [elisp]
  (let [{:keys [success result error]} (ec/eval-elisp elisp)]
    (if success
      {:type "text" :text result}
      (mcp-error (str "Error: " error)))))

(defn handle-projectile-info
  "Get current project info including name, root, type, and file count.

   Honors `directory` param (with ctx fallback) by rebinding Emacs
   `default-directory` so projectile resolves the requested project
   rather than the JVM cwd."
  [{:keys [directory]}]
  (let [effective-dir (or directory (ctx/current-directory))
        json-call (el/require-and-call-json 'hive-mcp-projectile
                                            'hive-mcp-projectile-api-project-info)
        elisp (if effective-dir
                (format "(let ((default-directory %s)) %s)"
                        (pr-str (str effective-dir "/"))
                        json-call)
                json-call)]
    (log/info "projectile-info" {:directory effective-dir})
    (eval-projectile elisp)))

(defn handle-projectile-files
  "List files in current project, optionally filtered by pattern."
  [{:keys [pattern]}]
  (log/info "projectile-files" {:pattern pattern})
  (eval-projectile
   (if pattern
     (el/require-and-call-json 'hive-mcp-projectile 'hive-mcp-projectile-api-project-files pattern)
     (el/require-and-call-json 'hive-mcp-projectile 'hive-mcp-projectile-api-project-files))))

(defn handle-projectile-find-file
  "Find files matching a filename in current project."
  [{:keys [filename]}]
  (log/info "projectile-find-file" {:filename filename})
  (eval-projectile (el/require-and-call-json 'hive-mcp-projectile 'hive-mcp-projectile-api-find-file filename)))

(defn handle-projectile-search
  "Search project for a pattern using ripgrep or grep."
  [{:keys [pattern]}]
  (log/info "projectile-search" {:pattern pattern})
  (eval-projectile (el/require-and-call-json 'hive-mcp-projectile 'hive-mcp-projectile-api-search pattern)))

(defn handle-projectile-recent
  "Get recently visited files in current project."
  [_]
  (log/info "projectile-recent")
  (eval-projectile (el/require-and-call-json 'hive-mcp-projectile 'hive-mcp-projectile-api-recent-files)))

(defn handle-projectile-list-projects
  "List all known projectile projects."
  [_]
  (log/info "projectile-list-projects")
  (eval-projectile (el/require-and-call-json 'hive-mcp-projectile 'hive-mcp-projectile-api-list-projects)))

(def tools
  "REMOVED: Flat projectile tools no longer exposed. Use consolidated `project` tool."
  [])
