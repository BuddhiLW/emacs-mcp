(ns hive-mcp.tools.swarm.wave.execution
  "Wave execution orchestrator composing phases into a complete pipeline."
  (:require [hive-mcp.tools.swarm.wave.domain :as domain]
            [hive-mcp.tools.swarm.wave.phases :as phases]
            [hive-mcp.tools.swarm.wave.retry :as retry]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.agent.core :as agent]
            [hive-mcp.knowledge-graph.disc :as kg-disc]
            [hive-mcp.concurrency.pool :as pool]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- build-task-str
  "Build task string with optional staleness warnings."
  [file task]
  (let [disc-warnings (rescue nil (kg-disc/staleness-warnings [file]))
        disc-notice (kg-disc/format-staleness-warnings disc-warnings)]
    (if disc-notice
      (str disc-notice "File: " file "\n\nTask: " task)
      (str "File: " file "\n\nTask: " task))))

(defn- task-result-from-drone
  "Convert drone execution result to TaskResult domain object."
  [id result]
  (if (= :completed (:status result))
    (cond-> (domain/success-result id (:result result))
      (seq (:proposed-diff-ids result))
      (assoc :proposed-diff-ids (:proposed-diff-ids result)))
    (domain/failure-result id (or (:result result) "Drone execution failed"))))

(defn- execute-drone-task-once
  "Execute a single drone task via delegate-fn path without retry."
  [{:keys [change-item/id change-item/file change-item/task]} preset cwd skip-auto-apply wave-id
   & {:keys [backend model seeds ctx-refs kg-node-ids]}]
  (let [task-str (build-task-str file task)
        result (agent/delegate-drone!
                (cond-> {:task task-str
                         :files [file]
                         :preset preset
                         :trace true
                         :cwd cwd
                         :skip-auto-apply skip-auto-apply
                         :wave-id wave-id}
                  backend            (assoc :backend backend)
                  model              (assoc :model model)
                  (seq seeds)        (assoc :seeds seeds)
                  (seq ctx-refs)     (assoc :ctx-refs ctx-refs)
                  (seq kg-node-ids)  (assoc :kg-node-ids kg-node-ids)))]
    (task-result-from-drone id result)))

(defn- execute-drone-task-once-agentic
  "Execute a single drone task via in-process agentic loop without retry."
  [{:keys [change-item/id change-item/file change-item/task]} preset cwd skip-auto-apply wave-id
   & {:keys [backend model seeds ctx-refs kg-node-ids]}]
  (let [task-str (build-task-str file task)
        result (agent/delegate-agentic-drone!
                (cond-> {:task task-str
                         :files [file]
                         :preset preset
                         :trace true
                         :cwd cwd
                         :skip-auto-apply skip-auto-apply
                         :wave-id wave-id}
                  backend            (assoc :backend backend)
                  model              (assoc :model model)
                  (seq seeds)        (assoc :seeds seeds)
                  (seq ctx-refs)     (assoc :ctx-refs ctx-refs)
                  (seq kg-node-ids)  (assoc :kg-node-ids kg-node-ids)))]
    (task-result-from-drone id result)))

(defn execute-drone-task
  "Execute a single drone task with unified retry logic."
  [{:keys [change-item/id change-item/file] :as item} preset cwd skip-auto-apply wave-id
   & {:keys [mode backend model seeds ctx-refs kg-node-ids] :or {mode :delegate}}]
  (log/info "Executing drone task for item:" id "file:" file "cwd:" cwd
            {:review-mode skip-auto-apply :mode mode :backend backend :model model
             :seeds (when (seq seeds) (count seeds))
             :ctx-refs? (some? (seq ctx-refs))})

  (ds/update-item-status! id :dispatched)

  (let [effective-backend (or backend
                              (when (= mode :agentic) :fsm-agentic))
        execute-fn (if (= mode :agentic)
                     #(execute-drone-task-once-agentic item preset cwd skip-auto-apply wave-id
                                                       :backend effective-backend :model model
                                                       :seeds seeds
                                                       :ctx-refs ctx-refs :kg-node-ids kg-node-ids)
                     #(execute-drone-task-once item preset cwd skip-auto-apply wave-id
                                               :backend effective-backend :model model
                                               :seeds seeds
                                               :ctx-refs ctx-refs :kg-node-ids kg-node-ids))]
    (retry/retry-task-execution
     execute-fn
     item
     {:item-id id :file file})))

