(ns hive-mcp.transport.olympus
  "Dedicated WebSocket server for Olympus UI on port 7911.

   ADR: Separate from Emacs channel (port 9999) because:
   1. Different protocol: keyword types (:agents) vs string ('hivemind-progress')
   2. Snapshot on connect: browser refresh needs full state
   3. Different consumers: React UI vs elisp

   Event Protocol (what Olympus expects):
   {:type :agents :data [{:id \"...\" :status :working ...}]}
   {:type :wave-update :wave-id \"...\" :task-idx 0 :status :completed}
   {:type :kg-snapshot :entries [...] :edges [...]}

   Lifecycle:
   - start! called from server.clj during MCP startup
   - stop! called on shutdown
   - Emits snapshot on each new client connection"

  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datascript.core :as d-core]
            [taoensso.timbre :as log]
            [hive-mcp.dns.result :as result]
            [hive-mcp.config.core :as config]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.swarm.datascript.connection :as ds-conn]
            [hive-mcp.project.tree :as project-tree]
            [hive-mcp.concurrency.pool :as pool]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare broadcast!)

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private server-atom (atom nil))
(defonce ^:private clients (atom #{}))

;; DS State Bridge: Throttled DataScript -> WebSocket push.
;; Uses plain Java concurrency (LinkedBlockingQueue + daemon Thread)
;; to avoid core.async executor compatibility issues.
(defonce ^:private ds-bridge-queue
  (java.util.concurrent.LinkedBlockingQueue. 100))
(defonce ^:private ds-bridge-thread (atom nil))

;; Channel subscription tracking for proper cleanup
(defonce ^:private channel-subs (atom []))

(def ^:private bridge-throttle-ms
  "Batching window for DS changes before pushing to clients.
   200ms balances responsiveness vs flood prevention."
  200)

;; =============================================================================
;; JSON Serialization Helpers
;; =============================================================================

(defn- serialize-date
  "Convert java.util.Date to ISO timestamp string for JSON serialization."
  [d]
  (when d
    (if (instance? java.util.Date d)
      (.format java.time.format.DateTimeFormatter/ISO_INSTANT
               (.toInstant ^java.util.Date d))
      (str d))))

(defn- serialize-ref
  "Convert DataScript entity ref to ID string."
  [ref]
  (cond
    (nil? ref) nil
    (map? ref) (or (:db/id ref) (str ref))
    :else (str ref)))

;; =============================================================================
;; Snapshot Builders (Query DataScript for current state)
;; =============================================================================

(defn- build-agents-snapshot
  "Build agents snapshot from DataScript.
   Returns vector of agent maps for Olympus UI.
   All values are JSON-serializable (dates as ISO strings, refs as IDs)."
  []
  (result/rescue []
                 (->> (ds-queries/get-all-slaves)
                      (map (fn [slave]
                             {:id (:slave/id slave)
                              :name (:slave/name slave)
                              :type (if (= 0 (:slave/depth slave)) :coordinator :ling)
                              :status (some-> (:slave/status slave) name)
                              :project-id (:slave/project-id slave)
                              :cwd (:slave/cwd slave)
                              :presets (vec (:slave/presets slave))
                              :parent (:slave/parent slave)
                              :current-task (:slave/current-task slave)
                              :tasks-completed (or (:slave/tasks-completed slave) 0)
                              :created-at (serialize-date (:slave/created-at slave))}))
                      vec)))

(defn- build-waves-snapshot
  "Build waves snapshot from DataScript.
   Returns map of wave-id -> wave state.
   All values are JSON-serializable."
  []
  (result/rescue {}
    ;; Query all waves from DataScript
                 (let [c (ds-conn/ensure-conn)
                       db @c
                       wave-eids (d-core/q '[:find [?e ...]
                                             :where [?e :wave/id _]]
                                           db)]
                   (->> wave-eids
                        (map #(d-core/entity db %))
                        (map (fn [e]
                               (let [wave-id (:wave/id e)]
                                 [wave-id
                                  {:id wave-id
                                   :plan-id (serialize-ref (:wave/plan e))
                                   :total-tasks (:wave/total-tasks e)
                                   :concurrency (:wave/concurrency e)
                                   :active-count (or (:wave/active-count e) 0)
                                   :completed-count (or (:wave/completed-count e) 0)
                                   :failed-count (or (:wave/failed-count e) 0)
                                   :status (some-> (:wave/status e) name)
                                   :started-at (serialize-date (:wave/started-at e))
                                   :completed-at (serialize-date (:wave/completed-at e))}])))
                        (into {})))))

(defn- build-kg-snapshot
  "Build knowledge graph snapshot.
   Returns {:entries [...] :edges [...]} for recent KG state.
   Note: This is a lightweight snapshot - full KG may be too large."
  []
  (result/rescue {:entries [] :edges [] :entry-count 0}
    ;; Query recent memory entries (last 50) via memory tools
                 (let [query-fn (requiring-resolve 'hive-mcp.tools.memory.crud/query-by-metadata)
                       recent-entries (when query-fn
                                        (take 50 (query-fn {:limit 50})))]
                   {:entries (or recent-entries [])
                    :edges []  ;; KG edges would come from kg/edges query - simplified for now
                    :entry-count (count recent-entries)})))

(defn- build-project-tree-snapshot
  "Build project tree snapshot from project.tree DataScript.
   Returns hierarchical structure with ling counts per project.

   HCR Wave 5: Enables Olympus UI project tree navigator."
  []
  (result/rescue {:projects [] :roots [] :children {} :total 0}
                 (let [all-projects (project-tree/query-all-projects)
                       tree-data (project-tree/build-project-tree all-projects)
          ;; Get agents to count lings per project
                       agents (build-agents-snapshot)
                       ling-counts (reduce (fn [acc a]
                                             (if-let [pid (:project-id a)]
                                               (update acc pid (fnil inc 0))
                                               acc))
                                           {}
                                           agents)]
                   {:projects (mapv (fn [p]
                                      {:id (:project/id p)
                                       :path (:project/path p)
                                       :type (some-> (:project/type p) name)
                                       :parent-id (:project/parent-id p)
                                       :tags (vec (or (:project/tags p) []))
                                       :git-root (:project/git-root p)
                                       :ling-count (get ling-counts (:project/id p) 0)
                                       :last-scanned (serialize-date (:project/last-scanned p))})
                                    all-projects)
                    :roots (:roots tree-data)
                    :children (:children tree-data)
                    :total (count all-projects)})))

(defn build-full-snapshot
  "Build complete state snapshot for new client connection.

   Returns:
   {:type :init-snapshot
    :timestamp <ms>
    :data {:agents [...] :waves {...} :kg {...} :project-tree {...}}}"
  []
  {:type :init-snapshot
   :timestamp (System/currentTimeMillis)
   :data {:agents (build-agents-snapshot)
          :waves (build-waves-snapshot)
          :kg (build-kg-snapshot)
          :project-tree (build-project-tree-snapshot)}})

;; =============================================================================
;; DataScript State Bridge (Auto-push DS changes to Olympus clients)
;; =============================================================================

(defn- classify-tx-changes
  "Classify which entity types changed in a DataScript transaction.
   Examines tx-report datoms and returns a set of changed domains.

   Returns: #{:agents :waves} (subset based on what actually changed)"
  [tx-report]
  (let [datoms (:tx-data tx-report)]
    (reduce (fn [acc datom]
              (let [attr-ns (some-> (.-a datom) namespace)]
                (case attr-ns
                  "slave" (conj acc :agents)
                  "wave"  (conj acc :waves)
                  ;; Ignore other namespaces (olympus, kanban, etc.)
                  acc)))
            #{}
            datoms)))

(defn- flush-state-patch!
  "Flush accumulated DS changes to all connected Olympus clients.
   Builds snapshots only for changed domains (selective rebuild).
   No-ops if no clients are connected (avoid wasted CPU)."
  [changed-domains]
  (when (and (seq changed-domains) (seq @clients))
    (result/rescue nil
                   (let [data (cond-> {}
                                (:agents changed-domains) (assoc :agents (build-agents-snapshot))
                                (:waves changed-domains)  (assoc :waves (build-waves-snapshot)))]
                     (when (seq data)
                       (broadcast! {:type :state-patch
                                    :timestamp (System/currentTimeMillis)
                                    :data data
                                    :changed (vec changed-domains)}))))))

(defn- start-bridge-loop!
  "Start background daemon thread that batches DS changes and flushes to clients.

   Uses plain Java concurrency (LinkedBlockingQueue + Thread) to avoid
   core.async executor compatibility issues.

   Algorithm:
   1. .take blocks until first change arrives (no busy-wait)
   2. .poll with timeout accumulates additional changes within throttle window
   3. Flush accumulated changes as a single :state-patch event
   4. Repeat

   This prevents flooding clients during rapid DS transactions
   (e.g., bulk agent spawns) while keeping latency under 200ms."
  []
  (when-not @ds-bridge-thread
    (.clear ds-bridge-queue)
    (let [thread (Thread.
                  (fn []
                    (try ; boundary — thread lifecycle with InterruptedException
                      (loop []
                        ;; Phase 1: Block until first change (no busy-wait)
                        (let [first-changes (.take ds-bridge-queue)]
                          (when first-changes
                            ;; Phase 2: Accumulate more changes within throttle window
                            (let [all-changes
                                  (loop [acc first-changes]
                                    (if-let [more (.poll ds-bridge-queue
                                                         bridge-throttle-ms
                                                         java.util.concurrent.TimeUnit/MILLISECONDS)]
                                      (recur (into acc more))
                                      acc))]
                              ;; Phase 3: Flush
                              (flush-state-patch! all-changes))
                            (recur))))
                      (catch InterruptedException _
                        (log/debug "DS bridge thread interrupted - shutting down"))
                      (catch Exception e
                        (log/warn "DS bridge thread error:" (.getMessage e))))))]
      (.setDaemon thread true)
      (.setName thread "olympus-ds-bridge")
      (.start thread)
      (reset! ds-bridge-thread thread)
      (log/debug "DS bridge thread started"))))

(defn- stop-bridge-loop!
  "Interrupt and stop the bridge thread."
  []
  (when-let [^Thread thread @ds-bridge-thread]
    (.interrupt thread)
    (reset! ds-bridge-thread nil)
    (.clear ds-bridge-queue)
    (log/debug "DS bridge thread stopped")))

(defn- on-ds-transaction!
  "DataScript listen! callback. Classifies changes and enqueues for batching.
   Fast path: returns immediately if no relevant changes detected.
   Uses non-blocking .offer so DS transactions are never delayed."
  [tx-report]
  (let [changes (classify-tx-changes tx-report)]
    (when (seq changes)
      (.offer ds-bridge-queue changes))))

(defn wire-ds-state-bridge!
  "Install DataScript listener that auto-pushes state changes to Olympus clients.

   Uses d/listen! on the swarm DataScript connection to detect transactions
   affecting agents (:slave/*) and waves (:wave/*). Changes are batched
   within a 200ms window and pushed as :state-patch events.

   Event format pushed to clients:
   {:type :state-patch
    :timestamp <ms>
    :data {:agents [...] :waves {...}}   ;; only changed domains included
    :changed [:agents :waves]}           ;; which domains changed

   Called from wire-hivemind-events! during server startup."
  []
  (result/rescue nil
                 (start-bridge-loop!)
                 (let [conn (ds-conn/ensure-conn)]
                   (d-core/listen! conn :olympus-state-bridge on-ds-transaction!)
                   (log/info "Olympus DS state bridge wired - auto-pushing DataScript changes"))))

(defn stop-ds-state-bridge!
  "Remove DataScript listener and stop bridge loop.
   Called from stop! during server shutdown."
  []
  (result/rescue nil
                 (when-let [conn (result/rescue nil (ds-conn/get-conn))]
                   (d-core/unlisten! conn :olympus-state-bridge))
                 (stop-bridge-loop!)
                 (log/debug "DS state bridge stopped")))

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
                                      :agents {:type :agents :data (build-agents-snapshot)}
                                      :waves {:type :waves :data (build-waves-snapshot)}
                                      :kg {:type :kg-snapshot :data (build-kg-snapshot)}
                                      :project-tree {:type :project-tree :data (build-project-tree-snapshot)}
                                      {:type :error :message (str "Unknown view: " view)})]
                       (send-to-client! client snapshot))

        ;; Ping/pong for keepalive
                     "ping"
                     (send-to-client! client {:type :pong :timestamp (System/currentTimeMillis)})

        ;; Unknown - log and ignore
                     (log/debug "Unknown Olympus message type:" (:type parsed))))))

(defn- ws-connection-handler
  "Handle WebSocket connection from Olympus UI.
   Sends full state snapshot immediately on connect."
  [req]
  (d/let-flow [socket (http/websocket-connection req)]
              (let [client-id (str "olympus-" (System/currentTimeMillis) "-" (rand-int 10000))]
                (log/info "Olympus client connected:" client-id)
                (swap! clients conj socket)

      ;; Send full snapshot immediately (browser refresh = needs full state)
                (send-to-client! socket (build-full-snapshot))

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

(defn- json-response
  "Helper to create JSON HTTP response."
  [status data]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}
   :body (json/write-str data)})

;; =============================================================================
;; Static File Serving (Olympus Web UI assets)
;; =============================================================================

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

(defonce ^:private static-root-cache (atom nil))

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
                  :clients (count @clients)
                  :timestamp (System/currentTimeMillis)}))

(defn- route-snapshot
  "Full state snapshot: GET /api/snapshot"
  []
  (json-response 200 (build-full-snapshot)))

(defn- route-agents
  "Agents snapshot: GET /api/agents"
  []
  (json-response 200
                 {:type :agents
                  :timestamp (System/currentTimeMillis)
                  :data (build-agents-snapshot)}))

(defn- route-waves
  "Waves snapshot: GET /api/waves"
  []
  (json-response 200
                 {:type :waves
                  :timestamp (System/currentTimeMillis)
                  :data (build-waves-snapshot)}))

(defn- route-kg
  "KG/Memory snapshot: GET /api/kg"
  []
  (json-response 200
                 {:type :kg-snapshot
                  :timestamp (System/currentTimeMillis)
                  :data (build-kg-snapshot)}))

(defn- route-project-tree
  "Project tree snapshot: GET /api/project-tree"
  []
  (json-response 200
                 {:type :project-tree
                  :timestamp (System/currentTimeMillis)
                  :data (build-project-tree-snapshot)}))

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
  (ws-connection-handler req))

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
      (websocket-upgrade? req)                          (ws-connection-handler req)
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
  (stop-ds-state-bridge!)
  ;; Unsubscribe from channel pub/sub to prevent pub leaks
  (doseq [[event-type sub-ch] @channel-subs]
    (result/rescue nil
                   (when-let [unsub-fn (requiring-resolve 'hive-mcp.channel.core/unsubscribe!)]
                     (unsub-fn event-type sub-ch))))
  (reset! channel-subs [])
  (when-let [{:keys [server port]} @server-atom]
    (.close server)
    (reset! server-atom nil)
    (reset! clients #{})
    (log/info "Olympus WebSocket server stopped (was on port" port ")")
    true))

(defn status
  "Get Olympus WS server status including DS bridge state."
  []
  {:running? (boolean @server-atom)
   :port (:port @server-atom)
   :clients (count @clients)
   :connected? (pos? (count @clients))
   :ds-bridge {:active? (boolean @ds-bridge-thread)
               :queue-size (.size ds-bridge-queue)}})

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
                     (swap! channel-subs conj [:memory-added sub-ch])
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

    ;; 3. Wire DataScript state bridge (auto-push DS changes to clients)
    ;;    Installs d/listen! on swarm conn, batches changes, pushes :state-patch.
                 (wire-ds-state-bridge!)

                 (log/info "Olympus event wiring complete - :olympus-broadcast effect + memory sub + DS state bridge")))

(comment
  ;; REPL testing
  (start!)
  (status)
  (broadcast! {:type :agents :data [{:id "test-1" :status :working}]})
  (stop!)

  ;; Test snapshot
  (build-full-snapshot)
  (build-agents-snapshot)
  (build-waves-snapshot)

  ;; DS State Bridge testing
  (wire-ds-state-bridge!)
  ;; Verify listener is installed:
  (status) ;; => {:ds-bridge {:active? true :channel? true}}
  ;; Trigger a DS transaction to test auto-push:
  (require '[datascript.core :as d])
  (d/transact! (ds-conn/ensure-conn)
               [{:slave/id "test-bridge" :slave/status :idle :slave/name "test"}])
  ;; Should see :state-patch broadcast in logs if clients connected
  (stop-ds-state-bridge!))
