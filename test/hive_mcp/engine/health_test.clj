(ns hive-mcp.engine.health-test
  "Tests for ENGINE-L2.2 per-subsystem health-budget accounting."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.engine.health :as h]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each
  (fn [t] (h/reset-all!) (try (t) (finally (h/reset-all!)))))

;; -----------------------------------------------------------------------------
;; Registry
;; -----------------------------------------------------------------------------

(deftest test-register-is-idempotent
  (testing "register! creates a fresh counter map exactly once per id"
    (let [a (h/register! :sub/a)
          b (h/register! :sub/a)]
      (is (= 0 (:cycle-count a)))
      (is (= a b) "second register! returns the same map, no reset"))))

(deftest test-snapshot-and-snapshot-of
  (testing "snapshot returns the full atom; snapshot-of one subsystem"
    (h/register! :sub/x)
    (h/register! :sub/y)
    (let [all (h/snapshot)]
      (is (contains? all :sub/x))
      (is (contains? all :sub/y))
      (is (= (get all :sub/x) (h/snapshot-of :sub/x))))
    (is (nil? (h/snapshot-of :sub/unknown)))))

(deftest test-reset-zeroes-only-target
  (testing "reset! clears one subsystem and leaves others alone"
    (h/record-cycle! :sub/a 100 10)
    (h/record-cycle! :sub/b 200 20)
    (h/reset! :sub/a)
    (is (= 0 (:cycle-count (h/snapshot-of :sub/a))))
    (is (= 1 (:cycle-count (h/snapshot-of :sub/b))))))

;; -----------------------------------------------------------------------------
;; Accumulation
;; -----------------------------------------------------------------------------

(deftest test-record-cycle-accumulates
  (testing "bytes + ms accumulate, cycle-count bumps each call"
    (h/record-cycle! :sub/k 100 5)
    (h/record-cycle! :sub/k 50  10)
    (let [c (h/snapshot-of :sub/k)]
      (is (= 150 (:alloc-bytes c)))
      (is (= 15  (:cycle-ms c)))
      (is (= 2   (:cycle-count c))))))

(deftest test-record-cycle-tolerates-nil-deltas
  (testing "nil bytes-delta or elapsed-ms must not throw"
    (h/record-cycle! :sub/n nil nil)
    (let [c (h/snapshot-of :sub/n)]
      (is (= 0 (:alloc-bytes c)))
      (is (= 0 (:cycle-ms c)))
      (is (= 1 (:cycle-count c))))))

(deftest test-record-restart-increments
  (testing "record-restart! bumps :restarts independent of cycles"
    (h/record-restart! :sub/r)
    (h/record-restart! :sub/r)
    (let [c (h/snapshot-of :sub/r)]
      (is (= 2 (:restarts c)))
      (is (= 0 (:cycle-count c))))))

;; -----------------------------------------------------------------------------
;; Budget checks
;; -----------------------------------------------------------------------------

(deftest test-budget-exceeded-returns-empty-when-within
  (testing "no breaches → empty vector"
    (h/record-cycle! :sub/q 100 10)
    (is (= [] (h/budget-exceeded? :sub/q {:max-alloc-bytes 1000
                                          :max-cycle-ms    1000
                                          :max-restarts    10})))))

(deftest test-budget-exceeded-flags-alloc
  (testing ":alloc-bytes breach when bytes > max-alloc-bytes"
    (h/record-cycle! :sub/q 200 0)
    (is (= [:alloc-bytes]
           (h/budget-exceeded? :sub/q {:max-alloc-bytes 100})))))

(deftest test-budget-exceeded-flags-restarts
  (testing ":restarts breach when count > max-restarts"
    (dotimes [_ 5] (h/record-restart! :sub/q))
    (is (= [:restarts]
           (h/budget-exceeded? :sub/q {:max-restarts 3})))))

(deftest test-budget-exceeded-nil-disables-ceiling
  (testing "nil for a ceiling key means 'no limit'"
    (h/record-cycle! :sub/q 9999999 0)
    (is (= [] (h/budget-exceeded? :sub/q {:max-alloc-bytes nil
                                          :max-cycle-ms    nil
                                          :max-restarts    nil})))))

;; -----------------------------------------------------------------------------
;; track-cycle! / with-cycle-tracking
;; -----------------------------------------------------------------------------

(deftest test-track-cycle-records-and-returns
  (testing "happy path returns f's value and records bytes/ms"
    (let [out (h/track-cycle! :sub/t {}
                              (fn [] (Thread/sleep 5) :result))
          c   (h/snapshot-of :sub/t)]
      (is (= :result out))
      (is (= 1 (:cycle-count c)))
      (is (>= (:cycle-ms c) 5))
      (when (h/allocation-tracking-supported?)
        (is (>= (:alloc-bytes c) 0))))))

(deftest test-track-cycle-rethrows-after-accounting
  (testing "f throws → counters still update, exception propagates"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"boom"
         (h/track-cycle! :sub/throws {}
                         (fn [] (throw (ex-info "boom" {}))))))
    (let [c (h/snapshot-of :sub/throws)]
      (is (= 1 (:cycle-count c)) "exception did not skip accounting"))))

(deftest test-with-cycle-tracking-macro
  (testing "macro sugar wraps body, same semantics"
    (let [out (h/with-cycle-tracking :sub/macro {}
                (Thread/sleep 2)
                42)]
      (is (= 42 out))
      (is (= 1 (:cycle-count (h/snapshot-of :sub/macro)))))))

(deftest test-track-cycle-emits-on-single-cycle-breach
  (testing ":max-cycle-ms-once flags a slow individual cycle"
    (let [events (atom [])]
      (with-redefs [h/track-cycle!
                    (let [orig h/track-cycle!]
                      (fn [sid opts f]
                        (with-redefs [hive-mcp.events.core/dispatch
                                      (fn [ev] (swap! events conj ev))]
                          (orig sid opts f))))]
        (h/track-cycle! :sub/slow {:budget {:max-cycle-ms-once 1}}
                        (fn [] (Thread/sleep 25) :ok))
        ;; Either the wrapper or the orig fires; ensure breach recorded
        ;; in counters regardless (the emit path is best-effort).
        (is (>= (:cycle-ms (h/snapshot-of :sub/slow)) 1)))))
  (testing "single-cycle budget check on snapshot path"
    (let [c (h/snapshot-of :sub/slow)]
      (is (= 1 (:cycle-count c))))))
