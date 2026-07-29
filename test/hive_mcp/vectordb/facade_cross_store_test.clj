(ns hive-mcp.vectordb.facade-cross-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.vectordb.facade :as facade]))

(defn- stub-store
  [get-fn]
  (reify proto/IMemoryStore
    (connect! [_ _] {:success? true})
    (disconnect! [_] nil)
    (connected? [_] true)
    (health-check [_] {:healthy? true})
    (add-entry! [_ entry] (:id entry))
    (get-entry [_ id] (get-fn id))
    (update-entry! [_ _ _] nil)
    (delete-entry! [_ _] nil)
    (query-entries [_ _] [])
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] {:cleaned 0})
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status [_] {})
    (reset-store! [_] nil)))

(deftest get-entry-any-store-falls-back-to-kanban
  (let [calls   (atom [])
        default (stub-store (fn [id]
                              (swap! calls conj [:default id])
                              nil))
        kanban  (stub-store (fn [id]
                              (swap! calls conj [:kanban id])
                              {:id id :store :kanban}))]
    (with-redefs [proto/registered-stores
                  (constantly {:kanban kanban :default default})]
      (is (= {:id "task-1" :store :kanban}
             (facade/get-entry-any-store "task-1")))
      (is (= [[:default "task-1"] [:kanban "task-1"]] @calls)))))

(deftest get-entry-any-store-short-circuits-on-default-hit
  (let [calls   (atom [])
        default (stub-store (fn [id]
                              (swap! calls conj [:default id])
                              {:id id :store :default}))
        kanban  (stub-store (fn [id]
                              (swap! calls conj [:kanban id])
                              {:id id :store :kanban}))]
    (with-redefs [proto/registered-stores
                  (constantly {:kanban kanban :default default})]
      (testing "default memory keeps precedence"
        (is (= :default (:store (facade/get-entry-any-store "memory-1"))))
        (is (= [[:default "memory-1"]] @calls))))))
