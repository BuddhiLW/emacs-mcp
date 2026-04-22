(ns hive-mcp.tools.catchup.carto
  "Carto health status for catchup workflow.

   Gathers at-a-glance cartography readiness:
   - LSP sidecar up/down
   - Indexed form count (from :carto store)
   - Last scan timestamp (from cartography/tools scan-state)

   Pure read-only — does NOT trigger scans or modify carto state."
  (:require [hive-mcp.extensions.registry :as ext]
            [taoensso.timbre :as log]
            [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- try-resolve
  "Resolve a symbol lazily. Returns var or nil."
  [sym]
  (rescue nil (requiring-resolve sym)))

(defn- lsp-up?
  "Check if LSP sidecar Docker container is running.
   Returns false on any failure (missing dep, Docker down, etc.)."
  []
  (if-let [f (try-resolve 'lsp-mcp.sidecar/sidecar-running?)]
    (try (boolean (f)) (catch Exception _ false))
    false))

(defn- carto-store-available?
  "Check if :carto memory store backend is registered."
  []
  (if-let [f (try-resolve 'hive-mcp.vectordb.carto-facade/available?)]
    (try (boolean (f)) (catch Exception _ false))
    false))

(defn- indexed-forms-count
  "Count carto snippets in the :carto store. Returns 0 on failure.

   Routes through hive-mcp.vectordb.carto-facade (the :carto slot) — the
   same backend scan.clj writes to. Previously queried hive-mcp.chroma.crud
   directly, which reads :default (Chroma) and missed Milvus-backed carto
   snippets, always returning 0."
  [project-id]
  (if-let [query-fn (try-resolve 'hive-mcp.vectordb.carto-facade/query-entries)]
    (try
      (count (query-fn :tags       ["carto"]
                       :limit      10000
                       :project-id (or project-id "hive-mcp")))
      (catch Exception e
        (log/debug "carto indexed-forms-count failed:" (ex-message e))
        0))
    0))

(defn- last-scan-info
  "Extract last scan timestamp via :carto/scan-state-snapshot extension.
   Returns {:last-scan-ts long :scan-status keyword} or nil."
  [project-id]
  (if-let [snapshot-fn (ext/get-extension :carto/scan-state-snapshot)]
    (try
      (let [state (snapshot-fn (or project-id "hive-mcp"))]
        (when state
          (cond-> {:scan-status (name (:status state))}
            (:finished-at state) (assoc :last-scan-ts (:finished-at state))
            (:started-at state)  (assoc :started-at (:started-at state))
            (:result state)      (assoc :scan-result
                                        (select-keys (:result state)
                                                     [:snippets :files :edges]))
            (:error state)       (assoc :scan-error (:error state)))))
      (catch Exception e
        (log/debug "carto last-scan-info failed:" (ex-message e))
        nil))
    nil))

(defn- derive-readiness
  "Derive a readiness keyword + action-oriented hint + warnings vector from
   raw carto fields. Keys on actual state so callers don't have to parse
   prose.

   Readiness values:
     :lsp-down   — sidecar not up; structural-edit tasks will fail
     :error      — last scan errored
     :scanning   — scan currently running; forms may be partial
     :empty      — store reachable but indexed-forms=0 (scan required)
     :stale      — ambiguous: forms=0 + no scan-status at all (never scanned)
     :ready      — store populated and (if known) last scan succeeded"
  [{:keys [lsp-up? carto-store? indexed-forms scan-status scan-error]}]
  (let [status-kw (some-> scan-status keyword)
        empty?    (or (nil? indexed-forms) (zero? indexed-forms))]
    (cond
      (not lsp-up?)
      {:readiness :lsp-down
       :warnings  ["lsp-down — LSP sidecar not running; structural-edit tasks will fail. Start the sidecar or run `hive_mcp lsp up`."]
       :hint      "LSP sidecar down — structural-edit tasks will fail until it is started."}

      (not carto-store?)
      {:readiness :store-unavailable
       :warnings  ["carto-store unavailable — :carto memory store backend is not registered."]
       :hint      "Carto store backend not registered — no structural queries available."}

      (or (= status-kw :error) scan-error)
      {:readiness :error
       :warnings  [(str "carto scan error"
                        (when scan-error (str " — " scan-error))
                        " — fix the underlying issue, then re-run codebase-map scan :scope <dir>.")]
       :hint      "Last carto scan errored — re-run codebase-map scan :scope <dir> after resolving the error."}

      (= status-kw :running)
      {:readiness :scanning
       :warnings  (if empty?
                    ["carto scan in progress — indexed-forms=0 until the scan finishes. Defer structural queries or wait for completion."]
                    [])
       :hint      "Carto scan in progress — structural queries may return partial results until it finishes."}

      empty?
      {:readiness :empty
       :warnings  ["carto empty — run codebase-map scan :scope <dir> before structural queries (carto_refs, carto_deps, etc.)."]
       :hint      "Carto store empty — run codebase-map scan :scope <dir> before any carto_* query."}

      :else
      {:readiness :ready
       :warnings  []
       :hint      "Carto store populated — structural queries should return results."})))

(defn get-status
  "Gather carto health status for catchup block.

   Returns:
     {:lsp-up?           bool
      :carto-store?      bool
      :indexed-forms     int
      :last-scan-ts      long or nil
      :scan-status       str or nil
      :scan-result       map or nil
      :readiness         keyword (:ready :empty :scanning :error :lsp-down :store-unavailable)
      :warnings          vector of strings — prominent, action-oriented
      :hint              action-oriented message keyed to actual state}

   All fields are best-effort — failures degrade to safe defaults.

   The `:warnings` and state-keyed `:hint` replace the previous generic
   hint prose so callers can branch on `:readiness` / inspect `:warnings`
   without NLP."
  [project-id]
  (let [scan-info (last-scan-info project-id)
        base      (cond-> {:lsp-up?       (lsp-up?)
                           :carto-store?  (carto-store-available?)
                           :indexed-forms (indexed-forms-count project-id)}
                    scan-info (merge scan-info))
        readiness (derive-readiness base)]
    (merge base readiness)))
