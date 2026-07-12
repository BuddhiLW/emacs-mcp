(ns hive-mcp.scheduler.dag-waves
  "DAGWave scheduler for dependency-ordered task dispatch."
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.tools.memory-kanban :as mem-kanban]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.tools.memory.scope :as scope]
            [clojure.core.async :as async :refer [go-loop <! close!]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive.events :as ev]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce dag-state
  (atom {:active    false
         :plan-id   nil
         :max-slots 5
         :wave-log  []
         :dispatched {}      ; {kanban-task-id -> ling-id}
         :completed  #{}     ; set of kanban-task-ids
         :failed     #{}     ; set of kanban-task-ids
         :opts       {}}))   ; original start opts

(defn- wave-progress-payload
  "Current DAG parity snapshot for the drone-roster panel."
  []
  (let [s @dag-state]
    {:run-id        (str (:plan-id s))
     :dispatched    (count (:dispatched s))
     :completed     (count (:completed s))
     :failed        (count (:failed s))
     :in-flight     (mapv (fn [[tid lid]] {:task-id tid :ling-id lid :status "dispatched"})
                          (:dispatched s))
     :completed-ids (vec (:completed s))
     :failed-ids    (vec (:failed s))}))

(defn- emit-wave!
  "Fail-soft emit of a swarm wave event on the generic hive.events multi-bus."
  [variant]
  (result/rescue nil (ev/dispatch-multi [variant (wave-progress-payload)])))

;; =============================================================================
;; Kanban Helpers
;; =============================================================================

(defn- get-kanban-todos
  "Get all kanban tasks with status 'todo' for the given project."
  [directory]
  (result/rescue []
                 (let [r (mem-kanban/handle-mem-kanban-list-slim
                          {:status "todo" :directory directory})]
                   (when-not (:isError r)
                     (let [parsed (json/read-str (:text r) :key-fn keyword)]
                       (if (sequential? parsed) parsed []))))))

(defn- get-kanban-task
  "Get a kanban task by ID from Chroma."
  [task-id]
  (result/rescue nil (facade/get-entry-by-id task-id)))

(defn- kanban-task-done?
  "Check if a kanban task has been completed."
  [task-id completed-set]
  (or (contains? completed-set task-id)
      (nil? (get-kanban-task task-id))))

(defn- move-kanban-done!
  "Move a kanban task to 'done' status."
  [task-id directory]
  (result/rescue nil
                 (mem-kanban/handle-mem-kanban-move
                  {:task_id task-id :new_status "done" :directory directory})))

;; =============================================================================
;; KG Dependency Helpers
;; =============================================================================

