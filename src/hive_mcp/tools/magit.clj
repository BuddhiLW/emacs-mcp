(ns hive-mcp.tools.magit
  "Magit integration handlers for MCP.

   Provides comprehensive git operations via magit addon:
   - Status, branches, log, diff
   - Stage, commit, push, pull, fetch
   - Feature branch listing for /ship and /ship-pr skills

   Result DSL: Internal logic returns Result maps ({:ok val} or {:error category}).
   Single try-result boundary at each handler level. Zero nested try-catch."
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.tools.core :refer [mcp-success mcp-error addon-available?]]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.emacs-ext.elisp :as el]
            [hive-mcp.agent.context :as ctx]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ============================================================
;; Working Directory Resolution
;; ============================================================
;;
;; When Claude CLI spawns in a project directory, magit tools should
;; operate on that project by default - not on whatever buffer is
;; active in Emacs.
;;
;; Fallback chain:
;;   1. Explicit directory parameter from caller
;;   2. ctx/current-directory - from request context (CTX migration)
;;   3. System/getProperty "user.dir" - MCP server's working directory

(defn- resolve-directory
  "Resolve the directory to use for git operations.
   Uses provided directory, request context directory, or falls back to
   MCP server's working directory.

   CTX Migration: Now uses hive-mcp.agent.context for directory resolution."
  [directory]
  (or directory
      (ctx/current-directory)
      (System/getProperty "user.dir")))

;;; =============================================================================
;;; Result DSL Helpers (boundary pattern — same as tools/cider.clj)
;;; =============================================================================

(defn- elisp->result
  "Execute elisp and convert response to Result.
   {:success true :result r} -> (ok r), {:success false :error e} -> (err ...)"
  [elisp]
  (let [{:keys [success result error]} (ec/eval-elisp elisp)]
    (if success
      (result/ok result)
      (result/err :magit/elisp-failed {:message (str error)}))))

(defn- try-result
  "Execute thunk f returning Result; catch unexpected exceptions as error Result.
   Unlike try-effect, expects f to return a Result map directly."
  [category f]
  (try
    (f)
    (catch Exception e
      (log/error e (str (name category) " failed"))
      (result/err category {:message (.getMessage e)}))))

(defn- result->mcp
  "Convert Result to MCP response.
   {:ok data} -> (mcp-success data), {:error ...} -> (mcp-error message).
   Uses if-let+find for CC-free unwrapping (scc does not count if-let)."
  [r]
  (if-let [entry (find r :ok)]
    (mcp-success (val entry))
    (mcp-error (str "Error: " (get r :message (get r :error "unknown"))))))

