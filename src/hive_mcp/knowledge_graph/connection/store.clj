(ns hive-mcp.knowledge-graph.connection.store
  (:require [hive-dsl.result :as r]
            [hive-mcp.events.core :as events]
            [hive-mcp.knowledge-graph.connection.detect :as detect]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.datascript :as ds-store]
            [hive-mcp.protocols.kg :as pkg]
            [taoensso.timbre :as log]))

(declare store-live? ensure-store! get-conn ensure-conn! ensure-conn reset-conn! delete-database! close! set-backend!)

(def ^:dynamic *test-store*
  "Per-thread override for the active KG store.
   When non-nil, `ensure-store!` returns this directly without
   touching the global proto/store atom. Bound by the :kg-conn
   isolation fixture (hive-mcp.isolation-methods) so KG tests run
   against a fresh ephemeral store without polluting prod state.
   Honors axiom 20260122235103-7151cc29 (Test Isolation Silent Server Death)."
  nil)

(defn store-live?
  "True iff a store is configured AND still satisfies the current
   IKGStore protocol object. Guards against a common live-REPL hazard:
   the protocol ns gets reloaded after the store was constructed, leaving
   a reify/defrecord instance that no longer satisfies the new protocol.
   `satisfies?` then returns false at every write call site, and the
   downstream `r/rescue nil` swallows the resulting AssertionError —
   producing silent transaction drops."
  []
  (and (proto/store-set?)
       (satisfies? pkg/IKGStore (proto/get-store))))

