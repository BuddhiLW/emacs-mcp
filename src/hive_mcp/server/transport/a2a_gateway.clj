(ns hive-mcp.server.transport.a2a-gateway
  "A2A JSON-RPC gateway for external agent interoperability.

   Single responsibility: start A2A gateway if enabled via config."
  (:require [hive-mcp.config.core :as config]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn start-a2a-gateway!
  "Start A2A JSON-RPC gateway for external agent interoperability.
   Opt-in via config :a2a :enabled or HIVE_MCP_A2A_ENABLED=true."
  []
  (when (config/get-service-value :a2a :enabled
                                  :env "HIVE_MCP_A2A_ENABLED"
                                  :parse #(= "true" %)
                                  :default false)
    (result/rescue nil
                   (require 'hive-mcp.transport.a2a)
                   ((resolve 'hive-mcp.transport.a2a/start!)
                    {:port (config/get-service-value :a2a :port
                                                     :env "HIVE_MCP_A2A_PORT"
                                                     :parse parse-long
                                                     :default 7912)
                     :api-key (config/get-service-value :a2a :api-key
                                                        :env "HIVE_MCP_A2A_API_KEY")})
                   (log/info "A2A gateway started"))))
