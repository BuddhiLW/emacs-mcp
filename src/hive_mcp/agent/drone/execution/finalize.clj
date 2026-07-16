(ns hive-mcp.agent.drone.execution.finalize
  "Finalization phase for drone execution: diff handling, validation,
   parent notification, event emission, metrics recording, and KG merge.

   Extracted from execution.clj to reduce per-file cyclomatic complexity."
  (:require [hive-mcp.agent.drone.domain :as domain]
            [hive-mcp.agent.drone.diff-mgmt :as diff-mgmt]
            [hive-mcp.agent.drone.validation :as validation]
            [hive-mcp.agent.drone.kg-factory :as kg-factory]
            [hive-mcp.agent.drone.errors :as errors]
            [hive-mcp.agent.routing :as routing]
            [hive-mcp.agent.cost :as cost]
            [hive-mcp.dns.result :as result]
            [hive-mcp.protocols.kg :as kg]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.events.core :as ev]
            [hive-mcp.telemetry.prometheus :as prom]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- truncate-str
  "Truncate string to max-len characters."
  [s max-len]
  (let [s (str s)]
    (subs s 0 (min max-len (count s)))))

(defn- task-preview
  "First 80 chars of task string for log messages."
  [task]
  (truncate-str task 80))

(defn- completed?
  "Check if raw-result indicates completion."
  [raw-result]
  (= :completed (:status raw-result)))

;;; ---------------------------------------------------------------------------
;;; Finalize sub-functions
;;; ---------------------------------------------------------------------------

