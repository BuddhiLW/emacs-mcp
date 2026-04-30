(ns hive-mcp.agent.ling
  "Ling agent facade — Claude Code instances with tool chaining and multi-mode
   spawn. Refactor convention `20260423151955-4faf4ffe`: this namespace is now
   a re-export surface for the SLAP-split implementation modules.

   Implementation modules:
     - hive-mcp.agent.ling.lifecycle  (mode/strategy helpers, critical-op guard)
     - hive-mcp.agent.ling.spawn      (Ling record, ->ling, create-ling!, spawn pipeline)
     - hive-mcp.agent.ling.status     (get-ling, list-lings, get-ling-for-task, interrupt-ling!)

   Every public symbol previously exposed by this namespace remains callable
   via `ling/...` from all downstream callers (see hotspot #20 plan)."
  (:require [hive-mcp.agent.ling.lifecycle :as lifecycle]
            [hive-mcp.agent.ling.spawn :as spawn]
            [hive-mcp.agent.ling.status :as status]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; --- Construction / spawn ----------------------------------------------------
(def ->ling              spawn/->ling)
(def create-ling!        spawn/create-ling!)

;; --- Status / queries --------------------------------------------------------
(def get-ling            status/get-ling)
(def list-lings          status/list-lings)
(def get-ling-for-task   status/get-ling-for-task)
(def interrupt-ling!     status/interrupt-ling!)

;; --- Lifecycle helpers -------------------------------------------------------
(def resolve-effective-mode lifecycle/resolve-effective-mode)
(def with-critical-op       lifecycle/with-critical-op)
