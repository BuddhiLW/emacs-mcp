(ns hive-mcp.transport.olympus.state-bridge
  "DataScript -> WebSocket state bridge for Olympus.

   Listens on the swarm DataScript connection, classifies which entity
   domains changed (:agents / :waves), batches changes within a 200ms
   throttle window, and pushes :state-patch events to all connected
   Olympus clients via the stream module.

   Owns the DS bridge queue, the background daemon thread atom, and the
   channel-subs tracking atom.

   Split from transport/olympus.clj (hotspot #14 refactor, plan
   refactor-hotspots-p0.md line 99-104)."
  (:require [datascript.core :as d-core]
            [taoensso.timbre :as log]
            [hive-mcp.dns.result :as result]
            [hive-mcp.swarm.datascript.connection :as ds-conn]
            [hive-mcp.transport.olympus.snapshots :as snap]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

;; DS State Bridge: Throttled DataScript -> WebSocket push.
;; Uses plain Java concurrency (LinkedBlockingQueue + daemon Thread)
;; to avoid core.async executor compatibility issues.
(defonce ds-bridge-queue
  (java.util.concurrent.LinkedBlockingQueue. 100))

(defonce ds-bridge-thread (atom nil))

;; Channel subscription tracking for proper cleanup. Lives here so that
;; start!/stop! lifecycle (http.clj) can coordinate teardown from a single
;; place, while stream.clj (which establishes subscriptions from
;; wire-hivemind-events!) appends to the atom via a resolved ref.
(defonce channel-subs (atom []))

(def bridge-throttle-ms
  "Batching window for DS changes before pushing to clients.
   200ms balances responsiveness vs flood prevention."
  200)

;; =============================================================================
;; Broadcast / clients resolution (dynamic to avoid ns cycle with stream)
;; =============================================================================

(defn- broadcast!
  "Resolve and call stream/broadcast! dynamically. Avoids a require cycle
   because stream.clj registers the DS bridge via requiring-resolve of
   wire-ds-state-bridge! below."
  [event]
  (when-let [f (requiring-resolve 'hive-mcp.transport.olympus.stream/broadcast!)]
    (f event)))

(defn- clients-snapshot
  "Deref the stream/clients atom. Returns #{} if not yet loaded."
  []
  (if-let [a (requiring-resolve 'hive-mcp.transport.olympus.stream/clients)]
    @@a
    #{}))

;; =============================================================================
;; DataScript State Bridge
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
  (when (and (seq changed-domains) (seq (clients-snapshot)))
    (result/rescue nil
                   (let [data (cond-> {}
                                (:agents changed-domains) (assoc :agents (snap/build-agents-snapshot))
                                (:waves changed-domains)  (assoc :waves (snap/build-waves-snapshot)))]
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
