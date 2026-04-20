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

;; NOTE: ICoordinatorAware protocol removed in 0.16.0 (ISP fix).
;; Hivemind role is now modeled as data via hive-mcp.agent.hivemind-role/HivemindRole ADT.
;; See: (require '[hive-mcp.agent.hivemind-role :as hr])
;;   (hr/hivemind-mode? role)        — replaces (coordinator-mode? agent)
;;   (hr/hivemind-tools role def)    — replaces (allowed-tools agent)
;;   (hr/worker-tool-pool role tools) — replaces (worker-tool-pool agent)
