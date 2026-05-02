(ns hive-mcp.emacs-ext.elisp
  "Extension-registry façade for `:emacs/*` elisp-generation keys.

   Replaces the legacy `hive-mcp.emacs.elisp` delegation shim. Each fn
   resolves via `:emacs/format-elisp`, `:emacs/require-and-call*` keys
   contributed by hive-emacs's IAddon `(hooks)` method. When hive-emacs
   is not loaded, fns return nil — these are pure string-builders, so a
   missing impl is a programmer error (loud nil surfaces faster than a
   stub string).

   Decisions:
   - 20260429195812-0c5dfe8d, 20260429230453-7e7627cc (Phase 2)."
  (:require [hive-mcp.extensions.registry :as ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn format-elisp
  [template & args]
  (when-let [f (ext/get-extension :emacs/format-elisp)]
    (apply f template args)))

(defn require-and-call
  [feature fn-sym & args]
  (when-let [f (ext/get-extension :emacs/require-and-call)]
    (apply f feature fn-sym args)))

(defn require-and-call-json
  [feature fn-sym & args]
  (when-let [f (ext/get-extension :emacs/require-and-call-json)]
    (apply f feature fn-sym args)))

(defn require-and-call-text
  [feature fn-sym & args]
  (when-let [f (ext/get-extension :emacs/require-and-call-text)]
    (apply f feature fn-sym args)))

(defn require-and-call-plist-json
  [feature fn-sym params-map]
  (when-let [f (ext/get-extension :emacs/require-and-call-plist-json)]
    (f feature fn-sym params-map)))
