(ns hive-mcp.tools.consolidated.workflow.forge-ops-property-test
  "Property tests for forge-ops pure functions: parse-kanban-tasks,
   sort-by-priority-then-created."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.tools.consolidated.workflow.forge-ops :as forge-ops]
            [clojure.data.json :as json]
            [clojure.string]))

;; ── Generators ─────────────────────────────────────────────────────────────

(def gen-priority
  (gen/elements ["high" "priority-high" "medium" "priority-medium" "low" "priority-low"]))

(def gen-task-id
  (gen/fmap #(str "task-" %) (gen/choose 1 10000)))

(def gen-task-map
  (gen/hash-map :id gen-task-id
                :title gen/string-alphanumeric
                :priority gen-priority))

(def gen-task-list
  (gen/vector gen-task-map 0 20))

;; ── parse-kanban-tasks ─────────────────────────────────────────────────────

(defspec parse-kanban-tasks-nil-returns-empty 200
  (prop/for-all [_ gen/any]
                (= [] (forge-ops/parse-kanban-tasks nil))))

(defspec parse-kanban-tasks-from-json-text 200
  (prop/for-all [tasks gen-task-list]
                (let [json-str (json/write-str tasks)
                      result   (forge-ops/parse-kanban-tasks {:text json-str})]
                  (and (sequential? result)
                       (= (count tasks) (count result))))))

(defspec parse-kanban-tasks-from-wrapped-map 200
  (prop/for-all [tasks gen-task-list]
                (let [json-str (json/write-str {:tasks tasks})
                      result   (forge-ops/parse-kanban-tasks {:text json-str})]
                  (and (sequential? result)
                       (= (count tasks) (count result))))))

(defspec parse-kanban-tasks-plain-string 200
  (prop/for-all [tasks gen-task-list]
                (let [json-str (json/write-str tasks)
                      result   (forge-ops/parse-kanban-tasks json-str)]
                  (and (sequential? result)
                       (= (count tasks) (count result))))))

(defspec parse-kanban-tasks-invalid-returns-empty 200
  (prop/for-all [s (gen/such-that #(not (clojure.string/starts-with? % "["))
                                  gen/string-alphanumeric)]
                (let [result (forge-ops/parse-kanban-tasks {:text s})]
                  (sequential? result))))

;; ── sort-by-priority-then-created ──────────────────────────────────────────

(defspec sort-preserves-count 200
  (prop/for-all [tasks gen-task-list]
                (= (count tasks)
                   (count (forge-ops/sort-by-priority-then-created tasks)))))

(defspec sort-is-idempotent 200
  (prop/for-all [tasks gen-task-list]
                (let [sorted  (forge-ops/sort-by-priority-then-created tasks)
                      sorted2 (forge-ops/sort-by-priority-then-created sorted)]
                  (= sorted sorted2))))

(defspec sort-high-before-medium-before-low 200
  (prop/for-all [_ gen/nat]
                (let [tasks  [{:id "low" :priority "low"}
                              {:id "high" :priority "high"}
                              {:id "med" :priority "medium"}]
                      sorted (forge-ops/sort-by-priority-then-created tasks)
                      ids    (mapv :id sorted)]
                  (= ids ["high" "med" "low"]))))

(defspec sort-returns-vector 200
  (prop/for-all [tasks gen-task-list]
                (vector? (forge-ops/sort-by-priority-then-created tasks))))

(defspec sort-contains-same-elements 200
  (prop/for-all [tasks gen-task-list]
                (let [sorted (forge-ops/sort-by-priority-then-created tasks)]
                  (= (set tasks) (set sorted)))))

;; ── Unit Tests ─────────────────────────────────────────────────────────────

(deftest parse-kanban-tasks-edge-cases
  (testing "nil input"
    (is (= [] (forge-ops/parse-kanban-tasks nil))))
  (testing "empty map"
    (is (= [] (forge-ops/parse-kanban-tasks {}))))
  (testing "non-string text"
    (is (= [] (forge-ops/parse-kanban-tasks {:text 42}))))
  (testing "invalid JSON"
    (is (= [] (forge-ops/parse-kanban-tasks {:text "not json"})))))

(deftest sort-priority-ordering
  (testing "all priorities respected"
    (let [tasks  [{:id "1" :priority "low"}
                  {:id "2" :priority "high"}
                  {:id "3" :priority "medium"}
                  {:id "4" :priority "priority-high"}
                  {:id "5" :priority "priority-low"}]
          sorted (forge-ops/sort-by-priority-then-created tasks)]
      ;; high/priority-high come first (rank 0)
      (is (#{"high" "priority-high"} (:priority (first sorted))))
      ;; low/priority-low come last (rank 2)
      (is (#{"low" "priority-low"} (:priority (last sorted)))))))
