(ns hive-mcp.knowledge-graph.connection-writer-test
  "Tests for the write-coalescing queue lifecycle and integration.

   Uses DataScript backend for speed. Each test gets a fresh store
   and a stopped writer to avoid cross-test interference.

   Determinism note: every place that previously used `(Thread/sleep N)`
   to wait on the async coalescing queue now calls `conn/flush-pending!`,
   the sentinel that busy-waits until in-flight=0 (or a 5s deadline). This
   eliminates the test flake source documented in kanban
   20260404134936-1b481a86 — see also the docstring on
   `hive-mcp.knowledge-graph.connection/flush-pending!`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as string]
            [clojure.core.async :as async]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn writer-fixture
  "Fixture that ensures writer is stopped before and after each test.
   Combined with datascript-fixture for a fresh store."
  [f]
  (fixtures/datascript-fixture
   (fn []
     (conn/stop-writer!)
     (try
       (f)
       (finally
         (conn/stop-writer!))))))

(use-fixtures :each writer-fixture)

;; =============================================================================
;; Lifecycle Tests
;; =============================================================================

(deftest stop-writer-idempotent-test
  (testing "calling stop-writer! twice does not throw"
    (conn/stop-writer!)
    (conn/stop-writer!)
    (is (not (:running? (conn/writer-stats))))))

(deftest start-stop-roundtrip-test
  (testing "writer can be started, stopped, and restarted (Bug #1)"
    ;; First start via transact!
    (conn/transact! [{:kg-edge/id "round-1"
                      :kg-edge/from "a"
                      :kg-edge/to "b"
                      :kg-edge/relation :implements
                      :kg-edge/confidence 1.0}])
    (is (:running? (conn/writer-stats)))

    ;; Stop
    (conn/stop-writer!)
    (is (not (:running? (conn/writer-stats))))

    ;; Restart — should work on fresh channels
    (conn/transact! [{:kg-edge/id "round-2"
                      :kg-edge/from "c"
                      :kg-edge/to "d"
                      :kg-edge/relation :implements
                      :kg-edge/confidence 1.0}])
    (is (:running? (conn/writer-stats)))

    ;; Drain the coalescing queue deterministically (replaces Thread/sleep 100).
    (conn/flush-pending!)

    ;; Both writes should have landed
    (let [results (conn/query '[:find ?id
                                :where [?e :kg-edge/id ?id]])]
      (is (contains? (set (map first results)) "round-2")))))

(deftest writer-stats-observability-test
  (testing "writer-stats returns metrics and running state (Bug #5)"
    (let [stats-before (conn/writer-stats)]
      (is (contains? stats-before :running?))
      (is (contains? stats-before :batches-flushed))
      (is (contains? stats-before :items-written))
      (is (contains? stats-before :items-dropped))
      (is (contains? stats-before :largest-batch))

      ;; Trigger a write
      (conn/transact! [{:kg-edge/id "stats-test"
                        :kg-edge/from "a"
                        :kg-edge/to "b"
                        :kg-edge/relation :implements
                        :kg-edge/confidence 1.0}])
      ;; Drain deterministically (replaces Thread/sleep 100).
      (conn/flush-pending!)

      (let [stats-after (conn/writer-stats)]
        (is (:running? stats-after))
        (is (pos? (:batches-flushed stats-after)))
        (is (pos? (:items-written stats-after)))))))

;; =============================================================================
;; Data Flow Tests
;; =============================================================================

