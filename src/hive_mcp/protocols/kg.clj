(ns hive-mcp.protocols.kg
  "Protocol definitions for Knowledge Graph storage backends.")

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
    "Reset the connection to a fresh empty database.")

  (close! [this]
    "Close the connection and release resources."))

;;; ============================================================================
;;; Active Store Management
;;; ============================================================================

(defonce ^:private active-store (atom nil))

(defn set-store!
  "Set the active graph store implementation."
  [store]
  {:pre [(satisfies? IKGStore store)]}
  (reset! active-store store)
  store)

(defn get-store
  "Get the active graph store, or throw if none set."
  []
  (or @active-store
      (throw (ex-info "No graph store configured. Call set-store! first."
                      {:hint "Initialize with datascript-store, datalevin-store, or datahike-store"}))))

(defn store-set?
  "Check if a store has been configured."
  []
  (some? @active-store))

(defn clear-store!
  "Clear the active store."
  []
  (when-let [store @active-store]
    (try
      (close! store)
      (catch Exception _)))
  (reset! active-store nil))

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

(defn active-temporal?
  "Check if the active store supports temporal queries."
  []
  (and (store-set?)
       (temporal-store? @active-store)))

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
