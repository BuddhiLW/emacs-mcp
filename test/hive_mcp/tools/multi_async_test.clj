(ns hive-mcp.tools.multi-async-test
  "Tests for async batch dispatch: dispatch, collect (running/completed/expired/failed),
   cancel, list, concurrent batches, GC."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.tools.multi-async :as async]
            [hive-mcp.channel.context-store :as ctx]
            [hive-mcp.dns.result :as result]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean-fixture [f]
  (async/reset-all!)
  (ctx/reset-all!)
  (try (f)
       (finally
         (async/reset-all!)
         (ctx/reset-all!))))

(use-fixtures :each clean-fixture)

;; =============================================================================
;; Mock run-multi implementations
;; =============================================================================

(defn mock-run-multi
  "Instant success mock matching run-multi return shape."
  [ops & {:keys [dry-run]}]
  (cond-> {:success true
           :summary {:total (count ops) :success (count ops) :failed 0 :waves 1}
           :waves   {1 {:ops     (mapv #(select-keys % [:id :tool :command]) ops)
                        :results (mapv (fn [op] {:id (:id op "?") :success true}) ops)}}}
    dry-run (assoc :dry-run true)))

(defn slow-mock-run-multi
  "Slow mock — takes 500ms, used to test running/cancel."
  [ops & {:keys [dry-run]}]
  (Thread/sleep 500)
  (mock-run-multi ops :dry-run dry-run))

(defn failing-mock-run-multi
  "Mock that throws an exception."
  [ops & _]
  (throw (ex-info "Simulated batch failure" {:ops (count ops)})))

;; Helper macro to bind resolve-run-multi to return a specific mock fn
(defmacro with-mock [mock-fn & body]
  `(with-redefs [hive-mcp.tools.multi-async/resolve-run-multi (fn [] ~mock-fn)]
     ~@body))

;; =============================================================================
;; Dispatch Tests
;; =============================================================================

(deftest test-dispatch-returns-immediately
  (testing "run-multi-async returns a batch descriptor immediately (before future completes)"
    (with-mock slow-mock-run-multi
      (let [ops    [{:id "op-1" :tool "memory" :command "add" :content "hello"}]
            result (async/run-multi-async ops)]
        (is (string? (:batch-id result))
            "should return a string batch-id")
        (is (str/starts-with? (:batch-id result) "batch-")
            "batch-id should start with 'batch-'")
        (is (= "running" (:status result))
            "status should be running immediately")
        (is (= 1 (:ops result))
            "ops count should match input")
        (is (number? (:created-at result))
            "should include created-at timestamp")))))

(deftest test-dispatch-multiple-ops
  (testing "dispatch with multiple ops reports correct count"
    (with-mock mock-run-multi
      (let [ops [{:id "a" :tool "memory" :command "add"}
                 {:id "b" :tool "kg"     :command "stats"}
                 {:id "c" :tool "kanban" :command "list"}]
            r   (async/run-multi-async ops)]
        (is (= 3 (:ops r)))))))

(deftest test-dispatch-engine-unavailable
  (testing "returns Result error when run-multi cannot be resolved"
    (with-redefs [hive-mcp.tools.multi-async/resolve-run-multi (fn [] nil)]
      (let [r (async/run-multi-async [{:id "op-1" :tool "memory" :command "add"}])]
        (is (result/err? r)
            "should return an error Result")
        (is (= :multi-async/engine-unavailable (:error r))
            "error category should be :multi-async/engine-unavailable")))))

(deftest test-dispatch-with-dry-run
  (testing "dry-run option is forwarded to run-multi"
    (let [dry-run-called? (atom false)]
      (with-mock (fn [ops & {:keys [dry-run]}]
                   (when dry-run (reset! dry-run-called? true))
                   (mock-run-multi ops :dry-run dry-run))
        (let [{:keys [batch-id]} (async/run-multi-async
                                  [{:id "op-1" :tool "memory" :command "add"}]
                                  :dry-run true)]
          (async/await-async-batch batch-id)
          (is (true? @dry-run-called?)
              "dry-run flag should be forwarded to run-multi"))))))

;; =============================================================================
;; Collect Tests: Running
;; =============================================================================

(deftest test-collect-running
  (testing "collect returns running status before future completes"
    (with-mock slow-mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}])
            r (async/collect-async-result batch-id)]
        (is (= "running" (:status r)))
        (is (= batch-id (:batch-id r)))
        (is (= 1 (:ops r)))
        (is (number? (:created-at r)))
        (is (number? (:elapsed-ms r))
            "running status should include elapsed-ms")))))

;; =============================================================================
;; Collect Tests: Completed
;; =============================================================================

(deftest test-collect-completed
  (testing "collect returns completed status with full results after future finishes"
    (with-mock mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}])
            r (async/await-async-batch batch-id)]
        (is (= "completed" (:status r))
            "status should be completed")
        (is (= batch-id (:batch-id r)))
        (is (map? (:results r))
            "results should contain the run-multi output map")
        (is (true? (:success (:results r)))
            "run-multi result should show success")))))

(deftest test-collect-completed-results-shape
  (testing "completed results preserve run-multi output structure"
    (with-mock mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "a" :tool "memory" :command "add"}
                                 {:id "b" :tool "kg"     :command "stats"}])
            {:keys [results]}  (async/await-async-batch batch-id)]
        (is (contains? results :summary)
            "results should contain :summary")
        (is (= 2 (get-in results [:summary :total]))
            "summary total should match ops count")
        (is (contains? results :waves)
            "results should contain :waves")))))

;; =============================================================================
;; Collect Tests: Failed
;; =============================================================================

(deftest test-collect-failed
  (testing "collect returns failed status when future throws an exception"
    (with-mock failing-mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}])
            r (async/await-async-batch batch-id)]
        (is (= "failed" (:status r)))
        (is (= batch-id (:batch-id r)))
        (is (string? (:error r))
            "should include error message")
        (is (str/includes? (:error r) "Simulated")
            "error should contain exception message")))))

;; =============================================================================
;; Collect Tests: Expired
;; =============================================================================

(deftest test-collect-expired
  (testing "collect returns expired when context-store TTL exceeded"
    (with-mock mock-run-multi
      (let [ttl-ms 50
            {:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}]
                                :ttl-ms ttl-ms)]
        ;; Completion is awaited through the batch future; only the TTL itself
        ;; is wall-clock, and it is the subject of this test.
        (is (= "completed" (:status (async/await-async-batch batch-id))))
        (Thread/sleep (* 2 ttl-ms))
        (let [r (async/collect-async-result batch-id)]
          (is (= "expired" (:status r))
              "status should be expired after TTL")
          (is (= batch-id (:batch-id r)))
          (is (string? (:message r))
              "should include expiry message"))))))

;; =============================================================================
;; Collect Tests: Not Found
;; =============================================================================

(deftest test-collect-not-found
  (testing "collect returns not-found for invalid batch-id"
    (let [r (async/collect-async-result "batch-nonexistent")]
      (is (= "not-found" (:status r)))
      (is (= "batch-nonexistent" (:batch-id r))))))

;; =============================================================================
;; Cancel Tests
;; =============================================================================

(deftest test-cancel-running-batch
  (testing "cancel stops a running batch and marks it cancelled"
    (with-mock slow-mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}])
            cancel-r (async/cancel-async-batch batch-id)]
        (is (true? (:cancelled cancel-r))
            "cancel should return true")
        (is (= batch-id (:batch-id cancel-r)))
        ;; Verify collect shows cancelled
        (let [r (async/collect-async-result batch-id)]
          (is (= "cancelled" (:status r))
              "collect should show cancelled status"))))))

(deftest test-cancel-completed-batch
  (testing "cancel returns not-running for already-completed batch"
    (with-mock mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}])]
        (is (= "completed" (:status (async/await-async-batch batch-id))))
        (let [r (async/cancel-async-batch batch-id)]
          (is (false? (:cancelled r)))
          (is (= "not-running" (:reason r))))))))

(deftest test-cancel-not-found
  (testing "cancel returns not-found for invalid batch-id"
    (let [r (async/cancel-async-batch "batch-nonexistent")]
      (is (false? (:cancelled r)))
      (is (= "not-found" (:reason r))))))

;; =============================================================================
;; List Tests
;; =============================================================================

(deftest test-list-empty
  (testing "list returns empty vector when no batches"
    (is (empty? (async/list-async-batches)))))

(deftest test-list-single-batch
  (testing "list returns one entry for one dispatched batch"
    (with-mock slow-mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}])
            batches (async/list-async-batches)]
        (is (= 1 (count batches)))
        (let [b (first batches)]
          (is (= batch-id (:batch-id b)))
          (is (= "running" (:status b)))
          (is (= 1 (:ops b)))
          (is (number? (:elapsed-ms b))))))))

(deftest test-list-multiple-batches
  (testing "list returns all tracked batches with correct status"
    (with-mock slow-mock-run-multi
      (let [r1 (async/run-multi-async [{:id "op-1" :tool "memory" :command "add"}])
            r2 (async/run-multi-async [{:id "op-2" :tool "kg"     :command "stats"}])
            batches (async/list-async-batches)]
        (is (= 2 (count batches)))
        (is (= #{(:batch-id r1) (:batch-id r2)}
               (set (map :batch-id batches)))
            "all batch-ids should be present")
        (is (every? #(= "running" (:status %)) batches)
            "all should be running")))))

(deftest test-list-mixed-statuses
  (testing "list shows mixed statuses after some complete"
    (with-mock mock-run-multi
      ;; Dispatch two batches (instant completion)
      (let [r1 (async/run-multi-async [{:id "op-1" :tool "memory" :command "add"}])]
        (async/await-async-batch (:batch-id r1))
        ;; Now dispatch a slow one
        (with-mock slow-mock-run-multi
          (let [r2 (async/run-multi-async [{:id "op-2" :tool "kg" :command "stats"}])
                batches (async/list-async-batches)
                by-id   (into {} (map (juxt :batch-id identity)) batches)]
            (is (= "completed" (get-in by-id [(:batch-id r1) :status])))
            (is (= "running"   (get-in by-id [(:batch-id r2) :status])))))))))

;; =============================================================================
;; Concurrent Batches Tests
;; =============================================================================

(deftest test-concurrent-dispatches
  (testing "multiple concurrent dispatches produce unique batch-ids"
    (with-mock mock-run-multi
      (let [n         10
            batch-ids (mapv (fn [i]
                              (:batch-id
                               (async/run-multi-async
                                [{:id (str "op-" i) :tool "memory" :command "add"}])))
                            (range n))]
        (is (= n (count (set batch-ids)))
            "all batch-ids should be unique")
        ;; Await each batch through its own future — no sleeping.
        (doseq [bid batch-ids]
          (is (= "completed" (:status (async/await-async-batch bid)))
              (str "batch " bid " should be completed")))))))

(deftest test-concurrent-dispatches-via-futures
  (testing "dispatches from multiple threads don't corrupt registry"
    (with-mock mock-run-multi
      (let [n       20
            futures (mapv (fn [i]
                            (future
                              (async/run-multi-async
                               [{:id (str "op-" i) :tool "memory" :command "add"}])))
                          (range n))
            results (mapv deref futures)]
        (is (= n (count results)))
        (is (every? #(= "running" (:status %)) results))
        (is (= n (count (set (map :batch-id results))))
            "all batch-ids should be unique")
        ;; Await every batch through its own future — a dispatch that finishes
        ;; before its dispatcher returns must still settle as completed.
        (is (= n (count (filter #(= "completed" (:status (async/await-async-batch (:batch-id %))))
                                results))))))))

(deftest test-concurrent-collects-same-batch
  (testing "concurrent collects on same completed batch don't corrupt state"
    (with-mock mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}])]
        (is (= "completed" (:status (async/await-async-batch batch-id))))
        (let [collect-futures (mapv (fn [_]
                                      (future (async/collect-async-result batch-id)))
                                    (range 20))
              collected       (mapv deref collect-futures)]
          (is (every? #(= "completed" (:status %)) collected)
              "all concurrent collects should return completed")
          (is (every? #(= batch-id (:batch-id %)) collected)
              "all should reference same batch-id"))))))

;; =============================================================================
;; GC Tests
;; =============================================================================

(deftest test-gc-purges-expired-terminal
  (testing "gc-completed! removes terminal batches with expired context-store entries"
    (with-mock mock-run-multi
      (let [ttl-ms 50
            {:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}]
                                :ttl-ms ttl-ms)]
        (is (= "completed" (:status (async/await-async-batch batch-id))))
        ;; Only the context-store TTL is wall-clock here.
        (Thread/sleep (* 2 ttl-ms))
        (let [purged (async/gc-completed!)]
          (is (pos? purged)
              "should purge at least one entry")
          (is (= "not-found" (:status (async/collect-async-result batch-id)))
              "batch should no longer exist in registry"))))))

(deftest test-gc-preserves-running
  (testing "gc-completed! does not purge running batches"
    (with-mock slow-mock-run-multi
      (async/run-multi-async [{:id "op-1" :tool "memory" :command "add"}])
      (let [purged (async/gc-completed!)]
        (is (zero? purged)
            "running batches should not be purged")
        (is (= 1 (count (async/list-async-batches))))))))

(deftest test-gc-preserves-live-completed
  (testing "gc-completed! does not purge completed batches with live context-store entries"
    (with-mock mock-run-multi
      (let [{:keys [batch-id]} (async/run-multi-async
                                [{:id "op-1" :tool "memory" :command "add"}]
                                :ttl-ms 60000)] ;; long TTL
        (is (= "completed" (:status (async/await-async-batch batch-id))))
        (let [purged (async/gc-completed!)]
          (is (zero? purged)
              "completed batch with live result should not be purged")
          (is (= 1 (count (async/list-async-batches)))))))))

;; =============================================================================
;; Reset Tests
;; =============================================================================

(deftest test-reset-all
  (testing "reset-all! clears batch registry completely"
    (with-mock mock-run-multi
      (async/run-multi-async [{:id "op-1" :tool "memory" :command "add"}])
      (async/run-multi-async [{:id "op-2" :tool "kg" :command "stats"}])
      (is (= 2 (count (async/list-async-batches))))
      (async/reset-all!)
      (is (empty? (async/list-async-batches))))))
