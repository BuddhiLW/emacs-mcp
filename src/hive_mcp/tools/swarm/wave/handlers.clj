(ns hive-mcp.tools.swarm.wave.handlers
  "MCP handlers for wave operations, delegating to domain functions."
  (:require [hive-mcp.tools.swarm.wave.execution :as execution]
            [hive-mcp.tools.swarm.wave.validation :as validation]
            [hive-mcp.tools.swarm.wave.status :as status]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.agent.openrouter :as llm-registry]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [hive-mcp.dns.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn create-plan!
  "Create a change plan with multiple tasks."
  [tasks & [preset]]
  (ds/create-plan! tasks (or preset "drone-worker")))

(defn- try-validated-wave-status
  "Fallback lookup for validated wave sessions (vw-* / rw-* prefixes)."
  [wave-id]
  (rescue nil
          (when-let [get-session (requiring-resolve
                                  'hive-mcp.tools.swarm.validated-wave/get-validated-wave-session)]
            (when-let [session (get-session wave-id)]
              (let [running? (= :running (:status session))]
                {:type "text"
                 :text (json/write-str
                        (cond-> {:wave_id wave-id
                                 :type "validated-wave"
                                 :status (if running? "running" (name (:status session)))
                                 :task_count (:task-count session)
                                 :started_at (:started-at session)}
                          (:completed-at session) (assoc :completed_at (:completed-at session))
                          (:iterations session)  (assoc :iterations (:iterations session))
                          (:final-wave-id session) (assoc :final_wave_id (:final-wave-id session))
                          (:final-plan-id session) (assoc :final_plan_id (:final-plan-id session))
                          (seq (:modified-files session)) (assoc :modified_files (:modified-files session))
                          (:execution-failures session) (assoc :execution_failures (:execution-failures session))
                          (:error session) (assoc :error (:error session))
                          (:message session) (assoc :message (:message session))
                          (:wave-id session) (assoc :inner_wave_id (:wave-id session))
                          (:plan-id session) (assoc :plan_id (:plan-id session))
                          (:completed-tasks session) (assoc :completed_tasks (:completed-tasks session))
                          (:failed-tasks session) (assoc :failed_tasks (:failed-tasks session))
                          (seq (:next-steps session)) (assoc :next_steps (:next-steps session))
                          (seq (:history session))
                          (assoc :iteration_history
                                 (mapv #(select-keys % [:iteration :wave-id :finding-count :execution-failures])
                                       (:history session)))))})))))

(defn handle-get-wave-status
  "Handle get_wave_status MCP tool call, checking both DataScript and validated wave sessions."
  [{:keys [wave_id]}]
  (try
    (when-not wave_id
      (throw (ex-info "wave_id is required" {})))

    (if-let [wave-status (status/get-wave-status wave_id)]
      (let [failed-items (status/get-failed-items wave_id)]
        {:type "text"
         :text (json/write-str (merge wave-status
                                      {:failed_items failed-items}))})
      (or (try-validated-wave-status wave_id)
          {:type "text"
           :text (json/write-str {:error "Wave not found"
                                  :wave_id wave_id})}))
    (catch Exception e
      (log/error e "get_wave_status failed")
      {:type "text"
       :text (json/write-str {:error (.getMessage e)})})))

(defn- normalize-backend
  "Normalize backend string/keyword from MCP into a keyword."
  [backend]
  (when backend
    (keyword (some-> backend name))))

(defn handle-dispatch-drone-wave
  "Handle dispatch_drone_wave MCP tool call."
  [{:keys [tasks preset trace cwd ensure_dirs validate_paths mode backend model seeds
           ctx_refs kg_node_ids]}]
  (log/warn {:event :deprecation-warning
             :tool "dispatch_drone_wave"
             :message "DEPRECATED: Use 'wave' consolidated tool instead."})

  (try
    (when (empty? tasks)
      (throw (ex-info "tasks array is required and must not be empty" {})))

    (let [effective-cwd (or cwd (ctx/current-directory))
          effective-mode (if (= "agentic" (some-> mode name))
                           :agentic
                           :delegate)
          effective-backend (normalize-backend backend)
          ;; Validate provider+model via registry
          _ (when effective-backend
              (when-let [err (llm-registry/validate-provider effective-backend)]
                (throw (ex-info (str "Unknown backend: " (name effective-backend)) err))))
          _ (when (and effective-backend model)
              (when-let [err (llm-registry/validate-model effective-backend model)]
                (log/warn "Model not in available-models for backend" err)))
          normalized-tasks (mapv (fn [t]
                                   {:file (or (get t "file") (:file t))
                                    :task (or (get t "task") (:task t))})
                                 tasks)
          do-validate (if (false? validate_paths) false true)
          do-ensure (if (false? ensure_dirs) false true)]

      (when do-ensure
        (validation/ensure-parent-dirs! normalized-tasks))
      (when do-validate
        (validation/validate-task-paths normalized-tasks))

      (let [plan-id (create-plan! normalized-tasks preset)
            {:keys [wave-id item-count]} (execution/execute-wave-async!
                                          plan-id
                                          (cond-> {:trace (if (nil? trace) true trace)
                                                   :cwd effective-cwd
                                                   :mode effective-mode}
                                            effective-backend  (assoc :backend effective-backend)
                                            model              (assoc :model model)
                                            (seq seeds)        (assoc :seeds (vec seeds))
                                            (seq ctx_refs)     (assoc :ctx-refs ctx_refs)
                                            (seq kg_node_ids)  (assoc :kg-node-ids (vec kg_node_ids))))]
        {:type "text"
         :text (json/write-str (cond-> {:status "dispatched"
                                        :plan_id plan-id
                                        :wave_id wave-id
                                        :item_count item-count
                                        :mode (name effective-mode)
                                        :message (str "Wave dispatched to background"
                                                      (when (= :agentic effective-mode)
                                                        " (agentic mode with session store)")
                                                      (when effective-backend
                                                        (str " (backend: " (name effective-backend) ")"))
                                                      ". Poll get_wave_status(wave_id) for progress.")}
                                 effective-backend (assoc :backend (name effective-backend))))}))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (log/error e "dispatch_drone_wave failed" {:error-type (:type data)})
        {:type "text"
         :text (json/write-str (merge {:error (.getMessage e)}
                                      (when (:hint data) {:hint (:hint data)})
                                      (when (:type data) {:error_type (name (:type data))})))}))
    (catch Exception e
      (log/error e "dispatch_drone_wave failed")
      {:type "text"
       :text (json/write-str {:error (.getMessage e)})})))

(defn handle-dispatch-validated-wave
  "Handle dispatch_drone_wave with post-execution validation."
  [{:keys [lint_level] :as params}]
  (try
    (let [dispatch-result (handle-dispatch-drone-wave params)
          dispatch-data (json/read-str (:text dispatch-result) :key-fn keyword)]

      (if (:error dispatch-data)
        dispatch-result
        {:type "text"
         :text (json/write-str (assoc dispatch-data
                                      :validation_mode true
                                      :lint_level (or lint_level :error)
                                      :message (str (:message dispatch-data)
                                                    " Post-apply validation enabled.")))}))

    (catch Exception e
      (log/error e "dispatch_validated_wave failed")
      {:type "text"
       :text (json/write-str {:error (.getMessage e)})})))

(defn handle-cancel-wave
  "Handle wave cancellation request."
  [{:keys [wave_id reason]}]
  (try
    (when-not wave_id
      (throw (ex-info "wave_id is required" {})))

    (when-let [wave (ds/get-wave wave_id)]
      (let [plan-id (:wave/plan wave)]
        (ds/complete-wave! wave_id :cancelled)
        (ds/update-plan-status! plan-id :cancelled)
        {:type "text"
         :text (json/write-str {:cancelled true
                                :wave_id wave_id
                                :reason (or reason "User requested")})}))

    (catch Exception e
      (log/error e "cancel_wave failed")
      {:type "text"
       :text (json/write-str {:error (.getMessage e)})})))
