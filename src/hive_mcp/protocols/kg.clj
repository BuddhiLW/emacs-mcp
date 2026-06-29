(ns hive-mcp.protocols.kg
  "Protocol definitions for Knowledge Graph storage backends."
  (:require [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IKGStore Protocol (Core Graph Operations)
;;; ============================================================================

(defprotocol IKGStore
  "Storage backend protocol for the Knowledge Graph."

  (ensure-conn! [this]
    "Ensure the connection is initialized.")

  (transact! [this tx-data]
    "Transact data into the store.")

  (query [this q] [this q inputs]
    "Execute a Datalog query against the current DB snapshot.")

  (entity [this eid]
    "Get an entity by its entity ID.")

  (entid [this lookup-ref]
    "Resolve a lookup ref to an entity ID.")

  (pull-entity [this pattern eid]
    "Pull an entity with a pull pattern.")

  (eids-by-attr [this attr]
    "Return a lazy sequence of entity IDs for all entities that have the
     given attribute. Backed by the attribute-first index on each backend
     (DataScript `:aevt`, Datalevin `:ave`, Datahike `:aevt`). Cheap to
     enumerate — does not materialize full entities. Used to drive
     batched pulls over large attribute sets without OOM.")

  (db-snapshot [this]
    "Get the current database snapshot value.")

  (reset-conn! [this]
    "Close and re-open the connection. NON-DESTRUCTIVE — implementations
     must NOT delete persistent data. For persistent backends, the same
     underlying state is re-attached. For ephemeral backends, this returns
     a fresh conn since there is no persistent backing to preserve.

     Destructive operations live on the optional `IPersistentKGStore`
     extension — they only make sense for backends that actually persist.

     Renamed semantics 2026-04-28: prior impl deleted on-disk data, which
     wiped the live KG when called from test fixtures. See AXIOM
     'Never NUKE Data — Destruction Requires Explicit, Loud, Guarded Consent'.")

  (close! [this]
    "Close the connection and release resources. NON-DESTRUCTIVE — does
     NOT delete data on disk."))

;;; ============================================================================
;;; IPersistentKGStore Protocol (Optional — Backends With On-Disk State)
;;; ============================================================================

(defprotocol IPersistentKGStore
  "Optional extension for KG backends that persist data outside the JVM
   (Datahike, Datalevin, etc.). Ephemeral backends (DataScript) do NOT
   satisfy this protocol — destruction is meaningless when there is no
   on-disk state to destroy. ISP-compliant: callers can detect support
   via `(satisfies? IPersistentKGStore store)` before invoking.

   Required by AXIOM 'Never NUKE Data': any function that removes data
   from disk must live behind this protocol AND require an explicit
   confirmation guard at the call site."

  (delete-database! [this confirm]
    "DESTRUCTIVE — delete the underlying on-disk database. Requires
     `confirm` to be the keyword `:i-mean-it`; any other value MUST throw.
     Implementations MUST log a high-severity event before AND after
     deletion fires (`[storage/destruction-fired]` / `[storage/destruction-completed]`).

     Use ONLY when you genuinely want the data gone. Test fixtures must
     never invoke this against a production data path — only against a
     temp directory the fixture itself created."))

;;; ============================================================================
;;; Active Store Management
;;; ============================================================================

(defonce ^:private slot
  (reg/single-slot {:validate #(satisfies? IKGStore %)
                    :on-empty #(throw (ex-info "No graph store configured. Call set-store! first."
                                               {:hint "Initialize with datascript-store, datalevin-store, or datahike-store"}))
                    :teardown close!}))

(defn set-store!
  "Set the active graph store implementation."
  [store]
  (reg/install! slot store))

(defn get-store
  "Get the active graph store, or throw if none set."
  []
  (reg/current slot))

(defn store-set?
  "Check if a store has been configured."
  []
  (reg/present? slot))

(defn clear-store!
  "Clear the active store."
  []
  (reg/clear! slot))

;;; ============================================================================
;;; ITemporalKGStore Protocol (Optional Extension)
;;; ============================================================================

(defprotocol ITemporalKGStore
  "Optional extension for temporal queries."

  (history-db [this]
    "Get a database containing all historical facts.")

  (as-of-db [this tx-or-time]
    "Get the database as of a specific point in time.")

  (since-db [this tx-or-time]
    "Get facts added since a point in time."))

;;; ============================================================================
;;; Helper Functions
;;; ============================================================================

(defn temporal-store?
  "Check if the given store supports temporal queries."
  [store]
  (satisfies? ITemporalKGStore store))

(defn persistent-store?
  "Check if the given store has on-disk state that can be destroyed.
   Ephemeral backends (DataScript) return false; persistent backends
   (Datahike, Datalevin) return true. Callers that need to invoke
   `delete-database!` MUST gate on this predicate."
  [store]
  (satisfies? IPersistentKGStore store))

(defn active-temporal?
  "Check if the active store supports temporal queries."
  []
  (and (store-set?)
       (temporal-store? (get-store))))

(defn kg-store?
  "Check if the given object implements IKGStore."
  [x]
  (satisfies? IKGStore x))

;;; ============================================================================
;;; NoopKGStore (No-op Fallback)
;;; ============================================================================

(defrecord NoopKGStore []
  IKGStore
  (ensure-conn! [_this] nil)
  (transact! [_this _tx-data] nil)
  (query [_this _q] #{})
  (query [_this _q _inputs] #{})
  (entity [_this _eid] nil)
  (entid [_this _lookup-ref] nil)
  (pull-entity [_this _pattern _eid] nil)
  (eids-by-attr [_this _attr] ())
  (db-snapshot [_this] nil)
  (reset-conn! [_this] nil)
  (close! [_this] nil))

(defn noop-store
  "Create a no-op KG store fallback."
  []
  (->NoopKGStore))
