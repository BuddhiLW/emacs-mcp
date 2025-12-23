;;; emacs-mcp-addon-template.el --- Template for emacs-mcp addons -*- lexical-binding: t; -*-

;; Copyright (C) 2025 Your Name
;; Author: Your Name <your@email.com>
;; Version: 0.1.0
;; Package-Requires: ((emacs "28.1") (emacs-mcp "0.1.0"))
;; Keywords: tools, ai, mcp
;; SPDX-License-Identifier: MIT

;;; Commentary:
;;
;; This is a template for creating emacs-mcp addons.
;;
;; To create your own addon:
;; 1. Copy this file to addons/emacs-mcp-YOUR-ADDON.el
;; 2. Replace "template" with your addon name throughout
;; 3. Implement your integration functions
;; 4. Register with emacs-mcp-addon-register
;;
;; Your addon will be auto-discovered if placed in the addons/ directory.
;; Users can load it with: (emacs-mcp-addon-load 'YOUR-ADDON)
;;
;; For auto-loading when a trigger package loads, add an entry to
;; `emacs-mcp-addon-auto-load-list' in your config:
;;   (add-to-list 'emacs-mcp-addon-auto-load-list '(YOUR-ADDON . trigger-package))

;;; Code:

(require 'emacs-mcp-api)

;; Soft dependency - don't error if target package isn't installed
(declare-function target-package-function "target-package")

;;;; Customization

(defgroup emacs-mcp-template nil
  "Integration between emacs-mcp and target-package."
  :group 'emacs-mcp
  :prefix "emacs-mcp-template-")

(defcustom emacs-mcp-template-auto-context nil
  "When non-nil, automatically include MCP context."
  :type 'boolean
  :group 'emacs-mcp-template)

;;;; Internal

(defvar emacs-mcp-template--initialized nil
  "Whether the addon has been initialized.")

(defun emacs-mcp-template--ensure-api ()
  "Ensure emacs-mcp-api is available."
  (unless (featurep 'emacs-mcp-api)
    (require 'emacs-mcp-api nil t))
  (featurep 'emacs-mcp-api))

;;;; Public API

;;;###autoload
(defun emacs-mcp-template-get-context ()
  "Get MCP context formatted for target-package."
  (when (emacs-mcp-template--ensure-api)
    (emacs-mcp-api-get-context)))

;;;###autoload
(defun emacs-mcp-template-save-to-memory (content type &optional tags)
  "Save CONTENT to emacs-mcp memory as TYPE with optional TAGS."
  (interactive
   (list (if (use-region-p)
             (buffer-substring-no-properties (region-beginning) (region-end))
           (read-string "Content: "))
         (completing-read "Type: " '("note" "snippet" "decision") nil t)
         (split-string (read-string "Tags (comma-separated): ") "," t " ")))
  (when (emacs-mcp-template--ensure-api)
    (emacs-mcp-api-memory-add type content tags)
    (message "Saved to memory as %s" type)))

;;;###autoload
(defun emacs-mcp-template-query-memory (type &optional limit)
  "Query emacs-mcp memory for entries of TYPE, return up to LIMIT entries."
  (when (emacs-mcp-template--ensure-api)
    (emacs-mcp-api-memory-query type nil (or limit 10))))

;;;; Integration hooks (customize these for your target package)

;; Example: Advice to add context to a target function
;; (defun emacs-mcp-template--add-context-advice (orig-fun &rest args)
;;   "Advice to inject MCP context."
;;   (if emacs-mcp-template-auto-context
;;       (let ((ctx (emacs-mcp-template-get-context)))
;;         ;; Modify args or behavior based on context
;;         (apply orig-fun args))
;;     (apply orig-fun args)))

;; Example: Hook function
;; (defun emacs-mcp-template--on-some-hook ()
;;   "Hook to run when something happens in target package."
;;   (when emacs-mcp-template-auto-context
;;     (emacs-mcp-template-save-to-memory
;;      (buffer-substring-no-properties (point-min) (point-max))
;;      "note"
;;      '("auto-logged"))))

;;;; Minor mode (optional)

;;;###autoload
(define-minor-mode emacs-mcp-template-mode
  "Minor mode for emacs-mcp integration with target-package."
  :init-value nil
  :lighter " MCP-T"
  :global t
  :group 'emacs-mcp-template
  (if emacs-mcp-template-mode
      (progn
        ;; Enable: add advice, hooks, keybindings
        ;; (advice-add 'target-function :around #'emacs-mcp-template--add-context-advice)
        (setq emacs-mcp-template--initialized t)
        (message "emacs-mcp-template enabled"))
    ;; Disable: remove advice, hooks
    ;; (advice-remove 'target-function #'emacs-mcp-template--add-context-advice)
    (message "emacs-mcp-template disabled")))

;;;; Transient menu (optional, requires transient.el)

;; (transient-define-prefix emacs-mcp-template-transient ()
;;   "MCP integration menu for target-package."
;;   ["emacs-mcp + target-package"
;;    ["Context"
;;     ("c" "Show context" emacs-mcp-template-show-context)
;;     ("s" "Save to memory" emacs-mcp-template-save-to-memory)]
;;    ["Query"
;;     ("q" "Query memory" emacs-mcp-template-query-memory)]])

;;;; Registration

;; Register this addon with emacs-mcp
(with-eval-after-load 'emacs-mcp-addons
  (emacs-mcp-addon-register
   'addon-template
   :version "0.1.0"
   :description "Template addon for emacs-mcp"
   :requires '(emacs-mcp-api)
   :provides '(emacs-mcp-template-mode)))

(provide 'emacs-mcp-addon-template)
;;; emacs-mcp-addon-template.el ends here
