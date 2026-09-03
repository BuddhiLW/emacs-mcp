(ns hive-mcp.tools.registry
  "MCP tool definitions registry — aggregates consolidated tool definitions.

   Domain-grouped tool roots: code, swarm, memory, project, fs, git, emacs, web, preset, multi.
   Core subdomains are statically defined. Addon subdomains injected at runtime (OCP).

   The advertised surface is shrunk to <=10 roots via a visibility gate
   (apply-visibility-gate + config [:tool-roots :visible]): non-allowlisted
   tools are marked :deprecated so tools/list hides them while tools/call
   keeps them callable (back-compat)."
  ;; The legacy consolidated namespaces below are loaded for their
  ;; REGISTRATION side effect: each registers its handlers at load time and
  ;; multi routing resolves them by name, so nothing here calls them through
  ;; their alias. Excluded by name rather than by switching the linter off, so
  ;; a genuinely dead require in this ns still reports.
  {:clj-kondo/config
   '{:linters
     {:unused-namespace
      {:exclude [hive-mcp.tools.consolidated.agent
                 hive-mcp.tools.consolidated.wave
                 hive-mcp.tools.consolidated.hivemind
                 hive-mcp.tools.consolidated.agora
                 hive-mcp.tools.consolidated.olympus
                 hive-mcp.tools.consolidated.kanban
                 hive-mcp.tools.consolidated.config
                 hive-mcp.tools.consolidated.session
                 hive-mcp.tools.consolidated.workflow
                 hive-mcp.tools.consolidated.kg
                 hive-mcp.tools.consolidated.migration
                 hive-mcp.tools.consolidated.magit
                 hive-mcp.tools.composite]}}}}
  (:require [hive-mcp.channel.core :as channel]
   ;; Domain-grouped tool roots
            [hive-mcp.tools.consolidated.code :as c-code]
            [hive-mcp.tools.consolidated.swarm :as c-swarm]
            [hive-mcp.tools.consolidated.memory :as c-memory]
            [hive-mcp.tools.consolidated.project :as c-project]
            [hive-mcp.tools.consolidated.fs :as c-fs]
            [hive-mcp.tools.consolidated.git :as c-git]
            [hive-mcp.tools.consolidated.emacs :as c-emacs]
            [hive-mcp.tools.consolidated.preset :as c-preset]
            [hive-mcp.tools.consolidated.web :as c-web]
            [hive-mcp.tools.consolidated.multi :as c-multi]
   ;; Keep old modules loaded for backward compat (multi routing)
            [hive-mcp.tools.consolidated.agent :as c-agent]
            [hive-mcp.tools.consolidated.wave :as c-wave]
            [hive-mcp.tools.consolidated.hivemind :as c-hivemind]
            [hive-mcp.tools.consolidated.agora :as c-agora]
            [hive-mcp.tools.consolidated.olympus :as c-olympus]
            [hive-mcp.tools.consolidated.kanban :as c-kanban]
            [hive-mcp.tools.consolidated.config :as c-config]
            [hive-mcp.tools.consolidated.session :as c-session]
            [hive-mcp.tools.consolidated.workflow :as c-workflow]
            [hive-mcp.tools.consolidated.kg :as c-kg]
            [hive-mcp.tools.consolidated.migration :as c-migration]
            [hive-mcp.tools.consolidated.magit :as c-magit]
            [hive-mcp.tools.events.core :as c-events]
            [hive-mcp.tools.composite :as composite]
            [hive-mcp.extensions.registry :as ext]
            [taoensso.timbre :as log]
            [hive-mcp.tools.consolidated.migrate-kanban :as c-migrate-kanban]
            [hive-mcp.tools.consolidated.hot :as c-hot]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; 9 Domain-Grouped Tool Roots
;; =============================================================================

(defn- config-absorbed-names
  "Read absorbed tool names from config.edn :tool-roots :absorbed.
   These are addon tool names that should not appear as standalone roots
   because they are contributed as subcommands to domain roots instead."
  []
  (try
    (let [cfg ((requiring-resolve 'hive-mcp.config.core/get-global-config))
          names (get-in cfg [:tool-roots :absorbed])]
      (cond
        (set? names) names
        (sequential? names) (set names)
        :else #{}))
    (catch Exception _ #{})))

;; =============================================================================
;; Visibility gate (surface-shrink without breaking back-compat)
;;
;; tools/list (server.registration) hides :deprecated tools, while
;; tools/call still dispatches them. So marking a tool :deprecated removes
;; it from the discovered surface yet keeps it callable by its old name.
;; We use an allowlist (config.edn [:tool-roots :visible]) of root tool
;; names that stay visible; everything else is gated to :deprecated.
;; =============================================================================

(defn visible-root-names
  "Allowlist of tool names that remain visible in tools/list.
   Read from config.edn [:tool-roots :visible]. Returns a set, or nil
   when unconfigured — nil means NO gating (current/legacy behavior)."
  []
  (try
    (let [cfg   ((requiring-resolve 'hive-mcp.config.core/get-global-config))
          names (get-in cfg [:tool-roots :visible])]
      (cond
        (set? names)        names
        (sequential? names) (set names)
        :else               nil))
    (catch Exception _ nil)))

(defn apply-visibility-gate
  "Mark every tool whose :name is NOT in the visible allowlist as
   `:deprecated true`. Deprecated tools stay callable (tools/call) but are
   hidden from tools/list. Pure over the tool-def seq: preserves order,
   handlers, and any pre-existing :deprecated flag; NEVER drops a tool.

   When `visible` is nil/empty, returns the tools unchanged (no gating)."
  ([tools] (apply-visibility-gate tools (visible-root-names)))
  ([tools visible]
   (if (seq visible)
     (mapv (fn [t]
             (if (contains? visible (:name t))
               t
               (assoc t :deprecated true)))
           tools)
     (vec tools))))

(defn ^:private get-base-tools
  "Get domain-grouped tool roots + channel tools + addon-registered tools.

   Filters addon-registered tools via merged exclusion:
   1. Dynamic: addon tool name collides with a domain root name
   2. Dynamic: addon tool marked :consolidated (legacy standalone)
   3. Config:  tool name listed in :tool-roots :absorbed in config.edn

   Novel addon tools that pass all three filters appear as additional roots."
  []
  (let [domain-roots (vec (concat c-code/tools
                                  c-swarm/tools
                                  ;; fn, not a static vec: the memory tool's `relation` enum is
                                  ;; registry-backed and must resolve at advertisement time.
                                  (c-memory/tool-defs)
                                  c-project/tools
                                  c-fs/tools
                                  c-git/tools
                                  c-emacs/tools
                                  c-preset/tools
                                  c-web/tools
                                  c-events/tools
                                  c-multi/tools
                                  c-hot/tools
                                  c-migrate-kanban/tools))
        domain-names   (into #{} (map :name) domain-roots)
        cfg-absorbed   (config-absorbed-names)
        addon-tools    (->> (ext/get-registered-tools)
                            (remove #(or (domain-names (:name %))
                                         (:consolidated %)
                                         (cfg-absorbed (:name %)))))]
    (vec (concat channel/channel-tools
                 domain-roots
                 addon-tools))))

(declare get-all-tools)

(def child-excluded-tool-names
  "Tool names excluded from child ling MCP servers.
   Prevents recursive spawning and coordinator-only operations."
  #{"swarm"     ;; contains agent spawn/kill, wave dispatch, olympus
    "multi"     ;; meta-facade routes to excluded tools
    "emacs"})   ;; Emacs grid control — coordinator-only

(defn child-ling-excluded?
  "True when TOOL's :name is a member of `child-excluded-tool-names`.

   Exact-name membership — a prefix does not match and :deprecated is not
   consulted."
  [{:keys [name]}]
  (contains? child-excluded-tool-names name))

(defn get-child-ling-tools
  "Get restricted tools for child ling MCP servers."
  []
  (let [all (get-all-tools :include-deprecated? true)]
    (filterv (complement child-ling-excluded?) all)))

(defn get-all-tools
  "Get ALL tools including deprecated shims (for dispatch/calling)."
  [& {:keys [include-deprecated?] :or {include-deprecated? true}}]
  (let [all-tools (get-base-tools)]
    (if include-deprecated?
      all-tools
      (filterv #(not (:deprecated %)) all-tools))))

(defn get-consolidated-tools
  "Get only the 9 domain-grouped root tools for minimal tool listing."
  []
  (filterv :consolidated (get-all-tools :include-deprecated? false)))

(defn get-filtered-tools
  "Get tools for MCP tools/list response."
  []
  (let [visible-tools (get-all-tools :include-deprecated? false)]
    (log/info "Filtered tools for listing:" (count visible-tools))
    visible-tools))

(defn- distinct-by-name
  "Dedupe a tool seq by :name, keeping the FIRST occurrence and preserving
   order. Consolidated native roots are listed first, so a native supertool
   wins over a same-named addon tool (no false-discoverability duplicate)."
  [tools]
  (-> (reduce (fn [[seen acc] t]
                (if (contains? seen (:name t))
                  [seen acc]
                  [(conj seen (:name t)) (conj acc t)]))
              [#{} []]
              tools)
      second))

(defn- merge-schema-ext
  "Fold addon-contributed schema extensions into a tool's :inputSchema, mirroring
   server.routes/make-tool. External loaders (bb-mcp) read :inputSchema straight
   from this surface and never run make-tool, so without this they advertise the
   bare core schema and addon params (e.g. carto's new-body/scope/array params)
   are invisible to the client. Kanban 588762d0."
  [{:keys [name inputSchema] :as tool}]
  (if-let [schema-ext (and name (ext/get-schema-extensions name))]
    (assoc tool :inputSchema (update inputSchema :properties merge schema-ext))
    tool))

(defn get-advertised-tools
  "Canonical MCP surface for external loaders (e.g. the bb-mcp dynamic loader).

   = consolidated native roots ++ addon/extension tools, deduped by name
   (consolidated wins), with the visibility gate applied and addon schema
   extensions merged into each :inputSchema. Non-allowlisted tools are KEPT in
   the list but marked :deprecated, so a consumer can hide them from tools/list
   while still dispatching them via tools/call (back-compat).

   Single-sources the gate: build-server-spec, refresh-tools! and any external
   loader all derive their surface from `apply-visibility-gate`, so the
   advertised set can no longer drift from the gate config. Schema-ext merge
   keeps this surface in sync with the stdio/server-context path (make-tool)."
  []
  (mapv merge-schema-ext
        (apply-visibility-gate
         (distinct-by-name
          (concat (get-consolidated-tools)
                  (ext/get-registered-tools))))))

(def tools
  "Static aggregation (deprecated — use get-filtered-tools)."
  (get-base-tools))

(defn get-tool-by-name
  "Find a tool definition by name."
  [name]
  (first (filter #(= (:name %) name) (get-all-tools))))

;; =============================================================================
;; Sub-command introspection
;; =============================================================================

