(ns hive-mcp.tools.kanban.filters-test
  "Pure-fn tests for kanban list filter helpers in
   `hive-mcp.tools.kanban.filters`. Exercises the token-budget
   filters wired into `handle-mem-kanban-list-slim`."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.kanban.filters :as kf]))

(def ^:private fixture
  [{:id "a"
    :tags ["kanban" "todo" "priority-high" "scope:project:x"]
    :updated "2026-04-28T10:00:00-0300"
    :content {:task-type "kanban" :title "Fix grep"
              :description "ripgrep returns empty matches"
              :status "todo" :priority "high"
              :created "2026-04-28T09:00:00-0300"}}
   {:id "b"
    :tags ["kanban" "todo" "priority-medium" "scope:project:y"]
    :updated "2026-04-26T10:00:00-0300"
    :content {:task-type "kanban" :title "Add docs"
              :description "readme update"
              :status "todo" :priority "medium"
              :created "2026-04-26T09:00:00-0300"}}
   {:id "c"
    :tags ["kanban" "todo" "priority-low" "scope:project:x" "infra"]
    :updated "2026-04-25T10:00:00-0300"
    :content {:task-type "kanban" :title "K8s manifest"
              :description "stateful set"
              :status "todo" :priority "low"
              :created "2026-04-25T09:00:00-0300"}}])

(defn- ids [pred]
  (mapv :id (filter pred fixture)))

(deftest entry-matches-query?-test
  (testing "case-insensitive substring match against title"
    (is (= ["a"] (ids #(kf/entry-matches-query? % "GREP")))))
  (testing "matches against description"
    (is (= ["a"] (ids #(kf/entry-matches-query? % "ripgrep")))))
  (testing "blank/nil query → match all"
    (is (= 3 (count (ids #(kf/entry-matches-query? % nil)))))
    (is (= 3 (count (ids #(kf/entry-matches-query? % "")))))
    (is (= 3 (count (ids #(kf/entry-matches-query? % "   ")))))))

(deftest entry-tags-match?-test
  (testing ":any matches when at least one tag present"
    (is (= ["c"] (ids #(kf/entry-tags-match? % ["infra"] :any))))
    (is (= ["a" "b" "c"]
           (ids #(kf/entry-tags-match? % ["todo" "infra"] :any)))))
  (testing ":all matches only when every tag present"
    (is (= ["a"]
           (ids #(kf/entry-tags-match? % ["scope:project:x" "priority-high"] :all))))
    (is (empty? (ids #(kf/entry-tags-match? % ["nope"] :all)))))
  (testing "empty/nil tag set → match all"
    (is (= 3 (count (ids #(kf/entry-tags-match? % [] :all)))))
    (is (= 3 (count (ids #(kf/entry-tags-match? % nil :any)))))))

(deftest entry-after-ts?-test
  (testing ":created threshold (strict)"
    (is (= ["a" "b"]
           (ids #(kf/entry-after-ts? % :created "2026-04-26T00:00:00-0300"))))
    (is (= ["a"]
           (ids #(kf/entry-after-ts? % :created "2026-04-27T00:00:00-0300")))))
  (testing ":updated threshold"
    (is (= ["a"]
           (ids #(kf/entry-after-ts? % :updated "2026-04-27T00:00:00-0300")))))
  (testing "nil threshold → match all"
    (is (= 3 (count (ids #(kf/entry-after-ts? % :created nil)))))))

(deftest paginate-test
  (testing "offset+limit"
    (is (= ["b" "c"] (vec (kf/paginate ["a" "b" "c" "d"] 1 2)))))
  (testing "limit only"
    (is (= ["a" "b"] (vec (kf/paginate ["a" "b" "c" "d"] nil 2)))))
  (testing "offset only"
    (is (= ["c" "d"] (vec (kf/paginate ["a" "b" "c" "d"] 2 nil)))))
  (testing "no offset / no limit → identity"
    (is (= ["a" "b" "c"] (vec (kf/paginate ["a" "b" "c"] nil nil)))))
  (testing "zero offset / zero limit ignored"
    (is (= ["a" "b" "c"] (vec (kf/paginate ["a" "b" "c"] 0 0))))))

(deftest project-fields-test
  (let [task {:id "x" :title "t" :status "todo" :priority "high" :project "x"}]
    (testing "string field names accepted"
      (is (= {:id "x" :title "t"}
             (kf/project-fields task ["id" "title"]))))
    (testing "keyword field names accepted"
      (is (= {:id "x" :status "todo"}
             (kf/project-fields task [:id :status]))))
    (testing "empty/nil fields → identity"
      (is (= task (kf/project-fields task nil)))
      (is (= task (kf/project-fields task []))))))

(deftest post-filters?-test
  (testing "any filter param triggers true"
    (is (kf/post-filters? {:query "x"}))
    (is (kf/post-filters? {:created_after "2026"}))
    (is (kf/post-filters? {:updated_after "2026"}))
    (is (kf/post-filters? {:limit 10}))
    (is (kf/post-filters? {:offset 5}))
    (is (kf/post-filters? {:tags ["a"] :tag_match "any"})))
  (testing "no relevant params → false"
    (is (not (kf/post-filters? {})))
    (is (not (kf/post-filters? {:status "todo"})))
    (is (not (kf/post-filters? {:query ""})))
    (is (not (kf/post-filters? {:query "   "})))))
