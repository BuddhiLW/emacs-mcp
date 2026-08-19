(ns hive-mcp.scheduler.decay
  "Periodic background decay for memory, edges, and discs."
  (:require [hive-mcp.config.core :as config]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private scheduler-state
  (atom {:executor nil
         :running? false
         :cycle-count 0
         :last-run nil
         :last-result nil}))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- resolve-and-call
  "Resolve sym via requiring-resolve; call (call-fn resolved-fn); rescue with
   fallback on failure. Returns {:skipped true :reason ...} when sym cannot be
   resolved."
  [sym call-fn fallback]
  (if-let [f (requiring-resolve sym)]
    (result/rescue fallback (call-fn f))
    {:skipped true :reason (str (name sym) " not available")}))

;; =============================================================================
;; Decay Cycle (Pure Logic)
;; =============================================================================

(defn- run-memory-decay!
  "Run memory staleness decay cycle."
  [opts]
  (resolve-and-call
   'hive-mcp.tools.memory.lifecycle/run-decay-cycle!
   #(% opts)
   {:decayed 0 :expired 0 :total-scanned 0}))

(defn- run-edge-decay!
  "Run edge confidence decay cycle."
  [opts]
  (resolve-and-call
   'hive-mcp.knowledge-graph.edges/decay-unverified-edges!
   #(% opts)
   {:decayed 0 :pruned 0 :fresh 0 :evaluated 0}))

(defn- run-disc-decay!
  "Run disc certainty time-decay."
  [opts]
  (resolve-and-call
   'hive-mcp.knowledge-graph.disc/apply-time-decay-to-all-discs!
   #(% :project-id (:project-id opts))
   {:updated 0 :skipped 0 :errors 1}))

