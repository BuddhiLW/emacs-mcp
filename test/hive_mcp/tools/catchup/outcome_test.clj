(ns hive-mcp.tools.catchup.outcome-test
  (:require [clojure.test :refer [deftest is]]
            [hive-mcp.tools.catchup.outcome :as outcome]
            [hive-schemas.test :as hst]
            [hive-test.mutation :as mut]
            [malli.core :as m]))

(hst/deftrifecta-from-schema ok-outcome-preserves-value
  hive-mcp.tools.catchup.outcome/ok
  {:in :string
   :out outcome/QueryOutcome
   :rel (fn [input result]
          (and (= :ok (:status result))
               (= input (:value result))
               (empty? (:warnings result))))
   :mutation false
   :num-tests 100})

(deftest failure-outcome-is-typed-and-unavailable
  (let [result (outcome/failure :timeout "bundle" "late")]
    (is (m/validate outcome/QueryOutcome result))
    (is (not (outcome/available? result)))
    (is (= :fallback (outcome/value-or result :fallback)))
    (is (= {:status :timeout
            :warnings [{:status :timeout :label "bundle" :message "late"}]}
           (outcome/summary result)))))

(mut/deftest-mutation-witness unavailable-must-not-be-treated-as-available
  hive-mcp.tools.catchup.outcome/available?
  (constantly true)
  (fn []
    (let [result (outcome/failure :error "bundle" "failed")]
      (is (false? (outcome/available? result)))
      (is (= :fallback (outcome/value-or result :fallback))))))