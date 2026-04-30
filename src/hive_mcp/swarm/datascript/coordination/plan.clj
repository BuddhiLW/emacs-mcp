(ns hive-mcp.swarm.datascript.coordination.plan
  (:require [datascript.core :as d]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.schema :as schema]
            [taoensso.timbre :as log]))

(declare create-plan! get-plan get-pending-items get-plan-items update-item-status! update-plan-status!)

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
