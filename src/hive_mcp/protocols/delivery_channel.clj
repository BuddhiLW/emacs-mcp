(ns hive-mcp.protocols.delivery-channel
  "Protocol definitions for event delivery channel backends.

   Abstracts delivery endpoints (WebSocket, core.async, Olympus, etc.)
   behind an IDeliveryChannel protocol so that fanout logic is decoupled
   from specific transport mechanisms.

   Pattern: follows protocols/editor.clj pattern.
   Registry pattern: multiple channels can be active simultaneously,
   unlike IEditor/IEventBackbone which have a single active instance." (:require [hive-dsl.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IDeliveryChannel Protocol
;;; ============================================================================

(defprotocol IDeliveryChannel
  "Backend protocol for event delivery endpoints.

   Unlike IEventBackbone (single active backbone), multiple IDeliveryChannels
   can be registered simultaneously for fanout delivery.

   Implementations:
   - WebSocketChannel         (delivery/channels.clj)
   - CoreAsyncChannel         (delivery/channels.clj)
   - ChannelBroadcastChannel  (delivery/channels.clj)
   - OlympusChannel           (delivery/channels.clj)
   - PiggybackChannel         (delivery/channels.clj)
   - NoopChannel              (this file, fallback)
   - [future: SlackChannel, DiscordChannel, ...]"

  (channel-id [this]
    "Return keyword identifying this delivery channel.
     Examples: :websocket, :core-async, :olympus, :channel-broadcast")

  (available? [this]
    "Return true if this channel is ready to receive events.")

  (deliver! [this event]
    "Deliver an event to this channel.
     event is a map with at minimum {:type keyword}.
     Returns nil. Delivery is fire-and-forget — failures are non-fatal."))

;;; ============================================================================
;;; Channel Registry (Multiple Active Channels)
;;; ============================================================================

(defonce ^:private channel-registry (atom {}))  ;; channel-id -> IDeliveryChannel

(defn register-channel!
  "Register a delivery channel. Replaces any existing channel with same id."
  [channel]
  {:pre [(satisfies? IDeliveryChannel channel)]}
  (swap! channel-registry assoc (channel-id channel) channel)
  channel)

(defn unregister-channel!
  "Unregister a delivery channel by id."
  [id]
  (swap! channel-registry dissoc id)
  nil)

(defn get-channel
  "Get a specific delivery channel by id, or nil."
  [id]
  (get @channel-registry id))

(defn get-channels
  "Get all registered delivery channels as a seq."
  []
  (vals @channel-registry))

(defn clear-channels!
  "Clear all registered delivery channels."
  []
  (reset! channel-registry {}))

(defn fanout!
  "Deliver event to all registered channels. Each delivery is independent
   and non-fatal — one channel failure does not block others."
  [event]
  (doseq [ch (get-channels)]
    (rescue nil (when (available? ch)
        (deliver! ch event)))))

;;; ============================================================================
;;; NoopChannel (Fallback)
;;; ============================================================================

(defrecord NoopChannel []
  IDeliveryChannel
  (channel-id [_this] :noop)
  (available? [_this] false)
  (deliver! [_this _event] nil))

(defn noop-channel
  "Create a no-op delivery channel fallback."
  []
  (->NoopChannel))
