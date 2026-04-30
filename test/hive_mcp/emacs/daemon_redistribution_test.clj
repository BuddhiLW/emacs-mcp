(ns hive-mcp.emacs.daemon-redistribution-test
  "Tests for ling redistribution (Multi-Daemon W4).

   Covers:
   - Overloaded daemon detection (degraded health, high ling count)
   - Migration candidate finding (idle lings only)
   - Redistribution planning (score threshold, max migrations)
   - Migration execution (rebind, race condition guards)
   - Full redistribute-lings! orchestration
   - Redistribution status monitoring
   - Edge cases (no overloaded, no targets, working lings skipped)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.emacs.daemon-selection :as selection]
            [hive-mcp.emacs.daemon-redistribution :as redist]
            [hive-mcp.emacs.daemon :as proto]
            [hive-mcp.emacs.daemon-ds :as daemon-ds]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.lings :as lings]
            [datascript.core :as d]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(def ^:private store (daemon-ds/create-store))

(use-fixtures :each (iso/with-isolations :swarm-ds))

;;; =============================================================================
;;; Helper Functions
;;; =============================================================================

(defn- setup-daemon!
  "Register a daemon and optionally set its status/health."
  [daemon-id & {:keys [status health-score]
                :or {status :active health-score 100}}]
  (proto/register! store daemon-id {})
  (let [c (conn/ensure-conn)
        db @c
        eid (:db/id (d/entity db [:emacs-daemon/id daemon-id]))]
    (d/transact! c [(cond-> {:db/id eid
                             :emacs-daemon/status status}
                      health-score
                      (assoc :emacs-daemon/health-score health-score))])))

(defn- setup-ling!
  "Create a ling (slave) and bind it to a daemon."
  [ling-id daemon-id & {:keys [status project-id]
                        :or {status :idle project-id "test-project"}}]
  (lings/add-slave! ling-id {:status status :project-id project-id})
  (proto/bind-ling! store daemon-id ling-id))

;;; =============================================================================
;;; Constants Tests
;;; =============================================================================

(deftest constants-sanity-test
  (testing "W4 constants have sane values"
    (is (pos? redist/redistribution-score-threshold)
        "Score threshold should be positive")
    (is (pos? redist/max-migrations-per-cycle)
        "Max migrations should be positive")
    (is (<= redist/overloaded-ling-threshold selection/max-lings-per-daemon)
        "Overloaded threshold should be <= max lings")))

;;; =============================================================================
;;; Find Overloaded Daemons Tests
;;; =============================================================================

(deftest find-overloaded-no-daemons-test
  (testing "find-overloaded-daemons returns nil when no daemons exist"
    (is (nil? (redist/find-overloaded-daemons store)))))

(deftest find-overloaded-all-healthy-test
  (testing "find-overloaded-daemons returns nil when all daemons are healthy and light"
    (setup-daemon! "healthy-d" :health-score 90)
    (setup-ling! "ling-1" "healthy-d")
    (is (nil? (redist/find-overloaded-daemons store)))))

(deftest find-overloaded-degraded-health-test
  (testing "find-overloaded-daemons detects degraded health daemons"
    (setup-daemon! "degraded-d" :health-score 50)
    (setup-ling! "ling-1" "degraded-d")
    (let [overloaded (redist/find-overloaded-daemons store)]
      (is (= 1 (count overloaded)))
      (is (= "degraded-d" (:emacs-daemon/id (first overloaded)))))))

(deftest find-overloaded-high-ling-count-test
  (testing "find-overloaded-daemons detects daemons at overloaded threshold"
    (setup-daemon! "busy-d" :health-score 90)
    ;; Bind 4 lings (= overloaded-ling-threshold)
    (doseq [i (range redist/overloaded-ling-threshold)]
      (setup-ling! (str "busy-ling-" i) "busy-d"))
    (let [overloaded (redist/find-overloaded-daemons store)]
      (is (= 1 (count overloaded)))
      (is (= "busy-d" (:emacs-daemon/id (first overloaded)))))))

