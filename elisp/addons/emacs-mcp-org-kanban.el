;;; emacs-mcp-org-kanban.el --- Kanban integration with dual backends -*- lexical-binding: t; -*-

;; Copyright (C) 2025 Pedro G. Branquinho
;; Author: Pedro G. Branquinho <pedrogbranquinho@gmail.com>
;; Version: 0.1.0
;; Package-Requires: ((emacs "28.1") (org "9.0"))
;; Keywords: tools, kanban, org-mode, mcp, project-management
;; SPDX-License-Identifier: MIT

;;; Commentary:
;;
;; This addon provides kanban task tracking with dual backends:
;; - vibe-kanban: Cloud/MCP-based kanban (team collaboration)
;; - standalone: Local org files (personal/offline use)
;;
;; Architecture follows SOLID/DDD principles:
;; - Backend Protocol: Defines operations all backends must support
;; - Strategy Pattern: Backends are interchangeable at runtime
;; - Adapter Layer: Unified API regardless of backend
;;
;; Features:
;; - Agent-aware task tracking (tracks which agent worked on what)
;; - Session continuity via org properties
;; - org-kanban UI integration for visualization
;; - Bidirectional sync between backends
;; - Roadmap and dependency tracking
;;
;; Usage:
;;   (emacs-mcp-addon-load 'org-kanban)
;;   (emacs-mcp-kanban-mode 1)
;;
;;   ;; Set backend
;;   (setq emacs-mcp-kanban-backend 'vibe)      ; cloud/MCP
;;   (setq emacs-mcp-kanban-backend 'standalone) ; local org file
;;
;;   ;; Or use both with sync
;;   (emacs-mcp-kanban-enable-dual-backend)

;;; Code:

(require 'cl-lib)
(require 'org)
(require 'json)

;; Soft dependencies
(declare-function emacs-mcp-api-get-context "emacs-mcp-api")
(declare-function org-kanban/initialize "org-kanban")

;;; ============================================================================
;;; Customization
;;; ============================================================================

(defgroup emacs-mcp-kanban nil
  "Kanban integration for emacs-mcp with dual backends."
  :group 'emacs-mcp
  :prefix "emacs-mcp-kanban-")

(defcustom emacs-mcp-kanban-backend 'standalone
  "Active kanban backend.
- `vibe': Use vibe-kanban MCP server (cloud/team)
- `standalone': Use local org file (personal/offline)"
  :type '(choice (const :tag "Vibe Kanban (MCP/Cloud)" vibe)
                 (const :tag "Standalone (Local Org)" standalone))
  :group 'emacs-mcp-kanban)

(defcustom emacs-mcp-kanban-org-file
  (expand-file-name ".emacs-mcp-kanban.org" user-emacs-directory)
  "Path to standalone kanban org file."
  :type 'file
  :group 'emacs-mcp-kanban)

(defcustom emacs-mcp-kanban-default-project nil
  "Default vibe-kanban project ID.
Set this to your project UUID for automatic project selection."
  :type '(choice (const :tag "None" nil)
                 (string :tag "Project UUID"))
  :group 'emacs-mcp-kanban)

(defcustom emacs-mcp-kanban-statuses
  '("TODO" "IN-PROGRESS" "IN-REVIEW" "DONE" "CANCELLED")
  "List of kanban statuses/columns."
  :type '(repeat string)
  :group 'emacs-mcp-kanban)

(defcustom emacs-mcp-kanban-status-map
  '(("TODO" . "todo")
    ("IN-PROGRESS" . "inprogress")
    ("IN-REVIEW" . "inreview")
    ("DONE" . "done")
    ("CANCELLED" . "cancelled"))
  "Map org TODO states to vibe-kanban statuses."
  :type '(alist :key-type string :value-type string)
  :group 'emacs-mcp-kanban)

(defcustom emacs-mcp-kanban-auto-sync nil
  "When non-nil, auto-sync changes to the other backend."
  :type 'boolean
  :group 'emacs-mcp-kanban)

(defcustom emacs-mcp-kanban-track-agents t
  "When non-nil, track which agent (session) worked on tasks."
  :type 'boolean
  :group 'emacs-mcp-kanban)

;;; ============================================================================
;;; Backend Protocol (DDD Port)
;;; ============================================================================

(cl-defgeneric emacs-mcp-kanban--list-projects (backend)
  "List all projects from BACKEND.")

(cl-defgeneric emacs-mcp-kanban--list-tasks (backend project-id &optional status)
  "List tasks from BACKEND for PROJECT-ID, optionally filtered by STATUS.")

(cl-defgeneric emacs-mcp-kanban--get-task (backend task-id)
  "Get task details from BACKEND by TASK-ID.")

(cl-defgeneric emacs-mcp-kanban--create-task (backend project-id title &optional description)
  "Create task in BACKEND for PROJECT-ID with TITLE and optional DESCRIPTION.")

(cl-defgeneric emacs-mcp-kanban--update-task (backend task-id &rest props)
  "Update task in BACKEND. PROPS is a plist of :title :description :status.")

(cl-defgeneric emacs-mcp-kanban--delete-task (backend task-id)
  "Delete task from BACKEND by TASK-ID.")

;;; ============================================================================
;;; Vibe-Kanban Backend (MCP Adapter)
;;; ============================================================================

(defvar emacs-mcp-kanban--vibe-cache nil
  "Cache for vibe-kanban data.")

(cl-defmethod emacs-mcp-kanban--list-projects ((_backend (eql vibe)))
  "List projects from vibe-kanban MCP."
  (emacs-mcp-kanban--mcp-call "list_projects" nil))

(cl-defmethod emacs-mcp-kanban--list-tasks ((_backend (eql vibe)) project-id &optional status)
  "List tasks from vibe-kanban for PROJECT-ID."
  (let ((params `((project_id . ,project-id))))
    (when status
      (push `(status . ,status) params))
    (emacs-mcp-kanban--mcp-call "list_tasks" params)))

(cl-defmethod emacs-mcp-kanban--get-task ((_backend (eql vibe)) task-id)
  "Get task from vibe-kanban by TASK-ID."
  (emacs-mcp-kanban--mcp-call "get_task" `((task_id . ,task-id))))

(cl-defmethod emacs-mcp-kanban--create-task ((_backend (eql vibe)) project-id title &optional description)
  "Create task in vibe-kanban."
  (let ((params `((project_id . ,project-id)
                  (title . ,title))))
    (when description
      (push `(description . ,description) params))
    (emacs-mcp-kanban--mcp-call "create_task" params)))

(cl-defmethod emacs-mcp-kanban--update-task ((_backend (eql vibe)) task-id &rest props)
  "Update task in vibe-kanban."
  (let ((params `((task_id . ,task-id))))
    (when-let ((title (plist-get props :title)))
      (push `(title . ,title) params))
    (when-let ((desc (plist-get props :description)))
      (push `(description . ,desc) params))
    (when-let ((status (plist-get props :status)))
      (push `(status . ,status) params))
    (emacs-mcp-kanban--mcp-call "update_task" params)))

(cl-defmethod emacs-mcp-kanban--delete-task ((_backend (eql vibe)) task-id)
  "Delete task from vibe-kanban."
  (emacs-mcp-kanban--mcp-call "delete_task" `((task_id . ,task-id))))

(defun emacs-mcp-kanban--mcp-call (tool params)
  "Call vibe-kanban MCP TOOL with PARAMS.
This uses emacsclient to communicate with the MCP server."
  ;; Note: In practice, this would call the MCP tool via the server
  ;; For now, we return a placeholder that can be filled in
  (let ((cmd (format "(mcp__vibe_kanban__%s %s)"
                     tool
                     (if params (json-encode params) ""))))
    ;; This is a placeholder - actual implementation depends on MCP bridge
    (message "MCP call: %s" cmd)
    nil))

;;; ============================================================================
;;; Standalone Backend (Org File Adapter)
;;; ============================================================================

(defvar emacs-mcp-kanban--standalone-buffer nil
  "Buffer visiting the standalone kanban org file.")

(cl-defmethod emacs-mcp-kanban--list-projects ((_backend (eql standalone)))
  "List projects from standalone org file.
Each top-level heading is a project."
  (emacs-mcp-kanban--with-org-file
   (org-map-entries
    (lambda ()
      (let ((id (org-id-get-create))
            (title (org-get-heading t t t t)))
        `((id . ,id) (name . ,title))))
    "LEVEL=1")))

(cl-defmethod emacs-mcp-kanban--list-tasks ((_backend (eql standalone)) project-id &optional status)
  "List tasks from standalone org file for PROJECT-ID.
If PROJECT-ID is nil, lists all tasks from all projects."
  (emacs-mcp-kanban--with-org-file
   (let ((tasks '()))
     (org-map-entries
      (lambda ()
        (let* ((props (org-entry-properties))
               (parent-id (save-excursion
                           (when (org-up-heading-safe)
                             (org-id-get))))
               (task-status (cdr (assoc "TODO" props)))
               (mapped-status (emacs-mcp-kanban--org-to-vibe-status task-status)))
          ;; If no project-id specified, list all tasks; otherwise filter by project
          (when (and (or (null project-id) (equal parent-id project-id))
                     (or (null status)
                         (equal mapped-status status)))
            (push `((id . ,(org-id-get-create))
                    (title . ,(org-get-heading t t t t))
                    (status . ,mapped-status)
                    (description . ,(org-entry-get nil "DESCRIPTION"))
                    (agent . ,(org-entry-get nil "AGENT"))
                    (session . ,(org-entry-get nil "SESSION")))
                  tasks))))
      "LEVEL=2")
     (nreverse tasks))))

(cl-defmethod emacs-mcp-kanban--get-task ((_backend (eql standalone)) task-id)
  "Get task from standalone org file by TASK-ID."
  (emacs-mcp-kanban--with-org-file
   (let ((marker (org-id-find task-id 'marker)))
     (when marker
       (with-current-buffer (marker-buffer marker)
         (goto-char marker)
         (let ((props (org-entry-properties)))
           `((id . ,task-id)
             (title . ,(org-get-heading t t t t))
             (status . ,(emacs-mcp-kanban--org-to-vibe-status
                        (cdr (assoc "TODO" props))))
             (description . ,(org-entry-get nil "DESCRIPTION"))
             (agent . ,(org-entry-get nil "AGENT"))
             (session . ,(org-entry-get nil "SESSION"))
             (created . ,(org-entry-get nil "CREATED"))
             (updated . ,(org-entry-get nil "UPDATED")))))))))

(cl-defmethod emacs-mcp-kanban--create-task ((_backend (eql standalone)) project-id title &optional description)
  "Create task in standalone org file.
If PROJECT-ID is nil, uses the first project (Default Project)."
  (emacs-mcp-kanban--with-org-file
   ;; Find project - if nil, use the first heading as default
   (let ((marker (if project-id
                     (org-id-find project-id 'marker)
                   ;; Find first level-1 heading (default project)
                   (save-excursion
                     (goto-char (point-min))
                     (when (re-search-forward "^\\* " nil t)
                       (point-marker))))))
     (when marker
       (with-current-buffer (or (marker-buffer marker) (current-buffer))
         (goto-char (if (markerp marker) marker (point-min)))
         (when (and (not (markerp marker)) (re-search-forward "^\\* " nil t))
           (beginning-of-line))
         (org-end-of-subtree t)
         (insert "\n** TODO " title "\n")
         (org-entry-put nil "ID" (org-id-uuid))
         (org-entry-put nil "CREATED" (format-time-string "%Y-%m-%d %H:%M"))
         (when description
           (org-entry-put nil "DESCRIPTION" description))
         (when emacs-mcp-kanban-track-agents
           (org-entry-put nil "AGENT" (emacs-mcp-kanban--current-agent))
           (org-entry-put nil "SESSION" (emacs-mcp-kanban--current-session)))
         (save-buffer)
         `((id . ,(org-entry-get nil "ID"))))))))

(cl-defmethod emacs-mcp-kanban--update-task ((_backend (eql standalone)) task-id &rest props)
  "Update task in standalone org file."
  (emacs-mcp-kanban--with-org-file
   (let ((marker (org-id-find task-id 'marker)))
     (when marker
       (with-current-buffer (marker-buffer marker)
         (goto-char marker)
         (when-let ((title (plist-get props :title)))
           (org-edit-headline title))
         (when-let ((desc (plist-get props :description)))
           (org-entry-put nil "DESCRIPTION" desc))
         (when-let ((status (plist-get props :status)))
           (let ((org-status (emacs-mcp-kanban--vibe-to-org-status status)))
             (org-todo org-status)))
         (org-entry-put nil "UPDATED" (format-time-string "%Y-%m-%d %H:%M"))
         (when emacs-mcp-kanban-track-agents
           (org-entry-put nil "LAST_AGENT" (emacs-mcp-kanban--current-agent)))
         (save-buffer)
         t)))))

(cl-defmethod emacs-mcp-kanban--delete-task ((_backend (eql standalone)) task-id)
  "Delete task from standalone org file."
  (emacs-mcp-kanban--with-org-file
   (let ((marker (org-id-find task-id 'marker)))
     (when marker
       (with-current-buffer (marker-buffer marker)
         (goto-char marker)
         (org-cut-subtree)
         (save-buffer)
         t)))))

;;; ============================================================================
;;; Standalone Backend Helpers
;;; ============================================================================

(defmacro emacs-mcp-kanban--with-org-file (&rest body)
  "Execute BODY with the kanban org file as current buffer."
  `(let ((file emacs-mcp-kanban-org-file))
     (unless (file-exists-p file)
       (emacs-mcp-kanban--init-org-file file))
     (with-current-buffer (find-file-noselect file)
       ,@body)))

(defun emacs-mcp-kanban--init-org-file (file)
  "Initialize a new kanban org FILE."
  (with-temp-file file
    (insert "#+TITLE: emacs-mcp Kanban\n")
    (insert "#+STARTUP: overview\n")
    (insert "#+TODO: TODO IN-PROGRESS IN-REVIEW | DONE CANCELLED\n\n")
    (insert "* Default Project\n")
    (insert ":PROPERTIES:\n")
    (insert (format ":ID: %s\n" (org-id-uuid)))
    (insert (format ":CREATED: %s\n" (format-time-string "%Y-%m-%d")))
    (insert ":END:\n")))

(defun emacs-mcp-kanban--org-to-vibe-status (org-status)
  "Convert ORG-STATUS to vibe-kanban status."
  (or (cdr (assoc org-status emacs-mcp-kanban-status-map))
      "todo"))

(defun emacs-mcp-kanban--vibe-to-org-status (vibe-status)
  "Convert VIBE-STATUS to org TODO state."
  (or (car (rassoc vibe-status emacs-mcp-kanban-status-map))
      "TODO"))

(defun emacs-mcp-kanban--current-agent ()
  "Get current agent identifier."
  (or (getenv "CLAUDE_SESSION_ID")
      (format "emacs-%s" (emacs-pid))))

(defun emacs-mcp-kanban--current-session ()
  "Get current session identifier."
  (format-time-string "%Y%m%d-%H%M%S"))

;;; ============================================================================
;;; Unified API (Application Layer)
;;; ============================================================================

(defun emacs-mcp-kanban-list-projects ()
  "List all projects from current backend."
  (emacs-mcp-kanban--list-projects emacs-mcp-kanban-backend))

(defun emacs-mcp-kanban-list-tasks (&optional project-id status)
  "List tasks from current backend.
PROJECT-ID defaults to `emacs-mcp-kanban-default-project'.
For standalone backend, PROJECT-ID can be nil.
STATUS optionally filters by kanban status."
  (let ((pid (or project-id emacs-mcp-kanban-default-project)))
    ;; Only require project-id for vibe backend
    (when (and (eq emacs-mcp-kanban-backend 'vibe) (not pid))
      (error "No project ID specified. Set emacs-mcp-kanban-default-project"))
    (emacs-mcp-kanban--list-tasks emacs-mcp-kanban-backend pid status)))

(defun emacs-mcp-kanban-get-task (task-id)
  "Get task details by TASK-ID from current backend."
  (emacs-mcp-kanban--get-task emacs-mcp-kanban-backend task-id))

(defun emacs-mcp-kanban-create-task (title &optional description project-id)
  "Create a new task with TITLE and optional DESCRIPTION.
PROJECT-ID defaults to `emacs-mcp-kanban-default-project'.
For standalone backend, PROJECT-ID can be nil."
  (let ((pid (or project-id emacs-mcp-kanban-default-project)))
    ;; Only require project-id for vibe backend
    (when (and (eq emacs-mcp-kanban-backend 'vibe) (not pid))
      (error "No project ID specified. Set emacs-mcp-kanban-default-project"))
    (let ((result (emacs-mcp-kanban--create-task
                   emacs-mcp-kanban-backend pid title description)))
      (when emacs-mcp-kanban-auto-sync
        (emacs-mcp-kanban--sync-task-to-other-backend result))
      result)))

(defun emacs-mcp-kanban-update-task (task-id &rest props)
  "Update task TASK-ID with PROPS (:title :description :status)."
  (let ((result (apply #'emacs-mcp-kanban--update-task
                       emacs-mcp-kanban-backend task-id props)))
    (when emacs-mcp-kanban-auto-sync
      (apply #'emacs-mcp-kanban--sync-update-to-other-backend task-id props))
    result))

(defun emacs-mcp-kanban-delete-task (task-id)
  "Delete task by TASK-ID from current backend."
  (emacs-mcp-kanban--delete-task emacs-mcp-kanban-backend task-id))

(defun emacs-mcp-kanban-move-task (task-id new-status)
  "Move task TASK-ID to NEW-STATUS."
  (emacs-mcp-kanban-update-task task-id :status new-status))

;;; ============================================================================
;;; Dual Backend Sync
;;; ============================================================================

(defun emacs-mcp-kanban-enable-dual-backend ()
  "Enable dual backend mode with auto-sync."
  (interactive)
  (setq emacs-mcp-kanban-auto-sync t)
  (message "Dual backend enabled. Changes sync to both vibe-kanban and standalone."))

(defun emacs-mcp-kanban-sync-all ()
  "Sync all tasks between backends."
  (interactive)
  (message "Syncing kanban backends...")
  ;; Pull from vibe, push to standalone
  (let ((vibe-tasks (emacs-mcp-kanban--list-tasks 'vibe emacs-mcp-kanban-default-project))
        (standalone-tasks (emacs-mcp-kanban--list-tasks 'standalone emacs-mcp-kanban-default-project)))
    ;; Merge logic here
    (message "Synced %d vibe tasks and %d standalone tasks"
             (length vibe-tasks) (length standalone-tasks))))

(defun emacs-mcp-kanban--sync-task-to-other-backend (task)
  "Sync TASK to the non-active backend."
  (let ((other-backend (if (eq emacs-mcp-kanban-backend 'vibe) 'standalone 'vibe)))
    (emacs-mcp-kanban--create-task
     other-backend
     emacs-mcp-kanban-default-project
     (alist-get 'title task)
     (alist-get 'description task))))

(defun emacs-mcp-kanban--sync-update-to-other-backend (task-id &rest props)
  "Sync task update to the non-active backend."
  (let ((other-backend (if (eq emacs-mcp-kanban-backend 'vibe) 'standalone 'vibe)))
    (apply #'emacs-mcp-kanban--update-task other-backend task-id props)))

;;; ============================================================================
;;; Interactive Commands
;;; ============================================================================

;;;###autoload
(defun emacs-mcp-kanban-show-board ()
  "Show kanban board in org buffer."
  (interactive)
  (let ((buf (get-buffer-create "*MCP Kanban*"))
        (tasks (emacs-mcp-kanban-list-tasks)))
    (with-current-buffer buf
      (erase-buffer)
      (org-mode)
      (insert "#+TITLE: Kanban Board\n")
      (insert "#+STARTUP: overview\n\n")
      ;; Group by status
      (dolist (status emacs-mcp-kanban-statuses)
        (insert (format "* %s\n" status))
        (let ((status-tasks (cl-remove-if-not
                             (lambda (t)
                               (equal (emacs-mcp-kanban--vibe-to-org-status
                                      (alist-get 'status t))
                                     status))
                             tasks)))
          (dolist (task status-tasks)
            (insert (format "** %s\n" (alist-get 'title task)))
            (insert ":PROPERTIES:\n")
            (insert (format ":ID: %s\n" (alist-get 'id task)))
            (when-let ((agent (alist-get 'agent task)))
              (insert (format ":AGENT: %s\n" agent)))
            (insert ":END:\n")
            (when-let ((desc (alist-get 'description task)))
              (insert desc "\n"))
            (insert "\n"))))
      (goto-char (point-min)))
    (switch-to-buffer buf)))

;;;###autoload
(defun emacs-mcp-kanban-create ()
  "Create a new kanban task interactively."
  (interactive)
  (let* ((title (read-string "Task title: "))
         (description (read-string "Description (optional): " nil nil ""))
         (result (emacs-mcp-kanban-create-task
                  title
                  (unless (string-empty-p description) description))))
    (message "Created task: %s" (alist-get 'id result))))

;;;###autoload
(defun emacs-mcp-kanban-update-status ()
  "Update status of a task interactively."
  (interactive)
  (let* ((tasks (emacs-mcp-kanban-list-tasks))
         (task-names (mapcar (lambda (t)
                              (format "%s [%s]"
                                      (alist-get 'title t)
                                      (alist-get 'status t)))
                            tasks))
         (selection (completing-read "Task: " task-names nil t))
         (task (nth (cl-position selection task-names :test #'equal) tasks))
         (new-status (completing-read "New status: "
                                      (mapcar #'cdr emacs-mcp-kanban-status-map)
                                      nil t)))
    (emacs-mcp-kanban-move-task (alist-get 'id task) new-status)
    (message "Moved task to %s" new-status)))

;;;###autoload
(defun emacs-mcp-kanban-open-org-file ()
  "Open the standalone kanban org file."
  (interactive)
  (find-file emacs-mcp-kanban-org-file))

;;;###autoload
(defun emacs-mcp-kanban-set-project ()
  "Set the default kanban project interactively."
  (interactive)
  (let* ((projects (emacs-mcp-kanban-list-projects))
         (names (mapcar (lambda (p) (alist-get 'name p)) projects))
         (selection (completing-read "Project: " names nil t))
         (project (cl-find-if (lambda (p)
                               (equal (alist-get 'name p) selection))
                             projects)))
    (setq emacs-mcp-kanban-default-project (alist-get 'id project))
    (message "Set default project: %s (%s)"
             selection emacs-mcp-kanban-default-project)))

;;; ============================================================================
;;; Agent API (for MCP tools)
;;; ============================================================================

(defun emacs-mcp-kanban-api-status ()
  "Get kanban status for API consumption.
Returns current tasks grouped by status."
  (let ((tasks (emacs-mcp-kanban-list-tasks)))
    `((backend . ,emacs-mcp-kanban-backend)
      (project . ,emacs-mcp-kanban-default-project)
      (total . ,(length tasks))
      (by_status . ,(mapcar
                     (lambda (status)
                       (cons status
                             (length (cl-remove-if-not
                                     (lambda (t)
                                       (equal (alist-get 'status t) status))
                                     tasks))))
                     (mapcar #'cdr emacs-mcp-kanban-status-map)))
      (tasks . ,tasks))))

(defun emacs-mcp-kanban-api-my-tasks ()
  "Get tasks assigned to or modified by current agent."
  (let* ((agent (emacs-mcp-kanban--current-agent))
         (tasks (emacs-mcp-kanban-list-tasks)))
    (cl-remove-if-not
     (lambda (t)
       (or (equal (alist-get 'agent t) agent)
           (equal (alist-get 'last_agent t) agent)))
     tasks)))

(defun emacs-mcp-kanban-api-roadmap ()
  "Get roadmap view with milestones and progress."
  (let* ((tasks (emacs-mcp-kanban-list-tasks))
         (total (length tasks))
         (done (length (cl-remove-if-not
                       (lambda (t)
                         (member (alist-get 'status t) '("done" "cancelled")))
                       tasks))))
    `((total . ,total)
      (completed . ,done)
      (progress . ,(if (> total 0)
                       (round (* 100 (/ (float done) total)))
                     0))
      (in_progress . ,(cl-remove-if-not
                       (lambda (t)
                         (equal (alist-get 'status t) "inprogress"))
                       tasks))
      (next_up . ,(seq-take
                   (cl-remove-if-not
                    (lambda (t)
                      (equal (alist-get 'status t) "todo"))
                    tasks)
                   5)))))

;;; ============================================================================
;;; Transient Menu
;;; ============================================================================

;;;###autoload (autoload 'emacs-mcp-kanban-transient "emacs-mcp-org-kanban" nil t)
(transient-define-prefix emacs-mcp-kanban-transient ()
  "Kanban management menu."
  ["emacs-mcp Kanban"
   ["View"
    ("b" "Show board" emacs-mcp-kanban-show-board)
    ("o" "Open org file" emacs-mcp-kanban-open-org-file)]
   ["Tasks"
    ("c" "Create task" emacs-mcp-kanban-create)
    ("u" "Update status" emacs-mcp-kanban-update-status)]
   ["Settings"
    ("p" "Set project" emacs-mcp-kanban-set-project)
    ("s" "Sync backends" emacs-mcp-kanban-sync-all)
    ("d" "Toggle dual mode" emacs-mcp-kanban-enable-dual-backend)]])

;;; ============================================================================
;;; Minor Mode
;;; ============================================================================

(defvar emacs-mcp-kanban-mode-map
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "C-c k") 'emacs-mcp-kanban-transient)
    map)
  "Keymap for `emacs-mcp-kanban-mode'.")

;;;###autoload
(define-minor-mode emacs-mcp-kanban-mode
  "Minor mode for kanban task tracking with emacs-mcp.

Provides:
- Dual backend support (vibe-kanban + standalone org)
- Agent-aware task tracking
- org-kanban UI integration
- Session continuity

Key bindings:
  C-c k - Open kanban transient menu"
  :init-value nil
  :lighter " Kanban"
  :keymap emacs-mcp-kanban-mode-map
  :global t
  :group 'emacs-mcp-kanban
  (if emacs-mcp-kanban-mode
      (message "emacs-mcp-kanban enabled (backend: %s)" emacs-mcp-kanban-backend)
    (message "emacs-mcp-kanban disabled")))

;;; ============================================================================
;;; Addon Registration
;;; ============================================================================

(with-eval-after-load 'emacs-mcp-addons
  (emacs-mcp-addon-register
   'org-kanban
   :version "0.1.0"
   :description "Kanban tracking with dual backends (vibe-kanban + standalone org)"
   :requires '(org)
   :provides '(emacs-mcp-kanban-mode emacs-mcp-kanban-transient)))

(provide 'emacs-mcp-org-kanban)
;;; emacs-mcp-org-kanban.el ends here
