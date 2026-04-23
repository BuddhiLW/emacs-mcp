(ns hive-mcp.transport.olympus
  "Dedicated WebSocket server for Olympus UI on port 7911.

   ADR: Separate from Emacs channel (port 9999) because:
   1. Different protocol: keyword types (:agents) vs string ('hivemind-progress')
   2. Snapshot on connect: browser refresh needs full state
   3. Different consumers: React UI vs elisp

   Event Protocol (what Olympus expects):
   {:type :agents :data [{:id \"...\" :status :working ...}]}
   {:type :wave-update :wave-id \"...\" :task-idx 0 :status :completed}
   {:type :kg-snapshot :entries [...] :edges [...]}

   Lifecycle:
   - start! called from server.clj during MCP startup
   - stop! called on shutdown
   - Emits snapshot on each new client connection

   This namespace is a façade (convention 20260423151955-4faf4ffe)
   that re-exports the public API of the submodules:

   - transport.olympus.snapshots    — build-*-snapshot builders
   - transport.olympus.state-bridge — DataScript -> WS auto-push
   - transport.olympus.stream       — WebSocket clients + broadcast!/emit!
   - transport.olympus.http         — HTTP routes + start!/stop!/status

   External callers (server.clj, effects.clj, …) require this ns only."
  (:require [hive-mcp.transport.olympus.snapshots :as snap]
            [hive-mcp.transport.olympus.state-bridge :as state-bridge]
            [hive-mcp.transport.olympus.stream :as stream]
            [hive-mcp.transport.olympus.http :as http]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Re-exports - Snapshots
;; =============================================================================

(def build-full-snapshot snap/build-full-snapshot)

;; =============================================================================
;; Re-exports - DataScript state bridge
;; =============================================================================

(def wire-ds-state-bridge! state-bridge/wire-ds-state-bridge!)
(def stop-ds-state-bridge! state-bridge/stop-ds-state-bridge!)

;; =============================================================================
;; Re-exports - Event broadcasting / emitters
;; =============================================================================

(def broadcast!            stream/broadcast!)
(def emit!                 stream/emit!)
(def emit-agent-event!     stream/emit-agent-event!)
(def emit-wave-event!      stream/emit-wave-event!)
(def emit-hivemind-shout!  stream/emit-hivemind-shout!)
(def emit-kg-event!        stream/emit-kg-event!)
(def wire-hivemind-events! stream/wire-hivemind-events!)

;; =============================================================================
;; Re-exports - Server lifecycle
;; =============================================================================

(def start!  http/start!)
(def stop!   http/stop!)
(def status  http/status)

(comment
  ;; REPL testing
  (start!)
  (status)
  (broadcast! {:type :agents :data [{:id "test-1" :status :working}]})
  (stop!)

  ;; Test snapshot
  (build-full-snapshot)

  ;; DS State Bridge testing
  (wire-ds-state-bridge!)
  (status)
  (stop-ds-state-bridge!))
