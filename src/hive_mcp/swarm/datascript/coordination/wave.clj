(ns hive-mcp.swarm.datascript.coordination.wave
  (:require [datascript.core :as d]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.ledger.default :as ledger-default]
            [taoensso.timbre :as log]))

(declare create-wave! get-wave get-all-waves update-wave-counts! complete-wave!)

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
      (let [report (d/transact! c [{:db/id eid
                                    :wave/status status
                                    :wave/active-count 0
                                    :wave/completed-at (conn/now)}])]
        (ledger-default/append!
         {:type :wave/completed
          :payload {:wave-id wave-id :status status}})
        report))))
