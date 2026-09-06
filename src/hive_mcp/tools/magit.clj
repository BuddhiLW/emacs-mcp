(ns hive-mcp.tools.magit
  "Magit integration handlers for MCP.

   Provides comprehensive git operations via magit addon:
   - Status, branches, log, diff
   - Stage, commit, push, pull, fetch
   - Feature branch listing for /ship and /ship-pr skills

   Result DSL: Internal logic returns Result maps ({:ok val} or {:error category}).
   Single try-result boundary at each handler level. Zero nested try-catch."
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.tools.core :refer [mcp-success mcp-error addon-available?
                                         emacs-timeout-ms]]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.emacs-ext.elisp :as el]
            [hive-mcp.agent.context :as ctx]
            [taoensso.timbre :as log]
            [clojure.string :as str]))
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
   {:success true :result r} -> (ok r), {:success false :error e} -> (err ...)

   `timeout-ms` nil takes the client's default; a number is the caller's own
   budget for this one call, clamped by the client's ceiling."
  ([elisp] (elisp->result elisp nil))
  ([elisp timeout-ms]
   (let [{:keys [success result error]} (if timeout-ms
                                          (ec/eval-elisp-with-timeout elisp timeout-ms)
                                          (ec/eval-elisp elisp))]
     (if success
       (result/ok result)
       (result/err :magit/elisp-failed {:message (str error)})))))

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
   DRYs the repeated pattern: eval-elisp -> if success -> mcp-success/mcp-error.

   `timeout-ms` nil takes the client's default."
  ([category elisp] (handle-elisp category elisp nil))
  ([category elisp timeout-ms]
   (result->mcp (try-result category #(elisp->result elisp timeout-ms)))))

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

(defn- push-options-elisp
  "Elisp plist literal for the push options, or \"nil\" when none apply.

   A blank or absent REMOTE contributes no :remote key, so the emitted call is
   byte-identical to the pre-remote one."
  [set_upstream remote]
  (let [remote (some-> remote str str/trim not-empty)
        pairs  (cond-> []
                 set_upstream (conj ":set-upstream t")
                 remote       (conj (str ":remote " (pr-str remote))))]
    (if (seq pairs)
      (str "'(" (str/join " " pairs) ")")
      "nil")))

(defn- build-push-elisp
  "Build elisp for push operation with optional set-upstream and explicit remote."
  [set_upstream remote dir]
  (el/format-elisp
   "(progn
      (require 'hive-mcp-magit nil t)
      (if (fboundp 'hive-mcp-magit-api-push)
          (hive-mcp-magit-api-push %s %s)
        \"hive-mcp-magit not loaded\"))"
   (push-options-elisp set_upstream remote) (pr-str dir)))

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

(defn normalize-files
  "Normalize the `files` tool parameter to :all or a vector of path strings.

   Accepts a collection of paths, a single path, several paths in one
   whitespace-separated string, or \"all\". Returns :all, a NON-EMPTY vector of
   paths, or nil when nothing usable was given."
  [files]
  (cond
    (nil? files)     nil
    (keyword? files) (when (= :all files) :all)
    (symbol? files)  (when (= 'all files) :all)
    (string? files)  (let [ps (vec (remove str/blank? (str/split (str/trim files) #"\s+")))]
                       (cond
                         (empty? ps)    nil
                         (= ["all"] ps) :all
                         :else          ps))
    (coll? files)    (let [ps (->> files (map str) (map str/trim) (remove str/blank?) vec)]
                       (cond
                         (empty? ps)    nil
                         (= ["all"] ps) :all
                         :else          ps))
    :else            nil))

(def ^:private stage-markers
  "Sentinel strings the stage-and-verify elisp answers with.
   :missing is a PREFIX — the offending path follows it."
  {:ok      "hive-mcp:staged-ok"
   :missing "hive-mcp:missing-path:"
   :empty   "hive-mcp:nothing-staged"})

(defn- paths->elisp-list
  "PATHS as a quoted elisp list literal."
  [paths]
  (str "'(" (str/join " " (map pr-str paths)) ")"))

(defn- build-stage-verify-elisp
  "Elisp that refuses a path absent from the working tree, stages PATHS, and
   reports whether the index actually carries any of them.

   Evaluates to one of `stage-markers`: :ok, :missing prefixed to the offending
   path, or :empty when `git diff --cached --name-only` restricted to PATHS is
   blank."
  [paths dir]
  (el/format-elisp
   "(progn
      (require 'hive-mcp-magit nil t)
      (if (fboundp 'hive-mcp-magit-api-stage)
          (let ((default-directory %s)
                (paths %s)
                (missing nil))
            (dolist (p paths)
              (unless (file-exists-p p) (setq missing (or missing p))))
            (if missing
                (concat \"%s\" missing)
              (progn
                (hive-mcp-magit-api-stage paths default-directory)
                (if (string-empty-p
                     (string-trim
                      (shell-command-to-string
                       (concat \"git diff --cached --name-only -- \"
                               (mapconcat #'shell-quote-argument paths \" \")
                               \" 2>/dev/null\"))))
                    \"%s\"
                  \"%s\"))))
        \"hive-mcp-magit not loaded\"))"
   (pr-str dir)
   (paths->elisp-list paths)
   (:missing stage-markers)
   (:empty stage-markers)
   (:ok stage-markers)))

(defn- missing-path
  "The path named by a :missing marker inside elisp output OUT."
  [out]
  (or (second (re-find (re-pattern (str (:missing stage-markers) "([^\"\\s]+)")) out))
      "(unnamed)"))

(defn- stage-verify
  "Stage PATHS in DIR and confirm the index carries at least one of them.

   Result: (ok PATHS), or (err :magit/path-not-found | :magit/nothing-staged |
   :magit/elisp-failed | :magit/stage-failed). Any answer that is not the
   explicit ok marker is an error: a commit must never proceed on an index this
   operation did not verify."
  [paths dir timeout-ms]
  (let [r (try-result :magit/stage-failed
                      #(elisp->result (build-stage-verify-elisp paths dir) timeout-ms))
        listed (str/join " " paths)]
    (if-let [entry (find r :ok)]
      (let [out (str (val entry))]
        (cond
          (str/includes? out (:missing stage-markers))
          (result/err :magit/path-not-found
                      {:message (str ":magit/path-not-found - no such path under "
                                     dir ": " (missing-path out))})

          (str/includes? out (:empty stage-markers))
          (result/err :magit/nothing-staged
                      {:message (str ":magit/nothing-staged - staging left the index empty for "
                                     listed
                                     "; refusing to commit what was already staged")})

          (str/includes? out (:ok stage-markers))
          (result/ok paths)

          :else
          (result/err :magit/stage-failed
                      {:message (str ":magit/stage-failed - staging " listed
                                     " returned no verdict: " out)})))
      r)))

(defn handle-magit-status
  "Get comprehensive git repository status via magit addon."
  [{:keys [directory] :as params}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-status" {:directory dir})
    (handle-elisp :magit/status-failed
                  (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status dir)
                  (emacs-timeout-ms params))))

(defn handle-magit-branches
  "Get branch information including current, upstream, local and remote branches."
  [{:keys [directory] :as params}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-branches" {:directory dir})
    (handle-elisp :magit/branches-failed
                  (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-branches dir)
                  (emacs-timeout-ms params))))

(defn handle-magit-log
  "Get recent commit log."
  [{:keys [count directory] :as params}]
  (let [dir (resolve-directory directory)
        n (if-let [c count] c 10)]
    (log/info "magit-log" {:count n :directory dir})
    (handle-elisp :magit/log-failed
                  (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-log n dir)
                  (emacs-timeout-ms params))))

(defn handle-magit-diff
  "Get diff for staged, unstaged, or all changes."
  [{:keys [target directory] :as params}]
  (let [dir (resolve-directory directory)
        target-sym (case target
                     "staged" 'staged
                     "unstaged" 'unstaged
                     "all" 'all
                     'staged)]
    (log/info "magit-diff" {:target target :directory dir})
    (handle-elisp :magit/diff-failed
                  (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-diff target-sym dir)
                  (emacs-timeout-ms params))))

(defn handle-magit-stage
  "Stage files for commit.

   `files` takes a list of paths, a single path, several paths in one
   whitespace-separated string, or 'all' for every modified file. An absent or
   blank `files` is an error, never a silent no-op."
  [{:keys [files directory] :as params}]
  (let [dir (resolve-directory directory)
        norm (normalize-files files)
        timeout-ms (emacs-timeout-ms params)]
    (log/info "magit-stage" {:files norm :directory dir})
    (if (nil? norm)
      (mcp-error (str ":magit/no-files - files is required: a path, a list of paths, "
                      "a whitespace-separated path string, or 'all'"))
      (let [file-arg (if (= :all norm) 'all norm)
            elisp (el/require-and-call 'hive-mcp-magit 'hive-mcp-magit-api-stage file-arg dir)]
        (result->mcp
         (try-result :magit/stage-failed
                     #(with-default "Staged files" (elisp->result elisp timeout-ms))))))))

(defn handle-magit-commit
  "Create a commit with the given message.

   `files` restricts the commit to explicit paths: a list, a single path,
   several paths in one whitespace-separated string, or 'all'. Named paths are
   staged and VERIFIED before the commit runs; a path that does not exist, or a
   stage that leaves the index empty for those paths, fails the operation
   instead of committing whatever the index already held."
  [{:keys [message all directory files] :as params}]
  (let [dir (resolve-directory directory)
        timeout-ms (emacs-timeout-ms params)
        norm (normalize-files files)
        stage-all (boolean (or all (= :all norm)))
        staged (when (vector? norm) (stage-verify norm dir timeout-ms))]
    (log/info "magit-commit" {:message-len (count message) :all stage-all
                              :files norm :directory dir})
    (if (and (some? staged) (nil? (find staged :ok)))
      (result->mcp staged)
      (handle-elisp :magit/commit-failed
                    (build-commit-elisp message stage-all dir)
                    timeout-ms))))

(defn handle-magit-push
  "Push to remote. Optionally set upstream tracking.

   `remote` selects the remote explicitly; omitted or blank, git's own default
   remote is used. A push is the magit command most likely to outrun the
   client's default timeout — it waits on a remote. Pass `timeout_ms` to give
   it a longer budget."
  [{:keys [set_upstream remote directory] :as params}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-push" {:set_upstream set_upstream :remote remote :directory dir})
    (handle-elisp :magit/push-failed
                  (build-push-elisp set_upstream remote dir)
                  (emacs-timeout-ms params))))

(defn handle-magit-pull
  "Pull from upstream."
  [{:keys [directory] :as params}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-pull" {:directory dir})
    (handle-elisp :magit/pull-failed
                  (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-pull dir)
                  (emacs-timeout-ms params))))

(defn handle-magit-fetch
  "Fetch from remote(s)."
  [{:keys [remote directory] :as params}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-fetch" {:remote remote :directory dir})
    (handle-elisp :magit/fetch-failed
                  (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-fetch remote dir)
                  (emacs-timeout-ms params))))

(defn handle-magit-feature-branches
  "Get list of feature/fix/feat branches (for /ship and /ship-pr skills)."
  [{:keys [directory] :as params}]
  (let [dir (resolve-directory directory)]
    (log/info "magit-feature-branches" {:directory dir})
    (handle-elisp :magit/feature-branches-failed
                  (build-feature-branches-elisp dir)
                  (emacs-timeout-ms params))))

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
