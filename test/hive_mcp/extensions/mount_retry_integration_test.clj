(ns hive-mcp.extensions.mount-retry-integration-test
  (:require [clojure.test :refer [deftest is]]
            [hive-addon.mount.boundary :as boundary]
            [hive-addon.mount.solve :as solve]
            [hive-addon.protocol :as proto]
            [hive-mcp.extensions.mount-host :as mount-host]))

(def init-calls (atom 0))

(defn retry-addon-ctor [_]
  (reify proto/IAddon
    (addon-id [_] "retry.integration")
    (addon-type [_] :native)
    (capabilities [_] #{})
    (initialize! [_ _]
      {:success? (>= (swap! init-calls inc) 2)
       :errors ["not ready"]})
    (shutdown! [_] nil)
    (tools [_] [])
    (schema-extensions [_] {})
    (health [_] {:status :ok})
    (excluded-tools [_] #{})
    (hooks [_] {})))

(deftest mount-host-retries-addon-initialization
  (reset! init-calls 0)
  (let [registry (atom {})
        host (mount-host/addon-registry-host
              {:reg-fn (fn [addon]
                         (swap! registry assoc (proto/addon-id addon) addon)
                         {:success? true})
               :init-fn (fn [id config]
                          (proto/initialize! (get @registry id) config))
               :registered-fn #(get @registry %)})
        spec {:addon/id "retry.integration"
              :addon/type :native
              :addon/init-ns "hive-mcp.extensions.mount-retry-integration-test"
              :addon/init-fn "retry-addon-ctor"
              :addon/init-retry {:max-attempts 2
                                 :initial-delay-ms 0
                                 :max-delay-ms 0
                                 :backoff-factor 2}}
        report (boundary/mount! (solve/solve [spec]) host)]
    (is (:ok? report))
    (is (= 2 @init-calls))
    (is (= 2 (get-in report [:mounted 0 :init-attempts])))))
