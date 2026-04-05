(ns hive-mcp.swarm.protocol
  "Swarm state management protocols (ISP-segregated).

   Provides unified interfaces for swarm persistence, enabling
   multiple backend implementations (DataScript, Datalevin, etc.).

   Protocols (ISP — Interface Segregation Principle):
   - ISwarmRegistry   — Slave + Task CRUD
   - IClaimStore      — File claim lifecycle
   - ICriticalOps     — Kill guard operations
   - ICoordination    — Wrap queue, plans, waves, coordinators
   - ISwarmDb         — Low-level DB access boundary

   Design: Functional DDD Repository pattern.
   OCP via protocols + records (compiles to Java interfaces).
   DIP: All consumer code depends on these abstractions, never on backends.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol ISwarmRegistry
  "Interface for swarm state registry.

   Implementations: DataScript, Datomic, XTDB, Atom.

   All methods should be thread-safe. Write operations (add-*, update-*, remove-*)
   may block. Read operations (get-*, list-*) should be fast and preferably non-blocking.

   Entity types managed:
   - Slave: Ling/drone instances with status, presets, project scope
   - Task: Work units assigned to slaves with status lifecycle"

  ;;; =========================================================================
  ;;; Slave Operations
  ;;; =========================================================================

  (add-slave! [this slave-id opts]
    "Add a new slave to the registry.

     Arguments:
       slave-id - Unique string identifier (required)
       opts     - Map with optional keys:
                  :name       - Human-readable name (defaults to slave-id)
                  :status     - Initial status keyword (default :idle)
                  :depth      - Hierarchy depth integer (default 1 for ling)
                  :parent     - Parent slave-id string
                  :presets    - Collection of preset name strings
                  :cwd        - Working directory path
                  :project-id - Project ID for scoping

     Returns:
       Transaction report with :tempids on success.

     Throws:
       ex-info if slave-id already exists or validation fails.")

  (get-slave [this slave-id]
    "Get a slave by ID.

     Arguments:
       slave-id - Slave identifier string

     Returns:
       Map with slave attributes or nil if not found.
       Keys include: :slave/id, :slave/name, :slave/status, :slave/depth,
                     :slave/parent, :slave/presets, :slave/cwd, :slave/project-id,
                     :slave/tasks-completed, :slave/created-at")

  (update-slave! [this slave-id updates]
    "Update an existing slave's attributes.

     Arguments:
       slave-id - Slave to update
       updates  - Map of attributes to update (uses :slave/* keys)

     Returns:
       Transaction report on success, nil if slave not found.

     Example:
       (update-slave! registry \"ling-1\" {:slave/status :busy})")

  (remove-slave! [this slave-id]
    "Remove a slave from the registry.

     Also releases any file claims held by this slave.
     Implementations should handle cascading cleanup.

     Arguments:
       slave-id - Slave to remove

     Returns:
       Transaction report on success, nil if slave not found.")

  (get-all-slaves [this]
    "Get all slaves in the registry.

     Returns:
       Seq of maps with slave attributes (may be empty).")

  (get-slaves-by-status [this status]
    "Get slaves filtered by status.

     Arguments:
       status - Status keyword to filter by (:idle, :busy, :blocked, etc.)

     Returns:
       Seq of slave maps matching status (may be empty).")

  (get-slaves-by-project [this project-id]
    "Get slaves filtered by project-id.

     Arguments:
       project-id - Project ID string to filter by

     Returns:
       Seq of slave maps belonging to the project (may be empty).")

  ;;; =========================================================================
  ;;; Task Operations
  ;;; =========================================================================

  (add-task! [this task-id slave-id opts]
    "Add a new task to the registry.

     Arguments:
       task-id  - Unique identifier string (optional, auto-generated if nil)
       slave-id - Owning slave's id (required)
       opts     - Map with keys:
                  :status - Initial status (default :dispatched)
                  :prompt - Task description string
                  :files  - Collection of file paths involved

     Returns:
       Transaction report with :tempids containing the task-id.

     Throws:
       ex-info if slave-id doesn't exist or validation fails.")

  (get-task [this task-id]
    "Get a task by ID.

     Arguments:
       task-id - Task identifier string

     Returns:
       Map with task attributes or nil if not found.
       Keys include: :task/id, :task/slave, :task/status, :task/prompt,
                     :task/files, :task/started-at, :task/completed-at")

  (update-task! [this task-id updates]
    "Update an existing task's attributes.

     Arguments:
       task-id - Task to update
       updates - Map of attributes to update (uses :task/* keys)

     Returns:
       Transaction report on success, nil if task not found.

     Common updates:
       {:task/status :completed :task/completed-at (java.util.Date.)}
       {:task/status :error}")

  (get-tasks-for-slave
    [this slave-id]
    [this slave-id status]
    "Get tasks for a specific slave.

     Arguments:
       slave-id - Slave to get tasks for
       status   - Optional status filter keyword

     Returns:
       Seq of task maps (may be empty)."))

;;; =============================================================================
;;; IClaimStore — File claim lifecycle (ISP: separate from registry)
;;; =============================================================================

(defprotocol IClaimStore
  "File claim management: acquire, release, conflict detection, history.

   Claims enforce mutual exclusion on files across lings/drones.
   Implementations must handle:
   - Upsert semantics on claim-file! (same file = update existing)
   - Cascading release on slave/task removal
   - Stale claim detection via timestamps"

  (-claim-file! [this file-path slave-id]
    [this file-path slave-id opts]
    "Acquire a file claim. Upserts if claim exists.
     opts: {:task-id string, :prior-hash string, :wave-id string}")

  (-release-claim! [this file-path]
    "Release a file claim and dispatch :claim/file-released event.")

  (-release-claims-for-slave! [this slave-id]
    "Release all claims held by a slave. Returns count released.")

  (-release-claims-for-task! [this task-id]
    "Release all claims associated with a task. Returns count released.")

  (-get-claims-for-file [this file-path]
    "Get claim info for a file. Returns map or nil if unclaimed.")

  (-get-all-claims [this]
    "Get all active claims. Returns seq of claim maps.")

  (-has-conflict? [this file-path requesting-slave]
    "Check if a file claim would conflict. Returns {:conflict? bool :held-by id}.")

  (-check-file-conflicts [this requesting-slave files]
    "Check conflicts on multiple files. Returns seq of conflicting files.")

  (-refresh-claim! [this file-path]
    "Refresh claim timestamp to prevent staleness.")

  (-cleanup-stale-claims! [this]
    [this threshold-ms]
    "Release claims older than threshold. Returns {:released-count N :released-files [...]}.")

  (-archive-claim-to-history! [this file-path opts]
    "Archive a released claim to history for CC.6 tracking.
     opts: {:slave-id :prior-hash :released-hash :lines-added :lines-removed}")

  (-get-recent-claim-history [this opts]
    "Query recent claim history. opts: {:file :slave-id :since :limit}")

  (-add-to-wait-queue! [this ling-id file-path]
    "Add ling to wait queue for a file (file-claim cascade)."))

;;; =============================================================================
;;; ICriticalOps — Kill guard operations (ISP: separate concern)
;;; =============================================================================

(defprotocol ICriticalOps
  "Critical operation guard for kill protection (ADR-003).

   When a ling is in a critical operation (:wrap :commit :dispatch),
   swarm_kill must wait or be rejected. These operations ensure
   data integrity during session crystallization and git commits."

  (-enter-critical-op! [this slave-id op-type]
    "Mark slave as in critical operation. op-type: :wrap :commit :dispatch")

  (-exit-critical-op! [this slave-id op-type]
    "Mark slave as having completed a critical operation.")

  (-get-critical-ops [this slave-id]
    "Get current critical operations for a slave. Returns set.")

  (-can-kill? [this slave-id]
    "Check if slave can be killed safely.
     Returns {:can-kill? bool :blocking-ops #{...}}."))

;;; =============================================================================
;;; ICoordination — Wrap queue, plans, waves, coordinators (ISP)
;;; =============================================================================

(defprotocol ICoordination
  "Multi-agent coordination: wrap queue, change plans, waves, coordinators.

   Application Service layer for swarm orchestration.
   Manages the lifecycle of coordination entities that span
   multiple agents and require transactional consistency."

  ;;; --- Wrap Queue (Crystal Convergence) ---

  (-add-wrap-notification! [this wrap-id opts]
    "Record a ling wrap for coordinator permeation.
     opts: {:agent-id :session-id :project-id :created-ids :stats}")

  (-get-unprocessed-wraps [this]
    "Get all unprocessed wrap notifications.")

  (-get-unprocessed-wraps-for-project [this project-id]
    "Get unprocessed wraps for a specific project.")

  (-get-unprocessed-wraps-for-hierarchy [this project-id-prefix]
    "Get unprocessed wraps for project and all children (prefix match).")

  (-mark-wrap-processed! [this wrap-id]
    "Mark a wrap notification as processed.")

  ;;; --- Change Plans ---

  (-create-plan! [this tasks preset]
    "Create a change plan with items. Returns plan-id.")

  (-get-plan [this plan-id]
    "Get a change plan by ID.")

  (-get-pending-items [this plan-id]
    "Get pending items for a plan.")

  (-get-plan-items [this plan-id]
    "Get all items for a plan.")

  (-update-item-status! [this item-id status]
    [this item-id status opts]
    "Update a change item's status. opts: {:drone-id :result}")

  (-update-plan-status! [this plan-id status]
    "Update a change plan's status.")

  ;;; --- Waves ---

  (-create-wave! [this plan-id]
    [this plan-id opts]
    "Create a wave execution. opts: {:concurrency N}. Returns wave-id.")

  (-get-wave [this wave-id]
    "Get a wave by ID.")

  (-get-all-waves [this]
    "Get all waves.")

  (-update-wave-counts! [this wave-id delta]
    "Update wave counts. delta: {:active N :completed N :failed N}")

  (-complete-wave! [this wave-id status]
    "Mark a wave as completed with final status.")

  ;;; --- Coordinators ---

  (-register-coordinator! [this coordinator-id opts]
    "Register a coordinator instance. opts: {:project :pid :session-id}")

  (-update-heartbeat! [this coordinator-id]
    "Update coordinator heartbeat timestamp.")

  (-get-coordinator [this coordinator-id]
    "Get a coordinator by ID.")

  (-get-all-coordinators [this]
    "Get all coordinators.")

  (-get-coordinators-for-project [this project]
    "Get coordinators for a project.")

  (-mark-coordinator-terminated! [this coordinator-id]
    "Mark coordinator as terminated (graceful shutdown).")

  (-cleanup-stale-coordinators! [this]
    [this opts]
    "Find and mark stale coordinators. Returns seq of marked IDs.")

  (-remove-coordinator! [this coordinator-id]
    "Remove a coordinator entity.")

  ;;; --- Completed Tasks (session-scoped) ---

  (-register-completed-task! [this task-id opts]
    "Register a completed task for wrap. opts: {:title :agent-id :project-id}")

  (-get-completed-tasks-this-session [this]
    [this opts]
    "Get completed tasks. opts: {:agent-id :project-id}")

  (-clear-completed-tasks! [this]
    "Clear all completed tasks. Returns count cleared.")

  ;;; --- Kanban Movements (session-scoped status transitions) ---

  (-register-kanban-movement! [this opts]
    "Register a kanban status transition. opts: {:task-id :title :from :to :agent-id :project-id}")

  (-get-kanban-movements-this-session [this]
    [this opts]
    "Get kanban movements. opts: {:agent-id :project-id}")

  (-clear-kanban-movements! [this]
    "Clear all kanban movements. Returns count cleared."))

;;; =============================================================================
;;; ISwarmDb — Low-level DB access boundary
;;; =============================================================================

(defprotocol ISwarmDb
  "Low-level database access boundary.

   Provides the minimal surface for code that needs direct DB access:
   - Raw transaction execution (event effects)
   - Change listening (Olympus bridge)
   - Stats and diagnostics

   DIP: Consumer code depends on this abstraction, never on
   datascript.core or datalevin.core directly."

  (-transact! [this tx-data]
    "Execute a raw transaction. Returns tx-report.")

  (-current-db [this]
    "Get the current database value (snapshot).")

  (-listen! [this key callback]
    "Register a transaction listener. callback: (fn [tx-report] ...).
     Returns the listener key for unlisten!.
     Note: Datalevin doesn't have native listen!; implementations
     must provide equivalent notification semantics.")

  (-unlisten! [this key]
    "Remove a transaction listener.")

  (-db-stats [this]
    "Get statistics about the current swarm state.")

  (-reset-db! [this]
    "Reset the database to empty state.")

  (-close! [this]
    "Close the database connection and release resources."))

;;; =============================================================================
;;; Registry Status Constants (for reference by implementations)
;;; =============================================================================

(def slave-statuses
  "Valid slave status values.

   :idle       - Available for work
   :spawning   - Being created
   :starting   - Starting up
   :working    - Actively processing a task
   :blocked    - Waiting on external input
   :error      - Failed with error
   :terminated - Killed/stopped"
  #{:idle :spawning :starting :working :blocked :error :terminated})

(def task-statuses
  "Valid task status values.

   :queued     - Waiting for dispatch (file conflicts)
   :dispatched - Sent to slave, in progress
   :completed  - Successfully finished
   :timeout    - Timed out waiting
   :error      - Failed with error"
  #{:queued :dispatched :completed :timeout :error})

;;; =============================================================================
;;; Public API Wrappers (backward-compatible delegation)
;;; =============================================================================
;;
;; These functions provide a stable public API that delegates to the ISwarmRegistry
;; protocol. Enables gradual migration without breaking existing code.
;;
;; Usage: (require '[hive-mcp.swarm.protocol :as proto])
;;        (proto/add-slave! registry "ling-1" {:name "Worker"})
;;

;;; ---------------------------------------------------------------------------
;;; Slave Operations
;;; ---------------------------------------------------------------------------

(defn add-slave!*
  "Add a new slave to the registry. Wrapper for ISwarmRegistry/add-slave!

   Arguments:
     registry - ISwarmRegistry implementation
     slave-id - Unique string identifier
     opts     - Map with :name, :status, :depth, :parent, :presets, :cwd, :project-id

   Returns: Transaction report with :tempids"
  [registry slave-id opts]
  (add-slave! registry slave-id opts))

(defn get-slave*
  "Get a slave by ID. Wrapper for ISwarmRegistry/get-slave

   Arguments:
     registry - ISwarmRegistry implementation
     slave-id - Slave identifier string

   Returns: Map with slave attributes or nil"
  [registry slave-id]
  (get-slave registry slave-id))

(defn update-slave!*
  "Update an existing slave. Wrapper for ISwarmRegistry/update-slave!

   Arguments:
     registry - ISwarmRegistry implementation
     slave-id - Slave to update
     updates  - Map of attributes to update (uses :slave/* keys)

   Returns: Transaction report on success, nil if not found"
  [registry slave-id updates]
  (update-slave! registry slave-id updates))

(defn remove-slave!*
  "Remove a slave from the registry. Wrapper for ISwarmRegistry/remove-slave!

   Arguments:
     registry - ISwarmRegistry implementation
     slave-id - Slave to remove

   Returns: Transaction report on success, nil if not found"
  [registry slave-id]
  (remove-slave! registry slave-id))

(defn get-all-slaves*
  "Get all slaves in the registry. Wrapper for ISwarmRegistry/get-all-slaves

   Arguments:
     registry - ISwarmRegistry implementation

   Returns: Seq of slave maps"
  [registry]
  (get-all-slaves registry))

(defn get-slaves-by-status*
  "Get slaves filtered by status. Wrapper for ISwarmRegistry/get-slaves-by-status

   Arguments:
     registry - ISwarmRegistry implementation
     status   - Status keyword (:idle, :busy, :blocked, etc.)

   Returns: Seq of matching slave maps"
  [registry status]
  (get-slaves-by-status registry status))

(defn get-slaves-by-project*
  "Get slaves filtered by project. Wrapper for ISwarmRegistry/get-slaves-by-project

   Arguments:
     registry   - ISwarmRegistry implementation
     project-id - Project ID string

   Returns: Seq of matching slave maps"
  [registry project-id]
  (get-slaves-by-project registry project-id))

;;; ---------------------------------------------------------------------------
;;; Task Operations
;;; ---------------------------------------------------------------------------

(defn add-task!*
  "Add a new task to the registry. Wrapper for ISwarmRegistry/add-task!

   Arguments:
     registry - ISwarmRegistry implementation
     task-id  - Unique identifier (or nil for auto-generated)
     slave-id - Owning slave's id
     opts     - Map with :status, :prompt, :files

   Returns: Transaction report with :tempids"
  [registry task-id slave-id opts]
  (add-task! registry task-id slave-id opts))

(defn get-task*
  "Get a task by ID. Wrapper for ISwarmRegistry/get-task

   Arguments:
     registry - ISwarmRegistry implementation
     task-id  - Task identifier string

   Returns: Map with task attributes or nil"
  [registry task-id]
  (get-task registry task-id))

(defn update-task!*
  "Update an existing task. Wrapper for ISwarmRegistry/update-task!

   Arguments:
     registry - ISwarmRegistry implementation
     task-id  - Task to update
     updates  - Map of attributes to update (uses :task/* keys)

   Returns: Transaction report on success, nil if not found"
  [registry task-id updates]
  (update-task! registry task-id updates))

(defn get-tasks-for-slave*
  "Get tasks for a specific slave. Wrapper for ISwarmRegistry/get-tasks-for-slave

   Arguments:
     registry - ISwarmRegistry implementation
     slave-id - Slave to get tasks for
     status   - Optional status filter keyword

   Returns: Seq of task maps"
  ([registry slave-id]
   (get-tasks-for-slave registry slave-id))
  ([registry slave-id status]
   (get-tasks-for-slave registry slave-id status)))
