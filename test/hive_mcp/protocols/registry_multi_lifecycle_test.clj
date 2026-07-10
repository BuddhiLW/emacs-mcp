(ns hive-mcp.protocols.registry-multi-lifecycle-test
  "Characterization net for the keyed multi-impl registries (Shape B) that
   register by a DERIVED id and return nil on miss: delivery-channel + vessel.
   Locks the observable contract before the MultiSlot consolidation and
   re-runs unchanged after. (memory's keyed registry has its own net in
   protocols.memory-test.)

   Snapshot+restore fixture honors the tests-must-not-touch-shared-state axiom."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.protocols.delivery-channel :as dc]
            [hive-mcp.protocols.vessel :as ve]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each
  (fn [f]
    (let [chans (vec (dc/get-channels))
          vsls (vec (ve/get-vessels))]
      (try (f)
           (finally
             (dc/clear-channels!) (doseq [c chans] (dc/register-channel! c))
             (ve/clear-vessels!)  (doseq [v vsls] (ve/register-vessel! v)))))))

;; =============================================================================
;; delivery-channel: register-by-derived-id, get -> nil on miss, get-channels -> vals
;; =============================================================================

(defn- test-channel [id]
  (reify dc/IDeliveryChannel
    (channel-id [_] id)
    (available? [_] true)
    (deliver! [_ _event] nil)))

(deftest delivery-channel-registry-lifecycle-test
  (testing "register by derived channel-id, get/get-channels/unregister/clear"
    (dc/clear-channels!)
    (is (empty? (dc/get-channels)) "starts empty after clear")
    (is (nil? (dc/get-channel :nope)) "absent key -> nil (no throw)")
    (let [c1 (test-channel :c1)
          c2 (test-channel :c2)]
      (is (identical? c1 (dc/register-channel! c1)) "register returns the channel")
      (dc/register-channel! c2)
      (is (identical? c1 (dc/get-channel :c1)) "keyed by derived channel-id")
      (is (identical? c2 (dc/get-channel :c2)))
      (is (= #{:c1 :c2} (set (map dc/channel-id (dc/get-channels)))))
      (dc/unregister-channel! :c1)
      (is (nil? (dc/get-channel :c1)) "unregister removes only the target")
      (is (= #{:c2} (set (map dc/channel-id (dc/get-channels)))))
      (dc/clear-channels!)
      (is (empty? (dc/get-channels))))))

(deftest delivery-channel-rejects-invalid-test
  (testing "register-channel! rejects a non-IDeliveryChannel via :pre"
    (is (thrown? AssertionError (dc/register-channel! {:not "a channel"})))))

(deftest delivery-channel-replace-same-id-test
  (testing "registering a second channel with the same id replaces the first"
    (dc/clear-channels!)
    (let [a (test-channel :dup) b (test-channel :dup)]
      (dc/register-channel! a)
      (dc/register-channel! b)
      (is (= 1 (count (dc/get-channels))))
      (is (identical? b (dc/get-channel :dup))))))

;; =============================================================================
;; vessel: same shape, keyed by vessel-id
;; =============================================================================

(defn- test-vessel [id]
  (reify ve/IVessel
    (vessel-id [_] id)
    (capabilities [_] #{})
    (resolve-context [_ _agent-id] nil)
    (addon [_ _cap] nil)
    (initialize! [_ _config] nil)
    (shutdown! [_] nil)))

(deftest vessel-registry-lifecycle-test
  (testing "register by derived vessel-id, get/get-vessels/unregister/clear"
    (ve/clear-vessels!)
    (is (empty? (ve/get-vessels)) "starts empty after clear")
    (is (nil? (ve/get-vessel :nope)) "absent key -> nil (no throw)")
    (let [v1 (test-vessel :v1)
          v2 (test-vessel :v2)]
      (is (identical? v1 (ve/register-vessel! v1)) "register returns the vessel")
      (ve/register-vessel! v2)
      (is (identical? v1 (ve/get-vessel :v1)) "keyed by derived vessel-id")
      (is (= #{:v1 :v2} (set (map ve/vessel-id (ve/get-vessels)))))
      (ve/unregister-vessel! :v1)
      (is (nil? (ve/get-vessel :v1)))
      (is (= #{:v2} (set (map ve/vessel-id (ve/get-vessels)))))
      (ve/clear-vessels!)
      (is (empty? (ve/get-vessels))))))

(deftest vessel-rejects-invalid-test
  (testing "register-vessel! rejects a non-IVessel via :pre"
    (is (thrown? AssertionError (ve/register-vessel! {:not "a vessel"})))))

(deftest vessel-resolve-agent-context-test
  (testing "resolve-agent-context returns the first non-nil vessel result"
    (ve/clear-vessels!)
    (let [v (reify ve/IVessel
              (vessel-id [_] :ctx)
              (capabilities [_] #{})
              (resolve-context [_ agent-id]
                (when (= agent-id "known") {:project-id "p" :cwd "/c" :session-id "s"}))
              (addon [_ _] nil)
              (initialize! [_ _] nil)
              (shutdown! [_] nil))]
      (ve/register-vessel! v)
      (is (= {:project-id "p" :cwd "/c" :session-id "s"}
             (ve/resolve-agent-context "known")))
      (is (nil? (ve/resolve-agent-context "unknown"))))))
