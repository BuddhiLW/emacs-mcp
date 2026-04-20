(ns hive-mcp.tools.consolidated.swarm
  "Consolidated swarm coordination tool — merges agent, wave, hivemind, agora, olympus.

   Uses nested command namespacing to avoid collisions:
     swarm agent spawn
     swarm wave dispatch
     swarm hivemind shout
     swarm agora dialogue
     swarm olympus focus

   Addons can extend via contribute-commands! \"swarm\"."
  (:require [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.consolidated.agent :as c-agent]
            [hive-mcp.tools.consolidated.wave :as c-wave]
            [hive-mcp.tools.consolidated.hivemind :as c-hivemind]
            [hive-mcp.tools.consolidated.agora :as c-agora]
            [hive-mcp.tools.consolidated.olympus :as c-olympus]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Canonical Handlers — nested by subdomain
;; =============================================================================

(def canonical-handlers
  "Nested handler tree. Dispatch via 'agent spawn', 'wave dispatch', etc."
  {:agent   c-agent/handlers
   :wave    c-wave/handlers
   :hivemind c-hivemind/handlers
   :agora   c-agora/handlers
   :olympus c-olympus/handlers})

(def handlers canonical-handlers)

;; =============================================================================
;; Tool Definition
;; =============================================================================

;; Collect all params from sub-tools for schema union
(defn- merge-schemas [& tool-defs]
  (apply merge-with merge
         (map #(get-in % [:inputSchema :properties]) tool-defs)))

(def tool-def
  (let [all-props (merge-schemas (first c-agent/tools)
                                 (first c-wave/tools)
                                 (first c-hivemind/tools)
                                 (first c-agora/tools)
                                 (first c-olympus/tools))]
    {:name "swarm"
     :consolidated true
     :description "Unified agent operations: spawn (create ling/drone), status (query agents), kill (terminate), kill-batch (terminate multiple agents in one call), batch-spawn (spawn multiple agents at once via operations array), dispatch (send task), interrupt (interrupt current query of agent-sdk ling), claims (file ownership), list (deprecated alias for status), collect (get task result), broadcast (prompt all), cleanup (remove orphan agents after Emacs restart). Type: 'ling' (Claude Code instance) or 'drone' (OpenRouter leaf worker). Nested: dag (start/stop/status DAGWave scheduler). Use command='help' to list all."
     :inputSchema {:type "object"
                   :properties (merge
                                {"command" {:type "string"
                                            :description "Swarm operation. Prefix with subdomain: 'agent spawn', 'wave dispatch', 'hivemind shout', 'agora dialogue', 'olympus focus'. Use command='help' to list all."}}
                                ;; Include all params from sub-tools
                                (dissoc all-props "command"))
                   :required ["command"]}
     :handler (composite/build-merged-handler "swarm" canonical-handlers)}))

(def tools [tool-def])
