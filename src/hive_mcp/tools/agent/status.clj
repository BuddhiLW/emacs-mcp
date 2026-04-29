(ns hive-mcp.tools.agent.status
  "Agent status query handler with DataScript and elisp fallback."
  (:require [hive-mcp.tools.core :refer [mcp-error mcp-json]]
            [hive-mcp.tools.agent.helpers :as helpers]
            [hive-mcp.agent.type-registry :as agent-type-registry]
            [hive-mcp.swarm.datascript.queries :as queries]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn handle-status
  "Get agent status, optionally filtered by agent_id, type, or project_id.
   Stale/zombie rows hidden by default; pass include_stale=true for diagnostics."
  [{:keys [agent_id type project_id include_stale]}]
  (let [eid    (when (and agent_id (not= agent_id "coordinator")) agent_id)
        stale? (boolean include_stale)]
    (try
      (cond
        eid
        (if-let [agent-data (queries/get-slave eid)]
          (mcp-json {:agent (helpers/format-agent agent-data)})
          (mcp-error (str "Agent not found: " eid)))
        type
        (let [agent-type (keyword type)
              depth (when (agent-type-registry/valid-type? agent-type)
                      (agent-type-registry/type-depth agent-type))
              all-agents (if project_id
                           (queries/get-slaves-by-project project_id :include-stale? stale?)
                           (if (= agent-type :ling)
                             (helpers/merge-with-elisp-lings (queries/get-all-slaves :include-stale? stale?))
                             (queries/get-all-slaves :include-stale? stale?)))
              filtered (if depth
                         (filter #(= depth (:slave/depth %)) all-agents)
                         all-agents)]
          (mcp-json (helpers/format-agents filtered)))
        project_id
        (mcp-json (helpers/format-agents (queries/get-slaves-by-project project_id :include-stale? stale?)))
        :else
        (mcp-json (helpers/format-agents (helpers/merge-with-elisp-lings (queries/get-all-slaves :include-stale? stale?)))))
      (catch Exception e
        (log/error "Failed to get agent status" {:error (ex-message e)})
        (mcp-error (str "Failed to get status: " (ex-message e)))))))
