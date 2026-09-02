(ns hive-mcp.tools.addon-test
  "Tests for addon availability utilities in tools.core.

   CLARITY: T - Telemetry first (comprehensive test coverage)
   SOLID: SRP - Tests only addon utilities"
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.core :as core]
            [hive-mcp.emacs-ext.client]))

;; =============================================================================
;; addon-available? Tests
;; =============================================================================

(deftest addon-available?-with-loaded-addon
  (testing "Returns true when addon feature is loaded"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (constantly {:success true :result "t"})]
      (is (true? (core/addon-available? :kanban)))
      (is (true? (core/addon-available? :swarm)))
      (is (true? (core/addon-available? :docs)))
      (is (true? (core/addon-available? :magit)))
      (is (true? (core/addon-available? :projectile))))))

(deftest addon-available?-with-unloaded-addon
  (testing "Returns false when addon feature is not loaded"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (constantly {:success true :result "nil"})]
      (is (false? (core/addon-available? :kanban)))
      (is (false? (core/addon-available? :swarm))))))

(deftest addon-available?-on-elisp-failure
  (testing "Returns false when elisp call fails"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (constantly {:success false :error "connection error"})]
      (is (false? (core/addon-available? :kanban))))))

(deftest addon-available?-on-exception
  (testing "Returns false when exception is thrown (CLARITY-Y)"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (fn [_] (throw (Exception. "network error")))]
      (is (false? (core/addon-available? :kanban))))))

(deftest addon-available?-with-custom-feature
  (testing "Accepts string for custom features"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (fn [elisp]
                    (if (= elisp "(featurep 'my-custom-feature)")
                      {:success true :result "t"}
                      {:success false}))]
      (is (true? (core/addon-available? "my-custom-feature"))))))

(deftest addon-available?-fallback-naming
  (testing "Unknown keyword falls back to hive-mcp-<name>"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (fn [elisp]
                    (if (= elisp "(featurep 'hive-mcp-unknown)")
                      {:success true :result "t"}
                      {:success false}))]
      (is (true? (core/addon-available? :unknown))))))

;; =============================================================================
;; addon-not-loaded-error Tests
;; =============================================================================

(deftest addon-not-loaded-error-format
  (testing "Returns proper MCP error structure"
    (let [result (core/addon-not-loaded-error :kanban)]
      (is (= {:type "text"
              :text "kanban addon not available"
              :isError true}
             result)))))

(deftest addon-not-loaded-error-with-string
  (testing "Works with string addon names"
    (let [result (core/addon-not-loaded-error "custom-addon")]
      (is (= "custom-addon addon not available" (:text result))))))

;; =============================================================================
;; with-addon Macro Tests
;; =============================================================================

(deftest with-addon-executes-body-when-available
  (testing "Executes body when addon is available"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (constantly {:success true :result "t"})]
      (let [result (core/with-addon :kanban
                     {:type "text" :text "success!"})]
        (is (= "success!" (:text result)))
        (is (nil? (:isError result)))))))

(deftest with-addon-returns-error-when-unavailable
  (testing "Returns error when addon is not available"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (constantly {:success true :result "nil"})]
      (let [result (core/with-addon :kanban
                     {:type "text" :text "should not reach"})]
        (is (= "kanban addon not available" (:text result)))
        (is (true? (:isError result)))))))

(deftest with-addon-supports-multiple-body-forms
  (testing "Macro wraps multiple body forms in implicit do"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (constantly {:success true :result "t"})]
      (let [side-effect (atom nil)
            result (core/with-addon :swarm
                     (reset! side-effect :executed)
                     {:type "text" :text "final"})]
        (is (= :executed @side-effect))
        (is (= "final" (:text result)))))))

(deftest with-addon-short-circuits-on-unavailable
  (testing "Body is NOT executed when addon unavailable"
    (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                  (constantly {:success true :result "nil"})]
      (let [side-effect (atom :untouched)]
        (core/with-addon :kanban
          (reset! side-effect :should-not-happen)
          {:type "text" :text "ignored"})
        (is (= :untouched @side-effect))))))

;; =============================================================================
;; Feature Name Mapping Tests
;; =============================================================================

(deftest kanban-uses-org-prefix
  (testing ":kanban maps to hive-mcp-org-kanban (special case)"
    (let [captured-elisp (atom nil)]
      (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                    (fn [elisp]
                      (reset! captured-elisp elisp)
                      {:success true :result "t"})]
        (core/addon-available? :kanban)
        (is (= "(featurep 'hive-mcp-org-kanban)" @captured-elisp))))))

(deftest swarm-uses-standard-naming
  (testing ":swarm maps to hive-mcp-swarm"
    (let [captured-elisp (atom nil)]
      (with-redefs [hive-mcp.emacs-ext.client/eval-elisp
                    (fn [elisp]
                      (reset! captured-elisp elisp)
                      {:success true :result "t"})]
        (core/addon-available? :swarm)
        (is (= "(featurep 'hive-mcp-swarm)" @captured-elisp))))))
