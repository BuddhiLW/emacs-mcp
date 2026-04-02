(ns hive-mcp.agent.drone.backend.hive-agent
  "DEPRECATED: HiveAgentBackend -- superseded by sdk-drone (hive-claude Agent SDK).
   Removed from ext_router priority in 0.16.0.
   Kept for explicit :hive-agent backend selection only."
  {:deprecated "0.16.0"}
  (:require [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.agent.hive-agent-bridge :as bridge]
            [hive-mcp.config.core :as config]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- adapt-bridge-result
  "Adapt hive-agent-bridge result to IDroneExecutionBackend contract."
  [bridge-result]
  (let [status (case (:status bridge-result)
                 :completed :completed
                 :error     :failed
                 :failed)]
    {:status   status
     :result   (or (:result bridge-result) "")
     :tokens   {:input-tokens  (get-in bridge-result [:tokens :input] 0)
                :output-tokens (get-in bridge-result [:tokens :output] 0)}
     :model    (or (:model bridge-result) "unknown")
     :steps    (or (:tool_calls_made bridge-result) 0)
     :metadata (merge
                (:hive-agent-metadata bridge-result)
                {:backend :hive-agent})}))

(defrecord HiveAgentBackend []
  backend/IDroneExecutionBackend

  (execute-drone [_this task-context]
    (let [{:keys [task model max-steps preset cwd files]} task-context
          bridge-opts {:task           task
                       :model          (or model (config/default-drone-model))
                       :max-turns      (or max-steps 20)
                       :preset-content preset
                       :project-id     nil
                       :files          files
                       :cwd            cwd}]
      (log/info {:event  :hive-agent-backend/executing
                 :model  (:model bridge-opts)
                 :cwd    cwd})
      (if-let [result (bridge/run-agent-via-bridge bridge-opts)]
        (do
          (log/info {:event  :hive-agent-backend/completed
                     :status (:status result)})
          (adapt-bridge-result result))
        (do
          (log/warn {:event :hive-agent-backend/unavailable})
          {:status :failed
           :result "hive-agent is not available on classpath"
           :tokens {:input-tokens 0 :output-tokens 0}
           :model  (or model "unknown")
           :steps  0
           :metadata {:backend :hive-agent
                      :reason  :unavailable}}))))

  (supports-validation? [_this]
    true)

  (backend-type [_this]
    :hive-agent))

(defn make-hive-agent-backend
  "Create a HiveAgentBackend instance."
  []
  (->HiveAgentBackend))

(defmethod backend/resolve-backend :hive-agent [_context]
  (make-hive-agent-backend))
