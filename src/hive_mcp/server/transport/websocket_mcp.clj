(ns hive-mcp.server.transport.websocket-mcp
  "WebSocket MCP server startup (Claude Code IDE integration).

   Single responsibility: start WebSocket MCP server if enabled via config."
  (:require [hive-mcp.config.core :as config]
            [hive-mcp.transport.websocket :as ws]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn start-websocket-server!
  "Start WebSocket MCP server if enabled via config or HIVE_MCP_WEBSOCKET=true."
  []
  (when (config/get-service-value :websocket :enabled
                                  :env "HIVE_MCP_WEBSOCKET"
                                  :parse #(= "true" %)
                                  :default false)
    (let [port (config/get-service-value :websocket :port
                                         :env "HIVE_MCP_WS_PORT"
                                         :parse parse-long)
          project-dir (or (:project-dir (config/get-service-config :websocket))
                          (System/getenv "HIVE_MCP_PROJECT_DIR"))]
      (log/info "Starting WebSocket MCP server" {:port port :project-dir project-dir})
      (ws/start-server! {:port port
                         :project-dir project-dir}))))
