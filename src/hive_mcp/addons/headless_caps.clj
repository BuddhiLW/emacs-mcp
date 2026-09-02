(ns hive-mcp.addons.headless-caps
  "Optional capability protocols for headless backends (ISP pattern).

   Small protocols that backends opt-in to via `satisfies?`. Core dispatch
   checks protocol satisfaction before calling: unsatisfied protocols simply
   do not execute (NoOp by omission).

   Every name here is a `def` ALIAS of hive-spi.addon.headless-caps, never a
   `defprotocol`. A second defprotocol mints a DISTINCT protocol, so a backend
   implementing the published contract would fail `satisfies?` here silently,
   and the only way for it to be recognised would be to name a hive-mcp symbol
   in its `reify`, which couples the backend to the host at COMPILE time. Same
   rule, and the same reason, as hive-mcp.addons.protocol and
   hive-mcp.addons.terminal.

   Usage in hive-mcp core (e.g. forge-strike hook injection):
     (when (satisfies? IHookable backend)
       (headless-caps/register-hooks! backend ling-id {...}))

   Protocols:
   - IHookable         Pre/post tool-use hook injection
   - ICheckpointable   Session checkpoint/rewind
   - ISubagentHost     Native subagent definitions
   - IBudgetGuardable  Per-session cost budgeting

   See also:
   - hive-spi.addon.headless-caps -- the published originals
   - hive-mcp.addons.headless  -- IHeadlessBackend (required protocol)
   - hive-mcp.agent.headless-capability -- HeadlessCapability ADT"
  (:require [hive-spi.addon.headless-caps :as caps]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IHookable -- re-export from hive-spi.addon.headless-caps
;; =============================================================================

(def IHookable caps/IHookable)

(def register-hooks! caps/register-hooks!)
(def active-hooks    caps/active-hooks)

;; =============================================================================
;; ICheckpointable -- re-export
;; =============================================================================

(def ICheckpointable caps/ICheckpointable)

(def checkpoint! caps/checkpoint!)
(def rewind!     caps/rewind!)

;; =============================================================================
;; ISubagentHost -- re-export
;; =============================================================================

(def ISubagentHost caps/ISubagentHost)

(def register-subagents! caps/register-subagents!)
(def list-subagents      caps/list-subagents)

;; =============================================================================
;; IBudgetGuardable -- re-export
;; =============================================================================

(def IBudgetGuardable caps/IBudgetGuardable)

(def set-budget!   caps/set-budget!)
(def budget-status caps/budget-status)

;; =============================================================================
;; Capability registry -- re-export
;; =============================================================================

(def capability-protocols caps/capability-protocols)

(def provided-capabilities caps/provided-capabilities)