(deftest find-overloaded-ignores-dead-daemons-test
  (testing "find-overloaded-daemons ignores non-active daemons (W3 handles those)"
    (setup-daemon! "stale-d" :status :stale :health-score 20)
    (setup-ling! "orphan" "stale-d")
    (is (nil? (redist/find-overloaded-daemons store)))))

(deftest find-overloaded-ignores-empty-degraded-test
  (testing "find-overloaded-daemons ignores degraded daemons with no lings"
    (setup-daemon! "empty-degraded" :health-score 40)
    ;; No lings bound — nothing to offload
    (is (nil? (redist/find-overloaded-daemons store)))))

(deftest find-overloaded-ignores-healthy-unhealthy-test
  (testing "find-overloaded-daemons: unhealthy daemons (< 30) are NOT overloaded targets
            because they're disqualified from selection entirely"
    ;; Unhealthy with 1 ling — while not healthy, it's active and has lings
    ;; The health-level check catches :degraded but score < 30 is :unhealthy
    ;; which is NOT matched by (= :degraded level)
    ;; However, if ling-count >= threshold, it still qualifies
    (setup-daemon! "sick-d" :health-score 20)
    (setup-ling! "sick-ling" "sick-d")
    ;; 1 ling < threshold (4), and :unhealthy ≠ :degraded — should NOT be overloaded
    (is (nil? (redist/find-overloaded-daemons store)))))

;;; =============================================================================
;;; Find Migration Candidates Tests
;;; =============================================================================

