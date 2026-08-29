(ns hive-mcp.tools.kg.bulk-edge-test
  "batch-edge must cost ONE transaction and ONE flush, not N of each.

   The generic batch runner dispatched every op to handle-kg-add-edge, which is
   with-kg-flush(add-edge*): one datahike transaction AND one flush-pending!
   barrier per edge. Measured 2026-08-29 during corpus synthesis, where a single
   book issues thousands of edges and the coordinator logged a continuous
   kg_add_edge stream plus repeated `flush-pending! deadline exceeded`."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]
            [hive-mcp.tools.kg.commands :as cmd]))

(use-fixtures :each fixtures/global-datascript-fixture)

(defn- payload [response]
  (json/read-str (:text response) :key-fn keyword))

(defn- edges-from [from]
  (set (conn/query '[:find ?to
                     :in $ ?from
                     :where
                     [?e :kg-edge/from ?from]
                     [?e :kg-edge/to ?to]]
                   from)))

(defn- ops [n]
  (mapv (fn [i] {:command "edge" :from "entry-a"
                 :to (str "chunk-" i) :relation "derived-from"
                 :confidence 0.95 :scope "topic:programming"})
        (range n)))

(deftest a-batch-of-edges-costs-one-transaction
  (let [calls (atom 0)
        real  conn/transact!]
    (with-redefs [conn/transact! (fn [& args] (swap! calls inc) (apply real args))]
      (cmd/handle-kg-add-edges {:operations (ops 25)}))
    (is (= 1 @calls)
        (str "25 edges must transact once, not 25 times; got " @calls))))

(deftest every-edge-in-the-batch-is-written-and-readable-on-return
  (let [body (payload (cmd/handle-kg-add-edges {:operations (ops 10)}))]
    (is (= 10 (get-in body [:summary :total])))
    (is (= 10 (get-in body [:summary :success])))
    (is (= 0 (get-in body [:summary :failed])))
    (is (= 10 (count (edges-from "entry-a")))
        "durable-on-return: the batch flushes once before returning")
    (is (every? #(get-in % [:result :edge-id]) (:results body))
        "each op reports the id of the edge it produced")))

(deftest one-invalid-op-is-reported-against-itself-and-the-rest-still-land
  (testing "per-op validation, so a bad spec does not sink its batch"
    (let [mixed (conj (ops 3) {:command "edge" :from "entry-a" :to ""
                               :relation "derived-from"})
          body  (payload (cmd/handle-kg-add-edges {:operations mixed}))]
      (is (= 4 (get-in body [:summary :total])))
      (is (= 3 (get-in body [:summary :success])))
      (is (= 1 (get-in body [:summary :failed])))
      (is (= 3 (count (edges-from "entry-a"))))
      (is (false? (:success (last (:results body))))
          "the failure is attributed to the offending op, not the first one"))))

(deftest an-empty-batch-writes-nothing
  (let [calls (atom 0)
        real  conn/transact!]
    (with-redefs [conn/transact! (fn [& args] (swap! calls inc) (apply real args))]
      (is (= [] (edges/add-edges! []))))
    (is (zero? @calls) "an empty batch must not open a transaction")))

(deftest add-edge-and-add-edges-agree-on-what-a-valid-edge-is
  (testing "single-edge writes go through the same definition as bulk ones"
    (let [id (edges/add-edge! {:from "solo-a" :to "solo-b" :relation :refines
                               :confidence 0.7 :scope "s"})]
      (is (string? id))
      ;; add-edge! is async by contract — durability is the handler's job
      ;; (with-kg-flush), not the write layer's.
      (conn/flush-pending!)
      (is (= #{["solo-b"]} (edges-from "solo-a"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edge! {:from "" :to "x" :relation :refines})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (edges/add-edges! [{:from "x" :to "y" :relation :not-a-relation}])))))
