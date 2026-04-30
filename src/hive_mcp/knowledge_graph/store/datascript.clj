(ns hive-mcp.knowledge-graph.store.datascript
  "DataScript implementation of IKGStore protocol.

   In-memory Datalog store. Fast, no persistence, ideal for tests
   and the default backend."

  (:require [datascript.core :as d]
            [hive-mcp.protocols.kg :as kg]
            [hive-mcp.knowledge-graph.schema :as schema]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord DataScriptStore [conn-atom]
  kg/IKGStore

  (ensure-conn! [_this]
    (when (nil? @conn-atom)
      (log/info "Initializing DataScript KG store (in-memory)")
      (reset! conn-atom (d/create-conn (schema/full-schema))))
    @conn-atom)

  (transact! [this tx-data]
    (d/transact! (kg/ensure-conn! this) tx-data))

  (query [this q]
    (d/q q @(kg/ensure-conn! this)))

  (query [this q inputs]
    (apply d/q q @(kg/ensure-conn! this) inputs))

  (entity [this eid]
    (d/entity @(kg/ensure-conn! this) eid))

  (entid [this lookup-ref]
    (d/entid @(kg/ensure-conn! this) lookup-ref))

  (pull-entity [this pattern eid]
    (d/pull @(kg/ensure-conn! this) pattern eid))

  (eids-by-attr [this attr]
    ;; DataScript :aevt index is sorted attribute, entity, value — iterating
    ;; gives one datom per entity-attr pair in entity order. For :db.cardinality/one
    ;; attributes (all :kg-edge/* today) that's one datom per entity, so we don't
    ;; need to dedupe. `d/datoms` returns a lazy iterator over the index, so the
    ;; outer map stays lazy as long as the caller consumes it incrementally.
    (map :e (d/datoms @(kg/ensure-conn! this) :aevt attr)))

  (db-snapshot [this]
    @(kg/ensure-conn! this))

  (reset-conn! [_this]
    ;; Ephemeral store — no persistent backing. "Reset" creates a fresh
    ;; conn by definition; nothing to delete because nothing was ever
    ;; persisted. DataScript intentionally does NOT extend
    ;; IPersistentKGStore — `delete-database!` is meaningless here and
    ;; callers must gate on `(satisfies? IPersistentKGStore store)` first.
    (log/debug "Resetting DataScript KG store (ephemeral)")
    (reset! conn-atom (d/create-conn (schema/full-schema)))
    @conn-atom)

  (close! [_this]
    ;; No-op for DataScript (in-memory, nothing to close)
    nil))

(defn create-store
  "Create a new DataScript-backed graph store.
   Returns an IKGStore implementation."
  []
  (log/info "Creating DataScript graph store (in-memory)")
  (->DataScriptStore (atom nil)))
