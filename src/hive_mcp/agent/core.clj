(ns hive-mcp.agent.core
  "Agent delegation facade. Routes drone tasks through the agentic execution path."
  (:require [hive-mcp.agent.mcp :as mcp]
            [hive-mcp.agent.drone :as drone]
            [hive-mcp.agent.registry :as registry]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn delegate-drone!
  "Delegate a task to a drone. Backend resolved via best-backend() priority
   (fsm-agentic → sdk-drone → agentic-loop) when no explicit backend specified."
  [opts]
  (drone/delegate-agentic! opts))

(defn delegate-agentic-drone!
  "Delegate a task to an in-process agentic drone with session store."
  [opts]
  (drone/delegate-agentic! opts))

(def tools
  "MCP tools for agent delegation."
  (mcp/make-tools delegate-drone!))

(def register-tools!
  "Register tools for agent delegation."
  registry/register!)
