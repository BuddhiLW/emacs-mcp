(ns hive-mcp.server.transport.olympus-ws
  "Olympus WebSocket server startup (Olympus Web UI).

   Single responsibility: start Olympus WS server and wire hivemind events."
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.transport.olympus :as olympus-ws]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn start-olympus-ws!
  "Start Olympus WebSocket server for Olympus Web UI (port 7911).
   Sends full snapshot on connect, supports typed event protocol."
  []
  (result/rescue nil
                 (olympus-ws/start!)
                 (olympus-ws/wire-hivemind-events!)
                 (log/info "Olympus WebSocket server started on port 7911")))
