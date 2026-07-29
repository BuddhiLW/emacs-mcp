(ns hive-mcp.emacs.daemon-autoheal-test
  "Tests for auto-heal orphan cleanup (Multi-Daemon W3).

   Covers:
   - Orphan detection (find-orphaned-lings)
   - Classification (classify-orphan)
   - Healing actions (rebind, terminate, skip)
   - Full heal-all-orphans! orchestration
   - Edge cases (no orphans, no healthy daemons, mixed states)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.emacs.daemon-autoheal :as autoheal]
            [hive-mcp.emacs.daemon :as proto]
            [hive-mcp.emacs.daemon-ds :as daemon-ds]
            [hive-mcp.emacs.daemon-selection :as selection]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.lings :as lings]
            [hive-mcp.swarm.datascript.queries :as queries]
            [datascript.core :as d]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.test.stub.daemon-store :as daemon-stub]
            [hive-mcp.test.stub.emacs-runtime-ports :as ports-stub]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(def ^:private ^:dynamic store
  "Daemon store for the test, bound per test by daemon-store-fixture so it
   shares the isolated swarm conn."
  nil)

(use-fixtures :each
  (iso/with-isolations :swarm-ds)
  ports-stub/with-swarm-ports
  (daemon-stub/daemon-store-fixture #'store))

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
;;; Orphan Detection Tests
;;; =============================================================================

(deftest find-orphaned-lings-no-orphans-test
  (testing "find-orphaned-lings returns nil when all daemons are healthy"
    (setup-daemon! "healthy-d" :status :active)
    (setup-ling! "ling-1" "healthy-d")
    (is (nil? (autoheal/find-orphaned-lings store)))))

(deftest find-orphaned-lings-no-daemons-test
  (testing "find-orphaned-lings returns nil when no daemons exist"
    (is (nil? (autoheal/find-orphaned-lings store)))))

(deftest find-orphaned-lings-stale-daemon-test
  (testing "find-orphaned-lings detects lings on stale daemons"
    (setup-daemon! "stale-d" :status :stale)
    (setup-ling! "orphan-1" "stale-d" :status :idle)
    (let [orphans (autoheal/find-orphaned-lings store)]
      (is (= 1 (count orphans)))
      (is (= "orphan-1" (:ling-id (first orphans))))
      (is (= "stale-d" (:daemon-id (first orphans))))
      (is (= :stale (:daemon-status (first orphans))))
      (is (= :idle (:ling-status (first orphans)))))))

(deftest find-orphaned-lings-terminated-daemon-test
  (testing "find-orphaned-lings detects lings on terminated daemons"
    (setup-daemon! "dead-d" :status :terminated)
    (setup-ling! "orphan-2" "dead-d" :status :working)
    (let [orphans (autoheal/find-orphaned-lings store)]
      (is (= 1 (count orphans)))
      (is (= :terminated (:daemon-status (first orphans))))
      (is (= :working (:ling-status (first orphans)))))))

(deftest find-orphaned-lings-error-daemon-test
  (testing "find-orphaned-lings detects lings on error daemons"
    (setup-daemon! "error-d" :status :error)
    (setup-ling! "orphan-3" "error-d" :status :blocked)
    (let [orphans (autoheal/find-orphaned-lings store)]
      (is (= 1 (count orphans)))
      (is (= :error (:daemon-status (first orphans))))
      (is (= :blocked (:ling-status (first orphans)))))))

(deftest find-orphaned-lings-multiple-daemons-test
  (testing "find-orphaned-lings finds orphans across multiple dead daemons"
    (setup-daemon! "dead-1" :status :stale)
    (setup-daemon! "dead-2" :status :terminated)
    (setup-daemon! "alive" :status :active)
    (setup-ling! "orphan-a" "dead-1" :status :idle)
    (setup-ling! "orphan-b" "dead-2" :status :working)
    (setup-ling! "healthy-ling" "alive" :status :idle)
    (let [orphans (autoheal/find-orphaned-lings store)
          orphan-ids (set (map :ling-id orphans))]
      (is (= 2 (count orphans)))
      (is (contains? orphan-ids "orphan-a"))
      (is (contains? orphan-ids "orphan-b"))
      (is (not (contains? orphan-ids "healthy-ling"))))))

(deftest find-orphaned-lings-empty-dead-daemon-test
  (testing "find-orphaned-lings ignores dead daemons with no lings"
    (setup-daemon! "dead-empty" :status :stale)
    (is (nil? (autoheal/find-orphaned-lings store)))))

;;; =============================================================================
;;; Classification Tests
;;; =============================================================================

(deftest classify-orphan-idle-test
  (testing "classify-orphan returns :rebind for idle lings"
    (is (= :rebind (autoheal/classify-orphan {:ling-status :idle})))
    (is (= :rebind (autoheal/classify-orphan {:ling-status :initializing})))
    (is (= :rebind (autoheal/classify-orphan {:ling-status :spawning})))
    (is (= :rebind (autoheal/classify-orphan {:ling-status :starting})))))

(deftest classify-orphan-working-test
  (testing "classify-orphan returns :terminate for working/blocked lings"
    (is (= :terminate (autoheal/classify-orphan {:ling-status :working})))
    (is (= :terminate (autoheal/classify-orphan {:ling-status :blocked})))))

(deftest classify-orphan-skip-test
  (testing "classify-orphan returns :skip for already-terminated lings"
    (is (= :skip (autoheal/classify-orphan {:ling-status :error})))
    (is (= :skip (autoheal/classify-orphan {:ling-status :terminated})))))

(deftest classify-orphan-unknown-test
  (testing "classify-orphan returns :terminate for unknown status (conservative)"
    (is (= :terminate (autoheal/classify-orphan {:ling-status :unknown})))))

;;; =============================================================================
;;; Healing Action Tests: Rebind
;;; =============================================================================

(deftest heal-orphan-rebind-success-test
  (testing "heal-orphan! rebinds idle ling to healthy daemon"
    ;; Setup: dead daemon with idle ling, plus healthy daemon
    (setup-daemon! "dead-d" :status :stale)
    (setup-daemon! "healthy-d" :status :active :health-score 90)
    (setup-ling! "idle-orphan" "dead-d" :status :idle)
    (let [orphan {:ling-id "idle-orphan"
                  :daemon-id "dead-d"
                  :daemon-status :stale
                  :ling-status :idle
                  :project-id "test-project"}
          result (autoheal/heal-orphan! store orphan)]
      (is (= :rebind (:action result)))
      (is (true? (:success? result)))
      (is (= "healthy-d" (:new-daemon-id result)))
      ;; Verify ling is now bound to healthy daemon
      (let [new-daemon (proto/get-daemon store "healthy-d")]
        (is (contains? (:emacs-daemon/lings new-daemon) "idle-orphan")))
      ;; Verify ling is unbound from dead daemon
      (let [dead-daemon (proto/get-daemon store "dead-d")]
        (is (not (contains? (or (:emacs-daemon/lings dead-daemon) #{})
                            "idle-orphan")))))))

(deftest heal-orphan-rebind-no-healthy-daemon-test
  (testing "heal-orphan! fails to rebind when no healthy daemons available"
    ;; All daemons are dead
    (setup-daemon! "dead-1" :status :stale)
    (setup-daemon! "dead-2" :status :terminated)
    (setup-ling! "stuck-orphan" "dead-1" :status :idle)
    (let [orphan {:ling-id "stuck-orphan"
                  :daemon-id "dead-1"
                  :daemon-status :stale
                  :ling-status :idle
                  :project-id "test-project"}
          result (autoheal/heal-orphan! store orphan)]
      (is (= :rebind (:action result)))
      (is (false? (:success? result)))
      (is (= :no-healthy-daemon (:reason result))))))

;;; =============================================================================
;;; Healing Action Tests: Terminate
;;; =============================================================================

(deftest heal-orphan-terminate-working-test
  (testing "heal-orphan! terminates working orphan and fails tasks"
    (setup-daemon! "dead-d" :status :stale)
    (setup-ling! "working-orphan" "dead-d" :status :working)
    ;; Create an active task for this ling
    (lings/add-task! "task-1" "working-orphan" {:status :dispatched
                                                :files ["/src/foo.clj"]})
    ;; Claim a file
    (lings/claim-file! "/src/foo.clj" "working-orphan")
    (let [orphan {:ling-id "working-orphan"
                  :daemon-id "dead-d"
                  :daemon-status :stale
                  :ling-status :working
                  :project-id "test-project"}
          result (autoheal/heal-orphan! store orphan)]
      (is (= :terminate (:action result)))
      (is (true? (:success? result)))
      (is (= 1 (:tasks-failed result)))
      ;; Verify task was failed
      (let [task (queries/get-task "task-1")]
        (is (= :error (:task/status task))))
      ;; Verify ling is terminated
      (let [slave (queries/get-slave "working-orphan")]
        (is (= :terminated (:slave/status slave))))
      ;; Verify ling is unbound from dead daemon
      (let [dead-daemon (proto/get-daemon store "dead-d")]
        (is (not (contains? (or (:emacs-daemon/lings dead-daemon) #{})
                            "working-orphan"))))
      ;; Verify claim was released
      (is (nil? (queries/get-claims-for-file "/src/foo.clj"))))))

(deftest heal-orphan-terminate-no-tasks-test
  (testing "heal-orphan! terminates orphan with no active tasks"
    (setup-daemon! "dead-d" :status :error)
    (setup-ling! "blocked-orphan" "dead-d" :status :blocked)
    (let [orphan {:ling-id "blocked-orphan"
                  :daemon-id "dead-d"
                  :daemon-status :error
                  :ling-status :blocked
                  :project-id "test-project"}
          result (autoheal/heal-orphan! store orphan)]
      (is (= :terminate (:action result)))
      (is (true? (:success? result)))
      (is (= 0 (:tasks-failed result))))))

;;; =============================================================================
;;; Healing Action Tests: Skip
;;; =============================================================================

(deftest heal-orphan-skip-terminated-test
  (testing "heal-orphan! skips already-terminated lings but unbinds them"
    (setup-daemon! "dead-d" :status :stale)
    (setup-ling! "dead-ling" "dead-d" :status :terminated)
    (let [orphan {:ling-id "dead-ling"
                  :daemon-id "dead-d"
                  :daemon-status :stale
                  :ling-status :terminated
                  :project-id "test-project"}
          result (autoheal/heal-orphan! store orphan)]
      (is (= :skip (:action result)))
      ;; Verify ling is unbound from dead daemon (cleanup)
      (let [dead-daemon (proto/get-daemon store "dead-d")]
        (is (not (contains? (or (:emacs-daemon/lings dead-daemon) #{})
                            "dead-ling")))))))

;;; =============================================================================
;;; Full Orchestration Tests
;;; =============================================================================

(deftest heal-all-orphans-none-test
  (testing "heal-all-orphans! returns nil when no orphans"
    (setup-daemon! "healthy" :status :active)
    (setup-ling! "good-ling" "healthy")
    (is (nil? (autoheal/heal-all-orphans! store)))))

(deftest heal-all-orphans-mixed-test
  (testing "heal-all-orphans! handles mixed scenarios"
    ;; Setup: 1 healthy daemon, 1 dead daemon with 3 lings in different states
    (setup-daemon! "healthy-d" :status :active :health-score 90)
    (setup-daemon! "dead-d" :status :stale)
    ;; Idle ling — should be rebound
    (setup-ling! "idle-ling" "dead-d" :status :idle)
    ;; Working ling — should be terminated
    (setup-ling! "working-ling" "dead-d" :status :working)
    (lings/add-task! "work-task" "working-ling" {:status :dispatched})
    ;; Already-terminated ling — should be skipped
    (setup-ling! "dead-ling" "dead-d" :status :terminated)
    (let [result (autoheal/heal-all-orphans! store)]
      (is (some? result))
      (is (= 3 (:orphans-found result)))
      ;; At least the idle rebind and the working terminate should succeed
      (is (pos? (:healed result)))
      ;; Check individual results
      (let [by-ling (into {} (map (juxt :ling-id identity) (:results result)))]
        ;; Idle ling should be rebound to healthy daemon
        (is (= :rebind (:action (get by-ling "idle-ling"))))
        (is (true? (:success? (get by-ling "idle-ling"))))
        ;; Working ling should be terminated
        (is (= :terminate (:action (get by-ling "working-ling"))))
        (is (true? (:success? (get by-ling "working-ling"))))
        ;; Dead ling should be skipped
        (is (= :skip (:action (get by-ling "dead-ling"))))))))

(deftest heal-all-orphans-multiple-dead-daemons-test
  (testing "heal-all-orphans! heals across multiple dead daemons"
    (setup-daemon! "healthy-d" :status :active :health-score 90)
    (setup-daemon! "stale-d" :status :stale)
    (setup-daemon! "err-d" :status :error)
    (setup-ling! "ling-a" "stale-d" :status :idle)
    (setup-ling! "ling-b" "err-d" :status :idle)
    (let [result (autoheal/heal-all-orphans! store)]
      (is (= 2 (:orphans-found result)))
      (is (= 2 (:healed result)))
      ;; Both should be rebound to the healthy daemon
      (let [healthy (proto/get-daemon store "healthy-d")
            lings (:emacs-daemon/lings healthy)]
        (is (contains? lings "ling-a"))
        (is (contains? lings "ling-b"))))))

;;; =============================================================================
;;; Orphan Status (Introspection) Tests
;;; =============================================================================

(deftest orphan-status-no-orphans-test
  (testing "orphan-status returns nil when no orphans"
    (setup-daemon! "healthy" :status :active)
    (is (nil? (autoheal/orphan-status store)))))

(deftest orphan-status-with-orphans-test
  (testing "orphan-status shows orphans with classified actions"
    (setup-daemon! "dead" :status :stale)
    (setup-ling! "idle-o" "dead" :status :idle)
    (setup-ling! "working-o" "dead" :status :working)
    (let [status (autoheal/orphan-status store)]
      (is (= 2 (:orphan-count status)))
      (let [actions (set (map :action (:orphans status)))]
        (is (contains? actions :rebind))
        (is (contains? actions :terminate))))))
