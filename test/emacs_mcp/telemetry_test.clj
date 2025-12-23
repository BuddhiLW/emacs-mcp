(ns emacs-mcp.telemetry-test
  "Tests for telemetry functionality."
  (:require [clojure.test :refer :all]
            [emacs-mcp.telemetry :as telemetry]
            [taoensso.timbre :as log]))

(deftest test-with-timing
  (testing "with-timing macro captures operation duration"
    (let [captured-logs (atom [])
          original-log-fn log/*config*]
      ;; Capture log output
      (with-redefs [log/info (fn [event data]
                               (swap! captured-logs conj {:event event :data data}))]
        (let [result (telemetry/with-timing "test-operation"
                       (Thread/sleep 10)
                       42)]
          (is (= 42 result) "Returns the result of body execution")
          (is (= 1 (count @captured-logs)) "Logs exactly once")
          (let [{:keys [event data]} (first @captured-logs)]
            (is (= :timing event) "Logs with :timing event")
            (is (= "test-operation" (:operation data)) "Includes operation name")
            (is (>= (:ms data) 10) "Duration is at least 10ms")))))))

(deftest test-log-eval-request
  (testing "log-eval-request logs structured data"
    (let [captured-logs (atom [])]
      (with-redefs [log/info (fn [event data]
                               (swap! captured-logs conj {:event event :data data}))]
        (telemetry/log-eval-request {:code "(+ 1 2 3)"
                                     :mode :elisp
                                     :metadata {:user "alice"}})
        (let [{:keys [event data]} (first @captured-logs)]
          (is (= :eval-request event))
          (is (= :elisp (:mode data)))
          (is (= 9 (:code-length data)))
          (is (= "(+ 1 2 3)" (:code-preview data)))
          (is (= "alice" (:user data))))))))

(deftest test-log-eval-request-truncation
  (testing "log-eval-request truncates long code"
    (let [captured-logs (atom [])
          long-code (apply str (repeat 100 "x"))]
      (with-redefs [log/info (fn [event data]
                               (swap! captured-logs conj {:event event :data data}))]
        (telemetry/log-eval-request {:code long-code :mode :test})
        (let [{:keys [data]} (first @captured-logs)]
          (is (= 100 (:code-length data)))
          (is (= 53 (count (:code-preview data)))) ; 50 chars + "..."
          (is (.endsWith (:code-preview data) "...")))))))

(deftest test-log-eval-result-success
  (testing "log-eval-result logs success"
    (let [captured-logs (atom [])]
      (with-redefs [log/info (fn [event data]
                               (swap! captured-logs conj {:event event :data data}))]
        (telemetry/log-eval-result {:success true
                                    :duration-ms 42
                                    :result-length 100
                                    :metadata {:session "123"}})
        (let [{:keys [event data]} (first @captured-logs)]
          (is (= :eval-success event))
          (is (= 42 (:duration-ms data)))
          (is (= 100 (:result-length data)))
          (is (= "123" (:session data))))))))

(deftest test-log-eval-result-failure
  (testing "log-eval-result logs failure with warning"
    (let [captured-logs (atom [])]
      (with-redefs [log/warn (fn [event data]
                               (swap! captured-logs conj {:event event :data data}))]
        (telemetry/log-eval-result {:success false
                                    :error "Syntax error"
                                    :duration-ms 15})
        (let [{:keys [event data]} (first @captured-logs)]
          (is (= :eval-failure event))
          (is (= "Syntax error" (:error data)))
          (is (= 15 (:duration-ms data))))))))

(deftest test-log-eval-exception
  (testing "log-eval-exception captures exception details"
    (let [captured-logs (atom [])
          test-exception (Exception. "Test error")]
      (with-redefs [log/error (fn [event data ex]
                                (swap! captured-logs conj {:event event
                                                           :data data
                                                           :exception ex}))]
        (telemetry/log-eval-exception {:exception test-exception
                                       :operation "test-op"
                                       :metadata {:context "test"}})
        (let [{:keys [event data exception]} (first @captured-logs)]
          (is (= :eval-exception event))
          (is (= "test-op" (:operation data)))
          (is (= "java.lang.Exception" (:exception-type data)))
          (is (= "Test error" (:exception-message data)))
          (is (= "test" (:context data)))
          (is (= test-exception exception)))))))

(deftest test-with-eval-telemetry-success
  (testing "with-eval-telemetry wraps successful evaluation"
    (let [captured-logs (atom [])]
      (with-redefs [log/info (fn [event data]
                               (swap! captured-logs conj {:event event :data data}))]
        (let [result (telemetry/with-eval-telemetry :test "(+ 1 2)" {:user "bob"}
                       {:success true :result "3"})]
          (is (= {:success true :result "3"} result))
          (is (= 2 (count @captured-logs))) ; request + success
          (let [request-log (first @captured-logs)
                success-log (second @captured-logs)]
            (is (= :eval-request (:event request-log)))
            (is (= :eval-success (:event success-log)))
            (is (= "bob" (get-in request-log [:data :user])))
            (is (number? (get-in success-log [:data :duration-ms])))))))))

(deftest test-with-eval-telemetry-exception
  (testing "with-eval-telemetry catches and logs exceptions"
    (let [captured-logs (atom [])]
      (with-redefs [log/info (fn [event data]
                               (swap! captured-logs conj {:event event :data data}))
                    log/warn (fn [event data]
                               (swap! captured-logs conj {:event event :data data}))
                    log/error (fn [event data ex]
                                (swap! captured-logs conj {:event event :data data}))]
        (is (thrown? Exception
                     (telemetry/with-eval-telemetry :test "(error)" nil
                       (throw (Exception. "Eval failed")))))
        (is (>= (count @captured-logs) 2)) ; request + exception + failure
        (let [logs-by-event (group-by :event @captured-logs)]
          (is (contains? logs-by-event :eval-request))
          (is (contains? logs-by-event :eval-exception))
          (is (contains? logs-by-event :eval-failure)))))))

(deftest test-configure-logging
  (testing "configure-logging sets log level"
    (telemetry/configure-logging! {:level :warn})
    (is (= :warn (:level @log/*config*))))

  (testing "configure-logging with default level"
    (telemetry/configure-logging!)
    (is (= :info (:level @log/*config*)))))

(comment
  ;; Run tests
  (run-tests)

  ;; Run specific test
  (test-with-timing)
  (test-log-eval-request)
  (test-with-eval-telemetry-success))
