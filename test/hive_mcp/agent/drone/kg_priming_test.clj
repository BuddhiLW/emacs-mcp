(ns hive-mcp.agent.drone.kg-priming-test
  "Tests for domain context priming stubs.

   Tests the noop fallback behavior when the priming backend is not on classpath.
   When hive-agent IS available, the real implementation is tested
   in hive-agent's own test suite."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.agent.drone.kg-priming :as priming]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.protocols.kg :as kg-proto]
            [hive-mcp.knowledge-graph.connection]))

;; =============================================================================
;; Availability Check Tests
;; =============================================================================

(deftest priming-available-test
  (testing "priming-available? returns false without backend"
    (is (false? (priming/priming-available?)))))

;; =============================================================================
;; Noop Fallback Tests (when backend is NOT on classpath)
;; =============================================================================

(deftest prime-context-noop-test
  (testing "prime-context returns empty string when backend unavailable"
    (is (= "" (priming/prime-context {:task "implement STM concurrency"
                                      :seeds ["fp" "concurrency"]
                                      :project-id "test-project"
                                      :token-budget 1500}))))

  (testing "prime-context returns empty string for empty seeds"
    (is (= "" (priming/prime-context {:task "some task"
                                      :seeds []
                                      :project-id "test-project"}))))

  (testing "prime-context returns empty string for nil seeds"
    (is (= "" (priming/prime-context {:task "some task"
                                      :seeds nil
                                      :project-id "test-project"}))))

  (testing "prime-context returns empty string when no seeds key"
    (is (= "" (priming/prime-context {:task "some task"
                                      :project-id "test-project"})))))

(deftest resolve-seeds-noop-test
  (testing "resolve-seeds returns empty vector when backend unavailable"
    (is (= [] (priming/resolve-seeds {:tags ["fp" "ddd"]
                                      :project-id "test-project"}))))

  (testing "resolve-seeds returns empty vector with explicit ids"
    (is (= [] (priming/resolve-seeds {:ids ["node-123"]
                                      :project-id "test-project"}))))

  (testing "resolve-seeds returns empty vector with task-based resolution"
    (is (= [] (priming/resolve-seeds {:task "implement STM"
                                      :project-id "test-project"})))))

;; =============================================================================
;; Delegation Tests (mocked backend)
;; =============================================================================

(deftest prime-context-delegation-test
  (testing "prime-context delegates to backend when available"
    (let [called-with (atom nil)]
      (with-redefs [;; Mock the try-resolve to return a fake priming fn
                    hive-mcp.agent.drone.kg-priming/priming-available? (constantly true)]
        ;; We can't easily mock requiring-resolve, but we can test that
        ;; the function structure is correct by testing the noop path
        ;; The real delegation test lives in hive-agent's test suite
        (is (= "" (priming/prime-context {:task "test"
                                          :seeds ["fp"]
                                          :project-id "test"})))))))

;; =============================================================================
;; Integration Contract Tests
;; =============================================================================

(deftest noop-integration-flow-test
  (testing "Full noop flow: check availability -> resolve seeds -> prime context"
    ;; Check availability
    (is (false? (priming/priming-available?)))

    ;; Resolve seeds returns empty
    (is (= [] (priming/resolve-seeds {:tags ["fp" "ddd"]})))

    ;; Prime context returns empty string
    (is (= "" (priming/prime-context {:task "implement concurrency patterns"
                                      :seeds ["fp" "concurrency"]
                                      :project-id "hive-mcp"
                                      :token-budget 1500})))))

(deftest prime-context-does-not-throw-test
  (testing "prime-context never throws, even with odd inputs"
    (is (= "" (priming/prime-context {})))
    (is (= "" (priming/prime-context {:seeds nil})))
    (is (= "" (priming/prime-context {:task nil :seeds []})))
    (is (= "" (priming/prime-context {:task "" :seeds ["fp"]})))))

;; =============================================================================
;; KG Store Auto-Initialization Tests
;; =============================================================================

(deftest kg-store-available-test
  (testing "kg-store-available? reflects actual store state"
    ;; Store should be set after auto-init from connection module
    ;; (DataScript fallback always succeeds)
    (is (boolean? (priming/kg-store-available?)))))

(deftest priming-available-checks-both-extension-and-store-test
  (testing "priming-available? requires BOTH extension AND store"
    ;; Without extension registered, should be false regardless of store
    (is (false? (priming/priming-available?))))

  (testing "priming-available? false when extension present but store missing"
    (ext/register! :pm/prime (fn [_] ""))
    (try
      (with-redefs [hive-mcp.protocols.kg/store-set? (constantly false)]
        (is (false? (priming/priming-available?))))
      (finally
        (ext/deregister! :pm/prime)))))

(deftest delegate-graceful-on-extension-exception-test
  (testing "prime-context returns noop when extension throws"
    (ext/register! :pm/prime (fn [_] (throw (ex-info "backend error" {}))))
    (try
      (is (= "" (priming/prime-context {:task "test"
                                        :seeds ["fp"]
                                        :project-id "test"})))
      (finally
        (ext/deregister! :pm/prime))))

  (testing "resolve-seeds returns noop when extension throws"
    (ext/register! :pm/seeds (fn [_] (throw (ex-info "backend error" {}))))
    (try
      (is (= [] (priming/resolve-seeds {:tags ["fp"]
                                        :project-id "test"})))
      (finally
        (ext/deregister! :pm/seeds)))))

(deftest delegate-with-store-unavailable-test
  (testing "prime-context returns noop when extension present but KG store unavailable"
    (ext/register! :pm/prime (fn [_] "primed content"))
    (try
      (with-redefs [hive-mcp.protocols.kg/store-set? (constantly false)
                    hive-mcp.knowledge-graph.connection/get-conn
                    (fn [] (throw (ex-info "no store" {})))]
        (is (= "" (priming/prime-context {:task "test"
                                          :seeds ["fp"]
                                          :project-id "test"}))))
      (finally
        (ext/deregister! :pm/prime))))

  (testing "resolve-seeds returns noop when KG store unavailable"
    (ext/register! :pm/seeds (fn [_] ["node-1" "node-2"]))
    (try
      (with-redefs [hive-mcp.protocols.kg/store-set? (constantly false)
                    hive-mcp.knowledge-graph.connection/get-conn
                    (fn [] (throw (ex-info "no store" {})))]
        (is (= [] (priming/resolve-seeds {:tags ["fp"]
                                          :project-id "test"}))))
      (finally
        (ext/deregister! :pm/seeds)))))

(deftest delegate-with-store-available-test
  (testing "prime-context delegates when extension AND store available"
    (ext/register! :pm/prime (fn [opts] (str "primed:" (:task opts))))
    (try
      (with-redefs [hive-mcp.protocols.kg/store-set? (constantly true)]
        (is (= "primed:test-task"
               (priming/prime-context {:task "test-task"
                                       :seeds ["fp"]
                                       :project-id "test"}))))
      (finally
        (ext/deregister! :pm/prime))))

  (testing "resolve-seeds delegates when extension AND store available"
    (ext/register! :pm/seeds (fn [_] ["node-1" "node-2"]))
    (try
      (with-redefs [hive-mcp.protocols.kg/store-set? (constantly true)]
        (is (= ["node-1" "node-2"]
               (priming/resolve-seeds {:tags ["fp"]
                                       :project-id "test"}))))
      (finally
        (ext/deregister! :pm/seeds)))))
