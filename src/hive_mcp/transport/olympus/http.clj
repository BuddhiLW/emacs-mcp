(ns hive-mcp.transport.olympus.http
  "HTTP routing, static file serving, and server lifecycle (start!/stop!/status)
   for the Olympus WebSocket transport.

   Owns the `server-atom` lifecycle atom and the static-root-cache. Delegates
   WebSocket upgrades to stream/ws-connection-handler and snapshot building
   to the snapshots module.

   Split from transport/olympus.clj (hotspot #14 refactor, plan
   refactor-hotspots-p0.md line 99-104)."
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-mcp.dns.result :as result]
            [hive-mcp.config.core :as config]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.transport.olympus.snapshots :as snap]
            [hive-mcp.transport.olympus.stream :as stream]
            [hive-mcp.transport.olympus.state-bridge :as state-bridge]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private server-atom (atom nil))

(defonce ^:private static-root-cache (atom nil))

;; =============================================================================
;; JSON / static file helpers
;; =============================================================================

(defn- json-response
  "Helper to create JSON HTTP response."
  [status data]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}
   :body (json/write-str data)})

(def ^:private content-types
  "MIME types for static file serving."
  {"html" "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "application/javascript; charset=utf-8"
   "json" "application/json"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "ico"  "image/x-icon"
   "map"  "application/json"})

(defn- resolve-static-root
  "Find the olympus-web-ui/resources/public directory.
   Searches relative to project root (cwd)."
  []
  (let [candidates ["olympus-web-ui/resources/public"
                    "resources/public"]
        found (some (fn [p]
                      (let [f (io/file p)]
                        (when (.isDirectory f) f)))
                    candidates)]
    (when found
      (log/debug "Olympus static root:" (.getAbsolutePath found)))
    found))

(defn- get-static-root
  "Get the static root directory, resolving lazily on first call."
  []
  (or @static-root-cache
      (reset! static-root-cache (resolve-static-root))))

(defn- websocket-upgrade?
  "Check if the request is a WebSocket upgrade request."
  [req]
  (let [upgrade (get-in req [:headers "upgrade"] "")]
    (= "websocket" (str/lower-case upgrade))))

