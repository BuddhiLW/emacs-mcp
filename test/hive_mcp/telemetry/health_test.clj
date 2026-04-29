(ns hive-mcp.telemetry.health-test
  "TDD tests for centralized health/error handling module.

   CLARITY-T: All catastrophic events flow through health module for:
   - Structured logging
   - WebSocket emission (Emacs visibility)
   - DataScript persistence (post-mortem)
   - Circuit breaker state updates"
  (:require [clojure.test :refer [deftest is testing use-fixtures run-tests]]
            [clojure.string :as str]
            [hive-mcp.telemetry.health :as health]
            [hive-mcp.telemetry.prometheus :as prom]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.channel.core :as channel]
            [taoensso.timbre :as log]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(use-fixtures :each (iso/with-isolations :swarm-ds))

;;; =============================================================================
;;; emit-health-event! Tests
;;; =============================================================================

(deftest test-emit-health-event-logs-correctly
  (testing "emit-health-event! emits events for all severity levels"
    ;; Note: We can't easily mock Timbre macros with with-redefs.
    ;; Instead, we verify the behavior by checking that events are
    ;; persisted to DataScript and return valid event IDs.
    (with-redefs [channel/emit-event! (fn [_ _] nil)]

      ;; Info severity
      (let [event-id (health/emit-health-event! {:type :harvest-failed
                                                 :severity :info
                                                 :message "Test info"
                                                 :recoverable? true})]
        (is (string? event-id) "Info severity returns event-id"))

      ;; Warn severity
      (let [event-id (health/emit-health-event! {:type :emacs-unreachable
                                                 :severity :warn
                                                 :message "Test warn"
                                                 :recoverable? true})]
        (is (string? event-id) "Warn severity returns event-id"))

      ;; Error severity
      (let [event-id (health/emit-health-event! {:type :chroma-unavailable
                                                 :severity :error
                                                 :message "Test error"
                                                 :recoverable? false})]
        (is (string? event-id) "Error severity returns event-id"))

      ;; Fatal severity
      (let [event-id (health/emit-health-event! {:type :websocket-death
                                                 :severity :fatal
                                                 :message "Test fatal"
                                                 :recoverable? false})]
        (is (string? event-id) "Fatal severity returns event-id"))

      ;; Verify all 4 events were persisted
      (let [events (health/get-recent-errors :limit 10)]
        (is (= 4 (count events)) "All 4 events persisted to DataScript")))))

(deftest test-emit-health-event-returns-event-id
  (testing "emit-health-event! returns event-id for correlation"
    (with-redefs [log/error (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      (let [event-id (health/emit-health-event! {:type :nrepl-disconnect
                                                 :severity :error
                                                 :message "Connection lost"
                                                 :recoverable? true})]
        (is (string? event-id) "Returns a string event-id")
        (is (.startsWith event-id "health-event-") "Event ID has correct prefix")))))

(deftest test-emit-health-event-emits-to-channel
  (testing "emit-health-event! broadcasts to WebSocket channel"
    (let [emitted-events (atom [])]
      (with-redefs [log/warn (fn [& _] nil)
                    channel/emit-event! (fn [event-type data]
                                          (swap! emitted-events conj {:type event-type :data data}))]
        (health/emit-health-event! {:type :restart-collision
                                    :severity :warn
                                    :message "Restart in progress"
                                    :context {:previous-pid 1234}
                                    :recoverable? true})
        (is (= 1 (count @emitted-events)) "Emits exactly one event")
        (let [{:keys [type data]} (first @emitted-events)]
          (is (= :health-event type) "Event type is :health-event")
          (is (= :restart-collision (:error-type data)) "Includes error type")
          (is (= "Restart in progress" (:message data)) "Includes message")
          (is (= {:previous-pid 1234} (:context data)) "Includes context"))))))

(deftest test-emit-health-event-persists-to-datascript
  (testing "emit-health-event! stores event in DataScript"
    (with-redefs [log/error (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      (let [event-id (health/emit-health-event! {:type :hot-reload-failed
                                                 :severity :error
                                                 :message "Namespace not found"
                                                 :context {:namespace "my.ns"}
                                                 :recoverable? true})
            ;; Query DataScript for the event
            events (health/get-recent-errors :limit 1)]
        (is (= 1 (count events)) "Event persisted")
        (let [event (first events)]
          (is (= event-id (:health-event/id event)) "Correct event-id")
          (is (= :hot-reload-failed (:health-event/type event)) "Correct type")
          (is (= :error (:health-event/severity event)) "Correct severity")
          (is (= "Namespace not found" (:health-event/message event)) "Correct message")
          (is (= {:namespace "my.ns"} (:health-event/context event)) "Correct context")
          (is (true? (:health-event/recoverable? event)) "Correct recoverable flag"))))))

(deftest test-emit-health-event-validates-type
  (testing "emit-health-event! validates error type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown error type"
                          (health/emit-health-event! {:type :unknown-type
                                                      :severity :error
                                                      :message "Invalid"
                                                      :recoverable? false})))))

(deftest test-emit-health-event-validates-severity
  (testing "emit-health-event! validates severity"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid severity"
                          (health/emit-health-event! {:type :harvest-failed
                                                      :severity :critical  ; invalid
                                                      :message "Invalid"
                                                      :recoverable? false})))))

;;; =============================================================================
;;; get-recent-errors Tests
;;; =============================================================================

(deftest test-get-recent-errors-default-limit
  (testing "get-recent-errors returns up to 20 errors by default"
    (with-redefs [log/error (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      ;; Create 25 events
      (doseq [i (range 25)]
        (health/emit-health-event! {:type :harvest-failed
                                    :severity :error
                                    :message (str "Error " i)
                                    :recoverable? false}))
      (let [errors (health/get-recent-errors)]
        (is (<= (count errors) 20) "Returns at most 20 errors by default")))))

(deftest test-get-recent-errors-custom-limit
  (testing "get-recent-errors respects custom limit"
    (with-redefs [log/error (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      (doseq [i (range 10)]
        (health/emit-health-event! {:type :harvest-failed
                                    :severity :error
                                    :message (str "Error " i)
                                    :recoverable? false}))
      (let [errors (health/get-recent-errors :limit 5)]
        (is (= 5 (count errors)) "Returns exactly 5 errors")))))

(deftest test-get-recent-errors-filter-by-type
  (testing "get-recent-errors can filter by error type"
    (with-redefs [log/error (fn [& _] nil)
                  log/warn (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      (health/emit-health-event! {:type :harvest-failed :severity :error :message "A" :recoverable? false})
      (health/emit-health-event! {:type :chroma-unavailable :severity :warn :message "B" :recoverable? true})
      (health/emit-health-event! {:type :harvest-failed :severity :error :message "C" :recoverable? false})

      (let [harvest-errors (health/get-recent-errors :type :harvest-failed)]
        (is (= 2 (count harvest-errors)) "Returns only harvest-failed errors")
        (is (every? #(= :harvest-failed (:health-event/type %)) harvest-errors))))))

;;; =============================================================================
;;; health-summary Tests
;;; =============================================================================

(deftest test-health-summary-structure
  (testing "health-summary returns expected structure"
    (with-redefs [log/error (fn [& _] nil)
                  log/warn (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      (let [summary (health/health-summary)]
        (is (map? summary) "Returns a map")
        (is (contains? summary :error-counts) "Has error-counts")
        (is (contains? summary :last-error-times) "Has last-error-times")
        (is (contains? summary :total-errors) "Has total-errors")))))

(deftest test-health-summary-counts-by-type
  (testing "health-summary counts errors by type correctly"
    (with-redefs [log/error (fn [& _] nil)
                  log/warn (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      (health/emit-health-event! {:type :harvest-failed :severity :error :message "A" :recoverable? false})
      (health/emit-health-event! {:type :harvest-failed :severity :error :message "B" :recoverable? false})
      (health/emit-health-event! {:type :chroma-unavailable :severity :warn :message "C" :recoverable? true})

      (let [summary (health/health-summary)]
        (is (= 2 (get-in summary [:error-counts :harvest-failed])) "harvest-failed count correct")
        (is (= 1 (get-in summary [:error-counts :chroma-unavailable])) "chroma-unavailable count correct")
        (is (= 3 (:total-errors summary)) "Total errors correct")))))

(deftest test-health-summary-tracks-last-error-times
  (testing "health-summary tracks last error time per type"
    (with-redefs [log/error (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      (health/emit-health-event! {:type :harvest-failed :severity :error :message "Test" :recoverable? false})

      (let [summary (health/health-summary)
            last-time (get-in summary [:last-error-times :harvest-failed])]
        (is (some? last-time) "Last error time recorded")
        (is (instance? java.util.Date last-time) "Is a Date object")))))

;;; =============================================================================
;;; Severity Validation Tests
;;; =============================================================================

(deftest test-severities-set
  (testing "severities contains expected values"
    (is (= #{:info :warn :error :fatal} health/severities))))

(deftest test-error-types-set
  (testing "error-types contains expected values"
    (is (contains? health/error-types :harvest-failed))
    (is (contains? health/error-types :emacs-unreachable))
    (is (contains? health/error-types :chroma-unavailable))
    (is (contains? health/error-types :websocket-death))
    (is (contains? health/error-types :nrepl-disconnect))
    (is (contains? health/error-types :restart-collision))
    (is (contains? health/error-types :hot-reload-failed))
    (is (contains? health/error-types :wrap-crystallize-failed))))

;;; =============================================================================
;;; Prometheus Integration Tests (CLARITY-T: Telemetry first)
;;; =============================================================================

(deftest test-emit-health-event-increments-prometheus-counter
  (testing "emit-health-event! increments Prometheus errors-total counter"
    ;; Initialize Prometheus (idempotent)
    (prom/init!)

    (with-redefs [log/error (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      ;; Get metrics before
      (let [_metrics-before (prom/metrics-response)]
        ;; Emit a health event
        (health/emit-health-event! {:type :harvest-failed
                                    :severity :error
                                    :message "Test prometheus integration"
                                    :recoverable? true})

        ;; Get metrics after
        (let [metrics-after (prom/metrics-response)]
          ;; Verify errors-total counter is present with correct labels
          (is (str/includes? metrics-after "hive_mcp_errors_total")
              "errors-total counter present")
          (is (str/includes? metrics-after "error_type=\"harvest-failed\"")
              "error-type label present")
          (is (str/includes? metrics-after "recoverable=\"true\"")
              "recoverable label present"))))))

(deftest test-emit-health-event-prometheus-labels-recoverable-false
  (testing "emit-health-event! records recoverable=false correctly"
    (prom/init!)

    (with-redefs [log/error (fn [& _] nil)
                  channel/emit-event! (fn [_ _] nil)]
      (health/emit-health-event! {:type :chroma-unavailable
                                  :severity :error
                                  :message "Test non-recoverable"
                                  :recoverable? false})

      (let [metrics (prom/metrics-response)]
        (is (str/includes? metrics "error_type=\"chroma-unavailable\"")
            "chroma-unavailable error type present")
        (is (str/includes? metrics "recoverable=\"false\"")
            "recoverable=false label present")))))

(comment
  ;; Run tests
  (run-tests)

  ;; Run specific test
  (test-emit-health-event-logs-correctly)
  (test-health-summary-structure)
  (test-emit-health-event-increments-prometheus-counter))
