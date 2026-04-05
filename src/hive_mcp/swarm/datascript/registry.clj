(ns hive-mcp.swarm.datascript.registry
  "DataScript implementation of all ISP-segregated swarm protocols."
  (:require [datascript.core :as d]
            [hive-mcp.swarm.protocol :as proto]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.lings :as lings]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.swarm.datascript.coordination :as coordination]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord DataScriptRegistry []
  proto/ISwarmRegistry

  (add-slave! [_ slave-id opts]
    (lings/add-slave! slave-id opts))

  (get-slave [_ slave-id]
    (queries/get-slave slave-id))

  (update-slave! [_ slave-id updates]
    (lings/update-slave! slave-id updates))

  (remove-slave! [_ slave-id]
    (lings/remove-slave! slave-id))

  (get-all-slaves [_]
    (queries/get-all-slaves))

  (get-slaves-by-status [_ status]
    (queries/get-slaves-by-status status))

  (get-slaves-by-project [_ project-id]
    (queries/get-slaves-by-project project-id))

  (add-task! [_ task-id slave-id opts]
    (lings/add-task! task-id slave-id opts))

  (get-task [_ task-id]
    (queries/get-task task-id))

  (update-task! [_ task-id updates]
    (lings/update-task! task-id updates))

  (get-tasks-for-slave [_ slave-id]
    (queries/get-tasks-for-slave slave-id))

  (get-tasks-for-slave [_ slave-id status]
    (queries/get-tasks-for-slave slave-id status)))

(defrecord DataScriptClaimStore []
  proto/IClaimStore

  (-claim-file! [_ file-path slave-id]
    (lings/claim-file! file-path slave-id))

  (-claim-file! [_ file-path slave-id opts]
    (lings/claim-file! file-path slave-id opts))

  (-release-claim! [_ file-path]
    (lings/release-claim! file-path))

  (-release-claims-for-slave! [_ slave-id]
    (lings/release-claims-for-slave! slave-id))

  (-release-claims-for-task! [_ task-id]
    (lings/release-claims-for-task! task-id))

  (-get-claims-for-file [_ file-path]
    (queries/get-claims-for-file file-path))

  (-get-all-claims [_]
    (queries/get-all-claims))

  (-has-conflict? [_ file-path requesting-slave]
    (queries/has-conflict? file-path requesting-slave))

  (-check-file-conflicts [_ requesting-slave files]
    (queries/check-file-conflicts requesting-slave files))

  (-refresh-claim! [_ file-path]
    (lings/refresh-claim! file-path))

  (-cleanup-stale-claims! [_]
    (lings/cleanup-stale-claims!))

  (-cleanup-stale-claims! [_ threshold-ms]
    (lings/cleanup-stale-claims! threshold-ms))

  (-archive-claim-to-history! [_ file-path opts]
    (lings/archive-claim-to-history! file-path opts))

  (-get-recent-claim-history [_ opts]
    (apply queries/get-recent-claim-history (mapcat identity opts)))

  (-add-to-wait-queue! [_ ling-id file-path]
    (lings/add-to-wait-queue! ling-id file-path)))

(defrecord DataScriptCriticalOps []
  proto/ICriticalOps

  (-enter-critical-op! [_ slave-id op-type]
    (lings/enter-critical-op! slave-id op-type))

  (-exit-critical-op! [_ slave-id op-type]
    (lings/exit-critical-op! slave-id op-type))

  (-get-critical-ops [_ slave-id]
    (lings/get-critical-ops slave-id))

  (-can-kill? [_ slave-id]
    (lings/can-kill? slave-id)))

