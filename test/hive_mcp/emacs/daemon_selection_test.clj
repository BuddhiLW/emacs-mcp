(ns hive-mcp.emacs.daemon-selection-test
  "Tests for daemon selection logic (Multi-Daemon W1 + W2).

   Covers:
   - Health classification (healthy/degraded/unhealthy)
   - Daemon metrics (ling count, capacity, project affinity)
   - Composite scoring
   - Selection algorithm (best candidate, fallback, disqualification)
   - Health score updates
   - W2: Latency/error/load health degradation (pure functions)
   - W2: EWMA health score computation
   - W2: heartbeat! with mock ping function"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.emacs.daemon-selection :as selection]
            [hive-mcp.emacs.daemon :as proto]
            [hive-mcp.emacs.daemon-ds :as daemon-ds]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.lings :as lings]
            [datascript.core :as d]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.emacs.daemon-scoring :as scoring]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(def ^:private store (daemon-ds/create-store))

(use-fixtures :each (iso/with-isolations :swarm-ds))

;;; =============================================================================
;;; Health Classification Tests
;;; =============================================================================

(deftest health-level-test
  (testing "health-level classifies scores correctly"
    (is (= :healthy (selection/health-level 100)))
    (is (= :healthy (selection/health-level 70)))
    (is (= :degraded (selection/health-level 69)))
    (is (= :degraded (selection/health-level 30)))
    (is (= :unhealthy (selection/health-level 29)))
    (is (= :unhealthy (selection/health-level 0)))))

(deftest health-level-nil-default-test
  (testing "health-level treats nil as default (healthy)"
    (is (= :healthy (selection/health-level nil)))))

(deftest healthy?-test
  (testing "healthy? returns true for healthy and degraded"
    (is (true? (selection/healthy? 100)))
    (is (true? (selection/healthy? 70)))
    (is (true? (selection/healthy? 50)))
    (is (true? (selection/healthy? 30)))
    (is (false? (selection/healthy? 29)))
    (is (false? (selection/healthy? 0)))))

;;; =============================================================================
;;; Daemon Metrics Tests
;;; =============================================================================

