;; PROPRIETARY - Copyright 2026 BuddhiLW. All Rights Reserved.

(ns hive-mcp.events.dispatch-loud-fail-test
  "ENGINE-L0.4 — loud-fail behaviour for missing effect handlers.
   Verifies the per-effect-id miss counter increments on each blackhole
   and that crossing `unhandled-effect-warn-threshold` flips logging
   from WARN to ERROR-level escalation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.events.dispatch :as d]
            [hive-mcp.events.handlers :as h]
            [taoensso.timbre :as log]))

(defn- reset-counters [f]
  (d/reset-unhandled-effect-counts!)
  (f)
  (d/reset-unhandled-effect-counts!))

(use-fixtures :each reset-counters)

(defn- capture-log-levels
  "Run thunk while capturing the timbre log level of every call.
   Returns a vector of keywords (e.g. [:warn :warn … :error])."
  [thunk]
  (let [captured (atom [])
        appender {:enabled? true
                  :async? false
                  :min-level :debug
                  :fn (fn [{:keys [level]}] (swap! captured conj level))}]
    (log/with-merged-config
      {:appenders {:capture appender}
       :min-level :debug}
      (thunk))
    @captured))

(deftest counter-tracks-misses-per-effect-id
  (testing "Each missing effect-id increments its own counter independently"
    (let [ctx {:effects {:nope-a {} :nope-b {}}}]
      (dotimes [_ 3] (d/do-fx ctx))
      (let [counts (d/unhandled-effect-counts)]
        (is (= 3 (:nope-a counts)) "nope-a counted 3 misses")
        (is (= 3 (:nope-b counts)) "nope-b counted 3 misses")))))

(deftest escalates-from-warn-to-error-at-threshold
  (testing "Log level escalates to :error once miss count crosses threshold"
    (let [ctx       {:effects {:nope-x {}}}
          threshold d/unhandled-effect-warn-threshold
          iterations (+ threshold 2)
          levels    (capture-log-levels
                     (fn [] (dotimes [_ iterations] (d/do-fx ctx))))
          ;; Levels are interleaved with whatever else fires during do-fx;
          ;; filter to just the warn/error pair we generate per miss.
          relevant  (filterv #{:warn :error} levels)]
      (is (= iterations (count relevant))
          (str "exactly one warn/error per miss (saw " (count relevant) ")"))
      (is (every? #(= :warn %) (subvec relevant 0 (dec threshold)))
          "below threshold: every miss logs WARN")
      (is (= :error (nth relevant threshold))
          "first miss after threshold flips to ERROR")
      (is (every? #(= :error %) (subvec relevant threshold))
          "post-threshold: every subsequent miss stays ERROR"))))

(deftest registered-handler-bypasses-counter
  (testing "Effects with registered handlers do not increment the miss counter"
    (require '[hive.events.fx :as fx])
    (let [fx-ns  (the-ns 'hive.events.fx)
          reg-fx (ns-resolve fx-ns 'reg-fx)]
      (reg-fx :probe/ok (fn [_] :ok))
      (try
        (d/do-fx {:effects {:probe/ok {}}})
        (is (empty? (d/unhandled-effect-counts))
            "registered handler keeps counter at zero")
        (finally
          (when-let [registry (ns-resolve fx-ns 'fx-registry)]
            (swap! @registry dissoc :probe/ok)))))))

;; =============================================================================
;; ENGINE-L0.4 — boot-time handler completeness check
;; =============================================================================

(deftest verify-handlers-detects-missing
  (testing "verify-handlers! reports missing canonical events"
    (let [r (h/verify-handlers!)]
      (is (set? (:missing r)) "returns a set of missing event-ids")
      (is (set? (:extra r))   "returns a set of extras")
      (is (set? (:registered r)) "returns the live registered set"))))
