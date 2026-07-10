(ns hive-mcp.delivery.fanout-test
  "Tests for ENGINE-L2.3 isolated fanout — timeout, breaker, telemetry."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.delivery.fanout :as fan]
            [hive-mcp.protocols.delivery-channel :as dc]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Test channels — minimal IDeliveryChannel impls with controllable behaviour
;; -----------------------------------------------------------------------------

(defrecord OkChannel [id calls]
  dc/IDeliveryChannel
  (channel-id [_] id)
  (available? [_] true)
  (deliver! [_ event] (swap! calls conj event) nil))

(defrecord HangChannel [id hang-ms calls]
  dc/IDeliveryChannel
  (channel-id [_] id)
  (available? [_] true)
  (deliver! [_ event]
    (swap! calls conj event)
    (Thread/sleep ^long hang-ms)
    nil))

(defrecord ThrowChannel [id calls]
  dc/IDeliveryChannel
  (channel-id [_] id)
  (available? [_] true)
  (deliver! [_ event]
    (swap! calls conj event)
    (throw (ex-info "boom" {:channel id}))))

(defrecord FlakyAvailableChannel [id avail-ms calls]
  dc/IDeliveryChannel
  (channel-id [_] id)
  (available? [_] (Thread/sleep ^long avail-ms) true)
  (deliver! [_ event] (swap! calls conj event) nil))

;; -----------------------------------------------------------------------------
;; Fixtures — every test gets a clean channel registry + breaker state
;; -----------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (dc/clear-channels!)
    (fan/reset-breakers!)
    (try (t)
         (finally
           (dc/clear-channels!)
           (fan/reset-breakers!)))))

(defn- register-all! [chs]
  (run! dc/register-channel! chs))

(def ^:private fast-policy
  "Tight timeouts so tests don't drag."
  {:per-channel-timeout-ms 100
   :availability-timeout-ms 30
   :max-failures 2
   :initial-cooldown-ms 10000})

;; -----------------------------------------------------------------------------
;; Happy path
;; -----------------------------------------------------------------------------

(deftest test-fanout-delivers-to-all-channels-on-happy-path
  (testing "every channel receives the event, outcome map says :ok"
    (let [a (->OkChannel :a (atom []))
          b (->OkChannel :b (atom []))]
      (register-all! [a b])
      (let [out (fan/fanout! {:msg "hello"} fast-policy)]
        (is (= {:a :ok :b :ok} out))
        (is (= 1 (count @(:calls a))))
        (is (= 1 (count @(:calls b))))))))

(deftest test-fanout-wraps-event-in-envelope
  (testing "channels see the same envelope id"
    (let [a (->OkChannel :a (atom []))
          b (->OkChannel :b (atom []))]
      (register-all! [a b])
      (fan/fanout! {:msg "x"} fast-policy)
      (let [id-a (::fan/id (first @(:calls a)))
            id-b (::fan/id (first @(:calls b)))]
        (is (some? id-a))
        (is (= id-a id-b) "shared envelope id correlates per-channel outcomes")))))

;; -----------------------------------------------------------------------------
;; Timeout isolation
;; -----------------------------------------------------------------------------

(deftest test-hung-channel-times-out-and-does-not-block-siblings
  (testing "a HangChannel does not stall delivery to sibling OkChannels"
    (let [hang (->HangChannel :hang 5000 (atom []))
          ok   (->OkChannel :ok (atom []))]
      (register-all! [hang ok])
      (let [t0  (System/currentTimeMillis)
            out (fan/fanout! {:msg "test"} fast-policy)
            dt  (- (System/currentTimeMillis) t0)]
        (is (= :timeout (:hang out)))
        (is (= :ok      (:ok out)))
        (is (< dt 1500) (str "fanout took " dt "ms — should be << 5000"))
        (is (= 1 (count @(:calls ok))))))))

(deftest test-availability-check-timeout-counts-as-failure
  (testing "available? that hangs is treated as a timeout"
    (let [flaky (->FlakyAvailableChannel :flaky 500 (atom []))]
      (register-all! [flaky])
      (let [out (fan/fanout! {:msg "x"} fast-policy)]
        (is (= :timeout (:flaky out)))
        (is (empty? @(:calls flaky))
            "deliver! never ran because available? timed out first")))))

;; -----------------------------------------------------------------------------
;; Exception isolation
;; -----------------------------------------------------------------------------

(deftest test-throwing-channel-isolated-from-siblings
  (testing "ThrowChannel surfaces :exception, other channels still receive"
    (let [bad (->ThrowChannel :bad (atom []))
          ok  (->OkChannel :ok (atom []))]
      (register-all! [bad ok])
      (let [out (fan/fanout! {:msg "x"} fast-policy)]
        (is (= :exception (:bad out)))
        (is (= :ok        (:ok out)))
        (is (= 1 (count @(:calls ok))))))))

;; -----------------------------------------------------------------------------
;; Circuit breaker
;; -----------------------------------------------------------------------------

(deftest test-breaker-trips-after-consecutive-failures
  (testing "after :max-failures the channel is short-circuited as :breaker-open"
    (let [bad (->ThrowChannel :bad (atom []))]
      (register-all! [bad])
      ;; max-failures = 2 in fast-policy. First two calls land as
      ;; :exception, third sees :breaker-open and deliver! is never run.
      (fan/fanout! {:msg "1"} fast-policy)
      (fan/fanout! {:msg "2"} fast-policy)
      (let [out (fan/fanout! {:msg "3"} fast-policy)]
        (is (= :breaker-open (:bad out)))
        (is (= 2 (count @(:calls bad)))
            "third event short-circuited before reaching deliver!")))))

(deftest test-breaker-isolates-per-channel
  (testing "tripping one channel's breaker does not affect siblings"
    (let [bad (->ThrowChannel :bad (atom []))
          ok  (->OkChannel :ok (atom []))]
      (register-all! [bad ok])
      (dotimes [_ 3] (fan/fanout! {:msg "x"} fast-policy))
      (let [out (fan/fanout! {:msg "final"} fast-policy)]
        (is (= :breaker-open (:bad out)))
        (is (= :ok           (:ok out)))))))

;; -----------------------------------------------------------------------------
;; Operator surface
;; -----------------------------------------------------------------------------

(deftest test-breaker-snapshot-reflects-state
  (testing "breaker-snapshot exposes per-channel state for /health"
    (let [bad (->ThrowChannel :bad (atom []))
          ok  (->OkChannel :ok (atom []))]
      (register-all! [bad ok])
      (dotimes [_ 2] (fan/fanout! {:msg "x"} fast-policy))
      (let [snap (fan/breaker-snapshot)]
        (is (= :open (get-in snap [:bad :state])))
        ;; :ok never failed — either absent (never touched) or :closed.
        (is (contains? #{nil :closed} (get-in snap [:ok :state])))))))

(deftest test-fanout-never-throws-even-with-no-channels
  (testing "empty registry returns {} and does not throw"
    (is (= {} (fan/fanout! {:msg "alone"} fast-policy)))))
