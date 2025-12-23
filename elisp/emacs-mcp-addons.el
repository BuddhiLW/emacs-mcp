;;; emacs-mcp-addons.el --- Addon system for emacs-mcp -*- lexical-binding: t; -*-

;; Copyright (C) 2025 Pedro G. Branquinho
;; Author: Pedro G. Branquinho <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: MIT

;;; Commentary:
;;
;; Lazy-loaded addon system for emacs-mcp integrations.
;;
;; Built-in addons:
;;   - claude-code-mcp: Integration with claude-code.el
;;   - cider-mcp: Integration with CIDER/nREPL
;;   - org-ai-mcp: Integration with org-ai
;;
;; Usage:
;;   ;; Load specific addon on-demand
;;   (emacs-mcp-addon-load 'claude-code-mcp)
;;
;;   ;; Or enable auto-loading for detected packages
;;   (emacs-mcp-addons-auto-load)
;;
;; Writing custom addons:
;;   See `addons/emacs-mcp-addon-template.el` for a skeleton.
;;   Place your addon in the addons/ directory or add its location
;;   to `emacs-mcp-addon-directories'.

;;; Code:

(require 'cl-lib)

;;;; Customization

(defgroup emacs-mcp-addons nil
  "Addon system for emacs-mcp."
  :group 'emacs-mcp
  :prefix "emacs-mcp-addon-")

(defcustom emacs-mcp-addon-directories
  (list (expand-file-name "addons" (file-name-directory
                                     (or load-file-name buffer-file-name))))
  "Directories to search for emacs-mcp addons."
  :type '(repeat directory)
  :group 'emacs-mcp-addons)

(defcustom emacs-mcp-addon-auto-load-list
  '((claude-code-mcp . claude-code)
    (cider-mcp . cider)
    (org-ai-mcp . org-ai))
  "Alist of (ADDON . TRIGGER-FEATURE).
When TRIGGER-FEATURE is loaded, ADDON will be auto-loaded."
  :type '(alist :key-type symbol :value-type symbol)
  :group 'emacs-mcp-addons)

;;;; Registry

(defvar emacs-mcp-addon--registry (make-hash-table :test 'eq)
  "Hash table of registered addons.
Keys are addon symbols, values are plists with :loaded, :version, :description.")

(defvar emacs-mcp-addon--hooks nil
  "Alist of (FEATURE . ADDON) for deferred loading.")

;;;; Core Functions

(defun emacs-mcp-addon--find-file (addon)
  "Find the file for ADDON in addon directories."
  (let ((filename (format "emacs-mcp-%s.el" addon)))
    (cl-some (lambda (dir)
               (let ((path (expand-file-name filename dir)))
                 (when (file-exists-p path) path)))
             emacs-mcp-addon-directories)))

(defun emacs-mcp-addon-available-p (addon)
  "Return non-nil if ADDON is available (file exists)."
  (emacs-mcp-addon--find-file addon))

(defun emacs-mcp-addon-loaded-p (addon)
  "Return non-nil if ADDON is already loaded."
  (plist-get (gethash addon emacs-mcp-addon--registry) :loaded))

;;;###autoload
(defun emacs-mcp-addon-load (addon)
  "Load ADDON if available and not already loaded.
Returns t if loaded successfully, nil otherwise."
  (interactive
   (list (intern (completing-read "Load addon: "
                                  (emacs-mcp-addon-list-available)
                                  nil t))))
  (cond
   ((emacs-mcp-addon-loaded-p addon)
    (message "Addon %s already loaded" addon)
    t)
   ((emacs-mcp-addon--find-file addon)
    (let ((file (emacs-mcp-addon--find-file addon)))
      (load file nil t)
      (puthash addon (plist-put (gethash addon emacs-mcp-addon--registry)
                                :loaded t)
               emacs-mcp-addon--registry)
      (message "Loaded emacs-mcp addon: %s" addon)
      t))
   (t
    (message "Addon %s not found" addon)
    nil)))

(defun emacs-mcp-addon-list-available ()
  "Return list of available addon symbols."
  (let (addons)
    (dolist (dir emacs-mcp-addon-directories)
      (when (file-directory-p dir)
        (dolist (file (directory-files dir nil "^emacs-mcp-.*\\.el$"))
          (when (string-match "^emacs-mcp-\\(.+\\)\\.el$" file)
            (push (intern (match-string 1 file)) addons)))))
    (delete-dups addons)))

(defun emacs-mcp-addon-list-loaded ()
  "Return list of loaded addon symbols."
  (let (loaded)
    (maphash (lambda (addon props)
               (when (plist-get props :loaded)
                 (push addon loaded)))
             emacs-mcp-addon--registry)
    loaded))

;;;; Auto-loading

(defun emacs-mcp-addon--after-load-hook (feature)
  "Hook function to auto-load addons when FEATURE is loaded."
  (dolist (entry emacs-mcp-addon-auto-load-list)
    (when (eq (cdr entry) feature)
      (emacs-mcp-addon-load (car entry)))))

;;;###autoload
(defun emacs-mcp-addons-auto-load ()
  "Enable auto-loading of addons when their trigger packages load.
Uses `emacs-mcp-addon-auto-load-list' to determine mappings."
  (interactive)
  (dolist (entry emacs-mcp-addon-auto-load-list)
    (let ((addon (car entry))
          (trigger (cdr entry)))
      ;; If trigger already loaded, load addon now
      (if (featurep trigger)
          (emacs-mcp-addon-load addon)
        ;; Otherwise, set up deferred loading
        (with-eval-after-load trigger
          (emacs-mcp-addon-load addon))))))

;;;; Registration (for addon authors)

(defun emacs-mcp-addon-register (addon &rest props)
  "Register ADDON with PROPS.
PROPS is a plist that may contain:
  :version     - Version string
  :description - One-line description
  :requires    - List of required features
  :provides    - List of provided features/commands"
  (puthash addon props emacs-mcp-addon--registry))

;;;; Info command

;;;###autoload
(defun emacs-mcp-addon-info ()
  "Display information about available and loaded addons."
  (interactive)
  (let ((buf (get-buffer-create "*emacs-mcp addons*")))
    (with-current-buffer buf
      (erase-buffer)
      (insert "=== emacs-mcp Addons ===\n\n")
      (insert "Available addons:\n")
      (dolist (addon (emacs-mcp-addon-list-available))
        (let ((loaded (emacs-mcp-addon-loaded-p addon))
              (props (gethash addon emacs-mcp-addon--registry)))
          (insert (format "  %s %s\n"
                          (if loaded "[loaded]" "[      ]")
                          addon))
          (when-let ((desc (plist-get props :description)))
            (insert (format "           %s\n" desc)))))
      (insert "\n")
      (insert "Addon directories:\n")
      (dolist (dir emacs-mcp-addon-directories)
        (insert (format "  %s\n" dir)))
      (goto-char (point-min)))
    (display-buffer buf)))

(provide 'emacs-mcp-addons)
;;; emacs-mcp-addons.el ends here
