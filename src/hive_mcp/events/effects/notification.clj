(ns hive-mcp.events.effects.notification
  "Notification effect handlers for the hive-mcp event system.

   Effects implemented:
   - :shout              - Broadcast to hivemind coordinator
   - :targeted-shout     - Send shout to specific agent (File Claim Cascade)
   - :log                - Log a message
   - :channel-publish    - Emit event to WebSocket channel
   - :emit-system-error  - Structured error telemetry
   - :olympus-broadcast  - Broadcast event to Olympus Web UI

   Usage:
   ```clojure
   (require '[hive-mcp.events.effects.notification :as notif-effects])
   (notif-effects/register-notification-effects!)
   ```"

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.channel.core :as channel]
            [datascript.core :as d]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Effect: :shout
;; =============================================================================

(defn- handle-shout
  "Execute a :shout effect - broadcast to hivemind.

   Expected data shape:
   {:agent-id   \"swarm-worker-123\"
    :event-type :progress | :completed | :error | :blocked | :started
    :data       {:task \"...\" :message \"...\" ...}}

   P1 FIX: Fallback chain includes ctx/current-agent-id for drone context."
  [{:keys [agent-id event-type data]}]
  ;; P1 FIX: Check context for drone attribution
  ;; Uses hive-mcp.agent.context (no circular dep) for thread-local agent-id
  (let [get-ctx-agent-id (try
                           (requiring-resolve 'hive-mcp.agent.context/current-agent-id)
                           (catch Exception e (log/trace "[notification] agent-context resolution failed:" (.getMessage e)) nil))
        effective-id (or agent-id
                         (when get-ctx-agent-id (get-ctx-agent-id))
                         (System/getenv "CLAUDE_SWARM_SLAVE_ID")
                         "unknown-agent")]
    (hivemind/shout! effective-id event-type data)))

;; =============================================================================
;; Effect: :targeted-shout (File Claim Cascade)
;; =============================================================================

(defn- handle-targeted-shout
  "Execute a :targeted-shout effect - send shout to specific agent.

   Like :shout but explicitly targets a specific agent-id rather than
   using the current agent from environment. Used for notifying lings
   about events that affect them (e.g., file availability).

   Expected data shape:
   {:target-agent-id \"ling-worker-123\"
    :event-type      :file-available
    :data            {:file \"...\" ...}}"
  [{:keys [target-agent-id event-type data]}]
  (when target-agent-id
    (hivemind/shout! target-agent-id event-type data)))

;; =============================================================================
;; Effect: :log
;; =============================================================================

(defn- handle-log
  "Execute a :log effect - log a message.

   Accepts either a string or a map:
   - String: logged at info level
   - Map: {:level :info/:warn/:error :message \"...\"}"
  [data]
  (cond
    (string? data)
    (log/info "[EVENT]" data)

    (map? data)
    (let [{:keys [level message]} data
          level (or level :info)]
      (case level
        :debug (log/debug "[EVENT]" message)
        :info (log/info "[EVENT]" message)
        :warn (log/warn "[EVENT]" message)
        :error (log/error "[EVENT]" message)
        (log/info "[EVENT]" message)))

    :else
    (log/info "[EVENT]" (str data))))

;; =============================================================================
;; Effect: :channel-publish (EVENTS-04, M1: NATS backbone routing)
;; =============================================================================

(defn- nats-backbone-connected?
  "Check if NATS backbone is connected. Uses requiring-resolve to avoid circular dep."
  []
  (try
    (when-let [get-bb (requiring-resolve 'hive-mcp.protocols.event-backbone/get-backbone)]
      (when-let [connected? (requiring-resolve 'hive-mcp.protocols.event-backbone/connected?)]
        (connected? (get-bb))))
    (catch Exception _ false)))

(defn- publish-tool-notification!
  "Publish a tool notification through NATS backbone."
  [event-type data]
  (try
    (when-let [publish-fn (requiring-resolve 'hive-mcp.nats.bridge/publish-tool-notification!)]
      (publish-fn {:tool-name event-type
                   :event-type event-type
                   :timestamp (System/currentTimeMillis)
                   :data (or data {})}))
    (catch Exception e
      (log/debug "[channel-publish] NATS tool notification failed (non-fatal):" (.getMessage e)))))

(defn- handle-channel-publish
  "Execute a :channel-publish effect - emit to event backbone.

   M1 Architecture:
   - If NATS connected: publish as tool notification → NATS subscribers handle fanout
   - If NATS disconnected: fallback to direct channel/emit-event! (pre-M1 behavior)

   Expected data shape:
   {:event-type :task-completed | :ling-spawned | :memory-added | ...
    :data       {:key \"value\" ...}}"
  [{:keys [event-type data]}]
  (when event-type
    (if (nats-backbone-connected?)
      (publish-tool-notification! event-type data)
      ;; Fallback: direct publish (pre-M1 behavior)
      (channel/emit-event! event-type (or data {})))))

;; =============================================================================
;; Effect: :emit-system-error (Telemetry Phase 1)
;; =============================================================================

(defn- handle-emit-system-error
  "Execute an :emit-system-error effect - emit structured error for telemetry.

   Provides structured error handling for catastrophic failures:
   1. Logs with structured format for searchability
   2. Emits to WebSocket channel for Emacs visibility
   3. Stores in DataScript for post-mortem analysis

   Expected data shape:
   {:error-type :harvest-failed | :component-failed | :restart-collision | :emacs-unreachable
    :source     \"hooks/harvest-session-progress\"
    :message    \"Emacs unreachable\"
    :context    {:fn \"harvest-session-progress\" :attempt 1}}"

  [{:keys [error-type source message context] :as data}]
  (let [timestamp (System/currentTimeMillis)
        error-data (assoc data :timestamp timestamp)]
    ;; 1. Log with structured format
    (log/error "[SYSTEM-ERROR]"
               {:error-type error-type
                :source source
                :message message
                :context context
                :timestamp timestamp})

    ;; 2. Emit to WebSocket channel for Emacs visibility
    (try
      (when (channel/server-connected?)
        (channel/emit-event! :system-error error-data))
      (catch Exception e
        (log/warn "[SYSTEM-ERROR] Failed to emit to channel:" (.getMessage e))))

    ;; 3. Store in DataScript for post-mortem analysis
    (try
      (let [conn (ds/get-conn)]
        (d/transact! conn [{:error/type :system-error
                            :error/error-type error-type
                            :error/source source
                            :error/message message
                            :error/context (pr-str context)
                            :error/timestamp timestamp}]))
      (catch Exception e
        (log/warn "[SYSTEM-ERROR] Failed to store in DataScript:" (.getMessage e))))))

;; =============================================================================
;; Effect: :olympus-broadcast
;; =============================================================================

(defn- handle-olympus-broadcast
  "Execute an :olympus-broadcast effect - broadcast event to Olympus Web UI.

   M1 Architecture:
   - If NATS connected: publish as tool notification → OlympusChannel receives via fanout
   - If NATS disconnected: fallback to direct transport.olympus/broadcast! (pre-M1 behavior)

   Expected data shape: {:type :wave-update :wave-id \"...\" ...}"
  [event-data]
  (if (nats-backbone-connected?)
    ;; M1: Route through NATS — OlympusChannel in delivery registry handles it
    (publish-tool-notification! (or (:type event-data) :olympus-event) event-data)
    ;; Fallback: direct Olympus broadcast (pre-M1 behavior)
    (try
      (when-let [broadcast-fn (requiring-resolve 'hive-mcp.transport.olympus/broadcast!)]
        (broadcast-fn event-data))
      (catch Exception e
        (log/debug "[EVENT] Olympus broadcast failed (non-fatal):" (.getMessage e))))))

;; =============================================================================
;; Effect: :nats-publish (Push-based drone notifications)
;; =============================================================================

(defn- handle-nats-publish
  "Execute a :nats-publish effect — publish to NATS for push-based collection.
   Uses requiring-resolve to avoid hard dependency on NATS client."
  [payload]
  (try
    (when-let [publish-fn (requiring-resolve 'hive-mcp.nats.bridge/publish-drone-event!)]
      (publish-fn payload))
    (catch Exception e
      (log/debug "[EVENT] NATS publish failed (non-fatal):" (.getMessage e)))))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-notification-effects!
  "Register all notification effect handlers.

   Effects registered:
   - :shout              - Broadcast to hivemind coordinator
   - :targeted-shout     - Send shout to specific agent (File Claim Cascade)
   - :log                - Simple logging
   - :channel-publish    - Emit to WebSocket channel
   - :emit-system-error  - Structured error telemetry (Telemetry Phase 1)
   - :olympus-broadcast  - Broadcast event to Olympus Web UI

   Called from hive-mcp.events.effects/register-effects!"
  []
  (ev/reg-fx :shout handle-shout)
  (ev/reg-fx :targeted-shout handle-targeted-shout)
  (ev/reg-fx :log handle-log)
  (ev/reg-fx :channel-publish handle-channel-publish)
  (ev/reg-fx :emit-system-error handle-emit-system-error)
  (ev/reg-fx :olympus-broadcast handle-olympus-broadcast)
  (ev/reg-fx :nats-publish handle-nats-publish)
  (log/info "[hive-events.notification] Notification effects registered: :shout :targeted-shout :log :channel-publish :emit-system-error :olympus-broadcast :nats-publish"))
