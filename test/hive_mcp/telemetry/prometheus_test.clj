(ns hive-mcp.telemetry.prometheus-test
  "TDD tests for Prometheus metrics module.

   CLARITY-T: Telemetry first - verifies all metrics are correctly
   recorded and exported in Prometheus exposition format.

   Test categories:
   1. Registry initialization
   2. Counter increments
   3. Gauge sets
   4. Histogram observations
   5. Timing macros
   6. Export format validation"
  (:require [clojure.test :refer [deftest is run-tests testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.telemetry.prometheus :as prom]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(defn init-prometheus-fixture
  "Ensure Prometheus registry is initialized before tests."
  [f]
  (prom/init!)
  (f))

(use-fixtures :once init-prometheus-fixture)

;;; =============================================================================
;;; Registry Initialization Tests
;;; =============================================================================

(deftest test-init-idempotent
  (testing "init! can be called multiple times safely"
    (is (true? (prom/init!)) "First init succeeds")
    (is (true? (prom/init!)) "Second init succeeds (idempotent)")))

(deftest test-registry-exists
  (testing "Registry is a delay that can be dereferenced"
    (is (delay? prom/registry) "Registry is a delay")
    (is (some? @prom/registry) "Registry can be dereferenced")))

;;; =============================================================================
;;; Counter Tests
;;; =============================================================================

(deftest test-events-counter
  (testing "events-total counter increments correctly"
    ;; Get initial metrics to establish baseline
    (let [_initial (prom/metrics-response)]
      ;; Increment counter
      (prom/inc-events-total! :progress :info)
      (prom/inc-events-total! :completed :info)
      (prom/inc-events-total! :error :error)

      ;; Verify counter is present in export
      (let [metrics (prom/metrics-response)]
        (is (str/includes? metrics "hive_mcp_events_total")
            "events-total counter present in export")
        (is (str/includes? metrics "type=\"progress\"")
            "progress label present")
        (is (str/includes? metrics "type=\"completed\"")
            "completed label present")
        (is (str/includes? metrics "severity=\"info\"")
            "info severity label present")
        (is (str/includes? metrics "severity=\"error\"")
            "error severity label present")))))

(deftest test-errors-counter
  (testing "errors-total counter increments correctly"
    (prom/inc-errors-total! :chroma-unavailable true)
    (prom/inc-errors-total! :harvest-failed false)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_errors_total")
          "errors-total counter present")
      (is (str/includes? metrics "error_type=\"chroma-unavailable\"")
          "error-type label present")
      (is (str/includes? metrics "recoverable=\"true\"")
          "recoverable=true label present")
      (is (str/includes? metrics "recoverable=\"false\"")
          "recoverable=false label present"))))

(deftest test-mcp-requests-counter
  (testing "mcp-requests-total counter increments correctly"
    (prom/inc-mcp-requests! "mcp_memory_query")
    (prom/inc-mcp-requests! "mcp_memory_add")

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_mcp_requests_total")
          "mcp-requests-total counter present")
      (is (str/includes? metrics "tool=\"mcp_memory_query\"")
          "tool label present"))))

(deftest test-memory-ops-counter
  (testing "memory-ops-total counter increments correctly"
    (prom/inc-memory-ops! :query :success)
    (prom/inc-memory-ops! :add :failure)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_memory_ops_total")
          "memory-ops-total counter present")
      (is (str/includes? metrics "operation=\"query\"")
          "operation label present")
      (is (str/includes? metrics "result=\"success\"")
          "result label present"))))

(deftest test-hivemind-shouts-counter
  (testing "hivemind-shouts-total counter increments correctly"
    (prom/inc-hivemind-shouts! :progress)
    (prom/inc-hivemind-shouts! :completed)
    (prom/inc-hivemind-shouts! :blocked)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_hivemind_shouts_total")
          "hivemind-shouts-total counter present")
      (is (str/includes? metrics "event_type=\"progress\"")
          "event-type label present"))))

;;; =============================================================================
;;; Gauge Tests
;;; =============================================================================

(deftest test-lings-gauge
  (testing "lings-active gauge can be set"
    (prom/set-lings-active! 5)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_lings_active")
          "lings-active gauge present")
      (is (str/includes? metrics "5.0")
          "gauge value is 5.0"))))

(deftest test-drones-gauge
  (testing "drones-active gauge can be set"
    (prom/set-drones-active! 3)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_drones_active")
          "drones-active gauge present"))))

(deftest test-ws-clients-gauge
  (testing "ws-clients gauge can be set"
    (prom/set-ws-clients! 2)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_ws_clients")
          "ws-clients gauge present"))))

;;; =============================================================================
;;; Histogram Tests
;;; =============================================================================

