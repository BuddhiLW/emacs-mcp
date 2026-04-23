(ns hive-mcp.transport.olympus.stream
  "WebSocket client stream handling and event broadcasting for Olympus.

   Owns the `clients` connection set and provides send/broadcast/emit
   primitives plus the WebSocket connection handler.

   Split from transport/olympus.clj (hotspot #14 refactor, plan
   refactor-hotspots-p0.md line 99-104)."
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [hive-mcp.dns.result :as result]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.transport.olympus.snapshots :as snap]
            [hive-mcp.transport.olympus.state-bridge :as state-bridge]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce clients (atom #{}))

;; =============================================================================
;; WebSocket Handler
;; =============================================================================

(defn- send-to-client!
  "Send JSON message to a specific client."
  [client msg]
  (when-not (s/closed? client)
    (d/catch
     (s/put! client (json/write-str msg))
     (fn [e]
       (log/debug "Send to client failed:" (.getMessage e))
       (swap! clients disj client)))))

(defn- handle-client-message
  "Handle incoming message from Olympus client.

   Supported commands:
   {:type :subscribe :views [:agents :waves]}  - Filter events (future)
   {:type :request-snapshot :view :kg}         - Request fresh snapshot"
  [client msg]
  (result/rescue nil
                 (let [parsed (json/read-str msg :key-fn keyword)]
                   (case (:type parsed)
        ;; Request snapshot for specific view
                     "request-snapshot"
                     (let [view (keyword (:view parsed))
                           snapshot (case view
                                      :agents {:type :agents :data (snap/build-agents-snapshot)}
                                      :waves {:type :waves :data (snap/build-waves-snapshot)}
                                      :kg {:type :kg-snapshot :data (snap/build-kg-snapshot)}
                                      :project-tree {:type :project-tree :data (snap/build-project-tree-snapshot)}
                                      {:type :error :message (str "Unknown view: " view)})]
                       (send-to-client! client snapshot))

        ;; Ping/pong for keepalive
                     "ping"
                     (send-to-client! client {:type :pong :timestamp (System/currentTimeMillis)})

        ;; Unknown - log and ignore
                     (log/debug "Unknown Olympus message type:" (:type parsed))))))

(defn ws-connection-handler
  "Handle WebSocket connection from Olympus UI.
   Sends full state snapshot immediately on connect."
  [req]
  (d/let-flow [socket (http/websocket-connection req)]
              (let [client-id (str "olympus-" (System/currentTimeMillis) "-" (rand-int 10000))]
                (log/info "Olympus client connected:" client-id)
                (swap! clients conj socket)

      ;; Send full snapshot immediately (browser refresh = needs full state)
                (send-to-client! socket (snap/build-full-snapshot))

      ;; Handle incoming messages
                (s/consume (fn [raw]
                             (cond
                               (= raw "ping") (send-to-client! socket {:type :pong})
                               :else (handle-client-message socket raw)))
                           socket)

      ;; Cleanup on disconnect
                (s/on-closed socket
                             (fn []
                               (log/info "Olympus client disconnected:" client-id)
                               (swap! clients disj socket)))

                socket)))

;; =============================================================================
;; Public API - Event Broadcasting
;; =============================================================================

(defn broadcast!
  "Broadcast event to all connected Olympus clients.

   Event should match Olympus protocol:
   {:type :agents :data [...]}
   {:type :wave-update :wave-id \"...\" :task-idx 0 :status :completed}
   {:type :hivemind-shout :agent-id \"...\" :event-type \"progress\" :message \"...\"}
   {:type :kg-entry-added :entry {...}}"
  [event]
  (let [json-msg (json/write-str event)
        active-clients @clients]
    (when (seq active-clients)
      (log/debug "Olympus broadcast to" (count active-clients) "clients:" (:type event))
      (doseq [client active-clients]
        (when-not (s/closed? client)
          (d/catch
           (s/put! client json-msg)
           (fn [e]
             (log/debug "Olympus broadcast failed:" (.getMessage e))
             (swap! clients disj client))))))))

(defn emit!
  "Emit a typed event to all Olympus clients.
   Convenience wrapper that adds timestamp."
  [event-type data]
  (broadcast! (merge {:type event-type
                      :timestamp (System/currentTimeMillis)}
                     data)))

;; =============================================================================
;; Event Adapters (Transform hivemind events to Olympus protocol)
;; =============================================================================

(defn emit-agent-event!
  "Emit agent lifecycle event in Olympus protocol.

   Event types:
   - :agent-spawned  -> full agent data
   - :agent-status   -> status update
   - :agent-killed   -> removal notification"
  [event-type agent-data]
  (emit! event-type {:agent agent-data}))

(defn emit-wave-event!
  "Emit wave lifecycle event in Olympus protocol.

   Event types:
   - :wave-dispatched -> full wave data
   - :wave-task-update -> task progress
   - :wave-completed -> final status"
  [event-type wave-data]
  (emit! event-type wave-data))

(defn emit-hivemind-shout!
  "Emit hivemind shout in Olympus protocol.
   Called from hivemind/shout! to keep Olympus in sync."
  [{:keys [agent-id event-type message task data]}]
  (emit! :hivemind-shout
         {:agent-id agent-id
          :event-type event-type
          :message message
          :task task
          :data data}))

(defn emit-kg-event!
  "Emit KG change event in Olympus protocol."
  [event-type kg-data]
  (emit! event-type kg-data))

;; =============================================================================
;; Integration Hooks (Wire into existing event sources)
;; =============================================================================

(defn wire-hivemind-events!
  "Wire internal events to Olympus broadcast.

   Called from server.clj during startup. Registers the :olympus-broadcast
   effect with the re-frame-style event system so that event handlers
   (wave, drone, ling, KG, memory) can include {:olympus-broadcast {...}}
   in their effects map and have it automatically broadcast to all
   connected Olympus WebSocket clients.

   Also wires memory change hooks via channel pub/sub for events that
   bypass the re-frame event system (direct chroma writes)."
  []
  (result/rescue nil
    ;; 1. Register :olympus-broadcast effect (safety net)
    ;;    effects.clj also registers this, but wire! may be called first
    ;;    in some startup orderings. Safe to call reg-fx multiple times.
                 (when-let [reg-fx (requiring-resolve 'hive-mcp.events.core/reg-fx)]
                   (reg-fx :olympus-broadcast
                           (fn [event-data]
                             (broadcast! event-data))))

    ;; 2. Subscribe to memory-added events via channel pub/sub
    ;;    Memory CRUD (handle-add) publishes :memory-added events to channel.
    ;;    We subscribe and forward to Olympus.
    ;;    Track sub for proper cleanup on stop!
                 (when-let [subscribe-fn (requiring-resolve 'hive-mcp.channel.core/subscribe!)]
                   (let [sub-ch (subscribe-fn :memory-added)]
                     (swap! state-bridge/channel-subs conj [:memory-added sub-ch])
                     (pool/with-io
                       (loop []
                         (when-let [event (async/<!! sub-ch)]
                           (result/rescue nil
                                          (emit-kg-event! :memory-entry-added
                                                          {:entry-id (:id event)
                                                           :type (:type event)
                                                           :tags (:tags event)
                                                           :project-id (:project-id event)}))
                           (recur))))))

    ;; 3. Wire DataScript state bridge (auto-push DS changes to clients).
                 (state-bridge/wire-ds-state-bridge!)

                 (log/info "Olympus event wiring complete - :olympus-broadcast effect + memory sub + DS state bridge")))
