(ns hive-mcp.system.layer4
  "Integrant key implementations — Layer 4: channels, sync, workflow.

   These keys manage channel servers, event bridging, and workflow:
     - :hive/ws-channel      — WebSocket channel with auto-healing monitor
     - :hive/olympus         — Olympus WebSocket server (Web UI)
     - :hive/a2a-gateway     — A2A JSON-RPC gateway (opt-in)
     - :hive/legacy-channel  — Legacy TCP channel (deprecated, backward compat)
     - :hive/channel-bridge  — Channel events → hive-events dispatch bridge
     - :hive/swarm-sync      — Channel events → logic database sync
     - :hive/workflow-engine — FSM workflow registry + IWorkflowEngine wiring

   Each init-key wraps existing start functions from:
     - server/transport/ws_channel    → start-ws-channel-with-healing!
     - server/transport/olympus_ws    → start-olympus-ws!
     - server/transport/a2a_gateway   → start-a2a-gateway!
     - server/transport/legacy        → start-legacy-channel!
     - server/init.clj                → init-channel-bridge!, start-swarm-sync!,
                                         init-workflow-engine!

   halt-key! stops each component and logs shutdown."
  (:require [integrant.core :as ig]
            [hive-mcp.server.transport.ws-channel :as ws-ch]
            [hive-mcp.server.transport.olympus-ws :as oly-ws]
            [hive-mcp.server.transport.a2a-gateway :as a2a-gw]
            [hive-mcp.server.transport.legacy :as legacy-ch]
            [hive-mcp.server.init :as init]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; :hive/ws-channel — WebSocket channel with auto-healing monitor
;; =============================================================================

(defmethod ig/init-key :hive/ws-channel
  [_ config]
  (log/info ":hive/ws-channel init — starting WebSocket channel with auto-heal" config)
  (let [monitor (atom nil)]
    (result/rescue nil
      (ws-ch/start-ws-channel-with-healing! monitor))
    {:monitor monitor
     :port    (:port config 9999)
     :status  :running}))

(defmethod ig/halt-key! :hive/ws-channel
  [_ state]
  (log/info ":hive/ws-channel halt — stopping WebSocket channel + monitor")
  ;; Stop monitor go-loop first
  (when-let [monitor-ch @(:monitor state)]
    (result/rescue nil
      (require 'clojure.core.async)
      (let [close! (resolve 'clojure.core.async/close!)]
        (close! monitor-ch)
        (reset! (:monitor state) nil))))
  ;; Stop ws-channel server
  (result/rescue nil
    (when-let [stop! (requiring-resolve 'hive-mcp.channel.websocket/stop!)]
      (stop!)
      (log/info ":hive/ws-channel stopped"))))

;; =============================================================================
;; :hive/olympus — Olympus WebSocket server (Web UI)
;; =============================================================================

(defmethod ig/init-key :hive/olympus
  [_ config]
  (log/info ":hive/olympus init — starting Olympus WebSocket server")
  (let [result (result/rescue nil
                 (oly-ws/start-olympus-ws!))]
    {:status (if result :running :failed)}))

(defmethod ig/halt-key! :hive/olympus
  [_ state]
  (when (= :running (:status state))
    (log/info ":hive/olympus halt — stopping Olympus WebSocket server")
    (result/rescue nil
      (when-let [stop! (requiring-resolve 'hive-mcp.transport.olympus/stop!)]
        (stop!)
        (log/info ":hive/olympus stopped")))))

;; =============================================================================
;; :hive/a2a-gateway — A2A JSON-RPC gateway (opt-in)
;; =============================================================================

(defmethod ig/init-key :hive/a2a-gateway
  [_ config]
  (log/info ":hive/a2a-gateway init — starting A2A gateway" config)
  (let [enabled? (:enabled config)]
    (when enabled?
      (a2a-gw/start-a2a-gateway!))
    {:enabled enabled?
     :status  (if enabled? :running :disabled)}))

(defmethod ig/halt-key! :hive/a2a-gateway
  [_ state]
  (when (= :running (:status state))
    (log/info ":hive/a2a-gateway halt — stopping A2A gateway")
    (result/rescue nil
      (when-let [stop! (requiring-resolve 'hive-mcp.transport.a2a/stop!)]
        (stop!)
        (log/info ":hive/a2a-gateway stopped")))))

;; =============================================================================
;; :hive/legacy-channel — Legacy TCP channel (deprecated, backward compat)
;; =============================================================================

(defmethod ig/init-key :hive/legacy-channel
  [_ config]
  (log/info ":hive/legacy-channel init — starting legacy TCP channel" config)
  (result/rescue nil
    (legacy-ch/start-legacy-channel!))
  {:port   (:port config 9998)
   :status :running})

(defmethod ig/halt-key! :hive/legacy-channel
  [_ state]
  (log/info ":hive/legacy-channel halt — stopping legacy TCP channel")
  (result/rescue nil
    (when-let [stop! (requiring-resolve 'hive-mcp.channel.core/stop-server!)]
      (stop!)
      (log/info ":hive/legacy-channel stopped"))))

;; =============================================================================
;; :hive/channel-bridge — Channel events → hive-events dispatch bridge
;; =============================================================================

(defmethod ig/init-key :hive/channel-bridge
  [_ config]
  (log/info ":hive/channel-bridge init — wiring channel events to hive-events" config)
  (result/rescue nil
    (init/init-channel-bridge!))
  {:status :running})

(defmethod ig/halt-key! :hive/channel-bridge
  [_ state]
  (when (= :running (:status state))
    (log/info ":hive/channel-bridge halt — shutting down channel bridge")
    (result/rescue nil
      (when-let [shutdown! (requiring-resolve 'hive-mcp.events.channel-bridge/shutdown!)]
        (shutdown!)
        (log/info ":hive/channel-bridge stopped")))))

;; =============================================================================
;; :hive/swarm-sync — Channel events → logic database sync
;; =============================================================================

(defmethod ig/init-key :hive/swarm-sync
  [_ config]
  (log/info ":hive/swarm-sync init — bridging channel events to logic database" config)
  (result/rescue nil
    (init/start-swarm-sync!))
  {:status :running})

(defmethod ig/halt-key! :hive/swarm-sync
  [_ state]
  (when (= :running (:status state))
    (log/info ":hive/swarm-sync halt — stopping swarm sync")
    (result/rescue nil
      (when-let [stop! (requiring-resolve 'hive-mcp.swarm.sync/stop-sync!)]
        (stop!)
        (log/info ":hive/swarm-sync stopped")))))

;; =============================================================================
;; :hive/workflow-engine — FSM workflow registry + IWorkflowEngine wiring
;; =============================================================================

(defmethod ig/init-key :hive/workflow-engine
  [_ config]
  (log/info ":hive/workflow-engine init — initializing FSM workflow engine" config)
  (result/rescue nil
    (init/init-workflow-engine!))
  {:status :running})

(defmethod ig/halt-key! :hive/workflow-engine
  [_ state]
  (when (= :running (:status state))
    (log/info ":hive/workflow-engine halt — resetting to NoopWorkflowEngine")
    (result/rescue nil
      (when-let [set-engine! (requiring-resolve 'hive-mcp.protocols.workflow/set-workflow-engine!)]
        (let [noop-ctor (requiring-resolve 'hive-mcp.protocols.workflow/->NoopWorkflowEngine)]
          (set-engine! (noop-ctor))
          (log/info ":hive/workflow-engine reset to noop"))))))
