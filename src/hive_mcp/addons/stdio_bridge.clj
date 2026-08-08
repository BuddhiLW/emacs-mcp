(ns hive-mcp.addons.stdio-bridge
  "Concrete IMcpBridge implementation for stdio transport.

   Spawns an external MCP server as a subprocess, communicates via
   newline-delimited JSON-RPC 2.0 over stdin/stdout.

   Usage:
     (def b (->stdio-bridge :bridge/echo))
     (bridge/register-bridge! b {:command [\"python3\" \"scripts/echo-mcp-server.py\"]})
     (bridge/list-remote-tools (bridge/get-bridge :bridge/echo))
     (bridge/call-tool (bridge/get-bridge :bridge/echo) \"echo\" {\"message\" \"hi\"})
     (bridge/unregister-bridge! :bridge/echo)"
  (:require [hive-addon.protocol :as proto]
            [hive-mcp.addons.mcp-bridge :as bridge]
            [hive-mcp.dns.result :as r]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter]
           [java.util.concurrent.atomic AtomicLong]
           [java.util.concurrent TimeUnit]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; JSON-RPC Transport Helpers
;; =============================================================================

(defn- send-request!
  "Send a JSON-RPC 2.0 request and read the synchronous response.

   Arguments:
     writer     - BufferedWriter connected to process stdin
     reader     - BufferedReader connected to process stdout
     id-counter - AtomicLong for monotonic request IDs
     method     - JSON-RPC method string (e.g. \"tools/list\")
     params     - Optional params map (omitted if nil)

   Returns parsed response map or throws on I/O failure."
  [^BufferedWriter writer ^BufferedReader reader ^AtomicLong id-counter method params]
  (let [id      (.getAndIncrement id-counter)
        request (cond-> {"jsonrpc" "2.0"
                         "id"      id
                         "method"  method}
                  params (assoc "params" params))
        line    (json/write-str request)]
    (locking writer
      (.write writer line)
      (.write writer "\n")
      (.flush writer))
    (locking reader
      (let [response-line (.readLine reader)]
        (when response-line
          (json/read-str response-line :key-fn keyword))))))

(defn- send-notification!
  "Send a JSON-RPC 2.0 notification (no id, no response expected).

   Arguments:
     writer - BufferedWriter connected to process stdin
     method - JSON-RPC method string
     params - Optional params map"
  [^BufferedWriter writer method params]
  (let [notification (cond-> {"jsonrpc" "2.0"
                              "method"  method}
                       params (assoc "params" params))
        line         (json/write-str notification)]
    (locking writer
      (.write writer line)
      (.write writer "\n")
      (.flush writer))))

;; =============================================================================
;; StdioBridge Record
;; =============================================================================

(defrecord StdioBridge [id state]
  proto/IAddon

  (addon-id [_] id)

  (addon-type [_] :mcp-bridge)

  (capabilities [_] #{:mcp-bridge :tools})

  (initialize! [this opts]
    (bridge/start-bridge! this opts))

  (shutdown! [this]
    (bridge/stop-bridge! this))

  (tools [this]
    (bridge/proxy-tool-defs this (clojure.core/name id)))

  (schema-extensions [_] {})

  (health [_]
    (if-let [s @state]
      {:status (if (.isAlive ^Process (:process s)) :ok :down)
       :details {:version "0.1.0"
                 :uptime-ms (- (System/currentTimeMillis) (:started-at s))}}
      {:status :down
       :details {:version "0.1.0"}}))

  bridge/IMcpBridge

  (transport-type [_] :stdio)

  (start-bridge! [_ opts]
    (let [effect (r/try-effect* :bridge/start-failed
                                (let [command    (:command opts)
                                      env        (:env opts)
                                      pb         (ProcessBuilder. ^java.util.List (vec command))
                                      _          (when env
                                                   (let [pe (.environment pb)]
                                                     (doseq [[k v] env]
                                                       (.put pe (str k) (str v)))))
                                      _          (.redirectErrorStream pb false)
                                      process    (.start pb)
                                      writer     (BufferedWriter.
                                                  (OutputStreamWriter. (.getOutputStream process) "UTF-8"))
                                      reader     (BufferedReader.
                                                  (InputStreamReader. (.getInputStream process) "UTF-8"))
                                      id-counter (AtomicLong. 1)
                                      started-at (System/currentTimeMillis)
                                      init-resp  (send-request! writer reader id-counter
                                                                "initialize"
                                                                {"protocolVersion" "2024-11-05"
                                                                 "capabilities"    {}
                                                                 "clientInfo"      {"name"    "hive-mcp-bridge"
                                                                                    "version" "0.1.0"}})]

                                  (when-not (get-in init-resp [:result :protocolVersion])
                                    (throw (ex-info "MCP initialize handshake failed"
                                                    {:response init-resp :bridge id})))

                     ;; Send initialized notification
                                  (send-notification! writer "notifications/initialized" nil)

                     ;; Store process state
                                  (reset! state {:process    process
                                                 :writer     writer
                                                 :reader     reader
                                                 :id-counter id-counter
                                                 :started-at started-at
                                                 :init-resp  init-resp})

                                  (log/info "Stdio bridge started"
                                            {:bridge id
                                             :server-info (get-in init-resp [:result :serverInfo])})

                                  {:success? true
                                   :errors   []
                                   :metadata {:server-info (get-in init-resp [:result :serverInfo])}}))]
      (if (r/ok? effect)
        (:ok effect)
        (do (log/error "Failed to start stdio bridge"
                       {:bridge id :error (:message effect)})
            {:success? false
             :errors   [(:message effect)]}))))

  (stop-bridge! [_]
    (let [effect (r/try-effect* :bridge/stop-failed
                                (when-let [{:keys [^Process process ^BufferedWriter writer ^BufferedReader reader]} @state]
                                  (r/rescue nil (.close writer))
                                  (r/rescue nil (.close reader))
                                  (.destroyForcibly process)
                                  (.waitFor process 5 TimeUnit/SECONDS))
                                (reset! state nil)
                                (log/info "Stdio bridge stopped" {:bridge id})
                                {:success? true :errors []})]
      (if (r/ok? effect)
        (:ok effect)
        (do (log/error "Error stopping stdio bridge"
                       {:bridge id :error (:message effect)})
            {:success? false :errors [(:message effect)]}))))

  (bridge-status [_]
    (if-let [s @state]
      {:connected?        (.isAlive ^Process (:process s))
       :transport         :stdio
       :uptime-ms         (- (System/currentTimeMillis) (:started-at s))
       :remote-tool-count 0} ;; no caching yet
      {:connected?        false
       :transport         :stdio
       :uptime-ms         nil
       :remote-tool-count 0}))

  (call-tool [_ tool-name params]
    (if-let [{:keys [writer reader id-counter]} @state]
      (let [resp (send-request! writer reader id-counter
                                "tools/call"
                                {"name"      tool-name
                                 "arguments" (or params {})})]
        (if-let [error (:error resp)]
          {:isError true
           :content [{:type "text"
                      :text (str "JSON-RPC error: " (:message error))}]}
          (or (:result resp)
              {:isError true
               :content [{:type "text" :text "Empty response from remote"}]})))
      (throw (ex-info "Bridge not connected" {:bridge id}))))

  (list-remote-tools [_]
    (if-let [{:keys [writer reader id-counter]} @state]
      (let [resp (send-request! writer reader id-counter "tools/list" nil)]
        (->> (get-in resp [:result :tools])
             (mapv (fn [t]
                     {:name        (:name t)
                      :description (:description t)
                      :inputSchema (:inputSchema t)}))))
      (do
        (log/warn "Cannot list tools — bridge not connected" {:bridge id})
        []))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn ->stdio-bridge
  "Create a StdioBridge instance.

   Arguments:
     id - Keyword identifier (e.g. :bridge/echo)

   The bridge must be initialized via register-bridge! or start-bridge!
   with {:command [\"python3\" \"path/to/server.py\"]} before use."
  [id]
  (->StdioBridge id (atom nil)))
