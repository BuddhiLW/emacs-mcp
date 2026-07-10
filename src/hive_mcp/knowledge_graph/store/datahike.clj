(ns hive-mcp.knowledge-graph.store.datahike
  "Datahike implementation of IKGStore protocol."
  (:require [datahike.api :as d]
            [datahike.norm.norm :as norm]
            [hive-mcp.knowledge-graph.store.datahike-config :as dhc]
            [hive-mcp.protocols.kg :as kg]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-dsl.result :as r]
            [hive-weave.retry :as retry]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private default-db-path
  "Final fallback only — DatahikeKGConfig owns env/config.edn resolution.
   Kept for safety if hive-di resolution itself fails (shouldn't happen).
   XDG-conformant; no CWD relativity."
  (str (System/getProperty "user.home") "/.local/share/hive-mcp/datahike"))

;; =============================================================================
;; Addon Norms Registry (OCP — addons register their norm resource paths)
;; =============================================================================

(defonce ^:private addon-norms-registry
  (atom []))

(def ^:dynamic *read-timeout-ms*
  "Upper bound for Datahike read operations. Datahike itself may deref
   internal futures; keep that async boundary bounded and recoverable.

   60s tolerates cold-cache reads on 28M-datom stores at boot; the prior
   10s value caused query-with-inputs failures whenever the schema cache
   was empty (e.g. first query after JVM start).

   Dynamic so callers with known-slow workloads (e.g. cold-start full-table
   aggregations from edges/stats) can `binding` a longer ceiling at the
   call site without inflating the global default for every entity lookup."
  60000)

(def ^:dynamic *write-timeout-ms*
  "Upper bound for a single Datahike `transact!` deref. Bigger than the
   read timeout because batch carto KG writes (≤500 edges/chunk per
   `kg-edge-chunk-size`) can outlive a 60s deref under heap pressure
   without being stuck — the writer is just slow, not dead.

   Dynamic so callers with known-large transactions can `binding` a
   longer ceiling without inflating the global default."
  120000)

(defn register-norms!
  "Register a classpath resource path for addon Datahike norms.
   Addons call this during init, before the KG store is first accessed.
   Norms are applied idempotently via datahike.norm/ensure-norms! on ensure-conn!.

   Example: (register-norms! \"my_addon/norms/kg\")"
  [resource-path]
  (swap! addon-norms-registry conj resource-path)
  (log/info "Registered addon KG norms" {:path resource-path}))

(defn- result-error
  "Normalize hive-weave/hive-dsl Result failure shape for ex-info."
  [result]
  (select-keys result [:error :message :class :timeout-ms :name]))

(defn- throw-read-failed!
  [label result]
  (throw (ex-info (str "Datahike KG read failed: " label)
                  (assoc (result-error result) :operation label))))

(defn- throw-write-failed!
  [label result]
  (throw (ex-info (str "Datahike KG write failed: " label)
                  (assoc (result-error result) :operation label))))

(defn- read-with-retry
  "Run bounded Datahike read with auto-heal. Thin adapter over
   `hive-weave.retry/with-recovery`: per-attempt budget from
   `*read-timeout-ms*`, recover via `reopen!` on non-timeout failure,
   throw `Datahike KG read failed` on terminal failure.

   Timeouts surface immediately — retrying a cold-cache scan after
   reopen drops page-cache work in flight and compounds wall-clock
   cost without changing the outcome."
  [label reopen! f]
  (retry/with-recovery
    {:timeout-ms *read-timeout-ms*
     :name       label
     :recover!   reopen!
     :on-failure (fn [l result] (throw-read-failed! l result))}
    f))

(defn- write-with-retry
  "Run bounded Datahike write with auto-heal. Thin adapter over
   `hive-weave.retry/with-recovery`: per-attempt budget from
   `*write-timeout-ms*`, recover via `reopen!` on non-timeout failure,
   throw `Datahike KG write failed` on terminal failure.

   Writer-dead exceptions (SoftReference cast, NoSuchFile during ksv
   rename — both observed under heap pressure when GC reclaims
   SoftReferences mid-commit) classify as non-timeout failures, so
   `reopen!` (drop broken conn, ensure-conn! against same on-disk db)
   runs and the transact retries once. Timeouts surface immediately:
   retry buys nothing if the writer is alive but slow, and a re-run
   can corrupt bookkeeping (e.g. duplicate :db.unique writes on a
   partial commit)."
  [label reopen! f]
  (retry/with-recovery
    {:timeout-ms *write-timeout-ms*
     :name       label
     :recover!   reopen!
     :on-failure (fn [l result] (throw-write-failed! l result))}
    f))

(declare validate-config!)

(defn- validate-config-result
  [cfg]
  (r/try-effect* :datahike/invalid-config
    (validate-config! cfg)
    cfg))

(defn- ensure-database-result
  [cfg]
  (r/let-ok [cfg     (validate-config-result cfg)
             exists? (r/try-effect* :datahike/database-exists-check-failed
                       (d/database-exists? cfg))
             cfg     (if exists?
                       (r/ok cfg)
                       (r/try-effect* :datahike/create-database-failed
                         (log/info "Creating new Datahike database" {:cfg cfg})
                         (d/create-database cfg)
                         cfg))]
    (r/ok cfg)))

(defn- connect-result
  [cfg]
  (r/try-effect* :datahike/connect-failed
    (d/connect cfg)))

(defn- ensure-core-norms-result
  [conn]
  (r/try-effect* :datahike/ensure-core-norms-failed
    (log/info "Applying KG norms" {:path "hive_mcp/norms/kg"})
    (norm/ensure-norms! conn (io/resource "hive_mcp/norms/kg"))
    conn))

(defn- ensure-addon-norms-result
  [conn]
  (r/try-effect* :datahike/ensure-addon-norms-failed
    (doseq [norms-path @addon-norms-registry]
      (when-let [resource (io/resource norms-path)]
        (log/info "Applying addon KG norms" {:path norms-path})
        (norm/ensure-norms! conn resource)))
    conn))

(defn- init-conn-result
  [cfg]
  (log/info "Initializing Datahike KG store" {:cfg cfg})
  (r/let-ok [cfg  (ensure-database-result cfg)
             conn (connect-result cfg)
             conn (ensure-core-norms-result conn)
             conn (ensure-addon-norms-result conn)]
    (r/ok conn)))

(defn- make-writer-config
  "Build the :writer section of Datahike config.
   Supports :self (default local), :datahike-server (HTTP), and :kabel (WebSocket).

   :datahike-server requires {:url \"http://...:4444\" :token \"...\"}
   :kabel requires {:peer-id #uuid \"...\" :local-peer <peer-atom>}

   See https://github.com/replikativ/datahike/blob/main/doc/distributed.md"
  [writer-opts]
  (case (get writer-opts :backend :self)
    :self            {:backend :self}
    :datahike-server {:backend :datahike-server
                      :url (:url writer-opts)
                      :token (:token writer-opts)}
    :kabel           {:backend :kabel
                      :peer-id (:peer-id writer-opts)
                      :local-peer (:local-peer writer-opts)}
    ;; Unknown writer backend — default to local
    {:backend :self}))

(defn- coerce-uuid
  "Accept a UUID, a UUID-formatted string, or nil. Return UUID or nil."
  [v]
  (cond
    (uuid? v)   v
    (string? v) (try (java.util.UUID/fromString v) (catch Throwable _ nil))
    :else       nil))

(defn- resolve-typed-config
  "Resolve DatahikeKGConfig via hive-di. Returns map with :db-path, :store-id,
   :backend (resolved across env > config.edn > defaults). Falls back to
   bare defaults if resolution itself errors (defensive — should not happen)."
  []
  (let [result (dhc/resolve-DatahikeKGConfig)]
    (if (r/ok? result)
      (:ok result)
      (do (log/warn "DatahikeKGConfig resolution failed; using bare defaults"
                    {:errors (:errors result)})
          {:db-path default-db-path :backend :file :store-id nil}))))

(defn- store-id-from-typed
  "Coerce typed-config :store-id (string from env / UUID from EDN) to a UUID,
   or derive the legacy v3 name UUID from 'hive-mcp-kg' when unset.
   Mirrors the prior resolve-default-store-id contract."
  [typed-id]
  (or (coerce-uuid typed-id)
      (java.util.UUID/nameUUIDFromBytes (.getBytes "hive-mcp-kg"))))

(defn- make-config
  "Create Datahike configuration map.
   Resolution order for :db-path / :backend / :store-id:
     1. Explicit caller args (:db-path, :backend, :id)
     2. DatahikeKGConfig resolver (env var, then config.edn, then default)
   Accepts optional :writer key for distributed write backends:
     {:writer {:backend :datahike-server :url \"http://...\" :token \"...\"}}
     {:writer {:backend :kabel :peer-id #uuid \"...\" :local-peer peer-atom}}"
  [& [{:keys [db-path backend index id writer]
       :or {index :datahike.index/persistent-set}}]]
  (let [typed     (resolve-typed-config)
        db-path   (or db-path (:db-path typed))
        backend   (or backend (:backend typed) :file)
        store-id  (or id (store-id-from-typed (:store-id typed)))
        store-cfg (case backend
                    :file {:store {:backend :file
                                   :path db-path
                                   :id store-id}
                           :schema-flexibility :read
                           :index index}
                    (:mem :memory) {:store {:backend :memory
                                            :id store-id}
                                    :schema-flexibility :read
                                    :index index}
                    {:store {:backend :file
                             :path db-path
                             :id store-id}
                     :schema-flexibility :read
                     :index index})
        writer-cfg (when writer (make-writer-config writer))]
    (cond-> store-cfg
      writer-cfg (assoc :writer writer-cfg))))

(defn- validate-config!
  "Validate Datahike configuration and create directories if needed."
  [cfg]
  (when-not (map? cfg)
    (throw (ex-info "Datahike config must be a map" {:cfg cfg})))
  (when-not (get-in cfg [:store :backend])
    (throw (ex-info "Datahike config missing :store :backend" {:cfg cfg})))

  (when (= :file (get-in cfg [:store :backend]))
    (let [db-path (get-in cfg [:store :path])]
      (when (or (nil? db-path) (empty? db-path))
        (throw (ex-info "Datahike file backend requires :store :path"
                        {:cfg cfg})))
      (let [dir (io/file db-path)]
        (when-not (.exists (.getParentFile dir))
          (log/info "Creating Datahike parent directory" {:path (.getParent dir)})
          (.mkdirs (.getParentFile dir))))))
  cfg)

(defrecord DatahikeStore [conn-atom cfg]
  kg/IKGStore

  (ensure-conn! [_this]
    (when (nil? @conn-atom)
      (let [result (init-conn-result cfg)]
        (when (r/err? result)
          (throw (ex-info "Datahike KG connection initialization failed"
                          (assoc result :cfg cfg))))
        (reset! conn-atom (:ok result))))
    (when (nil? @conn-atom)
      (throw (ex-info "Datahike KG connection is nil after initialization"
                      {:cfg cfg})))
    @conn-atom)

  (transact! [this tx-data]
    ;; NOTE: In Datahike 0.8+, `d/transact!` is ASYNC — it returns a
    ;; throwable-promise that must be deref'd for the write to block until
    ;; committed. Without the deref, subsequent queries on the same connection
    ;; see a pre-transact db snapshot (root cause of "KG traverse not
    ;; returning results" — edges were enqueued but not yet committed).
    ;; The sync variant in Datahike is the bangless `d/transact`.
    ;;
    ;; Auto-heal: writer-dead exceptions (SoftReference cast under GC
    ;; pressure, NoSuchFile during ksv rename) reopen the conn and retry
    ;; once, mirroring read-with-retry semantics.
    (write-with-retry "transact"
                      #(kg/reset-conn! this)
                      #(deref (d/transact! (kg/ensure-conn! this) tx-data))))

  (query [this q]
    (read-with-retry "query"
                     #(kg/reset-conn! this)
                     #(d/q q (d/db (kg/ensure-conn! this)))))

  (query [this q inputs]
    (read-with-retry "query-with-inputs"
                     #(kg/reset-conn! this)
                     #(apply d/q q (d/db (kg/ensure-conn! this)) inputs)))

  (entity [this eid]
    (read-with-retry "entity"
                     #(kg/reset-conn! this)
                     #(d/entity (d/db (kg/ensure-conn! this)) eid)))

  (entid [this lookup-ref]
    (read-with-retry "entid"
                     #(kg/reset-conn! this)
                     #(let [[attr val] lookup-ref]
                        (d/q '[:find ?e .
                               :in $ ?attr ?val
                               :where [?e ?attr ?val]]
                             (d/db (kg/ensure-conn! this))
                             attr val))))

  (pull-entity [this pattern eid]
    (read-with-retry "pull-entity"
                     #(kg/reset-conn! this)
                     #(d/pull (d/db (kg/ensure-conn! this)) pattern eid)))

  (eids-by-attr [this attr]
    ;; Datahike supports the same :aevt / :avet / :eavt index taxonomy as
    ;; Datomic/DataScript. `(d/datoms db :aevt attr)` walks the attribute-first
    ;; persistent index without materializing datoms eagerly. We pull :e out
    ;; of each datom and let the caller batch pulls.
    (read-with-retry "eids-by-attr"
                     #(kg/reset-conn! this)
                     #(mapv :e (d/datoms (d/db (kg/ensure-conn! this)) :aevt attr))))

  (db-snapshot [this]
    (read-with-retry "db-snapshot"
                     #(kg/reset-conn! this)
                     #(d/db (kg/ensure-conn! this))))

  (reset-conn! [this]
    ;; NON-DESTRUCTIVE — close conn and reopen against the SAME on-disk DB.
    ;; Renamed semantics 2026-04-28: prior impl called (d/delete-database cfg),
    ;; which silently wiped the live KG when invoked via test fixtures. See
    ;; AXIOM "Never NUKE Data — Destruction Requires Explicit, Loud, Guarded
    ;; Consent". Destructive wipe lives on IPersistentKGStore/delete-database!.
    (log/info "Reopening Datahike KG store (non-destructive)" {:cfg cfg})
    (when-let [c @conn-atom]
      (rescue nil (d/release c)))
    (reset! conn-atom nil)
    (kg/ensure-conn! this))

  (close! [_this]
    (when-let [c @conn-atom]
      (log/info "Closing Datahike KG store" {:cfg cfg})
      (rescue nil (d/release c))
      (reset! conn-atom nil)))

  kg/IPersistentKGStore

  (delete-database! [_this confirm]
    ;; DESTRUCTIVE — guard required. Datahike has on-disk state, so this
    ;; backend extends IPersistentKGStore. Ephemeral backends (DataScript)
    ;; do not extend this protocol; callers must `(satisfies?
    ;; IPersistentKGStore store)` before invoking.
    (when-not (= confirm :i-mean-it)
      (throw (ex-info "delete-database! requires confirm=:i-mean-it"
                      {:passed-confirm confirm
                       :hint "This call deletes the database from disk. Pass :i-mean-it explicitly to proceed."
                       :backend :datahike
                       :db-path (get-in cfg [:store :path])})))
    (log/error "[storage/destruction-fired] Datahike delete-database! invoked"
               {:backend :datahike
                :db-path (get-in cfg [:store :path])
                :stacktrace (mapv str (.getStackTrace (Throwable.)))})
    (when-let [c @conn-atom]
      (rescue nil (d/release c)))
    (when (d/database-exists? cfg)
      (d/delete-database cfg))
    (reset! conn-atom nil)
    (log/error "[storage/destruction-completed] Datahike database deleted"
               {:backend :datahike :db-path (get-in cfg [:store :path])})
    nil)

  kg/ITemporalKGStore

  (history-db [this]
    (d/history (d/db (kg/ensure-conn! this))))

  (as-of-db [this tx-or-time]
    (d/as-of (d/db (kg/ensure-conn! this)) tx-or-time))

  (since-db [this tx-or-time]
    (d/since (d/db (kg/ensure-conn! this)) tx-or-time)))

(defn create-store
  "Create a new Datahike-backed graph store."
  [& [opts]]
  (rescue nil
          (let [cfg (make-config opts)]
            (log/info "Creating Datahike graph store" {:cfg cfg})
            (->DatahikeStore (atom nil) cfg))))

(defn history-db
  "Get full history database for temporal queries."
  [store]
  (d/history (d/db (kg/ensure-conn! store))))

(defn as-of-db
  "Get database as of a specific transaction or timestamp."
  [store tx-or-time]
  (d/as-of (d/db (kg/ensure-conn! store)) tx-or-time))

(defn since-db
  "Get database with only facts added since a transaction or timestamp."
  [store tx-or-time]
  (d/since (d/db (kg/ensure-conn! store)) tx-or-time))

(defn query-history
  "Query against the full history database."
  [store q & inputs]
  (if (seq inputs)
    (apply d/q q (history-db store) inputs)
    (d/q q (history-db store))))

(defn query-as-of
  "Query the database as it was at a specific point in time."
  [store tx-or-time q & inputs]
  (if (seq inputs)
    (apply d/q q (as-of-db store tx-or-time) inputs)
    (d/q q (as-of-db store tx-or-time))))
