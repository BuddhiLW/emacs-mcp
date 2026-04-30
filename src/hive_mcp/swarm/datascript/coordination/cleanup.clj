(ns hive-mcp.swarm.datascript.coordination.cleanup
  "Stale-detection sweeps for coordinators and claims.

   Pure side-effect cleanup ops. Owns the staleness policy via CoordinationConfig
   (env-overridable thresholds). Sibling to coordination.coordinator (which owns
   coordinator CRUD); kept separate per SRP — these have a different reason to
   change (threshold tuning, sweep cadence) than the CRUD ops.

   DDD: Application Service layer for swarm-coordination housekeeping."
  (:require [datascript.core :as d]
            [taoensso.timbre :as log]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.coordination.config :as config]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(declare cleanup-stale-coordinators! cleanup-stale-claims!)

(defn cleanup-stale-coordinators!
  "Find and mark coordinators as stale if their heartbeat is too old.
   Returns coordinators that were marked stale.

   Arguments:
     threshold-ms - Optional custom threshold in ms
                    (default: :coordinator-stale-ms from CoordinationConfig)

   Returns:
     Seq of coordinator-ids that were marked stale"
  [& [{:keys [threshold-ms]
       :or   {threshold-ms (:coordinator-stale-ms (config/coordination-config))}}]]
  (let [c (conn/ensure-conn)
        db @c
        cutoff-ms (- (System/currentTimeMillis) threshold-ms)
        active-coords (d/q '[:find ?e ?id ?hb
                             :where
                             [?e :coordinator/id ?id]
                             [?e :coordinator/status :active]
                             [?e :coordinator/heartbeat-at ?hb]]
                           db)
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
     threshold-ms - Age threshold in milliseconds
                    (default: :claim-stale-ms from CoordinationConfig)

   Returns:
     Count of claims removed"
  [& [{:keys [threshold-ms]
       :or   {threshold-ms (:claim-stale-ms (config/coordination-config))}}]]
  (let [c (conn/ensure-conn)
        db @c
        cutoff-ms (- (System/currentTimeMillis) threshold-ms)
        stale-claims (d/q '[:find ?e ?file ?created
                            :where
                            [?e :claim/file ?file]
                            [?e :claim/created-at ?created]]
                          db)
        stale-eids (->> stale-claims
                        (filter (fn [[_ _ created]]
                                  (< (.getTime created) cutoff-ms)))
                        (map first))]
    (when (seq stale-eids)
      (log/warn "Cleaning up" (count stale-eids) "stale claims")
      (doseq [eid stale-eids]
        (d/transact! c [[:db/retractEntity eid]])))
    (count stale-eids)))
