;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.store.proximum
  "ProximumVecStore — IVecStore adapter over the proximum HNSW index
   (https://github.com/replikativ/proximum).

   Two design constraints drive the shape of this namespace:

   1. Late-bind via `requiring-resolve` so a hive-mcp deploy that omits
      the `org.replikativ/proximum` jar still compiles. The dep IS on
      the classpath in the canonical build, but addon-style isolation
      keeps the slot system honest.

   2. The proximum API is *immutable* (every op returns a new index
      value). The slot registry caches one handle per slot and shares
      it across the swarm, so we wrap the immutable index in an atom
      and synchronise mutations under it. Concurrent writers serialise
      through the atom, concurrent readers see a consistent snapshot.

   Lifecycle bridging:
     The slot registry calls `pkg/ensure-conn!` and `pkg/close!` (the
     IKGStore lifecycle). ProximumVecStore satisfies BOTH IKGStore
     (lifecycle methods only — data methods are noops) AND IVecStore
     (the real vector verbs). Vec callers do `(satisfies?
     vec/IVecStore store)` before invoking vector methods.

   Errors:
     Every external call is wrapped in `hive-dsl.result/rescue` per the
     'never raw try/catch' axiom. Failures log and return a fallback
     (empty seq, `this`, nil) — the caller decides how to surface a
     degraded slot via `store-status`."
  (:require [clojure.java.io :as io]
            [hive-dsl.result :as r :refer [rescue]]
            [hive-mcp.knowledge-graph.store.proximum-config :as cfg]
            [hive-mcp.protocols.kg :as pkg]
            [hive-mcp.protocols.vec :as pvec]
            [taoensso.timbre :as log]))

;; -----------------------------------------------------------------------------
;; Late-bound proximum API resolver — never throws if the jar is missing
;; -----------------------------------------------------------------------------

(defn- resolve-fn
  "Late-bound require. Returns the resolved var or nil. NEVER throws —
   missing classpath surfaces as a `:slot/factory-failed` upstream
   instead of crashing the JVM."
  [sym]
  (rescue nil (requiring-resolve sym)))