(deftest test-request-duration-histogram
  (testing "request-duration-seconds histogram observes values"
    (prom/observe-request-duration! "test-tool" 0.025)
    (prom/observe-request-duration! "test-tool" 0.150)
    (prom/observe-request-duration! "test-tool" 1.5)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_request_duration_seconds")
          "request-duration-seconds histogram present")
      (is (str/includes? metrics "_bucket{")
          "histogram bucket present")
      (is (str/includes? metrics "_count")
          "histogram count present")
      (is (str/includes? metrics "_sum")
          "histogram sum present")
      (is (str/includes? metrics "tool=\"test-tool\"")
          "tool label present"))))

(deftest test-chroma-query-histogram
  (testing "chroma-query-seconds histogram observes values"
    (prom/observe-chroma-query! :search 0.042)
    (prom/observe-chroma-query! :query 0.015)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_chroma_query_seconds")
          "chroma-query-seconds histogram present")
      (is (str/includes? metrics "operation=\"search\"")
          "operation label present"))))

;;; =============================================================================
;;; Timing Macro Tests
;;; =============================================================================

(deftest test-with-request-timing-macro
  (testing "with-request-timing records duration and returns result"
    (let [result (prom/with-request-timing "timing-test"
                   (Thread/sleep 10) ; Sleep for 10ms
                   {:status :ok})]
      ;; Verify result is returned
      (is (= {:status :ok} result) "Macro returns body result")

      ;; Verify metric was recorded
      (let [metrics (prom/metrics-response)]
        (is (str/includes? metrics "tool=\"timing-test\"")
            "timing-test tool label recorded")))))

;; Renamed upstream: prom/with-chroma-timing -> prom/with-store-timing
;; (commit 2bbd1d7). Same arity [operation & body], same underlying
;; observe-chroma-query! / hive_mcp_chroma_query_seconds histogram.
(deftest test-with-store-timing-macro
  (testing "with-store-timing records duration and returns result"
    (let [result (prom/with-store-timing :add
                   (Thread/sleep 5) ; Sleep for 5ms
                   [{:id "doc1"} {:id "doc2"}])]
      ;; Verify result is returned
      (is (= [{:id "doc1"} {:id "doc2"}] result) "Macro returns body result")

      ;; Verify metric was recorded. Uses :add (not :search, which
      ;; test-chroma-query-histogram already records) so the label below can
      ;; only have been emitted by the macro under test.
      (let [metrics (prom/metrics-response)]
        (is (str/includes? metrics "hive_mcp_chroma_query_seconds")
            "chroma-query-seconds histogram present")
        (is (str/includes? metrics "operation=\"add\"")
            "add operation label recorded by with-store-timing")))))

;;; =============================================================================
;;; Export Format Tests
;;; =============================================================================

(deftest test-metrics-response-format
  (testing "metrics-response returns valid Prometheus format"
    (let [metrics (prom/metrics-response)]
      ;; Should be a string
      (is (string? metrics) "Response is a string")

      ;; Should contain HELP lines
      (is (str/includes? metrics "# HELP")
          "Contains HELP comments")

      ;; Should contain TYPE lines
      (is (str/includes? metrics "# TYPE")
          "Contains TYPE comments")

      ;; Should contain JVM metrics (from jvm/initialize)
      (is (str/includes? metrics "jvm_")
          "Contains JVM metrics"))))

(deftest test-metrics-handler-ring-format
  (testing "metrics-handler returns Ring-compatible response"
    (let [response (prom/metrics-handler {})]
      (is (= 200 (:status response)) "Status is 200")
      (is (str/includes? (get-in response [:headers "Content-Type"]) "text/plain")
          "Content-Type is text/plain")
      (is (string? (:body response)) "Body is a string")
      (is (str/includes? (:body response) "hive_mcp")
          "Body contains hive_mcp metrics"))))

;;; =============================================================================
;;; Integration Test - Full Flow
;;; =============================================================================

(deftest test-full-metrics-flow
  (testing "Complete flow: record metrics → export → verify"
    ;; Record various metrics
    (prom/inc-events-total! :started :info)
    (prom/inc-mcp-requests! "integration-test-tool")
    (prom/set-lings-active! 2)
    (prom/observe-request-duration! "integration-test-tool" 0.033)

    ;; Export and verify
    (let [metrics (prom/metrics-response)]
      ;; All our custom metrics should be present
      (is (str/includes? metrics "hive_mcp_events_total") "events present")
      (is (str/includes? metrics "hive_mcp_mcp_requests_total") "requests present")
      (is (str/includes? metrics "hive_mcp_lings_active") "lings gauge present")
      (is (str/includes? metrics "hive_mcp_request_duration_seconds") "duration present")

      ;; JVM metrics should also be present
      (is (str/includes? metrics "jvm_memory") "JVM memory metrics present")
      (is (str/includes? metrics "jvm_threads") "JVM threads metrics present"))))

