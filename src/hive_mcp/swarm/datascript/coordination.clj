(ns hive-mcp.swarm.datascript.coordination
  "Coordination layer operations for swarm orchestration.

   Operations for:
   - Coordinator lifecycle (register, heartbeat, stale detection)
   - Wrap queue (crystal convergence notifications)
   - Change plans (dispatch_drone_wave)
   - Wave execution management
   - Completed task registry (session-scoped for wrap)
   - Kanban movement registry (session-scoped status transitions for wrap)

   DDD: Application Service layer for multi-agent coordination."
  (:require [datascript.core :as d]
            [taoensso.timbre :as log]
            [hive-mcp.swarm.datascript.schema :as schema]
            [hive-mcp.swarm.datascript.connection :as conn]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Wrap Queue Operations (Crystal Convergence)
;;; =============================================================================

(defn add-wrap-notification!
  "Record a ling wrap for coordinator permeation.

   Arguments:
     wrap-id - Unique identifier for this wrap notification
     opts    - Map with keys:
               :agent-id    - ID of the ling that wrapped
               :session-id  - Session tag (e.g., session:2026-01-14:ling-123)
               :project-id  - Project ID for scoping (derived from ling's directory)
               :created-ids - Collection of memory entry IDs created
               :stats       - Map of stats {:notes N :decisions N :conventions N}

   Returns:
     Transaction report"
  [wrap-id {:keys [agent-id session-id project-id created-ids stats]}]
  {:pre [(string? wrap-id)]}
  (let [c (conn/ensure-conn)]
    (d/transact! c
                 [(cond-> {:wrap-queue/id wrap-id
                           :wrap-queue/processed? false
                           :wrap-queue/created-at (conn/now)}
                    agent-id (assoc :wrap-queue/agent-id agent-id)
                    session-id (assoc :wrap-queue/session-id session-id)
                    project-id (assoc :wrap-queue/project-id project-id)
                    (seq created-ids) (assoc :wrap-queue/created-ids (vec created-ids))
                    stats (assoc :wrap-queue/stats stats))])))

(defn get-unprocessed-wraps
  "Get all wrap notifications not yet processed by coordinator.

   Returns:
     Seq of wrap notification maps"
  []
  (let [c (conn/ensure-conn)]
    (d/q '[:find [(pull ?e [*]) ...]
           :where
           [?e :wrap-queue/processed? false]]
         @c)))

(defn get-unprocessed-wraps-for-project
  "Get unprocessed wrap notifications for a specific project.

   Arguments:
     project-id - Project ID to filter by (e.g., derived from directory)

   Returns:
     Seq of wrap notification maps matching the project

   Note: If project-id is nil or 'global', returns all unprocessed wraps."
  [project-id]
  (let [c (conn/ensure-conn)
        db @c]
    (if (or (nil? project-id) (= project-id "global"))
      ;; No filtering - return all
      (d/q '[:find [(pull ?e [*]) ...]
             :where
             [?e :wrap-queue/processed? false]]
           db)
      ;; Filter by project-id
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ ?pid
             :where
             [?e :wrap-queue/processed? false]
             [?e :wrap-queue/project-id ?pid]]
           db project-id))))

(defn get-unprocessed-wraps-for-hierarchy
  "Get unprocessed wrap notifications for project and all its children.

   Uses prefix matching on project-id to support hierarchical project IDs
   like 'parent:child:grandchild'. A query for 'parent' will match:
   - 'parent'
   - 'parent:child'
   - 'parent:child:grandchild'

   Arguments:
     project-id-prefix - Project ID prefix to match (e.g., 'myproject' matches
                         'myproject', 'myproject:submodule', etc.)

   Returns:
     Seq of wrap notification maps for matching projects

   Note: If project-id-prefix is nil or 'global', returns all unprocessed wraps."
  [project-id-prefix]
  (let [c (conn/ensure-conn)
        db @c]
    (if (or (nil? project-id-prefix) (= project-id-prefix "global"))
      ;; No filtering - return all
      (d/q '[:find [(pull ?e [*]) ...]
             :where
             [?e :wrap-queue/processed? false]]
           db)
      ;; Filter by project-id prefix using clojure.string/starts-with?
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ ?prefix
             :where
             [?e :wrap-queue/processed? false]
             [?e :wrap-queue/project-id ?pid]
             [(clojure.string/starts-with? ?pid ?prefix)]]
           db project-id-prefix))))

