(ns hive-mcp.server.transport.ws-channel
  "WebSocket channel with auto-healing monitor.

   Single responsibility: start WebSocket channel server and monitor loop
   that restarts the server if it dies."
  (:require [hive-mcp.config.core :as config]
            [hive-mcp.dns.result :as result]
            [hive-mcp.channel.websocket :as ws-channel]
            [clojure.core.async :as async]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn start-ws-channel-with-healing!
  "Start WebSocket channel server with auto-healing.

   Runs a background async loop that monitors and restarts if needed.

   Parameters:
     ws-channel-monitor - atom to store the monitoring go-loop channel"
  [ws-channel-monitor]
  (let [port (config/get-service-value :ws-channel :port
                                       :env "HIVE_MCP_WS_CHANNEL_PORT"
                                       :parse parse-long
                                       :default 9999)
        check-interval-ms 30000] ; Check every 30 seconds
    ;; Start initial server
    (result/rescue nil
                   (ws-channel/start! {:port port})
                   (log/info "WebSocket channel server started on port" port))
    ;; Start monitoring loop
    (when-not @ws-channel-monitor
      (reset! ws-channel-monitor
              (async/go-loop []
                (async/<! (async/timeout check-interval-ms))
                (when-not (ws-channel/connected?)
                  (log/debug "WebSocket channel: no clients, server healthy"))
                  ;; Server running but no clients is fine

                (when-not (:running? (ws-channel/status))
                  (log/warn "WebSocket channel server died, attempting restart...")
                  (result/rescue nil
                                 (ws-channel/start! {:port port})
                                 (log/info "WebSocket channel server restarted on port" port)))
                (recur)))
      (log/info "WebSocket channel auto-heal monitor started"))))
