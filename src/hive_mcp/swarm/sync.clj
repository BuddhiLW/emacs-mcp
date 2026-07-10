(ns hive-mcp.swarm.sync
  "Synchronize DataScript database with Emacs swarm state.

   Uses channel.clj event bus to receive state updates from Emacs
   and keep the DataScript database in sync for accurate conflict detection.

   Events handled:
   - :slave-spawned    - Register new slave in DataScript
   - :slave-status     - Update slave status
   - :slave-killed     - Remove slave and release claims
   - :task-dispatched  - Register task and file claims
   - :task-completed   - Mark task complete, release claims, process queue
   - :task-failed      - Mark task failed, release claims, process queue
   - :prompt-shown     - Forward permission prompts to hivemind coordinator
   - :prompt-stall     - Urgent alert when ling idle with pending prompt

   Migration Note (ADR-001):
   - Phase 1: Uses DataScript for swarm state (replacing core.logic pldb)
   - Phase 2: Elisp queries DataScript via MCP (future)
   - Phase 3: Single source of truth achieved (future)

   ADR-004 (ISwarmRegistry Migration):
   - Uses ISwarmRegistry protocol for swarm state operations
   - Enables future backend swapping (Datomic, XTDB)
   - Extended operations (claims, connection) via registry helpers

   DRY Patterns (Sprint-2):
   - `get-field`: Unified event field extraction (string or keyword keys)
   - `dispatch-event!`: Safe event dispatch with error handling
   - Handler functions use consistent field extraction pattern"
  (:require [hive-mcp.swarm.protocol :as proto]
            [hive-mcp.swarm.bootstrap.factory :as bootstrap]
            [hive-mcp.swarm.bootstrap.noop :as bootstrap-noop]
            [hive-mcp.swarm.datascript.registry :as registry]
            [hive-mcp.swarm.datascript.lings :as lings]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.swarm.coordinator :as coord]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.emacs-ext.daemon-store :as daemon-store]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.hooks.core :as hooks]
            [hive-mcp.tools.memory.scope :as scope]
            [clojure.core.async :as async :refer [go-loop <!]]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private sync-state
  (atom {:running false
         :subscriptions []}))

(defonce ^:private hooks-registry-atom (atom nil))

;; ISwarmRegistry instance for protocol-based swarm operations
(defonce ^:private swarm-registry-atom (atom nil))

;; ISwarmBootstrap instance — durable projection of slave identity.
;; Defaults to NoopBootstrap (event-stream only) until explicitly injected.
;; The integrant init in server/init.clj injects the configured backend.
(defonce ^:private swarm-bootstrap-atom (atom (bootstrap-noop/make-noop-bootstrap)))

(defn set-swarm-registry!
  "Inject the swarm registry implementation.
   If not set, uses default DataScript registry."
  [registry]
  (reset! swarm-registry-atom registry)
  (log/info "Sync: swarm registry injected"))

(defn get-swarm-registry
  "Get the injected swarm registry, or default DataScript registry."
  []
  (or @swarm-registry-atom (registry/get-default-registry)))

(defn set-swarm-bootstrap!
  "Inject the ISwarmBootstrap implementation. Called by server/init.clj
   from the integrant init for :hive/swarm-sync."
  [bs]
  (when-let [old @swarm-bootstrap-atom]
    (rescue nil (bootstrap/close! old)))
  (reset! swarm-bootstrap-atom bs)
  (log/info "Sync: swarm bootstrap injected"))

(defn get-swarm-bootstrap
  "Get the injected ISwarmBootstrap (defaults to NoopBootstrap)."
  []
  @swarm-bootstrap-atom)

(defn set-hooks-registry!
  "Inject the hooks registry from server.clj to avoid cyclic dependency."
  [registry]
  (reset! hooks-registry-atom registry)
  (log/info "Sync: hooks registry injected"))

(defn get-hooks-registry
  "Get the injected hooks registry."
  []
  @hooks-registry-atom)

;; =============================================================================
;; Bootstrap Write-Through (defined here so handlers below can call them)
;; =============================================================================