(defn mark-wrap-processed!
  "Mark a wrap notification as processed.

   Arguments:
     wrap-id - ID of the wrap notification to mark

   Returns:
     Transaction report or nil if wrap-id not found"
  [wrap-id]
  (let [c (conn/ensure-conn)
        eid (d/q '[:find ?e .
                   :in $ ?id
                   :where [?e :wrap-queue/id ?id]]
                 @c wrap-id)]
    (when eid
      (d/transact! c
                   [[:db/add eid :wrap-queue/processed? true]]))))

;;; =============================================================================
;;; Change Plan Operations (dispatch_drone_wave)
;;; =============================================================================

(defn create-plan!
  "Create a new change plan with items.

   Arguments:
     tasks  - Collection of {:file \"path\" :task \"description\"}
     preset - Drone preset name (default: \"drone-worker\")

   Returns:
     The generated plan-id"
  [tasks preset]
  {:pre [(seq tasks)]}
  (let [c (conn/ensure-conn)
        plan-id (conn/gen-id "plan")
        plan-entity {:change-plan/id plan-id
                     :change-plan/status :pending
                     :change-plan/preset (or preset "drone-worker")
                     :change-plan/created-at (conn/now)}
        item-entities (mapv (fn [{:keys [file task]}]
                              {:change-item/id (conn/gen-id "item")
                               :change-item/plan [:change-plan/id plan-id]
                               :change-item/file file
                               :change-item/task task
                               :change-item/status :pending
                               :change-item/created-at (conn/now)})
                            tasks)]
    (log/debug "Creating plan:" plan-id "with" (count tasks) "items")
    (d/transact! c (into [plan-entity] item-entities))
    plan-id))

(defn get-plan
  "Get a change plan by ID.

   Returns:
     Map with plan attributes or nil if not found"
  [plan-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [e (d/entity db [:change-plan/id plan-id])]
      (-> (into {} e)
          (dissoc :db/id)))))

(defn get-pending-items
  "Get all pending items for a plan.

   Arguments:
     plan-id - Plan to get items for

   Returns:
     Seq of item maps with :pending status"
  [plan-id]
  (let [c (conn/ensure-conn)
        db @c
        plan-eid (:db/id (d/entity db [:change-plan/id plan-id]))]
    (when plan-eid
      (let [eids (d/q '[:find [?e ...]
                        :in $ ?plan-eid
                        :where
                        [?e :change-item/plan ?plan-eid]
                        [?e :change-item/status :pending]]
                      db plan-eid)]
        (->> eids
             (map #(d/entity db %))
             (map (fn [e]
                    (-> (into {} e)
                        (dissoc :db/id)
                        (update :change-item/plan (constantly plan-id))))))))))

(defn get-plan-items
  "Get all items for a plan.

   Arguments:
     plan-id - Plan to get items for

   Returns:
     Seq of item maps"
  [plan-id]
  (let [c (conn/ensure-conn)
        db @c
        plan-eid (:db/id (d/entity db [:change-plan/id plan-id]))]
    (when plan-eid
      (let [eids (d/q '[:find [?e ...]
                        :in $ ?plan-eid
                        :where
                        [?e :change-item/plan ?plan-eid]]
                      db plan-eid)]
        (->> eids
             (map #(d/entity db %))
             (map (fn [e]
                    (-> (into {} e)
                        (dissoc :db/id)
                        (update :change-item/plan (constantly plan-id))))))))))

(defn update-item-status!
  "Update a change item's status.

   Arguments:
     item-id - Item to update
     status  - New status
     opts    - Optional map with :drone-id :result

   Returns:
     Transaction report or nil if item not found"
  [item-id status & [{:keys [drone-id result]}]]
  {:pre [(contains? schema/item-statuses status)]}
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:change-item/id item-id]))]
      (let [tx-data (cond-> {:db/id eid
                             :change-item/status status}
                      drone-id (assoc :change-item/drone-id drone-id)
                      result (assoc :change-item/result result)
                      (#{:completed :failed} status) (assoc :change-item/completed-at (conn/now)))]
        (log/debug "Updating item:" item-id "to status:" status)
        (d/transact! c [tx-data])))))

