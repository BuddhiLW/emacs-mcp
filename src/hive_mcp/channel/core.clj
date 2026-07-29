(ns hive-mcp.channel.core
  "Bidirectional communication channel between Clojure and Emacs via transport abstraction."
  (:require [hive-mcp.transport.core :as t]
            [clojure.core.async :as async :refer [chan pub sub unsub close!]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Event Bus (core.async pub/sub for internal routing)

(defonce ^:private event-chan (chan 1024))
(defonce ^:private event-pub (pub event-chan :type))

(defn publish!
  "Publish event to the internal event bus.
   Event must have :type key for routing."
  [event]
  (when-not (:type event)
    (throw (ex-info "Event must have :type" {:event event})))
  (async/put! event-chan event))

(defn subscribe!
  "Subscribe to events of given type.
   Returns a core.async channel that receives matching events."
  [event-type]
  (let [ch (chan 256)]
    (sub event-pub event-type ch)
    ch))

(defn unsubscribe!
  "Unsubscribe channel from event type."
  [event-type ch]
  (unsub event-pub event-type ch)
  (close! ch))

;; Server State

(defonce ^:private server-state (atom nil))

;; Public API

(defn start-server!
  "Start the channel server for Emacs connections."
  [{:keys [type path port] :or {type :unix} :as opts}]
  (if @server-state
    (do
      (log/warn "Server already running")
      @server-state)
    (let [on-message (fn [msg client-id]
                       (log/debug "Received from" client-id ":" msg)
                       (when-let [type-str (get msg "type")]
                         (publish! (assoc msg :type (keyword type-str) :client-id client-id))))
          on-connect (fn [client-id]
                       (log/info "Client connected:" client-id))
          server (t/start-server! (merge opts
                                         {:type type
                                          :on-message on-message
                                          :on-connect on-connect}))]
      (log/info "Channel server started" (if (= type :unix)
                                           (str "on socket " (or path "/tmp/hive-mcp-channel.sock"))
                                           (str "on TCP port " (or port 9998))))
      (reset! server-state {:server server
                            :type type
                            :path (when (= type :unix) (or path "/tmp/hive-mcp-channel.sock"))
                            :port (when (= type :tcp) (or port 9998))
                            :running (:running? server)})  ; Expose transport running? atom
      @server-state)))

(defn stop-server!
  "Stop the channel server. No-op if coordinator is running."
  []
  (when-let [{:keys [server]} @server-state]
    (if (:coordinator-running? @server-state)
      (log/warn "stop-server! called but coordinator is running - ignoring to protect connections")
      (do
        (t/stop-server! server)
        (reset! server-state nil)
        (log/info "Server stopped")))))

(defn force-stop-server!
  "Force stop the channel server, bypassing coordinator guard."
  []
  (when-let [{:keys [server]} @server-state]
    (t/stop-server! server)
    (reset! server-state nil)
    (log/info "Server force-stopped")))

(defn mark-coordinator-running!
  "Mark coordinator as running, protecting server from test fixture cleanup."
  []
  (swap! server-state assoc :coordinator-running? true)
  (log/info "Channel server marked as coordinator-owned"))

(defn broadcast!
  "Send message to all connected clients."
  [msg]
  (when-let [{:keys [server]} @server-state]
    (t/broadcast! server msg)))

(defn server-connected?
  "Check if the channel server is running and has connected clients."
  []
  (when-let [{:keys [server]} @server-state]
    (and (t/server-running? server)
         (pos? (or (t/client-count server) 0)))))

(defn client-count
  "Return number of connected clients."
  []
  (when-let [{:keys [server]} @server-state]
    (t/client-count server)))

(def envelope-keys
  "Envelope keys `emit-event!` stamps onto every event it publishes.

   Emitted events are JSON-serialised to WebSocket clients, so payload and
   envelope keys are STRINGS. `:type` is additionally carried as a keyword
   because `publish!` routes the internal pub on `(:type event)`.

   Consumers (and tests) must read the envelope through this map rather than
   restating the literal wire keys."
  {:type "type"
   :timestamp "timestamp"})

;; Convenience Functions

(defn emit-event!
  "Emit an event to all connected clients and local subscribers.

   Payload keys of DATA are stringified; the envelope adds the keys of
   `envelope-keys` — the event type name and an epoch-millis timestamp —
   plus a keyword `:type` for internal pub routing."
  [event-type data]
  (let [string-data (into {} (map (fn [[k v]] [(name k) v])) data)
        event (assoc string-data
                     (:type envelope-keys) (name event-type)
                     (:timestamp envelope-keys) (System/currentTimeMillis)
                     :type event-type)]
    (publish! event)
    (broadcast! event)))

(comment
  ;; Development REPL examples

  ;; Start server (Unix socket - default)
  (start-server! {:type :unix})
  ;; or with custom path
  (start-server! {:type :unix :path "/tmp/my-channel.sock"})
  ;; or TCP
  (start-server! {:type :tcp :port 9998})

  ;; Check status
  (server-connected?)
  (client-count)

  ;; Test broadcast
  (broadcast! {:type :hivemind-progress
               :data {:agent-id "test" :message "hello"}})

  ;; Stop server
  (stop-server!))

;; MCP Tool Definitions

(def channel-tools "Channel MCP tools (currently empty)." [])
