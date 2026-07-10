;; PROPRIETARY - Copyright 2026 BuddhiLW. All Rights Reserved.

(ns hive-mcp.emacs.client-strike-test
  "ENGINE-L0.2 — 3-strike probe limiter for emacsclient void-symbol responses.
   Verifies that `probe-feature!` and `probe-fboundp!`:
   - return true / false on healthy round-trips
   - increment the per-symbol strike counter on void-symbol errors
   - short-circuit to :disabled once the strike limit is reached
   - reset on a successful probe"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.emacs.client :as ec]))

(defn- reset-state [f]
  (ec/reset-symbol-strikes!)
  (f)
  (ec/reset-symbol-strikes!))

(use-fixtures :each reset-state)

(deftest probe-feature-healthy-success
  (testing "Healthy `t` response returns true and does not touch strike state"
    (with-redefs [ec/eval-elisp (fn [_] {:success true :result "t"})]
      (is (true? (ec/probe-feature! 'hive-mcp))
          "feature present → true")
      (is (empty? (ec/symbol-strike-counts))
          "no strikes recorded for a healthy probe"))))

(deftest probe-feature-strike-escalation
  (testing "void-function errors accumulate strikes then disable the probe"
    (let [err {:success false :error "Symbol's function definition is void-function nope-feat"}]
      (with-redefs [ec/eval-elisp (fn [_] err)]
        (is (false? (ec/probe-feature! 'nope-feat)) "strike 1 — return false")
        (is (false? (ec/probe-feature! 'nope-feat)) "strike 2 — return false")
        (is (false? (ec/probe-feature! 'nope-feat)) "strike 3 — limit reached")
        (is (contains? (ec/disabled-symbols) 'nope-feat)
            "nope-feat is now on the short-circuit list")
        (is (= :disabled (ec/probe-feature! 'nope-feat))
            "subsequent probes short-circuit without IPC")))))

(deftest probe-fboundp-strike-escalation
  (testing "void-variable errors against fboundp also flip the limiter"
    (let [err {:success false :error "Reference to void-variable nope-fn"}]
      (with-redefs [ec/eval-elisp (fn [_] err)]
        (dotimes [_ ec/symbol-void-strike-limit]
          (is (false? (ec/probe-fboundp! 'nope-fn))))
        (is (= :disabled (ec/probe-fboundp! 'nope-fn))
            "post-limit probe returns :disabled")))))

(deftest healthy-probe-clears-strikes
  (testing "A successful probe drops the strike counter and re-enables the symbol"
    (let [responses (atom [{:success false :error "void-function flaky"}
                           {:success false :error "void-function flaky"}
                           {:success true  :result "t"}])]
      (with-redefs [ec/eval-elisp (fn [_]
                                    (let [r (first @responses)]
                                      (swap! responses rest)
                                      r))]
        (is (false? (ec/probe-feature! 'flaky)) "strike 1")
        (is (false? (ec/probe-feature! 'flaky)) "strike 2")
        (is (= 2 (get (ec/symbol-strike-counts) 'flaky))
            "two strikes recorded before recovery")
        (is (true? (ec/probe-feature! 'flaky)) "healthy probe resets")
        (is (nil? (get (ec/symbol-strike-counts) 'flaky))
            "strike counter cleared")
        (is (not (contains? (ec/disabled-symbols) 'flaky))
            "symbol re-enabled")))))

(deftest probe-respects-fresh-disable-set
  (testing "Pre-populated disabled-symbols short-circuits without calling eval-elisp"
    (let [called? (atom false)]
      (ec/reset-symbol-strikes!)
      (dotimes [_ ec/symbol-void-strike-limit]
        (with-redefs [ec/eval-elisp (fn [_]
                                      {:success false
                                       :error "void-function gone"})]
          (ec/probe-feature! 'gone)))
      (with-redefs [ec/eval-elisp (fn [_]
                                    (reset! called? true)
                                    {:success true :result "t"})]
        (is (= :disabled (ec/probe-feature! 'gone)))
        (is (false? @called?)
            "eval-elisp must NOT be invoked once the symbol is disabled")))))
