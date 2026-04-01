(ns hive-mcp.agent.protocol
  "Protocols for agent lifecycle, registry, and LLM backends.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol IAgent
  "Unified agent lifecycle protocol for lings and drones"
  (spawn! [this opts]
    "Spawn the agent. Returns agent-id.")
  (dispatch! [this task-opts]
    "Send a task to the agent. Returns task-id.")
  (kill! [this]
    "Terminate the agent and release resources.")
  (status [this]
    "Get current agent status map.")
  (agent-type [this]
    "Returns :ling or :drone")
  (can-chain-tools? [this]
    "Returns true if agent can chain multiple tool calls (lings only)")
  (claims [this]
    "Get list of files currently claimed by this agent.")
  (claim-files! [this files task-id]
    "Claim files for exclusive access during task.")
  (release-claims! [this]
    "Release all file claims held by this agent.")
  (upgrade! [this]
    "Upgrade drone to ling when task requires tool chaining. No-op for lings."))

(defprotocol IAgentRegistry
  "Registry for tracking all active agents."
  (register! [this agent]
    "Add agent to registry")
  (unregister! [this agent-id]
    "Remove agent from registry")
  (get-agent [this agent-id]
    "Get agent by ID")
  (list-agents [this]
    "List all agents")
  (list-agents-by-type [this agent-type]
    "List agents filtered by :ling or :drone"))

(defprotocol LLMBackend
  "Protocol for LLM backends that support tool calling."
  (chat [this messages tools]
    "Send messages to the model with available tools.
     Returns {:type :text :content \"...\"} or {:type :tool_calls :calls [...]}
     where each call is {:id \"...\" :name \"tool_name\" :arguments {...}}")
  (model-name [this] "Return the model identifier string."))

(defprotocol ICoordinatorAware
  "Protocol for agents that can operate in coordinator mode.

   Coordinator mode restricts available tools to delegation primitives
   (agent spawn, send-message, task management) while workers get the
   full tool pool. This enables the CC-parity coordinator→worker pattern
   where coordinators design and delegate, workers implement.

   Implementors:
   - hive-agent coordinator loops (restricted tool set)
   - hive-agent worker loops (full tool pool)

   See also:
   - hive-mcp.agent.agentic-loop/IAgenticLoop — loop lifecycle
   - hive-mcp.agent.agent-definition — agent definition schema"

  (coordinator-mode? [this]
    "Returns true if this agent is currently operating as a coordinator.
     Coordinators have a restricted tool set focused on delegation:
     agent spawn, send-message, task-stop, and read-only tools.")

  (allowed-tools [this]
    "Return the set of tool name strings available to this agent in its
     current mode. Coordinators get delegation primitives only; workers
     get the full pool minus any disallowed-tools from their definition.")

  (worker-tool-pool [this]
    "Return the full tool pool that would be delegated to spawned workers.
     Only meaningful when coordinator-mode? is true. Returns nil for workers.
     Used by the coordinator to configure worker agent definitions."))
