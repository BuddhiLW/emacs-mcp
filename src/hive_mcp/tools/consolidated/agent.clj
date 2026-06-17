(ns hive-mcp.tools.consolidated.agent
  "Consolidated Agent CLI tool — thin facade over agent.* modules."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler make-batch-handler]]
            [hive-mcp.tools.core :refer [mcp-error]]
            [hive-mcp.tools.agent.spawn :as spawn]
            [hive-mcp.tools.agent.status :as status]
            [hive-mcp.tools.agent.kill :as kill]
            [hive-mcp.tools.agent.dispatch :as dispatch]
            [hive-mcp.tools.agent.dag :as dag]
            [hive-mcp.tools.agent.lifecycle :as lifecycle]
            [hive-mcp.agent.type-registry :as agent-type-registry]
            [hive-mcp.agent.spawn-mode-registry :as spawn-registry]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private deprecated-aliases
  {:list :status})

(defn- wrap-deprecated
  [alias-kw canonical-kw handler-fn]
  (fn [params]
    (log/warn (str "DEPRECATED: command '" (name alias-kw)
                   "' is deprecated, use '" (name canonical-kw) "' instead."))
    (handler-fn params)))

(def ^:private batch-spawn-handler
  "Batch spawn multiple agents in one call via make-batch-handler."
  (let [spawn-handlers {:spawn spawn/handle-spawn}
        batch-fn (make-batch-handler spawn-handlers)]
    (fn [{:keys [operations] :as params}]
      (if (or (nil? operations) (empty? operations))
        (mcp-error "operations is required (array of spawn parameter objects)")
        (let [;; Auto-inject :command "spawn" into each operation
              ops-with-command (mapv #(assoc % :command "spawn") operations)]
          (batch-fn (assoc params :operations ops-with-command)))))))

(def canonical-handlers
  {:spawn       spawn/handle-spawn
   :status      status/handle-status
   :kill        kill/handle-kill
   :kill-batch  kill/handle-kill-batch
   :interrupt   lifecycle/handle-interrupt
   :batch-spawn batch-spawn-handler
   :dispatch    dispatch/handle-dispatch
   :claims      lifecycle/handle-claims
   :collect     lifecycle/handle-collect
   :broadcast   lifecycle/handle-broadcast
   :cleanup     lifecycle/handle-cleanup
   :dag         {:start    dag/handle-dag-start
                 :stop     dag/handle-dag-stop
                 :status   dag/handle-dag-status
                 :_handler dag/handle-dag-status}})

(def handlers
  (merge canonical-handlers
         (reduce-kv (fn [m alias-kw canonical-kw]
                      (assoc m alias-kw
                             (wrap-deprecated alias-kw canonical-kw
                                              (get canonical-handlers canonical-kw))))
                    {} deprecated-aliases)))

(def handle-agent
  (make-cli-handler handlers))

;; Re-exports for direct handler access (used by tests and internal callers)
(def handle-spawn spawn/handle-spawn)
(def handle-status status/handle-status)
(def handle-kill kill/handle-kill)
(def handle-kill-batch kill/handle-kill-batch)
(def handle-dispatch dispatch/handle-dispatch)
(def handle-claims lifecycle/handle-claims)
(def handle-collect lifecycle/handle-collect)
(def handle-broadcast lifecycle/handle-broadcast)
(def handle-cleanup lifecycle/handle-cleanup)
(def handle-list (get handlers :list))

(def tool-def
  {:name "agent"
   :consolidated true
   :description "Unified agent operations: spawn (create ling/drone), status (query agents), kill (terminate), kill-batch (terminate multiple agents in one call), batch-spawn (spawn multiple agents at once via operations array), dispatch (send task), interrupt (interrupt current query of agent-sdk ling), claims (file ownership), list (deprecated alias for status), collect (get task result), broadcast (prompt all), cleanup (remove orphan agents after Emacs restart). Type: 'ling' (Claude Code instance) or 'drone' (OpenRouter leaf worker). Nested: dag (start/stop/status DAGWave scheduler). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["spawn" "status" "kill" "kill-batch" "batch-spawn" "dispatch" "interrupt" "claims" "list" "collect" "broadcast" "cleanup" "dag start" "dag stop" "dag status" "help"]
                                         :description "Agent operation to perform"}
                              ;; spawn params
                              "type" {:type "string"
                                      :enum (agent-type-registry/mcp-enum)
                                      :description "Agent type to spawn (required for spawn)"}
                              "name" {:type "string"
                                      :description "Agent name/ID (auto-generated if not provided)"}
                              "cwd" {:type "string"
                                     :description "Working directory (required for spawn)"}
                              "presets" {:type "array"
                                         :items {:type "string"}
                                         :description "Preset names for ling (ling only)"}
                              "model" {:type "string"
                                       :description "Model override for drone or ling. For drones: OpenAI-compat model ID. For lings: 'claude' (default, Claude Code CLI) or OpenAI-compat model ID (auto-forces headless spawn mode). Supports 'provider:model' prefix, e.g. 'venice:qwen3-coder-480b-a35b-instruct', 'groq:llama-3.3-70b-versatile', 'moonshotai/kimi-k2.5' (bare → openrouter)."}
                              "provider" {:type "string"
                                          :enum ["openrouter" "venice" "groq" "together" "fireworks" "openai" "ollama-compat"]
                                          :description "Explicit OpenAI-compat LLM provider (overrides any 'provider:' prefix in model). Anthropic routing is automatic from model names (claude-*, anthropic/*)."}
                              "task" {:type "string"
                                      :description "Initial task to dispatch on spawn"}
                              "spawn_mode" {:type "string"
                                            :enum (spawn-registry/mcp-enum)
                                            :description "Spawn mode for lings: 'vterm' (default, Emacs buffer) or 'headless' (OS subprocess, no Emacs required). Headless mode captures stdout to ring buffer and supports stdin dispatch. NOTE: 'headless' is ABSTRACT — the concrete backend (:agent-sdk, :hive-agent, …) is resolved at spawn time via the headless-registry: operator override in ~/.config/hive-mcp/config.edn [:headless :default-backend] wins, otherwise priority-ranked addon-contributed backends are tried in registration order. The response's :spawn-mode reflects the resolved backend, not the requested 'headless' alias. To force a specific backend, pass spawn_mode directly (e.g. 'agent-sdk' or 'hive-agent') instead of 'headless'."}
                              "agents" {:type "object"
                                        :description "[spawn] Subagent definitions for Claude Agent SDK sessions. Map of agent-name to agent definition object. Each definition: {description: string, prompt: string, tools?: string[], model?: 'sonnet'|'opus'|'haiku'|'inherit'}. Only effective when the resolved spawn mode is :agent-sdk (either spawn_mode='agent-sdk' directly, or spawn_mode='headless' when agent-sdk is the registry-resolved default backend). Ignored by other headless backends (e.g. :hive-agent)."}
                              "max_budget_usd" {:type "number"
                                                :description "[spawn] Maximum USD spend for this agent. When set, registers a budget guardrail hook that denies+interrupts tool calls when cumulative cost exceeds the limit. E.g. 2.0 = $2 max."}
                              "kg_compress" {:type "boolean"
                                             :description "[spawn] Enable KG-compression between turns (default: true). Rewrites message history into compressed KG-derived context to keep context constant-size for long-running lings. SET TO FALSE for weaker models (qwen/qwen3.6-plus, z-ai/glm-5.1) that plan-paralyze on compressed context — they loop on identical tool calls because they can't infer 'already did X' from KG summaries. Strong models (claude, deepseek, kimi, gpt-5) handle kg_compress=true fine."}
                              "sliding_window_size" {:type "integer"
                                                     :description "[spawn] Number of recent raw conversation turns to keep in context alongside KG-compressed summaries. Default 5. Only effective when kg_compress=true. Larger = more coherent short-term recall, lower compression ratio. Planned: not yet implemented — reconstructor extension pending (kanban 20260415160150-4fd21a2a)."}
                              "verbose" {:type "boolean"
                                         :description "[spawn] When true, bb-ling lings shout the full per-turn LLM exchange (last request message + response, truncated) to hivemind piggyback. Default false — exchange is always persisted to the agent's transcript store (Datalevin) and queryable post-hoc via the `transcript` MCP tool (list/search)."}
                              "llm_retries" {:type "integer"
                                             :description "[spawn] Max bb-ling loop-level retries on transient LLM failures (rate-limit/overload/timeout). Default 3, exponential backoff."}
                              ;; common params
                              "agent_id" {:type "string"
                                          :description "Agent ID for status/kill/dispatch/claims"}
                              "agent_ids" {:type "array"
                                           :items {:type "string"}
                                           :description "Array of agent IDs for kill-batch"}
                              ;; batch-spawn params
                              "operations" {:type "array"
                                            :items {:type "object"}
                                            :description "Array of spawn parameter objects for batch-spawn. Each object: {type, name, cwd, presets, model, task, spawn_mode, parent, kanban_task_id}"}
                              "parallel" {:type "boolean"
                                          :description "Run batch operations in parallel (default: false)"}
                              "project_id" {:type "string"
                                            :description "Project ID filter for status"}
                              ;; dispatch params
                              "prompt" {:type "string"
                                        :description "Task prompt for dispatch or broadcast"}
                              "files" {:type "array"
                                       :items {:type "string"}
                                       :description "Files for drone or dispatch"}
                              "priority" {:type "string"
                                          :enum ["low" "normal" "high"]
                                          :description "Task priority for dispatch"}
                              ;; compressed context params (dispatch)
                              "ctx_refs" {:type "object"
                                          :description "[dispatch] Map of category->ctx-id for compressed context. When provided, creates RefContext (token-efficient vs text). E.g. {\"axioms\": \"ctx-123\", \"decisions\": \"ctx-456\"}"}
                              "kg_node_ids" {:type "array"
                                             :items {:type "string"}
                                             :description "[dispatch] Node IDs for context resolution seeds. Combined with ctx_refs for structural context reconstruction."}
                              "scope" {:type "string"
                                       :description "[dispatch] Project scope for context resolution (auto-derived from agent if omitted)"}
                              "parent" {:type "string"
                                        :description "Parent agent ID for spawn"}
                              "kanban_task_id" {:type "string"
                                                :description "Kanban task ID to link to ling. On session_complete, linked task auto-moves to done."}
                              ;; kill params
                              "force" {:type "boolean"
                                       :description "Force kill even if critical ops in progress"}
                              "directory" {:type "string"
                                           :description "Caller's working directory (for cross-project ownership check)"}
                              "force_cross_project" {:type "boolean"
                                                     :description "HIL override: Allow killing agents from different projects (default: false). Required when target agent belongs to different project than caller."}
                              ;; collect params
                              "task_id" {:type "string"
                                         :description "Task ID for collect operation"}
                              "timeout_ms" {:type "integer"
                                            :description "Timeout in ms for collect (default: 300000)"}
                              ;; dag params
                              "plan_id" {:type "string"
                                         :description "Plan memory entry ID for dag start (required for 'dag start')"}
                              "max_slots" {:type "integer"
                                           :description "Max concurrent lings for dag scheduler (default: 5)"}}
                 :required ["command"]}
   :handler handle-agent})

(def tools [tool-def])
