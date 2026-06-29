(ns hive-mcp.protocols.registry-test
  "Contract + property tests for the SingleSlot abstraction
   (hive-mcp.protocols.registry). One suite proves the LSP contract that every
   per-protocol slot wrapper inherits: install/current/present?/clear! semantics
   across the three empty-policies (throw | noop-fallback | seeded-default)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol IThing (thing-id [this]))
(defrecord Thing [id] IThing (thing-id [_] id))
(defrecord Fallback [] IThing (thing-id [_] :fallback))

(defn- thing-slot
  ([] (thing-slot {}))
  ([extra]
   (reg/single-slot (merge {:validate #(satisfies? IThing %)} extra))))

;; =============================================================================
;; install! / current / present? round-trip
;; =============================================================================

(deftest install-current-roundtrip-test
  (testing "install! returns the impl; current returns the identical impl; present? flips"
    (let [slot (thing-slot)
          t (->Thing :a)]
      (is (false? (reg/present? slot)) "nothing installed initially")
      (is (identical? t (reg/install! slot t)) "install! returns its arg")
      (is (true? (reg/present? slot)))
      (is (identical? t (reg/current slot)))
      (testing "re-install replaces"
        (let [t2 (->Thing :b)]
          (reg/install! slot t2)
          (is (identical? t2 (reg/current slot))))))))

(deftest validate-rejects-invalid-test
  (testing "install! throws AssertionError for an impl failing :validate"
    (is (thrown? AssertionError (reg/install! (thing-slot) {:not "a thing"}))))
  (testing "a slot with no :validate accepts anything"
    (let [slot (reg/single-slot {})]
      (is (= 42 (reg/install! slot 42)))
      (is (= 42 (reg/current slot))))))

;; =============================================================================
;; empty-policy: :throw / :noop-fallback / :seeded-default
;; =============================================================================

(deftest empty-policy-throw-test
  (testing "throw-on-empty slot: current throws when unset, returns impl when set"
    (let [slot (thing-slot {:on-empty #(throw (ex-info "empty" {:k :v}))})]
      (is (thrown? clojure.lang.ExceptionInfo (reg/current slot)))
      (reg/install! slot (->Thing :x))
      (is (= :x (thing-id (reg/current slot)))))))

(deftest empty-policy-noop-fallback-test
  (testing "noop slot: current returns a constructed fallback when unset; present? stays false"
    (let [slot (thing-slot {:on-empty ->Fallback})]
      (is (false? (reg/present? slot)))
      (is (= :fallback (thing-id (reg/current slot))) "fallback returned when unset")
      (reg/install! slot (->Thing :y))
      (is (true? (reg/present? slot)))
      (is (= :y (thing-id (reg/current slot)))))))

(deftest empty-policy-seeded-default-test
  (testing "seeded slot: starts present with the :initial value"
    (let [d (->Thing :default)
          slot (thing-slot {:initial d :on-empty #(->Thing :default)})]
      (is (true? (reg/present? slot)))
      (is (identical? d (reg/current slot))))))

;; =============================================================================
;; clear! + teardown
;; =============================================================================

(deftest clear-resets-and-applies-empty-policy-test
  (testing "clear! returns nil, drops the impl, current then applies empty-policy"
    (let [slot (thing-slot {:on-empty ->Fallback})]
      (reg/install! slot (->Thing :z))
      (is (nil? (reg/clear! slot)))
      (is (false? (reg/present? slot)))
      (is (= :fallback (thing-id (reg/current slot)))))))

(deftest clear-runs-teardown-once-on-installed-impl-test
  (testing "teardown fires exactly once, on the installed impl; no-op clear when empty"
    (let [torn (atom [])
          slot (thing-slot {:teardown #(swap! torn conj (thing-id %))})]
      (reg/clear! slot)
      (is (= [] @torn) "no teardown when nothing installed")
      (reg/install! slot (->Thing :t))
      (reg/clear! slot)
      (is (= [:t] @torn) "teardown ran once on the installed impl")
      (reg/clear! slot)
      (is (= [:t] @torn) "second clear is a no-op"))))

(deftest teardown-exception-is-swallowed-test
  (testing "a throwing teardown does not propagate; slot still clears"
    (let [slot (thing-slot {:teardown (fn [_] (throw (RuntimeException. "boom")))})]
      (reg/install! slot (->Thing :e))
      (is (nil? (reg/clear! slot)))
      (is (false? (reg/present? slot))))))

;; =============================================================================
;; Property: current always reflects the last successful install
;; =============================================================================

(defspec current-reflects-last-install 200
  (prop/for-all [ids (gen/not-empty (gen/vector gen/keyword))]
    (let [slot (thing-slot {:on-empty ->Fallback})]
      (doseq [id ids] (reg/install! slot (->Thing id)))
      (and (reg/present? slot)
           (= (last ids) (thing-id (reg/current slot)))
           (do (reg/clear! slot)
               (and (not (reg/present? slot))
                    (= :fallback (thing-id (reg/current slot)))))))))

;; =============================================================================
;; MultiSlot — keyed registry contract
;; =============================================================================

(defn- thing-registry
  ([] (thing-registry {}))
  ([extra] (reg/multi-slot (merge {:validate #(satisfies? IThing %)} extra))))

(deftest multi-put-get-roundtrip-test
  (testing "reg-put! returns impl; reg-get returns it; snapshot reflects keys"
    (let [r (thing-registry)
          a (->Thing :a) b (->Thing :b)]
      (is (identical? a (reg/reg-put! r :a a)) "reg-put! returns its arg")
      (reg/reg-put! r :b b)
      (is (identical? a (reg/reg-get r :a)))
      (is (identical? b (reg/reg-get r :b)))
      (is (= #{:a :b} (set (keys (reg/reg-snapshot r))))))))

(deftest multi-remove-and-clear-test
  (testing "reg-remove! drops one key; reg-clear! drops all; both return nil"
    (let [r (thing-registry)]
      (reg/reg-put! r :a (->Thing :a))
      (reg/reg-put! r :b (->Thing :b))
      (is (nil? (reg/reg-remove! r :a)))
      (is (= #{:b} (set (keys (reg/reg-snapshot r)))))
      (is (nil? (reg/reg-clear! r)))
      (is (= {} (reg/reg-snapshot r))))))

(deftest multi-validate-rejects-invalid-test
  (testing "reg-put! throws AssertionError for impls failing :validate"
    (is (thrown? AssertionError (reg/reg-put! (thing-registry) :bad {:not "a thing"})))))

(deftest multi-missing-policy-test
  (testing "reg-get on absent key: nil by default, on-missing fn when configured"
    (is (nil? (reg/reg-get (thing-registry) :nope)) "nil-on-missing default")
    (let [r (thing-registry {:on-missing (fn [k snap]
                                           (throw (ex-info "missing" {:k k :have (vec (keys snap))})))})]
      (reg/reg-put! r :a (->Thing :a))
      (is (thrown? clojure.lang.ExceptionInfo (reg/reg-get r :nope)))
      (try (reg/reg-get r :nope)
           (catch clojure.lang.ExceptionInfo e
             (is (= :nope (:k (ex-data e))))
             (is (= [:a] (:have (ex-data e)))))))))

(defspec multi-snapshot-reflects-puts 200
  (prop/for-all [ks (gen/not-empty (gen/vector gen/keyword))]
    (let [r (thing-registry)]
      (doseq [k ks] (reg/reg-put! r k (->Thing k)))
      (and (= (set ks) (set (keys (reg/reg-snapshot r))))
           (every? #(= % (thing-id (reg/reg-get r %))) ks)
           (do (reg/reg-clear! r) (= {} (reg/reg-snapshot r)))))))
