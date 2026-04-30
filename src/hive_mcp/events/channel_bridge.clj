(ns hive-mcp.events.channel-bridge
  "Wires channel events to hive-events dispatch.
   
   Subscribes to channel pub/sub, transforms via bridge patterns,
   and dispatches through the hive-events system.
   
   This bridges two systems:
   - channel.clj: core.async pub/sub for Emacs↔Clojure communication
   - events/core.clj: re-frame inspired event dispatch
   
   ## Usage
   
   ```clojure
   (require '[hive-mcp.events.channel-bridge :as bridge])
   
   ;; Start listening to channel events
   (bridge/subscribe-channel!)
   
   ;; Stop listening
   (bridge/unsubscribe-channel!)
   ```
   
   ## Event Transformation
   
   Channel events (maps with string keys) are transformed to
   hive-events vectors via the bridge.clj multimethod:
   
   Channel: {\"type\" \"task-complete\" \"data\" {...}}
   → Events: [:task/complete {...}]"
   
  (:require [hive-mcp.channel.core :as channel]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.bridge :as bridge]
            [clojure.core.async :as async :refer [go-loop <!]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private *subscription (atom nil))

;; =============================================================================
;; Event Transformation
;; =============================================================================

(defn- kebab->ns-keyword
  "Convert kebab-case string to namespaced keyword.
   
   \"task-complete\" → :task/complete
   \"hivemind-progress\" → :hivemind/progress
   \"simple\" → :channel/simple"
  [s]
  (if-let [[_ prefix suffix] (re-matches #"([^-]+)-(.+)" s)]
    (keyword prefix suffix)
    (keyword "channel" s)))

(defn hook->event
  "Transform a channel event map to a hive-events dispatch vector.
   
   Uses bridge.clj multimethod for known hook types, with fallback
   to kebab→namespaced conversion for channel-specific events.
   
   Channel events arrive as maps with string keys from bencode.
   Transforms to [:event-type data-map] format for dispatch.
   
   Examples:
   - {\"type\" \"task-complete\" \"task-id\" \"123\"} 
     → [:task/complete {:task-id \"123\"}]  (via bridge multimethod)
   - {:type :hivemind-progress :data {...}}
     → [:hivemind/progress {...}]  (via kebab fallback)"
  [channel-event]
  (let [;; Handle both string and keyword keys (channel normalizes to keywords)
        type-val (or (get channel-event :type)
                     (get channel-event "type"))
        ;; Extract data payload (everything except type/timestamp/client-id)
        data (dissoc channel-event :type "type" :timestamp "timestamp"
                     :client-id "client-id")]
    (when type-val
      (let [;; Convert type to keyword for bridge lookup
            hook-kw (if (keyword? type-val)
                      type-val
                      (keyword (str type-val)))
            ;; Try bridge multimethod first (authoritative source)
            bridge-result (bridge/hook->event hook-kw data)]
        (if bridge-result
          ;; Bridge knows this hook type
          bridge-result
          ;; Fallback: kebab→namespaced conversion for unknown types
          (let [event-kw (kebab->ns-keyword (name hook-kw))]
            [event-kw data]))))))

;; =============================================================================
;; Channel Event Handler
;; =============================================================================

(defn on-channel-event
  "Handle a single channel event.
   
   Transforms via hook->event and dispatches through hive-events.
   Returns the dispatch result or nil on error."
  [event]
  (try
    (when-let [hive-event (hook->event event)]
      (let [event-id (first hive-event)]
        ;; Only dispatch if handler is registered
        (if (ev/handler-registered? event-id)
          (do
            (log/debug "[channel-bridge] Dispatching:" hive-event)
            (ev/dispatch hive-event))
          (do
            (log/trace "[channel-bridge] No handler for:" event-id)
            nil))))
    (catch Throwable e
      (log/error "[channel-bridge] Error handling event:" (.getMessage e))
      nil)))

;; =============================================================================
;; Subscription Management
;; =============================================================================

(defn subscribe-channel!
  "Subscribe to all channel events and dispatch through hive-events.
   
   Creates a go-loop that:
   1. Subscribes to a wildcard event type
   2. Transforms each event via hook->event
   3. Dispatches to hive-events system
   
   Returns true if subscription started, false if already subscribed."
  ([]
   (subscribe-channel! :channel-bridge-events))
  ([event-type]
   (if @*subscription
     (do
       (log/warn "[channel-bridge] Already subscribed")
       false)
     (let [ch (channel/subscribe! event-type)]
       (reset! *subscription {:channel ch :event-type event-type})
       (go-loop []
         (when-let [event (<! ch)]
           (on-channel-event event)
           (recur)))
       (log/info "[channel-bridge] Subscribed to channel events:" event-type)
       true))))

(defn unsubscribe-channel!
  "Unsubscribe from channel events.
   
   Cleans up the subscription channel and go-loop.
   Returns true if unsubscribed, false if not subscribed."
  []
  (if-let [{:keys [channel event-type]} @*subscription]
    (do
      (channel/unsubscribe! event-type channel)
      (reset! *subscription nil)
      (log/info "[channel-bridge] Unsubscribed from channel events")
      true)
    (do
      (log/debug "[channel-bridge] Not subscribed")
      false)))

(defn subscribed?
  "Check if currently subscribed to channel events."
  []
  (some? @*subscription))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn init!
  "Initialize the channel bridge.
   
   Call after both channel server and event system are initialized."
  []
  (subscribe-channel!)
  (log/info "[channel-bridge] Initialized"))

(defn shutdown!
  "Shutdown the channel bridge cleanly."
  []
  (unsubscribe-channel!)
  (log/info "[channel-bridge] Shutdown"))
