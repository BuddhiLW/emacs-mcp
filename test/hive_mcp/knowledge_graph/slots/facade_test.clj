;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.facade-test
  "slots/* facade — with-registry / with-slot-store / store delegation."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.slots :as slots]
            [hive-mcp.knowledge-graph.slots.protocol :as p]
            [hive-mcp.knowledge-graph.slots.config :as cfg]
            [hive-mcp.knowledge-graph.slots.factory :as fact]
            [hive-mcp.knowledge-graph.slots.registry :as reg]
            [hive-mcp.protocols.kg :as pkg]))

(defrecord StubStore [conn-atom label]
  pkg/IKGStore
  (ensure-conn! [_] (reset! conn-atom :open) :open)
  (transact!    [_ tx] (swap! conn-atom (fn [_] {:tx tx})) :ok)
  (query        [_ _q] #{:from-stub})
  (query        [_ _q _ins] #{:from-stub-ins})
  (entity       [_ _eid] nil)
  (entid        [_ _ref] nil)
  (pull-entity  [_ _p _e] nil)
  (eids-by-attr [_ _a] ())
  (db-snapshot  [_] :stub-snapshot)
  (reset-conn!  [_] :reset)
  (close!       [_] (reset! conn-atom :closed) :closed))

(defn- stub-store [label] (->StubStore (atom :init) label))

(defn- stub-registry
  [defaults]
  (reg/->registry
    (cfg/->resolver (constantly nil) defaults)
    (fact/->factory (fn [be] (stub-store be)))))

;; -----------------------------------------------------------------------------
;; with-registry (Decorator) — facade routes through the override
;; -----------------------------------------------------------------------------

(deftest with-registry-overrides-default
  (let [reg (stub-registry {:carto :datalevin})]
    (slots/with-registry reg
      (is (= :datalevin (:label (slots/store :carto))))
      (is (= [:carto] (slots/registered-slots))))))

;; -----------------------------------------------------------------------------
;; with-slot-store — fixture override wins over registry
;; -----------------------------------------------------------------------------

(deftest with-slot-store-takes-priority
  (let [reg          (stub-registry {:carto :datalevin})
        custom-store (stub-store :fixture-override)]
    (slots/with-registry reg
      (slots/with-slot-store :carto custom-store
        (is (identical? custom-store (slots/store :carto))))
      ;; outside the binding, registry resolution returns again
      (is (= :datalevin (:label (slots/store :carto)))))))

;; -----------------------------------------------------------------------------
;; IKGStore-shaped facade delegates to the resolved store
;; -----------------------------------------------------------------------------

(deftest facade-delegates-query-and-transact
  (let [reg (stub-registry {:carto :datalevin})]
    (slots/with-registry reg
      (is (= #{:from-stub} (slots/query :carto '[:find ?x])))
      (is (= #{:from-stub-ins} (slots/query :carto '[:find ?x] [:input])))
      (is (= :ok (slots/transact! :carto [{:foo :bar}])))
      (is (= :stub-snapshot (slots/db-snapshot :carto))))))

;; -----------------------------------------------------------------------------
;; describe-slot surfaces the SlotInit ADT
;; -----------------------------------------------------------------------------

(deftest describe-slot-returns-adt
  (let [reg (stub-registry {:carto :datalevin})]
    (slots/with-registry reg
      (let [init (slots/describe-slot :carto)]
        (is (= :SlotInit (:adt/type init)))
        (is (= :slot/ok  (:adt/variant init)))
        (is (= :datalevin (:backend init)))))))

;; -----------------------------------------------------------------------------
;; heal-sweep! — boot-time probe + report
;; -----------------------------------------------------------------------------

(deftest heal-sweep-reports-ok-slots
  (testing "heal-sweep! returns :ok entries when every slot opens cleanly"
    (let [reg (stub-registry {:carto :datalevin :memory :datahike})]
      (slots/with-registry reg
        (let [report (slots/heal-sweep! [:carto :memory])]
          (is (= 2 (:ok-count report)))
          (is (= 0 (:degraded-count report)))
          (is (= [] (:degraded-slots report)))
          (is (= [{:slot :carto :status :ok :backend :datalevin}
                  {:slot :memory :status :ok :backend :datahike}]
                 (:slots report))))))))

(deftest heal-sweep-reports-degraded-slots
  (testing "heal-sweep! surfaces factory-failed + missing-backend slots"
    (let [reg (reg/->registry
                (cfg/->resolver (constantly nil)
                                ;; :memory has no mapping → :missing-backend
                                {:carto :datalevin})
                (fact/->factory (fn [_] nil)))]   ;; factory always fails
      (slots/with-registry reg
        (let [report (slots/heal-sweep! [:carto :memory])
              by-slot (into {} (map (juxt :slot identity)) (:slots report))]
          (is (= 0 (:ok-count report)))
          (is (= 2 (:degraded-count report)))
          (is (= #{:carto :memory} (set (:degraded-slots report))))
          (is (= :factory-failed   (get-in by-slot [:carto :status])))
          ;; :memory is in registry-defaults but the cfg path-lookup
          ;; returned nil AND our resolver defaults map omits it → fallback :datahike
          ;; → factory returns nil → :factory-failed
          (is (contains? #{:factory-failed :missing-backend}
                         (get-in by-slot [:memory :status]))))))))

(deftest heal-sweep-uses-canonical-slots-by-default
  (testing "0-arg heal-sweep! sweeps every named default slot"
    (let [reg (stub-registry cfg/default-slot->backend)]
      (slots/with-registry reg
        (let [report (slots/heal-sweep!)]
          (is (= (set (keys cfg/default-slot->backend))
                 (set (map :slot (:slots report))))))))))
