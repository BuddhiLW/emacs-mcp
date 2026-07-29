;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.factory-test
  "LateBoundFactory — Factory Method dispatch + supported-backends set."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.slots.protocol :as p]
            [hive-mcp.knowledge-graph.slots.factory :as fact]
            [hive-mcp.protocols.kg :as pkg]))

;; -----------------------------------------------------------------------------
;; Stub IKGStore — minimal satisfaction for factory-injection tests
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

(defn- stub-store [label]
  (->StubStore (atom :init) label))

;; -----------------------------------------------------------------------------
;; Factory dispatch via injected fn (DIP — no real backend needed)
;; -----------------------------------------------------------------------------

(deftest factory-dispatches-via-injected-fn
  (testing "make-store delegates to the injected factory-fn"
    (let [calls (atom [])
          f     (fact/->factory (fn [be] (swap! calls conj be) (stub-store be)))]
      (let [s (p/make-store f :datalevin)]
        (is (some? s))
        (is (= [:datalevin] @calls))
        (is (= :datalevin (:label s)))))))

(deftest factory-returns-nil-for-unmapped-backend
  (testing "factory-fn returning nil is reported, no exception"
    (let [f (fact/->factory (constantly nil))]
      (is (nil? (p/make-store f :imaginary))))))

;; -----------------------------------------------------------------------------
;; Supported-backends set — OCP contract
;; -----------------------------------------------------------------------------

(deftest supported-backends-stable
  (let [f (fact/->factory)]
    (is (contains? (p/supported-backends f) :datalevin))
    (is (contains? (p/supported-backends f) :datahike))
    (is (contains? (p/supported-backends f) :datascript))))

;; -----------------------------------------------------------------------------
;; Production multimethod chain — :default returns nil
;; -----------------------------------------------------------------------------

(deftest unknown-backend-defaults-to-nil
  (testing "fact/backend->store :unknown returns nil (caller surfaces failure)"
    (is (nil? (fact/backend->store :unknown)))))

(deftest datahike-health-is-late-bound
  (let [store    (stub-store :datahike)
        expected {:status :healthy
                  :backend :datahike
                  :compatible? true}]
    (with-redefs-fn
      {#'fact/resolve-fn
       (fn [sym]
         (when (= 'hive-datahike.kg.store/health sym)
           (fn [resolved-store]
             (is (identical? store resolved-store))
             expected)))}
      #(is (= expected (fact/backend-health :datahike store))))))

(deftest default-backend-health-reports-ready-store
  (let [store (stub-store :datascript)]
    (is (= {:status :healthy
            :backend :datascript
            :store-class (str (class store))}
           (fact/backend-health :datascript store)))))
