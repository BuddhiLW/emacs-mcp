(ns hive-mcp.swarm.event-bridge
  "Swarm event bridge — connects the IEventBackbone (NATS) to the in-process
   swarm event bus (`hive-mcp.channel.core`).

   Why this exists:
   - swarm/sync.clj handlers subscribe to channel.core via :slave-spawned,
     :slave-status, :slave-killed, etc.
   - In single-process mode those events arrive from the WebSocket channel.
   - In distributed mode (multiple coordinators / sidecar processes) the
     same events must reach this process via the IEventBackbone.

   Subject convention (extends nats/bridge.clj's hive.v1.* hierarchy):
     hive.v1.slave.spawned.{slave-id}
     hive.v1.slave.killed.{slave-id}
     hive.v1.slave.status.{slave-id}
     hive.v1.slave.ready.{slave-id}
     hive.v1.slave.>                      (wildcard used by the bridge)

   Design (SOLID):
   - DIP: depends only on the IEventBackbone protocol, not on NATS directly.
   - SRP: this namespace does ONE thing — translate between backbone subjects
     and the in-process channel topology. Handlers themselves stay unchanged.
   - OCP: adding a new slave event type means adding it to `slave-event-types`
     and (optionally) one publisher; no edits to consumers."
  (:require [hive-mcp.channel.core :as channel]
            [hive-mcp.protocols.event-backbone :as eb]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Subject Convention
;; =============================================================================

(def ^:private slave-prefix "hive.v1.slave")

(def ^:private slave-event-types
  "The set of slave event :type values that swarm-sync handlers consume.
   Adding a new entry here is the only step needed to extend the bridge —
   subscribers and publishers below derive everything else from this."
  #{:slave-spawned :slave-ready :slave-status :slave-killed
    :task-dispatched :task-completed :task-failed
    :prompt-shown :prompt-stall :dispatch-dropped})

(defn slave-subject
  "Build a NATS subject for a slave/task event.
   Pure: no side effects. event-type is a keyword like :slave-spawned."
  [event-type slave-id]
  (str slave-prefix "." (name event-type) "." (or slave-id "unknown")))

(def slave-wildcard
  "Wildcard subject for ALL slave/task events (used by the inbound bridge)."
  (str slave-prefix ".>"))

;; =============================================================================
;; Inbound: NATS → in-process channel
;; =============================================================================

(defn- ->keyword
  "Pure: coerce a value to a keyword if it isn't already. nil-safe."
  [v]
  (cond
    (nil? v) nil
    (keyword? v) v
    (string? v) (keyword v)
    :else (keyword (str v))))

(defn- republish-from-nats!
  "Handler invoked when a message arrives on `hive.v1.slave.>`.
   Re-emits the message into the local channel.core event bus so the
   existing swarm/sync.clj handlers fire unchanged.

   Coercion: NATS serialization preserves map keys as keywords but downgrades
   keyword *values* to strings. channel.core/publish! routes on the `:type`
   value, and swarm/sync subscribers register with keyword event-types, so
   we MUST coerce :type back to a keyword here or the message silently goes
   nowhere. :status is also coerced (consumed by handle-slave-status).

   Idempotency / loop prevention: re-emitted messages carry `:via :nats-bridge`
   so `publish-slave-event!` skips its outbound NATS mirror."
  [payload]
  (try
    (when (and (map? payload) (:type payload))
      (let [coerced (cond-> payload
                      true                (update :type ->keyword)
                      (:status payload)   (update :status ->keyword)
                      true                (assoc :via :nats-bridge))]
        (channel/publish! coerced)
        (log/debug "[swarm.event-bridge] re-emitted from NATS:" (:type coerced))))
    (catch Exception e
      (log/warn "[swarm.event-bridge] failed to re-emit from NATS:" (.getMessage e)))))

(defonce ^:private bridge-state (atom {:running false}))

(defn start-nats-bridge!
  "Start the NATS → channel.core bridge for slave events.
   Idempotent. No-op if backbone is not connected.
   Returns true on successful start, false otherwise."
  []
  (let [bb (eb/get-backbone)]
    (cond
      (:running @bridge-state)
      (do (log/info "[swarm.event-bridge] already running") true)

      (not (eb/connected? bb))
      (do
        (log/warn "[swarm.event-bridge] backbone not connected ("
                  (eb/backbone-id bb) ") — bridge not started")
        false)

      :else
      (do
        (eb/subscribe! bb slave-wildcard republish-from-nats!)
        (swap! bridge-state assoc :running true :backbone-id (eb/backbone-id bb))
        (log/info "[swarm.event-bridge] subscribed to" slave-wildcard
                  "on" (eb/backbone-id bb) "backbone")
        true))))

(defn stop-nats-bridge!
  "Stop the NATS → channel.core bridge. Idempotent."
  []
  (when (:running @bridge-state)
    (try
      (eb/unsubscribe! (eb/get-backbone) slave-wildcard)
      (catch Exception e
        (log/warn "[swarm.event-bridge] unsubscribe failed:" (.getMessage e))))
    (swap! bridge-state assoc :running false)
    (log/info "[swarm.event-bridge] stopped")))

(defn bridge-status
  "Return current bridge state for diagnostics."
  []
  @bridge-state)

;; =============================================================================
;; Outbound: in-process channel → NATS (opt-in helper)
;; =============================================================================

(defn publish-slave-event!
  "Publish a slave event to BOTH the in-process channel.core bus AND the
   active IEventBackbone (NATS).

   Producers that want their events to reach distributed consumers should
   call this instead of `channel/publish!` directly. Local consumers see
   no difference; NATS consumers receive the same payload on
   `hive.v1.slave.{type}.{slave-id}`.

   Loop prevention: events that already carry `:via :nats-bridge` (i.e.
   were inbound from NATS) are NOT re-published outbound."
  [{:keys [type slave-id via] :as event}]
  (when-not (:type event)
    (throw (ex-info "publish-slave-event! requires :type" {:event event})))
  ;; Always emit locally
  (channel/publish! event)
  ;; Mirror outbound only when not already inbound from NATS
  (when (not= via :nats-bridge)
    (let [bb (eb/get-backbone)]
      (when (eb/connected? bb)
        (eb/publish! bb (slave-subject type slave-id) event)))))
