(ns hive-mcp.events.metrics-test
  "Tests for metrics interceptor - POC-15 + E2 bounded metrics buffer.

   Validates:
   - Per-event-type dispatch counting
   - Handler execution time measurement
   - get-metrics and reset-metrics! functions
   - Bounded timings buffers (FIFO eviction)
   - Configurable buffer limits
   - Buffer metadata in get-metrics
   - Dropped counter tracking"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.events.core :as ev]
            [hive-mcp.telemetry.prometheus :as prom]
            [hive-mcp.events.metrics]))

;; =============================================================================
;; Test fixture: Reset metrics and config before each test
;; =============================================================================

(defn reset-metrics-fixture [f]
  (ev/reset-metrics!)
  (ev/configure-metrics! {:max-timings 1000 :max-timings-per-type 200})
  (f)
  (ev/reset-metrics!)
  (ev/configure-metrics! {:max-timings 1000 :max-timings-per-type 200}))

(use-fixtures :each reset-metrics-fixture)

;; =============================================================================
;; POC-15: Metrics interceptor tests (original, preserved)
;; =============================================================================

(deftest metrics-interceptor-exists
  (testing "metrics interceptor is defined"
    (is (some? ev/metrics) "metrics interceptor should be defined")
    (is (ev/interceptor? ev/metrics) "metrics should be a valid interceptor")
    (is (= :metrics (:id ev/metrics)) "interceptor id should be :metrics")))

(deftest get-metrics-returns-snapshot
  (testing "get-metrics returns expected structure"
    (let [m (ev/get-metrics)]
      (is (map? m) "Should return a map")
      (is (contains? m :events-dispatched) "Should have :events-dispatched")
      (is (contains? m :events-by-type) "Should have :events-by-type")
      (is (contains? m :effects-executed) "Should have :effects-executed")
      (is (contains? m :errors) "Should have :errors")
      (is (contains? m :avg-dispatch-ms) "Should have :avg-dispatch-ms")
      (is (contains? m :avg-by-type) "Should have :avg-by-type")
      (is (contains? m :timings-count) "Should have :timings-count")
      ;; E2: buffer metadata
      (is (contains? m :timings-buffer-size) "Should have :timings-buffer-size")
      (is (contains? m :timings-buffer-capacity) "Should have :timings-buffer-capacity")
      (is (contains? m :timings-dropped) "Should have :timings-dropped"))))

(deftest reset-metrics-clears-all-counters
  (testing "reset-metrics! clears all metrics to initial state"
    ;; First, let's manually set some values to ensure reset works
    (let [before (ev/get-metrics)]
      (ev/reset-metrics!)
      (let [after (ev/get-metrics)]
        (is (= 0 (:events-dispatched after)))
        (is (= {} (:events-by-type after)))
        (is (= 0 (:effects-executed after)))
        (is (= 0 (:errors after)))
        (is (= 0 (:timings-count after)))
        (is (= {} (:avg-by-type after)))
        ;; E2: dropped counter resets too
        (is (= 0 (:timings-dropped after))
            "reset-metrics! should clear dropped counter")))))

(deftest metrics-interceptor-tracks-total-count
  (testing "metrics interceptor increments total event count"
    (let [initial (ev/get-metrics)
          context {:coeffects {:event [:test-event {:data "test"}]}
                   :effects {}
                   :queue []
                   :stack []}
          ;; Execute the before phase
          before-fn (:before ev/metrics)
          after-ctx (before-fn context)
          final (ev/get-metrics)]
      (is (= 1 (- (:events-dispatched final)
                  (:events-dispatched initial)))
          "Should increment total event count by 1"))))

(deftest metrics-interceptor-tracks-per-event-type
  (testing "metrics interceptor tracks count per event type"
    (ev/reset-metrics!)
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)
          ;; Dispatch :event-a twice
          ctx-a1 {:coeffects {:event [:event-a {:x 1}]} :effects {} :queue [] :stack []}
          ctx-a2 {:coeffects {:event [:event-a {:x 2}]} :effects {} :queue [] :stack []}
          ;; Dispatch :event-b once
          ctx-b {:coeffects {:event [:event-b {:y 1}]} :effects {} :queue [] :stack []}
          _ (-> ctx-a1 before-fn after-fn)
          _ (-> ctx-a2 before-fn after-fn)
          _ (-> ctx-b before-fn after-fn)
          m (ev/get-metrics)]
      (is (= 3 (:events-dispatched m)) "Total should be 3")
      (is (= 2 (get-in m [:events-by-type :event-a])) ":event-a should have 2")
      (is (= 1 (get-in m [:events-by-type :event-b])) ":event-b should have 1"))))

