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

(declare eval-elisp)

(def ^:const symbol-void-strike-limit
  "After this many `void-variable` / `void-function` failures for the same
   elisp symbol, `probe-feature!` and `probe-fboundp!` short-circuit further
   IPC round-trips and return cached failure (ENGINE-L0.2, incident 2026-05-11
   — runaway emacsclient probes on a missing feature can stall the swarm)."
  3)

(defonce ^:private *symbol-strike-counts
  ^{:doc "elisp-symbol → strike count. Reset on first successful probe."}
  (atom {}))

(defonce ^:private *symbol-disabled
  ^{:doc "Set of elisp symbols whose probe path is shorted (strike limit hit)."}
  (atom #{}))

(defn symbol-strike-counts
  "Diagnostic snapshot of the per-symbol probe miss counter."
  []
  @*symbol-strike-counts)

(defn disabled-symbols
  "Diagnostic snapshot of probe-disabled elisp symbols."
  []
  @*symbol-disabled)

(defn reset-symbol-strikes!
  "Clear strike counts and the disabled-symbol set. Test/diagnostic only."
  []
  (reset! *symbol-strike-counts {})
  (reset! *symbol-disabled #{}))

(defn- void-symbol-error?
  "Return true if `error` is an elisp void-symbol failure for `sym`.
   Matches both `void-function` and `void-variable` shapes."
  [error sym]
  (boolean
   (and (string? error)
        (re-find (re-pattern (str "void-(?:variable|function)\\s+" sym)) error))))

(defn- record-symbol-strike!
  "Increment the strike counter for `sym`. Disables further probes once
   the count crosses `symbol-void-strike-limit`."
  [sym]
  (let [n (-> *symbol-strike-counts
              (swap! update sym (fnil inc 0))
              (get sym))]
    (when (>= n symbol-void-strike-limit)
      (swap! *symbol-disabled conj sym)
      (log/warn "[emacsclient] Disabling probe of" sym
                "— hit" n "void-symbol strikes (ENGINE-L0.2)"))
    n))

(defn- clear-symbol-strike!
  "Drop the strike counter and disabled flag for `sym` after a healthy probe."
  [sym]
  (swap! *symbol-strike-counts dissoc sym)
  (swap! *symbol-disabled disj sym))

(defn probe-feature!
  "Strike-tracked wrapper around (featurep '<feature>).
   Returns true / false / :disabled — the :disabled response means the
   3-strike limit has been hit; the caller MUST treat the feature as
   unavailable without scheduling further probes (ENGINE-L0.2)."
  [feature]
  (if (contains? @*symbol-disabled feature)
    :disabled
    (let [code (format "(featurep '%s)" (name feature))
          {:keys [success result error]} (eval-elisp code)]
      (cond
        (and success (= result "t"))
        (do (clear-symbol-strike! feature) true)

        (and success (= result "nil"))
        (do (record-symbol-strike! feature) false)

        (void-symbol-error? error feature)
        (do (record-symbol-strike! feature) false)

        :else false))))

(defn probe-fboundp!
  "Strike-tracked wrapper around (fboundp '<sym>).
   Returns true / false / :disabled."
  [sym]
  (if (contains? @*symbol-disabled sym)
    :disabled
    (let [code (format "(fboundp '%s)" (name sym))
          {:keys [success result error]} (eval-elisp code)]
      (cond
        (and success (= result "t"))
        (do (clear-symbol-strike! sym) true)

        (and success (= result "nil"))
        (do (record-symbol-strike! sym) false)

        (void-symbol-error? error sym)
        (do (record-symbol-strike! sym) false)

        :else false))))

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