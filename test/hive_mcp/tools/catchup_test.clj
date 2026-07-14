(ns hive-mcp.tools.catchup-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.catchup :as catchup]
            [hive-mcp.tools.catchup.outcome :as outcome]))

(deftest safe-deref-distinguishes-query-outcomes
  (testing "success"
    (let [result (#'catchup/safe-deref (future {:entries [1]}) 1000 "ok")]
      (is (= :ok (:status result)))
      (is (= {:entries [1]} (outcome/value-or result {})))))
  (testing "timeout"
    (let [gate (promise)
          result (#'catchup/safe-deref (future @gate) 10 "slow")]
      (is (= :timeout (:status result)))
      (is (false? (outcome/available? result)))
      (is (= :fallback (outcome/value-or result :fallback)))))
  (testing "error"
    (let [result (#'catchup/safe-deref
                  (future (throw (ex-info "boom" {})))
                  1000
                  "broken")]
      (is (= :error (:status result)))
      (is (false? (outcome/available? result))))))