(deftest metrics-interceptor-records-timing
  (testing "metrics interceptor records execution time"
    (ev/reset-metrics!)
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)
          ctx {:coeffects {:event [:timed-event {}]} :effects {} :queue [] :stack []}
          ;; Run through the interceptor
          ctx-after-before (before-fn ctx)
          ;; Simulate some work (sleep a tiny bit)
          _ (Thread/sleep 1)
          ctx-after-after (after-fn ctx-after-before)
          m (ev/get-metrics)]
      (is (pos? (:timings-count m)) "Should have timing samples")
      (is (pos? (:avg-dispatch-ms m)) "Average dispatch time should be positive")
      (is (seq (get-in m [:timings-by-type :timed-event]))
          "Should have per-type timing samples"))))

(deftest metrics-interceptor-records-timing-per-type
  (testing "metrics interceptor tracks timing separately per event type"
    (ev/reset-metrics!)
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)
          ctx-fast {:coeffects {:event [:fast-event {}]} :effects {} :queue [] :stack []}
          ctx-slow {:coeffects {:event [:slow-event {}]} :effects {} :queue [] :stack []}
          ;; Fast event
          _ (-> ctx-fast before-fn after-fn)
          ;; Slow event (with artificial delay)
          ctx-slow-after-before (before-fn ctx-slow)
          _ (Thread/sleep 5)
          _ (after-fn ctx-slow-after-before)
          m (ev/get-metrics)]
      (is (contains? (:avg-by-type m) :fast-event) "Should have :fast-event avg")
      (is (contains? (:avg-by-type m) :slow-event) "Should have :slow-event avg")
      ;; Slow event should have higher average (not always guaranteed due to timing variance,
      ;; but with 5ms delay it should be reliably larger)
      (is (> (get-in m [:avg-by-type :slow-event])
             (get-in m [:avg-by-type :fast-event]))
          ":slow-event should have higher average time than :fast-event"))))

(deftest metrics-interceptor-rolling-window
  (testing "metrics uses bounded rolling window (default 1000 global, 200 per-type)"
    (ev/reset-metrics!)
    ;; Use small limits for this test
    (ev/configure-metrics! {:max-timings 100 :max-timings-per-type 100})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)
          ;; Dispatch 150 events
          _ (dotimes [_ 150]
              (let [ctx {:coeffects {:event [:window-test {}]}
                         :effects {}
                         :queue []
                         :stack []}]
                (-> ctx before-fn after-fn)))
          m (ev/get-metrics)]
      ;; Total count should be 150
      (is (= 150 (:events-dispatched m)) "Total dispatched should be 150")
      ;; But timing samples should be capped at 100
      (is (<= (:timings-count m) 100) "Timings should be capped at 100")
      (is (<= (count (get-in m [:timings-by-type :window-test])) 100)
          "Per-type timings should be capped at 100"))))

;; =============================================================================
;; Integration test: metrics with full dispatch
;; =============================================================================

(deftest metrics-works-with-dispatch
  (testing "metrics interceptor works in full dispatch chain"
    (ev/reset-metrics!)
    (ev/reset-all!)
    ;; Register a test event with metrics interceptor
    (ev/reg-event :metrics-test-event
                  [ev/metrics]
                  (fn [_coeffects _event]
                    {:log "Metrics test event executed"}))
    ;; Register a dummy :log effect handler
    (ev/reg-fx :log (fn [_] nil))
    ;; Dispatch the event
    (ev/dispatch [:metrics-test-event {:test true}])
    (let [m (ev/get-metrics)]
      (is (= 1 (:events-dispatched m)) "Should have 1 event dispatched")
      (is (= 1 (get-in m [:events-by-type :metrics-test-event]))
          "Should have 1 :metrics-test-event")
      (is (pos? (:timings-count m)) "Should have timing recorded"))))

;; =============================================================================
;; Prometheus Integration Tests (CLARITY-T: Telemetry first)
;; =============================================================================

(deftest dispatch-records-prometheus-events-total
  (testing "dispatch increments Prometheus events-total counter"
    (prom/init!)
    (ev/reset-all!)

    ;; Register a test event
    (ev/reg-event :prom-test-event
                  []
                  (fn [_coeffects _event]
                    {:log "Prometheus test"}))
    (ev/reg-fx :log (fn [_] nil))

    ;; Dispatch the event
    (ev/dispatch [:prom-test-event {:data "test"}])

    ;; Verify Prometheus metric
    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_events_total")
          "events-total counter present")
      (is (str/includes? metrics "type=\"prom-test-event\"")
          "event type label recorded"))))