(defn- record-item-success!
  "Record successful drone execution in DataScript."
  [item-id result-text wave-id]
  (ds/update-item-status! item-id :completed {:result result-text})
  (ds/update-wave-counts! wave-id {:completed 1 :active -1}))

(defn- record-item-failure!
  "Record failed drone execution in DataScript."
  [item-id error-text wave-id]
  (ds/update-item-status! item-id :failed {:result error-text})
  (ds/update-wave-counts! wave-id {:failed 1 :active -1}))

(defn- update-item-state!
  "Update DataScript after drone execution completes."
  [{:keys [item-id success result error] :as _task-result} wave-id]
  (try
    (if success
      (record-item-success! item-id (str result) wave-id)
      (record-item-failure! item-id error wave-id))
    (catch Throwable e
      (log/error {:event :wave/item-status-update-failed
                  :item-id item-id
                  :wave-id wave-id
                  :error (ex-message e)})
      (try
        (record-item-failure! item-id (str "Post-execution error: " (ex-message e)) wave-id)
        (catch Throwable _)))))

(defn make-work-executor
  "Create a work unit executor for batching that composes execute, update, and return."
  [& {:keys [mode backend model seeds ctx-refs kg-node-ids] :or {mode :delegate}}]
  (fn [{:keys [item preset cwd skip-auto-apply wave-id]}]
    (let [result (execute-drone-task item preset cwd skip-auto-apply wave-id
                                     :mode mode :backend backend :model model
                                     :seeds seeds
                                     :ctx-refs ctx-refs :kg-node-ids kg-node-ids)]
      (update-item-state! result wave-id)
      result)))

(defn run-wave!
  "Orchestrate wave execution through all phases."
  [wave-spec items]
  (let [{:keys [plan-id wave-id]} wave-spec
        mode (or (:mode wave-spec) :delegate)
        backend (:backend wave-spec)
        model (:model wave-spec)
        seeds (:seeds wave-spec)
        ctx-refs (:ctx-refs wave-spec)
        kg-node-ids (:kg-node-ids wave-spec)
        effective-wave-id (or wave-id (ds/create-wave! plan-id {:concurrency (:concurrency wave-spec)}))
        start-time (System/nanoTime)]

    (log/info "Starting wave execution"
              {:wave-id effective-wave-id
               :plan-id plan-id
               :item-count (count items)
               :mode mode
               :backend backend
               :model model})

    (try
      (phases/phase:pre-flight! wave-spec items effective-wave-id)

      (phases/phase:register! plan-id items)

      (let [{:keys [batches item-map]} (phases/phase:compute-batches items)

            _ (ds/update-wave-counts! effective-wave-id {:active (count (first batches))})

            execution-result (phases/phase:execute-batches!
                              wave-spec effective-wave-id
                              batches item-map
                              (make-work-executor :mode mode :backend backend :model model
                                                  :seeds seeds
                                                  :ctx-refs ctx-refs :kg-node-ids kg-node-ids))]

        (phases/phase:complete! wave-spec effective-wave-id execution-result start-time items))

      (catch Throwable e
        (phases/phase:handle-error! wave-spec effective-wave-id e start-time)
        (throw e))

      (finally
        (phases/phase:cleanup! effective-wave-id)))))

