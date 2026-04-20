(ns hive-mcp.server.transport
  "Transport facade — re-exports start functions from SRP sub-namespaces.

   Each transport concern lives in its own namespace:
   - transport.nrepl          → start-embedded-nrepl!
   - transport.websocket-mcp  → start-websocket-server!
   - transport.ws-channel     → start-ws-channel-with-healing!
   - transport.olympus-ws     → start-olympus-ws!
   - transport.a2a-gateway    → start-a2a-gateway!
   - transport.legacy         → start-legacy-channel!

   Callers should migrate to direct sub-namespace requires over time."
  (:require [hive-mcp.server.transport.nrepl :as transport-nrepl]
            [hive-mcp.server.transport.websocket-mcp :as ws-mcp]
            [hive-mcp.server.transport.ws-channel :as ws-ch]
            [hive-mcp.server.transport.olympus-ws :as oly-ws]
            [hive-mcp.server.transport.a2a-gateway :as a2a-gw]
            [hive-mcp.server.transport.legacy :as legacy-ch]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; nREPL Server (delegated to hive-mcp.server.transport.nrepl)
;; =============================================================================

(defn start-embedded-nrepl!
  "Start an embedded nREPL server for bb-mcp tool forwarding.
   Delegates to hive-mcp.server.transport.nrepl."
  [nrepl-server-atom]
  (transport-nrepl/start-embedded-nrepl! nrepl-server-atom))

;; =============================================================================
;; WebSocket MCP Server (delegated to hive-mcp.server.transport.websocket-mcp)
;; =============================================================================

(defn start-websocket-server!
  "Start WebSocket MCP server if enabled via config.
   Delegates to hive-mcp.server.transport.websocket-mcp."
  []
  (ws-mcp/start-websocket-server!))

;; =============================================================================
;; WebSocket Channel (delegated to hive-mcp.server.transport.ws-channel)
;; =============================================================================

(defn start-ws-channel-with-healing!
  "Start WebSocket channel server with auto-healing.
   Delegates to hive-mcp.server.transport.ws-channel."
  [ws-channel-monitor]
  (ws-ch/start-ws-channel-with-healing! ws-channel-monitor))

;; =============================================================================
;; Olympus WebSocket (delegated to hive-mcp.server.transport.olympus-ws)
;; =============================================================================

(defn start-olympus-ws!
  "Start Olympus WebSocket server for Web UI.
   Delegates to hive-mcp.server.transport.olympus-ws."
  []
  (oly-ws/start-olympus-ws!))

;; =============================================================================
;; A2A Gateway (delegated to hive-mcp.server.transport.a2a-gateway)
;; =============================================================================

(defn start-a2a-gateway!
  "Start A2A JSON-RPC gateway for external agent interoperability.
   Delegates to hive-mcp.server.transport.a2a-gateway."
  []
  (a2a-gw/start-a2a-gateway!))

;; =============================================================================
;; Legacy Channel (delegated to hive-mcp.server.transport.legacy)
;; =============================================================================

(defn start-legacy-channel!
  "Start legacy TCP channel server (deprecated).
   Delegates to hive-mcp.server.transport.legacy."
  []
  (legacy-ch/start-legacy-channel!))
