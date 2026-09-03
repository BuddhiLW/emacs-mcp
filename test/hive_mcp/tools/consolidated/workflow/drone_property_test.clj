(ns hive-mcp.tools.consolidated.workflow.drone-property-test
  "Property tests for drone pure functions: kanban-task->wave-tasks."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.tools.consolidated.workflow.drone :as drone]
            [clojure.string]))

;; ── Generators ─────────────────────────────────────────────────────────────

(def gen-file-path
  (gen/fmap #(str "src/" % ".clj") gen/string-alphanumeric))

(def gen-task-with-file
  (gen/hash-map :title gen/string-alphanumeric
                :description (gen/one-of [gen/string-alphanumeric (gen/return nil)])
                :file gen-file-path))

(def gen-task-with-files
  (gen/hash-map :title gen/string-alphanumeric
                :description (gen/one-of [gen/string-alphanumeric (gen/return nil)])
                :files (gen/vector gen-file-path 1 5)))

(def gen-task-no-file
  (gen/hash-map :title gen/string-alphanumeric
                :description (gen/one-of [gen/string-alphanumeric (gen/return nil)])))

;; ── kanban-task->wave-tasks ────────────────────────────────────────────────

(defspec task-with-single-file-returns-one-wave-task 200
  (prop/for-all [task gen-task-with-file]
    (let [result (drone/kanban-task->wave-tasks task)]
      (and (= 1 (count result))
           (= (:file task) (:file (first result)))
           (string? (:task (first result)))))))

(defspec task-with-files-returns-one-per-file 200
  (prop/for-all [task gen-task-with-files]
    (let [result (drone/kanban-task->wave-tasks task)]
      (and (= (count (:files task)) (count result))
           (every? #(string? (:task %)) result)
           (= (set (:files task)) (set (map :file result)))))))

(defspec task-without-file-returns-empty 200
  (prop/for-all [task gen-task-no-file]
    (empty? (drone/kanban-task->wave-tasks task))))

(defspec wave-task-text-contains-title 200
  (prop/for-all [task gen-task-with-file]
    (let [result (drone/kanban-task->wave-tasks task)]
      (when (seq result)
        (clojure.string/starts-with? (:task (first result))
                                     (or (:title task) "untitled"))))))

(defspec wave-tasks-always-vector 200
  (prop/for-all [task (gen/one-of [gen-task-with-file gen-task-no-file gen-task-with-files])]
    (vector? (drone/kanban-task->wave-tasks task))))

;; ── Unit Tests ─────────────────────────────────────────────────────────────

(deftest kanban-task-conversion-edge-cases
  (testing "nil task"
    (is (= [] (drone/kanban-task->wave-tasks {}))))
  (testing "title-only"
    (is (= [] (drone/kanban-task->wave-tasks {:title "Review"}))))
  (testing "file takes precedence over files"
    (let [result (drone/kanban-task->wave-tasks {:title "T" :file "a.clj" :files ["b.clj"]})]
      (is (= 1 (count result)))
      (is (= "a.clj" (:file (first result))))))
  (testing "description appended"
    (let [result (drone/kanban-task->wave-tasks {:title "T" :description "D" :file "f.clj"})]
      (is (clojure.string/includes? (:task (first result)) "D")))))
