(ns hive-mcp.transport.websocket
  "WebSocket transport for bidirectional MCP communication."
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [manifold.stream :as s]
            [clojure.core.async :as async :refer [go-loop <!]]
            [clojure.data.json :as json]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]])
  (:import [java.io File]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; State
(defonce ^:private server-atom (atom nil))
(defonce ^:private clients (atom #{}))

;; Lockfile for Claude discovery
(defn- lockfile-path [port]
  (str (System/getProperty "user.home") "/.claude/ide/" port ".lock"))

(defn- create-lockfile!
  "Create Claude IDE lockfile for WebSocket discovery.
   Returns true on success, false on failure (with error logged)."
  [port project-dir]
  (try
    (let [dir (File. (str (System/getProperty "user.home") "/.claude/ide/"))
          lockfile (File. (lockfile-path port))]
      (.mkdirs dir)
      (spit lockfile (json/write-str {:pid (.pid (java.lang.ProcessHandle/current))
                                      :workspaceFolders [(or project-dir (System/getProperty "user.dir"))]
                                      :ideName "hive-mcp"
                                      :transport "ws"}))
      (log/info "Created lockfile:" (.getPath lockfile))
      true)
    (catch Exception e
      (log/error e "Failed to create lockfile - Claude IDE discovery will not work")
      false)))

(defn- remove-lockfile! [port]
  (let [f (File. (lockfile-path port))]
    (when (.exists f) (.delete f))))

(defn- register-shutdown-hook!
  "Register JVM shutdown hook to clean up lockfile on crash/exit."
  [port]
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable (fn []
                                         (log/debug "Shutdown hook: removing lockfile")
                                         (remove-lockfile! port)))))

;; WebSocket handler
(defn- ws-handler [input-ch output-ch]
  (fn [req]
    (let [socket @(http/websocket-connection req)]
      (log/info "WebSocket client connected")
      (swap! clients conj socket)

      ;; Incoming → input-ch
      (s/consume (fn [msg]
                   (when-let [parsed (rescue nil (json/read-str msg :key-fn keyword))]
                     (async/>!! input-ch parsed)))
                 socket)

      ;; output-ch → outgoing
      (go-loop []
        (when-let [msg (<! output-ch)]
          (when (s/stream? socket)
            @(s/put! socket (json/write-str msg)))
          (recur)))

      ;; Cleanup on close
      (s/on-closed socket #(swap! clients disj socket))
      socket)))

;; Public API
(defn start-server! [{:keys [port project-dir input-ch output-ch]}]
  (let [handler (ws-handler input-ch output-ch)
        server (http/start-server handler {:port (or port 0)})
        actual-port (netty/port server)]
    (create-lockfile! actual-port project-dir)
    (register-shutdown-hook! actual-port)
    (reset! server-atom {:server server :port actual-port})
    (log/info "WebSocket server started on port" actual-port)
    actual-port))

(defn stop-server! []
  (when-let [{:keys [server port]} @server-atom]
    (.close server)
    (remove-lockfile! port)
    (reset! server-atom nil)))

(defn notify!
  "Send JSON-RPC 2.0 notification to all connected WebSocket clients."
  [method params]
  (let [msg (json/write-str {:jsonrpc "2.0" :method method :params params})]
    (doseq [client @clients]
      (when (s/stream? client)
        @(s/put! client msg)))))

(defn connected? [] (boolean (seq @clients)))
