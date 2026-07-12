(ns hive-mcp.scheduler.decay-test
  "Tests for L2 time-decay scheduler.

   Tests cover:
   - Decay cycle execution (memory + edge + disc)
   - Scheduler lifecycle (start/stop/restart)
   - Status introspection
   - Error isolation (individual failures don't crash cycle)
   - Config reading"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.scheduler.decay :as decay]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn cleanup-fixture [f]
  ;; Ensure scheduler is stopped before and after each test
  (decay/stop!)
  (try
    (f)
    (finally
      (decay/stop!))))

(use-fixtures :each cleanup-fixture)

;; =============================================================================
;; Unit Tests: run-decay-cycle!
;; =============================================================================

(deftest test-run-decay-cycle-returns-expected-shape
  (testing "run-decay-cycle! returns map with all expected keys"
    (let [result (decay/run-decay-cycle! {:memory-limit 5
                                          :edge-limit 5
                                          :disc-enabled false})]
      (is (map? result) "result should be a map")
      (is (contains? result :memory-stats) "should have :memory-stats")
      (is (contains? result :edge-stats) "should have :edge-stats")
      (is (contains? result :disc-stats) "should have :disc-stats")
      (is (contains? result :cycle-number) "should have :cycle-number")
      (is (contains? result :duration-ms) "should have :duration-ms")
      (is (contains? result :timestamp) "should have :timestamp")
      (is (pos? (:cycle-number result)) "cycle-number should be positive")
      (is (>= (:duration-ms result) 0) "duration should be non-negative"))))

(deftest test-run-decay-cycle-increments-counter
  (testing "each call increments cycle counter"
    (let [r1 (decay/run-decay-cycle! {:disc-enabled false :memory-limit 1 :edge-limit 1})
          r2 (decay/run-decay-cycle! {:disc-enabled false :memory-limit 1 :edge-limit 1})]
      (is (= (inc (:cycle-number r1)) (:cycle-number r2))
          "cycle numbers should be sequential"))))

(deftest test-run-decay-cycle-disc-disabled
  (testing "disc decay skipped when disc-enabled false"
    (let [result (decay/run-decay-cycle! {:disc-enabled false
                                          :memory-limit 1
                                          :edge-limit 1})]
      (is (true? (get-in result [:disc-stats :skipped]))
          "disc-stats should show skipped"))))

(deftest test-run-decay-cycle-includes-grounding-pass
  (testing "decay cycle runs a bounded grounding pass and reports it"
    (let [result (decay/run-decay-cycle! {:memory-limit 1
                                          :edge-limit 1
                                          :disc-enabled false
                                          :grounding-enabled true
                                          :grounding-limit 1})]
      (is (contains? result :grounding-stats) "cycle must report a grounding pass")
      (is (map? (:grounding-stats result))))))

(deftest test-run-decay-cycle-grounding-disabled
  (testing "grounding pass skipped when :grounding-enabled false"
    (let [result (decay/run-decay-cycle! {:memory-limit 1
                                          :edge-limit 1
                                          :disc-enabled false
                                          :grounding-enabled false})]
      (is (true? (get-in result [:grounding-stats :skipped]))
          "grounding-stats should show skipped"))))

(deftest test-scheduler-config-exposes-grounding-knobs
  (testing "status :config carries the grounding knobs with code-side defaults"
    (let [cfg (:config (decay/status))]
      (is (contains? cfg :grounding-enabled))
      (is (contains? cfg :grounding-limit))
      (is (integer? (:grounding-limit cfg))))))

(deftest test-run-decay-cycle-error-isolation
  (testing "individual decay failures don't crash the full cycle"
    ;; Even if the underlying fns fail (e.g., Chroma not running),
    ;; run-decay-cycle! should return a result map, never throw
    (let [result (decay/run-decay-cycle! {:memory-limit 1
                                          :edge-limit 1
                                          :disc-enabled true})]
      (is (map? result) "should still return a map even if individual operations fail")
      (is (contains? result :memory-stats))
      (is (contains? result :edge-stats))
      (is (contains? result :disc-stats)))))

;; =============================================================================
;; Unit Tests: Scheduler Lifecycle
;; =============================================================================

(deftest test-status-when-not-running
  (testing "status reports not-running when scheduler hasn't started"
    (let [s (decay/status)]
      (is (false? (:running? s)))
      (is (map? (:config s))))))

(deftest test-stop-when-not-running
  (testing "stop! is safe to call when not running"
    (let [result (decay/stop!)]
      (is (false? (:stopped result)))
      (is (= "not-running" (:reason result))))))

(deftest test-start-and-stop-lifecycle
  (testing "start! creates executor, stop! shuts it down"
    ;; Start with short interval for testing (but we'll stop before it fires)
    (let [start-result (decay/start!)]
      ;; Might be disabled via config, handle both cases
      (if (:started start-result)
        (do
          (is (true? (:running? (decay/status))))
          (let [stop-result (decay/stop!)]
            (is (true? (:stopped stop-result)))
            (is (false? (:running? (decay/status))))))
        ;; If disabled, that's OK too
        (is (string? (:reason start-result)))))))

(deftest test-start-idempotent
  (testing "calling start! twice returns already-running"
    (let [r1 (decay/start!)]
      (when (:started r1)
        (let [r2 (decay/start!)]
          (is (false? (:started r2)))
          (is (= "already-running" (:reason r2))))))))

(deftest test-restart-picks-up-new-state
  (testing "restart! stops and starts cleanly"
    (let [r1 (decay/start!)]
      (when (:started r1)
        (let [r2 (decay/restart!)]
          ;; restart should return a new start result
          (is (or (:started r2) (string? (:reason r2)))))))))

;; =============================================================================
;; Unit Tests: Status Introspection
;; =============================================================================

(deftest test-status-after-manual-cycle
  (testing "status shows last-result after manual run"
    (decay/run-decay-cycle! {:disc-enabled false :memory-limit 1 :edge-limit 1})
    (let [s (decay/status)]
      (is (pos? (:cycle-count s)) "should have cycle count > 0")
      (is (some? (:last-run s)) "should have last-run timestamp")
      (is (map? (:last-result s)) "should have last-result summary"))))