(defn ensure-store!
  "Ensure a store is configured. Auto-detects backend from config.
   Re-initializes when the current store is stale (see `store-live?`).
   Returns *test-store* directly when bound (test-isolation override).

   Fires `:kg.store/ready` on every successful (re)initialization so
   subscribers (e.g. edge-stats warm-up) can react without coupling
   to the wiring path."
  []
  (or *test-store*
      (do
        (when-not (store-live?)
          (when (proto/store-set?)
            (log/warn "Active KG store failed satisfies? IKGStore — recreating"
                      "(likely stale protocol reference after ns reload)")
            (proto/clear-store!))
          (let [backend (detect/detect-backend)]
            (log/info "Auto-initializing KG backend" {:backend backend})
            (case backend
              :datalevin
              (let [store (r/guard Exception nil
                                   (require 'hive-mcp.knowledge-graph.store.datalevin)
                                   (let [create-fn (resolve 'hive-mcp.knowledge-graph.store.datalevin/create-store)]
                                     (create-fn)))]
                (if store
                  (do (proto/set-store! store)
                      (events/dispatch [:kg.store/ready {:backend :datalevin}]))
                  (do
                    (log/error "CRITICAL: Failed to initialize Datalevin, falling back to ephemeral DataScript. KG data on disk will NOT be accessible.")
                    (proto/set-store! (ds-store/create-store))
                    (events/dispatch [:kg.store/ready {:backend :datascript :fallback? true}]))))

              :datahike
              (let [writer-cfg (detect/detect-writer-config)
                    store (r/guard Exception nil
                                   ;; Pre-load konserve namespaces in correct order before datahike.
                                   ;; konserve.impl.defaults requires konserve.impl.storage-layout
                                   ;; which defines -atomic-move. If storage-layout is partially
                                   ;; loaded (e.g. from a concurrent require), method vars don't
                                   ;; get interned and defaults.cljc fails with
                                   ;; "-atomic-move does not exist". Loading the full chain here
                                   ;; prevents the race.
                                   (require 'konserve.protocols)
                                   (require 'konserve.impl.storage-layout)
                                   (require 'konserve.impl.defaults)
                                   (require 'konserve.cache)
                                   (require 'hive-mcp.knowledge-graph.store.datahike)
                                   (let [create-fn (resolve 'hive-mcp.knowledge-graph.store.datahike/create-store)]
                                     (create-fn (when writer-cfg {:writer writer-cfg}))))]
                (if (and store
                         (r/ok? (r/try-effect*
                                 :datahike/ensure-conn-failed
                                 (pkg/ensure-conn! store))))
                  (do (proto/set-store! store)
                      (events/dispatch [:kg.store/ready {:backend :datahike}]))
                  (do
                    (log/error "CRITICAL: Failed to initialize Datahike. Refusing to substitute another KG backend because :kg-backend requested :datahike.")
                    (throw (ex-info "Datahike KG backend unavailable"
                                    {:backend :datahike
                                     :hint "Check :services.datahike.path / HIVE_KG_DB_PATH. The configured path must be a Datahike database, not a container directory."})))))

              ;; Default: DataScript
              (do (proto/set-store! (ds-store/create-store))
                  (events/dispatch [:kg.store/ready {:backend :datascript}])))))
        (proto/get-store))))

(defn get-conn
  "Get the current connection, initializing if needed.
   Preferred entry point for accessing the KG database.
   Returns the raw backend connection."
  []
  (proto/ensure-conn! (ensure-store!)))

(defn ensure-conn!
  "Ensure connection is initialized. Creates if nil.
   Returns the connection."
  []
  (get-conn))

(def ensure-conn ensure-conn!)

(defn reset-conn!
  "Close and reopen the active KG connection. NON-DESTRUCTIVE — does NOT
   delete data on disk. The same on-disk DB is re-attached for persistent
   stores (Datahike, Datalevin); in-memory stores (DataScript) get a fresh
   empty conn since there is no persistent backing.

   For destructive wipe, use `delete-database!` with `:i-mean-it`.

   Renamed semantics 2026-04-28 — see AXIOM 'Never NUKE Data'."
  []
  (proto/reset-conn! (ensure-store!)))

(defn delete-database!
  "DESTRUCTIVE — delete the active KG database from disk. Requires
   `confirm` to be `:i-mean-it`; any other value throws.

   Only persistent backends (`(satisfies? IPersistentKGStore store)`)
   support deletion. Calling against an ephemeral backend (DataScript)
   throws — destruction has no meaning when there is no persistent state.

   Test code that needs a fresh persistent store MUST create a temp
   directory (e.g. via `(System/getProperty \"java.io.tmpdir\")`) and
   call this only against that temp path, never the production data path.

   Emits high-severity telemetry events before and after deletion."
  [confirm]
  (let [store (ensure-store!)]
    (when-not (proto/persistent-store? store)
      (throw (ex-info "delete-database! not supported on ephemeral backend"
                      {:store-class (str (class store))
                       :hint "Ephemeral backends (DataScript) have no persistent state. Use reset-conn! for a fresh in-memory conn."})))
    (proto/delete-database! store confirm)))

(defn close!
  "Close the active store connection.
   Required for Datalevin to flush LMDB."
  []
  (when (proto/store-set?)
    (proto/close! (proto/get-store))))

(defn set-backend!
  "Configure the KG storage backend.

   Arguments:
     backend - :datascript, :datalevin, or :datahike
     opts    - Backend-specific options:
               :datalevin {:db-path \"data/kg/datalevin\"}
               :datahike  {:db-path \"data/kg/datahike\" :backend :file}"

  [backend & [opts]]
  (log/info "Setting KG backend" {:backend backend :opts opts})
  (case backend
    :datascript
    (proto/set-store! (ds-store/create-store))

    :datalevin
    (let [;; Require datalevin store dynamically to avoid hard dep
          _ (require 'hive-mcp.knowledge-graph.store.datalevin)
          create-fn (resolve 'hive-mcp.knowledge-graph.store.datalevin/create-store)
          store (create-fn opts)]
      (if store
        (proto/set-store! store)
        (do
          (log/warn "Datalevin store creation failed, falling back to DataScript")
          (proto/set-store! (ds-store/create-store)))))

    :datahike
    (let [;; Pre-load konserve namespaces in correct order (see ensure-store! comment)
          _ (require 'konserve.protocols)
          _ (require 'konserve.impl.storage-layout)
          _ (require 'konserve.impl.defaults)
          _ (require 'konserve.cache)
          _ (require 'hive-mcp.knowledge-graph.store.datahike)
          create-fn (resolve 'hive-mcp.knowledge-graph.store.datahike/create-store)
          ;; Pass writer config if present (for datahike-server/kabel backends)
          store (create-fn (cond-> (or opts {})
                             (:writer opts) (assoc :writer (:writer opts))))]
      (if store
        (proto/set-store! store)
        (do
          (log/warn "Datahike store creation failed, falling back to DataScript")
          (proto/set-store! (ds-store/create-store)))))

    ;; Unknown backend
    (throw (ex-info "Unknown KG backend" {:backend backend
                                          :valid #{:datascript :datalevin :datahike}}))))
