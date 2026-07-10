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

;;; =============================================================================
;;; Terminal-entity sweep — retract cold rows already durable in the swarm ledger
;;; =============================================================================

(def default-ledger-retain-ms
  "Grace window a terminal entity stays queryable in the hot DataScript store
   after completion before retraction. Its durable record already lives in the
   swarm ledger, so retraction loses nothing."
  3600000)

(def ^:private terminal-task-statuses #{:completed :error :timeout})
(def ^:private terminal-wave-statuses #{:completed :partial-failure :failed :cancelled})

(defn- retract-old!
  "Retract entities whose Date-valued timestamp precedes cutoff-ms.
   rows: seq of [eid <java.util.Date>]. Returns count retracted."
  [c cutoff-ms rows]
  (let [old (->> rows
                 (filter (fn [[_ ts]] (and ts (< (.getTime ^java.util.Date ts) cutoff-ms))))
                 (map first))]
    (doseq [eid old]
      (d/transact! c [[:db/retractEntity eid]]))
    (count old)))

(defn cleanup-terminal-tasks!
  "Retract completed/failed tasks older than the retain window. Live tasks
   (no :task/completed-at, or non-terminal status) are never touched. The
   :task/completed-at gate is set only by complete-task!/fail-task!, which
   append to the ledger — so every retracted task is already durable.
   Returns count retracted."
  [& [{:keys [threshold-ms] :or {threshold-ms default-ledger-retain-ms}}]]
  (let [c (conn/ensure-conn)
        db @c
        cutoff (- (System/currentTimeMillis) threshold-ms)
        rows (->> (d/q '[:find ?e ?status ?done
                         :where
                         [?e :task/id _]
                         [?e :task/status ?status]
                         [?e :task/completed-at ?done]]
                       db)
                  (filter (fn [[_ status _]] (contains? terminal-task-statuses status)))
                  (map (fn [[eid _ done]] [eid done])))
        n (retract-old! c cutoff rows)]
    (when (pos? n) (log/debug "Ledger sweep: retracted" n "terminal tasks"))
    n))

(defn cleanup-completed-waves!
  "Retract terminal waves older than the retain window. Running waves are kept.
   The :wave/completed-at gate is set only by complete-wave! (which appends to
   the ledger). Returns count retracted."
  [& [{:keys [threshold-ms] :or {threshold-ms default-ledger-retain-ms}}]]
  (let [c (conn/ensure-conn)
        db @c
        cutoff (- (System/currentTimeMillis) threshold-ms)
        rows (->> (d/q '[:find ?e ?status ?done
                         :where
                         [?e :wave/id _]
                         [?e :wave/status ?status]
                         [?e :wave/completed-at ?done]]
                       db)
                  (filter (fn [[_ status _]] (contains? terminal-wave-statuses status)))
                  (map (fn [[eid _ done]] [eid done])))
        n (retract-old! c cutoff rows)]
    (when (pos? n) (log/debug "Ledger sweep: retracted" n "completed waves"))
    n))

(defn cleanup-old-claim-history!
  "Retract claim-history rows older than the retain window. Every row is durable
   in the ledger (archive-claim-to-history! appends). Returns count retracted."
  [& [{:keys [threshold-ms] :or {threshold-ms default-ledger-retain-ms}}]]
  (let [c (conn/ensure-conn)
        db @c
        cutoff (- (System/currentTimeMillis) threshold-ms)
        rows (d/q '[:find ?e ?rel
                    :where
                    [?e :claim-history/id _]
                    [?e :claim-history/released-at ?rel]]
                  db)
        n (retract-old! c cutoff rows)]
    (when (pos? n) (log/debug "Ledger sweep: retracted" n "old claim-history rows"))
    n))

(defn sweep-ledger-cold!
  "Retract terminal/cold swarm entities from the hot DataScript store once they
   age past the retain window; their durable record lives in the swarm ledger.
   Terminal-state guarded — live entities are never swept. Returns a summary map."
  [& [opts]]
  {:tasks         (cleanup-terminal-tasks! opts)
   :waves         (cleanup-completed-waves! opts)
   :claim-history (cleanup-old-claim-history! opts)})
