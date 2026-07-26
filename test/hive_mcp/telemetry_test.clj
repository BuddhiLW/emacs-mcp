(ns hive-mcp.telemetry-test
  "Tests for telemetry functionality.

   Log events are captured through a timbre appender, not by redefining
   log/info and friends — those are macros, and `with-redefs` on a macro
   never reaches the expanded call site."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.telemetry.core :as telemetry]
            [hive-mcp.test.stub.log-capture :as cap :refer [with-captured-logs]]
            [taoensso.timbre :as log]))

(deftest test-with-timing
  (testing "with-timing macro captures operation duration"
    (with-captured-logs logs
      (let [result (telemetry/with-timing "test-operation"
                     (Thread/sleep 10)
                     42)]
        (is (= 42 result) "Returns the result of body execution")
        (let [timing (cap/events-of logs :timing)]
          (is (= 1 (count timing)) "Logs exactly once")
          (is (= "test-operation" (:operation (:data (first timing))))
              "Includes operation name")
          (is (>= (:ms (:data (first timing))) 10) "Duration is at least 10ms"))))))

(deftest test-log-eval-request
  (testing "log-eval-request logs structured data"
    (with-captured-logs logs
      (telemetry/log-eval-request {:code "(+ 1 2 3)"
                                   :mode :elisp
                                   :metadata {:user "alice"}})
      (let [data (cap/event-data logs :eval-request)]
        (is (some? data) "an :eval-request event is logged")
        (is (= :elisp (:mode data)))
        (is (= 9 (:code-length data)))
        (is (= "(+ 1 2 3)" (:code-preview data)))
        (is (= "alice" (:user data)))))))

(deftest test-log-eval-request-truncation
  (testing "log-eval-request truncates long code"
    (let [long-code (apply str (repeat 100 "x"))]
      (with-captured-logs logs
        (telemetry/log-eval-request {:code long-code :mode :test})
        (let [data (cap/event-data logs :eval-request)]
          (is (= 100 (:code-length data)))
          (is (= 53 (count (:code-preview data)))) ; 50 chars + "..."
          (is (.endsWith (:code-preview data) "...")))))))

(deftest test-log-eval-result-success
  (testing "log-eval-result logs success"
    (with-captured-logs logs
      (telemetry/log-eval-result {:success true
                                  :duration-ms 42
                                  :result-length 100
                                  :metadata {:session "123"}})
      (let [data (cap/event-data logs :eval-success)]
        (is (some? data) "an :eval-success event is logged")
        (is (= 42 (:duration-ms data)))
        (is (= 100 (:result-length data)))
        (is (= "123" (:session data)))))))

(deftest test-log-eval-result-failure
  (testing "log-eval-result logs failure with warning"
    (with-captured-logs logs
      (telemetry/log-eval-result {:success false
                                  :error "Syntax error"
                                  :duration-ms 15})
      (let [failure (first (cap/events-of logs :eval-failure))]
        (is (some? failure) "an :eval-failure event is logged")
        (is (= :warn (:level failure)) "failures log at :warn")
        (is (= "Syntax error" (:error (:data failure))))
        (is (= 15 (:duration-ms (:data failure))))))))

(deftest test-log-eval-exception
  (testing "log-eval-exception captures exception details"
    (let [test-exception (Exception. "Test error")]
      (with-captured-logs logs
        (telemetry/log-eval-exception {:exception test-exception
                                       :operation "test-op"
                                       :metadata {:context "test"}})
        (let [ev (first (cap/events-of logs :eval-exception))]
          (is (some? ev) "an :eval-exception event is logged")
          (is (= :error (:level ev)) "exceptions log at :error")
          (is (= "test-op" (:operation (:data ev))))
          (is (= "java.lang.Exception" (:exception-type (:data ev))))
          (is (= "Test error" (:exception-message (:data ev))))
          (is (= "test" (:context (:data ev))))
          (is (= test-exception (:exception ev))
              "the throwable itself rides along with the event"))))))

(deftest test-with-eval-telemetry-success
  (testing "with-eval-telemetry wraps successful evaluation"
    (with-captured-logs logs
      (let [result (telemetry/with-eval-telemetry :test "(+ 1 2)" {:user "bob"}
                     {:success true :result "3"})]
        (is (= {:success true :result "3"} result))
        (let [request (first (cap/events-of logs :eval-request))
              success (first (cap/events-of logs :eval-success))]
          (is (some? request) "logs the request")
          (is (some? success) "logs the success")
          (is (= "bob" (get-in request [:data :user])))
          (is (number? (get-in success [:data :duration-ms]))))))))

(deftest test-with-eval-telemetry-exception
  (testing "with-eval-telemetry catches and logs exceptions"
    (with-captured-logs logs
      (is (thrown? Exception
                   (telemetry/with-eval-telemetry :test "(error)" nil
                     (throw (Exception. "Eval failed")))))
      (is (seq (cap/events-of logs :eval-request)) "logs the request")
      (is (seq (cap/events-of logs :eval-exception)) "logs the exception")
      (is (seq (cap/events-of logs :eval-failure)) "logs the failure"))))

(deftest test-configure-logging
  (testing "configure-logging sets log level"
    (telemetry/configure-logging! {:level :warn})
    (is (= :warn (:min-level log/*config*))))

  (testing "configure-logging with default level"
    (telemetry/configure-logging!)
    (is (= :info (:min-level log/*config*)))))
