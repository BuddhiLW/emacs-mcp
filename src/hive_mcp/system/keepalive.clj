(ns hive-mcp.system.keepalive
  "Integrant key :hive/keepalive — blocks the main thread until shutdown.

   Two modes, selected by :mode config:
     :stdio   — Desktop/Emacs: blocks on MCP stdio server's join promise.
                When stdin EOF occurs, the MCP pipeline delivers :done.
     :promise — K8s headless: blocks on a promise-chan that receives :shutdown
                from a JVM shutdown hook (SIGTERM from K8s pod lifecycle).

   halt-key! delivers :shutdown to the promise in both modes, enabling
   clean REPL reset via (integrant.repl/halt) without System/exit."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [integrant.core :as ig]
            [clojure.core.async :as async]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- register-sigterm-hook!
  "Registers a JVM shutdown hook that delivers `value` to `promise-ch`.
   Returns the Thread so halt-key! can remove it (avoid leak on REPL reset)."
  [promise-ch value]
  (let [hook (Thread.
               (fn []
                 (log/info ":hive/keepalive — JVM shutdown hook fired, delivering" value)
                 (async/put! promise-ch value))
               "hive-keepalive-shutdown-hook")]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

;; =============================================================================
;; :hive/keepalive — init
;; =============================================================================

(defmethod ig/init-key :hive/keepalive
  [_ {:keys [mode] :as config}]
  (let [mode (or mode :stdio)]
    (log/info ":hive/keepalive init — mode" mode)
    (case mode
      ;; ── :stdio mode ──────────────────────────────────────────────────
      ;; Caller (start! or -main) is responsible for starting the MCP stdio
      ;; server and passing its join promise via :stdio-join-fn (a 0-arg fn
      ;; that returns the join promise). We store the promise so halt-key!
      ;; can deliver :shutdown to it for clean REPL stop.
      :stdio
      (let [shutdown-ch (async/promise-chan)]
        {:mode        :stdio
         :shutdown-ch shutdown-ch
         :status      :running})

      ;; ── :promise mode (headless / K8s) ───────────────────────────────
      ;; No stdio — block on a promise-chan. JVM shutdown hook (SIGTERM)
      ;; delivers :shutdown. halt-key! also delivers for REPL reset.
      :promise
      (let [shutdown-ch (async/promise-chan)
            hook        (register-sigterm-hook! shutdown-ch :shutdown)]
        (log/info ":hive/keepalive — SIGTERM hook registered, blocking on promise-chan")
        {:mode        :promise
         :shutdown-ch shutdown-ch
         :hook-thread hook
         :status      :running}))))

;; =============================================================================
;; :hive/keepalive — halt
;; =============================================================================

(defmethod ig/halt-key! :hive/keepalive
  [_ state]
  (log/info ":hive/keepalive halt — delivering :shutdown")
  ;; Deliver :shutdown to unblock whoever is deref-ing / alt!!-ing
  (when-let [ch (:shutdown-ch state)]
    (async/put! ch :shutdown))
  ;; Remove JVM shutdown hook if we registered one (avoids leak on REPL reset)
  (when-let [hook (:hook-thread state)]
    (try
      (.removeShutdownHook (Runtime/getRuntime) hook)
      (log/info ":hive/keepalive — SIGTERM hook removed")
      (catch IllegalStateException _
        ;; JVM is already shutting down — hook can't be removed, that's fine
        (log/debug ":hive/keepalive — JVM already shutting down, hook removal skipped")))))

;; =============================================================================
;; Public API — for use by server/core.clj start! or -main
;; =============================================================================

(defn await-shutdown!
  "Blocks the calling thread until :shutdown is delivered to the keepalive's
   shutdown-ch. Call from the main thread after all services are started.

   In :stdio mode, the MCP server's join promise should be alt!!-ed against
   shutdown-ch so either stdin-EOF or halt-key! can unblock.

   In :promise mode, this simply derefs the promise-chan (SIGTERM or halt!).

   Returns the value delivered (:shutdown or :done)."
  [keepalive-state]
  (let [{:keys [mode shutdown-ch]} keepalive-state]
    (log/info ":hive/keepalive await-shutdown! — mode" mode "blocking...")
    (let [result (async/<!! shutdown-ch)]
      (log/info ":hive/keepalive await-shutdown! — unblocked with" result)
      result)))

(defn await-shutdown-or-stdio!
  "For :stdio mode — races the MCP stdio join promise against the keepalive
   shutdown-ch. Whichever fires first unblocks the main thread.

   `stdio-join` is a promise/derefable from jsonrpc-server/start.

   Returns [:stdio :done] or [:shutdown :shutdown]."
  [keepalive-state stdio-join]
  (let [{:keys [shutdown-ch]} keepalive-state
        ;; Bridge the stdio join (which is a plain promise, not a core.async chan)
        ;; into a core.async channel so we can alt!!
        stdio-ch (async/thread
                   (let [v @stdio-join]
                     (log/info ":hive/keepalive — stdio join delivered:" v)
                     v))]
    (log/info ":hive/keepalive await-shutdown-or-stdio! — racing stdio vs shutdown...")
    (let [[val port] (async/alts!! [stdio-ch shutdown-ch])]
      (if (= port shutdown-ch)
        (do (log/info ":hive/keepalive — shutdown signal won the race")
            [:shutdown val])
        (do (log/info ":hive/keepalive — stdio EOF won the race")
            [:stdio val])))))
