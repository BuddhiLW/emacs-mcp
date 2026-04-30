(ns hive-mcp.tools.registry
  "MCP tool definitions registry — aggregates consolidated tool definitions.

   9 domain-grouped tool roots: code, swarm, memory, project, fs, git, emacs, preset, multi.
   Core subdomains are statically defined. Addon subdomains injected at runtime (OCP)."
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
            [hive-mcp.tools.consolidated.cider :as c-cider]
            [hive-mcp.tools.consolidated.magit :as c-magit]
            [hive-mcp.tools.events.core :as c-events]
            [hive-mcp.tools.composite :as composite]
            [hive-mcp.extensions.registry :as ext]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

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
                                  c-memory/tools
                                  c-project/tools
                                  c-fs/tools
                                  c-git/tools
                                  c-emacs/tools
                                  c-preset/tools
                                  c-events/tools
                                  c-multi/tools))
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

(defn- child-ling-excluded?
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

(defn- extract-commands
  "Extract command paths from a handler tree."
  ([handlers] (extract-commands handlers []))
  ([handlers prefix]
   (reduce-kv
    (fn [acc k v]
      (if (= k :_handler)
        acc
        (cond
          (fn? v)
          (conj acc (str/join " " (map name (conj prefix k))))

          (map? v)
          (let [nested (extract-commands v (conj prefix k))
                with-default (if (contains? v :_handler)
                               (into [(str/join " " (map name (conj prefix k)))] nested)
                               nested)]
            (into acc with-default))

          :else acc)))
    [] handlers)))

(def ^:private consolidated-handler-maps
  "Map of consolidated tool name → their handler dispatch maps.
   Includes both new domain roots and old tool names for backward compat."
  {;; New 9 domain roots
   :code      c-code/handlers
   :swarm     c-swarm/handlers
   :memory    c-memory/canonical-handlers
   :project   c-project/handlers
   :fs        c-fs/handlers
   :git       c-git/handlers
   :emacs     c-emacs/handlers
   :preset    c-preset/handlers
   ;; Old tool names (backward compat via multi routing)
   :agent     c-agent/handlers
   :wave      c-wave/handlers
   :hivemind  c-hivemind/handlers
   :agora     c-agora/handlers
   :olympus   c-olympus/handlers
   :kanban    c-kanban/handlers
   :config    c-config/handlers
   :session   c-session/handlers
   :workflow  c-workflow/handlers
   :kg        c-kg/handlers
   :migration c-migration/handlers
   :cider     c-cider/handlers
   :magit     c-magit/handlers
   ;; events tool exposed as a flat dispatcher; expose only the tool def for help
   })

(defn get-child-tools
  "Get available sub-commands for a consolidated tool by name."
  [tool-name]
  (when-let [handlers (or (get consolidated-handler-maps (keyword tool-name))
                          (when (seq (ext/get-contributed-commands tool-name))
                            (composite/build-composite-handlers tool-name)))]
    (vec (sort (extract-commands handlers)))))
