(ns hive-mcp.tools.swarm.wave.status
  "Read-only wave status queries and formatting."
  (:require [hive-mcp.swarm.datascript :as ds]
            [clojure.string] [hive-dsl.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn get-wave-status
  "Get current status of a wave execution."
  [wave-id]
  (when-let [wave (ds/get-wave wave-id)]
    {:wave-id wave-id
     :plan-id (:wave/plan wave)
     :status (:wave/status wave)
     :active-count (:wave/active-count wave)
     :completed-count (:wave/completed-count wave)
     :failed-count (:wave/failed-count wave)
     :started-at (:wave/started-at wave)
     :completed-at (:wave/completed-at wave)}))

(defn wave-in-progress?
  "Check if a wave is still executing."
  [wave-id]
  (when-let [status (get-wave-status wave-id)]
    (#{:pending :in-progress :running} (:status status))))

(defn wave-completed?
  "Check if a wave has completed (success or failure)."
  [wave-id]
  (when-let [status (get-wave-status wave-id)]
    (#{:completed :partial-failure :failed :cancelled} (:status status))))

(defn get-plan-status
  "Get current status of a change plan with all items."
  [plan-id]
  (when-let [plan (ds/get-plan plan-id)]
    (let [items (ds/get-plan-items plan-id)]
      {:plan-id plan-id
       :status (:change-plan/status plan)
       :preset (:change-plan/preset plan)
       :items (mapv (fn [item]
                      {:item-id (:change-item/id item)
                       :file (:change-item/file item)
                       :status (:change-item/status item)
                       :result (:change-item/result item)})
                    items)})))

(defn get-pending-items
  "Get pending items for a plan."
  [plan-id]
  (ds/get-pending-items plan-id))

(defn get-plan-items
  "Get all items for a plan."
  [plan-id]
  (ds/get-plan-items plan-id))

(defn get-failed-items
  "Get details of failed items for a wave."
  [wave-id]
  (when-let [status (get-wave-status wave-id)]
    (let [plan-status (get-plan-status (:plan-id status))]
      (->> (:items plan-status)
           (filter #(= :failed (:status %)))
           (mapv #(select-keys % [:item-id :file :result]))))))

(defn get-wave-summary
  "Get a summary of wave execution with success rate."
  [wave-id]
  (when-let [status (get-wave-status wave-id)]
    (let [completed (:completed-count status)
          failed (:failed-count status)
          total (+ completed failed)
          success-rate (if (pos? total) (/ (double completed) total) 1.0)]
      {:wave-id wave-id
       :status (:status status)
       :completed completed
       :failed failed
       :success-rate success-rate
       :failed-items (get-failed-items wave-id)})))

(defn format-wave-status
  "Format wave status for display."
  [wave-id]
  (when-let [summary (get-wave-summary wave-id)]
    (format "Wave %s: %s (%d/%d completed, %.1f%% success rate)"
            wave-id
            (name (:status summary))
            (:completed summary)
            (+ (:completed summary) (:failed summary))
            (* 100 (:success-rate summary)))))

(defn format-failed-items
  "Format failed items for display."
  [wave-id]
  (let [failed (get-failed-items wave-id)]
    (if (empty? failed)
      "No failed items"
      (->> failed
           (map #(format "  - %s: %s" (:file %) (:result %)))
           (cons "Failed items:")
           (clojure.string/join "\n")))))

(defn- get-validated-wave-sessions
  "Get validated wave sessions via requiring-resolve."
  []
  (rescue nil (when-let [list-fn (requiring-resolve
                        'hive-mcp.tools.swarm.validated-wave/list-validated-wave-sessions)]
      (list-fn))))

(defn get-active-waves
  "Get all currently active waves (both DataScript and validated sessions)."
  []
  (let [ds-active (->> (ds/get-all-waves)
                       (filter #(#{:pending :in-progress :running} (:wave/status %)))
                       (mapv (fn [w]
                               {:wave-id (:wave/id w)
                                :plan-id (:wave/plan w)
                                :status (:wave/status w)
                                :active-count (:wave/active-count w)
                                :started-at (:wave/started-at w)})))
        vw-active (->> (get-validated-wave-sessions)
                       (filter #(= :running (:status %)))
                       (mapv (fn [s]
                               {:wave-id (:session-id s)
                                :type :validated-wave
                                :status :running
                                :task-count (:task-count s)
                                :started-at (:started-at s)})))]
    (into ds-active vw-active)))

(defn get-recent-waves
  "Get recently completed waves, most recent first."
  [& [limit]]
  (let [n (or limit 10)
        ds-waves (->> (ds/get-all-waves)
                      (mapv (fn [w]
                              {:wave-id (:wave/id w)
                               :plan-id (:wave/plan w)
                               :status (:wave/status w)
                               :completed-count (:wave/completed-count w)
                               :failed-count (:wave/failed-count w)
                               :started-at (:wave/started-at w)
                               :completed-at (:wave/completed-at w)})))
        vw-waves (->> (get-validated-wave-sessions)
                      (mapv (fn [s]
                              {:wave-id (:session-id s)
                               :type :validated-wave
                               :status (:status s)
                               :task-count (:task-count s)
                               :started-at (:started-at s)
                               :completed-at (:completed-at s)})))]
    (->> (into ds-waves vw-waves)
         (sort-by (fn [w]
                    (let [t (:started-at w)]
                      (cond
                        (instance? java.util.Date t) (.getTime ^java.util.Date t)
                        (number? t) (long t)
                        :else 0)))
                  #(compare %2 %1))
         (take n)
         (vec))))
