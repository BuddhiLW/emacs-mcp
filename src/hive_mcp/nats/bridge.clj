(ns hive-mcp.nats.bridge
  "Universal event backbone bridge — routes hive events through IEventBackbone.

   Subject hierarchy (v1):
     hive.v1.drone.{completed|failed}.{task-id}   — drone lifecycle
     hive.v1.shout.{project}.{agent-id}            — hivemind shouts
     hive.v1.event.{type}                           — system events
     hive.v1.tool.{tool-name}                       — tool notifications

   Publisher side: called from shout!, effect handlers, drone bridge.
   Subscriber side: delegates fanout to IDeliveryChannel registry.

   Design: backbone is protocol-mediated (IEventBackbone). All fanout
   is via IDeliveryChannel registry — no hardcoded transport in publishers
   or subscribers."

  (:require [hive-mcp.protocols.event-backbone :as eb]
            [hive-mcp.protocols.delivery-channel :as dc]
            [hive-mcp.tools.swarm.channel :as channel]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Subject Hierarchy — Canonical Subject Definitions
;; =============================================================================

;; Drone subjects (existing)
(def ^:private drone-prefix "hive.v1.drone")

;; Hivemind subjects (M1 — new)
(def ^:private shout-prefix "hive.v1.shout")

;; System event subjects (M1 — new)
(def ^:private event-prefix "hive.v1.event")

;; Tool notification subjects (M1 — new)
(def ^:private tool-prefix "hive.v1.tool")

;; --- Drone subjects ---

(defn task-subject
  "Build subject for a specific drone task event.
   E.g. hive.v1.drone.completed.task-123"
  [task-id event-type]
  (str drone-prefix "." (name event-type) "." task-id))

(defn wildcard-subject
  "Build wildcard subject for all drone events of a type.
   E.g. hive.v1.drone.completed.>"
  [event-type]
  (str drone-prefix "." (name event-type) ".>"))

;; --- Shout subjects ---

(defn shout-subject
  "Build subject for a hivemind shout.
   E.g. hive.v1.shout.hive.forja-cl-123"
  [project-id agent-id]
  (str shout-prefix "." (or project-id "global") "." agent-id))

(defn shout-wildcard
  "Build wildcard subject for all shouts in a project.
   hive.v1.shout.{project}.> or hive.v1.shout.> for all projects."
  ([] (str shout-prefix ".>"))
  ([project-id] (str shout-prefix "." project-id ".>")))

;; --- System event subjects ---

(defn event-subject
  "Build subject for system events.
   E.g. hive.v1.event.agent-spawn"
  [event-type]
  (str event-prefix "." (name event-type)))

(defn event-wildcard
  "Build wildcard subject for all system events.
   hive.v1.event.>"
  []
  (str event-prefix ".>"))

;; --- Tool notification subjects ---

(defn tool-subject
  "Build subject for tool notifications.
   E.g. hive.v1.tool.memory-add"
  [tool-name]
  (str tool-prefix "." (name tool-name)))

(defn tool-wildcard
  "Build wildcard subject for all tool notifications.
   hive.v1.tool.>"
  []
  (str tool-prefix ".>"))

;; =============================================================================
;; Publisher Side — Drone Events (called from :nats-publish effect)
;; =============================================================================

(defn publish-drone-event!
  "Publish drone event via IEventBackbone. Called by :nats-publish effect handler."
  [{:keys [event-type task-id] :as payload}]
  (let [backbone (eb/get-backbone)
        subject (task-subject task-id event-type)]
    (eb/publish! backbone subject payload)))

;; =============================================================================
;; Publisher Side — Hivemind Shouts (M1)
;; =============================================================================

(defn publish-shout!
  "Publish hivemind shout via IEventBackbone. The single publish point for all shout events.
   Subscribers receive via backbone subscriptions, fanout via IDeliveryChannel.

   Payload shape:
   {:agent-id   \"forja-cl-123\"
    :event-type :progress
    :message    \"Working on X\"
    :task       \"Big task\"
    :project-id \"hive\"
    :timestamp  1234567890
    :data       {...}}"
  [{:keys [agent-id project-id] :as payload}]
  (let [backbone (eb/get-backbone)
        subject (shout-subject project-id agent-id)]
    (eb/publish! backbone subject payload)
    (log/debug "[Bridge] Published shout on" subject)))

;; =============================================================================
;; Publisher Side — System Events (M1)
;; =============================================================================

(defn publish-event!
  "Publish system event via IEventBackbone. For agent lifecycle, vessel events, etc.

   Payload shape:
   {:type       :agent-spawn
    :agent-id   \"ling-123\"
    :timestamp  1234567890
    :data       {...}}"
  [{:keys [type] :as payload}]
  (let [backbone (eb/get-backbone)
        subject (event-subject type)]
    (eb/publish! backbone subject payload)
    (log/debug "[Bridge] Published event on" subject)))

;; =============================================================================
;; Publisher Side — Tool Notifications (M1)
;; =============================================================================

(defn publish-tool-notification!
  "Publish tool notification via IEventBackbone. For tool execution events
   (memory-add, kanban-update, wave-dispatch, etc.).

   Payload shape:
   {:tool-name  :memory-add
    :event-type :tool-executed
    :timestamp  1234567890
    :data       {...}}"
  [{:keys [tool-name] :as payload}]
  (let [backbone (eb/get-backbone)
        subject (tool-subject (or tool-name "unknown"))]
    (eb/publish! backbone subject payload)
    (log/debug "[Bridge] Published tool notification on" subject)))

;; =============================================================================
;; Callback Bridge (requiring-resolve to avoid circular deps)
;; =============================================================================

(defn- fire-callback-if-registered!
  "Fire callback for task if registered. Uses requiring-resolve to avoid
   circular dependency on hive-mcp.swarm.callback."
  [task-id event-data]
  (try
    (when-let [notify! (requiring-resolve 'hive-mcp.swarm.callback/notify-completion!)]
      (notify! task-id event-data))
    (catch Exception e
      (log/debug "[Bridge] Callback fire failed for" task-id (.getMessage e)))))

;; =============================================================================
;; Error summarization — cap drone error payloads before shouting
;; =============================================================================

(def ^:private ^:const max-summary-len 300)

(defn- truncate-str
  "Truncate s to max-summary-len, appending ellipsis if trimmed."
  [s]
  (let [s (str s)]
    (if (<= (count s) max-summary-len)
      s
      (str (subs s 0 max-summary-len) "…"))))

(defn- summarize-drone-error
  "Summarize a drone error payload for shouting — never emit >~300 chars.

   Rules:
   - string       → first 300 chars + '…' (if longer)
   - ex-info/Throwable → type + ex-message (truncated)
   - map          → prefer (:error/type), (:message), (ex-message); drop rest
   - collection > 5 items → '<N items, first: <truncated>>'
   - fallback     → truncated (pr-str x)

   Guards against drones echoing whole JSON arrays (e.g. kanban-list response)
   as their error — that floods piggyback blocks and wastes coordinator context."
  [error]
  (cond
    (nil? error)
    "unknown error"

    (string? error)
    (truncate-str error)

    (instance? Throwable error)
    (let [msg (ex-message error)
          data (ex-data error)
          etype (some-> data :error/type)]
      (truncate-str
       (cond
         (and etype msg) (str etype " " msg)
         etype (str etype)
         msg msg
         :else (.getName (class error)))))

    (map? error)
    (let [etype (:error/type error)
          msg (or (:message error) (:error/message error))
          exm (ex-message error)
          picked (cond
                   (and etype msg) (str etype " " msg)
                   etype (str etype)
                   msg msg
                   exm exm
                   :else (pr-str (select-keys error [:error/type :message
                                                     :error/message :type])))]
      (truncate-str picked))

    (and (coll? error) (not (map? error)))
    (let [n (count error)]
      (if (> n 5)
        (truncate-str
         (str "<" n " items, first: " (pr-str (first error)) ">"))
        (truncate-str (pr-str error))))

    :else
    (truncate-str (pr-str error))))

;; =============================================================================
;; Hivemind Auto-Shout (drone visibility via piggyback)
;; =============================================================================

(defn- auto-shout-drone-event!
  "Auto-shout drone completion/failure to hivemind for visibility.
   Makes drone wave results appear in ---HIVEMIND--- piggyback blocks.
   Uses requiring-resolve to avoid circular dep on hivemind.messaging."
  [task-id event-type summary-msg]
  (try
    (when-let [shout-fn (requiring-resolve 'hive-mcp.hivemind.core/shout!)]
      (shout-fn (str "drone:" task-id)
                event-type
                {:message summary-msg
                 :task (str "drone-task:" task-id)}))
    (catch Exception e
      (log/debug "[Bridge] Auto-shout failed for" task-id (.getMessage e)))))

;; =============================================================================
;; Subscriber Side — Drone Events (existing)
;; =============================================================================

(defn- handle-drone-completed
  "Handle drone completion — write to event journal, fire callback, and auto-shout."
  [{:keys [task-id parent-id result] :as _msg}]
  (log/info "[Bridge] drone completed:" task-id)
  (let [files-modified (get result :files-modified [])
        files-failed (get result :files-failed [])
        duration-ms (get result :duration-ms)
        event-data {:status "completed"
                    :result result
                    :slave-id parent-id
                    :timestamp (System/currentTimeMillis)
                    :via "backbone-push"}]
    (channel/record-nats-event! task-id event-data)
    (fire-callback-if-registered! task-id event-data)
    (auto-shout-drone-event!
     task-id :completed
     (str "Drone " task-id " completed: "
          (count files-modified) " files modified"
          (when (seq files-failed) (str ", " (count files-failed) " failed"))
          (when duration-ms (str " (" duration-ms "ms)"))))))

(defn- handle-drone-failed
  "Handle drone failure — write to event journal, fire callback, and auto-shout."
  [{:keys [task-id parent-id error] :as _msg}]
  (log/info "[Bridge] drone failed:" task-id)
  (let [event-data {:status "failed"
                    :error error
                    :slave-id parent-id
                    :timestamp (System/currentTimeMillis)
                    :via "backbone-push"}]
    (channel/record-nats-event! task-id event-data)
    (fire-callback-if-registered! task-id event-data)
    (auto-shout-drone-event!
     task-id :error
     (str "Drone " task-id " failed: " (summarize-drone-error error)))))

;; =============================================================================
;; Subscriber Side — Hivemind Shout Fanout (M1, protocol-mediated)
;; =============================================================================

(defn- handle-shout-fanout!
  "Fanout a shout to all registered IDeliveryChannels.
   Single backbone subscription, multiple local deliveries via protocol registry.
   Each delivery is independent and non-fatal."
  [payload]
  (dc/fanout! payload))

;; =============================================================================
;; Subscriber Side — Tool Notification Fanout (M1)
;; =============================================================================

(defn- handle-tool-notification!
  "Fanout a tool notification to all registered IDeliveryChannels.
   Same pattern as shout fanout — single backbone subscription, protocol registry fanout."
  [payload]
  (dc/fanout! payload))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start-subscriptions!
  "Subscribe to all event subjects via IEventBackbone.
   Drone events + hivemind shout fanout + tool notification fanout.
   No-op if backbone is not connected."
  []
  (let [backbone (eb/get-backbone)]
    (when (eb/connected? backbone)
      ;; Drone subscriptions (existing)
      (eb/subscribe! backbone (wildcard-subject :completed) handle-drone-completed)
      (eb/subscribe! backbone (wildcard-subject :failed) handle-drone-failed)
      ;; Shout fanout subscription (M1 — protocol-mediated)
      (eb/subscribe! backbone (shout-wildcard) handle-shout-fanout!)
      ;; Tool notification fanout subscription (M1)
      (eb/subscribe! backbone (tool-wildcard) handle-tool-notification!)
      (log/info "[Bridge] Subscriptions started (drone + shout + tool fanout)"))))

(defn stop-subscriptions!
  "Unsubscribe from all event subjects via IEventBackbone."
  []
  (let [backbone (eb/get-backbone)]
    (eb/unsubscribe! backbone (wildcard-subject :completed))
    (eb/unsubscribe! backbone (wildcard-subject :failed))
    (eb/unsubscribe! backbone (shout-wildcard))
    (eb/unsubscribe! backbone (tool-wildcard))
    (log/info "[Bridge] Subscriptions stopped")))
