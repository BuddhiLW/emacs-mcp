(ns hive-mcp.system.layer2
  "Integrant key implementations — Layer 2: transport (network servers).

   These keys manage external network services:
     - :hive/nrepl          — embedded nREPL for bb-mcp tool forwarding
     - :hive/websocket-mcp  — WebSocket MCP server (Claude Code IDE integration)
     - :hive/nats           — NATS client + backbone + bridge (event backbone)

   Each init-key wraps existing start functions from:
     - server/transport/nrepl.clj         → start-embedded-nrepl!
     - server/transport/websocket_mcp.clj → start-websocket-server!
     - server/init.clj       → init-nats!
     - nats/client.clj       → stop!

   halt-key! stops each server/connection and logs shutdown."
  (:require [integrant.core :as ig]
            [hive-mcp.server.transport.nrepl :as transport-nrepl]
            [hive-mcp.server.transport.websocket-mcp :as ws-mcp]
            [hive-mcp.server.init :as init]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; :hive/nrepl — Embedded nREPL server for bb-mcp tool forwarding
;; =============================================================================

(defmethod ig/init-key :hive/nrepl
  [_ config]
  (log/info ":hive/nrepl init — starting embedded nREPL server")
  (let [nrepl-server-atom (atom nil)
        server (try (transport-nrepl/start-embedded-nrepl! nrepl-server-atom)
                    (catch Throwable t
                      (log/warn t ":hive/nrepl start threw (non-fatal) — continuing with :failed status")
                      nil))]
    {:nrepl-server-atom nrepl-server-atom
     :server            server
     :port              (:port config)
     :status            (if server :running :failed)}))

(defmethod ig/halt-key! :hive/nrepl
  [_ state]
  (log/info ":hive/nrepl halt — stopping embedded nREPL server")
  (when-let [server-atom (:nrepl-server-atom state)]
    (when-let [server @server-atom]
      (result/rescue nil
        (require 'nrepl.server)
        (let [stop-server (resolve 'nrepl.server/stop-server)]
          (stop-server server)
          (reset! server-atom nil)
          (transport-nrepl/delete-port-file!)
          (log/info ":hive/nrepl stopped"))))))

;; =============================================================================
;; :hive/websocket-mcp — WebSocket MCP server (Claude Code IDE integration)
;; =============================================================================

(defmethod ig/init-key :hive/websocket-mcp
  [_ config]
  (log/info ":hive/websocket-mcp init — starting WebSocket MCP server" config)
  ;; The component config decides, so a profile that sets :enabled true is
  ;; obeyed. Status comes from what the start returned, never from the config
  ;; that asked for it.
  (let [result (result/rescue nil
                 (ws-mcp/start-websocket-server! config))]
    {:port    (:port result (:port config))
     :enabled (:enabled config)
     :result  result
     :status  (:status result :disabled)}))

(defmethod ig/halt-key! :hive/websocket-mcp
  [_ state]
  (when (= :running (:status state))
    (log/info ":hive/websocket-mcp halt — stopping WebSocket MCP server")
    (result/rescue nil
      (require 'hive-mcp.transport.websocket)
      (let [stop! (resolve 'hive-mcp.transport.websocket/stop-server!)]
        (stop!)
        (log/info ":hive/websocket-mcp stopped")))))

;; =============================================================================
;; :hive/nats — NATS client + backbone + bridge (event backbone)
;; =============================================================================

(defmethod ig/init-key :hive/nats
  [_ config]
  (log/info ":hive/nats init — initializing NATS backbone" config)
  ;; Check both Integrant config (system.edn) and runtime config (config.edn).
  ;; Runtime config takes precedence — user can enable NATS via config tool
  ;; without editing system.edn.
  (let [runtime-enabled? (result/rescue nil
                           (require 'hive-mcp.config.core)
                           (let [get-svc (resolve 'hive-mcp.config.core/get-service-value)]
                             (get-svc :nats :enabled :default false)))
        enabled? (or (:enabled config) runtime-enabled?)
        ok? (if enabled?
              (try (init/init-nats!) true
                   (catch Throwable t
                     (log/warn t ":hive/nats init-nats! threw (non-fatal) — continuing with :failed status")
                     false))
              true)]
    {:enabled enabled?
     :status  (cond
                (not enabled?) :disabled
                ok?            :running
                :else          :failed)}))

(defmethod ig/halt-key! :hive/nats
  [_ state]
  (when (= :running (:status state))
    (log/info ":hive/nats halt — disconnecting NATS client + backbone")
    ;; Stop callback listener first (subscriber)
    (result/rescue nil
      (when-let [stop-listener! (requiring-resolve 'hive-mcp.swarm.callback/stop-listener!)]
        (stop-listener!)))
    ;; Stop bridge subscriptions
    (result/rescue nil
      (when-let [stop-subs! (requiring-resolve 'hive-mcp.nats.bridge/stop-subscriptions!)]
        (stop-subs!)))
    ;; Clear backbone (reverts to NoopBackbone)
    (result/rescue nil
      (when-let [clear-bb! (requiring-resolve 'hive-mcp.protocols.event-backbone/clear-backbone!)]
        (clear-bb!)))
    ;; Disconnect NATS client (innermost — last to close)
    (result/rescue nil
      (require 'hive-mcp.nats.client)
      (let [stop! (resolve 'hive-mcp.nats.client/stop!)]
        (stop!)
        (log/info ":hive/nats disconnected")))))
