(ns hive-mcp.tools.kanban.coeffects-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.kanban.coeffects :as coeffects]
            [hive-mcp.tools.memory.scope :as scope]))

(def ^:private project-id-cofx #'coeffects/project-id-cofx)

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