(defn update-plan-status!
  "Update a change plan's status.

   Arguments:
     plan-id - Plan to update
     status  - New status

   Returns:
     Transaction report or nil if plan not found"
  [plan-id status]
  {:pre [(contains? schema/plan-statuses status)]}
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:change-plan/id plan-id]))]
      (let [tx-data (cond-> {:db/id eid
                             :change-plan/status status}
                      (#{:completed :failed} status) (assoc :change-plan/completed-at (conn/now)))]
        (log/debug "Updating plan:" plan-id "to status:" status)
        (d/transact! c [tx-data])))))

;;; =============================================================================
;;; Wave Operations
;;; =============================================================================

(defn create-wave!
  "Create a new wave execution for a plan.

   Arguments:
     plan-id     - Plan to execute
     concurrency - Max concurrent drones (default: 3)

   Returns:
     The generated wave-id"
  [plan-id & [{:keys [concurrency] :or {concurrency 3}}]]
  (let [c (conn/ensure-conn)
        wave-id (conn/gen-id "wave")]
    (d/transact! c [{:wave/id wave-id
                     :wave/plan [:change-plan/id plan-id]
                     :wave/concurrency concurrency
                     :wave/active-count 0
                     :wave/completed-count 0
                     :wave/failed-count 0
                     :wave/status :running
                     :wave/started-at (conn/now)}])
    (log/info "Created wave:" wave-id "for plan:" plan-id)
    wave-id))

(defn get-wave
  "Get a wave by ID.

   Returns:
     Map with wave attributes or nil if not found"
  [wave-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [e (d/entity db [:wave/id wave-id])]
      (-> (into {} e)
          (dissoc :db/id)
          (update :wave/plan #(when % (:change-plan/id %)))))))

(defn get-all-waves
  "Get all waves.

   Returns:
     Seq of maps with wave attributes"
  []
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :where [?e :wave/id _]]
                  db)]
    (->> eids
         (map #(d/entity db %))
         (map (fn [e]
                (-> (into {} e)
                    (dissoc :db/id)
                    (update :wave/plan #(when % (:change-plan/id %)))))))))

(defn update-wave-counts!
  "Update wave execution counts.

   Arguments:
     wave-id - Wave to update
     delta   - Map with delta values {:active +1 :completed +1 :failed 0}

   Returns:
     Transaction report"
  [wave-id {:keys [active completed failed] :or {active 0 completed 0 failed 0}}]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [e (d/entity db [:wave/id wave-id])]
      (let [eid (:db/id e)
            new-active (+ (or (:wave/active-count e) 0) active)
            new-completed (+ (or (:wave/completed-count e) 0) completed)
            new-failed (+ (or (:wave/failed-count e) 0) failed)]
        (d/transact! c [{:db/id eid
                         :wave/active-count new-active
                         :wave/completed-count new-completed
                         :wave/failed-count new-failed}])))))

(defn complete-wave!
  "Mark a wave as completed.

   Arguments:
     wave-id - Wave to complete
     status  - Final status (:completed :partial-failure :failed :cancelled)

   Returns:
     Transaction report"
  [wave-id status]
  {:pre [(contains? #{:completed :partial-failure :failed :cancelled} status)]}
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:wave/id wave-id]))]
      (log/info "Completing wave:" wave-id "with status:" status)
      (d/transact! c [{:db/id eid
                       :wave/status status
                       :wave/active-count 0
                       :wave/completed-at (conn/now)}]))))

;;; =============================================================================
;;; Coordinator Lifecycle Operations
;;; =============================================================================

(def ^:private stale-threshold-ms
  "Coordinator is considered stale after this many milliseconds without heartbeat.
   Default: 2 minutes (coordinated with heartbeat interval of ~30s)"
  (* 2 60 1000))

(defn register-coordinator!
  "Register a new coordinator instance.

   Arguments:
     coordinator-id - Unique identifier (required)
     opts           - Map with optional keys:
                      :project    - Project identifier
                      :pid        - OS process ID (default: current JVM PID)
                      :session-id - Session UUID (default: auto-generated)

   Returns:
     Transaction report with :tempids"

  [coordinator-id {:keys [project pid session-id]}]
  {:pre [(string? coordinator-id)]}
  (let [c (conn/ensure-conn)
        current-pid (or pid (.pid (java.lang.ProcessHandle/current)))
        session (or session-id (str (java.util.UUID/randomUUID)))
        tx-data {:coordinator/id coordinator-id
                 :coordinator/project project
                 :coordinator/pid current-pid
                 :coordinator/session-id session
                 :coordinator/started-at (conn/now)
                 :coordinator/heartbeat-at (conn/now)
                 :coordinator/status :active}]
    (log/info "Registering coordinator:" coordinator-id "project:" project "pid:" current-pid)
    (d/transact! c [tx-data])))

(defn update-heartbeat!
  "Update a coordinator's heartbeat timestamp.
   Also ensures status is :active (reactivates stale coordinators).

   Arguments:
     coordinator-id - Coordinator to update

   Returns:
     Transaction report or nil if coordinator not found"

  [coordinator-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:coordinator/id coordinator-id]))]
      (log/trace "Heartbeat for coordinator:" coordinator-id)
      (d/transact! c [{:db/id eid
                       :coordinator/heartbeat-at (conn/now)
                       :coordinator/status :active}]))))

