(ns hive-mcp.emacs.daemon-integration-test
  "Integration tests for IEmacsDaemon wiring into the system.

   Tests:
   - Daemon store singleton access
   - Spawn handler integration (daemon registration + ling binding)
   - Kill handler integration (ling unbinding)
   - Heartbeat loop lifecycle"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.emacs.daemon :as proto]
            [hive-mcp.emacs.daemon-store :as daemon-store]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.test.stub.daemon-store :as daemon-stub]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(defn- heartbeat-stop-fixture
  "Stop heartbeat loop if running before each test."
  [f]
  (daemon-store/stop-heartbeat-loop!)
  (f))

(use-fixtures :each
  (iso/with-isolations :swarm-ds)
  daemon-stub/singleton-store-fixture
  heartbeat-stop-fixture)

;;; =============================================================================
;;; Singleton Tests
;;; =============================================================================

(deftest singleton-store-test
  (testing "get-store returns consistent instance"
    (let [store1 (daemon-store/get-store)
          store2 (daemon-store/get-store)]
      (is (identical? store1 store2) "Should return same instance"))))

(deftest default-daemon-id-test
  (testing "default-daemon-id returns env var or 'server'"
    (let [id (daemon-store/default-daemon-id)]
      (is (string? id))
      (is (not (empty? id))))))

;;; =============================================================================
;;; Convenience API Tests
;;; =============================================================================

(deftest convenience-api-test
  (testing "Convenience functions delegate to store correctly"
    ;; Register
    (daemon-store/register! "test-daemon" {:socket-name "test-socket"})
    (let [d (daemon-store/get-daemon "test-daemon")]
      (is (some? d))
      (is (= "test-daemon" (:emacs-daemon/id d)))
      (is (= "test-socket" (:emacs-daemon/socket-name d)))
      (is (= :active (:emacs-daemon/status d))))

    ;; Heartbeat
    (Thread/sleep 10)
    (daemon-store/heartbeat! "test-daemon")
    (let [d (daemon-store/get-daemon "test-daemon")]
      (is (= :active (:emacs-daemon/status d))))

    ;; Mark error
    (daemon-store/mark-error! "test-daemon" "test error")
    (let [d (daemon-store/get-daemon "test-daemon")]
      (is (= :error (:emacs-daemon/status d)))
      (is (= "test error" (:emacs-daemon/error-message d))))

    ;; Heartbeat reactivates
    (daemon-store/heartbeat! "test-daemon")
    (let [d (daemon-store/get-daemon "test-daemon")]
      (is (= :active (:emacs-daemon/status d))))))

;;; =============================================================================
;;; Ling Binding Tests
;;; =============================================================================

(deftest ling-binding-integration-test
  (testing "Ling binding/unbinding works through convenience API"
    ;; Setup daemon
    (daemon-store/ensure-default-daemon!)
    (let [daemon-id (daemon-store/default-daemon-id)]
      ;; Bind lings
      (daemon-store/bind-ling! daemon-id "ling-001")
      (daemon-store/bind-ling! daemon-id "ling-002")

      ;; Verify binding
      (let [d (daemon-store/get-daemon daemon-id)]
        (is (= #{"ling-001" "ling-002"} (:emacs-daemon/lings d))))

      ;; Lookup by ling
      (let [found (daemon-store/get-daemon-for-ling "ling-001")]
        (is (some? found))
        (is (= daemon-id (:emacs-daemon/id found))))

      ;; Unbind
      (daemon-store/unbind-ling! daemon-id "ling-001")
      (let [d (daemon-store/get-daemon daemon-id)]
        (is (= #{"ling-002"} (:emacs-daemon/lings d))))

      ;; Verify ling-001 is unbound
      (is (nil? (daemon-store/get-daemon-for-ling "ling-001"))))))

;;; =============================================================================
;;; Heartbeat Loop Tests
;;; =============================================================================

(deftest heartbeat-loop-lifecycle-test
  (testing "Heartbeat loop start/stop"
    ;; Not running initially
    (let [status (daemon-store/heartbeat-status)]
      (is (false? (:running? status))))

    ;; Start
    (daemon-store/start-heartbeat-loop!)
    (Thread/sleep 100) ; Give thread time to start
    (let [status (daemon-store/heartbeat-status)]
      (is (true? (:running? status))))

    ;; Idempotent start
    (daemon-store/start-heartbeat-loop!)
    (let [status (daemon-store/heartbeat-status)]
      (is (true? (:running? status))))

    ;; Stop
    (daemon-store/stop-heartbeat-loop!)
    (Thread/sleep 100) ; Give thread time to stop
    (let [status (daemon-store/heartbeat-status)]
      (is (false? (:running? status))))

    ;; Idempotent stop
    (daemon-store/stop-heartbeat-loop!)
    (let [status (daemon-store/heartbeat-status)]
      (is (false? (:running? status))))))

;;; =============================================================================
;;; Spawn/Kill Handler Simulation Tests
;;; =============================================================================

(deftest spawn-kill-simulation-test
  (testing "Simulated spawn/kill flow with daemon binding"
    (let [daemon-id (daemon-store/default-daemon-id)]
      ;; Simulate startup - ensure daemon registered
      (daemon-store/ensure-default-daemon!)
      (is (some? (daemon-store/get-daemon daemon-id)))

      ;; Simulate spawn - bind ling
      (daemon-store/bind-ling! daemon-id "spawned-ling-1")
      (daemon-store/bind-ling! daemon-id "spawned-ling-2")

      (let [d (daemon-store/get-daemon daemon-id)]
        (is (= #{"spawned-ling-1" "spawned-ling-2"} (:emacs-daemon/lings d))))

      ;; Simulate kill - unbind ling
      (daemon-store/unbind-ling! daemon-id "spawned-ling-1")

      (let [d (daemon-store/get-daemon daemon-id)]
        (is (= #{"spawned-ling-2"} (:emacs-daemon/lings d))))

      ;; Kill remaining
      (daemon-store/unbind-ling! daemon-id "spawned-ling-2")

      (let [d (daemon-store/get-daemon daemon-id)]
        ;; Empty set or nil is acceptable
        (is (or (nil? (:emacs-daemon/lings d))
                (empty? (:emacs-daemon/lings d))))))))

;;; =============================================================================
;;; Query Tests
;;; =============================================================================

(deftest query-tests
  (testing "Query functions work correctly"
    ;; Setup multiple daemons
    (daemon-store/register! "daemon-a" {:socket-name "sa"})
    (daemon-store/register! "daemon-b" {:socket-name "sb"})
    (daemon-store/register! "daemon-c" {:socket-name "sc"})
    (daemon-store/mark-error! "daemon-c" "crash")

    ;; get-all-daemons
    (let [all (daemon-store/get-all-daemons)]
      (is (= 3 (count all))))

    ;; get-daemons-by-status
    (let [active (daemon-store/get-daemons-by-status :active)
          errored (daemon-store/get-daemons-by-status :error)]
      (is (= 2 (count active)))
      (is (= 1 (count errored))))))
