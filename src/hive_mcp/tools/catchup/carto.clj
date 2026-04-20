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

(defn get-status
  "Gather carto health status for catchup block.

   Returns:
     {:lsp-up?           bool
      :carto-store?      bool
      :indexed-forms     int
      :last-scan-ts      long or nil
      :scan-status       str or nil
      :scan-result       map or nil}

   All fields are best-effort — failures degrade to safe defaults."
  [project-id]
  (let [scan-info (last-scan-info project-id)]
    (cond-> {:lsp-up?       (lsp-up?)
             :carto-store?  (carto-store-available?)
             :indexed-forms (indexed-forms-count project-id)}
      scan-info (merge scan-info))))
