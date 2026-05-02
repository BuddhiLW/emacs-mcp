(ns hive-mcp.tools.kanban.coeffects-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.kanban.coeffects :as coeffects]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.vectordb.kanban-facade :as kanban-facade]))

(def ^:private project-id-cofx #'coeffects/project-id-cofx)
(def ^:private entry-cofx #'coeffects/entry-cofx)

(deftest project-id-cofx-preserves-entry-scope-over-directory
  (testing "moving a child-project task from a parent cwd keeps original scope tag"
    (let [entry {:id "task-1"
                 :tags ["kanban" "todo" "priority-high" "scope:project:hive-mcp"]
                 :content {:task-type "kanban" :status "todo"}}
          result (with-redefs [scope/get-current-project-id (fn [_] "hive")]
                   (project-id-cofx {:event [:kanban/move {:task-id "task-1"
                                                           :directory "/home/leibniz/PP/hive"}]
                                     :kanban/entry entry}))]
      (is (= "hive-mcp" (:kanban/project-id result))))))

(deftest project-id-cofx-falls-back-to-directory-for-unscoped-entry
  (testing "new or legacy entries without scope still use directory scope"
    (let [entry {:id "task-1"
                 :tags ["kanban" "todo" "priority-high"]
                 :content {:task-type "kanban" :status "todo"}}
          result (with-redefs [scope/get-current-project-id (fn [_] "hive")]
                   (project-id-cofx {:event [:kanban/move {:task-id "task-1"
                                                           :directory "/home/leibniz/PP/hive"}]
                                     :kanban/entry entry}))]
      (is (= "hive" (:kanban/project-id result))))))

(deftest entry-cofx-throws-on-store-failure-map
  (testing "milvus resilience-layer failure map must not leak as :kanban/entry"
    (with-redefs [kanban-facade/get-entry-by-id
                  (fn [_id] {:success? false
                             :errors [{:reason :transport :msg "Keepalive failed"}]
                             :reconnecting? true})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Memory store read failed for kanban task: task-fail-1"
            (entry-cofx {:event [:kanban/move {:task-id "task-fail-1"}]}))
          "store-failure map should throw, not be assoc'd as :kanban/entry"))))

(deftest entry-cofx-passes-real-entries-through
  (testing "real entries flow into :kanban/entry unchanged"
    (let [entry {:id "task-ok-1" :type "note" :content {} :tags ["kanban"]}]
      (with-redefs [kanban-facade/get-entry-by-id (fn [_id] entry)]
        (let [result (entry-cofx {:event [:kanban/move {:task-id "task-ok-1"}]})]
          (is (= entry (:kanban/entry result))))))))

(deftest entry-cofx-passes-nil-through-for-genuine-not-found
  (testing "nil from store (entry truly absent) keeps current 'not found' path"
    (with-redefs [kanban-facade/get-entry-by-id (fn [_id] nil)]
      (let [result (entry-cofx {:event [:kanban/move {:task-id "missing"}]})]
        (is (nil? (:kanban/entry result)))))))