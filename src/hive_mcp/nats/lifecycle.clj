(ns hive-mcp.nats.lifecycle
  "IShutdownHook for NATS connection close. Priority 200 (client band).

   TRANSITIONAL: lives in hive-mcp/nats. If NATS is extracted to a
   separate hive-nats addon later, this file moves there and registers
   via that addon's IAddon.init!.

   Wraps hive-mcp.nats.client/stop! — idempotent, safe to call when
   NATS is not connected (when-let guard on the connection atom)."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.system.registry :as reg]
            [hive-mcp.nats.client :as nats-client]
            [taoensso.timbre :as log]))

;; =============================================================================
;; NatsShutdown — priority 200 (client band)
;; =============================================================================

(defrecord NatsShutdown []
  lifecycle/IShutdownHook
  (shutdown-priority [_] 200)
  (shutdown-name     [_] "nats/close")
  (shutdown!         [_ _ctx]
    (try
      (nats-client/stop!)
      (log/info "NATS connection closed")
      (catch Throwable t
        (log/error t "NATS close failed")))))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register! []
  (reg/register-shutdown! (->NatsShutdown)))

;; Auto-register on ns load — layer1.clj requires this ns at init time
(defonce ^:private -registered?
  (do (register!) true))