(defn- get-task-dependencies
  "Get the task IDs that a given task depends on via KG edges."
  [task-id]
  (result/rescue #{}
                 (let [edges (kg-edges/get-edges-from task-id)
                       depends-on-edges (filter #(= :depends-on (:kg-edge/relation %)) edges)]
                   (set (map :kg-edge/to depends-on-edges)))))

;; =============================================================================
;; Core Pure Functions
;; =============================================================================

(defn- task-role
  "Extract a role for a task, fail-soft. Reads an explicit :role, else a
   `role:<name>` tag from :tags. Returns nil when absent."
  [task]
  (or (:role task)
      (some (fn [t]
              (let [s (str t)]
                (when (str/starts-with? s "role:")
                  (subs s (count "role:")))))
            (:tags task))))

(defn find-ready-tasks
  "Find tasks whose all dependencies have been completed."
  [directory completed dispatched failed]
  (let [todos (get-kanban-todos directory)
        already-handled (into (set (keys dispatched))
                              (into completed failed))]
    (->> todos
         (remove #(contains? already-handled (:id %)))
         (keep (fn [task]
                 (let [task-id (:id task)
                       deps (get-task-dependencies task-id)
                       ;; A dep is satisfied if it's completed or no longer exists
                       all-deps-done? (every? #(kanban-task-done? % completed) deps)]
                   (when all-deps-done?
                     {:task-id task-id
                      :title   (:title task)
                      :role    (task-role task)
                      :deps    deps
                      :dep-count (count deps)}))))
         vec)))

;; =============================================================================
;; Stateful Dispatch Functions
;; =============================================================================

(defn- normalize-role
  "Fail-soft role coercion: nil for absent/blank, else the role value."
  [role]
  (when (and (some? role)
             (not (and (string? role) (str/blank? role))))
    role))

(defn- spawn-ling-for-task
  "Attempt to spawn a ling for a single DAG task. Returns Result.
   Threads the task :role (when present) into create-ling! via the
   :spawn/request seam so Stage-A role overlay applies. Fail-soft: a
   nil/blank role adds no request.
   When :prefer-lightweight? is true (default), uses :headless spawn mode
   which routes to TransparentAgenticLoop (~2MB vs ~280MB ProcessBuilder)."
  [task-id title role {:keys [cwd presets project-id prefer-lightweight?]
                       :or {prefer-lightweight? true}}]
  (let [safe-title (-> (or title "task")
                       str/lower-case
                       (str/replace #"[^a-z0-9]+" "-"))
        truncated (subs safe-title 0 (min 30 (count safe-title)))
        ling-id (str "swarm-dag-" truncated "-" (System/currentTimeMillis))
        role* (normalize-role role)]
    (result/try-effect* :dag/spawn-failed
                        (ling/create-ling! ling-id
                                           (cond-> {:cwd cwd
                                                    :presets (or presets ["ling"])
                                                    :project-id project-id
                                                    :kanban-task-id task-id
                                                    :task title}
                                             prefer-lightweight?
                                             (assoc :spawn-mode :headless)
                                             role*
                                             (assoc :spawn/request {:role role*})))
                        ling-id)))

(defn dispatch-wave!
  "Dispatch lings for ready tasks up to available slots."
  [ready-tasks max-slots opts]
  (let [current-dispatched (:dispatched @dag-state)
        available-slots (max 0 (- max-slots (count current-dispatched)))
        tasks-to-dispatch (take available-slots ready-tasks)
        skipped (- (count ready-tasks) (count tasks-to-dispatch))]

    (when (pos? (count tasks-to-dispatch))
      (log/info "DAGWaves dispatching" (count tasks-to-dispatch)
                "tasks (slots:" available-slots "ready:" (count ready-tasks) ")"))

    (let [dispatched-results
          (doall
           (for [{:keys [task-id title role]} tasks-to-dispatch]
             (let [r (spawn-ling-for-task task-id title role opts)]
               (if-let [ling-id (:ok r)]
                 (do
                   ;; Track dispatch in state
                   (swap! dag-state update :dispatched assoc task-id ling-id)
                   ;; Log wave progress
                   (swap! dag-state update :wave-log conj
                          {:event :dispatched
                           :task-id task-id
                           :ling-id ling-id
                           :title title
                           :timestamp (System/currentTimeMillis)})
                   {:task-id task-id :ling-id ling-id :status :dispatched})
                 (do
                   ;; Mark as failed so we don't keep retrying
                   (swap! dag-state update :failed conj task-id)
                   {:task-id task-id :status :failed :error (:message r)})))))]

      (when (pos? (count tasks-to-dispatch))
        (emit-wave! :workflow/wave-dispatched))

      {:dispatched-count (count (filter #(= :dispatched (:status %)) dispatched-results))
       :dispatched-tasks dispatched-results
       :skipped-count skipped})))

;; =============================================================================
;; Completion Handler
;; =============================================================================

(defn on-ling-complete
  "Handle ling completion event and auto-dispatch next wave."
  [{:keys [agent-id _project-id data]}]
  (when (:active @dag-state)
    (let [;; Find the kanban-task-id for this ling
          slave (ds-queries/get-slave agent-id)
          kanban-task-id (:slave/kanban-task-id slave)
          directory (or (:slave/cwd slave)
                        (get-in @dag-state [:opts :cwd]))]

      (if-not kanban-task-id
        (log/debug "DAGWaves: ling" agent-id "completed but no kanban-task-id (not a DAG task)")

        ;; Only handle tasks that are in our dispatched set
        (when (contains? (:dispatched @dag-state) kanban-task-id)
          (log/info "DAGWaves: ling" agent-id "completed task" kanban-task-id)

          ;; Check if this is a success or failure
          (let [is-failure? (or (= (:event-type data) :error)
                                (= (:event-type data) :blocked)
                                (= (str (:result data)) "failure"))]

            (if is-failure?
              ;; Failed: move to failed set, don't mark kanban done
              (do
                (swap! dag-state (fn [s]
                                   (-> s
                                       (update :dispatched dissoc kanban-task-id)
                                       (update :failed conj kanban-task-id)
                                       (update :wave-log conj
                                               {:event :failed
                                                :task-id kanban-task-id
                                                :ling-id agent-id
                                                :timestamp (System/currentTimeMillis)}))))
                (log/warn "DAGWaves: task" kanban-task-id "FAILED via" agent-id)
                (emit-wave! :workflow/wave-completed))

              ;; Success: mark done, dispatch next wave
              (do
                ;; Move kanban to done
                (move-kanban-done! kanban-task-id directory)

                ;; Update state
                (swap! dag-state (fn [s]
                                   (-> s
                                       (update :dispatched dissoc kanban-task-id)
                                       (update :completed conj kanban-task-id)
                                       (update :wave-log conj
                                               {:event :completed
                                                :task-id kanban-task-id
                                                :ling-id agent-id
                                                :timestamp (System/currentTimeMillis)}))))

                (emit-wave! :workflow/wave-completed)

                ;; Auto-dispatch next wave
                (let [ready (find-ready-tasks directory
                                              (:completed @dag-state)
                                              (:dispatched @dag-state)
                                              (:failed @dag-state))
                      max-slots (:max-slots @dag-state)]
                  (when (seq ready)
                    (log/info "DAGWaves: auto-dispatching" (count ready)
                              "newly ready tasks after" kanban-task-id)
                    (dispatch-wave! ready max-slots (:opts @dag-state)))

                  ;; Check if DAG is complete
                  (when (and (empty? ready)
                             (empty? (:dispatched @dag-state)))
                    (log/info "DAGWaves: ALL TASKS COMPLETE for plan" (:plan-id @dag-state))
                    (hivemind/shout! "coordinator" :completed
                                     {:task (str "DAGWaves plan " (:plan-id @dag-state))
                                      :message (str "All tasks complete. "
                                                    (count (:completed @dag-state)) " succeeded, "
                                                    (count (:failed @dag-state)) " failed.")})
                    (swap! dag-state assoc :active false)))))))))))

;; =============================================================================
;; Channel Subscription (core.async pub/sub)
;; =============================================================================

(defonce ^:private dag-sub-channel (atom nil))

(defn- start-event-listener!
  "Start a go-loop listening for completion events."
  []
  (let [ch (channel/subscribe! :hivemind-completed)]
    (reset! dag-sub-channel ch)
    (go-loop []
      (when-let [event (<! ch)]
        (when (:active @dag-state)
          (result/rescue nil
                         (on-ling-complete {:agent-id  (:agent-id event)
                                            :project-id (:project-id event)
                                            :data       (:data event)})))
        (recur)))
    (log/info "DAGWaves event listener started")))

(defn- stop-event-listener!
  "Stop the event listener go-loop."
  []
  (when-let [ch @dag-sub-channel]
    (let [r (result/try-effect* :dag/unsubscribe-failed
                                (channel/unsubscribe! :hivemind-completed ch))]
      (when (result/err? r)
        (result/rescue nil (close! ch))))
    (reset! dag-sub-channel nil)
    (log/info "DAGWaves event listener stopped")))

;; =============================================================================
;; Lifecycle Functions
;; =============================================================================

(defn start-dag!
  "Initialize and start the DAG scheduler for a plan."
  [plan-id opts]
  (when (:active @dag-state)
    (throw (ex-info "DAG already active. Call stop-dag! first."
                    {:current-plan (:plan-id @dag-state)})))

  (let [{:keys [max-slots cwd presets project-id]
         :or {max-slots 5
              presets ["ling"]}} opts
        effective-project-id (or project-id
                                 (when cwd (scope/get-current-project-id cwd)))]

    ;; Initialize state
    (reset! dag-state
            {:active     true
             :plan-id    plan-id
             :max-slots  max-slots
             :wave-log   []
             :dispatched {}
             :completed  #{}
             :failed     #{}
             :opts       {:cwd cwd
                          :presets presets
                          :project-id effective-project-id}})

    ;; Start event listener for completion detection
    (start-event-listener!)

    ;; Shout start
    (hivemind/shout! "coordinator" :started
                     {:task (str "DAGWaves scheduler for plan " plan-id)
                      :message (str "Max slots: " max-slots " project: " effective-project-id)})

    ;; Find and dispatch first wave
    (let [ready (find-ready-tasks cwd #{} {} #{})
          result (when (seq ready)
                   (dispatch-wave! ready max-slots (:opts @dag-state)))]

      (log/info "DAGWaves started. Plan:" plan-id
                "Ready tasks:" (count ready)
                "Dispatched:" (or (:dispatched-count result) 0))

      {:started true
       :plan-id plan-id
       :max-slots max-slots
       :ready-count (count ready)
       :initial-dispatch result})))

(defn stop-dag!
  "Stop the DAG scheduler."
  []
  (let [state @dag-state]
    ;; Stop event listener
    (stop-event-listener!)

    ;; Mark as inactive
    (swap! dag-state assoc :active false)

    (log/info "DAGWaves stopped. Plan:" (:plan-id state)
              "Completed:" (count (:completed state))
              "Failed:" (count (:failed state))
              "Still dispatched:" (count (:dispatched state)))

    {:stopped true
     :plan-id (:plan-id state)
     :completed-count (count (:completed state))
     :failed-count (count (:failed state))
     :dispatched-count (count (:dispatched state))
     :wave-log (:wave-log state)}))

;; =============================================================================
;; Query Functions
;; =============================================================================

(defn dag-status
  "Get current DAG scheduler progress."
  []
  (let [state @dag-state
        directory (get-in state [:opts :cwd])
        ready (when (:active state)
                (result/rescue []
                               (find-ready-tasks directory
                                                 (:completed state)
                                                 (:dispatched state)
                                                 (:failed state))))]
    {:active       (:active state)
     :plan-id      (:plan-id state)
     :max-slots    (:max-slots state)
     :completed    (count (:completed state))
     :failed       (count (:failed state))
     :dispatched   (count (:dispatched state))
     :ready        (count (or ready []))
     :completed-ids (:completed state)
     :failed-ids   (:failed state)
     :dispatched-map (:dispatched state)
     :wave-log     (take-last 20 (:wave-log state))}))

;; =============================================================================
;; Comment / REPL Usage
;; =============================================================================

(comment
  ;; Typical usage:
  ;; 1. Coordinator creates plan and runs plan-to-kanban
  ;; 2. Start the DAG scheduler:
  (start-dag! "plan-memory-id-here"
              {:cwd "/home/lages/PP/hive/hive-mcp"
               :max-slots 5
               :presets ["ling"]})

  ;; 3. Monitor progress:
  (dag-status)

  ;; 4. Stop when done:
  (stop-dag!)

  ;; Manual completion test (simulates a ling finishing):
  (on-ling-complete {:agent-id "swarm-test-123"
                     :project-id "hive-mcp"
                     :data {:result "success"}})

  ;; Find ready tasks manually:
  (find-ready-tasks "/home/lages/PP/hive/hive-mcp" #{} {} #{}))