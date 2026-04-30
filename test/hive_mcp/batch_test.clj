(ns hive-mcp.batch-test
  "Smoke test proving hive-mcp.batch is independently callable without
   any hive-mcp tool routing. Deeper coverage remains in
   hive-mcp.tools.multi-test which exercises the wrapper."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.batch :as batch]
            [hive-mcp.batch.protocol :as bp]))

(defn- stub-handler
  "Simple in-process handler registry keyed by :tool keyword."
  [handlers tool-name]
  (get handlers tool-name))

(deftest run-operations-happy-path
  (testing "single op with an injected handler executes and returns success"
    (let [calls (atom [])
          handlers {"fake-tool" (fn [args]
                                  (swap! calls conj args)
                                  {:echo args})}
          result (batch/run-operations
                  [{:id "op-1" :tool "fake-tool" :command "noop" :arg 42}]
                  {:resolve-handler (partial stub-handler handlers)})]
      (is (:success result))
      (is (= 1 (count @calls)))
      (is (= 42 (:arg (first @calls))))
      (is (= 1 (get-in result [:summary :total])))
      (is (= 1 (get-in result [:summary :success])))
      (is (= 0 (get-in result [:summary :failed]))))))

(deftest run-operations-validation-error
  (testing "missing :tool is caught by validation before execution"
    (let [result (batch/run-operations
                  [{:id "op-bad" :command "noop"}]
                  {:resolve-handler (fn [_] nil)})]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest run-operations-dry-run
  (testing "dry-run reports plan without invoking handler"
    (let [called? (atom false)
          result (batch/run-operations
                  [{:id "op-1" :tool "noop-tool" :command "go"}]
                  {:resolve-handler (fn [_] (reset! called? true) (constantly nil))
                   :dry-run? true})]
      (is (:success result))
      (is (:dry-run result))
      (is (not @called?) "dry-run must not invoke handler"))))

(deftest ref-not-found-sentinel-back-compat
  (testing "sentinel keyword is preserved under legacy hive-mcp.tools.multi namespace"
    (is (= :hive-mcp.tools.multi/ref-not-found batch/ref-not-found))))

(deftest default-runner-is-batchable
  (testing "make-default-runner yields a Batchable implementation (T13 Phase 2)"
    (let [runner (batch/make-default-runner {:resolve-handler (constantly nil)})]
      (is (satisfies? bp/Batchable runner))
      (is (satisfies? bp/DAGBatchable runner))
      (is (satisfies? bp/StreamingBatchable runner)))))

(deftest default-runner-executes-via-protocol
  (testing "batch-execute via protocol matches legacy run-operations output shape"
    (let [calls  (atom 0)
          runner (batch/make-default-runner
                  {:resolve-handler (fn [_tool]
                                      (fn [_args] (swap! calls inc) {:ok true}))})
          result (bp/batch-execute runner
                                   [{:id "a" :tool "echo" :command "go"}]
                                   {})]
      (is (:success result))
      (is (= 1 @calls))
      (is (= 1 (get-in result [:summary :success]))))))
