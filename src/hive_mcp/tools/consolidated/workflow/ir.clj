(ns hive-mcp.tools.consolidated.workflow.ir
  "HWF2 combinator-workflow verbs for the consolidated workflow tool: list, get,
   describe, author, register, run, status, cancel, and method/vocabulary
   describe. Reaches the hive-workflows facade (hive-workflows.mcp) via
   requiring-resolve — hive-mcp keeps no compile dependency on hive-workflows."
  (:require [clojure.edn :as edn]
            [hive-mcp.tools.core :refer [mcp-error mcp-json]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private sentinel-absent ::absent)

(defn- call-facade
  "Resolve a hive-workflows.mcp fn and apply it, or return the absent sentinel
   when hive-workflows is off the runtime classpath."
  [fn-name & args]
  (if-let [f (try (requiring-resolve (symbol "hive-workflows.mcp" (name fn-name)))
                  (catch Throwable _ nil))]
    (apply f args)
    sentinel-absent))

(defn- ->mcp
  "Bridge a facade return to an MCP response: absent -> loud error, a
   {:error ...} Result -> loud error, anything else -> JSON."
  [v]
  (cond
    (= sentinel-absent v)
    (mcp-error "HWF2 workflow engine unavailable: hive-workflows not on the runtime classpath.")

    (and (map? v) (contains? v :error))
    (mcp-error (pr-str v))

    :else (mcp-json v)))

(defn- read-ast [params]
  (let [raw (:ast params)]
    (cond
      (map? raw)    raw
      (string? raw) (edn/read-string raw)
      :else         ::no-ast)))

(defn- author-opts [params]
  {:workflow-id (:workflow_id params)
   :directory   (:directory params)
   :predicates  (into #{} (map keyword) (:predicates params))})

(defn- with-ast [params f]
  (let [ast (read-ast params)]
    (if (= ::no-ast ast)
      (mcp-error "author/register require an :ast (EDN string or map).")
      (->mcp (f ast (author-opts params))))))

;; ── Verb handlers ────────────────────────────────────────────────────────────

(defn handle-list [params]
  (->mcp (call-facade 'list-workflows
                      {:tags  (into [] (map keyword) (:tags params))
                       :limit (:limit params)})))

(defn handle-get [params]
  (->mcp (call-facade 'get-workflow (:workflow_id params))))

(defn handle-describe [params]
  (->mcp (call-facade 'describe-workflow (:workflow_id params))))

(defn handle-author [params]
  (with-ast params (fn [ast opts] (call-facade 'author ast opts))))

(defn handle-register [params]
  (with-ast params (fn [ast opts] (call-facade 'register ast opts))))

(defn handle-run [params]
  (->mcp (call-facade 'run (:workflow_id params) (or (:opts params) {}))))

(defn handle-status [params]
  (->mcp (call-facade 'status (:workflow_id params))))

(defn handle-cancel [params]
  (->mcp (call-facade 'cancel (:workflow_id params) {})))

(defn handle-describe-method [params]
  (->mcp (if-let [m (:method params)]
           (call-facade 'describe-method m)
           (call-facade 'describe-method))))

(defn handle-describe-vocabulary [params]
  (->mcp (if-let [v (:verb params)]
           (call-facade 'describe-vocabulary v)
           (call-facade 'describe-vocabulary))))