(defn get-coordinator
  "Get a coordinator by ID.

   Returns:
     Map with coordinator attributes or nil if not found"
  [coordinator-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [e (d/entity db [:coordinator/id coordinator-id])]
      (-> (into {} e)
          (dissoc :db/id)))))

(defn get-all-coordinators
  "Get all coordinators.

   Returns:
     Seq of maps with coordinator attributes"
  []
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :where [?e :coordinator/id _]]
                  db)]
    (->> eids
         (map #(d/entity db %))
         (map (fn [e]
                (-> (into {} e)
                    (dissoc :db/id)))))))

(defn get-coordinators-by-status
  "Get coordinators filtered by status.

   Arguments:
     status - Status to filter by (:active :stale :terminated)

   Returns:
     Seq of coordinator maps"
  [status]
  {:pre [(contains? schema/coordinator-statuses status)]}
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :in $ ?status
                    :where
                    [?e :coordinator/id _]
                    [?e :coordinator/status ?status]]
                  db status)]
    (->> eids
         (map #(d/entity db %))
         (map (fn [e]
                (-> (into {} e)
                    (dissoc :db/id)))))))

(defn get-coordinators-for-project
  "Get all coordinators for a specific project.

   Arguments:
     project - Project identifier

   Returns:
     Seq of coordinator maps"
  [project]
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :in $ ?project
                    :where
                    [?e :coordinator/id _]
                    [?e :coordinator/project ?project]]
                  db project)]
    (->> eids
         (map #(d/entity db %))
         (map (fn [e]
                (-> (into {} e)
                    (dissoc :db/id)))))))

(defn mark-coordinator-terminated!
  "Mark a coordinator as terminated (graceful shutdown).

   Arguments:
     coordinator-id - Coordinator to mark

   Returns:
     Transaction report or nil if coordinator not found"
  [coordinator-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:coordinator/id coordinator-id]))]
      (log/info "Marking coordinator terminated:" coordinator-id)
      (d/transact! c [{:db/id eid
                       :coordinator/status :terminated}]))))

(defn mark-coordinator-stale!
  "Mark a coordinator as stale (not responding).

   Arguments:
     coordinator-id - Coordinator to mark

   Returns:
     Transaction report or nil if coordinator not found"
  [coordinator-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:coordinator/id coordinator-id]))]
      (log/warn "Marking coordinator stale:" coordinator-id)
      (d/transact! c [{:db/id eid
                       :coordinator/status :stale}]))))