(defn- serve-static-file
  "Serve a static file from the Olympus web UI public directory.
   Returns nil if file not found."
  [uri]
  (when-let [root (get-static-root)]
    (let [;; Normalize path: / -> /index.html
          path (if (= uri "/") "/index.html" uri)
          ;; Security: prevent directory traversal
          clean-path (str/replace path #"\.\." "")
          file (io/file root (subs clean-path 1))]
      (when (and (.exists file) (.isFile file) (.canRead file))
        (let [ext (last (str/split (.getName file) #"\."))
              content-type (get content-types ext "application/octet-stream")]
          {:status 200
           :headers {"Content-Type" content-type
                     "Cache-Control" "no-cache"
                     "Access-Control-Allow-Origin" "*"}
           :body file})))))

;; =============================================================================
;; Route Handlers (SRP: one fn per route, pure response construction)
;; =============================================================================

(defn- route-cors-preflight
  "CORS preflight response for OPTIONS requests."
  []
  {:status 204
   :headers {"Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}})

(defn- route-health
  "Health check endpoint: GET /health"
  []
  (json-response 200
                 {:status "healthy"
                  :service "olympus-ws"
                  :clients (count @stream/clients)
                  :timestamp (System/currentTimeMillis)}))

(defn- route-snapshot
  "Full state snapshot: GET /api/snapshot"
  []
  (json-response 200 (snap/build-full-snapshot)))

(defn- route-agents
  "Agents snapshot: GET /api/agents"
  []
  (json-response 200
                 {:type :agents
                  :timestamp (System/currentTimeMillis)
                  :data (snap/build-agents-snapshot)}))

(defn- route-waves
  "Waves snapshot: GET /api/waves"
  []
  (json-response 200
                 {:type :waves
                  :timestamp (System/currentTimeMillis)
                  :data (snap/build-waves-snapshot)}))

(defn- route-kg
  "KG/Memory snapshot: GET /api/kg"
  []
  (json-response 200
                 {:type :kg-snapshot
                  :timestamp (System/currentTimeMillis)
                  :data (snap/build-kg-snapshot)}))

(defn- route-project-tree
  "Project tree snapshot: GET /api/project-tree"
  []
  (json-response 200
                 {:type :project-tree
                  :timestamp (System/currentTimeMillis)
                  :data (snap/build-project-tree-snapshot)}))

(defn- route-stats
  "DataScript statistics: GET /api/stats
   Uses result/try-effect* for fallible DS query."
  []
  (let [r (result/try-effect* :ds/stats-failed
                              (ds-queries/db-stats))]
    (json-response 200
                   {:type :stats
                    :timestamp (System/currentTimeMillis)
                    :data (if (result/ok? r)
                            (:ok r)
                            {:error (:message r)})})))

(defn- route-ws
  "Explicit WebSocket endpoint: GET /ws"
  [req]
  (stream/ws-connection-handler req))

(defn- route-static
  "Static file serving with SPA fallback: GET /*
   Serves from olympus-web-ui/resources/public/."
  [uri]
  (or (serve-static-file uri)
      (serve-static-file "/index.html")
      (json-response 404
                     {:error "Olympus Web UI static files not found"
                      :hint "Ensure olympus-web-ui/resources/public/ exists with built JS"
                      :build-cmd "cd olympus-web-ui && npx shadow-cljs compile app"})))

(defn- route-not-allowed
  "Catch-all response for unsupported HTTP methods."
  []
  {:status 405
   :headers {"Content-Type" "text/plain"
             "Access-Control-Allow-Origin" "*"}
   :body "Method not allowed"})

(defn- http-handler
  "HTTP handler for Olympus WS server.
   Thin routing dispatch to named route handler fns.

   Routes:
     WebSocket upgrade  -> ws-connection-handler
     OPTIONS *          -> route-cors-preflight
     GET /health        -> route-health
     GET /api/snapshot  -> route-snapshot
     GET /api/agents    -> route-agents
     GET /api/waves     -> route-waves
     GET /api/kg        -> route-kg
     GET /api/project-tree -> route-project-tree
     GET /api/stats     -> route-stats
     GET /ws            -> route-ws
     GET /*             -> route-static
     *                  -> route-not-allowed"
  [req]
  (let [uri (:uri req)
        method (:request-method req)]
    (cond
      (websocket-upgrade? req)                          (stream/ws-connection-handler req)
      (= method :options)                               (route-cors-preflight)
      (and (= method :get) (= uri "/health"))           (route-health)
      (and (= method :get) (= uri "/api/snapshot"))     (route-snapshot)
      (and (= method :get) (= uri "/api/agents"))       (route-agents)
      (and (= method :get) (= uri "/api/waves"))        (route-waves)
      (and (= method :get) (= uri "/api/kg"))           (route-kg)
      (and (= method :get) (= uri "/api/project-tree")) (route-project-tree)
      (and (= method :get) (= uri "/api/stats"))        (route-stats)
      (and (= method :get) (= uri "/ws"))               (route-ws req)
      (= method :get)                                   (route-static uri)
      :else                                             (route-not-allowed))))

;; =============================================================================
;; Public API - Server Lifecycle
;; =============================================================================

(defn start!
  "Start Olympus WebSocket server on port 7911.

   Options:
     :port - Port number (default: 7911, env: HIVE_MCP_OLYMPUS_WS_PORT)

   Returns the actual port number."
  ([] (start! {}))
  ([{:keys [port]}]
   (let [port (or port
                  (config/get-service-value :olympus :ws-port
                                            :env "HIVE_MCP_OLYMPUS_WS_PORT"
                                            :parse parse-long
                                            :default 7911))]
     (if @server-atom
       (do
         (log/warn "Olympus WS server already running on port" (:port @server-atom))
         (:port @server-atom))
       (result/rescue nil
                      (let [server (http/start-server http-handler {:port port})
                            actual-port (netty/port server)]
                        (reset! server-atom {:server server :port actual-port})
                        (log/info "Olympus WebSocket server started on port" actual-port)
                        actual-port))))))

(defn stop!
  "Stop the Olympus WebSocket server, DS state bridge, and channel subscriptions."
  []
  (state-bridge/stop-ds-state-bridge!)
  ;; Unsubscribe from channel pub/sub to prevent pub leaks
  (doseq [[event-type sub-ch] @state-bridge/channel-subs]
    (result/rescue nil
                   (when-let [unsub-fn (requiring-resolve 'hive-mcp.channel.core/unsubscribe!)]
                     (unsub-fn event-type sub-ch))))
  (reset! state-bridge/channel-subs [])
  (when-let [{:keys [server port]} @server-atom]
    (.close server)
    (reset! server-atom nil)
    (reset! stream/clients #{})
    (log/info "Olympus WebSocket server stopped (was on port" port ")")
    true))

(defn status
  "Get Olympus WS server status including DS bridge state."
  []
  {:running? (boolean @server-atom)
   :port (:port @server-atom)
   :clients (count @stream/clients)
   :connected? (pos? (count @stream/clients))
   :ds-bridge {:active? (boolean @state-bridge/ds-bridge-thread)
               :queue-size (.size state-bridge/ds-bridge-queue)}})
