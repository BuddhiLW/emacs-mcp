(ns hive-mcp.channel.websocket
  "WebSocket channel for Emacs communication via Aleph, with /metrics and /health endpoints."
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.json :as json]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private server-atom (atom nil))
(defonce ^:private clients (atom #{}))

(defn- metrics-handler
  "Handle GET /metrics for Prometheus scraping."
  [_req]
  (try
    (require 'hive-mcp.telemetry.prometheus)
    (let [handler (resolve 'hive-mcp.telemetry.prometheus/metrics-handler)]
      (handler nil))
    (catch Exception e
      (log/warn "Prometheus metrics unavailable:" (.getMessage e))
      {:status 503
       :headers {"Content-Type" "text/plain"}
       :body "Prometheus metrics not initialized"})))

(defn- health-handler
  "Handle GET /health for health checks."
  [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:status "healthy"
                          :websocket {:clients (count @clients)
                                      :running? (boolean @server-atom)}
                          :timestamp (System/currentTimeMillis)})})

(defn- handle-message [client-id msg]
  (log/debug "Received from" client-id ":" msg)
  (when-let [handler (:on-message @server-atom)]
    (handler msg client-id)))

(defonce ^:private on-connect-hooks (atom []))

(defn add-on-connect-hook!
  "Register `(f emit-to)` to run when a client connects; `emit-to` is
   `(fn [event-type data])` targeting only that client. Returns hook count."
  [f]
  (count (swap! on-connect-hooks conj f)))

(defn clear-on-connect-hooks! []
  (reset! on-connect-hooks [])
  nil)

(defn emit-to!
  "Emit a typed event to a single client socket."
  [socket event-type data]
  (when-not (s/closed? socket)
    (d/catch (s/put! socket (json/write-str (merge {:type (name event-type)
                                                    :timestamp (System/currentTimeMillis)}
                                                   data)))
             (fn [e] (log/warn "emit-to! failed:" (.getMessage e)) nil))))

(defn- run-on-connect-hooks! [socket]
  (doseq [hook @on-connect-hooks]
    (rescue nil (hook (fn [event-type data] (emit-to! socket event-type data))))))

(defn- ws-connection-handler
  "Handle WebSocket connection upgrade."
  [req]
  (d/let-flow [socket (http/websocket-connection req)]
              (let [client-id (str "ws-" (System/currentTimeMillis) "-" (rand-int 10000))]
                (log/info "WebSocket client connected:" client-id)
                (swap! clients conj socket)
                (run-on-connect-hooks! socket)

                (s/consume (fn [raw]
                             (cond
                               (= raw "ping")
                               (s/put! socket "pong")

                               :else
                               (when-let [msg (rescue nil (json/read-str raw :key-fn keyword))]
                                 (handle-message client-id msg))))
                           socket)

                (s/on-closed socket
                             (fn []
                               (log/info "WebSocket client disconnected:" client-id)
                               (swap! clients disj socket)))

                socket)))

(defn- hybrid-handler
  "Hybrid HTTP/WebSocket handler routing to metrics, health, or WS upgrade."
  [req]
  (let [uri (:uri req)
        method (:request-method req)]
    (cond
      (and (= method :get) (= uri "/metrics"))
      (metrics-handler req)

      (and (= method :get) (= uri "/health"))
      (health-handler req)

      :else
      (ws-connection-handler req))))

(defn start!
  "Start WebSocket channel server with HTTP endpoints."
  [{:keys [port on-message] :or {port 9999}}]
  (if @server-atom
    (do
      (log/warn "WebSocket channel already running on port" (:port @server-atom))
      (:port @server-atom))
    (let [server (http/start-server hybrid-handler {:port port})
          actual-port (netty/port server)]
      (reset! server-atom {:server server
                           :port actual-port
                           :on-message on-message})
      (log/info "WebSocket channel started on port" actual-port
                "(endpoints: /metrics, /health, WebSocket)")
      actual-port)))

(defn stop!
  "Stop the WebSocket channel server."
  []
  (when-let [{:keys [server port]} @server-atom]
    (.close server)
    (reset! server-atom nil)
    (reset! clients #{})
    (log/info "WebSocket channel stopped (was on port" port ")")
    true))

(defn broadcast!
  "Broadcast JSON-encoded message to all connected WebSocket clients."
  [msg]
  (let [json-msg (json/write-str msg)
        active-clients @clients]
    (when (seq active-clients)
      (log/debug "Broadcasting to" (count active-clients) "clients:" (:type msg))
      (doseq [client active-clients]
        (when-not (s/closed? client)
          (d/catch
           (s/put! client json-msg)
           (fn [e]
             (log/warn "Broadcast failed:" (.getMessage e))
             (swap! clients disj client))))))))

(defn connected?
  "Returns true if at least one client is connected."
  []
  (boolean (seq @clients)))

(defn client-count
  "Returns number of connected clients."
  []
  (count @clients))

(defn status
  "Returns channel status map."
  []
  (let [port (:port @server-atom)]
    {:running? (boolean @server-atom)
     :port port
     :clients (count @clients)
     :connected? (connected?)
     :endpoints (when port
                  {:metrics (str "http://localhost:" port "/metrics")
                   :health (str "http://localhost:" port "/health")})}))

(defn emit!
  "Emit a typed event to all connected clients."
  [event-type data]
  (broadcast! (merge {:type (name event-type)
                      :timestamp (System/currentTimeMillis)}
                     data)))
