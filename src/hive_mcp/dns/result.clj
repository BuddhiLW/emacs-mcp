(ns hive-mcp.dns.result
  "Re-exports hive-dsl.result — single source of truth for Result monad.

   Canonical implementation lives in hive-dsl. This namespace re-exports
   all public vars so existing hive-mcp code continues to work unchanged.

   Usage (both equivalent):
     (require '[hive-mcp.dns.result :as result])    ;; legacy
     (require '[hive-dsl.result :as result])         ;; canonical"
  (:require [hive-dsl.result]))

;; --- Re-export all public vars from hive-dsl.result --------------------------
;; Macros must be re-defined (potemkin not on classpath), fns use def aliases.

;; Functions — simple var aliases
(def ok       hive-dsl.result/ok)
(def err      hive-dsl.result/err)
(def ok?      hive-dsl.result/ok?)
(def err?     hive-dsl.result/err?)
(def bind     hive-dsl.result/bind)
(def map-ok   hive-dsl.result/map-ok)
(def map-err  hive-dsl.result/map-err)
(def rescue-fn     hive-dsl.result/rescue-fn)
(def guard-fn      hive-dsl.result/guard-fn)
(def ensure-result hive-dsl.result/ensure-result)

;; Macros — must re-define (def doesn't preserve macro metadata)
(defmacro let-ok
  "Re-export of hive-dsl.result/let-ok. See canonical docstring."
  [bindings & body]
  `(hive-dsl.result/let-ok ~bindings ~@body))

(defmacro try-effect
  "Re-export of hive-dsl.result/try-effect. See canonical docstring."
  [& body]
  `(hive-dsl.result/try-effect ~@body))

(defmacro try-effect*
  "Re-export of hive-dsl.result/try-effect*. See canonical docstring."
  [category & body]
  `(hive-dsl.result/try-effect* ~category ~@body))

(defmacro rescue
  "Re-export of hive-dsl.result/rescue. See canonical docstring."
  [fallback & body]
  `(hive-dsl.result/rescue ~fallback ~@body))

(defmacro guard
  "Re-export of hive-dsl.result/guard. See canonical docstring."
  [catch-class fallback & body]
  `(hive-dsl.result/guard ~catch-class ~fallback ~@body))

(defmacro rescue-log
  "Re-export of hive-dsl.result/rescue-log. Log+fallback sugar."
  [label fallback & body]
  `(hive-dsl.result/rescue-log ~label ~fallback ~@body))

(defmacro rescue-interrupt
  "Re-export of hive-dsl.result/rescue-interrupt. Silent on
   InterruptedException, log+fallback on other throwables."
  [label fallback & body]
  `(hive-dsl.result/rescue-interrupt ~label ~fallback ~@body))

(defmacro ok->
  "Re-export of hive-dsl.result/ok->. Thread-first through Results with smart-wrap."
  [expr & forms]
  `(hive-dsl.result/ok-> ~expr ~@forms))

(defmacro ok->>
  "Re-export of hive-dsl.result/ok->>. Thread-last through Results with smart-wrap."
  [expr & forms]
  `(hive-dsl.result/ok->> ~expr ~@forms))
