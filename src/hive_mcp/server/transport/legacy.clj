(ns hive-mcp.server.transport.legacy
  "Legacy TCP channel server (deprecated, backward compat).

   Single responsibility: start legacy bidirectional channel server."
  (:require [hive-mcp.config.core :as config]
            [hive-mcp.dns.result :as result]
            [hive-mcp.channel.core :as channel]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn start-legacy-channel!
  "Start legacy bidirectional channel server (deprecated - kept for backward compat).
   Marks coordinator as running to protect from test fixture cleanup."
  []
  (let [channel-port (config/get-service-value :channel :port
                                               :env "HIVE_MCP_CHANNEL_PORT"
                                               :parse parse-long
                                               :default 9998)]
    (result/rescue nil
                   (channel/start-server! {:type :tcp :port channel-port})
      ;; Mark coordinator as running to protect from test fixture cleanup
      ;; the production server when tests run in the same JVM
                   (channel/mark-coordinator-running!)
                   (log/info "Legacy channel server started on TCP port" channel-port))))
