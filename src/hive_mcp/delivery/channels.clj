(ns hive-mcp.delivery.channels
  "IDeliveryChannel implementations for all fanout endpoints.

   Each channel wraps an existing delivery mechanism behind the protocol:
   - WebSocketChannel        — hive-mcp.channel.websocket/emit!
   - CoreAsyncChannel        — hive-mcp.channel.core/publish!
   - ChannelBroadcastChannel — hive-mcp.channel.core/broadcast!
   - OlympusChannel          — hive-mcp.transport.olympus/emit-hivemind-shout!
   - PiggybackChannel        — hive-mcp.channel.piggyback/buffer-backbone-event!

   All use requiring-resolve to avoid circular dependencies.
   All deliveries are non-fatal — failures log but don't propagate."

  (:require [hive-mcp.protocols.delivery-channel :as dc]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; WebSocketChannel
;;; ============================================================================

(defrecord WebSocketChannel []
  dc/IDeliveryChannel
  (channel-id [_this] :websocket)

  (available? [_this]
    (try
      (when-let [connected? (requiring-resolve 'hive-mcp.channel.websocket/connected?)]
        (connected?))
      (catch Exception _ false)))

  (deliver! [_this {:keys [event-type] :as event}]
    (try
      (when-let [ws-emit (requiring-resolve 'hive-mcp.channel.websocket/emit!)]
        (ws-emit (keyword (str "hivemind-" (name event-type)))
                 (dissoc event :type :event-type)))
      (catch Exception e
        (log/debug "[WebSocketChannel] Delivery failed:" (.getMessage e))))))

;;; ============================================================================
;;; CoreAsyncChannel
;;; ============================================================================

(defrecord CoreAsyncChannel []
  dc/IDeliveryChannel
  (channel-id [_this] :core-async)

  (available? [_this]
    true)  ;; core.async is always available in-process

  (deliver! [_this {:keys [agent-id event-type timestamp project-id] :as event}]
    (try
      (when-let [publish-fn (requiring-resolve 'hive-mcp.channel.core/publish!)]
        (publish-fn {:type (keyword (str "hivemind-" (name event-type)))
                     :agent-id agent-id
                     :timestamp timestamp
                     :project-id project-id
                     :data (dissoc event :agent-id :event-type :timestamp :project-id :type)}))
      (catch Exception e
        (log/debug "[CoreAsyncChannel] Delivery failed:" (.getMessage e))))))

;;; ============================================================================
;;; ChannelBroadcastChannel
;;; ============================================================================

(defrecord ChannelBroadcastChannel []
  dc/IDeliveryChannel
  (channel-id [_this] :channel-broadcast)

  (available? [_this]
    (try
      (when-let [connected? (requiring-resolve 'hive-mcp.channel.core/server-connected?)]
        (connected?))
      (catch Exception _ false)))

  (deliver! [_this {:keys [agent-id event-type timestamp project-id] :as event}]
    (try
      (when-let [broadcast-fn (requiring-resolve 'hive-mcp.channel.core/broadcast!)]
        (broadcast-fn {:type (keyword (str "hivemind-" (name event-type)))
                       :agent-id agent-id
                       :timestamp timestamp
                       :project-id project-id
                       :data (dissoc event :agent-id :event-type :timestamp :project-id :type)}))
      (catch Exception e
        (log/debug "[ChannelBroadcastChannel] Delivery failed:" (.getMessage e))))))

;;; ============================================================================
;;; OlympusChannel
;;; ============================================================================

(defrecord OlympusChannel []
  dc/IDeliveryChannel
  (channel-id [_this] :olympus)

  (available? [_this]
    true)  ;; Olympus availability checked at deliver time

  (deliver! [_this {:keys [agent-id event-type message task] :as event}]
    (try
      (when-let [emit-fn (requiring-resolve 'hive-mcp.transport.olympus/emit-hivemind-shout!)]
        (emit-fn {:agent-id agent-id
                  :event-type (name event-type)
                  :message message
                  :task task
                  :data (dissoc event :agent-id :event-type :message :task :type)}))
      (catch Exception e
        (log/debug "[OlympusChannel] Delivery failed:" (.getMessage e))))))

;;; ============================================================================
;;; PiggybackChannel
;;; ============================================================================

(defrecord PiggybackChannel []
  dc/IDeliveryChannel
  (channel-id [_this] :piggyback)

  (available? [_this]
    true)  ;; Piggyback buffer is always available (atom-based, in-process)

  (deliver! [_this {:keys [agent-id event-type message task timestamp project-id shout-id] :as event}]
    (try
      (when-let [buffer-fn (requiring-resolve 'hive-mcp.channel.piggyback/buffer-backbone-event!)]
        (buffer-fn (cond-> {:agent-id   agent-id
                            :event-type event-type
                            :message    message
                            :task       task
                            :timestamp  timestamp
                            :project-id project-id}
                     shout-id (assoc :shout-id shout-id))))
      (catch Exception e
        (log/debug "[PiggybackChannel] Delivery failed:" (.getMessage e))))))

;;; ============================================================================
;;; Factory Functions
;;; ============================================================================

(defn create-websocket-channel [] (->WebSocketChannel))
(defn create-core-async-channel [] (->CoreAsyncChannel))
(defn create-channel-broadcast-channel [] (->ChannelBroadcastChannel))
(defn create-olympus-channel [] (->OlympusChannel))
(defn create-piggyback-channel [] (->PiggybackChannel))

(defn register-default-channels!
  "Register all default delivery channels. Called during system init."
  []
  (dc/register-channel! (create-websocket-channel))
  (dc/register-channel! (create-core-async-channel))
  (dc/register-channel! (create-channel-broadcast-channel))
  (dc/register-channel! (create-olympus-channel))
  (dc/register-channel! (create-piggyback-channel))
  (log/info "[DeliveryChannels] Registered:" (mapv dc/channel-id (dc/get-channels))))
