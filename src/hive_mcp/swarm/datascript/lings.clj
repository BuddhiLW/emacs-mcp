(ns hive-mcp.swarm.datascript.lings
  "Entity lifecycle operations for lings (slaves, tasks, claims).

   CRUD operations for:
   - Slave entities (add, update, remove)
   - Task entities (add, complete, fail)
   - Claim entities (claim, release, batch release)
   - Critical operations guard (kill protection)
   - Claim TTL (stale detection, auto-expiration)

   DDD: Repository pattern for swarm entities."
  (:require [datascript.core :as d]
            [taoensso.timbre :as log]
            [hive-mcp.swarm.datascript.schema :as schema]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.swarm.ledger.default :as ledger-default]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Forward declarations for functions referenced before definition
(declare release-claims-for-slave! release-claims-for-task!)

;;; =============================================================================
;;; Slave CRUD Functions
;;; =============================================================================

(defn add-slave!
  "Add a new slave to the swarm.

   Arguments:
     slave-id  - Unique identifier (required)
     opts      - Map with optional keys:
                 :name           - Human-readable name (defaults to slave-id)
                 :status         - Initial status (default :idle)
                 :depth          - Hierarchy depth (default 1 for ling)
                 :parent         - Parent slave-id string
                 :presets        - Collection of preset names
                 :cwd            - Working directory
                 :project-id     - Project ID for scoping (derived from cwd)
                 :kanban-task-id - Optional kanban task ID this ling is working on

   Stamps `:slave/alive? true` and `:slave/last-active-at` (epoch millis), so
   the new row is visible to the default (non-`:include-stale?`) queries.

   Returns:
     Transaction report with :tempids"
  [slave-id {:keys [name status depth parent presets cwd project-id kanban-task-id]
             :or {status :idle depth 1}}]
  {:pre [(string? slave-id)
         (contains? schema/slave-statuses status)]}
  (let [c (conn/ensure-conn)
        tx-data (cond-> {:slave/id slave-id
                         :slave/name (or name slave-id)
                         :slave/status status
                         :slave/depth depth
                         :slave/tasks-completed 0
                         :slave/created-at (conn/now)
                         :slave/alive? true
                         :slave/last-active-at (System/currentTimeMillis)}
                  cwd (assoc :slave/cwd cwd)
                  project-id (assoc :slave/project-id project-id)
                  (seq presets) (assoc :slave/presets (vec presets))
                  parent (assoc :slave/parent [:slave/id parent])
                  kanban-task-id (assoc :slave/kanban-task-id kanban-task-id))]
    (log/debug "Adding slave:" slave-id "status:" status "project-id:" project-id
               (when kanban-task-id (str "kanban-task:" kanban-task-id)))
    (d/transact! c [tx-data])))

(defn update-slave!
  "Update an existing slave's attributes.

   Arguments:
     slave-id - Slave to update
     updates  - Map of attributes to update (supports all slave attrs)

   Returns:
     Transaction report or nil if slave not found"
  [slave-id updates]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:slave/id slave-id]))]
      (let [tx-data (cond-> (assoc updates :db/id eid)
                      ;; Convert parent string to lookup ref
                      (:slave/parent updates)
                      (update :slave/parent (fn [p] [:slave/id p]))
                      ;; Convert current-task string to lookup ref
                      (:slave/current-task updates)
                      (update :slave/current-task (fn [t] [:task/id t])))]
        (log/debug "Updating slave:" slave-id "with:" (keys updates))
        (d/transact! c [tx-data])))))

(defn remove-slave!
  "Remove a slave from the swarm.
   Also releases any file claims held by this slave.

   Arguments:
     slave-id - Slave to remove

   Returns:
     Transaction report or nil if slave not found"
  [slave-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:slave/id slave-id]))]
      ;; First release claims
      (release-claims-for-slave! slave-id)
      ;; Then retract entity
      (log/debug "Removing slave:" slave-id)
      (d/transact! c [[:db/retractEntity eid]]))))

