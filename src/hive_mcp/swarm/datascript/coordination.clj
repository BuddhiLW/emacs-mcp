(ns hive-mcp.swarm.datascript.coordination
  "Coordination layer facade for swarm orchestration.

   Sub-namespaces (split 2026-04-30, refactor/coordination-srp):
     coordination.wrap-queue       — wrap notifications (crystal convergence)
     coordination.plan             — change-plan + change-item CRUD (dispatch_drone_wave)
     coordination.wave             — wave execution lifecycle
     coordination.coordinator      — coordinator CRUD + lifecycle marks
     coordination.cleanup          — stale coordinator + stale claim sweeps
     coordination.session-registry — completed-task + kanban-movement registries
     coordination.config           — hive-di defconfig CoordinationConfig (DIP)

   This ns re-exports all public fns from the sub-namespaces so existing callers
   using `[... :as coordination]` keep working unchanged. New code should require
   the focused sub-ns directly (ISP).

   DDD: Application Service layer for multi-agent coordination."
  (:require [hive-mcp.swarm.datascript.coordination.wrap-queue :as wrap-queue]
            [hive-mcp.swarm.datascript.coordination.plan :as plan]
            [hive-mcp.swarm.datascript.coordination.wave :as wave]
            [hive-mcp.swarm.datascript.coordination.coordinator :as coordinator]
            [hive-mcp.swarm.datascript.coordination.cleanup :as cleanup]
            [hive-mcp.swarm.datascript.coordination.session-registry :as session-registry]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Wrap Queue (Crystal Convergence)
;; =============================================================================

(def add-wrap-notification!              wrap-queue/add-wrap-notification!)
(def get-unprocessed-wraps               wrap-queue/get-unprocessed-wraps)
(def get-unprocessed-wraps-for-project   wrap-queue/get-unprocessed-wraps-for-project)
(def get-unprocessed-wraps-for-hierarchy wrap-queue/get-unprocessed-wraps-for-hierarchy)
(def mark-wrap-processed!                wrap-queue/mark-wrap-processed!)

;; =============================================================================
;; Change Plan (dispatch_drone_wave)
;; =============================================================================

(def create-plan!         plan/create-plan!)
(def get-plan             plan/get-plan)
(def get-pending-items    plan/get-pending-items)
(def get-plan-items       plan/get-plan-items)
(def update-item-status!  plan/update-item-status!)
(def update-plan-status!  plan/update-plan-status!)

;; =============================================================================
;; Wave Execution
;; =============================================================================

(def create-wave!        wave/create-wave!)
(def get-wave            wave/get-wave)
(def get-all-waves       wave/get-all-waves)
(def update-wave-counts! wave/update-wave-counts!)
(def complete-wave!      wave/complete-wave!)

;; =============================================================================
;; Coordinator Lifecycle
;; =============================================================================

(def register-coordinator!         coordinator/register-coordinator!)
(def update-heartbeat!             coordinator/update-heartbeat!)
(def get-coordinator               coordinator/get-coordinator)
(def get-all-coordinators          coordinator/get-all-coordinators)
(def get-coordinators-by-status    coordinator/get-coordinators-by-status)
(def get-coordinators-for-project  coordinator/get-coordinators-for-project)
(def mark-coordinator-terminated!  coordinator/mark-coordinator-terminated!)
(def mark-coordinator-stale!       coordinator/mark-coordinator-stale!)
(def remove-coordinator!           coordinator/remove-coordinator!)

;; =============================================================================
;; Cleanup (consumes CoordinationConfig thresholds)
;; =============================================================================

(def cleanup-stale-coordinators! cleanup/cleanup-stale-coordinators!)
(def cleanup-stale-claims!       cleanup/cleanup-stale-claims!)

;; =============================================================================
;; Session Registries (completed-task + kanban-movement)
;; =============================================================================

(def register-completed-task!          session-registry/register-completed-task!)
(def get-completed-task                session-registry/get-completed-task)
(def get-completed-tasks-this-session  session-registry/get-completed-tasks-this-session)
(def clear-completed-tasks!            session-registry/clear-completed-tasks!)
(def register-kanban-movement!         session-registry/register-kanban-movement!)
(def get-kanban-movements-this-session session-registry/get-kanban-movements-this-session)
(def clear-kanban-movements!           session-registry/clear-kanban-movements!)