(defn- warn-zero-diffs!
  "Log warning when drone completed but produced no diffs for expected files."
  [drone-id raw-result files new-diff-ids]
  (when (and (completed? raw-result)
             (empty? new-diff-ids)
             (seq files))
    (let [result-text (str (:result raw-result))
          has-code-blocks? (boolean (re-find #"```" result-text))]
      (log/warn {:event :drone/zero-diff-completion
                 :drone-id drone-id
                 :files-expected (count files)
                 :has-code-blocks? has-code-blocks?
                 :result-preview (truncate-str result-text 200)
                 :message (str "Drone completed with 0 diffs but " (count files)
                               " files expected. Model likely returned code as text "
                               "instead of calling propose_diff."
                               (when has-code-blocks?
                                 " CODE BLOCKS DETECTED in text response."))}))))

(defn- run-post-validation
  "Run post-validation on modified files. Returns {:post-validation ... :validation-summary ...}."
  [drone-id raw-result files pre-validation file-contents-before skip-auto-apply]
  (let [should-validate? (and (seq files)
                              (completed? raw-result)
                              (not skip-auto-apply))
        post-validation (when should-validate?
                          (validation/validate-files-post
                           file-contents-before
                           (or pre-validation {})
                           {:lint-level :error
                            :require-modification false}))
        validation-summary (validation/summarize-validation
                            (merge (or pre-validation {}) (or post-validation {})))]

    (when (and post-validation (not (validation/all-valid? post-validation :post)))
      (log/warn {:event :drone/post-validation-warnings
                 :drone-id drone-id
                 :summary validation-summary}))

    {:post-validation post-validation
     :validation-summary validation-summary}))

(defn- notify-parent-completion!
  "Shout completion or error to parent ling."
  [parent-id drone-id task raw-result diff-results]
  (when parent-id
    (let [task-label (str "Drone: " (task-preview task))
          ok? (completed? raw-result)]
      (hivemind/shout! parent-id
                       (if ok? :completed :error)
                       {:task task-label
                        :message (if ok?
                                   (format "Drone %s completed. Files: %s"
                                           drone-id
                                           (diff-mgmt/summarize-diff-results diff-results))
                                   (format "Drone %s failed: %s"
                                           drone-id (:result raw-result)))}))))

(defn- emit-completion-event!
  "Dispatch :drone/completed or :drone/failed event.
   run-id (the wave run id, may be nil) is threaded into the event data so the
   downstream :nats-publish effect can emit a wave-scoped completion."
  [ctx raw-result diff-results duration-ms validation-summary files run-id]
  (let [{:keys [drone-id task-id parent-id]} ctx]
    (if (completed? raw-result)
      (ev/dispatch [:drone/completed {:drone-id drone-id
                                      :task-id task-id
                                      :parent-id parent-id
                                      :run-id run-id
                                      :files-modified (:applied diff-results)
                                      :files-failed (:failed diff-results)
                                      :proposed-diff-ids (:proposed diff-results)
                                      :duration-ms duration-ms
                                      :validation validation-summary}])
      (ev/dispatch [:drone/failed {:drone-id drone-id
                                   :task-id task-id
                                   :parent-id parent-id
                                   :run-id run-id
                                   :error (str (:result raw-result))
                                   :error-type :execution
                                   :files files}]))))

(defn- record-execution-metrics!
  "Record prometheus metrics, cost tracking, and routing feedback."
  [ctx task-spec config raw-result duration-ms]
  (let [{:keys [drone-id]} ctx
        {:keys [task options]} task-spec
        {:keys [task-type model]} config
        {:keys [wave-id]} options
        model-name (or (:model raw-result) model)
        tokens (:tokens raw-result)
        input-tokens (or (:input-tokens tokens)
                         (cost/count-tokens (:task task-spec)))
        output-tokens (or (:output-tokens tokens)
                          (cost/count-tokens (str (:result raw-result))))]

    (prom/record-drone-result! {:model model-name
                                :task-type (name task-type)
                                :success? (completed? raw-result)
                                :duration-ms duration-ms
                                :tokens tokens})

    (cost/track-drone-usage! drone-id
                             {:input-tokens input-tokens
                              :output-tokens output-tokens
                              :task-preview task
                              :wave-id wave-id})

    (routing/report-execution! task-type model-name raw-result
                               {:duration-ms duration-ms
                                :directory (:cwd task-spec)
                                :agent-id drone-id})))

(defn- merge-kg-results!
  "Merge drone KG store results into global KG store."
  [ctx]
  (when-let [drone-store (:kg-store ctx)]
    (let [merge-result (result/rescue nil
                                      (when (kg/store-set?)
                                        (kg-factory/merge-drone-results! drone-store (kg/get-store))))]
      (when merge-result
        (log/info {:event :drone/kg-merge
                   :drone-id (:drone-id ctx)
                   :edges-found (:edges-found merge-result)
                   :edges-merged (:edges-merged merge-result)})))))

;;; ---------------------------------------------------------------------------
;;; Phase: Finalize (orchestrator)
;;; ---------------------------------------------------------------------------

(defn phase:finalize!
  "Handle diffs, validate, record metrics, and build execution result."
  [ctx task-spec config raw-result diffs-before]
  (let [{:keys [drone-id pre-validation file-contents-before]} ctx
        {:keys [task files options]} task-spec
        {:keys [wave-id skip-auto-apply]} options
        ;; run-id = wave run id, carried at TaskSpec top level (->task-spec puts
        ;; :wave-id there, not under :options). Threaded to the completion event
        ;; so :nats-publish can emit a wave-scoped completion for wave-watch.sh.
        run-id (:wave-id task-spec)

        diffs-after (diff-mgmt/capture-diffs-before)
        new-diff-ids (diff-mgmt/get-new-diff-ids diffs-before diffs-after)
        _ (warn-zero-diffs! drone-id raw-result files new-diff-ids)

        diff-results (diff-mgmt/handle-diff-results!
                      drone-id new-diff-ids
                      {:wave-id wave-id :skip-auto-apply skip-auto-apply})

        duration-ms (domain/elapsed-ms ctx)

        {:keys [validation-summary]}
        (run-post-validation drone-id raw-result files
                             pre-validation file-contents-before skip-auto-apply)]

    (notify-parent-completion! (:parent-id ctx) drone-id task raw-result diff-results)
    (emit-completion-event! ctx raw-result diff-results duration-ms validation-summary files run-id)
    (record-execution-metrics! ctx task-spec config raw-result duration-ms)

    (hivemind/record-ling-result! drone-id
                                  {:task task
                                   :files files
                                   :result raw-result
                                   :diff-results diff-results
                                   :validation validation-summary
                                   :parent-id (:parent-id ctx)
                                   :timestamp (System/currentTimeMillis)})

    (merge-kg-results! ctx)

    (domain/success-result ctx {:result raw-result
                                :diff-results diff-results
                                :validation validation-summary})))

;;; ---------------------------------------------------------------------------
;;; Phase: Error Handling
;;; ---------------------------------------------------------------------------

(defn- record-failure-metrics!
  "Record prometheus and routing metrics for a failed execution."
  [drone-id task-spec config structured duration-ms]
  (let [{:keys [task-type model]} config]
    (prom/record-drone-result! {:model model
                                :task-type (name task-type)
                                :success? false
                                :duration-ms duration-ms
                                :retry? (= :timeout (:error-type structured))
                                :retry-reason (:error-type structured)})

    (routing/report-execution! task-type model
                               {:status :failed :error (:message structured)}
                               {:duration-ms duration-ms
                                :directory (:cwd task-spec)
                                :agent-id drone-id})))

(defn phase:handle-error!
  "Handle execution error with proper logging and metrics."
  [ctx task-spec config exception]
  (let [{:keys [drone-id task-id parent-id]} ctx
        {:keys [files]} task-spec
        duration-ms (domain/elapsed-ms ctx)
        structured (errors/structure-error exception)]

    (log/error {:event :drone/error
                :error-type (:error-type structured)
                :drone-id drone-id
                :task-id task-id
                :parent-id parent-id
                :model (:model config)
                :files files
                :duration-ms duration-ms
                :message (:message structured)
                :stacktrace (truncate-str (:stacktrace structured) 500)})

    (ev/dispatch [:drone/failed {:drone-id drone-id
                                 :task-id task-id
                                 :parent-id parent-id
                                 :error (:message structured)
                                 :error-type (:error-type structured)
                                 :stacktrace (:stacktrace structured)
                                 :files files}])

    (record-failure-metrics! drone-id task-spec config structured duration-ms)))
