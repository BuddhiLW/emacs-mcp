(ns hive-mcp.emacs.daemon-ds-test
  "Tests for DataScript IEmacsDaemon implementation.

   Covers:
   - Daemon registration and retrieval
   - Heartbeat updates and reactivation
   - Error marking with cumulative count
   - Status transitions (active -> stale -> error -> terminated)
   - Ling binding/unbinding
   - Stale daemon cleanup
   - Full lifecycle integration"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.emacs.daemon :as proto]
            [hive-mcp.emacs.daemon-ds :as daemon-ds]
            [hive-mcp.swarm.datascript.connection :as conn]
            [datascript.core :as d]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.test.stub.daemon-store :as daemon-stub]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(def ^:private ^:dynamic store
  "Daemon store for the test, bound per test by daemon-store-fixture so it
   shares the isolated swarm conn."
  nil)

(use-fixtures :each
  (iso/with-isolations :swarm-ds)
  (daemon-stub/daemon-store-fixture #'store))

;;; =============================================================================
;;; Registration Tests
;;; =============================================================================

(deftest register-daemon-basic-test
  (testing "register! creates daemon with required fields"
    (proto/register! store "emacs-main" {:socket-name "server"
                                         :pid 12345})
    (let [d (proto/get-daemon store "emacs-main")]
      (is (some? d) "Daemon should exist")
      (is (= "emacs-main" (:emacs-daemon/id d)))
      (is (= "server" (:emacs-daemon/socket-name d)))
      (is (= 12345 (:emacs-daemon/pid d)))
      (is (= :active (:emacs-daemon/status d)) "Initial status should be :active")
      (is (= 0 (:emacs-daemon/error-count d)) "Error count should start at 0")
      (is (some? (:emacs-daemon/started-at d)) "Started timestamp should be set")
      (is (some? (:emacs-daemon/heartbeat-at d)) "Heartbeat timestamp should be set"))))

(deftest register-daemon-defaults-test
  (testing "register! defaults socket-name to daemon-id"
    (proto/register! store "my-daemon" {})
    (let [d (proto/get-daemon store "my-daemon")]
      (is (= "my-daemon" (:emacs-daemon/socket-name d))))))

(deftest register-daemon-emacsclient-test
  (testing "register! stores emacsclient path"
    (proto/register! store "custom-ec" {:emacsclient "/usr/local/bin/emacsclient"})
    (let [d (proto/get-daemon store "custom-ec")]
      (is (= "/usr/local/bin/emacsclient" (:emacs-daemon/emacsclient d))))))

(deftest register-daemon-upsert-test
  (testing "register! upserts on same ID (unique identity)"
    (proto/register! store "upsert-test" {:socket-name "v1"})
    (is (= "v1" (:emacs-daemon/socket-name (proto/get-daemon store "upsert-test"))))

    (proto/register! store "upsert-test" {:socket-name "v2"})
    (let [d (proto/get-daemon store "upsert-test")]
      (is (= "v2" (:emacs-daemon/socket-name d)))
      (is (= :active (:emacs-daemon/status d))))))

;;; =============================================================================
;;; Query Tests
;;; =============================================================================

(deftest get-daemon-not-found-test
  (testing "get-daemon returns nil for non-existent ID"
    (is (nil? (proto/get-daemon store "non-existent")))))

(deftest get-all-daemons-test
  (testing "get-all-daemons returns all registered daemons"
    (proto/register! store "d1" {:socket-name "s1"})
    (proto/register! store "d2" {:socket-name "s2"})
    (proto/register! store "d3" {:socket-name "s3"})
    (let [all (proto/get-all-daemons store)]
      (is (= 3 (count all)))
      (is (= #{"d1" "d2" "d3"}
             (set (map :emacs-daemon/id all)))))))

(deftest get-daemons-by-status-test
  (testing "get-daemons-by-status filters correctly"
    (proto/register! store "active-1" {})
    (proto/register! store "active-2" {})
    (proto/register! store "error-1" {})
    (proto/mark-error! store "error-1" "segfault")

    (let [active (proto/get-daemons-by-status store :active)
          errored (proto/get-daemons-by-status store :error)]
      (is (= 2 (count active)))
      (is (= #{"active-1" "active-2"} (set (map :emacs-daemon/id active))))
      (is (= 1 (count errored)))
      (is (= "error-1" (:emacs-daemon/id (first errored)))))))

;;; =============================================================================
;;; Heartbeat Tests
;;; =============================================================================

(deftest heartbeat-updates-timestamp-test
  (testing "heartbeat! updates timestamp"
    (proto/register! store "hb-test" {})
    (let [before (proto/get-daemon store "hb-test")
          _ (Thread/sleep 10)
          _ (proto/heartbeat! store "hb-test")
          after (proto/get-daemon store "hb-test")]
      (is (.after (:emacs-daemon/heartbeat-at after)
                  (:emacs-daemon/heartbeat-at before))
          "Heartbeat timestamp should be updated"))))

(deftest heartbeat-reactivates-stale-test
  (testing "heartbeat! reactivates stale daemon"
    (proto/register! store "stale-react" {})
    ;; Manually set status to stale
    (let [c (conn/ensure-conn)
          db @c
          eid (:db/id (d/entity db [:emacs-daemon/id "stale-react"]))]
      (d/transact! c [{:db/id eid :emacs-daemon/status :stale}]))
    (is (= :stale (:emacs-daemon/status (proto/get-daemon store "stale-react"))))

    (proto/heartbeat! store "stale-react")
    (is (= :active (:emacs-daemon/status (proto/get-daemon store "stale-react")))
        "Heartbeat should reactivate stale daemon")))

(deftest heartbeat-reactivates-error-test
  (testing "heartbeat! reactivates errored daemon"
    (proto/register! store "err-react" {})
    (proto/mark-error! store "err-react" "timeout")
    (is (= :error (:emacs-daemon/status (proto/get-daemon store "err-react"))))

    (proto/heartbeat! store "err-react")
    (is (= :active (:emacs-daemon/status (proto/get-daemon store "err-react")))
        "Heartbeat should reactivate errored daemon")))

(deftest heartbeat-nonexistent-test
  (testing "heartbeat! returns nil for non-existent daemon"
    (is (nil? (proto/heartbeat! store "non-existent")))))

;;; =============================================================================
;;; Error Tests
;;; =============================================================================

(deftest mark-error-basic-test
  (testing "mark-error! sets status and message"
    (proto/register! store "err-test" {})
    (proto/mark-error! store "err-test" "Connection refused")
    (let [d (proto/get-daemon store "err-test")]
      (is (= :error (:emacs-daemon/status d)))
      (is (= "Connection refused" (:emacs-daemon/error-message d)))
      (is (= 1 (:emacs-daemon/error-count d))))))

(deftest mark-error-cumulative-count-test
  (testing "mark-error! increments error count cumulatively"
    (proto/register! store "err-count" {})
    (proto/mark-error! store "err-count" "error 1")
    (proto/mark-error! store "err-count" "error 2")
    (proto/mark-error! store "err-count" "error 3")
    (let [d (proto/get-daemon store "err-count")]
      (is (= 3 (:emacs-daemon/error-count d)))
      (is (= "error 3" (:emacs-daemon/error-message d))
          "Should keep latest error message"))))

(deftest mark-error-nonexistent-test
  (testing "mark-error! returns nil for non-existent daemon"
    (is (nil? (proto/mark-error! store "non-existent" "err")))))

;;; =============================================================================
;;; Termination Tests
;;; =============================================================================

(deftest mark-terminated-test
  (testing "mark-terminated! sets status to :terminated"
    (proto/register! store "term-test" {})
    (proto/mark-terminated! store "term-test")
    (is (= :terminated (:emacs-daemon/status (proto/get-daemon store "term-test"))))))

(deftest mark-terminated-nonexistent-test
  (testing "mark-terminated! returns nil for non-existent daemon"
    (is (nil? (proto/mark-terminated! store "non-existent")))))

;;; =============================================================================
;;; Ling Binding Tests
;;; =============================================================================

(deftest bind-ling-basic-test
  (testing "bind-ling! adds ling to daemon's ling set"
    (proto/register! store "bind-test" {})
    (proto/bind-ling! store "bind-test" "ling-1")
    (proto/bind-ling! store "bind-test" "ling-2")
    (let [d (proto/get-daemon store "bind-test")]
      (is (= #{"ling-1" "ling-2"} (:emacs-daemon/lings d))))))

(deftest bind-ling-idempotent-test
  (testing "bind-ling! is idempotent (binding same ling twice)"
    (proto/register! store "idempotent-bind" {})
    (proto/bind-ling! store "idempotent-bind" "ling-x")
    (proto/bind-ling! store "idempotent-bind" "ling-x")
    (let [d (proto/get-daemon store "idempotent-bind")]
      (is (= #{"ling-x"} (:emacs-daemon/lings d))))))

(deftest unbind-ling-test
  (testing "unbind-ling! removes ling from daemon"
    (proto/register! store "unbind-test" {})
    (proto/bind-ling! store "unbind-test" "ling-a")
    (proto/bind-ling! store "unbind-test" "ling-b")
    (proto/unbind-ling! store "unbind-test" "ling-a")
    (let [d (proto/get-daemon store "unbind-test")]
      (is (= #{"ling-b"} (:emacs-daemon/lings d))))))

(deftest unbind-ling-nonexistent-daemon-test
  (testing "unbind-ling! returns nil for non-existent daemon"
    (is (nil? (proto/unbind-ling! store "non-existent" "ling-1")))))

(deftest get-daemon-for-ling-test
  (testing "get-daemon-for-ling finds the daemon a ling is bound to"
    (proto/register! store "daemon-a" {:socket-name "sa"})
    (proto/register! store "daemon-b" {:socket-name "sb"})
    (proto/bind-ling! store "daemon-a" "ling-100")
    (proto/bind-ling! store "daemon-b" "ling-200")
    (let [result (proto/get-daemon-for-ling store "ling-100")]
      (is (some? result))
      (is (= "daemon-a" (:emacs-daemon/id result))))))

(deftest get-daemon-for-ling-not-bound-test
  (testing "get-daemon-for-ling returns nil for unbound ling"
    (is (nil? (proto/get-daemon-for-ling store "unbound-ling")))))

;;; =============================================================================
;;; Cleanup Tests
;;; =============================================================================

(deftest cleanup-stale-finds-old-daemons-test
  (testing "cleanup-stale! marks old heartbeats as stale"
    (proto/register! store "old-daemon" {})
    ;; Manually set heartbeat to 5 minutes ago
    (let [c (conn/ensure-conn)
          db @c
          eid (:db/id (d/entity db [:emacs-daemon/id "old-daemon"]))
          old-time (java.util.Date. (- (System/currentTimeMillis) (* 5 60 1000)))]
      (d/transact! c [{:db/id eid :emacs-daemon/heartbeat-at old-time}]))

    ;; Register fresh daemon
    (proto/register! store "fresh-daemon" {})

    ;; Cleanup with default 2-minute threshold
    (let [stale-ids (proto/cleanup-stale! store)]
      (is (= ["old-daemon"] (vec stale-ids)))
      (is (= :stale (:emacs-daemon/status (proto/get-daemon store "old-daemon"))))
      (is (= :active (:emacs-daemon/status (proto/get-daemon store "fresh-daemon")))))))

(deftest cleanup-stale-custom-threshold-test
  (testing "cleanup-stale! respects custom threshold"
    (proto/register! store "threshold-test" {})
    ;; Set heartbeat to 1 second ago
    (let [c (conn/ensure-conn)
          db @c
          eid (:db/id (d/entity db [:emacs-daemon/id "threshold-test"]))
          old-time (java.util.Date. (- (System/currentTimeMillis) 1000))]
      (d/transact! c [{:db/id eid :emacs-daemon/heartbeat-at old-time}]))

    (let [stale-ids (proto/cleanup-stale! store {:threshold-ms 500})]
      (is (= ["threshold-test"] (vec stale-ids))))))

(deftest cleanup-stale-skips-non-active-test
  (testing "cleanup-stale! skips already stale/terminated/error daemons"
    (proto/register! store "already-stale" {})
    (proto/register! store "already-terminated" {})
    (proto/register! store "already-error" {})

    ;; Set old heartbeats and non-active statuses
    (let [c (conn/ensure-conn)
          db @c
          old-time (java.util.Date. (- (System/currentTimeMillis) (* 5 60 1000)))]
      (doseq [id ["already-stale" "already-terminated" "already-error"]]
        (let [eid (:db/id (d/entity db [:emacs-daemon/id id]))]
          (d/transact! c [{:db/id eid :emacs-daemon/heartbeat-at old-time}]))))

    ;; Mark statuses
    (proto/mark-terminated! store "already-terminated")
    (proto/mark-error! store "already-error" "some error")
    ;; Manually mark stale
    (let [c (conn/ensure-conn)
          db @c
          eid (:db/id (d/entity db [:emacs-daemon/id "already-stale"]))]
      (d/transact! c [{:db/id eid :emacs-daemon/status :stale}]))

    ;; Cleanup should find nothing (all are non-active)
    (let [stale-ids (proto/cleanup-stale! store)]
      (is (nil? stale-ids) "Should not mark already non-active daemons"))))

;;; =============================================================================
;;; Integration Tests
;;; =============================================================================

(deftest daemon-lifecycle-integration-test
  (testing "Full daemon lifecycle: register -> heartbeat -> error -> heartbeat -> terminate"
    ;; 1. Register
    (proto/register! store "lifecycle" {:socket-name "server" :pid 9999})
    (is (= :active (:emacs-daemon/status (proto/get-daemon store "lifecycle"))))

    ;; 2. Heartbeat
    (Thread/sleep 10)
    (proto/heartbeat! store "lifecycle")
    (is (= :active (:emacs-daemon/status (proto/get-daemon store "lifecycle"))))

    ;; 3. Error
    (proto/mark-error! store "lifecycle" "connection lost")
    (let [d (proto/get-daemon store "lifecycle")]
      (is (= :error (:emacs-daemon/status d)))
      (is (= 1 (:emacs-daemon/error-count d))))

    ;; 4. Recovery via heartbeat
    (proto/heartbeat! store "lifecycle")
    (is (= :active (:emacs-daemon/status (proto/get-daemon store "lifecycle"))))

    ;; 5. Bind lings
    (proto/bind-ling! store "lifecycle" "ling-alpha")
    (proto/bind-ling! store "lifecycle" "ling-beta")
    (is (= #{"ling-alpha" "ling-beta"}
           (:emacs-daemon/lings (proto/get-daemon store "lifecycle"))))

    ;; 6. Verify ling lookup
    (is (= "lifecycle"
           (:emacs-daemon/id (proto/get-daemon-for-ling store "ling-alpha"))))

    ;; 7. Unbind one ling
    (proto/unbind-ling! store "lifecycle" "ling-alpha")
    (is (= #{"ling-beta"}
           (:emacs-daemon/lings (proto/get-daemon store "lifecycle"))))

    ;; 8. Graceful termination
    (proto/mark-terminated! store "lifecycle")
    (is (= :terminated (:emacs-daemon/status (proto/get-daemon store "lifecycle"))))))

(deftest multi-daemon-test
  (testing "Multiple daemons with different statuses and bound lings"
    (proto/register! store "daemon-1" {:socket-name "s1"})
    (proto/register! store "daemon-2" {:socket-name "s2"})
    (proto/register! store "daemon-3" {:socket-name "s3"})

    (proto/bind-ling! store "daemon-1" "ling-a")
    (proto/bind-ling! store "daemon-2" "ling-b")
    (proto/mark-error! store "daemon-3" "crash")

    ;; Query by status
    (let [active (proto/get-daemons-by-status store :active)
          errored (proto/get-daemons-by-status store :error)]
      (is (= 2 (count active)))
      (is (= 1 (count errored))))

    ;; Ling lookups
    (is (= "daemon-1" (:emacs-daemon/id (proto/get-daemon-for-ling store "ling-a"))))
    (is (= "daemon-2" (:emacs-daemon/id (proto/get-daemon-for-ling store "ling-b"))))
    (is (nil? (proto/get-daemon-for-ling store "ling-c")))))