(defn cleanup-stale-coordinators!
  "Find and mark coordinators as stale if their heartbeat is too old.
   Returns coordinators that were marked stale.

   Arguments:
     threshold-ms - Optional custom threshold in ms (default: 2 minutes)

   Returns:
     Seq of coordinator-ids that were marked stale"

  [& [{:keys [threshold-ms] :or {threshold-ms stale-threshold-ms}}]]
  (let [c (conn/ensure-conn)
        db @c
        cutoff-ms (- (System/currentTimeMillis) threshold-ms)
        ;; Find active coordinators with heartbeat info
        active-coords (d/q '[:find ?e ?id ?hb
                             :where
                             [?e :coordinator/id ?id]
                             [?e :coordinator/status :active]
                             [?e :coordinator/heartbeat-at ?hb]]
                           db)
        ;; Filter in Clojure (DataScript doesn't support .getTime in queries)
        stale-eids (->> active-coords
                        (filter (fn [[_ _ hb]]
                                  (< (.getTime hb) cutoff-ms)))
                        (map (fn [[eid id _]] [eid id])))]
    (when (seq stale-eids)
      (log/warn "Found" (count stale-eids) "stale coordinators")
      (doseq [[eid coordinator-id] stale-eids]
        (log/warn "Marking coordinator stale:" coordinator-id)
        (d/transact! c [{:db/id eid :coordinator/status :stale}]))
      (map second stale-eids))))

(defn cleanup-stale-claims!
  "Remove claims older than threshold with no heartbeat.
   Call at wave start and completion.

   Arguments:
     threshold-ms - Age threshold in milliseconds (default: 5 minutes)

   Returns:
     Count of claims removed"

  [& [{:keys [threshold-ms] :or {threshold-ms (* 5 60 1000)}}]]
  (let [c (conn/ensure-conn)
        db @c
        cutoff-ms (- (System/currentTimeMillis) threshold-ms)
        ;; Find claims with created-at older than cutoff
        stale-claims (d/q '[:find ?e ?file ?created
                            :where
                            [?e :claim/file ?file]
                            [?e :claim/created-at ?created]]
                          db)
        ;; Filter in Clojure (DataScript date comparison)
        stale-eids (->> stale-claims
                        (filter (fn [[_ _ created]]
                                  (< (.getTime created) cutoff-ms)))
                        (map first))]
    (when (seq stale-eids)
      (log/warn "Cleaning up" (count stale-eids) "stale claims")
      (doseq [eid stale-eids]
        (d/transact! c [[:db/retractEntity eid]])))
    (count stale-eids)))

(defn remove-coordinator!
  "Remove a coordinator entity.
   Should only be used for cleanup after graceful termination.

   Arguments:
     coordinator-id - Coordinator to remove

   Returns:
     Transaction report or nil if coordinator not found"
  [coordinator-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:coordinator/id coordinator-id]))]
      (log/info "Removing coordinator:" coordinator-id)
      (d/transact! c [[:db/retractEntity eid]]))))

;;; =============================================================================
;;; Completed Task Registry (Session-scoped for wrap)
;;; =============================================================================

(defn register-completed-task!
  "Register a completed task for wrap to harvest.

   Called by on-kanban-done hook when tasks move to DONE.
   Tasks are session-scoped and cleared after wrap.

   Arguments:
     task-id - Unique identifier (e.g., kanban task ID)
     opts    - Map with optional keys:
               :title      - Task title/description
               :agent-id   - ID of completing agent (auto-detected if not provided)
               :project-id - Project scope

   Returns:
     Transaction report"

  [task-id {:keys [title agent-id project-id]}]
  {:pre [(string? task-id)]}
  (let [c (conn/ensure-conn)
        ;; Auto-detect agent-id from environment if not provided
        auto-agent-id (or agent-id (System/getenv "CLAUDE_SWARM_SLAVE_ID"))
        tx-data (cond-> {:completed-task/id task-id
                         :completed-task/completed-at (conn/now)}
                  title (assoc :completed-task/title title)
                  auto-agent-id (assoc :completed-task/agent-id auto-agent-id)
                  project-id (assoc :completed-task/project-id project-id))]
    (log/debug "Registering completed task:" task-id "title:" title "project-id:" project-id)
    (d/transact! c [tx-data])))

(defn get-completed-task
  "Get a completed task by ID.

   Returns:
     Map with completed-task attributes or nil if not found"
  [task-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [e (d/entity db [:completed-task/id task-id])]
      (-> (into {} e)
          (dissoc :db/id)))))