(deftest transact-via-queue-reaches-db-test
  (testing "data sent through queue ends up in the database"
    (conn/transact! [{:kg-edge/id "queue-test"
                      :kg-edge/from "x"
                      :kg-edge/to "y"
                      :kg-edge/relation :implements
                      :kg-edge/confidence 0.9}])
    ;; Drain deterministically (replaces Thread/sleep 100).
    (conn/flush-pending!)

    (let [results (conn/query '[:find ?id
                                :where [?e :kg-edge/id ?id]])]
      (is (= #{["queue-test"]} (set results))))))

(deftest transact-sync-bypasses-queue-test
  (testing "transact-sync! bypasses queue and returns immediately"
    (let [result (conn/transact-sync!
                  [{:kg-edge/id "sync-test"
                    :kg-edge/from "a"
                    :kg-edge/to "b"
                    :kg-edge/relation :implements
                    :kg-edge/confidence 1.0}])]
      ;; No sleep needed — sync is immediate
      (is (some? result))
      (let [results (conn/query '[:find ?id
                                  :where [?e :kg-edge/id ?id]])]
        (is (= #{["sync-test"]} (set results)))))))

(deftest coalescing-batches-multiple-transacts-test
  (testing "20 rapid writes coalesce into fewer than 20 batches"
    (let [before-batches (:batches-flushed (conn/writer-stats))]
      (dotimes [i 20]
        (conn/transact! [{:kg-edge/id (str "coalesce-" i)
                          :kg-edge/from (str "from-" i)
                          :kg-edge/to (str "to-" i)
                          :kg-edge/relation :implements
                          :kg-edge/confidence 0.5}]))
      ;; Drain deterministically (replaces Thread/sleep 200).
      (conn/flush-pending!)

      (let [after-batches (:batches-flushed (conn/writer-stats))
            batch-count (- after-batches before-batches)]
        (is (< batch-count 20)
            (str "Expected coalescing: " batch-count " batches for 20 writes"))
        ;; Verify all data landed
        (let [results (conn/query '[:find ?id
                                    :where [?e :kg-edge/id ?id]])]
          (is (= 20 (count (filter #(string/starts-with? (first %) "coalesce-")
                                   results)))))))))

(deftest with-tx-batch-bypasses-queue-test
  (testing "with-tx-batch goes through synchronous path"
    (conn/with-tx-batch
      (conn/transact! [{:kg-edge/id "batch-1"
                        :kg-edge/from "a"
                        :kg-edge/to "b"
                        :kg-edge/relation :implements
                        :kg-edge/confidence 1.0}])
      (conn/transact! [{:kg-edge/id "batch-2"
                        :kg-edge/from "c"
                        :kg-edge/to "d"
                        :kg-edge/relation :implements
                        :kg-edge/confidence 1.0}]))
    ;; No sleep needed — with-tx-batch is synchronous
    (let [results (conn/query '[:find ?id
                                :where [?e :kg-edge/id ?id]])]
      (is (= #{["batch-1"] ["batch-2"]} (set results))))))

;; =============================================================================
;; Normalization Tests
;; =============================================================================

(deftest vector-datum-normalization-test
  (testing "[:db/add ...] through queue normalizes correctly (Bug #4)"
    (conn/transact-sync! [{:kg-edge/id "norm-target"
                           :kg-edge/from "a"
                           :kg-edge/to "b"
                           :kg-edge/relation :implements
                           :kg-edge/confidence 0.5}])
    (let [eid (ffirst (conn/query '[:find ?e
                                    :where [?e :kg-edge/id "norm-target"]]))]
      ;; Send a vector datum through the queue
      (conn/transact! [:db/add eid :kg-edge/confidence 0.99])
      ;; Drain deterministically (replaces Thread/sleep 100).
      (conn/flush-pending!)

      (let [updated (conn/query '[:find ?c
                                  :where
                                  [?e :kg-edge/id "norm-target"]
                                  [?e :kg-edge/confidence ?c]])]
        (is (= #{[0.99]} (set updated)))))))

;; =============================================================================
;; Concurrency Tests
;; =============================================================================

(deftest concurrent-ensure-writer-test
  (testing "10 threads calling transact! concurrently — all writes survive (Bug #2)"
    (let [n-threads 10
          writes-per-thread 5
          latch (CountDownLatch. n-threads)
          errors (atom [])
          done   (CountDownLatch. n-threads)]
      ;; Launch threads
      (dotimes [t n-threads]
        (.start
         (Thread.
          (fn []
            (try
              (.await latch)
              (dotimes [i writes-per-thread]
                (conn/transact! [{:kg-edge/id (str "concurrent-" t "-" i)
                                  :kg-edge/from (str "from-" t)
                                  :kg-edge/to (str "to-" t "-" i)
                                  :kg-edge/relation :implements
                                  :kg-edge/confidence 0.5}]))
              (catch Exception e
                (swap! errors conj (.getMessage e)))
              (finally
                (.countDown done))))))
        (.countDown latch))

      ;; Wait for all threads to finish ENQUEUEING (deterministic, no sleep).
      (.await done 10 TimeUnit/SECONDS)
      ;; Then drain the coalescing queue (replaces Thread/sleep 500).
      (conn/flush-pending!)

      (is (empty? @errors) (str "Errors during concurrent writes: " @errors))

      ;; Verify all writes landed (some may have gone sync fallback)
      (let [results (conn/query '[:find ?id
                                  :where [?e :kg-edge/id ?id]])
            concurrent-ids (filter #(string/starts-with? (first %) "concurrent-")
                                   results)]
        (is (= (* n-threads writes-per-thread) (count concurrent-ids))
            (str "Expected " (* n-threads writes-per-thread)
                 " writes, got " (count concurrent-ids)))))))