(defn run-wave-async!
  "Execute a wave asynchronously in a background thread."
  [wave-spec items]
  (let [{:keys [plan-id concurrency]} wave-spec
        wave-id (ds/create-wave! plan-id {:concurrency concurrency})
        item-count (count items)
        spec-with-wave (assoc wave-spec :wave-id wave-id)]

    (pool/with-io
      (try
        (run-wave! spec-with-wave items)
        (catch Throwable e
          (log/error e "Async wave execution failed" {:wave-id wave-id :plan-id plan-id}))))

    {:wave-id wave-id
     :plan-id plan-id
     :item-count item-count}))

(defn execute-wave!
  "Execute a wave for a plan (backwards-compatible API)."
  [plan-id & [{:keys [concurrency trace cwd skip-auto-apply wave-id mode backend model seeds
                      ctx-refs kg-node-ids]
               :or {concurrency domain/default-concurrency
                    trace true
                    skip-auto-apply false
                    mode :delegate}}]]
  (let [plan (ds/get-plan plan-id)]
    (when-not plan
      (throw (ex-info "Plan not found" {:plan-id plan-id})))

    (let [items (ds/get-pending-items plan-id)]
      (when (empty? items)
        (throw (ex-info "No pending items for wave execution"
                        {:plan-id plan-id
                         :message "Plan has no items with :pending status"})))

      (let [preset (:change-plan/preset plan)
            wave-spec (domain/->wave-spec
                       (cond-> {:plan-id plan-id
                                :concurrency concurrency
                                :trace trace
                                :cwd cwd
                                :skip-auto-apply skip-auto-apply
                                :wave-id wave-id
                                :preset preset
                                :mode mode}
                         backend            (assoc :backend backend)
                         model              (assoc :model model)
                         (seq seeds)        (assoc :seeds seeds)
                         (seq ctx-refs)     (assoc :ctx-refs ctx-refs)
                         (seq kg-node-ids)  (assoc :kg-node-ids kg-node-ids)))]

        (:wave-id (run-wave! wave-spec items))))))

(defn execute-wave-async!
  "Execute a wave asynchronously (backwards-compatible API)."
  [plan-id & [{:keys [concurrency trace cwd skip-auto-apply on-complete mode backend model seeds
                      ctx-refs kg-node-ids]
               :or {concurrency domain/default-concurrency
                    trace true
                    skip-auto-apply false
                    mode :delegate}}]]
  (let [plan (ds/get-plan plan-id)]
    (when-not plan
      (throw (ex-info "Plan not found" {:plan-id plan-id})))

    (let [items (ds/get-pending-items plan-id)]
      (when (empty? items)
        (throw (ex-info "No pending items for wave execution"
                        {:plan-id plan-id
                         :message "Plan has no items with :pending status"})))

      (let [preset (:change-plan/preset plan)
            wave-spec (domain/->wave-spec
                       (cond-> {:plan-id plan-id
                                :concurrency concurrency
                                :trace trace
                                :cwd cwd
                                :skip-auto-apply skip-auto-apply
                                :on-complete on-complete
                                :preset preset
                                :mode mode}
                         backend            (assoc :backend backend)
                         model              (assoc :model model)
                         (seq seeds)        (assoc :seeds seeds)
                         (seq ctx-refs)     (assoc :ctx-refs ctx-refs)
                         (seq kg-node-ids)  (assoc :kg-node-ids kg-node-ids)))]

        (run-wave-async! wave-spec items)))))

(defn cancel-wave!
  "Cancel a running wave."
  [wave-id & [{:keys [reason message] :or {reason :explicit}}]]
  (when-let [wave (ds/get-wave wave-id)]
    (let [plan-id (:wave/plan wave)]
      (ds/complete-wave! wave-id :cancelled)
      (ds/update-plan-status! plan-id :cancelled)

      (phases/phase:cleanup! wave-id)

      (log/info {:event :wave/cancelled
                 :wave-id wave-id
                 :plan-id plan-id
                 :reason reason
                 :message message})

      {:cancelled true
       :wave-id wave-id
       :reason reason})))
