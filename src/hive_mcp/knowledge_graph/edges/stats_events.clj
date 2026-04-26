(ns hive-mcp.knowledge-graph.edges.stats-events
  "hive-events wiring between KG edge CRUD and the edge-stats cache.

   This is the metrics side of the CRUD/metrics decoupling: `edges.clj`
   dispatches `:kg.edges/added`, `:kg.edges/removed`, and
   `:kg.edges/scope-migrated` after persistence, and the handlers
   registered here translate those events into delta calls on
   `edges.stats`. CRUD therefore has no compile-time dependency on the
   stats cache; new observers (telemetry, audit log, watchers) can
   subscribe to the same events without touching CRUD.

   Handlers are registered eagerly when this namespace is loaded so that
   the very first `add-edge!` after `(:require ...)` already finds a live
   listener. `register-handlers!` stays public for explicit re-registration
   (e.g. after `events/reset-all!` in tests)."
  (:require [hive-mcp.events.core :as events]
            [hive-mcp.knowledge-graph.edges.stats :as stats]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- on-edge-added [_coeffects [_ {:keys [relation scope]}]]
  (stats/apply-delta! relation scope 1)
  {})

(defn- on-edge-removed [_coeffects [_ {:keys [relation scope]}]]
  (stats/apply-delta! relation scope -1)
  {})

(defn- on-scope-migrated [_coeffects [_ {:keys [old-scope new-scope n]}]]
  (stats/migrate-scope! old-scope new-scope n)
  {})

(defn register-handlers!
  "Idempotent registration of the kg.edges/* event handlers."
  []
  (events/reg-event :kg.edges/added         [] on-edge-added)
  (events/reg-event :kg.edges/removed       [] on-edge-removed)
  (events/reg-event :kg.edges/scope-migrated [] on-scope-migrated)
  (log/debug "KG edge stats event handlers registered:"
             ":kg.edges/added :kg.edges/removed :kg.edges/scope-migrated"))

;; Eager registration on namespace load. edges.clj requires this ns for
;; its side-effect; the moment it loads, any subsequent dispatch from
;; CRUD will find a handler.
(register-handlers!)
