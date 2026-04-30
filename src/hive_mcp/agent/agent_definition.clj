(ns hive-mcp.agent.agent-definition
  "Declarative agent definitions — malli schema + markdown loader (façade).

   Ports Claude Code's AgentDefinition pattern to hive's Clojure ecosystem.
   Agents are declared via:
   1. Built-in definitions (code, :source :built-in)
   2. Project definitions (.claude/agents/*.md in project root, :source :project)
   3. User definitions   (~/.claude/agents/*.md, :source :user)

   Later sources override earlier ones (user > project > built-in).

   Markdown files use YAML frontmatter for metadata, body becomes :system-prompt.

   Example .claude/agents/reviewer.md:
   ```
   ---
   name: reviewer
   description: Code review specialist that checks for bugs and style issues.
   tools:
     - Read
     - Grep
     - Glob
   model: inherit
   max-turns: 15
   skills:
     - simplify
   ---
   You are a code reviewer. Focus on correctness, clarity, and idiomatic style.
   ```

   Schema follows CC's AgentDefinition type with Clojure adaptations:
   - agentType     → :agent-type
   - whenToUse     → :description (CC's 'description' frontmatter field)
   - tools         → :tools (vec of strings; [\"*\"] = all tools)
   - disallowedTools → :disallowed-tools
   - model         → :model (string or :inherit)
   - maxTurns      → :max-turns
   - prompt/body   → :system-prompt (string or 0-arity fn)
   - source        → :source (:built-in, :project, :user, :plugin)
   - hooks         → :hooks (optional map)
   - mcpServers    → :mcp-servers (optional vec)
   - skills        → :skills (optional vec of strings)

   Leaf namespace for schema definitions — no heavy hive-mcp deps.
   Uses clj-yaml (already in deps.edn) for frontmatter parsing.

   ---
   Façade (convention 20260423151955-4faf4ffe). Implementation is split
   (SLAP) across three sibling namespaces:
   - `hive-mcp.agent.agent-definition.spec`     — schemas, enums
   - `hive-mcp.agent.agent-definition.validate` — validate/explain/valid?/throw!
   - `hive-mcp.agent.agent-definition.compose`  — frontmatter, loaders, merge, lookup

   The AgentDef defrecord is declared here so its generated Java class name
   (`hive_mcp.agent.agent_definition.AgentDef`) stays stable for callers that
   rely on `(instance? hive_mcp.agent.agent_definition.AgentDef x)` checks.

   All prior public vars are re-exported here so downstream callers
   (aliased as `ad/`) keep working unchanged."

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

  (:require [hive-mcp.agent.agent-definition.spec :as spec]
            [hive-mcp.agent.agent-definition.validate :as validate]
            [hive-mcp.agent.agent-definition.compose :as compose]))

;; =============================================================================
;; Re-exports: spec (schemas + enums)
;; =============================================================================

(def Source          spec/Source)
(def ToolSpec        spec/ToolSpec)
(def HooksSpec       spec/HooksSpec)
(def McpServerSpec   spec/McpServerSpec)
(def AgentDefinition spec/AgentDefinition)

;; =============================================================================
;; Record: AgentDef (internal domain representation)
;; =============================================================================
;;
;; Declared here (not in spec.clj) so the generated class keeps its legacy
;; name `hive_mcp.agent.agent_definition.AgentDef` — downstream callers rely
;; on this for `instance?` checks and pretty-printing.

(defrecord AgentDef
  [^String agent-type
   ^String description
   system-prompt   ;; String or 0-arity fn
   source          ;; :built-in | :project | :user | :plugin
   tools           ;; vec of strings, or nil (= all tools)
   disallowed-tools ;; vec of strings, or nil
   model           ;; string, :inherit, or nil
   max-turns       ;; pos-int or nil
   hooks           ;; map or nil
   mcp-servers     ;; vec or nil
   skills          ;; vec of strings or nil
   filename        ;; string or nil
   base-dir        ;; string or nil
   spawn-mode      ;; keyword or nil
   hivemind-role]) ;; :role/hivemind | :role/worker | :role/standalone | nil

(defn agent-def?
  "Is x an AgentDef record?"
  [x]
  (instance? AgentDef x))

(defn ->map
  "Convert an AgentDef record to a plain map (for boundary output).
   Strips nil-valued optional fields for clean serialization.
   No-op if already a plain map."
  [agent-def]
  (if (agent-def? agent-def)
    (into {} (remove (comp nil? val)) agent-def)
    agent-def))

(defmethod print-method AgentDef
  [^AgentDef agent-def ^java.io.Writer w]
  (.write w (str "#AgentDef{:agent-type "
                 (pr-str (:agent-type agent-def))
                 " :source "
                 (pr-str (:source agent-def))
                 "}")))

;; =============================================================================
;; Re-exports: validate
;; =============================================================================

(def validate           validate/validate)
(def valid?             validate/valid?)
(def explain            validate/explain)
(def validate-or-throw! validate/validate-or-throw!)

(defn make-agent-def
  "Validate a plain map and convert to an AgentDef record.
   This is the boundary crossing point: maps external → record internal.

   - Validates against the AgentDefinition malli schema
   - Applies defaults (:source → :built-in, :hivemind-role → :role/standalone)
   - Returns an AgentDef record for internal domain use
   - Throws ex-info on validation failure

   Use at registration boundaries where external data enters the domain.
   Keyword access on the returned record works identically to maps:
     (:agent-type (make-agent-def {...})) => \"explore\"

   Example:
   (make-agent-def {:agent-type \"explore\" :description \"Fast\" :system-prompt \"You search.\"})
   ;=> #hive_mcp.agent.agent_definition.AgentDef{:agent-type \"explore\" ...}"
  [m]
  {:pre [(map? m)]}
  (let [validated     (validate/validate-or-throw! m)
        with-defaults (merge {:source        :built-in
                              :hivemind-role :role/standalone}
                             validated)]
    (map->AgentDef with-defaults)))

;; =============================================================================
;; Re-exports: compose (frontmatter, loaders, merge, lookup, summary)
;; =============================================================================

(def parse-frontmatter      compose/parse-frontmatter)
(def frontmatter->agent-def compose/frontmatter->agent-def)
(def load-agent-file        compose/load-agent-file)
(def load-agents-dir        compose/load-agents-dir)
(def find-agent-dirs        compose/find-agent-dirs)
(def load-all-custom-agents compose/load-all-custom-agents)
(def merge-definitions      compose/merge-definitions)
(def active-agents          compose/active-agents)
(def find-by-type           compose/find-by-type)
(def built-in?              compose/built-in?)
(def custom?                compose/custom?)
(def plugin?                compose/plugin?)
(def has-all-tools?         compose/has-all-tools?)
(def get-system-prompt      compose/get-system-prompt)
(def summarize              compose/summarize)
(def list-summary           compose/list-summary)