(defn- snapshot-slave-write-through!
  "Mirror an in-memory slave write to the durable bootstrap projection.
   Failures never propagate — bootstrap is observability-grade, not
   transactionally coupled to the in-memory registry."
  [slave-id slave-data]
  (try
    (let [reg-slave (some-> (get-swarm-registry) (proto/get-slave slave-id))
          enriched  (cond-> slave-data
                      (:ling/spawn-mode reg-slave)   (assoc :spawn-mode (:ling/spawn-mode reg-slave))
                      (:slave/process-pid reg-slave) (assoc :process-pid (:slave/process-pid reg-slave)))]
      (bootstrap/snapshot-slave! (get-swarm-bootstrap) slave-id enriched))
    (catch Exception e
      (log/warn "Sync: bootstrap snapshot threw for" slave-id ":" (.getMessage e)))))

(defn- forget-slave-write-through!
  [slave-id]
  (try
    (bootstrap/forget-slave! (get-swarm-bootstrap) slave-id)
    (catch Exception e
      (log/warn "Sync: bootstrap forget threw for" slave-id ":" (.getMessage e)))))

;; =============================================================================
;; DRY Helpers (Sprint-2 Refactoring)
;; =============================================================================

(defn- get-field
  "Extract field from event map, handling both string and keyword keys.
   DRY pattern: All handlers use this instead of (or (get event \"x\") (:x event))."
  ([event field] (get-field event field nil))
  ([event field default]
   (or (get event (name field))
       (get event field)
       default)))

(defn- dispatch-event!
  "Dispatch event to hive-events system with error handling.
   DRY pattern: Replaces repeated try/require/resolve/catch blocks."
  [event-vec]
  (try
    (require '[hive-mcp.events.core :as ev])
    ((resolve 'hive-mcp.events.core/dispatch) event-vec)
    (catch Throwable e
      (log/warn "Event dispatch failed:" (.getMessage e)))))

;; =============================================================================
;; Hook Action Execution (Layer 4 - Architectural Guarantee)
;; =============================================================================

(defn- trigger-task-complete-hooks!
  "Trigger :task-complete hooks via event dispatch.

   ARCHITECTURAL GUARANTEE: Ensures a shout is emitted on task completion,
   regardless of whether the ling explicitly called hivemind_shout.

   Layer 4 emits a DIRECT synthetic shout (not via events) to guarantee
   visibility even if events system is not initialized."
  [task-id slave-id]
  ;; LAYER 4: Direct synthetic shout - architectural guarantee
  ;; This call happens regardless of events system state
  (hivemind/shout! slave-id :completed
                   {:task-id task-id
                    :source "layer4-synthetic"
                    :message (str "Task " task-id " completed (synthetic)")})
  (log/debug "Layer4: Emitted synthetic shout for" slave-id)
  ;; Also dispatch event for any registered handlers (optional enhancement)
  (dispatch-event! [:task/shout-complete {:task-id task-id :agent-id slave-id}])
  (when-let [registry (get-hooks-registry)]
    (let [merged-ctx {:type :task-complete :task-id task-id :agent-id slave-id}
          results (hooks/trigger-hooks registry :task-complete merged-ctx)]
      (log/debug "Layer4: Triggered" (count results) "custom hooks")
      results)))

;; =============================================================================
;; Event Handlers
;; =============================================================================

(defn- handle-slave-spawned
  "Handle slave spawn event. Event: {:slave-id :name :parent-id :depth :cwd}
   Derives project-id from cwd for project-scoped swarm operations.

   NOTE: Sets initial status to :initializing, NOT :idle.
   The slave transitions to :idle only after preset injection completes
   (signaled by slave-ready event). This prevents dispatch race conditions
   where tasks are queued before the slave is fully initialized.

   RACE GUARD: If the JVM spawn! already registered this slave (with :working
   or :idle status), we skip the add-slave! to avoid overwriting the status.
   We still bind to daemon and emit Olympus event.

   Multi-Daemon (W1): Uses daemon selection to pick the healthiest daemon
   with capacity, instead of hardcoding the default daemon. Falls back to
   default daemon if selection fails or no daemons are registered."
  [event]
  (let [slave-id (get-field event :slave-id)
        name (get-field event :name slave-id)
        depth (get-field event :depth 1)
        parent-id (get-field event :parent-id)
        cwd (get-field event :cwd)
        project-id (when cwd (scope/get-current-project-id cwd))
        reg (get-swarm-registry)
        ;; Multi-Daemon W1: Select best daemon instead of hardcoded default
        ;; Ensures default daemon is registered before selection
        _ (daemon-store/ensure-default-daemon!)
        {:keys [daemon-id reason]} (daemon-store/select-daemon-for-ling
                                    {:project-id project-id})]
    (when slave-id
      ;; RACE GUARD: Check if JVM spawn! already registered this slave.
      ;; If it did (e.g., with :working status because a task was provided),
      ;; skip add-slave! to avoid overwriting the status via upsert.
      (let [existing (proto/get-slave reg slave-id)
            effective-status (if existing
                               (:slave/status existing)
                               :initializing)]
        (if existing
          (log/debug "Sync: slave" slave-id "already registered by JVM spawn (status:"
                     (:slave/status existing) ") — skipping add-slave! to preserve status")
          (proto/add-slave! reg slave-id {:status :initializing :name name :depth depth
                                          :parent parent-id :cwd cwd :project-id project-id}))
        ;; Write-through to durable bootstrap projection
        (snapshot-slave-write-through! slave-id
                                       {:name name
                                        :status effective-status
                                        :depth depth
                                        :cwd cwd
                                        :project-id project-id
                                        :parent-id parent-id})
        ;; IEmacsDaemon integration: bind ling to selected daemon
        (daemon-store/bind-ling! daemon-id slave-id)
        ;; Emit to Olympus Web UI (port 7911)
        (try
          (when-let [emit-fn (requiring-resolve 'hive-mcp.transport.olympus/emit-agent-event!)]
            (emit-fn :agent-spawned
                     {:id slave-id
                      :name name
                      :type (if (zero? depth) :coordinator :ling)
                      :status effective-status
                      :parent-id parent-id
                      :project-id project-id
                      :daemon-id daemon-id
                      :cwd cwd}))
          (catch Exception e (log/warn "Sync: Olympus emit failed for" slave-id (.getMessage e))))
        (log/debug "Sync: registered slave" slave-id "depth:" depth
                   "status:" effective-status "bound to daemon:" daemon-id
                   "(selection:" reason ")")))))

(defn- handle-slave-ready
  "Handle slave-ready event. Event: {:slave-id}
   Transitions slave from :initializing to :idle after preset injection completes.
   This signals the slave is ready to receive dispatched tasks.

   IMPORTANT: Does NOT downgrade from :working to :idle.
   When a ling is spawned with an initial task, the JVM-side spawn!
   sets status to :working before Emacs finishes initialization.
   Without this guard, the :slave-ready event would overwrite :working
   with :idle, causing lings to appear idle when they're actually working."
  [event]
  (let [slave-id (get-field event :slave-id)
        reg (get-swarm-registry)]
    (when slave-id
      (let [current (proto/get-slave reg slave-id)
            current-status (:slave/status current)]
        (if (= :working current-status)
          (log/info "Sync: slave" slave-id "ready but already :working — not downgrading to :idle")
          (do
            (proto/update-slave! reg slave-id {:slave/status :idle})
            (snapshot-slave-write-through! slave-id {:status :idle})
            (log/info "Sync: slave" slave-id "ready (preset injection complete)")))))))

(defn- handle-slave-status
  "Handle slave status change event. Event: {:slave-id :status}"
  [event]
  (let [slave-id (get-field event :slave-id)
        status (some-> (get-field event :status) keyword)
        reg (get-swarm-registry)]
    (when (and slave-id status)
      (proto/update-slave! reg slave-id {:slave/status status})
      (snapshot-slave-write-through! slave-id {:status status})
      (log/debug "Sync: updated slave status" slave-id "->" status))))

(defn- release-ling-resources!
  "Clean up all JVM-side resources held by a dead ling.
   Each cleanup is individually guarded — one failure doesn't stop the rest."
  [slave-id]
  ;; hivemind state: ling results + agent messages + swarm prompts
  (try
    (when-let [f (requiring-resolve 'hive-mcp.hivemind.state/remove-ling-result!)]
      (f slave-id))
    (catch Exception e (log/warn "Sync: failed to remove ling result for" slave-id (.getMessage e))))
  (try
    (when-let [f (requiring-resolve 'hive-mcp.hivemind.state/clear-agent-messages!)]
      (f slave-id))
    (catch Exception e (log/warn "Sync: failed to clear agent messages for" slave-id (.getMessage e))))
  (try
    (when-let [f (requiring-resolve 'hive-mcp.hivemind.state/remove-swarm-prompt!)]
      (f slave-id))
    (catch Exception e (log/warn "Sync: failed to remove swarm prompt for" slave-id (.getMessage e))))
  ;; stdout buffers (headless lings)
  (try
    (lings/cleanup-stdout-buffer! slave-id)
    (catch Exception e (log/warn "Sync: failed to cleanup stdout buffer for" slave-id (.getMessage e))))
  ;; callbacks registered by this ling
  (try
    (when-let [f (requiring-resolve 'hive-mcp.swarm.callback/cleanup-for-agent!)]
      (f slave-id))
    (catch Exception e (log/warn "Sync: failed to cleanup callbacks for" slave-id (.getMessage e))))
  (log/debug "Sync: released JVM resources for dead ling" slave-id))

(defn- handle-slave-killed
  "Handle slave killed event. Event: {:slave-id}
   EVENTS-07: Dispatches :ling/completed BEFORE removing from DataScript.
   Releases JVM-side resources held by the dead ling.
   IEmacsDaemon integration: Unbinds ling from daemon before removal.
   Multi-Daemon (W1): Looks up actual daemon binding instead of assuming default."
  [event]
  (let [slave-id (get-field event :slave-id)
        reg (get-swarm-registry)
        ;; Multi-Daemon W1: Look up the daemon this ling is actually bound to
        daemon-id (or (:emacs-daemon/id (daemon-store/get-daemon-for-ling slave-id))
                      (daemon-store/default-daemon-id))]
    (when slave-id
      (dispatch-event! [:ling/completed {:slave-id slave-id :reason "terminated"}])
      ;; Release JVM-side resources for the dead ling
      (release-ling-resources! slave-id)
      ;; IEmacsDaemon integration: unbind ling from actual daemon before removal
      (daemon-store/unbind-ling! daemon-id slave-id)
      ;; Emit to Olympus Web UI before removal
      (try
        (when-let [emit-fn (requiring-resolve 'hive-mcp.transport.olympus/emit-agent-event!)]
          (emit-fn :agent-killed {:id slave-id}))
        (catch Exception e (log/warn "Sync: Olympus agent-killed emit failed for" slave-id (.getMessage e))))
      (proto/remove-slave! reg slave-id)
      (forget-slave-write-through! slave-id)
      (log/debug "Sync: removed slave" slave-id "unbound from daemon:" daemon-id))))

(defn- handle-task-dispatched
  "Handle task dispatch event. Event: {:task-id :slave-id :files}

   ADR-002 FIX: Updates slave status to :working when task is dispatched.
   This ensures swarm_status accurately reflects ling activity."
  [event]
  (let [task-id (get-field event :task-id)
        slave-id (get-field event :slave-id)
        files (get-field event :files [])
        reg (get-swarm-registry)]
    (when (and task-id slave-id)
      (proto/add-task! reg task-id slave-id {:status :dispatched :files files})
      ;; ADR-002 FIX: Set slave status to :working on dispatch
      (proto/update-slave! reg slave-id {:slave/status :working})
      ;; claim-file! is not in ISwarmRegistry - use lings directly
      (doseq [f files]
        (lings/claim-file! f slave-id task-id))
      (log/debug "Sync: registered task" task-id "with" (count files) "files, slave now :working"))))

(defn- handle-task-completed
  "Handle task completion event. Event: {:task-id :slave-id}
   LAYER 4: Triggers hooks for synthetic shout (architectural guarantee)."
  [event]
  (let [task-id (get-field event :task-id)
        slave-id (get-field event :slave-id)]
    (when task-id
      ;; complete-task! has full semantics (release claims, update stats) - use lings directly
      (lings/complete-task! task-id)
      (dispatch-event! [:task/complete {:task-id task-id :agent-id slave-id :result :completed}])
      (trigger-task-complete-hooks! task-id slave-id)
      (when-let [ready (seq (coord/process-queue!))]
        (log/info "Sync:" (count ready) "queued tasks now ready"))
      (log/debug "Sync: task completed" task-id))))

(defn- handle-task-failed
  "Handle task failure event. Event: {:task-id :error}"
  [event]
  (let [task-id (get-field event :task-id)]
    (when task-id
      ;; fail-task! has full semantics (release claims) - use lings directly
      (lings/fail-task! task-id :error)
      (coord/process-queue!)
      (log/debug "Sync: task failed" task-id))))

(defn- handle-prompt-shown
  "Handle permission prompt event. Event: {:slave-id :prompt :timestamp :session-id}
   Emits :ling/prompt-pending for Olympus UI reactive updates."
  [event]
  (let [slave-id (get-field event :slave-id)
        prompt (get-field event :prompt)
        timestamp (get-field event :timestamp (System/currentTimeMillis))
        session-id (get-field event :session-id)]
    (when (and slave-id prompt)
      (hivemind/add-swarm-prompt! slave-id prompt session-id timestamp)
      ;; Emit event for Olympus UI reactive prompt detection
      (dispatch-event! [:ling/prompt-pending
                        {:slave-id slave-id
                         :prompt-preview (subs prompt 0 (min 100 (count prompt)))
                         :pending-since timestamp}])
      (log/info "Sync: forwarded prompt from" slave-id "to hivemind"))))

(defn- handle-prompt-stall
  "Handle prompt-stall event. Event: {:slave-id :idle-duration-secs :prompt-preview :urgency}"
  [event]
  (let [slave-id (get-field event :slave-id)
        idle-secs (get-field event :idle-duration-secs 0)
        prompt-preview (get-field event :prompt-preview "")
        urgency (get-field event :urgency "high")]
    (when slave-id
      (hivemind/shout! slave-id :prompt-stall
                       {:task "awaiting-response"
                        :message (format "STALLED %.0fs waiting for prompt response: %s"
                                         idle-secs prompt-preview)
                        :idle-secs idle-secs
                        :urgency urgency})
      (log/warn "Sync: prompt-stall from" slave-id "- idle" idle-secs "secs"))))

(defn- handle-dispatch-dropped
  "Handle dispatch-dropped event. Event: {:slave-id :reason :prompt-preview :retries :wait-time-secs}
   This critical event indicates a queued dispatch was lost after max retries."
  [event]
  (let [slave-id (get-field event :slave-id)
        reason (get-field event :reason "unknown")
        prompt-preview (get-field event :prompt-preview "")
        retries (get-field event :retries 0)
        wait-time (get-field event :wait-time-secs 0)]
    (when slave-id
      ;; Shout to hivemind so coordinator is notified
      (hivemind/shout! slave-id :error
                       {:task "dispatch-dropped"
                        :message (format "DISPATCH DROPPED: %s - reason: %s, retries: %d, wait: %.1fs. Prompt: %s"
                                         slave-id reason retries wait-time prompt-preview)
                        :reason reason
                        :retries retries
                        :wait-time-secs wait-time
                        :urgency "high"})
      ;; Dispatch to hive-events for potential hooks/effects
      (dispatch-event! [:task/dispatch-dropped {:slave-id slave-id
                                                :reason reason
                                                :prompt-preview prompt-preview
                                                :retries retries}])
      (log/error "Sync: DISPATCH DROPPED for" slave-id "- reason:" reason
                 "retries:" retries "wait:" wait-time "secs"))))

;; =============================================================================
;; Event Subscription Management
;; =============================================================================

(def ^:private event-handlers
  {:slave-spawned handle-slave-spawned
   :slave-ready handle-slave-ready
   :slave-status handle-slave-status
   :slave-killed handle-slave-killed
   :task-dispatched handle-task-dispatched
   :task-completed handle-task-completed
   :task-failed handle-task-failed
   :prompt-shown handle-prompt-shown
   :prompt-stall handle-prompt-stall
   :dispatch-dropped handle-dispatch-dropped})

(defn- subscribe-to-event!
  "Subscribe to a single event type with handler."
  [event-type handler]
  (let [sub-ch (channel/subscribe! event-type)]
    (go-loop []
      (when-let [event (<! sub-ch)]
        (try
          (handler event)
          (catch Throwable e
            (log/error "Sync handler error for" event-type ":" (.getMessage e))))
        (recur)))
    sub-ch))

;; =============================================================================
;; Full Sync (Bootstrap) — backend-agnostic via ISwarmBootstrap
;; =============================================================================

(defn- register-slave-from-status!
  "Register a single slave from a bootstrap source's slave map.
   Derives project-id from cwd if not already supplied."
  [slave]
  (let [slave-id (:slave-id slave)
        status (some-> (:status slave) keyword)
        name (or (:name slave) slave-id)
        depth (or (:depth slave) 1)
        cwd (:cwd slave)
        project-id (or (:project-id slave)
                       (when cwd (scope/get-current-project-id cwd)))
        reg (get-swarm-registry)]
    (proto/add-slave! reg slave-id {:status status :name name :depth depth
                                    :cwd cwd :project-id project-id})
    (let [updates (cond-> {}
                    (:spawn-mode slave)  (assoc :ling/spawn-mode (:spawn-mode slave))
                    (:process-pid slave) (assoc :slave/process-pid (:process-pid slave)))]
      (when (seq updates)
        (proto/update-slave! reg slave-id updates)))
    (log/debug "Sync: bootstrapped slave" slave-id status "project-id:" project-id)))

(defn full-sync-from-bootstrap!
  "One-time full sync from the configured ISwarmBootstrap source.
   Replaces the legacy `full-sync-from-emacs!` — the backend is now
   pluggable via `set-swarm-bootstrap!`."
  []
  (log/info "Starting full sync from bootstrap source...")
  (conn/reset-conn!)
  (let [bs (get-swarm-bootstrap)
        slaves (try (bootstrap/load-slaves bs)
                    (catch Exception e
                      (log/error "Bootstrap load failed:" (.getMessage e))
                      []))]
    (run! register-slave-from-status! slaves)
    (log/info "Full sync complete:" (count slaves) "slaves")))

;; Backwards-compatibility alias — keep old call sites working until they
;; migrate. Marked deprecated to discourage new use.
(defn ^:deprecated full-sync-from-emacs!
  "DEPRECATED: use `full-sync-from-bootstrap!`. Retained as a thin alias."
  []
  (full-sync-from-bootstrap!))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start-sync!
  "Start event-driven synchronization.

   Options:
     :bootstrap? — If true, do a full sync from the configured ISwarmBootstrap
                   source before subscribing to events. Default: true."
  ([] (start-sync! {:bootstrap? true}))
  ([{:keys [bootstrap?] :or {bootstrap? true}}]
   (if (:running @sync-state)
     (do (log/warn "Sync already running") @sync-state)
     (do
       (log/info "Starting logic database sync...")
       (when bootstrap? (full-sync-from-bootstrap!))
       (let [subs (mapv (fn [[et h]] (subscribe-to-event! et h)) event-handlers)]
         (reset! sync-state {:running true :subscriptions subs})
         (log/info "Logic database sync started -" (count subs) "event subscriptions")
         @sync-state)))))

(defn stop-sync!
  "Stop event synchronization."
  []
  (when (:running @sync-state)
    (run! async/close! (:subscriptions @sync-state))
    (reset! sync-state {:running false :subscriptions []})
    (log/info "Logic database sync stopped")))

(defn sync-status
  "Get current sync status."
  []
  {:running (:running @sync-state)
   :subscription-count (count (:subscriptions @sync-state))
   ;; db-stats is a debug function, not in ISwarmRegistry
   :db-stats (queries/db-stats)})

(comment
  ;; Development REPL examples
  (start-sync!)
  (sync-status)
  (handle-slave-spawned {:slave-id "test-slave-1" :name "test" :depth 1})
  (handle-task-dispatched {:task-id "task-1" :slave-id "test-slave-1" :files ["/src/core.clj"]})
  ;; Debug utilities - not in ISwarmRegistry
  (queries/dump-db)
  (stop-sync!))
