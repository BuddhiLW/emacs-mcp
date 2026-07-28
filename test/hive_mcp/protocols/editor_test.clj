(ns hive-mcp.protocols.editor-test
  "Contract tests for IEditor protocol implementations."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [hive-mcp.protocols.editor :as ed]
            [hive-mcp.test.stub.editor :as ed-stub]
            [hive-dsl.result :as result]))

;; =============================================================================
;; Fixture: clean editor atom between tests
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (ed/clear-editor!)
    (f)
    (ed/clear-editor!)))

;; =============================================================================
;; NoopEditor contract tests
;; =============================================================================

(deftest noop-editor-id-test
  (is (= :noop (ed/editor-id (ed/noop-editor)))))

(deftest noop-editor-available?-test
  (is (false? (ed/available? (ed/noop-editor)))))

(deftest noop-editor-eval-expr-returns-err-test
  (testing "1-arity eval-expr returns error Result"
    (let [r (ed/eval-expr (ed/noop-editor) "(+ 1 2)")]
      (is (result/err? r))
      (is (= :editor/not-available (:error r)))))
  (testing "2-arity eval-expr returns error Result"
    (let [r (ed/eval-expr (ed/noop-editor) "(+ 1 2)" {:timeout-ms 5000})]
      (is (result/err? r))
      (is (= :editor/not-available (:error r))))))

(deftest noop-editor-feature-available?-test
  (is (false? (ed/feature-available? (ed/noop-editor) "hive-mcp-swarm"))))

(deftest noop-editor-send-to-terminal-returns-err-test
  (let [r (ed/send-to-terminal (ed/noop-editor) "ling-1" "hello")]
    (is (result/err? r))
    (is (= :editor/not-available (:error r)))))

;; =============================================================================
;; Active editor lifecycle tests
;; =============================================================================

(deftest get-editor-returns-noop-when-unset-test
  (is (instance? hive_mcp.protocols.editor.NoopEditor (ed/get-editor))))

(deftest set-get-clear-lifecycle-test
  (let [editor (ed-stub/->stub-editor {:id :stub})]
    (ed/set-editor! editor)
    (is (ed/editor-set?))
    (is (= :stub (ed/editor-id (ed/get-editor))))
    (ed/clear-editor!)
    (is (not (ed/editor-set?)))
    (is (= :noop (ed/editor-id (ed/get-editor))))))

(deftest set-editor!-rejects-non-ieditor-test
  (is (thrown? AssertionError (ed/set-editor! {:not "an editor"}))))

;; =============================================================================
;; IEditor contract tests — driven through a stub, not a concrete adapter
;;
;; The emacsclient implementation lives in the hive-emacs sibling repo and is
;; contract-tested there. Here we pin what hive-mcp requires OF any IEditor.
;; =============================================================================

(deftest editor-id-is-reported-test
  (is (= :stub (ed/editor-id (ed-stub/->stub-editor {:id :stub})))))

(deftest eval-expr-success-returns-ok-test
  (let [editor (ed-stub/->stub-editor
                {:eval-fn (fn [_code _opts] (result/ok "42"))})
        r      (ed/eval-expr editor "(+ 1 2)")]
    (is (result/ok? r))
    (is (= "42" (:ok r)))
    (is (= [[:eval-expr "(+ 1 2)" {}]] (ed-stub/calls editor))
        "1-arity eval-expr delegates with empty opts")))

(deftest eval-expr-failure-returns-err-test
  (let [editor (ed-stub/->stub-editor
                {:eval-fn (fn [_code _opts]
                            (result/err :editor/eval-failed {:message "void-function foo"}))})
        r      (ed/eval-expr editor "(foo)")]
    (is (result/err? r))
    (is (= :editor/eval-failed (:error r)))))

(deftest eval-expr-timeout-passes-opts-through-test
  (let [editor (ed-stub/->stub-editor
                {:eval-fn (fn [_code opts]
                            (if (= 100 (:timeout-ms opts))
                              (result/err :editor/timeout {:timeout-ms 100})
                              (result/ok "should not happen")))})
        r      (ed/eval-expr editor "(sleep 100)" {:timeout-ms 100})]
    (is (result/err? r))
    (is (= :editor/timeout (:error r)))
    (is (= [[:eval-expr "(sleep 100)" {:timeout-ms 100}]] (ed-stub/calls editor))
        "opts reach the implementation unchanged")))

(deftest available?-reports-backend-reachability-test
  (is (true? (ed/available? (ed-stub/->stub-editor {:available true}))))
  (is (false? (ed/available? (ed-stub/->stub-editor {:available false})))))

(deftest feature-available?-answers-per-feature-test
  (let [editor (ed-stub/->stub-editor
                {:feature-fn #(= "hive-mcp-swarm" %)})]
    (is (true? (ed/feature-available? editor "hive-mcp-swarm")))
    (is (false? (ed/feature-available? editor "missing-feature")))
    (is (= [[:feature-available? "hive-mcp-swarm"]
            [:feature-available? "missing-feature"]]
           (ed-stub/calls editor)))))

(deftest send-to-terminal-delegates-test
  (let [editor (ed-stub/->stub-editor {})
        r      (ed/send-to-terminal editor "ling-1" "hello")]
    (is (result/ok? r))
    (is (= [[:send-to-terminal "ling-1" "hello"]] (ed-stub/calls editor)))))

(deftest with-editor-restores-prior-editor-test
  (testing "the installed editor is active inside, and cleared after"
    (let [editor (ed-stub/->stub-editor {:id :temp})]
      (ed-stub/with-editor editor
        (fn [] (is (= :temp (ed/editor-id (ed/get-editor))))))
      (is (= :noop (ed/editor-id (ed/get-editor)))))))

;; =============================================================================
;; Property: IEditor methods never throw
;; =============================================================================

(deftest noop-methods-never-throw-test
  (testing "Every NoopEditor method returns a value, never throws"
    (let [noop (ed/noop-editor)]
      (are [expr] (some? expr)
        (ed/editor-id noop)
        (ed/eval-expr noop "(anything)")
        (ed/eval-expr noop "(anything)" {:timeout-ms 1000})
        (ed/send-to-terminal noop "id" "text"))
      ;; available? and feature-available? return false (not nil)
      (is (false? (ed/available? noop)))
      (is (false? (ed/feature-available? noop "x"))))))