(deftest daemon-ling-count-test
  (testing "daemon-ling-count counts bound lings"
    (is (= 0 (selection/daemon-ling-count {:emacs-daemon/lings nil})))
    (is (= 0 (selection/daemon-ling-count {})))
    (is (= 2 (selection/daemon-ling-count {:emacs-daemon/lings #{"a" "b"}})))
    (is (= 5 (selection/daemon-ling-count {:emacs-daemon/lings #{"a" "b" "c" "d" "e"}})))))

(deftest daemon-has-capacity?-test
  (testing "daemon-has-capacity? checks against max-lings-per-daemon"
    (is (true? (selection/daemon-has-capacity? {:emacs-daemon/lings #{}})))
    (is (true? (selection/daemon-has-capacity? {:emacs-daemon/lings #{"a" "b" "c" "d"}})))
    (is (false? (selection/daemon-has-capacity? {:emacs-daemon/lings #{"a" "b" "c" "d" "e"}}))))

  (testing "daemon-has-capacity? respects custom max"
    (is (true? (selection/daemon-has-capacity? {:emacs-daemon/lings #{"a" "b"}} 3)))
    (is (false? (selection/daemon-has-capacity? {:emacs-daemon/lings #{"a" "b" "c"}} 3)))))

(deftest daemon-project-affinity-nil-project-test
  (testing "daemon-project-affinity returns 0 for nil project-id"
    (is (= 0 (selection/daemon-project-affinity {} nil)))))

(deftest daemon-project-affinity-with-project-test
  (testing "daemon-project-affinity scores based on same-project lings"
    ;; Register a daemon and bind lings with project-ids
    (proto/register! store "affinity-daemon" {})
    (proto/bind-ling! store "affinity-daemon" "ling-1")
    (proto/bind-ling! store "affinity-daemon" "ling-2")

    ;; Create slave entities with project-ids
    (lings/add-slave! "ling-1" {:project-id "hive-mcp"})
    (lings/add-slave! "ling-2" {:project-id "hive-mcp"})

    (let [daemon (proto/get-daemon store "affinity-daemon")]
      ;; 2 lings with same project => affinity = 5
      (is (= 5 (selection/daemon-project-affinity daemon "hive-mcp")))
      ;; Different project => affinity = 0
      (is (= 0 (selection/daemon-project-affinity daemon "other-project"))))))

(deftest daemon-project-affinity-high-test
  (testing "daemon-project-affinity returns 10 for 3+ same-project lings"
    (proto/register! store "high-aff" {})
    (doseq [i (range 3)]
      (let [ling-id (str "ha-ling-" i)]
        (proto/bind-ling! store "high-aff" ling-id)
        (lings/add-slave! ling-id {:project-id "big-project"})))

    (let [daemon (proto/get-daemon store "high-aff")]
      (is (= 10 (selection/daemon-project-affinity daemon "big-project"))))))

;;; =============================================================================
;;; Scoring Tests
;;; =============================================================================

(deftest score-daemon-active-healthy-test
  (testing "score-daemon computes composite score for healthy active daemon"
    (let [daemon {:emacs-daemon/id "d1"
                  :emacs-daemon/status :active
                  :emacs-daemon/health-score 90
                  :emacs-daemon/lings #{"ling-1" "ling-2"}}
          result (selection/score-daemon daemon nil)]
      (is (= "d1" (:daemon-id result)))
      (is (false? (:disqualified? result)))
      (is (= 2 (:ling-count result)))
      (is (= :healthy (:health-level result)))
      ;; Score = health(90) + capacity(3*10=30) + affinity(0) = 120
      (is (= 120 (:score result))))))

(deftest score-daemon-disqualified-inactive-test
  (testing "score-daemon disqualifies non-active daemons"
    (let [daemon {:emacs-daemon/id "d2"
                  :emacs-daemon/status :stale
                  :emacs-daemon/health-score 100
                  :emacs-daemon/lings #{}}
          result (selection/score-daemon daemon nil)]
      (is (true? (:disqualified? result)))
      (is (= -1 (:score result)))
      (is (some #{:not-active} (:disqualify-reasons result))))))

(deftest score-daemon-disqualified-at-capacity-test
  (testing "score-daemon disqualifies daemons at capacity"
    (let [daemon {:emacs-daemon/id "d3"
                  :emacs-daemon/status :active
                  :emacs-daemon/health-score 100
                  :emacs-daemon/lings #{"a" "b" "c" "d" "e"}}
          result (selection/score-daemon daemon nil)]
      (is (true? (:disqualified? result)))
      (is (some #{:at-capacity} (:disqualify-reasons result))))))

(deftest score-daemon-disqualified-unhealthy-test
  (testing "score-daemon disqualifies unhealthy daemons"
    (let [daemon {:emacs-daemon/id "d4"
                  :emacs-daemon/status :active
                  :emacs-daemon/health-score 10
                  :emacs-daemon/lings #{}}
          result (selection/score-daemon daemon nil)]
      (is (true? (:disqualified? result)))
      (is (some #{:unhealthy} (:disqualify-reasons result))))))

(deftest score-daemon-capacity-bonus-test
  (testing "score-daemon gives higher score to emptier daemons"
    (let [empty-d {:emacs-daemon/id "empty"
                   :emacs-daemon/status :active
                   :emacs-daemon/health-score 80
                   :emacs-daemon/lings #{}}
          full-d {:emacs-daemon/id "busy"
                  :emacs-daemon/status :active
                  :emacs-daemon/health-score 80
                  :emacs-daemon/lings #{"a" "b" "c" "d"}}
          empty-score (:score (selection/score-daemon empty-d nil))
          full-score (:score (selection/score-daemon full-d nil))]
      ;; empty: 80 + 50 = 130, full: 80 + 10 = 90
      (is (> empty-score full-score)))))

;;; =============================================================================
;;; Selection Algorithm Tests
;;; =============================================================================

(deftest select-daemon-no-daemons-test
  (testing "select-daemon falls back to default when no daemons exist"
    (let [result (selection/select-daemon store {:default-id "fallback"})]
      (is (= "fallback" (:daemon-id result)))
      (is (= :no-daemons (:reason result))))))

(deftest select-daemon-single-healthy-test
  (testing "select-daemon picks the only healthy daemon"
    (proto/register! store "only-one" {})
    (let [result (selection/select-daemon store {:default-id "fallback"})]
      (is (= "only-one" (:daemon-id result)))
      (is (= :selected (:reason result))))))

(deftest select-daemon-picks-healthiest-test
  (testing "select-daemon picks healthiest daemon with most capacity"
    (proto/register! store "healthy-d" {})
    (proto/register! store "degraded-d" {})

    ;; Set health scores via DataScript
    (let [c (conn/ensure-conn)
          db @c]
      (d/transact! c [{:db/id (:db/id (d/entity db [:emacs-daemon/id "healthy-d"]))
                       :emacs-daemon/health-score 95}
                      {:db/id (:db/id (d/entity db [:emacs-daemon/id "degraded-d"]))
                       :emacs-daemon/health-score 50}]))

    (let [result (selection/select-daemon store {})]
      (is (= "healthy-d" (:daemon-id result)))
      (is (= :selected (:reason result))))))

(deftest select-daemon-prefers-emptier-test
  (testing "select-daemon prefers daemon with fewer lings (same health)"
    (proto/register! store "empty-d" {})
    (proto/register! store "busy-d" {})

    ;; Bind 4 lings to busy daemon
    (doseq [i (range 4)]
      (proto/bind-ling! store "busy-d" (str "busy-ling-" i)))

    (let [result (selection/select-daemon store {})]
      (is (= "empty-d" (:daemon-id result))))))

(deftest select-daemon-all-disqualified-test
  (testing "select-daemon falls back to default when all daemons are disqualified"
    (proto/register! store "stale-d" {})
    (proto/register! store "terminated-d" {})

    ;; Disqualify both
    (let [c (conn/ensure-conn)
          db @c]
      (d/transact! c [{:db/id (:db/id (d/entity db [:emacs-daemon/id "stale-d"]))
                       :emacs-daemon/status :stale}
                      {:db/id (:db/id (d/entity db [:emacs-daemon/id "terminated-d"]))
                       :emacs-daemon/status :terminated}]))

    (let [result (selection/select-daemon store {:default-id "fallback"})]
      (is (= "fallback" (:daemon-id result)))
      (is (= :all-disqualified (:reason result))))))

(deftest select-daemon-skips-at-capacity-test
  (testing "select-daemon skips daemons at max capacity"
    (proto/register! store "full-d" {})
    (proto/register! store "free-d" {})

    ;; Fill one daemon to capacity (5 lings)
    (doseq [i (range 5)]
      (proto/bind-ling! store "full-d" (str "cap-ling-" i)))

    (let [result (selection/select-daemon store {})]
      (is (= "free-d" (:daemon-id result))))))

(deftest select-daemon-project-affinity-test
  (testing "select-daemon prefers daemons with project affinity"
    (proto/register! store "proj-d" {})
    (proto/register! store "other-d" {})

    ;; Bind lings with matching project to proj-d
    (proto/bind-ling! store "proj-d" "proj-ling-1")
    (lings/add-slave! "proj-ling-1" {:project-id "my-project"})

    ;; Balance capacity: give other-d a ling too (different project)
    ;; so capacity is equal and affinity is the tiebreaker
    (proto/bind-ling! store "other-d" "other-ling-1")
    (lings/add-slave! "other-ling-1" {:project-id "other-project"})

    (let [result (selection/select-daemon store {:project-id "my-project"})]
      (is (= "proj-d" (:daemon-id result))
          "Should prefer daemon with same-project lings when capacity is equal"))))

;;; =============================================================================
;;; Health Score Update Tests
;;; =============================================================================

(deftest update-health-score-test
  (testing "update-health-score! sets score on daemon"
    (proto/register! store "health-upd" {})
    (selection/update-health-score! "health-upd" 75)

    (let [d (proto/get-daemon store "health-upd")]
      (is (= 75 (:emacs-daemon/health-score d))))))

(deftest update-health-score-clamps-test
  (testing "update-health-score! clamps to 0-100 range"
    (proto/register! store "clamp-test" {})

    (selection/update-health-score! "clamp-test" 150)
    (is (= 100 (:emacs-daemon/health-score (proto/get-daemon store "clamp-test"))))

    (selection/update-health-score! "clamp-test" -50)
    (is (= 0 (:emacs-daemon/health-score (proto/get-daemon store "clamp-test"))))))

(deftest update-health-score-nonexistent-test
  (testing "update-health-score! returns nil for non-existent daemon"
    (is (nil? (selection/update-health-score! "non-existent" 50)))))

;;; =============================================================================
;;; Integration Tests
;;; =============================================================================

(deftest multi-daemon-selection-integration-test
  (testing "Full multi-daemon selection scenario"
    ;; Setup: 3 daemons with different health/capacity
    (proto/register! store "d-healthy" {})    ;; healthy, empty
    (proto/register! store "d-busy" {})       ;; healthy, 4 lings
    (proto/register! store "d-sick" {})       ;; unhealthy, empty

    ;; Set health scores
    (selection/update-health-score! "d-healthy" 90)
    (selection/update-health-score! "d-busy" 85)
    (selection/update-health-score! "d-sick" 20)

    ;; Fill d-busy with 4 lings
    (doseq [i (range 4)]
      (proto/bind-ling! store "d-busy" (str "busy-ling-" i)))

    ;; First spawn should go to d-healthy (high health, empty)
    (let [result (selection/select-daemon store {})]
      (is (= "d-healthy" (:daemon-id result)))
      (is (= :selected (:reason result))))

    ;; Verify d-sick is disqualified
    (let [scored (:scored (selection/select-daemon store {}))
          sick (first (filter #(= "d-sick" (:daemon-id %)) scored))]
      (is (true? (:disqualified? sick)))
      (is (some #{:unhealthy} (:disqualify-reasons sick))))))

;;; =============================================================================
;;; W2: Latency Score Delta Tests
;;; =============================================================================

(deftest latency-score-delta-fast-test
  (testing "latency-score-delta returns 0 for fast responses"
    (is (= 0 (scoring/latency-score-delta 100)))
    (is (= 0 (scoring/latency-score-delta 0)))
    (is (= 0 (scoring/latency-score-delta 500)))))

(deftest latency-score-delta-slow-test
  (testing "latency-score-delta penalizes slow responses"
    ;; Midpoint: 1250ms = half of range = ~-20
    (let [mid-penalty (scoring/latency-score-delta 1250)]
      (is (neg? mid-penalty))
      (is (> mid-penalty -30) "Should be between 0 and -40"))
    ;; Very slow: 2000ms+ = max penalty
    (is (= (- scoring/latency-max-penalty) (scoring/latency-score-delta 2000)))
    (is (= (- scoring/latency-max-penalty) (scoring/latency-score-delta 5000)))))

(deftest latency-score-delta-nil-test
  (testing "latency-score-delta treats nil as max penalty (failed ping)"
    (is (= (- scoring/latency-max-penalty) (scoring/latency-score-delta nil)))))

;;; =============================================================================
;;; W2: Error Score Delta Tests
;;; =============================================================================

(deftest error-score-delta-zero-test
  (testing "error-score-delta returns 0 for no errors"
    (is (= 0 (scoring/error-score-delta 0)))
    (is (= 0 (scoring/error-score-delta nil)))))

(deftest error-score-delta-scaling-test
  (testing "error-score-delta scales with consecutive errors"
    (is (= -15 (scoring/error-score-delta 1)))
    (is (= -30 (scoring/error-score-delta 2)))
    (is (= -45 (scoring/error-score-delta 3)))))

(deftest error-score-delta-caps-test
  (testing "error-score-delta caps at error-penalty-max"
    (is (= (- scoring/error-penalty-max) (scoring/error-score-delta 10)))
    (is (= (- scoring/error-penalty-max) (scoring/error-score-delta 100)))))

;;; =============================================================================
;;; W2: Ling Load Delta Tests
;;; =============================================================================

(deftest ling-load-delta-empty-test
  (testing "ling-load-delta returns 0 for 0 or 1 lings"
    (is (= 0 (scoring/ling-load-delta 0)))
    (is (= 0 (scoring/ling-load-delta 1)))
    (is (= 0 (scoring/ling-load-delta nil)))))

(deftest ling-load-delta-scaling-test
  (testing "ling-load-delta scales with lings beyond first"
    (is (= -2 (scoring/ling-load-delta 2)))
    (is (= -4 (scoring/ling-load-delta 3)))
    (is (= -8 (scoring/ling-load-delta 5)))))

;;; =============================================================================
;;; W2: Compute Health Score (EWMA) Tests
;;; =============================================================================

(deftest compute-health-score-perfect-test
  (testing "compute-health-score for perfect conditions (fast, no errors, no lings)"
    (let [score (scoring/compute-health-score 100 100 0 0)]
      (is (>= score 95) "Should stay near 100 with perfect conditions")
      (is (<= score 100)))))

(deftest compute-health-score-degraded-test
  (testing "compute-health-score degrades with slow latency"
    ;; From prev=100 with 3s latency: raw=60, EWMA=0.3*60+0.7*100=88
    ;; EWMA smoothing keeps it high on first bad reading
    (let [score (scoring/compute-health-score 100 3000 0 0)]
      (is (< score 95) "Should show some degradation with 3s latency")
      (is (> score 30) "But not critically low with just latency"))
    ;; Sustained slow latency from already degraded score drops further
    (let [score (scoring/compute-health-score 70 3000 0 0)]
      (is (< score 70) "Should degrade further from degraded state"))))

(deftest compute-health-score-errors-test
  (testing "compute-health-score degrades with consecutive errors"
    ;; From prev=100 with 1 error: raw = 100 - 40(nil latency) - 15(1 err) = 45
    ;; EWMA: 0.3*45 + 0.7*100 = 83.5 → 83
    (let [score-1err (scoring/compute-health-score 100 nil 1 0)
          score-3err (scoring/compute-health-score 100 nil 3 0)]
      (is (< score-1err 90) "1 error should degrade from 100")
      (is (< score-3err score-1err) "3 errors should be worse than 1"))))

(deftest compute-health-score-ewma-smoothing-test
  (testing "compute-health-score blends with previous score (EWMA)"
    ;; From score=80, perfect measurement should move toward 100 slowly
    (let [score (scoring/compute-health-score 80 100 0 0)]
      (is (> score 80) "Should increase from 80 toward 100")
      (is (< score 100) "But not jump to 100 immediately"))
    ;; From score=80, terrible measurement should not crash to 0
    (let [score (scoring/compute-health-score 80 nil 3 5)]
      (is (< score 80) "Should decrease from 80")
      (is (> score 10) "But EWMA smoothing prevents crash to 0"))))

(deftest compute-health-score-load-pressure-test
  (testing "compute-health-score accounts for ling load"
    (let [score-0 (scoring/compute-health-score 100 200 0 0)
          score-5 (scoring/compute-health-score 100 200 0 5)]
      (is (> score-0 score-5) "More lings should produce lower score"))))

(deftest compute-health-score-clamps-test
  (testing "compute-health-score clamps to 0-100 range"
    ;; Even with worst conditions, stays >= 0
    (let [score (scoring/compute-health-score 0 nil 10 5)]
      (is (>= score 0))
      (is (<= score 100)))
    ;; Even with best conditions from high prev, stays <= 100
    (let [score (scoring/compute-health-score 100 50 0 0)]
      (is (>= score 0))
      (is (<= score 100)))))

;;; =============================================================================
;;; W2: Heartbeat! Integration Tests
;;; =============================================================================

(deftest heartbeat!-success-test
  (testing "heartbeat! with successful ping updates health score"
    (proto/register! store "hb-ok" {})
    (let [mock-ping (fn [_] {:success true :duration-ms 150})
          result (selection/heartbeat! "hb-ok" mock-ping)]
      (is (some? result))
      (is (true? (:success? result)))
      (is (= 150 (:latency-ms result)))
      (is (= 0 (:consecutive-errors result)))
      (is (true? (:healthy? result)))
      ;; Verify DataScript was updated
      (let [d (proto/get-daemon store "hb-ok")]
        (is (some? (:emacs-daemon/health-score d)))
        (is (= :active (:emacs-daemon/status d)))
        (is (some? (:emacs-daemon/heartbeat-at d)))))))

(deftest heartbeat!-failure-test
  (testing "heartbeat! with failed ping degrades health"
    (proto/register! store "hb-fail" {})
    ;; First failure
    (let [mock-ping (fn [_] {:success false :error "Connection refused"})
          result (selection/heartbeat! "hb-fail" mock-ping)]
      (is (some? result))
      (is (false? (:success? result)))
      (is (nil? (:latency-ms result)))
      (is (= 1 (:consecutive-errors result)))
      ;; Health should have degraded from default 100
      (is (< (:health-score result) 100)))
    ;; Second failure - should degrade more
    (let [mock-ping (fn [_] {:success false :error "Connection refused"})
          result (selection/heartbeat! "hb-fail" mock-ping)]
      (is (= 2 (:consecutive-errors result)))
      (is (< (:health-score result) 80) "Two failures should degrade significantly"))))

(deftest heartbeat!-slow-response-test
  (testing "heartbeat! with slow response degrades health proportionally"
    (proto/register! store "hb-slow" {})
    (let [mock-ping (fn [_] {:success true :duration-ms 1500})
          result (selection/heartbeat! "hb-slow" mock-ping)]
      (is (true? (:success? result)))
      (is (= 1500 (:latency-ms result)))
      ;; Health should be degraded but not critically
      (is (< (:health-score result) 100))
      (is (> (:health-score result) 50)))))

(deftest heartbeat!-recovery-test
  (testing "heartbeat! recovers health after errors with recovery bonus"
    (proto/register! store "hb-recover" {})
    ;; First: induce errors
    (let [fail-ping (fn [_] {:success false :error "timeout"})]
      (selection/heartbeat! "hb-recover" fail-ping)
      (selection/heartbeat! "hb-recover" fail-ping))
    ;; Get health after errors
    (let [d-after-errors (proto/get-daemon store "hb-recover")
          score-after-errors (:emacs-daemon/health-score d-after-errors)]
      ;; Now recover with good heartbeat
      (let [ok-ping (fn [_] {:success true :duration-ms 100})
            result (selection/heartbeat! "hb-recover" ok-ping)]
        (is (true? (:success? result)))
        (is (= 0 (:consecutive-errors result)))
        ;; Health should have improved
        (is (> (:health-score result) score-after-errors)
            "Health should improve after recovery")))))

(deftest heartbeat!-error-threshold-status-test
  (testing "heartbeat! sets status to :error after 3+ consecutive failures"
    (proto/register! store "hb-err-thresh" {})
    (let [fail-ping (fn [_] {:success false :error "dead"})]
      ;; 3 consecutive failures
      (selection/heartbeat! "hb-err-thresh" fail-ping)
      (selection/heartbeat! "hb-err-thresh" fail-ping)
      (selection/heartbeat! "hb-err-thresh" fail-ping)
      ;; Should now be in :error status
      (let [d (proto/get-daemon store "hb-err-thresh")]
        (is (= :error (:emacs-daemon/status d)))
        (is (= 3 (:emacs-daemon/error-count d)))))))

(deftest heartbeat!-nonexistent-daemon-test
  (testing "heartbeat! returns nil for non-existent daemon"
    (is (nil? (selection/heartbeat! "no-such-daemon" (fn [_] {:success true :duration-ms 100}))))))

(deftest heartbeat!-with-ling-load-test
  (testing "heartbeat! accounts for ling count in health score"
    (proto/register! store "hb-loaded" {})
    ;; Bind 4 lings
    (doseq [i (range 4)]
      (proto/bind-ling! store "hb-loaded" (str "load-ling-" i)))
    (let [mock-ping (fn [_] {:success true :duration-ms 200})
          result (selection/heartbeat! "hb-loaded" mock-ping)]
      (is (= 4 (:ling-count result)))
      ;; Health should be slightly lower than a daemon with 0 lings
      ;; due to ling load penalty
      (is (< (:health-score result) 100)))))
