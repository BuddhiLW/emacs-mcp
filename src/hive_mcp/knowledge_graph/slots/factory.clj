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
            [taoensso.timbre :as log]))

;; -----------------------------------------------------------------------------
;; Resolution helpers
;; -----------------------------------------------------------------------------

(defn- resolve-fn
  "Late-bound require — never throws."
  [sym]
  (r/rescue nil (requiring-resolve sym)))

(defn- invoke-create-fn
  "Resolve `create-store-sym` and call it. Returns the IKGStore or nil."
  [create-store-sym]
  (when-let [create-fn (resolve-fn create-store-sym)]
    (r/rescue nil (create-fn))))

;; -----------------------------------------------------------------------------
;; Per-backend factories — multimethod gives OCP for new backends
;; -----------------------------------------------------------------------------

(defmulti backend->store
  "Construct a fresh IKGStore for `backend`. Default impl returns nil so an
   unknown backend surfaces as `:slot/missing-backend` upstream rather
   than throwing."
  identity)

(defmethod backend->store :datalevin [_]
  (invoke-create-fn 'hive-mcp.knowledge-graph.store.datalevin/create-store))

(defmethod backend->store :datahike [_]
  ;; Reuse the live global active-store when one is already configured —
  ;; the :default slot must return the same handle that legacy
  ;; `connection.clj` callers see, so reads/writes don't split-brain.
  (or (when (pkg/store-set?) (pkg/get-store))
      (invoke-create-fn 'hive-mcp.knowledge-graph.store.datahike/create-store)))

(defmethod backend->store :datascript [_]
  (invoke-create-fn 'hive-mcp.knowledge-graph.store.datascript/create-store))

(defmethod backend->store :default [_] nil)

(def ^:const supported-backends-set
  "Backends this factory can construct. Keep in lockstep with the
   `defmethod backend->store` declarations above."
  #{:datalevin :datahike :datascript})

;; -----------------------------------------------------------------------------
;; LateBoundFactory — Factory Method implementation
;; -----------------------------------------------------------------------------

(defrecord LateBoundFactory [factory-fn]
  p/IBackendFactory
  (make-store [_ backend]
    (let [store (factory-fn backend)]
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
   Tests pass a custom `factory-fn` to inject in-memory stores."
  ([] (->factory backend->store))
  ([factory-fn] (->LateBoundFactory factory-fn)))
