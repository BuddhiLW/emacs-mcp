(ns hive-mcp.tools.consolidated.roster
  "Pure-data roster of consolidated tool name → fully-qualified handler symbol.

   Single source of truth for the 20 'leaf' consolidated tools. NOT loaded
   eagerly: callers (multi.core-seed, consolidated.multi) `requiring-resolve`
   each symbol at boot time, lazily triggering ns load on demand. This breaks
   the static-require chain that previously coupled consolidated.multi to
   every consolidated.X namespace (DIP).

   Adding a new consolidated tool: add one row here. No edits to
   consolidated.multi or multi.core-seed required.

   Composite tools (analysis) live in `composite-tools` — they have no
   per-tool ns; their handler is built by
   `hive-mcp.tools.composite/build-composite-handler` keyed on tool-name.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def consolidated-tools
  "Vector of [tool-name fully-qualified-handler-symbol] tuples. Resolved
   lazily — this ns has no :require of consolidated.X namespaces."
  '[["agent"          hive-mcp.tools.consolidated.agent/handle-agent]
    ["memory"         hive-mcp.tools.consolidated.memory/handle-memory]
    ["kg"             hive-mcp.tools.consolidated.kg/handle-kg]
    ["hivemind"       hive-mcp.tools.consolidated.hivemind/handle-hivemind]
    ["magit"          hive-mcp.tools.consolidated.magit/handle-magit]
    ["cider"          hive-mcp.tools.consolidated.cider/handle-cider]
    ["kanban"         hive-mcp.tools.consolidated.kanban/handle-kanban]
    ["preset"         hive-mcp.tools.consolidated.preset/handle-preset]
    ["olympus"        hive-mcp.tools.consolidated.olympus/handle-olympus]
    ["agora"          hive-mcp.tools.consolidated.agora/handle-agora]
    ["project"        hive-mcp.tools.consolidated.project/handle-project]
    ["session"        hive-mcp.tools.consolidated.session/handle-session]
    ["emacs"          hive-mcp.tools.consolidated.emacs/handle-emacs]
    ["wave"           hive-mcp.tools.consolidated.wave/handle-wave]
    ["migration"      hive-mcp.tools.consolidated.migration/handle-migration]
    ["migrate-kanban" hive-mcp.tools.consolidated.migrate-kanban/handle-migrate-kanban]
    ["config"         hive-mcp.tools.consolidated.config/handle-config]
    ["workflow"       hive-mcp.tools.consolidated.workflow/handle-workflow]
    ["transcript"     hive-mcp.tools.consolidated.transcript/handle-transcript]
    ["events"         hive-mcp.tools.events.core/handle]])

(def composite-tools
  "Tool-names whose handler is built via
   `hive-mcp.tools.composite/build-composite-handler` rather than resolved
   from a consolidated.X namespace."
  ["analysis"])

(defn tool-names
  "Sorted vec of every tool-name in the roster (consolidated + composite).
   Pure — no ns load triggered."
  []
  (vec (sort (concat (map first consolidated-tools)
                     composite-tools))))
