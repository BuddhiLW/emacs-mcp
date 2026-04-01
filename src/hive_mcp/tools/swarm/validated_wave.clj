(ns hive-mcp.tools.swarm.validated-wave
  "Validated wave execution with self-healing lint-and-retry loop."
  (:require [hive-mcp.tools.swarm.wave :as wave]
            [hive-mcp.analysis.resolve :as resolve]
            [hive-mcp.tools.core :refer [mcp-error]]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.events.core :as ev]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-dsl.bounded-atom :refer [bounded-atom bput! bget bounded-swap!
                                           bclear! register-sweepable!]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(declare execute-review-wave!)

(def ^:const default-max-retries
  "Default maximum retry iterations for validation loop."
  3)

(def ^:const default-lint-level
  "Default severity threshold for validation."
  "error")

(defn- extract-modified-files
  "Extract file paths of completed items from wave status."
  [plan-status]
  (->> (:items plan-status)
       (filter #(= :completed (:status %)))
       (mapv :file)))

(def ^:const default-lint-timeout-ms
  "Default per-file lint timeout for validated waves (15 seconds)."
  15000)

(defn- lint-files
  "Run clj-kondo lint on files and filter by severity level.
   Each file analysis is bounded by lint-timeout-ms (default 15s)."
  [files lint-level & {:keys [lint-timeout-ms] :or {lint-timeout-ms default-lint-timeout-ms}}]
  (if-let [run-analysis (resolve/resolve-kondo-analysis)]
    (let [level-kw (keyword lint-level)
          all-findings (reduce
                        (fn [acc file]
                          (try
                            (let [{:keys [findings timed-out]} (run-analysis file :timeout-ms lint-timeout-ms)
                                  _ (when timed-out
                                      (log/warn "Lint timed out for file, partial results" {:file file :timeout-ms lint-timeout-ms}))
                                  filtered (->> findings
                                                (filter #(case level-kw
                                                           :error (= (:level %) :error)
                                                           :warning (#{:error :warning} (:level %))
                                                           :info true)))]
                              (concat acc filtered))
                            (catch Exception e
                              (log/warn "Failed to lint file:" file (.getMessage e))
                              acc)))
                        []
                        files)
          files-with-errors (->> all-findings
                                 (group-by :filename)
                                 (keys)
                                 (vec))]
      {:findings (vec all-findings)
       :files-with-errors files-with-errors
       :count (count all-findings)})
    (do
      (log/warn "clj-kondo-mcp not available, skipping lint")
      {:findings [] :files-with-errors [] :count 0})))

(defn- format-finding-for-task
  "Format a lint finding into a human-readable string."
  [{:keys [row col level type message]}]
  (format "- Line %d, Col %d [%s/%s]: %s"
          row col (name level) (name type) message))

(defn- generate-fix-tasks
  "Generate fix tasks from lint findings, grouped by file."
  [findings original-tasks]
  (let [by-file (group-by :filename findings)
        original-by-file (into {} (map (juxt :file :task) original-tasks))]
    (mapv (fn [[file file-findings]]
            {:file file
             :task (str "Fix the following lint errors in this file:\n\n"
                        (str/join "\n" (map format-finding-for-task file-findings))
                        "\n\n"
                        (when-let [original-task (get original-by-file file)]
                          (str "Original task context: " original-task "\n\n"))
                        "IMPORTANT: Fix ALL listed errors. Ensure code compiles after changes.")})
          by-file)))

(defn execute-validated-wave!
  "Execute a wave with validation loop and auto-retry on lint failures."
  [tasks {:keys [validate max-retries lint-level preset trace cwd]
          :or {validate true
               max-retries default-max-retries
               lint-level default-lint-level
               preset "drone-worker"
               trace true}}]
  (let [start-time (System/nanoTime)]
    (log/info "Starting validated wave" {:task-count (count tasks)
                                         :validate validate
                                         :max-retries max-retries
                                         :lint-level lint-level})

    (when trace
      (ev/dispatch [:validated-wave/start {:task-count (count tasks)
                                           :max-retries max-retries
                                           :lint-level lint-level}]))

    (loop [current-tasks tasks
           iteration 1
           history []]
      (log/info "Validated wave iteration" iteration "with" (count current-tasks) "tasks")

      (when trace
        (ev/dispatch [:validated-wave/iteration-start {:iteration iteration
                                                       :task-count (count current-tasks)}]))

      (let [plan-id (wave/create-plan! current-tasks preset)
            wave-id (wave/execute-wave! plan-id {:trace trace :cwd cwd})
            plan-status (wave/get-plan-status plan-id)
            modified-files (extract-modified-files plan-status)
            failed-items (->> (:items plan-status)
                              (filter #(= :failed (:status %))))]

        (when (seq failed-items)
          (log/warn "Some tasks failed in iteration" iteration {:failed-count (count failed-items)}))

        (let [validation-result (when (and validate (seq modified-files))
                                  (lint-files modified-files lint-level))
              findings (:findings validation-result)
              iteration-record {:iteration iteration
                                :wave-id wave-id
                                :plan-id plan-id
                                :modified-files modified-files
                                :finding-count (count findings)
                                :execution-failures (count failed-items)}
              updated-history (conj history iteration-record)
              total-exec-failures (reduce + (map :execution-failures updated-history))]

          (cond
            (or (not validate) (empty? findings))
            (if (pos? total-exec-failures)
              (do
                (log/warn "Validated wave completed with execution failures"
                          {:iterations iteration
                           :execution-failures total-exec-failures})
                (when trace
                  (ev/dispatch [:validated-wave/partial
                                {:iterations iteration
                                 :execution-failures total-exec-failures
                                 :duration-ns (- (System/nanoTime) start-time)}]))
                {:status :partial
                 :iterations iteration
                 :final-wave-id wave-id
                 :final-plan-id plan-id
                 :modified-files modified-files
                 :execution-failures total-exec-failures
                 :history updated-history
                 :message (format "Lint passed but %d task(s) failed to execute." total-exec-failures)})
              (do
                (log/info "Validated wave completed successfully" {:iterations iteration})
                (when trace
                  (ev/dispatch [:validated-wave/success
                                {:iterations iteration
                                 :wave-id wave-id
                                 :duration-ns (- (System/nanoTime) start-time)}]))
                {:status :success
                 :iterations iteration
                 :final-wave-id wave-id
                 :final-plan-id plan-id
                 :modified-files modified-files
                 :history updated-history}))

            (>= iteration max-retries)
            (do
              (log/warn "Validated wave reached max retries" {:iterations iteration
                                                              :remaining-findings (count findings)
                                                              :execution-failures total-exec-failures})
              (when trace
                (ev/dispatch [:validated-wave/partial
                              {:iterations iteration
                               :remaining-findings (count findings)
                               :execution-failures total-exec-failures
                               :duration-ns (- (System/nanoTime) start-time)}]))
              {:status :partial
               :iterations iteration
               :final-wave-id wave-id
               :final-plan-id plan-id
               :modified-files modified-files
               :findings findings
               :files-with-errors (:files-with-errors validation-result)
               :execution-failures total-exec-failures
               :history updated-history
               :message (format "Validation failed after %d iterations. %d lint errors remain. %d task(s) failed to execute."
                                iteration (count findings) total-exec-failures)})

            :else
            (let [fix-tasks (generate-fix-tasks findings current-tasks)]
              (log/info "Generating fix tasks for iteration" (inc iteration)
                        {:fix-task-count (count fix-tasks)
                         :finding-count (count findings)})
              (when trace
                (ev/dispatch [:validated-wave/retry {:iteration iteration
                                                     :finding-count (count findings)
                                                     :fix-task-count (count fix-tasks)}]))
              (recur fix-tasks (inc iteration) updated-history))))))))

;; gc-fix-3: validated-wave-sessions — LRU eviction, 50 entries, 1hr TTL
;; Wave sessions are ephemeral; old completed sessions waste memory.
(defonce ^:private validated-wave-sessions
  (bounded-atom {:max-entries 50
                 :ttl-ms 3600000    ;; 1 hour
                 :eviction-policy :lru}))
(register-sweepable! validated-wave-sessions :validated-wave-sessions)

(defn execute-validated-wave-async!
  "Execute a validated wave asynchronously, returning a session-id for polling."
  [tasks opts]
  (let [session-id (str "vw-" (java.util.UUID/randomUUID))]
    (bput! validated-wave-sessions session-id
           {:session-id session-id
            :status :running
            :task-count (count tasks)
            :started-at (System/currentTimeMillis)})
    (future
      (try
        (let [result (execute-validated-wave! tasks opts)]
          (bput! validated-wave-sessions session-id
                 (merge result
                        {:session-id session-id
                         :completed-at (System/currentTimeMillis)})))
        (catch Exception e
          (log/error e "Async validated wave failed" {:session-id session-id})
          (bput! validated-wave-sessions session-id
                 {:session-id session-id
                  :status :failed
                  :error (.getMessage e)
                  :completed-at (System/currentTimeMillis)}))))
    {:session-id session-id
     :task-count (count tasks)}))

(defn execute-review-wave-async!
  "Execute a review wave asynchronously, returning a session-id for polling."
  [tasks opts]
  (let [session-id (str "rw-" (java.util.UUID/randomUUID))]
    (bput! validated-wave-sessions session-id
           {:session-id session-id
            :status :running
            :mode :review
            :task-count (count tasks)
            :started-at (System/currentTimeMillis)})
    (future
      (try
        (let [result (execute-review-wave! tasks opts)]
          (bput! validated-wave-sessions session-id
                 (merge result
                        {:session-id session-id
                         :completed-at (System/currentTimeMillis)})))
        (catch Exception e
          (log/error e "Async review wave failed" {:session-id session-id})
          (bput! validated-wave-sessions session-id
                 {:session-id session-id
                  :status :failed
                  :error (.getMessage e)
                  :completed-at (System/currentTimeMillis)}))))
    {:session-id session-id
     :task-count (count tasks)}))

(defn get-validated-wave-session
  "Get session state for an async validated wave execution."
  [session-id]
  (bget validated-wave-sessions session-id))

(defn list-validated-wave-sessions
  "List all validated wave sessions."
  []
  (->> (vals @(:atom validated-wave-sessions))
       (mapv (fn [entry]
               (select-keys (:data entry) [:session-id :status :task-count :started-at :completed-at])))
       (sort-by :started-at)))

(defn execute-review-wave!
  "Execute a wave with review-before-apply workflow."
  [tasks {:keys [preset trace cwd]
          :or {preset "drone-worker"
               trace true}}]
  (let [start-time (System/nanoTime)]
    (log/info "Starting review wave (skip-auto-apply mode)" {:task-count (count tasks)})

    (when trace
      (ev/dispatch [:review-wave/start {:task-count (count tasks)
                                        :mode :review-before-apply}]))

    (let [plan-id (wave/create-plan! tasks preset)
          wave-id (wave/execute-wave! plan-id {:trace trace
                                               :cwd cwd
                                               :skip-auto-apply true})
          plan-status (wave/get-plan-status plan-id)
          completed-items (->> (:items plan-status)
                               (filter #(= :completed (:status %))))
          failed-items (->> (:items plan-status)
                            (filter #(= :failed (:status %))))]

      (log/info "Review wave execution completed"
                {:wave-id wave-id
                 :completed (count completed-items)
                 :failed (count failed-items)
                 :duration-ms (/ (- (System/nanoTime) start-time) 1e6)})

      (when trace
        (ev/dispatch [:review-wave/awaiting-review {:wave-id wave-id
                                                    :completed (count completed-items)
                                                    :failed (count failed-items)}]))

      {:status :awaiting-review
       :wave-id wave-id
       :plan-id plan-id
       :completed-tasks (count completed-items)
       :failed-tasks (count failed-items)
       :next-steps ["review_wave_diffs" "approve_wave_diffs" "auto_approve_wave_diffs"]})))

(defn handle-dispatch-validated-wave
  "Handle dispatch_validated_wave MCP tool call with lint or review mode."
  [{:keys [tasks review_mode validate max_retries lint_level preset trace cwd]}]
  (log/warn {:event :deprecation-warning
             :tool "dispatch_validated_wave"
             :message "DEPRECATED: Use 'delegate' tool instead. delegate({tasks: [...], validate: true})"})

  (try
    (when (empty? tasks)
      (throw (ex-info "tasks array is required and must not be empty" {})))

    (let [effective-cwd (or cwd (ctx/current-directory))
          normalized-tasks (mapv (fn [t]
                                   {:file (or (get t "file") (:file t))
                                    :task (or (get t "task") (:task t))})
                                 tasks)]

      (if review_mode
        (let [{:keys [session-id task-count]}
              (execute-review-wave-async!
               normalized-tasks
               {:preset (or preset "drone-worker")
                :trace (if (nil? trace) true trace)
                :cwd effective-cwd})]
          {:type "text"
           :text (json/write-str
                  {:status "dispatched"
                   :session_id session-id
                   :task_count task-count
                   :workflow "review-before-apply"
                   :message (str "Review wave dispatched to background. "
                                 "Poll get_validated_wave_status(session_id: \"" session-id "\") for progress. "
                                 "When complete, use review_wave_diffs/approve_wave_diffs.")})})

        (let [{:keys [session-id task-count]}
              (execute-validated-wave-async!
               normalized-tasks
               {:validate (if (false? validate) false true)
                :max-retries (or max_retries default-max-retries)
                :lint-level (or lint_level default-lint-level)
                :preset (or preset "drone-worker")
                :trace (if (nil? trace) true trace)
                :cwd effective-cwd})]
          {:type "text"
           :text (json/write-str
                  {:status "dispatched"
                   :session_id session-id
                   :task_count task-count
                   :max_retries (or max_retries default-max-retries)
                   :message (str "Validated wave dispatched to background. "
                                 "Poll get_validated_wave_status(session_id: \"" session-id "\") for progress.")})})))

    (catch Exception e
      (log/error e "dispatch_validated_wave failed")
      (mcp-error (str "Validated wave failed: " (.getMessage e))))))

(defn handle-get-validated-wave-status
  "Return the current state of an async validated wave session."
  [{:keys [session_id]}]
  (try
    (when-not session_id
      (throw (ex-info "session_id is required" {})))

    (if-let [session (get-validated-wave-session session_id)]
      (let [running? (= :running (:status session))]
        {:type "text"
         :text (json/write-str
                (if running?
                  {:session_id session_id
                   :status "running"
                   :task_count (:task-count session)
                   :started_at (:started-at session)
                   :message "Validated wave is still executing. Poll again."}
                  (merge
                   {:session_id session_id
                    :status (name (:status session))
                    :task_count (:task-count session)
                    :started_at (:started-at session)
                    :completed_at (:completed-at session)}
                   (when (:iterations session)
                     {:iterations (:iterations session)
                      :final_wave_id (:final-wave-id session)
                      :final_plan_id (:final-plan-id session)
                      :modified_files (:modified-files session)})
                   (when (:wave-id session)
                     {:wave_id (:wave-id session)
                      :plan_id (:plan-id session)
                      :completed_tasks (:completed-tasks session)
                      :failed_tasks (:failed-tasks session)
                      :next_steps (:next-steps session)})
                   (when-let [exec-failures (:execution-failures session)]
                     {:execution_failures exec-failures})
                   (when (= :partial (:status session))
                     (merge
                      {:message (:message session)}
                      (when (:findings session)
                        {:remaining_findings (count (:findings session))
                         :files_with_errors (:files-with-errors session)})))
                   (when (:error session)
                     {:error (:error session)})
                   (when (seq (:history session))
                     {:iteration_history
                      (mapv #(select-keys % [:iteration :wave-id :finding-count :execution-failures])
                            (:history session))}))))})
      {:type "text"
       :text (json/write-str {:error "Session not found"
                              :session_id session_id})})
    (catch Exception e
      (log/error e "get_validated_wave_status failed")
      {:type "text"
       :text (json/write-str {:error (.getMessage e)})})))

(def tools
  "Tool definitions for validated wave execution."
  [{:name "get_validated_wave_status"
    :description "Get the status of an async validated wave execution. Returns running state or full results when complete. Use the session_id returned by dispatch_validated_wave to poll."
    :inputSchema {:type "object"
                  :properties {"session_id" {:type "string"
                                             :description "Session ID from dispatch_validated_wave response"}}
                  :required ["session_id"]}
    :handler handle-get-validated-wave-status}
   {:name "dispatch_validated_wave"
    :description "Dispatch multiple drones with post-execution validation and self-healing. Runs kondo_lint after each iteration, generates fix tasks for errors, and re-dispatches until validation passes or max retries reached. Use this instead of dispatch_drone_wave when you need quality gates on drone output. Set review_mode=true for review-before-apply workflow where drones propose diffs for human review before applying."
    :inputSchema {:type "object"
                  :properties {"tasks" {:type "array"
                                        :items {:type "object"
                                                :properties {"file" {:type "string"
                                                                     :description "File path to modify"}
                                                             "task" {:type "string"
                                                                     :description "Task description for this file"}}
                                                :required ["file" "task"]}
                                        :description "Array of {file, task} objects to execute"}
                               "review_mode" {:type "boolean"
                                              :description "When true, use review-before-apply workflow: drones propose diffs without applying, then use review_wave_diffs/approve_wave_diffs (default: false)"}
                               "validate" {:type "boolean"
                                           :description "Run clj-kondo lint after execution (default: true, ignored in review_mode)"}
                               "max_retries" {:type "integer"
                                              :description "Max retry iterations for validation failures (default: 3, ignored in review_mode)"}
                               "lint_level" {:type "string"
                                             :enum ["error" "warning" "info"]
                                             :description "Lint severity threshold (default: error, ignored in review_mode)"}
                               "preset" {:type "string"
                                         :description "Drone preset (default: drone-worker)"}
                               "trace" {:type "boolean"
                                        :description "Emit progress events (default: true)"}
                               "cwd" {:type "string"
                                      :description "Working directory override for path resolution"}}
                  :required ["tasks"]}
    :handler handle-dispatch-validated-wave}])
