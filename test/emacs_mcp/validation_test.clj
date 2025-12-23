(ns emacs-mcp.validation-test
  "Tests for input validation following CLARITY principle: 'Inputs are guarded'"
  (:require [clojure.test :refer [deftest is testing]]
            [emacs-mcp.validation :as v]))

(deftest test-validate-code
  (testing "Valid code"
    (is (nil? (v/validate-code "(+ 1 2)")))
    (is (nil? (v/validate-code "some code"))))

  (testing "Invalid code - nil"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Code is required"
                          (v/validate-code nil))))

  (testing "Invalid code - blank"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Code cannot be empty or blank"
                          (v/validate-code "   "))))

  (testing "Invalid code - wrong type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Code must be a string"
                          (v/validate-code 123)))))

(deftest test-validate-mode
  (testing "Valid modes"
    (is (nil? (v/validate-mode :silent)))
    (is (nil? (v/validate-mode :explicit)))
    (is (nil? (v/validate-mode nil))))

  (testing "Invalid mode - wrong keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid mode"
                          (v/validate-mode :invalid))))

  (testing "Invalid mode - wrong type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Mode must be a keyword"
                          (v/validate-mode "silent")))))

(deftest test-validate-port
  (testing "Valid ports"
    (is (nil? (v/validate-port 1)))
    (is (nil? (v/validate-port 7888)))
    (is (nil? (v/validate-port 65535)))
    (is (nil? (v/validate-port nil))))

  (testing "Invalid port - out of range low"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Port must be between 1 and 65535"
                          (v/validate-port 0))))

  (testing "Invalid port - out of range high"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Port must be between 1 and 65535"
                          (v/validate-port 99999))))

  (testing "Invalid port - wrong type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Port must be an integer"
                          (v/validate-port "7888")))))

(deftest test-validate-timeout
  (testing "Valid timeouts"
    (is (nil? (v/validate-timeout 1)))
    (is (nil? (v/validate-timeout 5000)))
    (is (nil? (v/validate-timeout nil))))

  (testing "Invalid timeout - negative"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Timeout must be positive"
                          (v/validate-timeout -100))))

  (testing "Invalid timeout - zero"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Timeout must be positive"
                          (v/validate-timeout 0))))

  (testing "Invalid timeout - wrong type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Timeout must be an integer"
                          (v/validate-timeout "5000")))))

(deftest test-validate-buffer-name
  (testing "Valid buffer names"
    (is (nil? (v/validate-buffer-name "*scratch*")))
    (is (nil? (v/validate-buffer-name "myfile.clj")))
    (is (nil? (v/validate-buffer-name nil))))

  (testing "Invalid buffer name - blank"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Buffer name cannot be empty or blank"
                          (v/validate-buffer-name "   "))))

  (testing "Invalid buffer name - wrong type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Buffer name must be a string"
                          (v/validate-buffer-name 123)))))

(deftest test-validate-line-number
  (testing "Valid line numbers"
    (is (nil? (v/validate-line-number 1)))
    (is (nil? (v/validate-line-number 42)))
    (is (nil? (v/validate-line-number nil))))

  (testing "Invalid line number - negative"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Line number must be positive"
                          (v/validate-line-number -5))))

  (testing "Invalid line number - zero"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Line number must be positive"
                          (v/validate-line-number 0))))

  (testing "Invalid line number - wrong type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Line number must be an integer"
                          (v/validate-line-number "42")))))

(deftest test-validate-eval-request
  (testing "Valid eval requests"
    (is (nil? (v/validate-eval-request {:code "(+ 1 2)"})))
    (is (nil? (v/validate-eval-request {:code "(println 1)"
                                        :mode :silent
                                        :port 7888
                                        :timeout 5000}))))

  (testing "Invalid eval request - missing code"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Code is required"
                          (v/validate-eval-request {}))))

  (testing "Invalid eval request - invalid mode"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid mode"
                          (v/validate-eval-request {:code "(+ 1 2)" :mode :wrong}))))

  (testing "Invalid eval request - invalid port"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Port must be between 1 and 65535"
                          (v/validate-eval-request {:code "(+ 1 2)" :port 99999}))))

  (testing "Invalid eval request - invalid timeout"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Timeout must be positive"
                          (v/validate-eval-request {:code "(+ 1 2)" :timeout -100})))))

(deftest test-validate-cider-eval-request
  (testing "Valid CIDER eval request"
    (is (nil? (v/validate-cider-eval-request {:code "(+ 1 2)"}))))

  (testing "Invalid CIDER eval request - missing code"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Code is required"
                          (v/validate-cider-eval-request {})))))

(deftest test-validate-buffer-request
  (testing "Valid buffer request"
    (is (nil? (v/validate-buffer-request {:buffer_name "*scratch*"}))))

  (testing "Invalid buffer request - blank name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Buffer name cannot be empty or blank"
                          (v/validate-buffer-request {:buffer_name "   "})))))

(deftest test-validate-goto-line-request
  (testing "Valid goto-line request"
    (is (nil? (v/validate-goto-line-request {:line 42}))))

  (testing "Invalid goto-line request - negative"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Line number must be positive"
                          (v/validate-goto-line-request {:line -5})))))

(deftest test-wrap-validation-error
  (testing "Wrap validation error with field"
    (let [e (try
              (v/validate-code nil)
              (catch Exception e e))
          wrapped (v/wrap-validation-error e)]
      (is (= "text" (:type wrapped)))
      (is (true? (:isError wrapped)))
      (is (re-find #"Code is required" (:text wrapped)))
      (is (re-find #"field: code" (:text wrapped)))))

  (testing "Wrap validation error with valid values"
    (let [e (try
              (v/validate-mode :invalid)
              (catch Exception e e))
          wrapped (v/wrap-validation-error e)]
      (is (re-find #"Invalid mode" (:text wrapped)))
      (is (re-find #"valid values:" (:text wrapped)))))

  (testing "Wrap validation error with valid range"
    (let [e (try
              (v/validate-port 99999)
              (catch Exception e e))
          wrapped (v/wrap-validation-error e)]
      (is (re-find #"Port must be between" (:text wrapped)))
      (is (re-find #"valid range: 1-65535" (:text wrapped))))))

(deftest test-ex-data-structure
  (testing "Exception data contains expected fields"
    (let [e (try
              (v/validate-mode :invalid)
              (catch Exception e e))
          data (ex-data e)]
      (is (= :validation (:type data)))
      (is (= :mode (:field data)))
      (is (= :invalid-value (:reason data)))
      (is (= #{:silent :explicit} (:valid data)))
      (is (= :invalid (:received data))))))
