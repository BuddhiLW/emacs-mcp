(ns hive-mcp.nrepl.classloader-gc-test
  "Tests for the DynamicClassLoader mitigation middleware.

   Validates that:
   1. Session-less evals get pinned to a shared session
   2. Evals with explicit sessions pass through unmodified
   3. Non-eval ops pass through unmodified
   4. Session rotation occurs at the configured threshold
   5. The status API reports correct state"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.nrepl.classloader-gc :as classloader-gc]
            [nrepl.transport]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(def ^:private captured-messages (atom []))

(defn- make-mock-transport
  "Create a mock transport that records sent messages."
  []
  (reify nrepl.transport/Transport
    (recv [_] nil)
    (recv [_ _timeout] nil)
    (send [_ response]
      (swap! captured-messages conj response))))

(defn- make-recording-handler
  "Create a handler that records messages passed to it and sends a response
   with the session from the message."
  []
  (let [received (atom [])]
    {:handler (fn [{:keys [session transport] :as msg}]
                (swap! received conj msg)
                ;; Simulate nREPL responding with the session ID
                (when transport
                  (nrepl.transport/send transport
                                        {:session (or session "auto-created-session-123")
                                         :status ["done"]
                                         :value "nil"})))
     :received received}))

;; Reset shared state between tests
(use-fixtures :each
  (fn [f]
    ;; Reset the shared session ID atom via force-rotate
    (classloader-gc/force-rotate!)
    (reset! captured-messages [])
    (f)
    (classloader-gc/force-rotate!)
    (reset! captured-messages [])))

;; =============================================================================
;; Core Middleware Behavior
;; =============================================================================

(deftest session-less-eval-captures-shared-session-test
  (testing "First session-less eval captures the session ID for reuse"
    (let [{:keys [handler received]} (make-recording-handler)
          middleware (classloader-gc/wrap-shared-classloader handler)
          transport (make-mock-transport)]
      ;; First session-less eval
      (middleware {:op "eval" :code "(+ 1 2)" :transport transport})

      ;; The handler should have received a message (without :session initially,
      ;; but wrapped with capturing transport)
      (is (= 1 (count @received))
          "handler received the message")

      ;; After the first eval, the status should show the captured session
      (let [status (classloader-gc/status)]
        (is (= "auto-created-session-123" (:shared-session-id status))
            "shared session ID captured from response")
        (is (= 1 (:eval-count status))
            "eval counter is 1")))))

(deftest session-less-eval-reuses-shared-session-test
  (testing "Subsequent session-less evals inject the shared session ID"
    (let [{:keys [handler received]} (make-recording-handler)
          middleware (classloader-gc/wrap-shared-classloader handler)
          transport (make-mock-transport)]
      ;; First eval: captures session
      (middleware {:op "eval" :code "(+ 1 2)" :transport transport})

      ;; Second eval: should inject the shared session
      (middleware {:op "eval" :code "(+ 3 4)" :transport transport})

      (is (= 2 (count @received))
          "handler received both messages")

      ;; The second message should have the shared session injected
      (let [second-msg (nth @received 1)]
        (is (= "auto-created-session-123" (:session second-msg))
            "shared session ID injected into second eval"))

      ;; Eval count should be 2
      (is (= 2 (:eval-count (classloader-gc/status)))
          "eval counter is 2"))))

(deftest explicit-session-passes-through-test
  (testing "Evals with explicit :session pass through unmodified"
    (let [{:keys [handler received]} (make-recording-handler)
          middleware (classloader-gc/wrap-shared-classloader handler)
          transport (make-mock-transport)]
      ;; Eval with explicit session (CIDER pattern)
      (middleware {:op "eval" :code "(+ 1 2)" :session "cider-session-abc" :transport transport})

      (is (= 1 (count @received))
          "handler received the message")

      ;; The session should be unchanged
      (let [msg (first @received)]
        (is (= "cider-session-abc" (:session msg))
            "explicit session preserved")))))

(deftest non-eval-ops-pass-through-test
  (testing "Non-eval ops pass through without session manipulation"
    (let [{:keys [handler received]} (make-recording-handler)
          middleware (classloader-gc/wrap-shared-classloader handler)
          transport (make-mock-transport)]
      ;; Non-eval op
      (middleware {:op "describe" :transport transport})

      (is (= 1 (count @received))
          "handler received the message")

      ;; No session should be injected
      (let [msg (first @received)]
        (is (nil? (:session msg))
            "no session injected for non-eval ops")))))

(deftest clone-op-passes-through-test
  (testing "Clone op (used by CIDER) passes through without session manipulation"
    (let [{:keys [handler received]} (make-recording-handler)
          middleware (classloader-gc/wrap-shared-classloader handler)
          transport (make-mock-transport)]
      (middleware {:op "clone" :transport transport})

      (is (= 1 (count @received))
          "handler received the clone message")
      (is (nil? (:session (first @received)))
          "no session injected for clone op"))))

;; =============================================================================
;; Session Rotation
;; =============================================================================

(deftest session-rotation-at-threshold-test
  (testing "Shared session rotates after reaching eval threshold"
    (classloader-gc/set-rotation-threshold! 3)
    (let [{:keys [handler received]} (make-recording-handler)
          middleware (classloader-gc/wrap-shared-classloader handler)
          transport (make-mock-transport)]
      ;; First eval: captures session
      (middleware {:op "eval" :code "1" :transport transport})
      (is (= "auto-created-session-123" (:shared-session-id (classloader-gc/status))))

      ;; Evals 2 and 3: still using same session
      (middleware {:op "eval" :code "2" :transport transport})
      (middleware {:op "eval" :code "3" :transport transport})

      ;; After eval 3, rotation should have happened (counter >= threshold)
      ;; The session should be nil (cleared by rotation)
      (let [status (classloader-gc/status)]
        (is (nil? (:shared-session-id status))
            "session rotated after reaching threshold")
        (is (= 0 (:eval-count status))
            "eval counter reset after rotation")))
    ;; Restore default threshold
    (classloader-gc/set-rotation-threshold! 10000)))

;; =============================================================================
;; Status API
;; =============================================================================

(deftest status-reports-correct-state-test
  (testing "Status API reports classloader info"
    (let [status (classloader-gc/status)]
      (is (contains? status :shared-session-id))
      (is (contains? status :eval-count))
      (is (contains? status :rotation-threshold))
      (is (contains? status :classloader-info))
      (is (contains? (:classloader-info status) :chain-depth))
      (is (contains? (:classloader-info status) :dynamic-classloader-count)))))

(deftest force-rotate-clears-session-test
  (testing "force-rotate! clears the shared session"
    (let [{:keys [handler]} (make-recording-handler)
          middleware (classloader-gc/wrap-shared-classloader handler)
          transport (make-mock-transport)]
      ;; Establish a shared session
      (middleware {:op "eval" :code "(+ 1 2)" :transport transport})
      (is (some? (:shared-session-id (classloader-gc/status)))
          "session established before rotate")

      ;; Force rotate
      (classloader-gc/force-rotate!)

      (is (nil? (:shared-session-id (classloader-gc/status)))
          "session cleared after force-rotate")
      (is (= 0 (:eval-count (classloader-gc/status)))
          "eval counter reset after force-rotate"))))

;; =============================================================================
;; Classloader Counting
;; =============================================================================

(deftest count-dynamic-classloaders-returns-valid-map-test
  (testing "count-dynamic-classloaders returns expected structure"
    (let [info (classloader-gc/count-dynamic-classloaders)]
      (is (map? info))
      (is (nat-int? (:chain-depth info)))
      (is (nat-int? (:dynamic-classloader-count info)))
      (is (string? (:thread-context-classloader info))))))
