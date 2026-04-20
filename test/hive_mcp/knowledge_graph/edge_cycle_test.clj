(ns hive-mcp.knowledge-graph.edge-cycle-test
  "Unit + property tests for the generic run-cycle! HOF.
   These tests use pure-data fakes and never touch the database."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-mcp.knowledge-graph.edge-cycle :as ec]
            [hive-test.properties :as props]))

;; =============================================================================
;; Fakes
;; =============================================================================

(defn- fake-edge [id score]
  {:kg-edge/id id :kg-edge/score score})

(defn- counting-effect
  "Return [effect-fn counter-atom] where effect-fn records each call."
  []
  (let [calls (atom [])]
    [(fn [edge] (swap! calls conj (:kg-edge/id edge))) calls]))

;; =============================================================================
;; Unit tests
;; =============================================================================

(deftest run-cycle-respects-limit-and-tallies
  (testing "5 candidates, limit 3, all :promoted"
    (let [edges [(fake-edge "a" 0.9)
                 (fake-edge "b" 0.8)
                 (fake-edge "c" 0.7)
                 (fake-edge "d" 0.6)
                 (fake-edge "e" 0.5)]
          [effect! calls] (counting-effect)
          result (ec/run-cycle!
                  {:fetch        (constantly edges)
                   :sort-key     :kg-edge/score
                   :sort-desc?   true
                   :limit        3
                   :outcome-keys [:promoted :skipped :below]
                   :step!        (fn [e] (effect! e) :promoted)})]
      (is (= 3 (:evaluated result)))
      (is (= 3 (:promoted result)))
      (is (= 0 (:skipped result)))
      (is (= 0 (:below result)))
      (is (= 0 (:errors result)))
      (is (= 3 (count @calls)) "effect! invoked exactly limit times")
      (is (= ["a" "b" "c"] @calls) "processed in descending-score order"))))

(deftest run-cycle-seeds-zero-outcome-keys
  (testing "outcome keys are present in tally even with empty input"
    (let [result (ec/run-cycle!
                  {:fetch        (constantly [])
                   :outcome-keys [:promoted :skipped :below]
                   :step!        (fn [_] :promoted)})]
      (is (= 0 (:evaluated result)))
      (is (= 0 (:promoted result)))
      (is (= 0 (:skipped result)))
      (is (= 0 (:below result)))
      (is (= 0 (:errors result))))))

(deftest run-cycle-captures-step-exceptions
  (testing "throwing step! increments :errors and loop continues"
    (let [edges [(fake-edge "a" 0.9)
                 (fake-edge "boom" 0.8)
                 (fake-edge "c" 0.7)]
          err-log-calls (atom [])
          result (ec/run-cycle!
                  {:fetch        (constantly edges)
                   :sort-key     :kg-edge/score
                   :sort-desc?   true
                   :outcome-keys [:promoted]
                   :error-log-fn (fn [edge err]
                                   (swap! err-log-calls conj
                                          {:id (:kg-edge/id edge)
                                           :msg (:message err)}))
                   :step!        (fn [e]
                                   (if (= "boom" (:kg-edge/id e))
                                     (throw (ex-info "nope" {}))
                                     :promoted))})]
      (is (= 3 (:evaluated result)) "loop visited all 3 edges")
      (is (= 2 (:promoted result)) "2 successful promotions")
      (is (= 1 (:errors result)) "1 failure captured")
      (is (= 1 (count @err-log-calls)))
      (is (= "boom" (:id (first @err-log-calls)))))))

(deftest run-cycle-log-fn-called-once
  (testing ":log-fn is invoked with the final tally exactly once"
    (let [calls (atom [])
          _ (ec/run-cycle!
             {:fetch        (constantly [(fake-edge "a" 1.0)])
              :outcome-keys [:promoted]
              :step!        (constantly :promoted)
              :log-fn       (fn [tally] (swap! calls conj tally))})]
      (is (= 1 (count @calls)))
      (is (= 1 (:evaluated (first @calls))))
      (is (= 1 (:promoted (first @calls)))))))

(deftest run-cycle-no-sort-key
  (testing "omitting :sort-key preserves fetch order"
    (let [edges [(fake-edge "x" 0.1)
                 (fake-edge "y" 0.9)
                 (fake-edge "z" 0.5)]
          seen (atom [])
          _ (ec/run-cycle!
             {:fetch        (constantly edges)
              :outcome-keys [:ok]
              :step!        (fn [e] (swap! seen conj (:kg-edge/id e)) :ok)})]
      (is (= ["x" "y" "z"] @seen)))))

;; =============================================================================
;; Property test: outcome counts always sum to :evaluated
;; =============================================================================

(def ^:private outcome-gen
  (gen/elements [:promoted :skipped :below :boom]))

(def ^:private edge-seq-gen
  (gen/fmap
   (fn [outcomes]
     (mapv (fn [i o] {:kg-edge/id (str i) :outcome o})
           (range)
           outcomes))
   (gen/vector outcome-gen 0 20)))

(defn- run-cycle-from-seq
  "Run run-cycle! over the generated edge seq. Each edge carries its own
   desired outcome; :boom makes step! throw."
  [edges]
  (ec/run-cycle!
   {:fetch        (constantly edges)
    :outcome-keys [:promoted :skipped :below]
    :step!        (fn [e]
                    (let [o (:outcome e)]
                      (if (= o :boom)
                        (throw (ex-info "boom" {}))
                        o)))}))

(defn- tally-sum-invariant
  "Return true iff (:evaluated tally) = sum of all other numeric values."
  [tally]
  (let [{:keys [evaluated]} tally
        other (->> (dissoc tally :evaluated)
                   vals
                   (filter number?)
                   (reduce + 0))]
    (= evaluated other)))

(props/defprop-total run-cycle-tally-sums-to-evaluated
  (fn [edges] (tally-sum-invariant (run-cycle-from-seq edges)))
  edge-seq-gen
  {:num-tests 200 :pred true?})