(defn get-completed-tasks-this-session
  "Get all completed tasks registered this session.

   Used by wrap to harvest task completions.

   Options:
   - :agent-id   - Filter by specific agent
   - :project-id - Filter by project scope

   Returns:
     Seq of completed-task maps sorted by completion time (most recent first)"
  [& {:keys [agent-id project-id]}]
  (let [c (conn/ensure-conn)
        db @c
        ;; Query all completed tasks
        all-tasks (d/q '[:find [(pull ?e [*]) ...]
                         :where [?e :completed-task/id _]]
                       db)]
    (->> all-tasks
         ;; Filter by agent-id if provided
         (filter (fn [task]
                   (or (nil? agent-id)
                       (= agent-id (:completed-task/agent-id task)))))
         ;; Filter by project-id if provided (HCR scope isolation)
         (filter (fn [task]
                   (or (nil? project-id)
                       (= project-id (:completed-task/project-id task)))))
         ;; Sort by completion time (most recent first)
         (sort-by :completed-task/completed-at #(compare %2 %1))
         ;; Clean up output format
         (map (fn [task]
                (dissoc task :db/id))))))

(defn clear-completed-tasks!
  "Clear all completed tasks from the registry.

   Called after wrap to reset for next session.

   Returns:
     Number of tasks cleared"
  []
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :where [?e :completed-task/id _]]
                  db)
        count-cleared (count eids)]
    (when (seq eids)
      (log/debug "Clearing" count-cleared "completed tasks")
      (d/transact! c (mapv (fn [eid] [:db/retractEntity eid]) eids)))
    count-cleared))

;;; =============================================================================
;;; Kanban Movement Registry (Session-scoped status transitions for wrap)
;;; =============================================================================

(defn register-kanban-movement!
  "Register a kanban status transition for wrap to harvest.

   Called by kanban create/move handlers to track all status changes,
   not just completions. Session-scoped and cleared after wrap.

   Arguments:
     opts - Map with keys:
            :task-id    - Kanban task ID
            :title      - Task title
            :from       - Previous status (nil for creation)
            :to         - New status
            :agent-id   - Agent that triggered the move (auto-detected)
            :project-id - Project scope

   Returns:
     Transaction report"
  [{:keys [task-id title from to agent-id project-id]}]
  {:pre [(string? task-id) (string? to)]}
  (let [c (conn/ensure-conn)
        auto-agent-id (or agent-id (System/getenv "CLAUDE_SWARM_SLAVE_ID"))
        movement-id (str "mv-" (System/currentTimeMillis) "-" (subs (str (java.util.UUID/randomUUID)) 0 8))
        tx-data (cond-> {:kanban-movement/id movement-id
                         :kanban-movement/task-id task-id
                         :kanban-movement/to to
                         :kanban-movement/at (conn/now)}
                  title (assoc :kanban-movement/title title)
                  from (assoc :kanban-movement/from from)
                  auto-agent-id (assoc :kanban-movement/agent-id auto-agent-id)
                  project-id (assoc :kanban-movement/project-id project-id))]
    (log/debug "Registering kanban movement:" task-id from "->" to "project-id:" project-id)
    (d/transact! c [tx-data])))

(defn get-kanban-movements-this-session
  "Get all kanban movements registered this session.

   Used by wrap to harvest status transitions.

   Options:
   - :agent-id   - Filter by specific agent
   - :project-id - Filter by project scope

   Returns:
     Seq of kanban-movement maps sorted by timestamp (chronological)"
  [& {:keys [agent-id project-id]}]
  (let [c (conn/ensure-conn)
        db @c
        all-movements (d/q '[:find [(pull ?e [*]) ...]
                             :where [?e :kanban-movement/id _]]
                           db)]
    (->> all-movements
         (filter (fn [mv]
                   (or (nil? agent-id)
                       (= agent-id (:kanban-movement/agent-id mv)))))
         (filter (fn [mv]
                   (or (nil? project-id)
                       (= project-id (:kanban-movement/project-id mv)))))
         (sort-by :kanban-movement/at)
         (map (fn [mv] (dissoc mv :db/id))))))

(defn clear-kanban-movements!
  "Clear all kanban movements from the registry.

   Called after wrap to reset for next session.

   Returns:
     Number of movements cleared"
  []
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :where [?e :kanban-movement/id _]]
                  db)
        count-cleared (count eids)]
    (when (seq eids)
      (log/debug "Clearing" count-cleared "kanban movements")
      (d/transact! c (mapv (fn [eid] [:db/retractEntity eid]) eids)))
    count-cleared))