(deftest find-migration-candidates-idle-test
  (testing "find-migration-candidates returns idle lings"
    (setup-daemon! "d1" :health-score 50)
    (setup-ling! "idle-1" "d1" :status :idle)
    (setup-ling! "idle-2" "d1" :status :idle)
    (let [daemon (proto/get-daemon store "d1")
          candidates (redist/find-migration-candidates daemon)]
      (is (= 2 (count candidates)))
      (is (every? #(= :idle (:ling-status %)) candidates)))))

(deftest find-migration-candidates-skips-working-test
  (testing "find-migration-candidates skips working/blocked lings"
    (setup-daemon! "d1" :health-score 50)
    (setup-ling! "idle-1" "d1" :status :idle)
    (setup-ling! "working-1" "d1" :status :working)
    (setup-ling! "blocked-1" "d1" :status :blocked)
    (let [daemon (proto/get-daemon store "d1")
          candidates (redist/find-migration-candidates daemon)]
      (is (= 1 (count candidates)))
      (is (= "idle-1" (:ling-id (first candidates)))))))

(deftest find-migration-candidates-empty-daemon-test
  (testing "find-migration-candidates returns empty for daemon with no lings"
    (setup-daemon! "empty-d")
    (let [daemon (proto/get-daemon store "empty-d")
          candidates (redist/find-migration-candidates daemon)]
      (is (empty? candidates)))))

(deftest find-migration-candidates-includes-project-id-test
  (testing "find-migration-candidates includes project-id for affinity scoring"
    (setup-daemon! "d1" :health-score 50)
    (setup-ling! "ling-1" "d1" :status :idle :project-id "my-project")
    (let [daemon (proto/get-daemon store "d1")
          candidates (redist/find-migration-candidates daemon)]
      (is (= "my-project" (:project-id (first candidates)))))))

;;; =============================================================================
;;; Plan Redistribution Tests
;;; =============================================================================

(deftest plan-redistribution-no-overloaded-test
  (testing "plan-redistribution returns nil when no daemons are overloaded"
    (setup-daemon! "healthy" :health-score 90)
    (setup-ling! "ling-1" "healthy")
    (is (nil? (redist/plan-redistribution store)))))

(deftest plan-redistribution-no-better-target-test
  (testing "plan-redistribution returns nil when no target is significantly better"
    ;; Two daemons with similar scores — no improvement above threshold
    ;; Use different project-ids to avoid affinity bonuses
    (setup-daemon! "d1" :health-score 55)
    (setup-daemon! "d2" :health-score 60)
    ;; Make d1 overloaded (4 lings) with project-a
    (doseq [i (range 4)]
      (setup-ling! (str "d1-ling-" i) "d1" :status :idle :project-id "project-a"))
    ;; d2 has 4 lings with project-b — at capacity, disqualified
    (doseq [i (range 4)]
      (setup-ling! (str "d2-ling-" i) "d2" :status :idle :project-id "project-b"))
    ;; d2 is at capacity (4 lings) and will be disqualified from being a target
    ;; since capacity = 5 - 4 = 1 slot, but at 4 it's NOT disqualified
    ;; However d2 is also overloaded itself (4 lings >= threshold)
    ;; d1 source: health=55 + capacity=(5-4)*10=10 = 65
    ;; d2 target: health=60 + capacity=(5-4)*10=10 + affinity=0 = 70
    ;; Improvement: 70 - 65 = 5 < threshold(20) → no migration
    (is (nil? (redist/plan-redistribution store)))))

(deftest plan-redistribution-creates-plan-test
  (testing "plan-redistribution creates migration plan when improvement is significant"
    ;; Degraded daemon with idle lings
    (setup-daemon! "degraded" :health-score 45)
    (setup-ling! "migrant-1" "degraded" :status :idle)
    (setup-ling! "migrant-2" "degraded" :status :idle)
    ;; Healthy empty daemon
    (setup-daemon! "healthy" :health-score 95)
    (let [plan (redist/plan-redistribution store)]
      (is (some? plan) "Should produce a migration plan")
      (is (pos? (count plan)))
      ;; Check plan structure
      (let [m (first plan)]
        (is (= "degraded" (:source-daemon m)))
        (is (= "healthy" (:target-daemon m)))
        (is (pos? (:improvement m)))
        (is (>= (:improvement m) redist/redistribution-score-threshold))))))

(deftest plan-redistribution-caps-migrations-test
  (testing "plan-redistribution caps at max-migrations-per-cycle"
    ;; Degraded daemon with many idle lings
    (setup-daemon! "degraded" :health-score 40)
    (doseq [i (range 5)]
      (setup-ling! (str "migrant-" i) "degraded" :status :idle))
    ;; Healthy empty daemon
    (setup-daemon! "healthy" :health-score 95)
    (let [plan (redist/plan-redistribution store)]
      (is (some? plan))
      (is (<= (count plan) redist/max-migrations-per-cycle)
          "Should not exceed max migrations per cycle"))))

(deftest plan-redistribution-skips-working-lings-test
  (testing "plan-redistribution only plans for idle lings"
    ;; Overloaded daemon but all lings are working
    (setup-daemon! "overloaded" :health-score 50)
    (doseq [i (range 4)]
      (setup-ling! (str "worker-" i) "overloaded" :status :working))
    (setup-daemon! "healthy" :health-score 95)
    (is (nil? (redist/plan-redistribution store))
        "No plan when all lings are working")))

;;; =============================================================================
;;; Migrate Ling Tests
;;; =============================================================================

(deftest migrate-ling-success-test
  (testing "migrate-ling! successfully moves idle ling"
    (setup-daemon! "source" :health-score 50)
    (setup-daemon! "target" :health-score 90)
    (setup-ling! "migrant" "source" :status :idle)
    (let [migration {:ling-id "migrant"
                     :source-daemon "source"
                     :target-daemon "target"}
          result (redist/migrate-ling! store migration)]
      (is (true? (:success? result)))
      (is (= "migrant" (:ling-id result)))
      ;; Verify binding moved
      (let [source-d (proto/get-daemon store "source")
            target-d (proto/get-daemon store "target")]
        (is (not (contains? (or (:emacs-daemon/lings source-d) #{}) "migrant"))
            "Ling should be unbound from source")
        (is (contains? (:emacs-daemon/lings target-d) "migrant")
            "Ling should be bound to target")))))

(deftest migrate-ling-race-condition-test
  (testing "migrate-ling! skips ling that started working (race condition)"
    (setup-daemon! "source" :health-score 50)
    (setup-daemon! "target" :health-score 90)
    (setup-ling! "worker" "source" :status :working) ;; Changed before migration
    (let [migration {:ling-id "worker"
                     :source-daemon "source"
                     :target-daemon "target"}
          result (redist/migrate-ling! store migration)]
      (is (false? (:success? result)))
      (is (= :no-longer-idle (:reason result)))
      ;; Verify ling stayed on source
      (let [source-d (proto/get-daemon store "source")]
        (is (contains? (:emacs-daemon/lings source-d) "worker"))))))

(deftest migrate-ling-not-found-test
  (testing "migrate-ling! handles ling that was killed"
    (setup-daemon! "source" :health-score 50)
    (setup-daemon! "target" :health-score 90)
    ;; Don't create the ling — simulate it being killed
    (let [migration {:ling-id "ghost"
                     :source-daemon "source"
                     :target-daemon "target"}
          result (redist/migrate-ling! store migration)]
      (is (false? (:success? result)))
      (is (= :ling-not-found (:reason result))))))

;;; =============================================================================
;;; Full Redistribute Tests
;;; =============================================================================

(deftest redistribute-lings-balanced-test
  (testing "redistribute-lings! returns nil when system is balanced"
    (setup-daemon! "d1" :health-score 90)
    (setup-daemon! "d2" :health-score 85)
    (setup-ling! "ling-1" "d1")
    (setup-ling! "ling-2" "d2")
    (is (nil? (redist/redistribute-lings! store)))))

(deftest redistribute-lings-executes-test
  (testing "redistribute-lings! moves idle lings from degraded to healthy daemon"
    ;; Degraded, overloaded daemon
    (setup-daemon! "degraded" :health-score 40)
    (setup-ling! "idle-1" "degraded" :status :idle)
    (setup-ling! "idle-2" "degraded" :status :idle)
    (setup-ling! "worker-1" "degraded" :status :working)
    (setup-ling! "worker-2" "degraded" :status :working)
    ;; Healthy empty daemon
    (setup-daemon! "healthy" :health-score 95)
    (let [result (redist/redistribute-lings! store)]
      (is (some? result))
      (is (pos? (:migrations-planned result)))
      (is (pos? (:migrations-executed result)))
      ;; Working lings should NOT have moved
      (let [degraded (proto/get-daemon store "degraded")
            healthy (proto/get-daemon store "healthy")]
        (is (contains? (:emacs-daemon/lings degraded) "worker-1")
            "Working lings should stay on degraded daemon")
        (is (contains? (:emacs-daemon/lings degraded) "worker-2")
            "Working lings should stay on degraded daemon")))))

(deftest redistribute-lings-mixed-results-test
  (testing "redistribute-lings! reports partial success correctly"
    ;; Degraded daemon with mixed lings
    (setup-daemon! "degraded" :health-score 40)
    (setup-ling! "idle-1" "degraded" :status :idle)
    ;; Healthy target
    (setup-daemon! "healthy" :health-score 95)
    (let [result (redist/redistribute-lings! store)]
      (is (some? result))
      (is (= (:migrations-planned result)
             (+ (:migrations-executed result) (:migrations-failed result)))
          "planned = executed + failed"))))

;;; =============================================================================
;;; Redistribution Status Tests
;;; =============================================================================

(deftest redistribution-status-balanced-test
  (testing "redistribution-status returns nil when balanced"
    (setup-daemon! "d1" :health-score 90)
    (setup-ling! "ling-1" "d1")
    (is (nil? (redist/redistribution-status store)))))

(deftest redistribution-status-shows-overloaded-test
  (testing "redistribution-status shows overloaded daemons and planned migrations"
    (setup-daemon! "degraded" :health-score 45)
    (setup-ling! "idle-1" "degraded" :status :idle)
    (setup-ling! "idle-2" "degraded" :status :idle)
    (setup-daemon! "healthy" :health-score 95)
    (let [status (redist/redistribution-status store)]
      (is (some? status))
      (is (pos? (:overloaded-count status)))
      (is (seq (:overloaded-daemons status)))
      ;; Check overloaded daemon details
      (let [od (first (:overloaded-daemons status))]
        (is (= "degraded" (:daemon-id od)))
        (is (= :degraded (:health-level od)))
        (is (= 2 (:ling-count od)))))))

;;; =============================================================================
;;; Integration Tests
;;; =============================================================================

(deftest full-redistribution-scenario-test
  (testing "Full W4 scenario: multi-daemon rebalancing"
    ;; 3 daemons:
    ;; d1: degraded (score 40), 4 idle lings — overloaded + degraded
    ;; d2: healthy (score 90), 1 ling — has capacity
    ;; d3: healthy (score 85), 2 lings — has capacity
    (setup-daemon! "d1" :health-score 40)
    (doseq [i (range 4)]
      (setup-ling! (str "d1-ling-" i) "d1" :status :idle))

    (setup-daemon! "d2" :health-score 90)
    (setup-ling! "d2-ling-0" "d2" :status :idle)

    (setup-daemon! "d3" :health-score 85)
    (setup-ling! "d3-ling-0" "d3" :status :idle)
    (setup-ling! "d3-ling-1" "d3" :status :idle)

    ;; Verify overloaded detection
    (let [overloaded (redist/find-overloaded-daemons store)]
      (is (= 1 (count overloaded)))
      (is (= "d1" (:emacs-daemon/id (first overloaded)))))

    ;; Execute redistribution
    (let [result (redist/redistribute-lings! store)]
      (is (some? result))
      (is (<= (:migrations-executed result) redist/max-migrations-per-cycle)
          "Should respect max migrations per cycle")
      ;; After redistribution, d1 should have fewer lings
      (let [d1 (proto/get-daemon store "d1")
            d1-lings (count (or (:emacs-daemon/lings d1) #{}))]
        (is (< d1-lings 4) "d1 should have lost lings")))))

(deftest redistribution-preserves-working-lings-test
  (testing "Redistribution never moves working lings, even when daemon is degraded"
    (setup-daemon! "degraded" :health-score 35)
    ;; All 4 lings are working
    (doseq [i (range 4)]
      (setup-ling! (str "worker-" i) "degraded" :status :working))
    (setup-daemon! "healthy" :health-score 95)

    ;; No redistribution should happen (no idle candidates)
    (is (nil? (redist/redistribute-lings! store)))

    ;; All workers still on degraded daemon
    (let [degraded (proto/get-daemon store "degraded")]
      (is (= 4 (count (:emacs-daemon/lings degraded)))))))

(deftest redistribution-idempotent-test
  (testing "Running redistribution twice doesn't cause issues"
    (setup-daemon! "degraded" :health-score 40)
    (setup-ling! "migrant" "degraded" :status :idle)
    (setup-daemon! "healthy" :health-score 95)

    ;; First run
    (let [result1 (redist/redistribute-lings! store)]
      (is (some? result1))
      (is (pos? (:migrations-executed result1))))

    ;; Second run — should be nil (system now balanced)
    (let [result2 (redist/redistribute-lings! store)]
      ;; Either nil (no overloaded) or no migrations planned
      (is (or (nil? result2)
              (zero? (:migrations-executed result2)))
          "Second run should have nothing to do"))))
