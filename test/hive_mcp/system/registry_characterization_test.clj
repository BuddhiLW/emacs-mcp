(ns hive-mcp.system.registry-characterization-test
  "Representation-agnostic characterization net for hive-mcp.system.registry.

   Exercises ONLY the public fn surface (register-*/unregister-*/registered-*/
   get-resource-owner/registry-snapshot) — never the backing atoms. It therefore
   passes UNCHANGED against both the original `(atom {})` registries and the
   MultiSlot-backed migration, serving as the invariant anchor for that refactor
   (defense-in-depth: green before AND after, with no edits to this file).

   Isolation without representation access: every test registers uniquely-keyed
   entries and asserts on the PRESENCE/ABSENCE of its own keys only — never on
   total counts — then unregisters them in a finally. This is robust against
   real production registrations (e.g. orphan-channel auto-registers a sweep on
   load) per the tests-must-not-touch-shared-state axiom."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.protocols.lifecycle :as proto]
            [hive-mcp.system.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private k-pfx "chartest-")

(defn- mk-hook [name priority]
  (reify proto/IShutdownHook
    (shutdown-name [_] name)
    (shutdown-priority [_] priority)
    (shutdown! [_ _] :ok)))

(defn- mk-sweep [name interval]
  (reify proto/ISweepable
    (sweep-name [_] name)
    (sweep-interval-s [_] interval)
    (sweep! [_ _] {:swept 0 :errors []})))

(defn- mk-owner [id]
  (reify proto/IResourceOwner
    (owner-id [_] id)
    (owned-resources [_] {})
    (release-all! [_] nil)))

(defn- our-shutdown-names []
  (->> (reg/registered-shutdown-hooks)
       (map proto/shutdown-name)
       (filter #(.startsWith ^String % k-pfx))
       set))

(defn- our-sweep-names []
  (->> (reg/registered-sweeps)
       (map proto/sweep-name)
       (filter #(.startsWith ^String % k-pfx))
       set))

;; =============================================================================
;; Shutdown registry
;; =============================================================================

(deftest shutdown-register-get-order-overwrite-remove
  (testing "register surfaces the hook; ascending priority order among our hooks"
    (let [n1 (str k-pfx "sd-1") n2 (str k-pfx "sd-2")]
      (try
        (let [h (mk-hook n1 200)]
          (is (identical? h (reg/register-shutdown! h)) "register-shutdown! returns its impl"))
        ;; register two in non-sorted insertion order; reads come back sorted
        (reg/register-shutdown! (mk-hook n2 50))
        (reg/register-shutdown! (mk-hook n1 300))
        (is (= #{n1 n2} (our-shutdown-names)) "both our hooks present")
        (let [ours (->> (reg/registered-shutdown-hooks)
                        (filter #(#{n1 n2} (proto/shutdown-name %)))
                        (map proto/shutdown-priority))]
          (is (= ours (sort ours)) "registered-shutdown-hooks is priority-ascending"))
        (testing "idempotent re-register: same name overwrites, stays single"
          (reg/register-shutdown! (mk-hook n1 999))
          (let [matches (->> (reg/registered-shutdown-hooks)
                             (filter #(= n1 (proto/shutdown-name %))))]
            (is (= 1 (count matches)) "name appears exactly once")
            (is (= 999 (proto/shutdown-priority (first matches))) "second registration wins")))
        (testing "unregister removes by name"
          (reg/unregister-shutdown! n1)
          (is (not (contains? (our-shutdown-names) n1)) "n1 gone")
          (is (contains? (our-shutdown-names) n2) "n2 retained"))
        (finally
          (reg/unregister-shutdown! n1)
          (reg/unregister-shutdown! n2))))))

(deftest shutdown-register-rejects-non-impl
  (testing "register-shutdown! :pre rejects a non-IShutdownHook (AssertionError)"
    (is (thrown? AssertionError (reg/register-shutdown! {:not "a hook"})))
    (is (thrown? AssertionError (reg/register-shutdown! (mk-sweep (str k-pfx "x") 1)))
        "an ISweepable is not an IShutdownHook")))

;; =============================================================================
;; Sweep registry
;; =============================================================================

(deftest sweep-register-list-remove
  (testing "register surfaces the sweep; unregister removes it"
    (let [n (str k-pfx "sw-1")]
      (try
        (reg/register-sweep! (mk-sweep n 120))
        (is (contains? (our-sweep-names) n) "our sweep present")
        (reg/unregister-sweep! n)
        (is (not (contains? (our-sweep-names) n)) "our sweep gone")
        (finally (reg/unregister-sweep! n))))))

(deftest sweep-register-rejects-non-impl
  (testing "register-sweep! :pre rejects a non-ISweepable"
    (is (thrown? AssertionError (reg/register-sweep! {:not "a sweep"})))))

;; =============================================================================
;; Resource-owner registry
;; =============================================================================

(deftest resource-register-get-remove
  (testing "register/get/unregister roundtrip; absent -> nil"
    (let [id (str k-pfx "owner-1")
          owner (mk-owner id)]
      (try
        (is (nil? (reg/get-resource-owner (str k-pfx "absent"))) "absent owner -> nil")
        (reg/register-resource-owner! owner)
        (is (identical? owner (reg/get-resource-owner id)) "get returns the registered owner")
        (reg/unregister-resource-owner! id)
        (is (nil? (reg/get-resource-owner id)) "after unregister -> nil")
        (finally (reg/unregister-resource-owner! id))))))

(deftest resource-register-rejects-non-impl
  (testing "register-resource-owner! :pre rejects a non-IResourceOwner"
    (is (thrown? AssertionError (reg/register-resource-owner! {:not "an owner"})))))

;; =============================================================================
;; Cross-registry snapshot shape
;; =============================================================================

(deftest snapshot-shape-and-membership
  (testing "registry-snapshot exposes the three keys and reflects our entries"
    (let [sd (str k-pfx "snap-sd")
          sw (str k-pfx "snap-sw")
          ow (str k-pfx "snap-ow")]
      (try
        (reg/register-shutdown! (mk-hook sd 123))
        (reg/register-sweep! (mk-sweep sw 60))
        (reg/register-resource-owner! (mk-owner ow))
        (let [snap (reg/registry-snapshot)]
          (is (map? snap))
          (is (contains? snap :shutdown))
          (is (contains? snap :sweeps))
          (is (contains? snap :resources))
          (is (some #(= sd (first %)) (:shutdown snap)) ":shutdown is [name priority] pairs incl ours")
          (is (some #(= sw (first %)) (:sweeps snap)) ":sweeps is [name interval] pairs incl ours")
          (is (some #(= ow %) (:resources snap)) ":resources is owner-id keys incl ours"))
        (finally
          (reg/unregister-shutdown! sd)
          (reg/unregister-sweep! sw)
          (reg/unregister-resource-owner! ow))))))
