(ns hive-mcp.tools.composite
  "Build consolidated MCP tools dynamically from addon command contributions.

   Supports two modes:
   1. Pure composite (addon-only): tool has no core handlers, all commands from addons.
   2. Merged composite (core + addon): tool has canonical core handlers that addons
      can extend or override.

   Addon handlers override core handlers with the same name (addon wins).
   Re-resolves contributions on each call for hot-reload support."
  (:require [hive-mcp.extensions.registry :as ext]
            [hive-mcp.tools.cli :as cli]
            [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Addon Contribution → Handler Map
;; =============================================================================

(defn- addon-commands->handlers
  "Convert addon command contributions to keyword->fn handler map.
   Supports both flat handlers and nested handler trees."
  [tool-name]
  (when-let [commands (ext/get-contributed-commands tool-name)]
    (into {} (map (fn [[cmd {:keys [handler]}]]
                    [(keyword cmd) handler])
                  commands))))

(defn lazy-resolve-handlers
  "Lazily resolve a consolidated tool's `handlers` map by fully-qualified
   symbol, triggering ns load on first access (DIP).

   Replaces the static `c-X/handlers` reference pattern in domain-root
   consolidators (project, memory, code, swarm). Drops the compile-time
   coupling between a domain root and every subdomain ns it merges in —
   moving / renaming a subdomain consolidator no longer breaks compile,
   it just yields an empty handler tree at runtime.

   Returns the handlers tree on success, `{}` on miss. Caller merges
   into its canonical-handlers tree the same way the literal reference
   would have."
  [sym]
  (or (try (some-> (requiring-resolve sym) deref)
           (catch Throwable _ nil))
      {}))

(defn lazy-resolve-schema-props
  "Lazily resolve a consolidated tool's advertised inputSchema :properties by
   fully-qualified symbol of its `tool-def` (a map) or `tool-defs` (a 0-arity
   fn returning a vector of them), triggering ns load on first access (DIP).

   Returns the properties map on success, `{}` on miss.

   Sibling of `lazy-resolve-handlers`: a domain root that folds a subdomain's
   HANDLERS must fold that subdomain's PARAMS too. The MCP layer forwards only
   params the called tool declares, so an undeclared one is dropped and the
   subdomain handler runs on its default instead."
  [sym]
  (or (try (let [v  (some-> (requiring-resolve sym) deref)
                 td (if (fn? v) (first (v)) v)]
             (get-in td [:inputSchema :properties]))
           (catch Throwable _ nil))
      {}))

;; =============================================================================
;; Composite Handler Builder
;; =============================================================================

(defn build-composite-handler
  "Build a handler fn that dispatches to contributed addon handlers only.
   Re-resolves contributions on each call so hot-reload picks up changes.
   Use build-merged-handler when core handlers exist.

   Every root is contributed, so all of them are recorded under
   ::cli/opaque-roots in the tree's metadata."
  [tool-name]
  (fn [params]
    (let [addon-cmds (or (addon-commands->handlers tool-name) {})
          handlers   (vary-meta addon-cmds
                                update ::cli/opaque-roots (fnil into #{})
                                (keys addon-cmds))
          cli-fn     (cli/make-cli-handler handlers)]
      (cli-fn params))))

(defn subdomain-handler
  "Wrap INNER as the handler for SUBDOMAIN-NAME: strips the \"<subdomain> \"
   prefix off :command before calling INNER.

   A subdomain dispatches on the whole command string — `code carto search`
   reaches the subdomain owner as \"carto search\" — so the owner uses this to
   hand its inner router just \"search\". A command without the prefix is
   passed through unchanged."
  [subdomain-name inner]
  (fn [params]
    (let [full   (str (:command params))
          prefix (str subdomain-name " ")]
      (inner (assoc params :command (if (str/starts-with? full prefix)
                                      (subs full (count prefix))
                                      full))))))

(defn effective-handlers
  "The handler tree TOOL-NAME dispatches on right now: CANONICAL-HANDLERS merged
   with the commands addons have contributed under TOOL-NAME (addon wins).
   Re-resolved on every call, so a contribution registered later is visible.

   Contributed root keys are recorded under ::cli/opaque-roots in the returned
   map's METADATA: a contributed handler receives the whole :command and routes
   the remainder itself, so this tree cannot enumerate what lives beneath it.
   The map value itself is identical to the plain merge."
  [tool-name canonical-handlers]
  (if-let [addon-cmds (addon-commands->handlers tool-name)]
    (vary-meta (merge canonical-handlers addon-cmds)
               update ::cli/opaque-roots (fnil into #{}) (keys addon-cmds))
    canonical-handlers))

(defn build-merged-handler
  "Build a handler fn that merges core handlers with addon contributions.
   Addon handlers override core handlers with the same name (addon wins).
   Re-resolves addon contributions on each call for hot-reload.

   canonical-handlers: keyword->fn map (or nested tree) from consolidated tool.
   tool-name: string name used for addon contribution lookup.

   Optional coerce-schema: passed through to cli/make-cli-handler."
  ([tool-name canonical-handlers]
   (build-merged-handler tool-name canonical-handlers nil))
  ([tool-name canonical-handlers coerce-schema]
   (fn [params]
     (let [cli-fn (cli/make-cli-handler
                   (effective-handlers tool-name canonical-handlers)
                   coerce-schema)]
       (cli-fn params)))))

;; =============================================================================
;; Composite Tool Definition Builder
;; =============================================================================

(defn build-composite-tool
  "Build a consolidated tool definition from addon contributions only.
   description-prefix: e.g. \"Code analysis\"
   Returns tool-def map identical in shape to other consolidated tools."
  [tool-name description-prefix]
  (let [commands (ext/get-contributed-commands tool-name)
        cmd-names (vec (sort (keys commands)))
        all-params (apply merge-with merge (map :params (vals commands)))
        handler (build-composite-handler tool-name)]
    {:name tool-name
     :consolidated true
     :composite true
     :description (str description-prefix ": "
                       (str/join ", " cmd-names)
                       ". Use command='help' to list all.")
     :inputSchema {:type "object"
                   :properties (merge
                                {"command" {:type "string"
                                            :enum (conj cmd-names "help")
                                            :description (str tool-name " operation to perform")}}
                                all-params)
                   :required ["command"]}
     :handler handler}))

(defn build-merged-tool
  "Build a consolidated tool definition from core handlers + addon contributions.
   core-tool-def: existing tool definition map with :handler, :inputSchema, etc.
   Returns updated tool-def with addon commands merged into command enum and handler.

   The tool-def's :handler is replaced with a merged handler that dispatches to
   both core and addon commands. The :inputSchema command enum is extended with
   addon command names."
  [core-tool-def]
  (let [tool-name (:name core-tool-def)
        addon-cmds (ext/get-contributed-commands tool-name)
        addon-cmd-names (vec (sort (keys (or addon-cmds {}))))
        addon-params (apply merge-with merge (map :params (vals (or addon-cmds {}))))
        ;; Extract core handlers from the tool's canonical-handlers if available
        ;; Otherwise the core handler is already embedded in the tool-def
        core-enum (get-in core-tool-def [:inputSchema :properties "command" :enum] [])]
    (if (empty? addon-cmds)
      core-tool-def
      (-> core-tool-def
          ;; Extend command enum with addon commands
          (assoc-in [:inputSchema :properties "command" :enum]
                    (vec (sort (distinct (concat core-enum addon-cmd-names)))))
          ;; Merge addon params into schema
          (update-in [:inputSchema :properties] merge addon-params)
          ;; Mark as composite
          (assoc :composite true)))))

;; =============================================================================
;; Handler Map for Registry Introspection
;; =============================================================================

(defn build-composite-handlers
  "Build handler map for registry introspection (consolidated-handler-maps).
   Returns keyword->fn map compatible with cli/extract-commands."
  [tool-name]
  (let [commands (ext/get-contributed-commands tool-name)]
    (into {:help (fn [_] {:type "text" :text "help"})}
          (map (fn [[cmd {:keys [handler]}]]
                 [(keyword cmd) handler])
               commands))))

(defn build-merged-handlers
  "Build handler map merging core + addon for registry introspection.
   canonical-handlers: keyword->fn map from consolidated tool."
  [tool-name canonical-handlers]
  (let [addon-cmds (addon-commands->handlers tool-name)]
    (if addon-cmds
      (merge canonical-handlers addon-cmds)
      canonical-handlers)))

;; =============================================================================
;; Batch Builder
;; =============================================================================

(defn build-all-composite-tools
  "Build tool definitions for tool names that (a) have addon contributions and
   (b) are explicitly listed in `descriptions`. The descriptions map acts as a
   whitelist — tools not listed keep their core consolidated tool-def (and
   pick up addon commands via merge semantics in `build-merged-handler`).

   Without this filter, addon-only composites silently overwrite tools like
   `memory` whose 41 canonical verbs would disappear from dispatch — leaving
   only addon-contributed verbs callable."
  [descriptions]
  (vec (for [tool-name (ext/contributed-tool-names)
             :when (contains? descriptions tool-name)
             :let [desc (get descriptions tool-name)]]
         (build-composite-tool tool-name desc))))