(ns hive-mcp.test.stub.elisp
  "Stub `:emacs/*` elisp string-builders.

   `hive-mcp.emacs-ext.elisp` resolves each builder from the extension
   registry and returns nil when hive-emacs is not loaded. Every caller then
   passes nil to the transport, so a cold JVM fails with a NullPointerException
   far from the cause.

   `with-elisp-builders` registers builders that emit a deterministic sexp
   naming the feature, the elisp fn and its args — enough for a test to match
   on, without depending on hive-emacs's exact formatting.

   `with-eval-elisp` additionally serves the TRANSPORT keys
   (`:emacs/eval-elisp`, `:emacs/eval-elisp-with-timeout`), which
   `hive-mcp.emacs-ext.client` resolves and which otherwise answer
   {:success false :error \"hive-emacs not loaded ...\"}.

   API:
     with-elisp-builders   run f with the builders registered
     with-eval-elisp       run f with the transport served by a responder
     elisp-fixture         clojure.test :each fixture"
  (:require [clojure.string :as str]
            [hive-mcp.test.stub.extensions :as ext-stub]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn elisp-quote
  "Render V as an elisp literal.

   Contract: numbers bare, strings double-quoted with \\ and \" escaped,
   nil and false as `nil`, true as `t`, keywords/symbols by name, collections
   as a quoted list."
  [v]
  (cond
    (nil? v)     "nil"
    (true? v)    "t"
    (false? v)   "nil"
    (number? v)  (str v)
    (string? v)  (str \" (-> v (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \")
    (keyword? v) (name v)
    (symbol? v)  (str v)
    (coll? v)    (str "'(" (str/join " " (map elisp-quote v)) ")")
    :else        (str v)))

(defn- call-form
  [kind feature fn-sym args]
  (str "(" (name kind) " " feature " " fn-sym
       (when (seq args) (str " " (str/join " " (map elisp-quote args))))
       ")"))

(def builders
  "The `:emacs/*` extension keys `hive-mcp.emacs-ext.elisp` resolves."
  {:emacs/format-elisp
   (fn [template & args] (apply format template args))

   :emacs/require-and-call
   (fn [feature fn-sym & args] (call-form :require-and-call feature fn-sym args))

   :emacs/require-and-call-json
   (fn [feature fn-sym & args] (call-form :require-and-call-json feature fn-sym args))

   :emacs/require-and-call-text
   (fn [feature fn-sym & args] (call-form :require-and-call-text feature fn-sym args))

   :emacs/require-and-call-plist-json
   (fn [feature fn-sym params-map]
     (call-form :require-and-call-plist-json feature fn-sym [params-map]))})

(defn with-elisp-builders
  "Run F with the `:emacs/*` elisp builders registered, restoring prior ones."
  [f]
  (ext-stub/with-extensions builders f))

(defn elisp-fixture
  "clojure.test :each fixture form of `with-elisp-builders`."
  [f]
  (with-elisp-builders f))

(defn with-eval-elisp
  "Run F with the elisp TRANSPORT served by RESPOND.

   RESPOND is (fn [code] {:success bool :result ... :error ...}) — the shape
   `hive-mcp.emacs-ext.client/eval-elisp` promises its callers. The timeout
   variant delegates to the same responder."
  [respond f]
  (ext-stub/with-extensions
    {:emacs/eval-elisp              respond
     :emacs/eval-elisp-with-timeout (fn [code & _] (respond code))}
    f))
