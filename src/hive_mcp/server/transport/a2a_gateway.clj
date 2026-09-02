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
  "Start the A2A JSON-RPC gateway.

   Enabled when the system component config says so, or when the runtime
   config / HIVE_MCP_A2A_ENABLED does. Port and api-key come from the
   component config first, the runtime config second.

   Returns nil when not enabled, {:status :running :port n} when the gateway
   is listening, {:status :failed :port n} when the start threw. Never throws."
  ([] (start-a2a-gateway! nil))
  ([component-config]
   (let [enabled? (or (:enabled component-config)
                      (config/get-service-value :a2a :enabled
                                                :env "HIVE_MCP_A2A_ENABLED"
                                                :parse #(= "true" %)
                                                :default false))
         port     (or (:port component-config)
                      (config/get-service-value :a2a :port
                                                :env "HIVE_MCP_A2A_PORT"
                                                :parse parse-long
                                                :default 7912))]
     (when enabled?
       (let [started (result/rescue ::failed
                                    (require 'hive-mcp.transport.a2a)
                                    ((resolve 'hive-mcp.transport.a2a/start!)
                                     {:port    port
                                      :api-key (config/get-service-value :a2a :api-key
                                                                         :env "HIVE_MCP_A2A_API_KEY")})
                                    :started)]
         (if (= ::failed started)
           (do (log/warn "A2A gateway failed to start, NOT listening" {:port port})
               {:status :failed :port port})
           (do (log/info "A2A gateway started" {:port port})
               {:status :running :port port})))))))
