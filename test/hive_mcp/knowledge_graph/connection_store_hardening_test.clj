(ns hive-mcp.knowledge-graph.connection-store-hardening-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.connection.store :as store]
            [hive-mcp.knowledge-graph.slots.factory :as factory]
            [hive-mcp.protocols.kg :as kg]))

(deftest datahike-unavailable-preserves-cause-test
  (testing "startup refusal retains actionable version provenance"
    (let [provenance {:runtime {:konserve/version "0.9.352"}
                      :stored {:konserve/version "0.9.353"}}
          cause      (ex-info "connect failed"
                              {:error :datahike/connect-failed
                               :version-provenance provenance})
          wrapped    (#'store/datahike-unavailable-ex cause)]
      (is (identical? cause (.getCause wrapped)))
      (is (= :datahike (-> wrapped ex-data :backend)))
      (is (= :datahike/connect-failed
             (-> wrapped ex-data :failure-type)))
      (is (= provenance
             (-> wrapped ex-data :version-provenance))))))

(deftest datahike-initialization-refuses-fallback-and-preserves-cause-test
  (let [provenance {:runtime {:konserve/version "0.9.352"}
                    :stored {:konserve/version "0.9.353"}}
        cause      (ex-info "connect failed"
                            {:error :datahike/connect-failed
                             :version-provenance provenance})]
    (with-redefs-fn
      {#'store/preload-datahike-runtime! (constantly nil)
       #'factory/backend->store (fn [& _] ::store)
       #'kg/ensure-conn! (fn [_] (throw cause))}
      #(try
         (#'store/initialize-datahike-store! nil)
         (is false "an incompatible Datahike store must not be substituted")
         (catch clojure.lang.ExceptionInfo e
           (is (identical? cause (.getCause e)))
           (is (= :datahike/connect-failed
                  (-> e ex-data :failure-type)))
           (is (= provenance
                  (-> e ex-data :version-provenance))))))))