(defn- proximum-create-index
  "Resolve `proximum.core/create-index`. Lazy on first call."
  []
  (resolve-fn 'proximum.core/create-index))

(defn- proximum-insert
  "Resolve `proximum.core/insert`."
  []
  (resolve-fn 'proximum.core/insert))

(defn- proximum-search
  "Resolve `proximum.core/search`. Returns `({:id :distance} ...)`
   ordered ascending by distance."
  []
  (resolve-fn 'proximum.core/search))

(defn- proximum-delete
  "Resolve `proximum.core/delete`. External-id keyed."
  []
  (resolve-fn 'proximum.core/delete))

(defn- proximum-count-vectors
  "Resolve `proximum.core/count-vectors`."
  []
  (resolve-fn 'proximum.core/count-vectors))

(defn- proximum-close!
  "Resolve `proximum.core/close!`. Returns a core.async channel — we
   ignore the channel for the sync close path; the JVM teardown hook
   releases mmap regardless."
  []
  (resolve-fn 'proximum.core/close!))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- validate-db-path!
  "Validate and ensure the Konserve filestore parent directory exists."
  [db-path]
  (when (or (nil? db-path) (and (string? db-path) (empty? db-path)))
    (throw (ex-info "Proximum :db-path cannot be nil or empty"
                    {:db-path db-path})))
  (let [dir (io/file db-path)]
    (when-not (.exists dir)
      (log/info "Creating Proximum store directory" {:path db-path})
      (.mkdirs dir)))
  db-path)

(defn- coerce-floats
  "Tolerate `[1.0 2.0]` / `(1.0 2.0)` callers and convert to a primitive
   `^floats` array. Pass-through when already `^floats`."
  ^floats [v]
  (cond
    (nil? v)                       (float-array 0)
    (instance? (Class/forName "[F") v) v
    (sequential? v)                (float-array v)
    :else (throw (ex-info "vector must be a sequential or float-array"
                          {:got (type v)}))))

(defn- store-id-for-path
  "Derive a deterministic UUID for a Konserve store given the on-disk
   path. proximum requires `:store-config :id (UUID)`; using the path
   hash keeps reopens stable across restarts."
  ^java.util.UUID [^String path]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str "hive-proximum:" path))))

(defn- build-create-config
  "Assemble the proximum `create-index` config map for the file
   backend. Kept private so callers depend on the IVecStore facade,
   not the proximum option surface."
  [{:keys [db-path dim ^java.util.UUID store-id mmap-dir]}]
  {:type         :hnsw
   :dim          (long dim)
   :store-config {:backend :file
                  :path    db-path
                  :id      store-id}
   :mmap-dir     (or mmap-dir db-path)})

(defn- open-index!
  "Open or create the underlying proximum HNSW index. Returns the
   index value, or nil if the proximum classpath is missing."
  [db-path dim]
  (when-let [create-fn (proximum-create-index)]
    (validate-db-path! db-path)
    (let [cfg (build-create-config
                {:db-path  db-path
                 :dim      dim
                 :store-id (store-id-for-path db-path)
                 :mmap-dir db-path})]
      (log/info "Opening Proximum HNSW index" {:path db-path :dim dim})
      (rescue nil (create-fn cfg)))))

;; -----------------------------------------------------------------------------
;; ProximumVecStore record
;;
;; State:
;;   idx-atom — atom wrapping the immutable proximum index value.
;;              Mutations swap; reads deref. nil until ensure-conn!.
;;   db-path  — Konserve filestore directory (string).
;;   dim      — embedding dimensionality (long).
;; -----------------------------------------------------------------------------

(defrecord ProximumVecStore [idx-atom db-path dim]

  pvec/IVecStore

  (ensure-conn! [this]
    (when (nil? @idx-atom)
      (when-let [idx (open-index! db-path dim)]
        (reset! idx-atom idx)))
    @idx-atom)

  (upsert! [this id vector]
    (pvec/upsert! this id vector nil))

  (upsert! [this id vector metadata]
    (pvec/ensure-conn! this)
    (when-let [insert-fn (proximum-insert)]
      (let [v (coerce-floats vector)]
        (rescue nil
                (swap! idx-atom
                       (fn [idx]
                         (if metadata
                           (insert-fn idx id v metadata)
                           (insert-fn idx id v)))))))
    this)

  (search [this query k]
    (pvec/search this query k nil))

  (search [this query k opts]
    (pvec/ensure-conn! this)
    (or (when-let [search-fn (proximum-search)]
          (let [q (coerce-floats query)]
            (rescue nil
                    (if opts
                      (search-fn @idx-atom q k opts)
                      (search-fn @idx-atom q k)))))
        ()))

  (delete! [this id]
    (pvec/ensure-conn! this)
    (when-let [delete-fn (proximum-delete)]
      (rescue nil
              (swap! idx-atom delete-fn id)))
    this)

  (count [this]
    (pvec/ensure-conn! this)
    (or (when-let [count-fn (proximum-count-vectors)]
          (rescue 0 (count-fn @idx-atom)))
        0))

  (close! [_this]
    (when-let [idx @idx-atom]
      (log/info "Closing Proximum HNSW index" {:path db-path})
      (when-let [close-fn (proximum-close!)]
        (rescue nil (close-fn idx)))
      (reset! idx-atom nil)))

  (store-status [_this]
    {:backend :proximum
     :path    db-path
     :dim     dim
     :open?   (some? @idx-atom)
     :count   (or (when-let [count-fn (proximum-count-vectors)]
                    (when-let [idx @idx-atom]
                      (rescue 0 (count-fn idx))))
                  0)})

  ;; ---------------------------------------------------------------------------
  ;; IKGStore lifecycle bridge — lets the SlotRegistry treat a vec slot
  ;; uniformly. Data methods (transact!/query/...) are noops because a
  ;; vector index has no Datalog facet.
  ;; ---------------------------------------------------------------------------
  pkg/IKGStore

  (ensure-conn! [this]
    (pvec/ensure-conn! this))

  (transact!    [_this _tx-data]            nil)
  (query        [_this _q]                  #{})
  (query        [_this _q _inputs]          #{})
  (entity       [_this _eid]                nil)
  (entid        [_this _lookup-ref]         nil)
  (pull-entity  [_this _pattern _eid]       nil)
  (eids-by-attr [_this _attr]               ())
  (db-snapshot  [_this]                     nil)

  (reset-conn! [this]
    ;; NON-DESTRUCTIVE — close the live handle and re-open the SAME
    ;; on-disk Konserve filestore. See AXIOM "Never NUKE Data".
    (log/info "Reopening Proximum vec store (non-destructive)" {:path db-path})
    (pvec/close! this)
    (pvec/ensure-conn! this))

  (close! [this]
    (pvec/close! this)))

;; -----------------------------------------------------------------------------
;; Public constructor — mirrors `(dl/create-store {...})` ergonomics
;; -----------------------------------------------------------------------------

(defn- resolve-typed-config
  "Resolve ProximumKGConfig via hive-di. Returns map with :db-path /
   :dim coalesced across env > config.edn > XDG default. Falls back
   to bare defaults if resolution itself errors."
  []
  (let [result (cfg/resolve-ProximumKGConfig)]
    (if (r/ok? result)
      (:ok result)
      (do (log/warn "ProximumKGConfig resolution failed; using bare defaults"
                    {:errors (:errors result)})
          {:db-path cfg/default-db-path
           :dim     cfg/default-dim}))))

(defn create-store
  "Create a new Proximum-backed IVecStore.

   Resolution order:
     1. Explicit caller args (:db-path, :dim)
     2. ProximumKGConfig (env > config.edn > XDG default)

   The store is opened lazily on first `ensure-conn!` — construction is
   cheap and side-effect free (just builds the record)."
  [& [{:keys [db-path dim]}]]
  (rescue nil
          (let [resolved (resolve-typed-config)
                resolved-path (or db-path (:db-path resolved))
                resolved-dim  (or dim     (:dim     resolved))]
            (log/info "Creating Proximum vec store"
                      {:path resolved-path :dim resolved-dim})
            (->ProximumVecStore (atom nil) resolved-path (long resolved-dim)))))
