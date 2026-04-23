(ns hive-mcp.tools.analysis-bridge
  "Enriched `analysis bridge-status` command.

   The upstream lsp-mcp handler only surfaces Emacs lsp-mode workspaces,
   which are often empty when the active analysis path is the Docker
   sidecar cache or in-process clojure-lsp. That produced the misleading
   `{:lsp-available true, :workspace-count 0, :workspaces nil}` after a
   successful scan.

   This namespace contributes a replacement `bridge-status` handler into
   the `analysis` composite tool that splits the view into two axes:

     :workspaces-registered  — Emacs lsp-mode workspaces (live bridge)
     :workspaces-resolved    — projects with resolved analysis in this
                               JVM (sidecar cache + in-process memo)

   `:workspace-count` is retained for backwards compatibility and equals
   `(count (distinct (union registered resolved)))`. `:workspaces` is
   never nil — it is always a vector (empty if no workspaces on either
   axis), with a `:source` tag per entry so callers can tell where the
   data originated.

   Kanban: 20260423132050-0b5d09a6"
  (:require [hive-mcp.extensions.registry :as ext]
            [hive-dsl.result :refer [rescue]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Lazy Resolution Helpers
;; =============================================================================

(defn- try-resolve
  "Resolve a symbol via requiring-resolve, returning nil on failure."
  [sym]
  (rescue nil (requiring-resolve sym)))

;; =============================================================================
;; Registered Workspaces (Emacs lsp-mode bridge)
;; =============================================================================

(defn- emacs-bridge-status
  "Call lsp-mcp's Emacs bridge for live workspace state.
   Returns the bridge payload map, or nil if the bridge isn't wired up."
  []
  (when-let [make-fn (try-resolve 'lsp-mcp.emacs-bridge/make-emacs-bridge)]
    (when-let [status-fn (try-resolve 'lsp-mcp.bridge/bridge-status)]
      (try
        (let [inst   (make-fn)
              avail? (when-let [a (try-resolve 'lsp-mcp.bridge/bridge-available?)]
                       (boolean (a inst)))]
          (when avail?
            (status-fn inst)))
        (catch Exception e
          (log/debug "Emacs bridge-status probe failed:" (ex-message e))
          nil)))))

(defn- normalize-registered
  "Normalize raw Emacs bridge workspaces into a vector of
   {:root str :server-id keyword :source :emacs-lsp} maps.
   Always returns a vector, never nil."
  [bridge-status]
  (->> (:workspaces bridge-status)
       (filter some?)
       (mapv (fn [ws]
               (-> ws
                   (select-keys [:root :server-id])
                   (assoc :source :emacs-lsp))))))

;; =============================================================================
;; Resolved Workspaces (sidecar cache + in-process memo)
;; =============================================================================

(defn- sidecar-resolved
  "Enumerate projects with resolved analysis cached by the LSP sidecar.
   Returns a vector of {:root str :project-id str :status kw :fresh? bool
   :source :sidecar-cache} maps, filtering to projects whose meta.edn
   reports :status :ok. Returns [] when the sidecar cache is absent."
  []
  (let [list-fn  (try-resolve 'lsp-mcp.cache/list-cached-projects)
        meta-fn  (try-resolve 'lsp-mcp.cache/read-meta)
        fresh-fn (try-resolve 'lsp-mcp.cache/cache-fresh?)
        dir-fn   (try-resolve 'lsp-mcp.cache/cache-dir)]
    (if (and list-fn meta-fn)
      (try
        (let [cache-dir (when dir-fn (str (dir-fn)))]
          (->> (list-fn)
               (keep (fn [pid]
                       (let [meta (meta-fn pid)]
                         (when (= :ok (:status meta))
                           (cond-> {:project-id pid
                                    :status     (:status meta)
                                    :source     :sidecar-cache}
                             cache-dir (assoc :root (str cache-dir "/" pid))
                             fresh-fn  (assoc :fresh? (boolean (fresh-fn pid))))))))
               vec))
        (catch Exception e
          (log/debug "Sidecar cache probe failed:" (ex-message e))
          []))
      [])))

(defn- in-process-resolved
  "Inspect the lsp-mcp.tools/analysis-cache atom for a memoized in-process
   analysis. Returns [{:root str :source :in-process :fresh? true}] when a
   fresh entry exists, else []. Accesses the private atom via resolve —
   gracefully no-ops if the structure changes."
  []
  (when-let [cache-var (try-resolve 'lsp-mcp.tools/analysis-cache)]
    (try
      (when-let [{:keys [project-root timestamp-ms result]} @@cache-var]
        (when (and (:ok result)
                   project-root
                   (< (- (System/currentTimeMillis) (or timestamp-ms 0))
                      30000))
          [{:root   project-root
            :source :in-process
            :fresh? true}]))
      (catch Exception e
        (log/debug "In-process analysis-cache probe failed:" (ex-message e))
        nil))))

;; =============================================================================
;; Aggregation
;; =============================================================================

(defn- dedup-by-root
  "De-duplicate workspace maps by :root while preserving first-wins order.
   Entries without :root pass through unchanged."
  [workspaces]
  (:acc (reduce (fn [{:keys [acc seen]} ws]
                  (let [root (:root ws)]
                    (if (and root (contains? seen root))
                      {:acc acc :seen seen}
                      {:acc  (conj acc ws)
                       :seen (cond-> seen root (conj root))})))
                {:acc [] :seen #{}}
                workspaces)))

(defn build-status
  "Pure builder — assembles the enriched bridge-status payload from the
   three probe results. Exposed for testing without hitting side effects."
  [{:keys [registered resolved emacs-probe]}]
  (let [registered (vec (or registered []))
        resolved   (vec (or resolved []))
        all        (dedup-by-root (concat registered resolved))]
    {:lsp-available         (boolean (:lsp-available emacs-probe))
     :workspaces-registered registered
     :workspaces-resolved   resolved
     :workspace-count       (count all)
     :workspaces            all
     :sources               {:emacs-lsp     (count registered)
                             :sidecar-cache (count (filter #(= :sidecar-cache (:source %)) resolved))
                             :in-process    (count (filter #(= :in-process (:source %)) resolved))}}))

(defn bridge-status
  "Public entry point — gathers data from Emacs bridge + sidecar cache
   + in-process memo and returns the enriched status map."
  []
  (let [emacs-probe (emacs-bridge-status)]
    (build-status
     {:emacs-probe emacs-probe
      :registered  (normalize-registered emacs-probe)
      :resolved    (into [] (concat (sidecar-resolved)
                                    (in-process-resolved)))})))

;; =============================================================================
;; Registry Installation
;; =============================================================================

(defn- mcp-handler
  "MCP-shape handler matching the lsp-mcp upstream contract:
   returns {:content [{:type \"text\" :text <pr-str>}]}."
  [_params]
  (try
    {:content [{:type "text" :text (pr-str (bridge-status))}]}
    (catch Exception e
      (log/warn "analysis bridge-status failed:" (ex-message e))
      {:content [{:type "text"
                  :text (pr-str {:error   :bridge-status/exception
                                 :command "bridge-status"
                                 :details {:message (ex-message e)}})}]
       :isError true})))

(defn install!
  "Contribute the enriched bridge-status handler into the `analysis`
   composite tool under addon id :hive-mcp.analysis-bridge. Idempotent —
   safe to call repeatedly. Must run AFTER lsp-mcp registers its addon
   so the enriched handler wins via merge."
  []
  (ext/contribute-commands!
   "analysis" :hive-mcp.analysis-bridge
   {"bridge-status"
    {:handler     mcp-handler
     :params      {}
     :description (str "Live LSP bridge + resolved-analysis status. "
                       "Returns :workspaces-registered (Emacs lsp-mode), "
                       ":workspaces-resolved (sidecar cache + in-process), "
                       "and :workspace-count = union cardinality.")}})
  (log/info "Installed enriched analysis bridge-status handler"))
