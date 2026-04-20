(ns hive-mcp.elisp-test
  "Comprehensive unit tests for the hive-mcp.emacs.elisp namespace.

   Tests cover all public functions:
   - require-and-call-json: Generate elisp with require, call, JSON-encode
   - require-and-call-text: Generate elisp with require, call, return text
   - require-and-call: Generate elisp with require, call, error on failure
   - fboundp-call-json: Generate elisp for bound function with JSON encoding
   - wrap-progn: Combine multiple elisp strings in progn
   - format-elisp: Template-based elisp generation
   - emit: clojure-elisp integration (fallback to pr-str)
   - emit-forms: Multiple form emission"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-mcp.emacs.elisp :as el]))

;; =============================================================================
;; Test require-and-call-json
;; =============================================================================

(deftest test-require-and-call-json-no-args
  (testing "Basic usage without arguments"
    (let [result (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status)]
      ;; Should contain progn wrapper
      (is (str/starts-with? result "(progn"))
      ;; Should require the feature
      (is (str/includes? result "(require 'hive-mcp-magit nil t)"))
      ;; Should check if function is bound
      (is (str/includes? result "(fboundp 'hive-mcp-magit-api-status)"))
      ;; Should call the function
      (is (str/includes? result "(hive-mcp-magit-api-status)"))
      ;; Should JSON-encode the result
      (is (str/includes? result "(json-encode (hive-mcp-magit-api-status))"))
      ;; Should have error handling
      (is (str/includes? result "hive-mcp-magit not loaded")))))

(deftest test-require-and-call-json-single-arg
  (testing "Usage with single numeric argument"
    (let [result (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-log 10)]
      ;; Should include the argument
      (is (str/includes? result "(hive-mcp-magit-api-log 10)"))
      (is (str/includes? result "(json-encode (hive-mcp-magit-api-log 10))"))))

  (testing "Usage with single string argument"
    (let [result (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-eval "(+ 1 2)")]
      ;; String should be properly quoted
      (is (str/includes? result "(hive-mcp-cider-eval \"(+ 1 2)\")"))))

  (testing "Usage with single symbol argument (should be quoted)"
    (let [result (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-diff 'staged)]
      ;; Symbol should be quoted with '
      (is (str/includes? result "(hive-mcp-magit-api-diff 'staged)")))))

(deftest test-require-and-call-json-multiple-args
  (testing "Usage with multiple arguments of different types"
    (let [result (el/require-and-call-json 'hive-mcp-test 'test-fn "hello" 42 'world)]
      ;; All args should be present with proper formatting
      (is (str/includes? result "(test-fn \"hello\" 42 'world)")))))

(deftest test-require-and-call-json-nil-arg
  (testing "Usage with nil argument"
    (let [result (el/require-and-call-json 'hive-mcp-test 'test-fn nil)]
      (is (str/includes? result "(test-fn nil)")))))

(deftest test-require-and-call-json-keyword-arg
  (testing "Usage with keyword argument"
    (let [result (el/require-and-call-json 'hive-mcp-test 'test-fn :silent)]
      ;; Keywords should become :keyword in elisp
      (is (str/includes? result "(test-fn :silent)")))))

(deftest test-require-and-call-json-vector-arg
  (testing "Usage with vector argument (becomes quoted list)"
    (let [result (el/require-and-call-json 'hive-mcp-test 'test-fn ["a" "b"])]
      ;; Vector should become quoted list
      (is (str/includes? result "'(\"a\" \"b\")")))))

;; =============================================================================
;; Test require-and-call-text
;; =============================================================================

(deftest test-require-and-call-text-no-args
  (testing "Basic usage without arguments"
    (let [result (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-diff)]
      ;; Should contain progn wrapper
      (is (str/starts-with? result "(progn"))
      ;; Should require the feature
      (is (str/includes? result "(require 'hive-mcp-magit nil t)"))
      ;; Should NOT JSON-encode (plain text return)
      (is (not (str/includes? result "json-encode")))
      ;; Should have text error message
      (is (str/includes? result "\"Error: hive-mcp-magit not loaded\"")))))

(deftest test-require-and-call-text-with-args
  (testing "Usage with symbol argument"
    (let [result (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-diff 'staged)]
      (is (str/includes? result "(hive-mcp-magit-api-diff 'staged)")))))

(deftest test-require-and-call-text-multiple-args
  (testing "Usage with multiple arguments"
    (let [result (el/require-and-call-text 'hive-mcp-test 'test-fn "file.txt" 100 'mode)]
      (is (str/includes? result "(test-fn \"file.txt\" 100 'mode)")))))

;; =============================================================================
;; Test require-and-call
;; =============================================================================

(deftest test-require-and-call-basic
  (testing "Basic usage without arguments"
    (let [result (el/require-and-call 'hive-mcp-magit 'hive-mcp-magit-api-stage)]
      ;; Should contain progn wrapper
      (is (str/starts-with? result "(progn"))
      ;; Should require the feature
      (is (str/includes? result "(require 'hive-mcp-magit nil t)"))
      ;; Should check fboundp
      (is (str/includes? result "(fboundp 'hive-mcp-magit-api-stage)"))
      ;; Should call the function
      (is (str/includes? result "(hive-mcp-magit-api-stage)"))
      ;; Should use (error ...) for failure (not json-encode)
      (is (str/includes? result "(error \"hive-mcp-magit not loaded\")"))
      ;; Should NOT have json-encode
      (is (not (str/includes? result "json-encode"))))))

(deftest test-require-and-call-with-args
  (testing "Usage with arguments"
    (let [result (el/require-and-call 'hive-mcp-magit 'hive-mcp-magit-api-stage ["file1.txt" "file2.txt"])]
      (is (str/includes? result "(hive-mcp-magit-api-stage '(\"file1.txt\" \"file2.txt\"))")))))

;; =============================================================================
;; Test fboundp-call-json
;; =============================================================================

(deftest test-fboundp-call-json-no-args
  (testing "Basic usage without arguments"
    (let [result (el/fboundp-call-json 'hive-mcp-api-status)]
      ;; Should check fboundp first
      (is (str/starts-with? result "(if (fboundp 'hive-mcp-api-status)"))
      ;; Should JSON-encode result
      (is (str/includes? result "(json-encode (hive-mcp-api-status))"))
      ;; Should have error case
      (is (str/includes? result "hive-mcp-api-status not available")))))

(deftest test-fboundp-call-json-with-args
  (testing "Usage with arguments"
    (let [result (el/fboundp-call-json 'hive-mcp-api-get "buffer-name" 42)]
      (is (str/includes? result "(hive-mcp-api-get \"buffer-name\" 42)")))))

(deftest test-fboundp-call-json-symbol-arg
  (testing "Usage with symbol argument"
    (let [result (el/fboundp-call-json 'hive-mcp-get-mode 'text-mode)]
      (is (str/includes? result "(hive-mcp-get-mode 'text-mode)")))))

;; =============================================================================
;; Test wrap-progn
;; =============================================================================

(deftest test-wrap-progn-single
  (testing "Wrap single elisp string"
    (let [result (el/wrap-progn "(message \"hello\")")]
      (is (str/starts-with? result "(progn"))
      (is (str/includes? result "(message \"hello\")"))
      (is (str/ends-with? result ")")))))

(deftest test-wrap-progn-multiple
  (testing "Wrap multiple elisp strings"
    (let [result (el/wrap-progn "(setq x 1)" "(setq y 2)" "(+ x y)")]
      (is (str/starts-with? result "(progn"))
      (is (str/includes? result "(setq x 1)"))
      (is (str/includes? result "(setq y 2)"))
      (is (str/includes? result "(+ x y)")))))

(deftest test-wrap-progn-empty
  (testing "Wrap with no arguments"
    (let [result (el/wrap-progn)]
      (is (str/starts-with? result "(progn"))
      (is (str/ends-with? result ")")))))

;; =============================================================================
;; Test format-elisp
;; =============================================================================

(deftest test-format-elisp-integer
  (testing "Format with integer placeholder"
    (is (= "(goto-line 42)" (el/format-elisp "(goto-line %d)" 42)))))

(deftest test-format-elisp-string
  (testing "Format with string placeholder"
    (is (= "(switch-to-buffer \"*scratch*\")"
           (el/format-elisp "(switch-to-buffer %s)" (pr-str "*scratch*"))))))

(deftest test-format-elisp-multiple
  (testing "Format with multiple placeholders"
    (is (= "(set-buffer-modified-p t) ; line 100"
           (el/format-elisp "(set-buffer-modified-p %s) ; line %d" "t" 100)))))

(deftest test-format-elisp-simple
  (testing "Format with simple string"
    (is (= "(buffer-name)" (el/format-elisp "(buffer-name)")))))

;; =============================================================================
;; Test emit and emit-forms
;; =============================================================================

(deftest test-emit-simple-form
  (testing "Emit simple function call"
    (let [result (el/emit '(buffer-name))]
      ;; Should at minimum be a string representation
      (is (string? result))
      ;; Should contain the form
      (is (str/includes? result "buffer-name")))))

(deftest test-emit-with-args
  (testing "Emit form with arguments"
    (let [result (el/emit '(+ 1 2))]
      (is (string? result))
      (is (str/includes? result "+"))
      (is (str/includes? result "1"))
      (is (str/includes? result "2")))))

(deftest test-emit-conditional
  (testing "Emit conditional form"
    (let [result (el/emit '(if (> x 0) "yes" "no"))]
      (is (string? result))
      (is (str/includes? result "if")))))

(deftest test-emit-string
  (testing "Emit simple string"
    (let [result (el/emit "hello world")]
      (is (string? result))
      (is (str/includes? result "hello world")))))

(deftest test-emit-number
  (testing "Emit number"
    (let [result (el/emit 42)]
      (is (= "42" result)))))

(deftest test-emit-forms-multiple
  (testing "Emit multiple forms joined by newlines"
    (let [result (el/emit-forms ['(setq x 1) '(setq y 2) '(+ x y)])]
      (is (string? result))
      ;; Should have newlines between forms
      (is (str/includes? result "\n\n"))
      (is (str/includes? result "setq")))))

(deftest test-emit-forms-single
  (testing "Emit single form in list"
    (let [result (el/emit-forms ['(buffer-name)])]
      (is (string? result))
      (is (str/includes? result "buffer-name")))))

(deftest test-emit-forms-empty
  (testing "Emit empty list"
    (let [result (el/emit-forms [])]
      (is (= "" result)))))

;; =============================================================================
;; Edge Cases and Integration Tests
;; =============================================================================

(deftest test-elisp-quote-via-require-and-call-json
  (testing "Symbol quoting"
    (let [result (el/require-and-call-json 'test-feature 'test-fn 'my-symbol)]
      ;; Symbol should be quoted as 'my-symbol
      (is (str/includes? result "'my-symbol"))))

  (testing "String escaping"
    (let [result (el/require-and-call-json 'test-feature 'test-fn "hello \"world\"")]
      ;; String with quotes should be properly escaped
      (is (str/includes? result "\"hello \\\"world\\\"\"")))))

(deftest test-number-formatting
  (testing "Integer formatting"
    (let [result (el/require-and-call-json 'test 'fn 42)]
      (is (str/includes? result "(fn 42)"))))

  (testing "Float formatting"
    (let [result (el/require-and-call-json 'test 'fn 3.14)]
      (is (str/includes? result "(fn 3.14)")))))

(deftest test-keyword-formatting
  (testing "Simple keyword"
    (let [result (el/require-and-call-json 'test 'fn :mode)]
      (is (str/includes? result "(fn :mode)"))))

  (testing "Namespaced keyword value (only name part used)"
    (let [result (el/require-and-call-json 'test 'fn :my-ns/mode)]
      ;; Should only use the name part for elisp
      (is (str/includes? result ":mode")))))

(deftest test-vector-formatting
  (testing "Empty vector"
    (let [result (el/require-and-call-json 'test 'fn [])]
      (is (str/includes? result "'()"))))

  (testing "Vector with numbers"
    (let [result (el/require-and-call-json 'test 'fn [1 2 3])]
      (is (str/includes? result "'(1 2 3)"))))

  (testing "Vector with mixed types"
    (let [result (el/require-and-call-json 'test 'fn ["a" 1 :key])]
      (is (str/includes? result "'(\"a\" 1 :key)")))))

(deftest test-special-characters-in-strings
  (testing "Newlines in strings"
    (let [result (el/require-and-call-json 'test 'fn "line1\nline2")]
      ;; Should be properly escaped
      (is (str/includes? result "\\n"))))

  (testing "Tabs in strings"
    (let [result (el/require-and-call-json 'test 'fn "col1\tcol2")]
      (is (str/includes? result "\\t")))))

(deftest test-complex-nested-args
  (testing "Nested vectors"
    (let [result (el/require-and-call-json 'test 'fn [[1 2] [3 4]])]
      ;; Should handle nested vectors
      (is (string? result))
      (is (str/includes? result "'(")))))

(deftest test-empty-feature-and-function-names
  (testing "Simple short names"
    (let [result (el/require-and-call-json 'f 'g)]
      (is (str/includes? result "(require 'f nil t)"))
      (is (str/includes? result "(fboundp 'g)"))
      (is (str/includes? result "(json-encode (g))")))))

(deftest test-hyphenated-names
  (testing "Hyphenated feature and function names"
    (let [result (el/require-and-call-json 'my-great-feature 'my-great-feature-do-thing)]
      (is (str/includes? result "(require 'my-great-feature nil t)"))
      (is (str/includes? result "(my-great-feature-do-thing)")))))

;; =============================================================================
;; Regression Tests
;; =============================================================================

(deftest test-real-world-magit-usage
  (testing "Real-world magit status call"
    (let [result (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status)]
      (is (str/starts-with? result "(progn"))
      (is (str/includes? result "require 'hive-mcp-magit"))
      (is (str/includes? result "json-encode"))
      (is (str/includes? result "hive-mcp-magit-api-status")))))

(deftest test-real-world-magit-diff
  (testing "Real-world magit diff call with staged symbol"
    (let [result (el/require-and-call-text 'hive-mcp-magit 'hive-mcp-magit-api-diff 'staged)]
      (is (str/includes? result "(hive-mcp-magit-api-diff 'staged)")))))

(deftest test-real-world-cider-eval
  (testing "Real-world cider eval call with code string"
    (let [result (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-eval "(+ 1 2 3)")]
      (is (str/includes? result "(hive-mcp-cider-eval \"(+ 1 2 3)\")")))))

;; =============================================================================
;; Test require-and-call-plist-json
;;
;; Regression: positional arg ordering across CLJ↔Elisp boundary caused
;; project-dir to land in repl-type slot → numberp/"coordinator" error.
;; Plist-based calls eliminate positional ordering bugs entirely.
;; =============================================================================

(deftest test-require-and-call-plist-json-basic
  (testing "Generates plist-based function call"
    (let [result (el/require-and-call-plist-json
                   'hive-mcp-cider 'hive-mcp-cider-spawn-session-from-plist
                   {:name "test-session"})]
      (is (str/starts-with? result "(progn"))
      (is (str/includes? result "(require 'hive-mcp-cider nil t)"))
      (is (str/includes? result "(fboundp 'hive-mcp-cider-spawn-session-from-plist)"))
      (is (str/includes? result ":name \"test-session\""))
      (is (str/includes? result "(list :name \"test-session\")")))))

(deftest test-require-and-call-plist-json-nil-omission
  (testing "Nil values are omitted from the plist"
    (let [result (el/require-and-call-plist-json
                   'hive-mcp-cider 'hive-mcp-cider-spawn-session-from-plist
                   {:name "foo" :repl-type nil :project-dir nil :agent-id nil})]
      ;; Only :name should appear — all nil values omitted
      (is (str/includes? result ":name \"foo\""))
      (is (not (str/includes? result ":repl-type")))
      (is (not (str/includes? result ":project-dir")))
      (is (not (str/includes? result ":agent-id"))))))

(deftest test-require-and-call-plist-json-symbol-values
  (testing "Clojure symbols emit as quoted elisp symbols"
    (let [result (el/require-and-call-plist-json
                   'hive-mcp-cider 'hive-mcp-cider-spawn-session-from-plist
                   {:name "test" :repl-type 'clj})]
      ;; Symbol 'clj should be quoted in elisp
      (is (str/includes? result ":repl-type 'clj")))))

(deftest test-require-and-call-plist-json-full-spawn-params
  (testing "Full spawn params match expected elisp structure"
    (let [result (el/require-and-call-plist-json
                   'hive-mcp-cider 'hive-mcp-cider-spawn-session-from-plist
                   {:name "agent-1"
                    :repl-type 'clj
                    :project-dir "/home/user/project"
                    :agent-id "coordinator"})]
      ;; All four params present with correct types
      (is (str/includes? result ":name \"agent-1\""))
      (is (str/includes? result ":repl-type 'clj"))
      (is (str/includes? result ":project-dir \"/home/user/project\""))
      (is (str/includes? result ":agent-id \"coordinator\""))
      ;; Regression: agent-id must NOT end up where a port number goes
      ;; With positional args this was: (spawn-session "agent-1" "/home/..." "coordinator")
      ;; With plist: (spawn-session-from-plist (list :name "agent-1" :agent-id "coordinator" ...))
      (is (not (str/includes? result "(hive-mcp-cider-spawn-session-from-plist \"agent-1\"")))))

  (testing "Error fallback when feature not loaded"
    (let [result (el/require-and-call-plist-json
                   'hive-mcp-cider 'hive-mcp-cider-spawn-session-from-plist
                   {:name "x"})]
      (is (str/includes? result "hive-mcp-cider not loaded")))))