(defrecord DataScriptCoordination []
  proto/ICoordination

  (-add-wrap-notification! [_ wrap-id opts]
    (coordination/add-wrap-notification! wrap-id opts))

  (-get-unprocessed-wraps [_]
    (coordination/get-unprocessed-wraps))

  (-get-unprocessed-wraps-for-project [_ project-id]
    (coordination/get-unprocessed-wraps-for-project project-id))

  (-get-unprocessed-wraps-for-hierarchy [_ project-id-prefix]
    (coordination/get-unprocessed-wraps-for-hierarchy project-id-prefix))

  (-mark-wrap-processed! [_ wrap-id]
    (coordination/mark-wrap-processed! wrap-id))

  (-create-plan! [_ tasks preset]
    (coordination/create-plan! tasks preset))

  (-get-plan [_ plan-id]
    (coordination/get-plan plan-id))

  (-get-pending-items [_ plan-id]
    (coordination/get-pending-items plan-id))

  (-get-plan-items [_ plan-id]
    (coordination/get-plan-items plan-id))

  (-update-item-status! [_ item-id status]
    (coordination/update-item-status! item-id status))

  (-update-item-status! [_ item-id status opts]
    (coordination/update-item-status! item-id status opts))

  (-update-plan-status! [_ plan-id status]
    (coordination/update-plan-status! plan-id status))

  (-create-wave! [_ plan-id]
    (coordination/create-wave! plan-id))

  (-create-wave! [_ plan-id opts]
    (coordination/create-wave! plan-id opts))

  (-get-wave [_ wave-id]
    (coordination/get-wave wave-id))

  (-get-all-waves [_]
    (coordination/get-all-waves))

  (-update-wave-counts! [_ wave-id delta]
    (coordination/update-wave-counts! wave-id delta))

  (-complete-wave! [_ wave-id status]
    (coordination/complete-wave! wave-id status))

  (-register-coordinator! [_ coordinator-id opts]
    (coordination/register-coordinator! coordinator-id opts))

  (-update-heartbeat! [_ coordinator-id]
    (coordination/update-heartbeat! coordinator-id))

  (-get-coordinator [_ coordinator-id]
    (coordination/get-coordinator coordinator-id))

  (-get-all-coordinators [_]
    (coordination/get-all-coordinators))

  (-get-coordinators-for-project [_ project]
    (coordination/get-coordinators-for-project project))

  (-mark-coordinator-terminated! [_ coordinator-id]
    (coordination/mark-coordinator-terminated! coordinator-id))

  (-cleanup-stale-coordinators! [_]
    (coordination/cleanup-stale-coordinators!))

  (-cleanup-stale-coordinators! [_ opts]
    (coordination/cleanup-stale-coordinators! opts))

  (-remove-coordinator! [_ coordinator-id]
    (coordination/remove-coordinator! coordinator-id))

  (-register-completed-task! [_ task-id opts]
    (coordination/register-completed-task! task-id opts))

  (-get-completed-tasks-this-session [_]
    (coordination/get-completed-tasks-this-session))

  (-get-completed-tasks-this-session [_ opts]
    (apply coordination/get-completed-tasks-this-session (mapcat identity opts)))

  (-clear-completed-tasks! [_]
    (coordination/clear-completed-tasks!))

  (-register-kanban-movement! [_ opts]
    (coordination/register-kanban-movement! opts))

  (-get-kanban-movements-this-session [_]
    (coordination/get-kanban-movements-this-session))

  (-get-kanban-movements-this-session [_ opts]
    (apply coordination/get-kanban-movements-this-session (mapcat identity opts)))

  (-clear-kanban-movements! [_]
    (coordination/clear-kanban-movements!)))

(defrecord DataScriptDb []
  proto/ISwarmDb

  (-transact! [_ tx-data]
    (d/transact! (conn/ensure-conn) tx-data))

  (-current-db [_]
    @(conn/ensure-conn))

  (-listen! [_ key callback]
    (d/listen! (conn/ensure-conn) key callback)
    key)

  (-unlisten! [_ key]
    (d/unlisten! (conn/ensure-conn) key))

  (-db-stats [_]
    (queries/db-stats))

  (-reset-db! [_]
    (conn/reset-conn!))

  (-close! [_]
    (conn/reset-conn!)))

;;; =============================================================================
;;; Default Instances
;;; =============================================================================

(defonce default-registry (->DataScriptRegistry))
(defonce default-claim-store (->DataScriptClaimStore))
(defonce default-critical-ops (->DataScriptCriticalOps))
(defonce default-coordination (->DataScriptCoordination))
(defonce default-db (->DataScriptDb))

(defn get-default-registry [] default-registry)
(defn get-default-claim-store [] default-claim-store)
(defn get-default-critical-ops [] default-critical-ops)
(defn get-default-coordination [] default-coordination)
(defn get-default-db [] default-db)