(deftest dispatch-records-prometheus-request-duration
  (testing "dispatch records request duration to Prometheus histogram"
    (prom/init!)
    (ev/reset-all!)

    ;; Register a test event
    (ev/reg-event :prom-duration-test
                  []
                  (fn [_coeffects _event]
                    (Thread/sleep 5) ; Add small delay
                    {:log "Duration test"}))
    (ev/reg-fx :log (fn [_] nil))

    ;; Dispatch the event
    (ev/dispatch [:prom-duration-test {}])

    ;; Verify Prometheus histogram
    (let [metrics (prom/metrics-response)]
      (is (str/includes? metrics "hive_mcp_request_duration_seconds")
          "request-duration-seconds histogram present")
      (is (str/includes? metrics "tool=\"event-dispatch-prom-duration-test\"")
          "tool label with event-dispatch prefix recorded"))))

;; =============================================================================
;; E2: Bounded metrics buffer tests
;; =============================================================================

(deftest timings-capped-at-configured-max
  (testing "global :timings vector is capped at :max-timings"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 50 :max-timings-per-type 200})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)]
      (dotimes [_ 80]
        (let [ctx {:coeffects {:event [:cap-test {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (let [m (ev/get-metrics)]
        (is (= 50 (:timings-count m))
            "Global timings should be capped at 50")
        (is (= 50 (:timings-buffer-size m))
            "Buffer size should match timings count")
        (is (= 50 (:timings-buffer-capacity m))
            "Buffer capacity should reflect configured max")))))

(deftest per-type-timings-capped-at-configured-max
  (testing "per-type timings are capped at :max-timings-per-type"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 5000 :max-timings-per-type 30})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)]
      (dotimes [_ 60]
        (let [ctx {:coeffects {:event [:type-cap-test {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (let [m (ev/get-metrics)
            type-timings (get-in m [:timings-by-type :type-cap-test])]
        (is (= 30 (count type-timings))
            "Per-type timings should be capped at 30")
        ;; Global should have all 60 since cap is 5000
        (is (= 60 (:timings-count m))
            "Global timings should have all 60 (cap is 5000)")))))

(deftest oldest-timings-dropped-first-fifo
  (testing "oldest timings are dropped first (FIFO order preserved)"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 5 :max-timings-per-type 5})
    (let [;; We need to inject known timing values directly to test FIFO order.
          ;; Use the metrics atom directly via the var for testing.
          metrics-atom (var-get #'hive-mcp.events.metrics/*metrics)]
      ;; Manually set up timings with known values [1.0 2.0 3.0 4.0 5.0]
      (swap! metrics-atom assoc
             :timings [1.0 2.0 3.0 4.0 5.0]
             :timings-by-type {:test-event [1.0 2.0 3.0 4.0 5.0]})
      ;; Now dispatch one more event through the interceptor to add a new timing
      (let [before-fn (:before ev/metrics)
            after-fn (:after ev/metrics)
            ctx {:coeffects {:event [:test-event {}]}
                 :effects {}
                 :queue []
                 :stack []}
            ctx-after (-> ctx before-fn after-fn)
            m (ev/get-metrics)
            global-timings (:timings m)
            type-timings (get-in m [:timings-by-type :test-event])]
        ;; Oldest (1.0) should be gone, newest should be at the end
        (is (= 5 (count global-timings))
            "Global timings should remain at cap 5")
        (is (= 5 (count type-timings))
            "Per-type timings should remain at cap 5")
        ;; First element should no longer be 1.0 (it was dropped)
        (is (not= 1.0 (first global-timings))
            "Oldest timing (1.0) should have been evicted from global")
        (is (= 2.0 (first global-timings))
            "Second-oldest (2.0) should now be first in global")
        (is (not= 1.0 (first type-timings))
            "Oldest timing (1.0) should have been evicted from per-type")
        (is (= 2.0 (first type-timings))
            "Second-oldest (2.0) should now be first in per-type")))))

(deftest averages-correct-on-bounded-window
  (testing "averages are computed correctly on the bounded window"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 5 :max-timings-per-type 5})
    (let [metrics-atom (var-get #'hive-mcp.events.metrics/*metrics)]
      ;; Set known timing values
      (swap! metrics-atom assoc
             :timings [10.0 20.0 30.0 40.0 50.0]
             :timings-by-type {:avg-event [10.0 20.0 30.0 40.0 50.0]}
             :events-dispatched 5)
      (let [m (ev/get-metrics)]
        (is (= 30.0 (:avg-dispatch-ms m))
            "Average should be (10+20+30+40+50)/5 = 30.0")
        (is (= 30.0 (get-in m [:avg-by-type :avg-event]))
            "Per-type average should be 30.0")))))

(deftest configure-metrics-changes-limits
  (testing "configure-metrics! changes buffer limits"
    (ev/reset-metrics!)
    ;; Set small limits
    (ev/configure-metrics! {:max-timings 10 :max-timings-per-type 5})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)]
      ;; Fill beyond limits
      (dotimes [_ 20]
        (let [ctx {:coeffects {:event [:config-test {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (let [m (ev/get-metrics)]
        (is (= 10 (:timings-count m))
            "Global timings should be capped at new limit 10")
        (is (= 10 (:timings-buffer-capacity m))
            "Capacity should reflect new limit")
        (is (= 5 (count (get-in m [:timings-by-type :config-test])))
            "Per-type timings should be capped at new limit 5")))))

(deftest get-metrics-includes-buffer-metadata
  (testing "get-metrics includes buffer size, capacity, and dropped count"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 10 :max-timings-per-type 200})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)]
      ;; Dispatch 15 events to cause 5 drops in global timings
      (dotimes [_ 15]
        (let [ctx {:coeffects {:event [:meta-test {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (let [m (ev/get-metrics)]
        (is (= 10 (:timings-buffer-size m))
            "Buffer size should be 10 (at capacity)")
        (is (= 10 (:timings-buffer-capacity m))
            "Buffer capacity should be 10")
        (is (pos? (:timings-dropped m))
            "Dropped count should be positive after overflow")
        ;; 15 events dispatched, global cap is 10, so 5 global drops.
        ;; Per-type cap is 200, so 0 type drops.
        (is (= 5 (:timings-dropped m))
            "Should have dropped exactly 5 timings from global buffer")))))

(deftest timings-dropped-accumulates
  (testing "timings-dropped counter accumulates across multiple overflows"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 5 :max-timings-per-type 3})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)]
      ;; First batch: 10 events
      (dotimes [_ 10]
        (let [ctx {:coeffects {:event [:accum-test {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (let [m1 (ev/get-metrics)
            dropped-1 (:timings-dropped m1)]
        ;; 10 events, global cap 5 -> 5 global drops
        ;; 10 events, per-type cap 3 -> 7 per-type drops
        ;; Total: 12 drops
        (is (= 12 dropped-1)
            "Should have 12 drops after first batch (5 global + 7 per-type)")

        ;; Second batch: 5 more events
        (dotimes [_ 5]
          (let [ctx {:coeffects {:event [:accum-test {}]}
                     :effects {}
                     :queue []
                     :stack []}]
            (-> ctx before-fn after-fn)))
        (let [m2 (ev/get-metrics)
              dropped-2 (:timings-dropped m2)]
          ;; 5 more events, both buffers already full
          ;; 5 global drops + 5 per-type drops = 10 more
          (is (= (+ dropped-1 10) dropped-2)
              "Dropped counter should accumulate across batches"))))))

(deftest reset-metrics-clears-dropped-counter
  (testing "reset-metrics! clears the dropped counter"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 5 :max-timings-per-type 5})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)]
      ;; Cause some drops
      (dotimes [_ 20]
        (let [ctx {:coeffects {:event [:reset-drop-test {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (is (pos? (:timings-dropped (ev/get-metrics)))
          "Should have drops before reset")
      ;; Reset
      (ev/reset-metrics!)
      (let [m (ev/get-metrics)]
        (is (= 0 (:timings-dropped m))
            "Dropped counter should be 0 after reset")
        (is (= 0 (:timings-count m))
            "Timings should be empty after reset")
        (is (= {} (:timings-by-type m))
            "Per-type timings should be empty after reset")))))

(deftest timings-remain-vectors
  (testing "bounded timings remain persistent vectors (not lazy seqs)"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 5 :max-timings-per-type 5})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)]
      ;; Overflow the buffers
      (dotimes [_ 10]
        (let [ctx {:coeffects {:event [:vec-test {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (let [m (ev/get-metrics)]
        (is (vector? (:timings m))
            "Global timings should be a vector after overflow")
        (is (vector? (get-in m [:timings-by-type :vec-test]))
            "Per-type timings should be a vector after overflow")))))

(deftest multiple-event-types-bounded-independently
  (testing "different event types have independent per-type bounds"
    (ev/reset-metrics!)
    (ev/configure-metrics! {:max-timings 5000 :max-timings-per-type 10})
    (let [before-fn (:before ev/metrics)
          after-fn (:after ev/metrics)]
      ;; Dispatch 20 of type-a, 5 of type-b
      (dotimes [_ 20]
        (let [ctx {:coeffects {:event [:type-a {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (dotimes [_ 5]
        (let [ctx {:coeffects {:event [:type-b {}]}
                   :effects {}
                   :queue []
                   :stack []}]
          (-> ctx before-fn after-fn)))
      (let [m (ev/get-metrics)]
        (is (= 10 (count (get-in m [:timings-by-type :type-a])))
            "type-a should be capped at 10")
        (is (= 5 (count (get-in m [:timings-by-type :type-b])))
            "type-b should have all 5 (under cap)")
        ;; Global should have all 25 since cap is 5000
        (is (= 25 (:timings-count m))
            "Global timings should have all 25")))))
