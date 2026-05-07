;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.registry-test
  "AtomBackedRegistry — SlotInit ADT outcomes + lifecycle."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.adt :as adt]
            [hive-mcp.knowledge-graph.slots.protocol :as p]
            [hive-mcp.knowledge-graph.slots.config :as cfg]
            [hive-mcp.knowledge-graph.slots.factory :as fact]
            [hive-mcp.knowledge-graph.slots.registry :as reg]
            [hive-mcp.protocols.kg :as pkg]))

;; -----------------------------------------------------------------------------
;; Stub IKGStore (mirrors factory_test) + builders
;; -----------------------------------------------------------------------------

(defrecord StubStore [conn-atom label]
  pkg/IKGStore
  (ensure-conn! [_] (reset! conn-atom :open) :open)
  (transact!    [_ _tx] nil)
  (query        [_ _q] #{})
  (query        [_ _q _ins] #{})
  (entity       [_ _eid] nil)
  (entid        [_ _ref] nil)
  (pull-entity  [_ _p _e] nil)
  (eids-by-attr [_ _a] ())
  (db-snapshot  [_] nil)
  (reset-conn!  [_] :reset)
  (close!       [_] (reset! conn-atom :closed) :closed))

(defn- stub-store [label] (->StubStore (atom :init) label))

(defn- registry-with
  "Build a registry whose factory always returns a StubStore, and whose
   resolver maps every named slot to a fixed backend keyword."
  [defaults]
  (reg/->registry
    (cfg/->resolver (constantly nil) defaults)
    (fact/->factory (fn [be] (stub-store be)))))

;; -----------------------------------------------------------------------------
;; SlotInit outcomes — exhaustive
;; -----------------------------------------------------------------------------

(deftest slot-ok-on-known-mapping
  (let [r (registry-with {:carto :datalevin})
        init (p/describe-slot r :carto)]
    (is (= :slot/ok (adt/adt-variant init)))
    (is (= :datalevin (:backend init)))
    (is (= :open (-> init :store :conn-atom deref)))))

(deftest slot-missing-backend-when-resolver-returns-nil
  (testing "explicit nil mapping bypasses fallback"
    (let [r (reg/->registry
              ;; Resolver always returns nil
              (reify p/IBackendResolver
                (resolve-backend [_ _] nil)
                (default-mapping [_] {}))
              (fact/->factory (fn [_] (stub-store :ignored))))]
      (is (= :slot/missing-backend
             (adt/adt-variant (p/describe-slot r :nope)))))))

(deftest slot-factory-failed-when-store-cant-build
  (let [r (reg/->registry
            (cfg/->resolver (constantly nil) {:carto :datalevin})
            (fact/->factory (constantly nil)))
        init (p/describe-slot r :carto)]
    (is (= :slot/factory-failed (adt/adt-variant init)))
    (is (= :factory-returned-nil (:reason init)))))

;; -----------------------------------------------------------------------------
;; Caching + lifecycle
;; -----------------------------------------------------------------------------

(deftest slot-store-cached-after-first-access
  (let [r (registry-with {:carto :datalevin})
        s1 (p/slot-store r :carto)
        s2 (p/slot-store r :carto)]
    (is (identical? s1 s2) "registry memoises the slot's IKGStore")
    (is (= [:carto] (p/registered r)))))

(deftest close-slot-evicts-and-rebuilds
  (let [r (registry-with {:carto :datalevin})
        s1 (p/slot-store r :carto)]
    (p/close-slot! r :carto)
    (is (= :closed (-> s1 :conn-atom deref))
        "close! propagated to the cached store")
    (is (empty? (p/registered r)))
    (let [s2 (p/slot-store r :carto)]
      (is (not (identical? s1 s2))
          "next access rebuilds a fresh store"))))

(deftest close-all-snapshots-and-clears
  (let [r (registry-with {:carto :datalevin :memory :datahike})
        _ (p/slot-store r :carto)
        _ (p/slot-store r :memory)]
    (p/close-all! r)
    (is (empty? (p/registered r)))))
