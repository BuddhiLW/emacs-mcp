(ns hive-mcp.knowledge-graph.versioning
  "Yggdrasil version control integration for Knowledge Graph.

   Wraps a DatahikeStore with Yggdrasil protocols to enable:
   - Git-like branching for experimental knowledge exploration
   - Snapshot-based time travel (beyond Datahike's built-in as-of)
   - Branch merging for multi-agent knowledge synthesis
   - Commit history traversal

   Architecture:
   ┌──────────────────────────────────────────────────┐
   │              versioning.clj (this)              │
   │  - create-versioned-store                        │
   │  - branch! / checkout / branches                 │
   │  - snapshot-id / parent-ids                      │
   │  - merge! / history                              │
   └─────────────────────┬────────────────────────────┘
                         │ wraps
   ┌─────────────────────▼────────────────────────────┐
   │        yggdrasil.adapters.datahike              │
   │  - DatahikeSystem record                         │
   │  - Implements Snapshotable, Branchable,          │
   │    Graphable, Mergeable protocols                │
   └─────────────────────┬────────────────────────────┘
                         │ operates on
   ┌─────────────────────▼────────────────────────────┐
   │     hive-mcp.knowledge-graph.store.datahike     │
   │  - DatahikeStore record                          │
   │  - Implements IGraphStore, ITemporalGraphStore   │
   └──────────────────────────────────────────────────┘

   Note: This namespace shadows clojure.core/ancestors with a
   versioning-specific function for traversing the commit graph."

  (:refer-clojure :exclude [ancestors])
  (:require [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State Management
;; =============================================================================

;; Holds the active Yggdrasil system (DatahikeSystem record).
;; Unlike the store atom in protocol.clj, this wraps the store for versioning.
(defonce ^:private versioned-system (atom nil))

;; =============================================================================
;; Dynamic Yggdrasil Loading
;; =============================================================================

(defn- require-yggdrasil!
  "Dynamically require Yggdrasil namespaces.
   Returns true if available, false otherwise."
  []
  (r/rescue false
            (require 'yggdrasil.adapters.datahike)
            (require 'yggdrasil.protocols)
            true))

(defn- create-yggdrasil-system
  "Create a Yggdrasil DatahikeSystem from a Datahike connection.
   Returns nil if Yggdrasil is not available."
  [conn system-name]
  (when (require-yggdrasil!)
    (let [create-fn (resolve 'yggdrasil.adapters.datahike/create)]
      (when create-fn
        (create-fn conn {:system-name system-name})))))

;; =============================================================================
;; Protocol Resolution Helpers
;; =============================================================================

(defn- protocol-fn
  "Resolve a Yggdrasil protocol function by name."
  [protocol-fn-name]
  (when (require-yggdrasil!)
    (resolve (symbol "yggdrasil.protocols" (name protocol-fn-name)))))

(defmacro with-protocol
  "Execute body with resolved protocol function, or return default on failure.
   Note: Introduces `pfn` binding for use in body."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [fn-name default-val & body]
  `(if-let [~'pfn (protocol-fn ~fn-name)]
     (try
       ~@body
       (catch Exception e#
         (log/error "Protocol call failed" {:fn ~fn-name :error (.getMessage e#)})
         ~default-val))
     (do
       (log/warn "Protocol function not available" {:fn ~fn-name})
       ~default-val)))

;; =============================================================================
;; Public API - Store Creation
;; =============================================================================

(defn versioning-available?
  "Check if versioning (Yggdrasil) is available."
  []
  (require-yggdrasil!))

(defn create-versioned-store
  "Create a versioned Knowledge Graph store backed by Datahike.

   Arguments:
     opts - Optional map with:
       :db-path     - Path for file storage (default: data/kg/datahike-versioned)
       :backend     - :file or :mem (default: :file)
       :system-name - Name for Yggdrasil system (default: auto-generated)

   Returns map with:
     :store  - DatahikeStore implementing IGraphStore
     :system - Yggdrasil DatahikeSystem for versioning"

  [& [opts]]
  (let [db-path (or (:db-path opts) "data/kg/datahike-versioned")
        backend (or (:backend opts) :file)
        system-name (or (:system-name opts) (str "hive-kg-" (subs (str (random-uuid)) 0 8)))]
    (log/info "Creating versioned KG store" {:db-path db-path :backend backend :system-name system-name})

    ;; Create Datahike store
    (require 'hive-datahike.kg.store)
    (let [create-store-fn (resolve 'hive-datahike.kg.store/create-store)
          store (create-store-fn {:db-path db-path :backend backend})]

      (if-not store
        (do
          (log/error "Failed to create Datahike store")
          nil)

        ;; Initialize store connection
        (let [conn (proto/ensure-conn! store)]
          ;; Try to wrap with Yggdrasil
          (if-let [system (create-yggdrasil-system conn system-name)]
            (do
              (reset! versioned-system system)
              (log/info "Created versioned store with Yggdrasil"
                        {:system-name system-name})
              {:store store :system system})
            (do
              (log/warn "Created plain Datahike store (Yggdrasil unavailable)")
              {:store store :system nil})))))))

(defn get-versioned-system
  "Get the active Yggdrasil system, or nil if not initialized."
  []
  @versioned-system)

(defn set-versioned-system!
  "Set the active Yggdrasil system.
   Used when creating a versioned store."
  [system]
  (reset! versioned-system system))

;; =============================================================================
;; Public API - Branching
;; =============================================================================

(defn branches
  "List all branch names in the versioned store.
   Returns set of branch keywords, or nil if versioning unavailable."
  []
  (when-let [sys @versioned-system]
    (with-protocol 'branches nil
      (pfn sys))))

(defn current-branch
  "Get the name of the current branch.
   Returns keyword, or nil if versioning unavailable."
  []
  (when-let [sys @versioned-system]
    (with-protocol 'current-branch nil
      (pfn sys))))

(defn branch!
  "Create a new branch from current state or from a specific snapshot.

   Arguments:
     name - Branch name (keyword)
     from - Optional source snapshot-id or branch name to branch from

   Returns the updated Yggdrasil system, or nil on failure.

   Example:
     (branch! :experiment)           ; branch from current
     (branch! :bugfix :main)         ; branch from :main
     (branch! :replay \"abc-123\")   ; branch from commit"
  ([name]
   (when-let [sys @versioned-system]
     (with-protocol 'branch! nil
       (let [new-sys (pfn sys name)]
         (reset! versioned-system new-sys)
         new-sys))))
  ([name from]
   (when-let [sys @versioned-system]
     (with-protocol 'branch! nil
       (let [new-sys (pfn sys name from)]
         (reset! versioned-system new-sys)
         new-sys)))))

(defn delete-branch!
  "Delete a branch.

   Arguments:
     name - Branch name (keyword) to delete

   Returns the updated Yggdrasil system, or nil on failure."
  [name]
  (when-let [sys @versioned-system]
    (with-protocol 'delete-branch! nil
      (pfn sys name))))

(defn checkout
  "Switch to a different branch.

   Arguments:
     name - Branch name (keyword) to switch to

   Returns a NEW Yggdrasil system pointing to the branch.
   The original system remains unchanged (value semantics).

   IMPORTANT: Updates the active versioned-system atom."
  [name]
  (when-let [sys @versioned-system]
    (with-protocol 'checkout nil
      (let [new-sys (pfn sys name)]
        (reset! versioned-system new-sys)
        new-sys))))

;; =============================================================================
;; Public API - Snapshots
;; =============================================================================

(defn snapshot-id
  "Get the current snapshot (commit) ID.
   Returns UUID string, or nil if versioning unavailable."
  []
  (when-let [sys @versioned-system]
    (with-protocol 'snapshot-id nil
      (pfn sys))))

(defn parent-ids
  "Get parent snapshot IDs of the current state.
   Returns set of UUID strings, or nil if versioning unavailable."
  []
  (when-let [sys @versioned-system]
    (with-protocol 'parent-ids nil
      (pfn sys))))

(defn snapshot-meta
  "Get metadata for a specific snapshot.

   Arguments:
     snap-id - Snapshot UUID string

   Returns map with :snapshot-id, :parent-ids, :timestamp, :branch."
  [snap-id]
  (when-let [sys @versioned-system]
    (with-protocol 'snapshot-meta nil
      (pfn sys snap-id))))

(defn as-of
  "Get a read-only view of the database at a specific snapshot.

   Arguments:
     snap-id - Snapshot UUID string

   Returns database value at that point in time."
  [snap-id]
  (when-let [sys @versioned-system]
    (with-protocol 'as-of nil
      (pfn sys snap-id))))

;; =============================================================================
;; Public API - History & Graph
;; =============================================================================

(defn history
  "Get commit history for the current branch.

   Arguments:
     opts - Optional map with:
       :limit - Maximum number of commits (default: 100)

   Returns vector of snapshot-id strings, newest first."
  [& [opts]]
  (when-let [sys @versioned-system]
    (with-protocol 'history []
      (pfn sys (or opts {})))))

(defn ancestors
  "Get all ancestor snapshot IDs of a given snapshot.

   Arguments:
     snap-id - Snapshot UUID string

   Returns vector of ancestor snapshot-id strings."
  [snap-id]
  (when-let [sys @versioned-system]
    (with-protocol 'ancestors []
      (pfn sys snap-id))))

(defn ancestor?
  "Check if snapshot A is an ancestor of snapshot B.

   Arguments:
     a - Potential ancestor snapshot-id
     b - Descendant snapshot-id

   Returns boolean."
  [a b]
  (when-let [sys @versioned-system]
    (with-protocol 'ancestor? false
      (pfn sys a b))))

(defn common-ancestor
  "Find the most recent common ancestor of two snapshots.

   Arguments:
     a - First snapshot-id
     b - Second snapshot-id

   Returns snapshot-id string, or nil if unrelated."
  [a b]
  (when-let [sys @versioned-system]
    (with-protocol 'common-ancestor nil
      (pfn sys a b))))

(defn commit-graph
  "Get the full commit DAG structure.

   Returns map with:
     :nodes   - {id {:parent-ids #{...} :meta {...}}}
     :branches - {:main id ...}
     :roots   - #{root-ids}"
  []
  (when-let [sys @versioned-system]
    (with-protocol 'commit-graph nil
      (pfn sys))))

;; =============================================================================
;; Public API - Merging
;; =============================================================================

(defn merge!
  "Merge a source branch/snapshot into the current branch.

   Arguments:
     source - Source branch keyword or snapshot-id string
     opts   - Optional map with:
       :tx-data  - Transaction data to include in merge
       :tx-meta  - Transaction metadata

   Returns the updated Yggdrasil system, or nil on failure.

   Example:
     (merge! :experiment)               ; merge experiment into current
     (merge! \"abc-123\")               ; merge specific commit
     (merge! :feature {:tx-meta {:author \"agent-1\"}})"
  ([source]
   (merge! source {}))
  ([source opts]
   (when-let [sys @versioned-system]
     (with-protocol 'merge! nil
       (let [new-sys (pfn sys source opts)]
         (reset! versioned-system new-sys)
         new-sys)))))

(defn conflicts
  "Detect conflicts between two snapshots without merging.

   Arguments:
     a - First snapshot-id or branch
     b - Second snapshot-id or branch

   Returns sequence of conflict descriptors (empty if no conflicts)."
  [a b]
  (when-let [sys @versioned-system]
    (with-protocol 'conflicts []
      (pfn sys a b))))

(defn diff
  "Compute the difference between two snapshots.

   Arguments:
     a - Source snapshot-id or branch
     b - Target snapshot-id or branch

   Returns delta representation (system-specific)."
  [a b]
  (when-let [sys @versioned-system]
    (with-protocol 'diff nil
      (pfn sys a b))))

;; =============================================================================
;; Public API - System Identity
;; =============================================================================

(defn system-id
  "Get the unique identifier for this versioned system."
  []
  (when-let [sys @versioned-system]
    (with-protocol 'system-id nil
      (pfn sys))))

(defn system-type
  "Get the system type keyword (:datahike)."
  []
  (when-let [sys @versioned-system]
    (with-protocol 'system-type nil
      (pfn sys))))

(defn capabilities
  "Get the capability map for this system."
  []
  (when-let [sys @versioned-system]
    (with-protocol 'capabilities nil
      (pfn sys))))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn status
  "Get a summary of the current versioning state.

   Returns map with:
     :available    - Whether versioning is available
     :system-id    - System identifier
     :branch       - Current branch
     :snapshot-id  - Current commit
     :branches     - All branches"
  []
  (if-let [_sys @versioned-system]
    {:available true
     :system-id (system-id)
     :branch (current-branch)
     :snapshot-id (snapshot-id)
     :branches (branches)}
    {:available false
     :system-id nil
     :branch nil
     :snapshot-id nil
     :branches nil}))
