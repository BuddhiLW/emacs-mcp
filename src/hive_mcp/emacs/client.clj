(ns hive-mcp.emacs.client
  "Delegation shim — routes to hive-emacs.client.

   Full emacsclient implementation (50 forms, 3-state circuit breaker,
   daemon death detection) extracted to hive-emacs project. This shim
   preserves backward compatibility for 28 direct callers in hive-mcp core.

   Dynamic vars kept for backward compat; real vars live in hive-emacs.client."
  (:require [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ---------------------------------------------------------------------------
;; Dynamic vars — backward compat for code that binds these
;; ---------------------------------------------------------------------------

(def ^:dynamic *emacsclient-path*
  "Path to emacsclient binary."
  (or (System/getenv "EMACSCLIENT") "emacsclient"))

(def ^:dynamic *emacs-socket-name*
  "Emacs daemon socket name."
  (System/getenv "EMACS_SOCKET_NAME"))

(def ^:dynamic *default-timeout-ms*
  "Default timeout for emacsclient calls in milliseconds."
  5000)

(def ^:dynamic *max-timeout-ms*
  "Hard ceiling for any emacsclient call."
  30000)

(def ^:dynamic *circuit-breaker-cooldown-ms*
  "Minimum time (ms) the circuit stays open before recovery probe."
  5000)

;; ---------------------------------------------------------------------------
;; Delegation core
;; ---------------------------------------------------------------------------

(defn- resolve-emacs-fn
  "Resolve a function from hive-emacs.client. Returns nil if not available."
  [sym]
  (rescue nil (requiring-resolve sym)))

(defn eval-elisp-with-timeout
  "Execute elisp code with a timeout.
   Delegates to hive-emacs.client/eval-elisp-with-timeout.
   Returns a map with :success, :result or :error keys.
   On timeout, returns {:success false :error \"...\" :timed-out true}
   On circuit-open, returns {:success false :error \"...\" :circuit-open true}"
  ([code] (eval-elisp-with-timeout code *default-timeout-ms*))
  ([code timeout-ms]
   (if-let [f (resolve-emacs-fn 'hive-emacs.client/eval-elisp-with-timeout)]
     ;; Rebind hive-emacs *max-timeout-ms* when caller needs more than 30s
     ;; (e.g. CIDER eval with long-running expressions).
     (if-let [max-var (when (> (or timeout-ms 0) *max-timeout-ms*)
                         (resolve-emacs-fn 'hive-emacs.client/*max-timeout-ms*))]
       (with-bindings {max-var timeout-ms}
         (f code timeout-ms))
       (f code timeout-ms))
     {:success false
      :error "hive-emacs not on classpath — Emacs integration unavailable"})))

(defn eval-elisp
  "Execute elisp code in running Emacs and return the result.
   Returns a map with :success, :result or :error keys."
  [code]
  (eval-elisp-with-timeout code *default-timeout-ms*))

(defn eval-elisp!
  "Execute elisp and return result string, or throw on non-timeout error.
   On timeout, returns {:error :timeout :msg \"...\"}.
   On circuit-open, returns {:error :circuit-open :msg \"...\"}."
  [code]
  (let [{:keys [success result error timed-out circuit-open]} (eval-elisp code)]
    (cond
      success      result
      timed-out    {:error :timeout :msg error}
      circuit-open {:error :circuit-open :msg error}
      :else        (throw (ex-info "Elisp evaluation failed"
                                   {:error error :code code})))))

(defn emacs-running?
  "Check if Emacs server is running. Returns false on timeout."
  []
  (:success (eval-elisp-with-timeout "t" 2000)))

;; ---------------------------------------------------------------------------
;; Convenience functions — delegate through eval-elisp!
;; Kept for any residual callers; tool handlers use eval-elisp directly.
;; ---------------------------------------------------------------------------

(defn buffer-list [] (eval-elisp! "(mapcar #'buffer-name (buffer-list))"))
(defn current-buffer [] (eval-elisp! "(buffer-name)"))
(defn current-file []
  (let [result (eval-elisp! "(buffer-file-name)")]
    (when (not= result "nil") result)))
(defn buffer-content [buffer-name]
  (eval-elisp! (format "(with-current-buffer \"%s\" (buffer-string))" buffer-name)))
(defn switch-to-buffer [buffer-name]
  (eval-elisp! (format "(switch-to-buffer \"%s\")" buffer-name)))
(defn find-file [file-path]
  (eval-elisp! (format "(find-file \"%s\")" file-path)))
(defn save-buffer [] (eval-elisp! "(save-buffer)"))
(defn goto-line [line-number]
  (eval-elisp! (format "(goto-line %d)" line-number)))
(defn insert-text [text]
  (eval-elisp! (format "(insert \"%s\")" (clojure.string/escape text {\" "\\\"" \\ "\\\\"}))))
(defn project-root []
  (let [result (eval-elisp! "(project-root (project-current))")]
    (when (not= result "nil") result)))
(defn recent-files [] (eval-elisp! "recentf-list"))
