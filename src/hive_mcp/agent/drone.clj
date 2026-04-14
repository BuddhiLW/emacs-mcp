(ns hive-mcp.agent.drone
  "Drone delegation - token-optimized leaf agents for task execution."
  (:require [hive-mcp.agent.protocol :refer [IAgent]]
            [hive-mcp.agent.drone.domain :as domain]
            [hive-mcp.agent.drone.execution :as execution]
            [hive-mcp.agent.drone.augment :as augment]
            [hive-mcp.agent.drone.diff-mgmt :as diff-mgmt]
            [hive-mcp.agent.drone.decompose :as decompose]
            [hive-mcp.agent.drone.sandbox :as sandbox]
            [hive-mcp.agent.drone.retry :as retry]
            [hive-mcp.agent.config :as config]
            [hive-mcp.agent.routing :as routing]
            [hive-mcp.agent.drone.tools :as drone-tools]
            [hive-mcp.agent.drone.preset :as preset]
            [hive-mcp.tools.diff :as diff]
            [hive-mcp.swarm.coordinator :as coordinator]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.logic :as logic]
            [hive-mcp.events.core :as ev]
            [hive-mcp.telemetry.prometheus :as prom]
            [hive-mcp.tools.swarm.channel :as channel]
            [clojure.set]
            [hive-dsl.result :as r]
            [hive-dsl.bounded-atom :refer [bkeys]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn allowed-tools
  "DEPRECATED: Use drone-tools/get-tools-for-drone instead."
  []
  (vec (drone-tools/full-toolset)))

(defn delegate!
  "Delegate a task to a drone via phase-based execution."
  [{:keys [task files task-type preset trace parent-id cwd skip-auto-apply wave-id backend]
    :or {trace true
         skip-auto-apply false}}
   delegate-fn]
  (let [task-spec (domain/->task-spec
                   {:task task
                    :files files
                    :task-type task-type
                    :preset preset
                    :cwd cwd
                    :parent-id parent-id
                    :wave-id wave-id
                    :trace trace
                    :skip-auto-apply skip-auto-apply
                    :backend backend})]
    (execution/run-execution! task-spec delegate-fn)))

(defn delegate-agentic!
  "Delegate a task to an in-process agentic drone with session store."
  [{:keys [task files task-type preset trace parent-id cwd skip-auto-apply wave-id backend model seeds
           ctx-refs kg-node-ids]
    :or {trace true
         skip-auto-apply false}}]
  (let [task-spec (domain/->task-spec
                   {:task task
                    :files files
                    :task-type task-type
                    :preset preset
                    :cwd cwd
                    :parent-id parent-id
                    :wave-id wave-id
                    :trace trace
                    :skip-auto-apply skip-auto-apply
                    :backend backend
                    :model model
                    :seeds seeds
                    :ctx-refs ctx-refs
                    :kg-node-ids kg-node-ids})]
    (execution/run-agentic-execution! task-spec)))

(defn delegate-with-retry!
  "Delegate a task to a drone with automatic retry on transient failures."
  [{:keys [_task _files preset max-retries initial-delay-ms max-delay-ms on-retry]
    :or {max-retries 3
         initial-delay-ms 1000
         max-delay-ms 30000}
    :as opts}
   delegate-fn]
  (let [agent-id (str "drone-retry-" (java.util.UUID/randomUUID))
        current-model (config/resolve-model {:preset (or preset "drone-worker")})
        retry-opts {:max-retries max-retries
                    :initial-delay-ms initial-delay-ms
                    :max-delay-ms max-delay-ms
                    :model current-model
                    :preset preset
                    :drone-id agent-id
                    :task-id (str "task-" agent-id)
                    :on-retry on-retry}
        start-time (System/currentTimeMillis)
        retry-count (atom 0)
        models-tried (atom [current-model])]

    (try
      (let [result (retry/with-retry
                     (fn [exec-opts]
                       (let [effective-opts (if-let [new-model (:model exec-opts)]
                                              (assoc opts :model new-model)
                                              opts)]
                         (delegate! effective-opts delegate-fn)))
                     (assoc retry-opts
                            :on-retry (fn [attempt ex strategy]
                                        (swap! retry-count inc)
                                        (when-let [new-model (:model strategy)]
                                          (swap! models-tried conj new-model))
                                        (when on-retry
                                          (on-retry attempt ex strategy)))))]

        (assoc result
               :retry-info {:retries @retry-count
                            :models-tried @models-tried
                            :total-duration-ms (- (System/currentTimeMillis) start-time)}))

      (catch Exception e
        (log/error {:event :drone/retry-exhausted
                    :drone-id agent-id
                    :retries @retry-count
                    :models-tried @models-tried
                    :total-duration-ms (- (System/currentTimeMillis) start-time)
                    :error (ex-message e)})
        (throw (ex-info "Drone execution failed after retries"
                        {:retries @retry-count
                         :models-tried @models-tried
                         :total-duration-ms (- (System/currentTimeMillis) start-time)
                         :original-error (ex-message e)}
                        e))))))

(defrecord Drone [id model task-type cwd max-steps parent-id project-id
                  state-atom]
  IAgent

  (spawn! [_this opts]
    (let [{:keys [files task-id]} opts
          effective-task-id (or task-id (str "task-" id))
          effective-parent (or (:parent-id opts) parent-id
                               (System/getenv "CLAUDE_SWARM_SLAVE_ID"))]

      (let [tx-result (ds/add-slave! id {:slave/status :spawning
                                         :slave/name "drone"
                                         :slave/agent-type :drone
                                         :slave/depth 2
                                         :slave/parent effective-parent
                                         :slave/cwd cwd
                                         :slave/project-id project-id})]
        (when-not (and tx-result (seq (:tx-data tx-result)))
          (log/error {:event :drone/spawn-failed
                      :drone-id id
                      :reason "DataScript registration failed"})
          (throw (ex-info "Failed to register drone in DataScript"
                          {:drone-id id}))))

      (when (seq files)
        (let [result (coordinator/atomic-claim-files! effective-task-id id files)]
          (if (:acquired? result)
            (do
              (swap! state-atom assoc
                     :claimed-files (vec files)
                     :current-task-id effective-task-id)
              (log/info "Drone spawned and claimed files"
                        {:drone-id id :files-claimed (:files-claimed result)}))
            (do
              (ds/remove-slave! id)
              (throw (ex-info "Failed to claim files during spawn"
                              {:drone-id id
                               :conflicts (:conflicts result)}))))))

      (ev/dispatch [:drone/started {:drone-id id
                                    :parent-id effective-parent
                                    :files (or files [])
                                    :task-type task-type}])

      (log/info "Drone spawned" {:id id :task-type task-type :files (count (or files 0))})
      id))

  (dispatch! [_this task-opts]
    (let [{:keys [task files delegate-fn skip-auto-apply wave-id trace backend]
           :or {trace true}} task-opts
          {:keys [claimed-files current-task-id]} @state-atom
          effective-files (vec (distinct (concat (or claimed-files []) (or files []))))
          task-id (or current-task-id (str "task-" id "-" (System/currentTimeMillis)))]

      (ds/update-slave! id {:slave/status :working})

      (let [effective-task-type (or task-type (preset/get-task-type task effective-files))
            minimal-tools (drone-tools/get-tools-for-drone effective-task-type effective-files)
            effective-preset (preset/select-drone-preset task effective-files)
            model-selection (routing/route-and-select task effective-files {:directory cwd})
            selected-model (or model (:model model-selection))
            step-budget (or max-steps (decompose/get-step-budget task effective-files))
            augmented-task (augment/augment-task task effective-files {:project-root cwd})
            diffs-before (set (bkeys diff/pending-diffs))
            effective-root (or cwd (diff/get-project-root))
            drone-sandbox (sandbox/create-sandbox (or effective-files []) effective-root)

            execution-fn (or delegate-fn
                             (fn [_opts]
                               (throw (ex-info "No delegate-fn provided - use delegate! for standalone execution"
                                               {:drone-id id}))))
            start-time (System/currentTimeMillis)
            result (execution-fn {:backend backend
                                  :preset effective-preset
                                  :model selected-model
                                  :task augmented-task
                                  :tools minimal-tools
                                  :max-steps step-budget
                                  :trace trace
                                  :sandbox {:allowed-files (:allowed-files drone-sandbox)
                                            :allowed-dirs (:allowed-dirs drone-sandbox)
                                            :blocked-patterns (map str (:blocked-patterns drone-sandbox))
                                            :blocked-tools (:blocked-tools drone-sandbox)}})
            diffs-after (set (bkeys diff/pending-diffs))
            new-diff-ids (clojure.set/difference diffs-after diffs-before)
            duration-ms (- (System/currentTimeMillis) start-time)

            _ (when (and wave-id (seq new-diff-ids))
                (diff-mgmt/tag-diffs-with-wave! new-diff-ids wave-id))
            diff-results (if skip-auto-apply
                           {:applied [] :failed [] :proposed (vec new-diff-ids)}
                           (diff-mgmt/auto-apply-diffs! id new-diff-ids))]

        (if (= :completed (:status result))
          (ev/dispatch [:drone/completed {:drone-id id
                                          :task-id task-id
                                          :parent-id parent-id
                                          :files-modified (:applied diff-results)
                                          :duration-ms duration-ms}])
          (ev/dispatch [:drone/failed {:drone-id id
                                       :task-id task-id
                                       :parent-id parent-id
                                       :error (str (:result result))
                                       :error-type :execution}]))

        ;; Bridge: write result to event-journal so `agent collect` can find it
        ;; without depending on NATS/channel infrastructure being active.
        (channel/record-task-result!
         task-id
         (if (= :completed (:status result))
           {:status "completed"
            :result (str (:result result))
            :slave-id id
            :timestamp (System/currentTimeMillis)}
           {:status "failed"
            :error (str (:result result))
            :slave-id id
            :timestamp (System/currentTimeMillis)}))

        (prom/record-drone-result! {:model selected-model
                                    :task-type (name effective-task-type)
                                    :success? (= :completed (:status result))
                                    :duration-ms duration-ms})

        (assoc result
               :agent-id id
               :task-id task-id
               :files-modified (:applied diff-results)
               :proposed-diff-ids (:proposed diff-results)
               :duration-ms duration-ms))))

  (status [_this]
    (let [slave-info (ds/get-slave id)
          {:keys [claimed-files current-task-id]} @state-atom]
      (when slave-info
        (assoc slave-info
               :claimed-files (or claimed-files [])
               :current-task-id current-task-id))))

  (kill! [this]
    (let [result (r/try-effect* :drone/kill-failed
                                (.release-claims! this)
                                (ds/remove-slave! id)
                                (ev/dispatch [:drone/failed {:drone-id id
                                                             :error "Killed by request"
                                                             :error-type :killed}])
                                (log/info "Drone killed" {:id id})
                                {:killed? true :id id})]
      (if (r/ok? result)
        (:ok result)
        (do (log/error "Error killing drone" {:id id :error (:message result)})
            {:killed? false :id id :error (:message result)}))))

  (agent-type [_]
    :drone)

  (can-chain-tools? [_]
    false)

  (claims [_this]
    (or (:claimed-files @state-atom)
        (->> (logic/get-all-claims)
             (filter #(= id (:slave-id %)))
             (map :file)
             vec)))

  (claim-files! [_this files task-id]
    (when (seq files)
      (let [{:keys [claimed-files current-task-id]} @state-atom
            effective-task-id (or task-id current-task-id (str "task-" id))
            result (coordinator/atomic-claim-files! effective-task-id id files)]
        (when (:acquired? result)
          (swap! state-atom assoc
                 :claimed-files (vec (distinct (concat (or claimed-files []) files)))
                 :current-task-id effective-task-id))
        (log/info "Drone claim-files!" {:drone-id id
                                        :acquired? (:acquired? result)
                                        :files-claimed (:files-claimed result)})
        result)))

  (release-claims! [_this]
    (let [{:keys [claimed-files current-task-id]} @state-atom
          files-count (count (or claimed-files []))]
      (if current-task-id
        (coordinator/release-task-claims! current-task-id)
        (logic/release-claims-for-slave! id))
      (swap! state-atom assoc :claimed-files nil :current-task-id nil)
      (log/info "Drone released claims" {:id id :count files-count})
      files-count))

  (upgrade! [_this]
    (let [result (r/try-effect* :drone/upgrade-failed
                                (let [ling-id (str "ling-" (java.util.UUID/randomUUID))
                                      current-claims (or (:claimed-files @state-atom) [])]
                                  (ds/update-slave! id {:slave/status :upgraded})
                                  (log/info "Drone upgrade requested" {:drone-id id
                                                                       :ling-id ling-id
                                                                       :claims current-claims})
                                  {:ling-id ling-id
                                   :cwd cwd
                                   :inherited-claims current-claims
                                   :parent-id parent-id
                                   :project-id project-id}))]
      (if (r/ok? result)
        (:ok result)
        (do (log/error "Drone upgrade failed" {:id id :error (:message result)})
            nil)))))

(defn ->drone
  "Create a new Drone agent instance."
  [id opts]
  (map->Drone {:id id
               :model (:model opts)
               :task-type (:task-type opts)
               :cwd (:cwd opts)
               :max-steps (:max-steps opts)
               :parent-id (:parent-id opts)
               :project-id (:project-id opts)
               :state-atom (atom {:claimed-files nil
                                  :current-task-id nil})}))

(defn create-drone!
  "Create and spawn a new drone agent."
  [id opts]
  (let [drone (->drone id opts)]
    (.spawn! drone opts)))

(defn get-drone
  "Get a drone by ID as a Drone record from DataScript."
  [id]
  (when-let [slave (ds/get-slave id)]
    (when (= :drone (:slave/agent-type slave))
      (->drone id {:cwd (:slave/cwd slave)
                   :parent-id (when-let [p (:slave/parent slave)]
                                (:slave/id p))
                   :project-id (:slave/project-id slave)}))))

(defn list-drones
  "List all active drones, optionally filtered by project-id."
  [& [project-id]]
  (let [slaves (if project-id
                 (ds/get-slaves-by-project project-id)
                 (ds/get-all-slaves))]
    (->> slaves
         (filter #(or (= 2 (:slave/depth %))
                      (= :drone (:slave/agent-type %))))
         (map (fn [s]
                (->drone (:slave/id s)
                         {:cwd (:slave/cwd s)
                          :parent-id (when-let [p (:slave/parent s)]
                                       (:slave/id p))
                          :project-id (:slave/project-id s)}))))))
