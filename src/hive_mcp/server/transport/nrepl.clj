(ns hive-mcp.server.transport.nrepl
  "Embedded nREPL server for bb-mcp tool forwarding.

   Manages nREPL startup with CIDER middleware detection,
   classloader GC, and port-binding retry logic."
  (:require [nrepl.server :as nrepl-server]
            [hive-mcp.nrepl.classloader-gc :as classloader-gc]
            [hive-mcp.config.core :as config]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; nREPL Server
;; =============================================================================

(defn- port-available?
  "Check if a port is available for binding. Returns Result."
  [port]
  (result/try-effect* :nrepl/port-probe-failed
    (let [ss (java.net.ServerSocket. (int port) 1 (java.net.InetAddress/getByName "127.0.0.1"))]
      (.close ss)
      true)))

(defn- resolve-cider-middleware
  "Collect the CIDER middleware that actually resolves in THIS image.

   cider.nrepl/cider-middleware names its middleware by symbol. An entry whose
   namespace is absent from the running classpath resolves to nil, and a single
   nil makes nrepl.server/default-handler throw NullPointerException on
   with-meta, which loses the entire nREPL server. Unresolvable entries are
   dropped and counted in a warning rather than allowed to do that.

   Returns a vector of vars/fns, empty when CIDER is not present."
  []
  (result/rescue []
    (require 'cider.nrepl)
    (if-let [mw-var (resolve 'cider.nrepl/cider-middleware)]
      (let [declared (or @mw-var [])
            resolved (into []
                           (keep (fn [mw]
                                   (cond
                                     (var? mw)    mw
                                     (symbol? mw) (result/rescue nil (requiring-resolve mw))
                                     (ifn? mw)    mw
                                     :else        nil)))
                           declared)
            dropped  (- (count declared) (count resolved))]
        (when (pos? dropped)
          (log/warn "CIDER middleware did not resolve in this image and was dropped"
                    {:declared (count declared)
                     :resolved (count resolved)
                     :dropped  dropped}))
        resolved)
      [])))

(defn- build-nrepl-handler
  "Build nREPL handler with classloader-gc + optional CIDER middleware."
  []
  (let [cider-mw (resolve-cider-middleware)
        ;; GC-fix-6: wrap-shared-classloader pins session-less evals (bb-mcp pattern)
        ;; to a shared session, preventing DynamicClassLoader proliferation.
        all-mw   (into [#'classloader-gc/wrap-shared-classloader] cider-mw)]
    {:handler    (apply nrepl-server/default-handler all-mw)
     :has-cider? (seq cider-mw)}))

(defn- try-start-nrepl!
  "Attempt to start nREPL server. Returns Result ok with server or err."
  [nrepl-server-atom server-opts]
  (result/try-effect* :nrepl/start-failed
    (let [server (nrepl-server/start-server server-opts)]
      (reset! nrepl-server-atom server)
      server)))

(defn start-embedded-nrepl!
  "Start an embedded nREPL server for bb-mcp tool forwarding.

   CRITICAL: This runs in the SAME JVM as the MCP server and channel,
   allowing bb-mcp to forward tool calls that access the live channel.

   Railway: probe-port → build-handler → start-server (with retry).
   Returns server or nil (non-fatal)."
  [nrepl-server-atom]
  (let [nrepl-port  (config/get-service-value :nrepl :port
                                              :env "HIVE_MCP_NREPL_PORT"
                                              :parse parse-long
                                              :default 7910)
        probe       (port-available? nrepl-port)]
    (if (result/err? probe)
      ;; Port already bound (e.g. nREPL started during extension loading) — skip
      (do (log/info "nREPL already running on port" nrepl-port "— skipping embedded start")
          nil)
      ;; Port free — build handler and start with retry
      (let [{:keys [handler has-cider?]} (build-nrepl-handler)
            server-opts  {:port nrepl-port :bind "127.0.0.1" :handler handler}
            max-retries  5
            retry-delay  2000]
        (loop [attempt 1]
          (let [r (try-start-nrepl! nrepl-server-atom server-opts)]
            (cond
              (result/ok? r)
              (do (log/info "Embedded nREPL started on port" nrepl-port
                            (if has-cider? "(with CIDER + classloader-gc)" "(with classloader-gc)")
                            (when (> attempt 1) (str "(attempt " attempt ")")))
                  (:ok r))

              (and (= "class java.net.BindException" (:class r))
                   (< attempt max-retries))
              (do (log/warn "nREPL port" nrepl-port "busy (attempt" (str attempt "/" max-retries "),")
                            "retrying in" (str retry-delay "ms..."))
                  (Thread/sleep retry-delay)
                  (recur (inc attempt)))

              :else
              (do (log/warn "Embedded nREPL failed (non-fatal):" (:message r))
                  nil))))))))
