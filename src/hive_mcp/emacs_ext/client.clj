(ns hive-mcp.emacs-ext.client
  "Extension-registry façade for `:emacs/*` client keys.

   Replaces the legacy `hive-mcp.emacs.client` delegation shim. Each fn
   resolves a callable from `hive-mcp.extensions.registry` via the
   `:emacs/*` key contributed by hive-emacs's IAddon `(hooks)` method.
   When hive-emacs is not loaded (registry miss), returns a uniformly-
   shaped failure map — never throws.

   Decisions:
   - 20260429195812-0c5dfe8d (outphase free-form ext-keys via IAddon hooks)
   - 20260429230453-7e7627cc (Phase 2: consumers swap shim for registry lookup)"
  (:require [hive-mcp.extensions.registry :as ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:dynamic *default-timeout-ms*
  "Default timeout for emacsclient calls in milliseconds.
   Façade-local default — consumers no longer reach into hive-emacs.client."
  5000)

(defn- not-loaded
  [k]
  {:success false
   :error (str "hive-emacs not loaded — extension " k " unavailable")})

(defn eval-elisp [code]
  (if-let [f (ext/get-extension :emacs/eval-elisp)]
    (f code)
    (not-loaded :emacs/eval-elisp)))

(defn eval-elisp-with-timeout
  ([code] (eval-elisp-with-timeout code *default-timeout-ms*))
  ([code timeout-ms]
   (if-let [f (ext/get-extension :emacs/eval-elisp-with-timeout)]
     (f code timeout-ms)
     (not-loaded :emacs/eval-elisp-with-timeout))))

(defn emacs-running? []
  (if-let [f (ext/get-extension :emacs/running?)] (f) false))

(defn buffer-list []
  (when-let [f (ext/get-extension :emacs/buffer-list)] (f)))

(defn current-buffer []
  (when-let [f (ext/get-extension :emacs/current-buffer)] (f)))

(defn current-file []
  (when-let [f (ext/get-extension :emacs/current-file)] (f)))

(defn switch-to-buffer [buffer-name]
  (when-let [f (ext/get-extension :emacs/switch-to-buffer)] (f buffer-name)))

(defn find-file [file-path]
  (when-let [f (ext/get-extension :emacs/find-file)] (f file-path)))

(defn save-buffer []
  (when-let [f (ext/get-extension :emacs/save-buffer)] (f)))

(defn goto-line [line-number]
  (when-let [f (ext/get-extension :emacs/goto-line)] (f line-number)))

(defn insert-text [text]
  (when-let [f (ext/get-extension :emacs/insert-text)] (f text)))

(defn project-root []
  (when-let [f (ext/get-extension :emacs/project-root)] (f)))

(defn recent-files []
  (when-let [f (ext/get-extension :emacs/recent-files)] (f)))
