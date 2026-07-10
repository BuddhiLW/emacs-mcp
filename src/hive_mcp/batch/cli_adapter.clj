(ns hive-mcp.batch.cli-adapter
  "Bridge — expose a `Batchable` runner (decision 20260429230453-7e7627cc)
   as a single-command CLI batch handler whose envelope matches the legacy
   `hive-mcp.tools.cli/make-batch-handler` output shape.

   Why this exists
   ─────────────────────────────────────────────────────────────────────
   The consolidated tools (memory, kg, agent, …) historically registered
   their `batch-*` commands by calling `make-batch-handler` directly. That
   helper iterates ops sequentially (N store round-trips) and emits a
   deprecation log every call. The replacement path is to route batches
   through an explicit `Batchable` record — the same one `multi` already
   uses — and translate its result back to the legacy `{:results :summary}`
   envelope the LLM-facing CLI tools have always returned.

   Result shape preserved
   ─────────────────────────────────────────────────────────────────────
   Callers cannot tell whether they hit `make-batch-handler` or this
   adapter: both return
       {:type \"text\" :text (json/str {:results [...] :summary {...}})}
   with per-op `{:success :command :result|:error}` entries."
  (:require [clojure.data.json :as json]
            [hive-mcp.tools.core :refer [mcp-error]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- op-result->legacy
  "Project one Batchable per-op result onto the legacy CLI per-op shape."
  [cmd-name {:keys [success result data error]}]
  (cond-> {:success (boolean success)
           :command cmd-name}
    (some? result) (assoc :result result)
    (and (nil? result) (some? data)) (assoc :result data)
    (some? error)  (assoc :error error)))

(defn- batchable-result->cli-envelope
  "Translate a `Batchable` result `{:success :waves :summary :errors?}` into
   the legacy `{:type \"text\" :text json}` envelope."
  [cmd-name {:keys [waves summary errors]}]
  (let [ordered  (into (sorted-map) (or waves {}))
        per-op   (vec (mapcat (fn [[_wave-num {:keys [results]}]]
                                (mapv (partial op-result->legacy cmd-name)
                                      results))
                              ordered))
        payload  (cond-> {:results per-op
                          :summary (select-keys summary [:total :success :failed])}
                   (seq errors) (assoc :errors errors))]
    {:type "text"
     :text (json/write-str payload)}))

(defn cli-batch-handler
  "Build a single-command batch handler backed by a Batchable `run-fn`.

   Drop-in replacement for `(hive-mcp.tools.cli/make-batch-handler
   {cmd-kw single-op-handler})` at the registration site.

   Required keys:
     `:run-fn`     `(fn [ops opts] → Batchable result)` — typically a
                   `hive-mcp.tools.<tool>.batch/run-batch` var.
     `:cmd-kw`     command keyword (e.g. `:add`, `:edge`).

   Optional keys:
     `:tool-name`  `:tool` string injected into each op for the runner's
                   resolver. Defaults to `(name cmd-kw)`. Use this when
                   the underlying runner expects a nested path
                   (e.g. `\"agent spawn\"` for `swarm.batch/run-batch`).

   Per-op `:command` is forced to `(name cmd-kw)` so callers cannot
   smuggle a different command in via the operation map."
  [{:keys [run-fn cmd-kw tool-name]}]
  (assert (ifn? run-fn)         ":run-fn must be a fn")
  (assert (keyword? cmd-kw)     ":cmd-kw must be a keyword")
  (let [cmd-name (name cmd-kw)
        op-tool  (or tool-name cmd-name)]
    (fn [{:keys [operations] :as _params}]
      (if (or (nil? operations) (empty? operations))
        (mcp-error
         (str "operations is required (array of {command, ...} objects) for batch-"
              cmd-name))
        (let [ops (mapv (fn [op] (assoc op :tool op-tool :command cmd-name))
                        operations)]
          (batchable-result->cli-envelope cmd-name (run-fn ops {})))))))
