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
            [taoensso.timbre :as log]
            [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; WebSocketChannel
;;; ============================================================================

(defn- event-name
  "Coerce :event-type to its string name for hivemind-* keyword construction.
   Returns nil and warns when event-type is missing — refusing delivery beats
   throwing NPE inside a debug-only catch (incident 2026-05-11, ENGINE-L0.1)."
  [event-type channel-id event]
  (if (nil? event-type)
    (do (log/warn "[" channel-id "] Dropping event with nil :event-type — keys:"
                  (vec (keys event)))
        nil)
    (name event-type)))

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
      (when-let [ename (event-name event-type :websocket event)]
        (when-let [ws-emit (requiring-resolve 'hive-mcp.channel.websocket/emit!)]
          (ws-emit (keyword (str "hivemind-" ename))
                   (dissoc event :type :event-type))))
      (catch Exception e
        (log/debug "[WebSocketChannel] Delivery failed:" (.getMessage e))))))

;;; ============================================================================
;;; CoreAsyncChannel
;;; ============================================================================

(defrecord CoreAsyncChannel []
  dc/IDeliveryChannel
  (channel-id [_this] :core-async)

  (available? [_this]
    true)

  (deliver! [_this {:keys [agent-id event-type timestamp project-id] :as event}]
    (try
      (when-let [ename (event-name event-type :core-async event)]
        (when-let [publish-fn (requiring-resolve 'hive-mcp.channel.core/publish!)]
          (publish-fn {:type (keyword (str "hivemind-" ename))
                       :agent-id agent-id
                       :timestamp timestamp
                       :project-id project-id
                       :data (dissoc event :agent-id :event-type :timestamp :project-id :type)})))
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
      (when-let [ename (event-name event-type :channel-broadcast event)]
        (when-let [broadcast-fn (requiring-resolve 'hive-mcp.channel.core/broadcast!)]
          (broadcast-fn {:type (keyword (str "hivemind-" ename))
                         :agent-id agent-id
                         :timestamp timestamp
                         :project-id project-id
                         :data (dissoc event :agent-id :event-type :timestamp :project-id :type)})))
      (catch Exception e
        (log/debug "[ChannelBroadcastChannel] Delivery failed:" (.getMessage e))))))

;;; ============================================================================
;;; OlympusChannel
;;; ============================================================================

(defrecord OlympusChannel []
  dc/IDeliveryChannel
  (channel-id [_this] :olympus)

  (available? [_this]
    true)

  (deliver! [_this {:keys [agent-id event-type message task] :as event}]
    (try
      (when-let [ename (event-name event-type :olympus event)]
        (when-let [emit-fn (requiring-resolve 'hive-mcp.transport.olympus/emit-hivemind-shout!)]
          (emit-fn {:agent-id agent-id
                    :event-type ename
                    :message message
                    :task task
                    :data (dissoc event :agent-id :event-type :message :task :type)})))
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
;;; NatsChannel — frontend-agnostic delivery via NATS bridge
;;; ============================================================================

(defrecord NatsChannel []
  dc/IDeliveryChannel
  (channel-id [_this] :nats)

  (available? [_this]
    (try
      (when-let [connected? (requiring-resolve 'hive-mcp.nats.client/connected?)]
        (boolean (connected?)))
      (catch Exception _ false)))

  (deliver! [_this {:keys [agent-id event-type project-id timestamp via] :as event}]
    ;; Loopback guard (incident 2026-05-11): when the bridge fans out a shout
    ;; that arrived FROM NATS, it tags the payload with :via :nats-inbound.
    ;; Republishing inbound traffic to NATS spawns a 100Hz ping-pong: NATS →
    ;; fanout → NatsChannel republish → NATS … OOMs the JVM in minutes.
    (when-not (= via :nats-inbound)
      (try
        (when-let [publish-fn (requiring-resolve 'hive-mcp.nats.bridge/publish-shout!)]
          (publish-fn {:agent-id   agent-id
                       :event-type event-type
                       :project-id (or project-id "global")
                       :timestamp  (or timestamp (System/currentTimeMillis))
                       :message    (:message event)
                       :task       (:task event)
                       :data       (dissoc event :agent-id :event-type
                                           :project-id :timestamp :message
                                           :task :type :via)}))
        (catch Exception e
          (log/debug "[NatsChannel] Delivery failed:" (.getMessage e)))))))

(defrecord FileTailChannel [path]
  dc/IDeliveryChannel
  (channel-id [_this] :file-tail)

  (available? [_this]
    (try
      (let [parent (.getParentFile (java.io.File. ^String path))]
        (or (nil? parent) (.exists parent) (.mkdirs parent)))
      (catch Exception _ false)))

  (deliver! [_this {:keys [agent-id event-type project-id timestamp] :as event}]
    (try
      (let [line (pr-str
                  {:agent-id   agent-id
                   :event-type event-type
                   :project-id (or project-id "global")
                   :timestamp  (or timestamp (System/currentTimeMillis))
                   :message    (:message event)
                   :task       (:task event)
                   :data       (dissoc event :agent-id :event-type
                                       :project-id :timestamp :message
                                       :task :type)})]
        (locking path
          (with-open [w (java.io.FileWriter. ^String path true)]
            (.write w (str line "\n")))))
      (catch Exception e
        (log/debug "[FileTailChannel] Delivery failed:" (.getMessage e))))))

;;; ============================================================================
;;; Factory Functions
;;; ============================================================================

(defn create-websocket-channel [] (->WebSocketChannel))
(defn create-core-async-channel [] (->CoreAsyncChannel))
(defn create-channel-broadcast-channel [] (->ChannelBroadcastChannel))
(defn create-olympus-channel [] (->OlympusChannel))
(defn create-piggyback-channel [] (->PiggybackChannel))

(defn create-nats-channel [] (->NatsChannel))

(defn default-file-tail-path
  "Resolve a frontend-agnostic default path for FileTailChannel.
   Order: HIVE_HIVEMIND_SHOUTS_PATH env > $XDG_RUNTIME_DIR/hivemind-shouts.jsonl
   > $TMPDIR/hivemind-shouts.jsonl > /tmp/hivemind-shouts.jsonl"
  []
  (or (System/getenv "HIVE_HIVEMIND_SHOUTS_PATH")
      (when-let [xdg (System/getenv "XDG_RUNTIME_DIR")]
        (str xdg "/hivemind-shouts.jsonl"))
      (when-let [tmp (System/getenv "TMPDIR")]
        (str tmp "/hivemind-shouts.jsonl"))
      "/tmp/hivemind-shouts.jsonl"))

(defn create-file-tail-channel
  ([] (create-file-tail-channel (default-file-tail-path)))
  ([path] (->FileTailChannel path)))

(def ^:private channel-factories
  "Static DI table for IDeliveryChannel impls.
   Each entry: channel-id -> 0-arity factory fn returning IDeliveryChannel.
   New impls slot in here without touching register-default-channels!."
  {:websocket         create-websocket-channel
   :core-async        create-core-async-channel
   :channel-broadcast create-channel-broadcast-channel
   :olympus           create-olympus-channel
   :piggyback         create-piggyback-channel
   :nats              create-nats-channel
   :file-tail         create-file-tail-channel})

(def default-fallback-order
  "Frontend-agnostic ordering: highest-priority (most-broadcast) first.
   Used when callers want a deterministic fallback chain rather than
   broadcast fanout. Emacs-specific channels intentionally lower than
   NATS / piggyback / file-tail so a headless host still has working
   delivery without an editor attached."
  [:nats :piggyback :file-tail
   :websocket :channel-broadcast :core-async :olympus])

(defn- enabled-channel-ids
  "Resolve which channel-ids should be registered. Order:
   1. Explicit `ids` arg (set of keywords).
   2. HIVE_DELIVERY_CHANNELS env var: comma-separated keyword names.
   3. All keys of `channel-factories` (default: every impl)."
  [ids]
  (cond
    (set? ids) ids
    :else
    (if-let [env (System/getenv "HIVE_DELIVERY_CHANNELS")]
      (->> (clojure.string/split env #",")
           (map clojure.string/trim)
           (remove clojure.string/blank?)
           (map keyword)
           set)
      (set (keys channel-factories)))))

(defn register-default-channels!
  "Register delivery channels chosen via `channel-factories`.

   Backend choice obeys DIP/ISP/LSP:
   - All channels share the same IDeliveryChannel surface (LSP).
   - Each impl only depends on its transport (ISP — channels don't see each other).
   - Status + shout pipelines depend on the protocol, not the impls (DIP).

   Selection precedence: explicit `ids` set > HIVE_DELIVERY_CHANNELS env >
   every key in `channel-factories`."
  ([] (register-default-channels! nil))
  ([ids]
   (let [chosen (enabled-channel-ids ids)
         registered (atom [])]
     (doseq [[id factory] channel-factories
             :when (contains? chosen id)]
       (try
         (let [ch (factory)]
           (dc/register-channel! ch)
           (swap! registered conj id))
         (catch Throwable t
           (log/warn t "[DeliveryChannels] Skipping" id "— factory threw:"
                     (.getMessage t)))))
     (log/info "[DeliveryChannels] Registered:" @registered
               "(available now:" (mapv (fn [ch] [(dc/channel-id ch)
                                                 (dc/available? ch)])
                                       (dc/get-channels))
               ")")
     @registered)))