(ns hive-mcp.tools.memory.crud.edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.tools.memory.crud.edit :as edit]
            [hive-mcp.tools.memory.duration :as duration]))

(defn- edit-store
  [existing update-fn]
  (reify proto/IMemoryStore
    (connect! [_ _] {:success? true})
    (disconnect! [_] nil)
    (connected? [_] true)
    (health-check [_] {:healthy? true})
    (add-entry! [_ entry] (:id entry))
    (get-entry [_ id] (when (= id (:id existing)) existing))
    (update-entry! [_ id updates] (update-fn id updates))
    (delete-entry! [_ _] nil)
    (query-entries [_ _] [])
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] {:cleaned 0})
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status [_] {})
    (reset-store! [_] nil)))

(deftest edit-refreshes-derived-metadata
  (let [existing {:id "m-1"
                  :type "note"
                  :content "before"
                  :content-hash (proto/content-hash "before")
                  :updated "old"
                  :duration "long"
                  :expires "old-expiry"}
        updates  (atom nil)
        store    (edit-store existing
                   (fn [_ fields]
                     (reset! updates fields)
                     (merge existing fields)))]
    (with-redefs [proto/store-set? (constantly true)
                  proto/get-store (constantly store)
                  proto/iso-timestamp (constantly "new-time")
                  duration/calculate-expires (constantly "new-expiry")]
      (edit/handle-edit {:id "m-1"
                         :content "after"
                         :duration "short"}))
    (is (= "after" (:content @updates)))
    (is (= (proto/content-hash "after") (:content-hash @updates)))
    (is (= "new-time" (:updated @updates)))
    (is (= "short" (:duration @updates)))
    (is (= "new-expiry" (:expires @updates)))))

(deftest edit-reports-malformed-store-result
  (let [existing {:id "m-1" :type "note" :content "before"}
        store    (edit-store existing (fn [_ _] "opaque-result"))]
    (with-redefs [proto/store-set? (constantly true)
                  proto/get-store (constantly store)
                  proto/iso-timestamp (constantly "new-time")]
      (let [response (edit/handle-edit {:id "m-1" :content "after"})]
        (is (re-find #"Memory store update failed for m-1"
                     (:text response)))
        (is (not (re-find #"ClassCastException|Associative"
                          (:text response))))))))

(deftest edit-accepts-id-string-return
  (testing "a store that answers update-entry! with the entry id is a SUCCESS"
    ;; The qdrant store backing the :kanban slot returns the id string, while
    ;; milvus/chroma return the updated entry map. Both are successes; only an
    ;; opaque value is a failure.
    (let [existing {:id "m-1" :type "note" :content "before"}
          store    (edit-store existing (fn [id _] id))]
      (with-redefs [proto/store-set?  (constantly true)
                    proto/get-store   (constantly store)
                    proto/iso-timestamp (constantly "new-time")]
        (let [response (edit/handle-edit {:id "m-1" :content "after"})]
          (is (re-find #"\"edit_applied\":true" (:text response))
              "id-string return must not be reported as a failure")
          (is (not (re-find #"Memory store update failed" (:text response)))))))))

(deftest edit-reaches-a-non-default-store
  (testing "an entry living in a non-default slot (:kanban) is editable"
    ;; Reads are unified across stores; writes must be too, or `memory edit`
    ;; answers 'Entry not found' for an id that `kanban get` resolves.
    (let [kanban-entry {:id "k-1" :type "note" :content "task"}
          seen         (atom nil)
          empty-store  (edit-store {:id "other"} (fn [id _] id))
          kanban-store (edit-store kanban-entry
                                   (fn [id fields] (reset! seen [id fields]) id))]
      (with-redefs [proto/store-set?       (constantly true)
                    proto/get-store        (constantly empty-store)
                    proto/registered-stores (constantly {:default empty-store
                                                         :kanban  kanban-store})
                    proto/iso-timestamp    (constantly "new-time")]
        (let [response (edit/handle-edit {:id "k-1" :tags ["a" "b"]})]
          (is (some? @seen) "the write reached the store that holds the entry")
          (is (= "k-1" (first @seen)))
          (is (= ["a" "b"] (:tags (second @seen))))
          (is (not (re-find #"Entry not found" (:text response)))))))))

(deftest edit-still-reports-missing-entry
  (testing "an id no registered store holds is still Entry not found"
    (let [store (edit-store {:id "present"} (fn [id _] id))]
      (with-redefs [proto/store-set?       (constantly true)
                    proto/get-store        (constantly store)
                    proto/registered-stores (constantly {:default store})]
        (let [response (edit/handle-edit {:id "absent" :tags ["x"]})]
          (is (re-find #"Entry not found" (:text response))))))))
