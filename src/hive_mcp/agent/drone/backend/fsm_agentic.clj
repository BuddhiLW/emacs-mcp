(ns hive-mcp.agent.drone.backend.fsm-agentic
  "FSMAgenticBackend -- IDroneExecutionBackend via composable FSM drone-loop."
  (:require [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- resolve-drone-loop-fn
  "Lazily resolve a function from the drone-loop FSM namespace."
  [sym]
  (rescue nil (requiring-resolve sym)))

(defn- get-run-drone-loop
  "Get the run-drone-loop convenience function, or nil if not available."
  []
  (resolve-drone-loop-fn 'hive-mcp.workflows.drone-loop/run-drone-loop))

(defn- default-llm-backend-factory
  "Create an LLMBackend via auto-backend (best available OpenAI-compat provider)."
  [model]
  (when-let [factory-fn (rescue nil (requiring-resolve 'hive-mcp.agent.openrouter/auto-backend))]
    (factory-fn {:model model})))

(defn- build-fsm-resources
  "Build the FSM resources map from task-context."
  [task-context opts]
  (let [{:keys [model tools preset sandbox cwd drone-id]} task-context
        {:keys [tool-executor-fn]} opts
        llm-factory (or (:llm-backend-factory opts) default-llm-backend-factory)]
    (cond-> {:model       model
             :tools       (or tools [])
             :preset      preset
             :sandbox     sandbox
             :cwd         (or cwd ".")
             :drone-id    drone-id
             :llm-backend (llm-factory model)}
      tool-executor-fn
      (assoc :tool-executor tool-executor-fn))))

(defn- build-fsm-initial-data
  "Build the FSM initial data map from task-context."
  [task-context]
  (let [{:keys [task max-steps drone-id]} task-context
        files (get-in task-context [:sandbox :allowed-files])]
    {:task       task
     :files      (if (set? files) (vec files) (or files []))
     :max-turns  (or max-steps 20)
     :turn       0
     :drone-id   drone-id
     :messages   []
     :kg-observations []
     :tool-results    []
     :status     :running}))

(defn- adapt-fsm-result
  "Adapt the drone-loop FSM result to IDroneExecutionBackend contract."
  [fsm-result model]
  (let [status (case (:status fsm-result)
                 :completed          :completed
                 :max-turns-exceeded :timeout
                 :failed)
        usage (or (:usage fsm-result) {})]
    {:status     status
     :result     (or (:result fsm-result) "")
     :tokens     {:input-tokens  (or (:input-tokens usage) 0)
                  :output-tokens (or (:output-tokens usage) 0)}
     :model      (or model "unknown")
     :steps      (or (:turns fsm-result) (:turn fsm-result) 0)
     :tool-calls (or (:tool-calls fsm-result) 0)
     :metadata   {:backend        :fsm-agentic
                  :kg-observations (count (or (:kg-observations fsm-result) []))}}))

(defrecord FSMAgenticBackend [opts]
  backend/IDroneExecutionBackend

  (execute-drone [_this task-context]
    (let [{:keys [task model drone-id]} task-context]
      (log/info {:event    :fsm-agentic-backend/executing
                 :drone-id drone-id
                 :model    model
                 :task-preview (subs (or task "") 0 (min 80 (count (or task ""))))})

      (if-let [run-fn (get-run-drone-loop)]
        (let [resources (build-fsm-resources task-context opts)
              init-data (build-fsm-initial-data task-context)]
          (log/debug {:event :fsm-agentic-backend/using-run-fn
                      :drone-id drone-id})
          (try
            (let [result (run-fn init-data resources)]
              (log/info {:event    :fsm-agentic-backend/completed
                         :drone-id drone-id
                         :status   (:status result)
                         :turns    (:turns result)})
              (adapt-fsm-result result model))
            (catch Exception e
              (log/error e "FSM agentic execution failed"
                         {:drone-id drone-id})
              {:status   :failed
               :result   (str "FSM drone-loop execution failed: " (ex-message e))
               :tokens   {:input-tokens 0 :output-tokens 0}
               :model    (or model "unknown")
               :steps    0
               :metadata {:backend :fsm-agentic
                          :error   (ex-message e)}})))

        (do
          (log/warn {:event    :fsm-agentic-backend/unavailable
                     :drone-id drone-id
                     :message  "drone-loop FSM namespace not on classpath (step-8 pending)"})
          {:status   :failed
           :result   "FSM drone-loop is not yet available. The drone-loop FSM (step-8) must be implemented first."
           :tokens   {:input-tokens 0 :output-tokens 0}
           :model    (or model "unknown")
           :steps    0
           :metadata {:backend :fsm-agentic
                      :reason  :unavailable}}))))

  (supports-validation? [_this]
    true)

  (backend-type [_this]
    :fsm-agentic))

(defn make-fsm-agentic-backend
  "Create an FSMAgenticBackend instance."
  ([] (make-fsm-agentic-backend {}))
  ([opts]
   (->FSMAgenticBackend opts)))

(defmethod backend/resolve-backend :fsm-agentic [context]
  (when-not (get-run-drone-loop)
    (throw (ex-info "FSM drone-loop not on classpath (step-8 pending)"
                    {:backend :fsm-agentic :reason :unavailable})))
  (make-fsm-agentic-backend
   (select-keys context [:llm-backend-factory :tool-executor-fn])))