(defn- emit-grounding-event!
  "Dispatch :scheduler/grounding-completed so the pass is observable rather than
   silent. Only fires when a handler is registered (mirrors
   knowledge-graph.edges.write/emit-stats-event!); a missing event bus must never
   break the tick, hence the rescue."
  [stats]
  (result/rescue
   nil
   (let [registered? (requiring-resolve 'hive-mcp.events.core/handler-registered?)
         dispatch! (requiring-resolve 'hive-mcp.events.core/dispatch)]
     (when (and registered? dispatch! (registered? :scheduler/grounding-completed))
       (dispatch! [:scheduler/grounding-completed stats])))))

(defn- run-grounding-pass!
  "Re-ground entries whose source anchor drifted. Unlike the read-mostly passes
   above this one WRITES, so it is bounded by :limit."
  [opts]
  (let [stats (resolve-and-call
               'hive-mcp.knowledge-graph.grounding/backfill-grounding!
               #(% opts)
               {:total-scanned 0 :with-source 0 :processed 0 :by-status {}})]
    (emit-grounding-event! stats)
    stats))

(defn- run-canary-pass!
  "Read back what retrieval returns and report faults.

   Read-only, and the only pass on the tick that can declare the system
   UNTRUSTWORTHY rather than merely aged: it queries through the agent-visible
   search seam and asserts the anchor comes back, ordered, unsuperseded.
   A missing canary ns degrades to {:skipped true}, never to a silent pass."
  [opts]
  (resolve-and-call
   'hive-mcp.recall.canary.live/run!
   #(% opts)
   {:ok? false
    :faults [{:fault :recall/canary-crashed
              :diagnosis "the canary pass threw; treat as untrusted retrieval"}]
    :skipped []}))

(defn run-decay-cycle!
  "Run a complete decay cycle: memory + edges + discs + grounding + recall canary."
  ([] (run-decay-cycle! {}))
  ([{:keys [directory project-id memory-limit edge-limit disc-enabled
            grounding-enabled grounding-limit canary-enabled canary-scope]
     :or {memory-limit 50 edge-limit 100 disc-enabled true
          grounding-enabled true grounding-limit 50 canary-enabled true}}]
   (let [start-ms (System/currentTimeMillis)
         cycle-num (-> (swap! scheduler-state update :cycle-count inc)
                       :cycle-count)
         _ (log/info "Scheduler: decay cycle" cycle-num "starting")

         ;; Resolve project-id from directory if not explicit
         resolved-project-id (or project-id
                                 (result/rescue nil
                                                (when directory
                                                  ((requiring-resolve 'hive-mcp.tools.memory.scope/get-current-project-id)
                                                   directory))))

         ;; 1. Memory staleness decay
         memory-stats (run-memory-decay! {:directory directory
                                          :limit memory-limit})

         ;; 2. Edge confidence decay
         edge-stats (run-edge-decay! {:scope resolved-project-id
                                      :limit edge-limit
                                      :created-by "scheduler:decay"})

         ;; 3. Disc certainty time-decay
         disc-stats (if disc-enabled
                      (run-disc-decay! {:project-id resolved-project-id})
                      {:skipped true :reason "disc-decay-disabled"})

         ;; 4. Grounding re-verification (bounded — this pass writes)
         grounding-stats (if grounding-enabled
                           (run-grounding-pass! {:project-id resolved-project-id
                                                 :limit grounding-limit})
                           {:skipped true :reason "grounding-disabled"})

         ;; 5. Recall canary — the tick's only READ-BACK check
         canary-stats (if canary-enabled
                        (run-canary-pass! {:scope (or canary-scope resolved-project-id)})
                        {:skipped true :reason "canary-disabled"})

         elapsed-ms (- (System/currentTimeMillis) start-ms)
         result {:memory-stats memory-stats
                 :edge-stats edge-stats
                 :disc-stats disc-stats
                 :grounding-stats grounding-stats
                 :canary-stats canary-stats
                 :cycle-number cycle-num
                 :duration-ms elapsed-ms
                 :timestamp (java.time.Instant/now)}]

     ;; Update state
     (swap! scheduler-state assoc
            :last-run (java.time.Instant/now)
            :last-result result)

     ;; Log summary
     (log/info "Scheduler: decay cycle" cycle-num "completed in" elapsed-ms "ms"
               {:memory-decayed (or (:decayed memory-stats) 0)
                :memory-expired (or (:expired memory-stats) 0)
                :edges-decayed (or (:decayed edge-stats) 0)
                :edges-pruned (or (:pruned edge-stats) 0)
                :discs-updated (or (:updated disc-stats) 0)
                :grounding-processed (or (:processed grounding-stats) 0)
                :grounding-persistence-lost (or (:persistence-lost grounding-stats) 0)
                :canary-ok? (:ok? canary-stats)
                :canary-faults (count (:faults canary-stats))})
     result)))

;; =============================================================================
;; Scheduler Lifecycle
;; =============================================================================

(defn- get-scheduler-config
  "Read scheduler config with defaults applied."
  []
  (let [cfg (config/get-service-config :scheduler)]
    {:enabled (get cfg :enabled true)
     :interval-minutes (get cfg :interval-minutes 60)
     :memory-limit (get cfg :memory-limit 50)
     :edge-limit (get cfg :edge-limit 100)
     :disc-enabled (get cfg :disc-enabled true)
     :grounding-enabled (get cfg :grounding-enabled true)
     :grounding-limit (get cfg :grounding-limit 50)
     :project-id (get cfg :project-id nil)}))

(defn- make-decay-task
  "Create a Runnable that runs a decay cycle."
  [config]
  (reify Runnable
    (run [_]
      ;; boundary — MUST catch Throwable: ScheduledExecutorService silently
      ;; stops scheduling future executions if a Runnable throws.
      (try
        (run-decay-cycle! {:memory-limit (:memory-limit config)
                           :edge-limit (:edge-limit config)
                           :disc-enabled (:disc-enabled config)
                           :grounding-enabled (:grounding-enabled config)
                           :grounding-limit (:grounding-limit config)
                           :project-id (:project-id config)})
        (catch Throwable t
          (log/error t "Scheduler: decay cycle threw (caught at boundary)"))))))

(defn start!
  "Start the periodic decay scheduler."
  []
  ;; Install the synthesis-afterlife provider before any reap can fire, so
  ;; expired entries a live synthesis references are spared. Lazy resolve keeps
  ;; the scheduler free of a hard dep on the KG-reading provider ns.
  (resolve-and-call 'hive-mcp.memory.synthesis-protection/install!
                    #(%) nil)
  (let [{:keys [enabled interval-minutes] :as cfg} (get-scheduler-config)]
    (cond
      (not enabled)
      (do
        (log/info "Scheduler: decay scheduler disabled via config")
        {:started false :reason "disabled"})

      (:running? @scheduler-state)
      (do
        (log/info "Scheduler: already running, skipping start")
        {:started false :reason "already-running"})

      :else
      ;; boundary — executor creation + scheduling (Java interop)
      (try
        (let [^ScheduledExecutorService executor
              (Executors/newSingleThreadScheduledExecutor
               (reify java.util.concurrent.ThreadFactory
                 (newThread [_ r]
                   (doto (Thread. r "hive-decay-scheduler")
                     (.setDaemon true)))))
              task (make-decay-task cfg)]
          ;; Schedule with fixed delay (not fixed rate) to prevent
          ;; overlapping cycles if one takes longer than the interval
          (.scheduleWithFixedDelay executor task
                                   (long interval-minutes) ;; initial delay
                                   (long interval-minutes) ;; subsequent delay
                                   TimeUnit/MINUTES)
          (swap! scheduler-state assoc
                 :executor executor
                 :running? true)
          (log/info "Scheduler: decay scheduler started"
                    {:interval-minutes interval-minutes
                     :memory-limit (:memory-limit cfg)
                     :edge-limit (:edge-limit cfg)
                     :disc-enabled (:disc-enabled cfg)
                     :grounding-enabled (:grounding-enabled cfg)
                     :grounding-limit (:grounding-limit cfg)})
          {:started true
           :interval-minutes interval-minutes
           :config (dissoc cfg :project-id)})
        (catch Exception e
          (log/error e "Scheduler: failed to start decay scheduler")
          {:started false :reason (.getMessage e)})))))

(defn stop!
  "Stop the periodic decay scheduler."
  []
  (if-not (:running? @scheduler-state)
    {:stopped false :reason "not-running"}
    ;; boundary — executor shutdown (Java interop)
    (try
      (let [^ScheduledExecutorService executor (:executor @scheduler-state)
            cycles (:cycle-count @scheduler-state)]
        (.shutdown executor)
        (when-not (.awaitTermination executor 5 TimeUnit/SECONDS)
          (.shutdownNow executor)
          (log/warn "Scheduler: forced shutdown after 5s timeout"))
        (swap! scheduler-state assoc
               :executor nil
               :running? false)
        (log/info "Scheduler: decay scheduler stopped after" cycles "cycles")
        {:stopped true :cycles-completed cycles})
      (catch Exception e
        (log/error e "Scheduler: error during shutdown")
        (swap! scheduler-state assoc :executor nil :running? false)
        {:stopped true :error (.getMessage e)}))))

(defn restart!
  "Stop and restart the decay scheduler."
  []
  (stop!)
  (start!))

;; =============================================================================
;; Status / Introspection
;; =============================================================================

(defn status
  "Return current scheduler status."
  []
  (let [state @scheduler-state
        cfg (get-scheduler-config)]
    {:running? (:running? state)
     :cycle-count (:cycle-count state)
     :last-run (:last-run state)
     :last-result (when-let [r (:last-result state)]
                    ;; Compact summary, not full result
                    {:memory-decayed (get-in r [:memory-stats :decayed] 0)
                     :memory-expired (get-in r [:memory-stats :expired] 0)
                     :edges-decayed (get-in r [:edge-stats :decayed] 0)
                     :edges-pruned (get-in r [:edge-stats :pruned] 0)
                     :discs-updated (get-in r [:disc-stats :updated] 0)
                     :grounding-processed (get-in r [:grounding-stats :processed] 0)
                     :grounding-persistence-lost (get-in r [:grounding-stats :persistence-lost] 0)
                     :duration-ms (:duration-ms r)
                     :cycle-number (:cycle-number r)})
     :config cfg}))
