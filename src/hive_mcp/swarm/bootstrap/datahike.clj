(ns hive-mcp.swarm.bootstrap.datahike
  "DatahikeBootstrap — persistent slave projection backed by a dedicated
   Datahike store at `data/swarm/datahike` (separate from the KG store).

   Design:
   - Separate store (not piggybacking on KG): swarm lifecycle is independent
     of KG lifecycle, and a failure of one must not cascade to the other.
   - Minimal schema: only the fields needed to re-register a slave via
     ISwarmRegistry/add-slave!. Transient state (tasks, claims, wraps,
     waves) remains in the in-memory Datascript registry and is rebuilt
     from the event stream.
   - Lazy connection: store is created on first ensure-conn! call.
   - Write-through: upsert on every slave spawn/update, retract on kill.

   DDD: this is the *Repository* for the Slave aggregate root's durable
   projection. It is NOT the registry — it has no query surface, only
   load/snapshot/forget.

   FP: pure functions for schema and tx-data construction; side effects
   (connect, transact) confined to the record methods."
  (:require [hive-mcp.swarm.bootstrap.protocol :as proto]
            [datahike.api :as d]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private default-db-path "data/swarm/datahike")

;; =============================================================================
;; Schema — minimum fields needed to re-register a slave
;; =============================================================================

(def ^:private schema
  "Datahike schema for the persistent slave projection.
   Uses :db.unique/identity on :slave/id for upsert semantics."
  [{:db/ident       :slave/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Unique slave identifier (primary key)"}
   {:db/ident       :slave/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable name"}
   {:db/ident       :slave/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Last-known status (:idle :working :error ...)"}
   {:db/ident       :slave/depth
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Hierarchy depth"}
   {:db/ident       :slave/cwd
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Working directory"}
   {:db/ident       :slave/project-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Project scope"}
   {:db/ident       :slave/parent-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Parent slave-id (flat string, not a ref — decouples from parent presence)"}
   {:db/ident       :slave/snapshot-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Last snapshot timestamp"}])

;; =============================================================================
;; Pure helpers (schema-free)
;; =============================================================================

(defn- make-config
  "Pure: build a Datahike file-backend config for the given path.

   The store-id is derived from the db-path so distinct paths get distinct
   ids. A previous version used a hardcoded constant which caused Datahike's
   per-JVM connection cache to alias all DatahikeBootstrap instances onto
   the same backing store regardless of path — fine in production (one path
   per process) but broke test isolation in REPL sessions and would surface
   as cross-test state bleed in any test runner that creates multiple
   bootstraps in the same JVM."
  [db-path]
  {:store              {:backend :file
                        :path    db-path
                        :id      (java.util.UUID/nameUUIDFromBytes
                                  (.getBytes (str "hive-mcp-swarm-bootstrap:" db-path)))}
   :schema-flexibility :write
   :index              :datahike.index/persistent-set})

(defn- slave->entity
  "Pure: transform a slave map (from the in-memory registry or from the event
   stream) into a Datahike entity map. Drops nil values — Datahike with
   :write flexibility rejects them."
  [slave-id {:keys [name status depth cwd project-id parent-id]}]
  (cond-> {:slave/id slave-id
           :slave/snapshot-at (java.util.Date.)}
    name       (assoc :slave/name name)
    status     (assoc :slave/status (keyword status))
    depth      (assoc :slave/depth (long depth))
    cwd        (assoc :slave/cwd cwd)
    project-id (assoc :slave/project-id project-id)
    parent-id  (assoc :slave/parent-id parent-id)))

(defn- entity->slave
  "Pure: transform a Datahike pull result back into the slave map shape
   expected by `hive-mcp.swarm.sync/register-slave-from-status!`."
  [e]
  (cond-> {:slave-id (:slave/id e)}
    (:slave/name e)       (assoc :name (:slave/name e))
    (:slave/status e)     (assoc :status (:slave/status e))
    (:slave/depth e)      (assoc :depth (:slave/depth e))
    (:slave/cwd e)         (assoc :cwd (:slave/cwd e))
    (:slave/project-id e) (assoc :project-id (:slave/project-id e))
    (:slave/parent-id e)  (assoc :parent-id (:slave/parent-id e))))

;; =============================================================================
;; Effectful boundary (confined to these two fns + record methods)
;; =============================================================================

(defn- ensure-parent-dir!
  [db-path]
  (let [dir (io/file db-path)
        parent (.getParentFile dir)]
    (when (and parent (not (.exists parent)))
      (log/info "DatahikeBootstrap: creating parent directory" (.getPath parent))
      (.mkdirs parent))))

(defn- ensure-conn!
  "Connect to the swarm bootstrap Datahike store, creating it if needed.
   Idempotent via the conn-atom.

   Thread-safety: a `locking` block guards the create-database / connect
   sequence. Without it, concurrent first-writes from the swarm event
   go-loop race into d/create-database twice on the same path; the second
   throws `File store already exists at path`, the exception is swallowed
   in the outer try/catch, and the bootstrap silently degrades to []. The
   locking object is the conn-atom itself (one per DatahikeBootstrap
   instance) so independent stores don't contend.

   Empty-dir handling mirrors hive-mcp.knowledge-graph.store.datahike: if the
   db-path exists but is empty (e.g. left over from a previous failed run, or
   pre-created by tests / `mkdir -p`), delete the empty dir so create-database
   can take over. A non-empty existing dir is treated as an existing DB."
  [conn-atom cfg]
  ;; Fast path — already connected, no need to acquire the lock.
  (or @conn-atom
      (locking conn-atom
        ;; Re-check inside the lock (double-checked-locking-style guard).
        (when (nil? @conn-atom)
          (try
            (ensure-parent-dir! (get-in cfg [:store :path]))
            (when-not (d/database-exists? cfg)
              (when (= :file (get-in cfg [:store :backend]))
                (let [dir (io/file (get-in cfg [:store :path]))]
                  (when (and (.exists dir) (empty? (.listFiles dir)))
                    (log/info "DatahikeBootstrap: removing empty pre-existing dir" (.getPath dir))
                    (.delete dir))))
              (log/info "DatahikeBootstrap: creating new swarm bootstrap DB" cfg)
              (d/create-database cfg))
            (let [conn (d/connect cfg)]
              ;; Idempotent schema install — safe to re-transact on existing DB.
              (d/transact conn schema)
              (reset! conn-atom conn)
              (log/info "DatahikeBootstrap: connected at" (get-in cfg [:store :path])))
            (catch Exception e
              (log/error "DatahikeBootstrap: connect failed:" (.getMessage e)))))
        @conn-atom)))

;; =============================================================================
;; Record — ISwarmBootstrap implementation
;; =============================================================================

(defrecord DatahikeBootstrap [conn-atom cfg]
  proto/ISwarmBootstrap

  (-load-slaves [_this]
    (if-let [conn (ensure-conn! conn-atom cfg)]
      (try
        (let [db (d/db conn)
              ids (d/q '[:find [?id ...]
                         :where [?e :slave/id ?id]]
                       db)
              entities (->> ids
                            (map #(d/pull db '[:slave/id :slave/name :slave/status
                                               :slave/depth :slave/cwd :slave/project-id
                                               :slave/parent-id]
                                          [:slave/id %]))
                            (map entity->slave)
                            (vec))]
          (log/info "DatahikeBootstrap: loaded" (count entities) "slaves from persistent store")
          entities)
        (catch Exception e
          (log/error "DatahikeBootstrap: load failed:" (.getMessage e))
          []))
      (do
        (log/warn "DatahikeBootstrap: no connection, returning empty")
        [])))

  (-snapshot-slave! [this slave-id slave-data]
    (when-let [conn (ensure-conn! conn-atom cfg)]
      (try
        (d/transact conn [(slave->entity slave-id slave-data)])
        (log/debug "DatahikeBootstrap: snapshot slave" slave-id)
        (catch Exception e
          (log/warn "DatahikeBootstrap: snapshot failed for" slave-id ":" (.getMessage e)))))
    this)

  (-forget-slave! [this slave-id]
    (when-let [conn (ensure-conn! conn-atom cfg)]
      (try
        (d/transact conn [[:db/retractEntity [:slave/id slave-id]]])
        (log/debug "DatahikeBootstrap: forgot slave" slave-id)
        (catch Exception e
          (log/warn "DatahikeBootstrap: forget failed for" slave-id ":" (.getMessage e)))))
    this)

  (-close! [_this]
    (when-let [conn @conn-atom]
      (try
        (d/release conn)
        (reset! conn-atom nil)
        (log/info "DatahikeBootstrap: connection released")
        (catch Exception e
          (log/warn "DatahikeBootstrap: close failed:" (.getMessage e)))))))

(defn make-datahike-bootstrap
  "Construct a DatahikeBootstrap.
   opts: {:db-path string} — defaults to `data/swarm/datahike`.
   Connection is lazy: first -load-slaves / -snapshot-slave! call opens it."
  ([] (make-datahike-bootstrap {}))
  ([{:keys [db-path] :or {db-path default-db-path}}]
   (->DatahikeBootstrap (atom nil) (make-config db-path))))
