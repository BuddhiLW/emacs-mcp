(ns hive-mcp.test.stub.kg-store
  "Stub IKGStore backends for backend keys the running classpath does not
   provide.

   Each stub is an ephemeral in-memory store obtained through the factory
   port, wrapped in a record whose class name carries the backend it stands
   in for — `hive-mcp.knowledge-graph.migration/detect-current-backend`
   identifies a store by class name, so the wrapper is what makes the stub
   answer as that backend.

   `with-stub-backends` installs the stubs on `factory/backend->store` and
   restores any pre-existing method on exit."
  (:require [hive-spi.kg.protocol :as kg]
            [hive-mcp.knowledge-graph.slots.factory :as factory]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defmacro ^:private def-stub-store
  "Define record NM with a single field `delegate`, forwarding every IKGStore
   method to it."
  [nm]
  `(defrecord ~nm [~'delegate]
     kg/IKGStore
     (~'ensure-conn!  [_#] (kg/ensure-conn! ~'delegate))
     (~'db-snapshot   [_#] (kg/db-snapshot ~'delegate))
     (~'reset-conn!   [_#] (kg/reset-conn! ~'delegate))
     (~'close!        [_#] (kg/close! ~'delegate))
     (~'transact!     [_# tx#] (kg/transact! ~'delegate tx#))
     (~'entid         [_# lookup#] (kg/entid ~'delegate lookup#))
     (~'entity        [_# eid#] (kg/entity ~'delegate eid#))
     (~'eids-by-attr  [_# attr#] (kg/eids-by-attr ~'delegate attr#))
     (~'query         [_# q#] (kg/query ~'delegate q#))
     (~'query         [_# q# inputs#] (kg/query ~'delegate q# inputs#))
     (~'pull-entity   [_# pattern# eid#] (kg/pull-entity ~'delegate pattern# eid#))))

(def-stub-store StubDatalevinStore)
(def-stub-store StubDatahikeStore)

(def ^:private stub-ctors
  {:datalevin ->StubDatalevinStore
   :datahike  ->StubDatahikeStore})

(defn- ephemeral-delegate
  [opts]
  (factory/backend->store :datascript (assoc opts :fresh? true)))

(defn stub-store
  "Ephemeral in-memory IKGStore answering as BACKEND. Throws for a backend
   with no stub ctor."
  [backend opts]
  (let [ctor (or (get stub-ctors backend)
                 (throw (ex-info "No stub store for backend" {:backend backend})))
        store (ctor (ephemeral-delegate (or opts {})))]
    (kg/ensure-conn! store)
    store))

(defn with-stub-backends
  "Run F with each backend in BACKENDS served by `stub-store`, restoring the
   prior `factory/backend->store` methods afterwards."
  [backends f]
  (let [mf    factory/backend->store
        prior (into {} (map (fn [b] [b (get (methods mf) b)])) backends)]
    (try
      (doseq [b backends]
        (.addMethod ^clojure.lang.MultiFn mf b
                    (fn [_ & [opts]] (stub-store b opts))))
      (f)
      (finally
        (doseq [b backends]
          (remove-method mf b)
          (when-let [m (get prior b)]
            (.addMethod ^clojure.lang.MultiFn mf b m)))))))

(defn stub-backends-fixture
  "clojure.test fixture form of `with-stub-backends`."
  [backends]
  (fn [f] (with-stub-backends backends f)))
