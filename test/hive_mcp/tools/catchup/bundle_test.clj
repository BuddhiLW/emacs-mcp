(ns hive-mcp.tools.catchup.bundle-test
  "Tests for the pure split-by-type tiering chokepoint."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [hive-mcp.tools.catchup.bundle :as bundle]))

(def ^:private split-by-type #'bundle/split-by-type)

(defn- p [id & tags] {:id id :type "principle" :tags (vec tags)})
(defn- c [id & tags] {:id id :type "convention" :tags (vec tags)})

(deftest principles-hot-cold-split-test
  (testing "catchup-priority → :priority-principles; untagged → :principles; disjoint"
    (let [by-type {"principle" [(p "p1" "catchup-priority") (p "p2")
                                (p "p3" "catchup-priority") (p "p4")]}
          result (split-by-type by-type [])]
      (is (= ["p1" "p3"] (mapv :id (:priority-principles result))))
      (is (= ["p2" "p4"] (mapv :id (:principles result))))
      (is (empty? (set/intersection
                   (set (map :id (:priority-principles result)))
                   (set (map :id (:principles result))))))))

  (testing "ZERO-TAGGED FALLBACK: no tagged principle ⇒ all inline, cold empty (today's behavior)"
    (let [result (split-by-type {"principle" [(p "p2") (p "p4")]} [])]
      (is (= ["p2" "p4"] (mapv :id (:priority-principles result))))
      (is (= [] (:principles result)))))

  (testing "each bucket capped at 50"
    (let [tagged   (mapv #(p (str "t" %) "catchup-priority") (range 60))
          untagged (mapv #(p (str "u" %)) (range 60))
          result   (split-by-type {"principle" (into tagged untagged)} [])]
      (is (= 50 (count (:priority-principles result))))
      (is (= 50 (count (:principles result)))))))

(deftest conventions-split-regression-test
  (testing "conventions hot/cold split unchanged (the pattern principles mirror)"
    (let [result (split-by-type {"convention" [(c "c1" "catchup-priority") (c "c2")]} [])]
      (is (= ["c1"] (mapv :id (:priority-conventions result))))
      (is (= ["c2"] (mapv :id (:conventions result)))))))
