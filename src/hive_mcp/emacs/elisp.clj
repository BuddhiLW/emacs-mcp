(ns hive-mcp.emacs.elisp
  "Elisp generation helpers for hive-mcp.

   This namespace provides string-based helpers for generating elisp code,
   with optional clojure-elisp integration for more advanced use cases.

   The string helpers are stable and don't require clojure-elisp to be mature.
   As clojure-elisp matures, more features can be migrated to use it.

   Usage:
     (require '[hive-mcp.emacs.elisp :as el])

     ;; String-based helpers (stable, recommended)
     (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status)
     ;; => \"(progn (require 'hive-mcp-magit nil t) ...)\"

     (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-log 10)
     ;; => \"(progn ... (json-encode (hive-mcp-magit-api-log 10)) ...)\"

     ;; clojure-elisp integration (experimental)
     (el/emit '(if (> x 0) \"yes\" \"no\"))"
  (:require [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; String-Based Helpers (stable, no clojure-elisp dependency)
;; =============================================================================

(defn- elisp-quote
  "Quote a Clojure value for elisp.
   Strings get double-quoted, symbols get quoted, numbers pass through."
  [v]
  (cond
    (nil? v) "nil"
    (string? v) (pr-str v)
    (number? v) (str v)
    (keyword? v) (str ":" (name v))
    (symbol? v) (str "'" v)
    (vector? v) (str "'(" (str/join " " (map elisp-quote v)) ")")
    :else (pr-str v)))

(defn- format-args
  "Format arguments for elisp function call.
   Uses elisp-quote for proper quoting of symbols, strings, etc."
  [args]
  (if (seq args)
    (str " " (str/join " " (map elisp-quote args)))
    ""))

(defn require-and-call-json
  "Generate elisp: require feature, call function, JSON-encode result.

   This is the most common pattern in hive-mcp tool handlers.

   Examples:
     (require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status)
     (require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-log 10)
     (require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-eval code-str)"
  [feature fn-sym & args]
  (format "(progn
  (require '%s nil t)
  (if (fboundp '%s)
      (json-encode (%s%s))
    (json-encode (list :error \"%s not loaded\"))))"
          feature fn-sym fn-sym (format-args args) feature))

(defn require-and-call-plist-json
  "Generate elisp: require feature, call function with a single plist arg, JSON-encode result.

   Eliminates positional arg ordering bugs at the CLJ↔Elisp boundary.
   params-map is a Clojure map converted to an elisp plist. Nil values are omitted.

   Examples:
     (require-and-call-plist-json 'hive-mcp-cider 'hive-mcp-cider-spawn-session-from-plist
                                  {:name \"foo\" :repl-type 'clj :project-dir \"/path\"})
     ;; => (progn ... (hive-mcp-cider-spawn-session-from-plist (list :name \"foo\" :repl-type 'clj :project-dir \"/path\")))"
  [feature fn-sym params-map]
  (let [plist-str (->> params-map
                       (remove (comp nil? val))
                       (mapcat (fn [[k v]] [(str ":" (name k)) (elisp-quote v)]))
                       (str/join " "))]
    (format "(progn
  (require '%s nil t)
  (if (fboundp '%s)
      (json-encode (%s (list %s)))
    (json-encode (list :error \"%s not loaded\"))))"
            feature fn-sym fn-sym plist-str feature)))

(defn require-and-call-text
  "Generate elisp: require feature, call function, return as text.

   Similar to require-and-call-json but returns plain text.

   Examples:
     (require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-diff 'staged)"
  [feature fn-sym & args]
  (format "(progn
  (require '%s nil t)
  (if (fboundp '%s)
      (%s%s)
    \"Error: %s not loaded\"))"
          feature fn-sym fn-sym (format-args args) feature))

(defn require-and-call
  "Generate elisp: require feature, call function, error if not available.

   Examples:
     (require-and-call 'hive-mcp-magit 'hive-mcp-magit-api-stage files)"
  [feature fn-sym & args]
  (format "(progn
  (require '%s nil t)
  (if (fboundp '%s)
      (%s%s)
    (error \"%s not loaded\")))"
          feature fn-sym fn-sym (format-args args) feature))

(defn fboundp-call-json
  "Generate elisp: check if function exists, call it, JSON-encode.

   Use when feature is already required elsewhere.

   Example:
     (fboundp-call-json 'hive-mcp-api-status)"
  [fn-sym & args]
  (format "(if (fboundp '%s)
    (json-encode (%s%s))
  (json-encode (list :error \"%s not available\")))"
          fn-sym fn-sym (format-args args) fn-sym))

;; =============================================================================
;; clojure-elisp Integration (experimental - use as clojure-elisp matures)
;; =============================================================================

(def ^:private clel-available?
  "Check if clojure-elisp is available at runtime."
  (delay
    (try
      (require 'clojure-elisp.core)
      true
      (catch Exception _ false))))

(defn emit
  "Compile a Clojure form to elisp string using clojure-elisp.

   Falls back to pr-str if clojure-elisp is not available.

   Example:
     (emit '(buffer-name)) => \"(buffer-name)\"
     (emit '(if (> x 0) \"yes\" \"no\"))"
  [form]
  (if @clel-available?
    ((resolve 'clojure-elisp.core/emit) form)
    (pr-str form)))

(defn emit-forms
  "Compile multiple forms to elisp, joined by newlines."
  [forms]
  (str/join "\n\n" (map emit forms)))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn wrap-progn
  "Wrap multiple elisp strings in a progn form."
  [& elisp-strs]
  (str "(progn\n  " (str/join "\n  " elisp-strs) ")"))

(defn format-elisp
  "Simple elisp template formatting.

   Example:
     (format-elisp \"(goto-line %d)\" 42)
     (format-elisp \"(switch-to-buffer %s)\" (pr-str buffer-name))"
  [template & args]
  (apply format template args))

(comment
  ;; Test string helpers
  (require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status)
  (require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-log 10)
  (require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-diff 'staged)

  ;; Test clojure-elisp integration (when available)
  (emit '(buffer-name))
  (emit '(if (> x 0) "yes" "no")))
