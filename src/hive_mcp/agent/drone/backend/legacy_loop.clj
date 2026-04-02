(ns hive-mcp.agent.drone.backend.legacy-loop
  "LegacyLoopBackend -- IDroneExecutionBackend wrapping the existing OpenRouter drone loop."
  (:require [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.agent.drone.tool-allowlist :as allowlist]
            [hive-mcp.agent.registry :as registry]
            [hive-mcp.config.core :as config]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- create-llm-backend
  "Create an LLM backend using the best available OpenAI-compatible provider."
  [model _preset]
  (let [factory-fn (requiring-resolve 'hive-mcp.agent.openrouter/auto-backend)]
    (factory-fn {:model model})))

(defn- resolve-effective-tools
  "Resolve the tool set for drone execution."
  [tools]
  (or (when (seq tools) (vec tools))
      (vec (allowlist/resolve-allowlist {:task-type :general}))))

(defn- normalize-status
  "Normalize agentic loop status to IDroneExecutionBackend contract."
  [status]
  (case status
    :completed :completed
    :max_steps :completed
    (:noop :error) :failed
    :failed))

(defn- normalize-tokens
  "Extract token counts from loop result into standardized format."
  [tokens]
  {:input-tokens  (or (:input tokens) 0)
   :output-tokens (or (:output tokens) 0)})

(defn- build-result
  "Transform agentic loop result to IDroneExecutionBackend result contract."
  [loop-result effective-model]
  {:status     (normalize-status (:status loop-result))
   :result     (or (:result loop-result) "No result")
   :tokens     (normalize-tokens (:tokens loop-result))
   :model      (or (:model loop-result) effective-model)
   :steps      (or (:turns loop-result) 0)
   :tool-calls (or (:tool_calls_made loop-result) 0)})

(defn- error-result
  "Build a failed result map from an exception."
  [e model]
  {:status     :failed
   :result     (or (ex-message e) "Unknown error")
   :tokens     {:input-tokens 0 :output-tokens 0}
   :model      (or model "unknown")
   :steps      0
   :tool-calls 0})

(defrecord LegacyLoopBackend [model-override]
  backend/IDroneExecutionBackend

  (execute-drone [_this task-context]
    (let [{:keys [task model preset tools max-steps trace drone-id cwd]} task-context
          effective-model (or model-override model (config/default-drone-model))
          effective-id    (or drone-id "legacy-drone")]
      (try
        (log/info "LegacyLoopBackend executing"
                  {:drone-id effective-id :model effective-model :max-steps max-steps})

        (let [llm-backend     (create-llm-backend effective-model preset)
              _               (registry/ensure-registered!)
              effective-tools (resolve-effective-tools tools)
              run-loop!       @(requiring-resolve 'hive-mcp.agent.drone.loop/run-agentic-loop)
              loop-result     (run-loop!
                               {:task task :files [] :cwd cwd}
                               {:drone-id effective-id :kg-store nil}
                               {:max-turns    (or max-steps 10)
                                :backend      llm-backend
                                :tools        effective-tools
                                :permissions  #{}
                                :trace?       (boolean trace)
                                :agent-id     effective-id})]

          (log/info "LegacyLoopBackend completed"
                    {:drone-id effective-id
                     :status   (:status loop-result)
                     :turns    (:turns loop-result)})

          (build-result loop-result effective-model))

        (catch Exception e
          (log/error {:event      :legacy-loop/execution-failed
                      :drone-id   effective-id
                      :model      effective-model
                      :error      (ex-message e)})
          (error-result e effective-model)))))

  (supports-validation? [_this]
    true)

  (backend-type [_this]
    :legacy-loop))

(defn ->legacy-loop-backend
  "Create a LegacyLoopBackend instance."
  [& [{:keys [model]}]]
  (->LegacyLoopBackend model))

(defmethod backend/resolve-backend :legacy-loop [context]
  (->legacy-loop-backend {:model (:model context)}))