;;; =============================================================================
;;; Critical Operations Guard (Kill Guard - ADR-003)
;;; =============================================================================

(defn enter-critical-op!
  "Mark a slave as being in a critical operation.
   Prevents swarm_kill from terminating this slave.

   Arguments:
     slave-id - Slave entering critical operation
     op-type  - Type of operation (:wrap :commit :dispatch)

   Returns:
     Transaction report or nil if slave not found"
  [slave-id op-type]
  {:pre [(contains? schema/critical-op-types op-type)]}
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:slave/id slave-id]))]
      (log/debug "Slave" slave-id "entering critical op:" op-type)
      (d/transact! c [[:db/add eid :slave/critical-ops op-type]]))))

(defn exit-critical-op!
  "Mark a slave as having completed a critical operation.

   Arguments:
     slave-id - Slave exiting critical operation
     op-type  - Type of operation (:wrap :commit :dispatch)

   Returns:
     Transaction report or nil if slave not found"
  [slave-id op-type]
  {:pre [(contains? schema/critical-op-types op-type)]}
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:slave/id slave-id]))]
      (log/debug "Slave" slave-id "exiting critical op:" op-type)
      (d/transact! c [[:db/retract eid :slave/critical-ops op-type]]))))

(defn get-critical-ops
  "Get current critical operations for a slave.

   Returns:
     Set of active critical operations, or empty set if none/not found"
  [slave-id]
  (let [c (conn/ensure-conn)
        db @c]
    (if-let [e (d/entity db [:slave/id slave-id])]
      (set (or (:slave/critical-ops e) []))
      #{})))

(defn can-kill?
  "Check if a slave can be killed safely.
   Returns false if slave has any critical operations in progress.

   Arguments:
     slave-id - Slave to check

   Returns:
     {:can-kill? bool :blocking-ops #{...}}"
  [slave-id]
  (let [ops (get-critical-ops slave-id)]
    (if (empty? ops)
      {:can-kill? true :blocking-ops #{}}
      {:can-kill? false :blocking-ops ops})))

(defmacro with-critical-op
  "Execute body while holding a critical operation guard.
   Ensures the critical op is properly released even on exception.

   Usage:
     (with-critical-op slave-id :wrap
       (do-wrap-stuff))"

  [slave-id op-type & body]
  `(do
     (enter-critical-op! ~slave-id ~op-type)
     (try
       ~@body
       (finally
         (exit-critical-op! ~slave-id ~op-type)))))

;;; =============================================================================
;;; Task CRUD Functions
;;; =============================================================================

(defn add-task!
  "Add a new task to the swarm.

   Arguments:
     task-id  - Unique identifier (optional, auto-generated if nil)
     slave-id - Owning slave's id
     opts     - Map with keys:
                :status  - Initial status (default :dispatched)
                :prompt  - Task description
                :files   - Collection of file paths

   Returns:
     Transaction report with :tempids
     The task-id (generated or provided) is in (:tempids report)"
  [task-id slave-id {:keys [status prompt files]
                     :or {status :dispatched}}]
  {:pre [(or (nil? task-id) (string? task-id))
         (string? slave-id)
         (contains? schema/task-statuses status)]}
  (let [c (conn/ensure-conn)
        tid (or task-id (conn/gen-id "task"))
        tx-data (cond-> {:task/id tid
                         :task/slave [:slave/id slave-id]
                         :task/status status
                         :task/started-at (conn/now)}
                  prompt (assoc :task/prompt prompt)
                  (seq files) (assoc :task/files (vec files)))]
    (log/debug "Adding task:" tid "for slave:" slave-id)
    (d/transact! c [tx-data])))

(defn complete-task!
  "Mark a task as completed and update slave stats.

   Arguments:
     task-id - Task to complete

   Returns:
     Transaction report or nil if task not found"
  [task-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [task (d/entity db [:task/id task-id])]
      (let [slave-eid (get-in task [:task/slave :db/id])
            slave (d/entity db slave-eid)
            completed-count (or (:slave/tasks-completed slave) 0)
            ;; Build retraction for current-task if it exists
            current-task-ref (:slave/current-task slave)
            retract-current (when current-task-ref
                              [:db/retract slave-eid :slave/current-task (:db/id current-task-ref)])]
        (log/debug "Completing task:" task-id)
        ;; Release file claims for this task
        (release-claims-for-task! task-id)
        ;; Update task status and slave stats (use retract instead of nil)
        (let [report (d/transact! c (cond-> [{:db/id (:db/id task)
                                              :task/status :completed
                                              :task/completed-at (conn/now)}
                                             {:db/id slave-eid
                                              :slave/tasks-completed (inc completed-count)}]
                                      retract-current (conj retract-current)))]
          (ledger-default/append!
           {:type :task/completed
            :payload {:task-id task-id
                      :slave-id (:slave/id slave)
                      :tasks-completed (inc completed-count)}})
          report)))))

(defn fail-task!
  "Mark a task as failed with error or timeout.

   Arguments:
     task-id - Task to fail
     status  - Failure status (:error or :timeout)

   Returns:
     Transaction report or nil if task not found"
  [task-id status]
  {:pre [(contains? #{:error :timeout} status)]}
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [task (d/entity db [:task/id task-id])]
      (log/debug "Failing task:" task-id "with status:" status)
      ;; Release file claims for this task
      (release-claims-for-task! task-id)
      ;; Update task and clear slave's current-task (use retract instead of nil)
      (let [slave-eid (get-in task [:task/slave :db/id])
            slave (d/entity db slave-eid)
            current-task-ref (:slave/current-task slave)
            retract-current (when current-task-ref
                              [:db/retract slave-eid :slave/current-task (:db/id current-task-ref)])]
        (let [report (d/transact! c (cond-> [{:db/id (:db/id task)
                                              :task/status status
                                              :task/completed-at (conn/now)}]
                                      retract-current (conj retract-current)))]
          (ledger-default/append!
           {:type :task/failed
            :payload {:task-id task-id
                      :status status
                      :slave-id (:slave/id slave)}})
          report)))))

(defn update-task!
  "Update an existing task's attributes.

   Arguments:
     task-id  - Task to update
     updates  - Map of attributes to update (uses :task/* keys)

   Returns:
     Transaction report or nil if task not found

   Common updates:
     {:task/status :completed :task/completed-at (conn/now)}
     {:task/status :error}"
  [task-id updates]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:task/id task-id]))]
      (let [tx-data (cond-> (assoc updates :db/id eid)
                      ;; Convert slave string to lookup ref if present
                      (:task/slave updates)
                      (update :task/slave (fn [s] [:slave/id s])))]
        (log/debug "Updating task:" task-id "with:" (keys updates))
        (d/transact! c [tx-data])))))

;;; =============================================================================
;;; Claim CRUD Functions
;;; =============================================================================

(defn claim-file!
  "Create a file claim for a slave/task.

   Arguments:
     file-path - Path to claim (must be unique)
     slave-id  - Slave making the claim
     opts      - Optional map with:
                 :task-id    - Task associated with claim
                 :prior-hash - File content hash at acquire time (CC.3)

   Returns:
     Transaction report

   Note: Due to :db/unique on :claim/file, attempting to claim
   an already-claimed file will upsert (update the existing claim).
   Use has-conflict? to check first if you want to prevent this."
  [file-path slave-id & [{:keys [task-id prior-hash]}]]
  {:pre [(string? file-path)
         (string? slave-id)]}
  (let [c (conn/ensure-conn)
        db @c
        ;; Only use lookup ref if task entity exists in DataScript.
        ;; Drone tasks (task-drone-*) are generated but never transacted as entities,
        ;; so the [:task/id ...] ref would fail with "Nothing found for entity id".
        task-ref (when task-id
                   (when (:db/id (d/entity db [:task/id task-id]))
                     [:task/id task-id]))
        tx-data (cond-> {:claim/file file-path
                         :claim/slave [:slave/id slave-id]
                         :claim/created-at (conn/now)}
                  task-ref (assoc :claim/task task-ref)
                  prior-hash (assoc :claim/prior-hash prior-hash))]
    (log/debug "Claiming file:" file-path "for slave:" slave-id
               (when prior-hash (str "hash:" (subs prior-hash 0 8) "...")))
    (d/transact! c [tx-data])))

(defn release-claim!
  "Release a file claim and dispatch :claim/file-released event.

   When a claim is released, the event system notifies any lings
   that were waiting for access to this file (file-claim event cascade).

   DUAL-STORE SYNC: Also clears claim from logic-db to prevent ghost claims.
   Claims exist in both DataScript (for queries) and logic-db (for conflict
   detection). Both must be cleared on release.

   Arguments:
     file-path - Path to release

   Returns:
     Transaction report or nil if claim not found"
  [file-path]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:claim/file file-path]))]
      (log/debug "Releasing claim:" file-path)
      (let [result (d/transact! c [[:db/retractEntity eid]])]
        ;; DUAL-STORE SYNC: Also release from logic-db (core.logic pldb)
        ;; Use requiring-resolve to avoid cyclic dependency with swarm.logic
        (when-let [release-logic (requiring-resolve 'hive-mcp.swarm.logic/release-claim-for-file!)]
          (release-logic file-path))
        ;; Dispatch event for file-claim cascade (impl-1 handles this)
        ;; Use requiring-resolve to avoid cyclic dependency with events.core
        ;; Only dispatch if handler is registered (avoids error during bootstrap)
        (when-let [handler-registered? (requiring-resolve 'hive-mcp.events.core/handler-registered?)]
          (when (handler-registered? :claim/file-released)
            (let [dispatch (requiring-resolve 'hive-mcp.events.core/dispatch)]
              (dispatch [:claim/file-released {:file file-path}]))))
        result))))

(defn release-claims-for-slave!
  "Release all file claims held by a slave.

   Arguments:
     slave-id - Slave whose claims to release

   Returns:
     Number of claims released"
  [slave-id]
  (let [c (conn/ensure-conn)
        db @c
        slave-eid (:db/id (d/entity db [:slave/id slave-id]))
        claims (when slave-eid
                 (d/q '[:find ?file
                        :in $ ?slave-eid
                        :where
                        [?c :claim/slave ?slave-eid]
                        [?c :claim/file ?file]]
                      db slave-eid))]
    (when (seq claims)
      (log/debug "Releasing" (count claims) "claims for slave:" slave-id)
      (doseq [[file] claims]
        (release-claim! file)))
    (count claims)))

(defn release-claims-for-task!
  "Release all file claims associated with a task.

   Arguments:
     task-id - Task whose claims to release

   Returns:
     Number of claims released"
  [task-id]
  (let [c (conn/ensure-conn)
        db @c
        task-eid (:db/id (d/entity db [:task/id task-id]))
        claims (when task-eid
                 (d/q '[:find ?file
                        :in $ ?task-eid
                        :where
                        [?c :claim/task ?task-eid]
                        [?c :claim/file ?file]]
                      db task-eid))]
    (when (seq claims)
      (log/debug "Releasing" (count claims) "claims for task:" task-id)
      (doseq [[file] claims]
        (release-claim! file)))
    (count claims)))

;;; =============================================================================
;;; Claim TTL Functions (Stale Detection & Auto-Expiration)
;;; =============================================================================

(def ^:const default-stale-threshold-ms
  "Claims older than 10 minutes are considered stale (600,000 ms)."
  (* 10 60 1000))

;; Delegate to queries namespace for claim queries
(def get-claim-info
  "Get claim information for a file path.
   Delegates to queries/get-claims-for-file."
  queries/get-claims-for-file)

(def get-all-claims
  "Get all active claims with their metadata.
   Delegates to queries/get-all-claims."
  queries/get-all-claims)

(defn claim-age-ms
  "Get the age of a claim in milliseconds.

   Arguments:
     file-path - Path to check

   Returns:
     Age in ms, or nil if claim not found"
  [file-path]
  (when-let [claim (get-claim-info file-path)]
    (when-let [created-at (:created-at claim)]
      (- (inst-ms (conn/now))
         (inst-ms created-at)))))

(defn claim-stale?
  "Check if a claim is stale (older than threshold).

   Arguments:
     file-path     - Path to check
     threshold-ms  - Optional threshold (default: 10 minutes)

   Returns:
     true if claim exists and is older than threshold"
  ([file-path]
   (claim-stale? file-path default-stale-threshold-ms))
  ([file-path threshold-ms]
   (when-let [age (claim-age-ms file-path)]
     (> age threshold-ms))))

(defn get-stale-claims
  "Get all claims that are older than the threshold.

   Arguments:
     threshold-ms - Optional threshold (default: 10 minutes)

   Returns:
     Sequence of stale claim maps with :file, :slave-id, :age-ms, :age-minutes"
  ([]
   (get-stale-claims default-stale-threshold-ms))
  ([threshold-ms]
   (let [now-ms (inst-ms (conn/now))
         all-claims (get-all-claims)]
     (->> all-claims
          (filter (fn [{:keys [created-at]}]
                    (when (some? created-at)
                      (> (- now-ms (inst-ms created-at)) threshold-ms))))
          (map (fn [{:keys [created-at] :as claim}]
                 (let [age-ms (- now-ms (inst-ms created-at))]
                   (assoc claim
                          :age-ms age-ms
                          :age-minutes (Math/round (/ age-ms 60000.0))))))))))

(defn cleanup-stale-claims!
  "Release all stale claims (older than threshold).

   Arguments:
     threshold-ms - Optional threshold (default: 10 minutes)

   Returns:
     Map with :released-count and :released-files"
  ([]
   (cleanup-stale-claims! default-stale-threshold-ms))
  ([threshold-ms]
   (let [stale (get-stale-claims threshold-ms)
         files (map :file stale)]
     (when (seq files)
       (log/info "Cleaning up" (count files) "stale claims:" (vec files)))
     (doseq [file files]
       (release-claim! file))
     {:released-count (count files)
      :released-files (vec files)})))

(defn refresh-claim!
  "Refresh a claim's timestamp to prevent staleness.
   Use when a long-running operation needs to keep a claim active.

   Arguments:
     file-path - Path to refresh

   Returns:
     Transaction report or nil if claim not found"
  [file-path]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [eid (:db/id (d/entity db [:claim/file file-path]))]
      (log/debug "Refreshing claim timestamp:" file-path)
      (d/transact! c [{:db/id eid
                       :claim/heartbeat-at (conn/now)}]))))

;;; =============================================================================
;;; Wait-Queue Functions (inlined from claims.clj)
;;; =============================================================================

(defn add-to-wait-queue!
  "Add a ling to the wait queue for a specific file.

   If the ling is already waiting for this file, this is a no-op
   (upsert behavior via composite unique key).

   Arguments:
     ling-id   - ID of the ling waiting for access
     file-path - Path to the file being waited on

   Returns:
     Transaction report"
  [ling-id file-path]
  {:pre [(string? ling-id)
         (string? file-path)]}
  (let [c (conn/ensure-conn)
        wait-id (str "wait:" ling-id ":" file-path)
        tx-data {:wait-queue/id wait-id
                 :wait-queue/ling-id ling-id
                 :wait-queue/file file-path
                 :wait-queue/queued-at (conn/now)}]
    (log/debug "Adding to wait-queue: ling" ling-id "waiting for" file-path)
    (d/transact! c [tx-data])))

;;; =============================================================================
;;; Claim History Functions (CC.6)
;;; =============================================================================

(defn archive-claim-to-history!
  "CC.6: Archive a claim to history when releasing with changes.

   Called by coordinator/contextual-claim-release! when a file was modified
   during the claim period. Records:
   - Prior and released content hashes
   - Lines added/removed (if provided)
   - Release timestamp

   Arguments:
     file-path - Path of the file being released
     opts      - Map with:
                 :slave-id      - ID of the slave that held the claim (required)
                 :prior-hash    - Content hash at acquire time
                 :released-hash - Content hash at release time
                 :lines-added   - Number of lines added (optional)
                 :lines-removed - Number of lines removed (optional)

   Returns:
     Transaction report"
  [file-path {:keys [slave-id prior-hash released-hash lines-added lines-removed]}]
  {:pre [(string? file-path)
         (string? slave-id)]}
  (let [c (conn/ensure-conn)
        history-id (conn/gen-id "claim-history")
        tx-data (cond-> {:claim-history/id history-id
                         :claim-history/file file-path
                         :claim-history/slave-id slave-id
                         :claim-history/released-at (conn/now)}
                  prior-hash (assoc :claim-history/prior-hash prior-hash)
                  released-hash (assoc :claim-history/released-hash released-hash)
                  lines-added (assoc :claim-history/lines-added lines-added)
                  lines-removed (assoc :claim-history/lines-removed lines-removed))]
    (log/debug "Archiving claim to history:" file-path "slave:" slave-id
               (when (and lines-added lines-removed)
                 (str "+/- " lines-added "/" lines-removed)))
    (let [report (d/transact! c [tx-data])]
      (ledger-default/append!
       {:type :claim/released
        :payload {:file file-path
                  :slave-id slave-id
                  :history-id history-id
                  :prior-hash prior-hash
                  :released-hash released-hash
                  :lines-added lines-added
                  :lines-removed lines-removed}})
      report)))

;;; =============================================================================
;;; Headless Ling Stdout Ring Buffer
;;; =============================================================================
;;
;; Stdout output for headless lings is ephemeral runtime state stored outside
;; DataScript in an atom registry. Each buffer is a bounded vector of output
;; lines with monotonically increasing indices for efficient "since" queries.
;;
;; Buffer structure per ling:
;;   {:lines    []    ;; vector of {:idx N :text "..." :ts <inst>}
;;    :next-idx 0}    ;; next line index (monotonically increasing)
;;
;; Design: FIFO eviction at 5000 lines. O(1) append, O(log n) since-query
;; via binary search on :idx.

(def ^:const stdout-buffer-max-lines
  "Maximum number of stdout lines retained per headless ling.
   FIFO eviction drops oldest lines when cap is reached."
  5000)

(defonce ^{:doc "Registry of stdout ring buffers for headless lings.
                 Map of slave-id -> atom of {:lines [...] :next-idx N}.
                 Lives outside DataScript because it's ephemeral mutable state."}
  stdout-buffers
  (atom {}))

(defn- ensure-stdout-buffer!
  "Ensure a stdout buffer exists for the given slave-id.
   Creates one if missing. Returns the buffer atom."
  [slave-id]
  (or (get @stdout-buffers slave-id)
      (let [buf (atom {:lines [] :next-idx 0})]
        (swap! stdout-buffers assoc slave-id buf)
        buf)))

(defn init-stdout-buffer!
  "Initialize a stdout ring buffer for a headless ling.
   Called during headless spawn. Idempotent - resets if already exists.

   Arguments:
     slave-id - The ling's slave-id

   Returns:
     The buffer atom"
  [slave-id]
  (let [buf (atom {:lines [] :next-idx 0})]
    (swap! stdout-buffers assoc slave-id buf)
    (log/debug "Initialized stdout buffer for headless ling:" slave-id)
    buf))

(defn cleanup-stdout-buffer!
  "Remove the stdout buffer for a ling (call on kill/terminate).

   Arguments:
     slave-id - The ling's slave-id

   Returns:
     true if buffer existed and was removed, false otherwise"
  [slave-id]
  (let [existed? (contains? @stdout-buffers slave-id)]
    (swap! stdout-buffers dissoc slave-id)
    (when existed?
      (log/debug "Cleaned up stdout buffer for:" slave-id))
    existed?))

(defn append-stdout!
  "Append output lines to a headless ling's ring buffer.
   Evicts oldest lines when buffer exceeds max capacity (FIFO).

   Arguments:
     slave-id - The ling's slave-id
     lines    - String or collection of strings to append

   Returns:
     Number of lines currently in buffer after append"
  [slave-id lines]
  (let [buf (ensure-stdout-buffer! slave-id)
        line-strs (if (string? lines) [lines] (vec lines))
        now (conn/now)]
    (swap! buf
           (fn [{:keys [lines next-idx] :as _state}]
             (let [;; Create indexed entries for new lines
                   new-entries (mapv (fn [i text]
                                       {:idx (+ next-idx i)
                                        :text text
                                        :ts now})
                                     (range)
                                     line-strs)
                   all-lines (into lines new-entries)
                   new-next-idx (+ next-idx (count line-strs))
                   ;; FIFO eviction: keep only last N lines
                   trimmed (if (> (count all-lines) stdout-buffer-max-lines)
                             (subvec all-lines (- (count all-lines) stdout-buffer-max-lines))
                             all-lines)]
               {:lines trimmed
                :next-idx new-next-idx})))
    (count (:lines @buf))))

(defn get-stdout
  "Read the last N lines from a headless ling's stdout buffer.

   Arguments:
     slave-id - The ling's slave-id
     n        - Number of lines to read (default: 100)

   Returns:
     Vector of {:idx N :text \"...\" :ts <inst>} maps, ordered oldest-first.
     Empty vector if no buffer exists."
  ([slave-id] (get-stdout slave-id 100))
  ([slave-id n]
   (if-let [buf (get @stdout-buffers slave-id)]
     (let [{:keys [lines]} @buf
           start (max 0 (- (count lines) n))]
       (subvec lines start))
     [])))

(defn get-stdout-since
  "Read lines from a headless ling's stdout buffer since a given index.
   Uses binary search for O(log n) lookup on the monotonic :idx field.

   Arguments:
     slave-id  - The ling's slave-id
     since-idx - Return lines with :idx > since-idx

   Returns:
     Vector of {:idx N :text \"...\" :ts <inst>} maps after since-idx.
     Empty vector if no buffer exists or no new lines."
  [slave-id since-idx]
  (if-let [buf (get @stdout-buffers slave-id)]
    (let [{:keys [lines]} @buf
          n (count lines)]
      (if (zero? n)
        []
        ;; Binary search for first entry with :idx > since-idx
        (let [find-start (fn []
                           (loop [lo 0 hi n]
                             (if (>= lo hi)
                               lo
                               (let [mid (quot (+ lo hi) 2)]
                                 (if (> (:idx (nth lines mid)) since-idx)
                                   (recur lo mid)
                                   (recur (inc mid) hi))))))]
          (subvec lines (find-start)))))
    []))

(defn get-stdout-buffer-info
  "Get metadata about a ling's stdout buffer (for diagnostics).

   Arguments:
     slave-id - The ling's slave-id

   Returns:
     Map with :line-count, :next-idx, :oldest-idx, :newest-idx
     or nil if no buffer exists."
  [slave-id]
  (when-let [buf (get @stdout-buffers slave-id)]
    (let [{:keys [lines next-idx]} @buf]
      {:line-count (count lines)
       :next-idx next-idx
       :oldest-idx (when (seq lines) (:idx (first lines)))
       :newest-idx (when (seq lines) (:idx (peek lines)))
       :max-lines stdout-buffer-max-lines})))

(defn reset-stdout-buffers!
  "Reset all stdout buffers (for testing only).
   Production code should use cleanup-stdout-buffer! per slave."
  []
  (reset! stdout-buffers {}))