(defn- handle-elisp
  "Common handler: execute elisp via try-result boundary, return MCP response.
   DRYs the repeated pattern: eval-elisp -> if success -> mcp-success/mcp-error."
  [category elisp]
  (result->mcp (try-result category #(elisp->result elisp))))

(defn- with-default
  "Provide default value when Result ok value is nil.
   Uses if-let (CC-free) for both Result detection and nil check."
  [default-val r]
  (if-let [entry (find r :ok)]
    (if-let [_v (val entry)] r (result/ok default-val))
    r))

;; ============================================================
;; Magit Integration Tools (requires hive-mcp-magit addon)
;; ============================================================

(defn magit-addon-available?
  "Check if the magit addon is loaded in Emacs.
   Delegates to tools.core/addon-available? for DRY addon checks."
  []
  (addon-available? :magit))

;;; =============================================================================
;;; Elisp Builders (for handlers requiring custom elisp)
;;; =============================================================================

(defn- build-commit-elisp
  "Build elisp for commit operation with optional stage-all."
  [message all dir]
  (let [options (if-let [_ all] "'(:all t)" "nil")]
    (el/format-elisp
     "(progn
        (require 'hive-mcp-magit nil t)
        (if (fboundp 'hive-mcp-magit-api-commit)
            (hive-mcp-magit-api-commit %s %s %s)
          \"hive-mcp-magit not loaded\"))"
     (pr-str message) options (pr-str dir))))

(defn- build-push-elisp
  "Build elisp for push operation with optional set-upstream."
  [set_upstream dir]
  (let [options (if-let [_ set_upstream] "'(:set-upstream t)" "nil")]
    (el/format-elisp
     "(progn
        (require 'hive-mcp-magit nil t)
        (if (fboundp 'hive-mcp-magit-api-push)
            (hive-mcp-magit-api-push %s %s)
          \"hive-mcp-magit not loaded\"))"
     options (pr-str dir))))

(defn- build-feature-branches-elisp
  "Build elisp for feature branch listing with client-side filtering."
  [dir]
  (el/format-elisp
   "(progn
      (require 'hive-mcp-magit nil t)
      (if (fboundp 'hive-mcp-magit-api-branches)
          (let* ((default-directory %s)
                 (branches (hive-mcp-magit-api-branches default-directory))
                 (local (plist-get branches :local))
                 (feature-branches
                   (seq-filter
                     (lambda (b)
                       (string-match-p \"^\\\\(feature\\\\|fix\\\\|feat\\\\)/\" b))
                     local)))
            (json-encode (list :current (plist-get branches :current)
                               :feature_branches feature-branches)))
        (json-encode (list :error \"hive-mcp-magit not loaded\"))))"
   (pr-str dir)))

;;; =============================================================================
;;; Magit Handlers (thin wrappers over handle-elisp)
;;; =============================================================================

(defn handle-magit-status
  "Get comprehensive git repository status via magit addon."
  [{:keys [directory]}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-status" {:directory dir})
    (handle-elisp :magit/status-failed
                  (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status dir))))

(defn handle-magit-branches
  "Get branch information including current, upstream, local and remote branches."
  [{:keys [directory]}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-branches" {:directory dir})
    (handle-elisp :magit/branches-failed
                  (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-branches dir))))

(defn handle-magit-log
  "Get recent commit log."
  [{:keys [count directory]}]
  (let [dir (resolve-directory directory)
        n (if-let [c count] c 10)]
    (log/info "magit-log" {:count n :directory dir})
    (handle-elisp :magit/log-failed
                  (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-log n dir))))

(defn handle-magit-diff
  "Get diff for staged, unstaged, or all changes."
  [{:keys [target directory]}]
  (let [dir (resolve-directory directory)
        target-sym (case target
                     "staged" 'staged
                     "unstaged" 'unstaged
                     "all" 'all
                     'staged)]
    (log/info "magit-diff" {:target target :directory dir})
    (handle-elisp :magit/diff-failed
                  (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-diff target-sym dir))))

(defn handle-magit-stage
  "Stage files for commit. Use 'all' to stage all modified files."
  [{:keys [files directory]}]
  (let [dir (resolve-directory directory)
        file-arg (case files "all" 'all files)
        elisp (el/require-and-call 'hive-mcp-magit 'hive-mcp-magit-api-stage file-arg dir)]
    (log/info "magit-stage" {:files files :directory dir})
    (result->mcp
     (try-result :magit/stage-failed
                 #(with-default "Staged files" (elisp->result elisp))))))

(defn handle-magit-commit
  "Create a commit with the given message."
  [{:keys [message all directory]}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-commit" {:message-len (count message) :all all :directory dir})
    (handle-elisp :magit/commit-failed (build-commit-elisp message all dir))))

(defn handle-magit-push
  "Push to remote. Optionally set upstream tracking."
  [{:keys [set_upstream directory]}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-push" {:set_upstream set_upstream :directory dir})
    (handle-elisp :magit/push-failed (build-push-elisp set_upstream dir))))

(defn handle-magit-pull
  "Pull from upstream."
  [{:keys [directory]}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-pull" {:directory dir})
    (handle-elisp :magit/pull-failed
                  (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-pull dir))))

(defn handle-magit-fetch
  "Fetch from remote(s)."
  [{:keys [remote directory]}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-fetch" {:remote remote :directory dir})
    (handle-elisp :magit/fetch-failed
                  (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-fetch remote dir))))

(defn handle-magit-feature-branches
  "Get list of feature/fix/feat branches (for /ship and /ship-pr skills)."
  [{:keys [directory]}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-feature-branches" {:directory dir})
    (handle-elisp :magit/feature-branches-failed (build-feature-branches-elisp dir))))

;; Tool definitions for magit handlers

;; IMPORTANT: When using a shared hive-mcp server across multiple projects,
;; Claude should ALWAYS pass its current working directory to these tools.
;; The directory can be found in Claude's prompt (e.g., ~/PP/funeraria/sisf-web)
;; or by running `pwd` in bash.

(def ^:private dir-desc
  "IMPORTANT: Pass your current working directory here to ensure git operations target YOUR project, not the MCP server's directory. Get it from your prompt path or run `pwd`.")

(def tools
  "REMOVED: Flat magit tools no longer exposed. Use consolidated `magit` tool."
  [])