;;; =============================================================================
;;; Drone Error Metrics Tests (CLARITY-T: Enhanced error tracking)
;;; =============================================================================

(deftest test-drones-failed-with-drone-id
  (testing "drones-failed-total counter includes drone_id label"
    (prom/inc-drones-failed! "parent-ling-1" :nrepl-connection "drone-123")
    (prom/inc-drones-failed! "parent-ling-1" :nrepl-timeout "drone-456")
    (prom/inc-drones-failed! "parent-ling-2" :validation)  ;; Without drone-id

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_drones_failed_total")
          "drones-failed-total counter present")
      (is (str/includes? metrics "drone_id=\"drone-123\"")
          "drone_id label with specific value present")
      (is (str/includes? metrics "drone_id=\"drone-456\"")
          "second drone_id label present")
      (is (str/includes? metrics "error_type=\"nrepl-connection\"")
          "nrepl-connection error type present")
      (is (str/includes? metrics "error_type=\"nrepl-timeout\"")
          "nrepl-timeout error type present")
      (is (str/includes? metrics "error_type=\"validation\"")
          "validation error type present"))))

(deftest test-drone-duration-with-status
  (testing "drone-duration-seconds histogram includes status label"
    (prom/observe-drone-duration! "parent-1" 5.0 :success)
    (prom/observe-drone-duration! "parent-1" 3.0 :failed)
    (prom/observe-drone-duration! "parent-2" 2.5) ;; Default status=success

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_drone_duration_seconds")
          "drone-duration-seconds histogram present")
      (is (str/includes? metrics "status=\"success\"")
          "success status label present")
      (is (str/includes? metrics "status=\"failed\"")
          "failed status label present"))))

;;; =============================================================================
;;; Wave Failure Metrics Tests (CLARITY-T: Wave-level error tracking)
;;; =============================================================================

(deftest test-wave-failures-counter
  (testing "wave-failures-total counter tracks failure reasons"
    (prom/inc-wave-failures! "wave-001" :nrepl-unhealthy)
    (prom/inc-wave-failures! "wave-002" :partial-failure)
    (prom/inc-wave-failures! "wave-003" :all-failed)
    (prom/inc-wave-failures! "wave-004" :cancelled)

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_wave_failures_total")
          "wave-failures-total counter present")
      (is (str/includes? metrics "wave_id=\"wave-001\"")
          "wave_id label present")
      (is (str/includes? metrics "reason=\"nrepl-unhealthy\"")
          "nrepl-unhealthy reason present")
      (is (str/includes? metrics "reason=\"partial-failure\"")
          "partial-failure reason present")
      (is (str/includes? metrics "reason=\"all-failed\"")
          "all-failed reason present")
      (is (str/includes? metrics "reason=\"cancelled\"")
          "cancelled reason present"))))

;;; =============================================================================
;;; Effect Handler Tests (CLARITY-T: Event system integration)
;;; =============================================================================

(deftest test-handle-prometheus-effect-drone-failed
  (testing "handle-prometheus-effect! handles drone_failed with all labels"
    (prom/handle-prometheus-effect!
     {:counter :drone_failed
      :labels {:parent "ling-1"
               :error_type "nrepl-connection"
               :drone_id "drone-789"}})

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "parent=\"ling-1\"")
          "parent label from effect")
      (is (str/includes? metrics "drone_id=\"drone-789\"")
          "drone_id label from effect"))))

(deftest test-handle-prometheus-effect-drone-duration-with-status
  (testing "handle-prometheus-effect! handles histogram with status"
    (prom/handle-prometheus-effect!
     {:counter :drone_completed
      :labels {:parent "ling-2"}
      :histogram {:name :drone_duration_seconds
                  :value 4.5
                  :status :success}})

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_drone_duration_seconds")
          "duration histogram recorded via effect"))))

(deftest test-handle-prometheus-effect-wave-failure
  (testing "handle-prometheus-effect! handles wave_failure counter"
    (prom/handle-prometheus-effect!
     {:counter :wave_failure
      :labels {:wave_id "wave-effect-test"
               :reason "validation"}})

    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "wave_id=\"wave-effect-test\"")
          "wave_id from effect handler")
      (is (str/includes? metrics "reason=\"validation\"")
          "reason from effect handler"))))

(comment
  ;; Run tests from REPL
  (run-tests 'hive-mcp.telemetry.prometheus-test)

  ;; Run specific test
  (test-full-metrics-flow))
