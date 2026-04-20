(ns hive-mcp.chroma.gate-property-test
  "Property-based + golden tests for the Chroma concurrency gate.

   Pins the anti-hang invariants:
   1. Gate-protected operations always terminate (timeout, never hang)
   2. Concurrent reads don't exceed permit limit
   3. Write serialization holds under contention
   4. Embedding gate bounds Ollama fan-out

   Mutation targets:
   - Removing deref-gate timeout → test detects hang (P1, P2)
   - Removing semaphore acquire → test detects over-concurrency (P3)
   - Removing gate entirely → golden shape test fails (G1)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-dsl.gate :as g]
            [hive-dsl.result :as r]
            [hive-mcp.chroma.gate :as cg]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-permits
  "Generator for valid permit counts (1-8)."
  (gen/choose 1 8))

(def gen-timeout-ms
  "Generator for timeout values (50-500ms for fast tests)."
  (gen/choose 50 500))

(def gen-gate-config
  "Generator for gate configurations."
  (gen/let [permits gen-permits
            timeout gen-timeout-ms
            name (gen/fmap #(str "test-gate-" %) gen/nat)]
    {:permits permits :timeout-ms timeout :name name}))

(def gen-work-ms
  "Generator for simulated work duration (1-50ms)."
  (gen/choose 1 50))

;; =============================================================================
;; P1: Totality — gate-run never hangs, always returns Result
;; =============================================================================

(defspec p1-gate-run-always-returns-result 100
  (prop/for-all [config gen-gate-config
                 v gen/any-printable]
    (let [gate (g/gate config)
          result (g/gate-run gate (fn [] v))]
      (or (r/ok? result) (r/err? result)))))

(defspec p1-gate-run-timeout-returns-err 50
  (prop/for-all [config gen-gate-config]
    (let [gate (g/gate (assoc config :permits 1 :timeout-ms 50))
          entered (promise)
          ;; Exhaust the single permit, signal when acquired
          blocker (future (g/gate-run! gate (fn [] (deliver entered true) (Thread/sleep 500))))]
      (try
        ;; Wait for blocker to actually hold the permit
        (deref entered 1000 nil)
        ;; Second call should timeout, not hang
        (let [result (g/gate-run gate (fn [] :unreachable))]
          (r/err? result))
        (finally
          (future-cancel blocker))))))

;; =============================================================================
;; P2: Bounded termination — deref-gate always terminates within timeout
;; =============================================================================

(defspec p2-deref-gate-terminates 50
  (prop/for-all [config gen-gate-config]
    (let [gate (g/gate (assoc config :timeout-ms 100))
          ;; Promise that never delivers — simulates hung Chroma
          hung-promise (promise)
          start (System/currentTimeMillis)]
      (try
        (g/deref-gate gate hung-promise 100)
        false ;; Should have thrown
        (catch Exception e
          (let [elapsed (- (System/currentTimeMillis) start)]
            ;; Must terminate within 2x timeout (generous for CI jitter)
            (< elapsed 500)))))))

(defspec p2-deref-gate-returns-value-on-success 100
  (prop/for-all [config gen-gate-config
                 v gen/nat]
    (let [gate (g/gate config)
          p (promise)]
      (deliver p v)
      (= v (g/deref-gate gate p)))))

;; =============================================================================
;; P3: Concurrency bound — active permits never exceed configured max
;; =============================================================================

(defspec p3-concurrency-bounded 30
  (prop/for-all [permits (gen/choose 1 4)
                 n-workers (gen/choose 2 12)]
    (let [gate (g/gate {:permits permits :timeout-ms 5000 :name "p3-test"})
          peak (atom 0)
          active (atom 0)]
      ;; Launch n-workers all trying to enter the gate
      (let [futures (doall
                      (for [_ (range n-workers)]
                        (future
                          (try
                            (g/gate-run! gate
                              (fn []
                                (let [cur (swap! active inc)]
                                  (swap! peak max cur)
                                  (Thread/sleep 10)
                                  (swap! active dec))))
                            (catch Exception _ nil)))))]
        (doseq [f futures] (deref f 10000 nil))
        ;; Peak concurrent should never exceed permits
        (<= @peak permits)))))

;; =============================================================================
;; P4: Fairness — with-gate and gate-run! produce same result for pure fns
;; =============================================================================

(defspec p4-with-gate-matches-gate-run 100
  (prop/for-all [config gen-gate-config
                 v gen/nat]
    (let [gate (g/gate config)]
      (= (g/with-gate gate (inc v))
         (g/gate-run! gate (fn [] (inc v)))))))

;; =============================================================================
;; G1: Golden — gate-stats shape is stable
;; =============================================================================

(deftest g1-chroma-gate-stats-shape
  (testing "gate-stats returns expected structure for all three gates"
    (let [stats (cg/gate-stats)]
      (is (= #{:read :write :embed} (set (keys stats))))
      (doseq [[gate-key gate-stat] stats]
        (testing (str gate-key " shape")
          (is (contains? gate-stat :name))
          (is (contains? gate-stat :permits))
          (is (contains? gate-stat :available))
          (is (contains? gate-stat :queue-length))
          (is (contains? gate-stat :state))
          (is (= :started (:state gate-stat)))
          (is (pos-int? (:permits gate-stat)))
          (is (<= 0 (:available gate-stat) (:permits gate-stat))))))))

(deftest g1-chroma-gate-permits-config
  (testing "gate permits match design: read=4, write=1, embed=2"
    (let [stats (cg/gate-stats)]
      (is (= 4 (get-in stats [:read :permits])))
      (is (= 1 (get-in stats [:write :permits])))
      (is (= 2 (get-in stats [:embed :permits]))))))

;; =============================================================================
;; M1: Mutation targets — tests that break when gate is removed/weakened
;; =============================================================================

(deftest m1-deref-read-has-timeout
  (testing "deref-read throws on hung promise instead of blocking forever"
    (let [hung (promise)
          start (System/currentTimeMillis)]
      (is (thrown-with-msg? Exception #"timed out|full"
            (cg/deref-read hung 200)))
      (is (< (- (System/currentTimeMillis) start) 1000)
          "Must terminate promptly, not hang"))))

(deftest m1-deref-write-has-timeout
  (testing "deref-write throws on hung promise instead of blocking forever"
    (let [hung (promise)
          start (System/currentTimeMillis)]
      (is (thrown-with-msg? Exception #"timed out|full"
            (cg/deref-write hung 200)))
      (is (< (- (System/currentTimeMillis) start) 1000)
          "Must terminate promptly, not hang"))))

(deftest m1-embedding-gate-has-concurrency-bound
  (testing "with-embedding-gate limits concurrent Ollama calls"
    (let [active (atom 0)
          peak (atom 0)
          ;; Launch 6 concurrent "embedding" calls
          futures (doall
                    (for [_ (range 6)]
                      (future
                        (try
                          (cg/with-embedding-gate
                            (let [cur (swap! active inc)]
                              (swap! peak max cur)
                              (Thread/sleep 50)
                              (swap! active dec)))
                          (catch Exception _ nil)))))]
      (doseq [f futures] (deref f 10000 nil))
      ;; embed-gate has 2 permits
      (is (<= @peak 2) "Embedding concurrency must be bounded to 2"))))
