(ns hive-mcp.server.transport.websocket-mcp
  "WebSocket MCP server startup (Claude Code IDE integration).

   Single responsibility: start WebSocket MCP server if enabled via config."
  (:require [hive-mcp.config.core :as config]
            [hive-mcp.transport.websocket :as ws]
            [taoensso.timbre :as log]
            [hive-mcp.dns.result :as result]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn start-websocket-server!
  "Start the WebSocket MCP server (Claude Code IDE integration).

   Enabled when the system component config says so, or when the runtime
   config / HIVE_MCP_WEBSOCKET does. Port comes from the component config
   first, the runtime config second.

   Returns nil when not enabled, {:status :running :port n :server s} when
   the server is listening, {:status :failed :port n} when the start threw.
   Never throws."
  ([] (start-websocket-server! nil))
  ([component-config]
   (let [enabled? (or (:enabled component-config)
                      (config/get-service-value :websocket :enabled
                                                :env "HIVE_MCP_WEBSOCKET"
                                                :parse #(= "true" %)
                                                :default false))
         port     (or (:port component-config)
                      (config/get-service-value :websocket :port
                                                :env "HIVE_MCP_WS_PORT"
                                                :parse parse-long))
         project-dir (or (:project-dir (config/get-service-config :websocket))
                         (System/getenv "HIVE_MCP_PROJECT_DIR"))]
     (when enabled?
       (log/info "Starting WebSocket MCP server" {:port port :project-dir project-dir})
       (let [server (result/rescue ::failed
                                   (ws/start-server! {:port        port
                                                      :project-dir project-dir}))]
         (if (= ::failed server)
           (do (log/warn "WebSocket MCP server failed to start, NOT listening" {:port port})
               {:status :failed :port port})
           (do (log/info "WebSocket MCP server started" {:port port})
               {:status :running :port port :server server})))))))
