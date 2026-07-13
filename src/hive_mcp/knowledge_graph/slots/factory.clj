;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.factory
  "LateBoundFactory — Factory Method implementation of IBackendFactory.

   Constructs an IKGStore for a backend keyword. Late-bound (requiring-resolve)
   so consumers without datahike on the classpath can still build a Datalevin
   slot, and vice versa.

   Adding a new backend:
     1. Implement IKGStore in hive-mcp.knowledge-graph.store.<backend>
     2. Add a `defmethod backend->store` here keyed on the backend kw
     3. Add the backend to `supported-backends-set`

   No changes to the resolver, registry, or facade — closed for modification,
   open for extension (OCP)."
  (:require [hive-dsl.result :as r]
            [hive-mcp.protocols.kg :as pkg]
            [hive-mcp.knowledge-graph.slots.protocol :as p]
            [taoensso.timbre :as log]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-mcp.knowledge-graph.store.datalevin-config :as dlc]
            [hive-mcp.knowledge-graph.store.datahike-config :as dhc]))

;; -----------------------------------------------------------------------------
;; Resolution helpers
;; -----------------------------------------------------------------------------

(defn- resolve-fn
  "Late-bound require — never throws."
  [sym]
  (r/rescue nil (requiring-resolve sym)))

(defn- invoke-create-fn
  "Resolve `create-store-sym` and call it. The 2-arity variant forwards
   an `opts` map to the create-fn (datalevin's `create-store` accepts
   `{:db-path :extra-schema :recovery-policy}`). Returns the IKGStore
   or nil."
  ([create-store-sym] (invoke-create-fn create-store-sym nil))
  ([create-store-sym opts]
   (when-let [create-fn (resolve-fn create-store-sym)]
     (r/rescue nil
       (if (seq opts) (create-fn opts) (create-fn))))))

(defn- datalevin-opts
  "Host-owned injection for the domain-agnostic datalevin sibling. Caller opts
   win over resolved config defaults."
  [opts]
  (let [cfg (let [res (dlc/resolve-DatalevinKGConfig)] (if (r/ok? res) (:ok res) {}))]
    (merge {:db-path     (:db-path cfg)
            :cache-limit (:cache-limit cfg)
            :base-schema (schema/full-schema)}
           opts)))

(defn- datahike-opts
  "Host-owned injection for the domain-agnostic datahike sibling. :store-name +
   raw :store-id reproduce the legacy store-id UUID via the sibling make-config.
   Caller opts win."
  [opts]
  (let [cfg (let [res (dhc/resolve-DatahikeKGConfig)] (if (r/ok? res) (:ok res) {}))]
    (merge {:db-path             (:db-path cfg)
            :backend             (:backend cfg)
            :id                  (:store-id cfg)
            :store-name          "hive-mcp-kg"
            :index               :datahike.index/persistent-set
            :core-norms-resource "hive_mcp/norms/kg"}
           opts)))

;; -----------------------------------------------------------------------------
;; Per-backend factories — multimethod gives OCP for new backends
;;
;; Dispatch on the backend keyword. The opts arg is a map that the
;; per-backend defmethod may consume (e.g. datalevin forwards
;; `:recovery-policy` to create-store) or ignore.
;; -----------------------------------------------------------------------------

(defmulti backend->store
  "Construct a fresh IKGStore for `backend`. Default impl returns nil so an
   unknown backend surfaces as `:slot/missing-backend` upstream rather
   than throwing.

   2-arity (backend, opts). Single-arity is a back-compat shim that
   passes `{}`."
  (fn [backend & _] backend))

(defmethod backend->store :datalevin
  ([be] (backend->store be {}))
  ([_ opts]
   (invoke-create-fn 'hive-datalevin.kg.store/create-store (datalevin-opts opts))))

(defmethod backend->store :datahike
  ([be] (backend->store be {}))
  ([_ opts]
   ;; Reuse the live active-store unless the caller demands a distinct store
   ;; (:fresh?), so the :default slot doesn't split-brain vs connection.clj.
   (or (when (and (not (:fresh? opts)) (pkg/store-set?)) (pkg/get-store))
       (invoke-create-fn 'hive-datahike.kg.store/create-store (datahike-opts opts)))))

(defmethod backend->store :datascript
  ([be] (backend->store be {}))
  ([_ _opts]
   (invoke-create-fn 'hive-mcp.knowledge-graph.store.datascript/create-store)))

(defmethod backend->store :proximum
  ([be] (backend->store be {}))
  ([_ _opts]
   ;; STORAGE-2 phase 2: Proximum HNSW vector store. Implementation lives
   ;; in the `hive-proximum` consumer project (DIP — hive-mcp depends on
   ;; the IVecStore + IKGStore abstractions, hive-proximum provides the
   ;; concrete record). The returned ProximumVecStore satisfies BOTH
   ;; IVecStore (vector verbs) and IKGStore (lifecycle only — data
   ;; methods noop) so the SlotRegistry's ensure-conn! path works
   ;; unchanged. Vec callers gate on `(satisfies? pvec/IVecStore store)`
   ;; before invoking vector methods.
   (invoke-create-fn 'hive-proximum.vec.store/create-store)))

(defmethod backend->store :default
  ([_] nil)
  ([_ _opts] nil))

(def ^:const supported-backends-set
  "Backends this factory can construct. Keep in lockstep with the
   `defmethod backend->store` declarations above."
  #{:datalevin :datahike :datascript :proximum})

;; -----------------------------------------------------------------------------
;; LateBoundFactory — Factory Method implementation
;; -----------------------------------------------------------------------------

(defn- call-factory-fn
  "Call `factory-fn` with `[backend opts]` if it supports 2-arity, else
   `[backend]`. Lets the existing 1-arg test fixtures keep working
   while production sites pass opts through."
  [factory-fn backend opts]
  (try
    (factory-fn backend opts)
    (catch clojure.lang.ArityException _
      (factory-fn backend))))

(defrecord LateBoundFactory [factory-fn]
  p/IBackendFactory
  (make-store [this backend]
    (p/make-store this backend {}))
  (make-store [_ backend opts]
    (let [store (call-factory-fn factory-fn backend opts)]
      (when-not store
        (log/warn "kg-slot factory produced no store"
                  {:backend backend
                   :hint    (if (contains? supported-backends-set backend)
                              "create-store returned nil — check db-path / classpath"
                              "backend not in supported-backends-set")}))
      store))
  (supported-backends [_] supported-backends-set))

(defn ->factory
  "Build a factory. Production default uses the multimethod chain above.
   Tests pass a custom `factory-fn` to inject in-memory stores.

   `factory-fn` may be 1-arity `(fn [backend])` or 2-arity
   `(fn [backend opts])`. The 2-arity form receives per-slot opts (e.g.
   `{:recovery-policy ...}`); the 1-arity form ignores them. Existing
   fixtures keep working without modification."
  ([] (->factory backend->store))
  ([factory-fn] (->LateBoundFactory factory-fn)))