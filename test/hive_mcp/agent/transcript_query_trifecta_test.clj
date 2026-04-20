(ns hive-mcp.agent.transcript-query-trifecta-test
  "Trifecta tests for TranscriptQuery + TranscriptSource ADTs.

   Unit:    Construction, dispatch exhaustiveness
   Property: Totality, structural invariants
   Golden:  ADT variant sets snapshot"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.golden :as golden]
            [hive-mcp.agent.transcript-query :as tq]
            [hive-dsl.adt :refer [adt-case adt-variant]]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-agent-id
  (gen/fmap #(str "agent-" %) (gen/such-that (complement empty?) gen/string-alphanumeric)))

(def gen-pos-int
  (gen/fmap #(int (Math/abs ^int %)) gen/small-integer))

(def gen-query-by-agent
  (gen/fmap #(tq/transcript-query :query/by-agent {:agent-id %})
            gen-agent-id))

(def gen-query-by-time
  (gen/fmap (fn [[a b]] (tq/transcript-query :query/by-time
                          {:start-ms (int (min a b)) :end-ms (int (max a b))}))
            (gen/tuple gen-pos-int gen-pos-int)))

(def gen-query-since
  (gen/fmap (fn [[id t]] (tq/transcript-query :query/since
                           {:agent-id id :turn (int t)}))
            (gen/tuple gen-agent-id gen-pos-int)))

(def gen-query-tail
  (gen/fmap (fn [[id n]] (tq/transcript-query :query/tail
                           {:agent-id id :n (int (max 1 n))}))
            (gen/tuple gen-agent-id gen-pos-int)))

(def gen-any-query
  (gen/one-of [gen-query-by-agent gen-query-by-time gen-query-since gen-query-tail]))

(def gen-source
  (gen/fmap #(tq/transcript-source %)
            (gen/elements [:source/datalevin :source/jsonl :source/auto])))

;; =============================================================================
;; Unit Tests: TranscriptQuery
;; =============================================================================

(deftest query-adt-registered-test
  (testing "TranscriptQuery has 4 variants"
    (is (= #{:query/by-agent :query/by-time :query/since :query/tail}
           (:variants tq/TranscriptQuery)))))

(deftest query-construction-test
  (testing "All 4 query variants construct correctly"
    (let [q1 (tq/transcript-query :query/by-agent {:agent-id "a1"})
          q2 (tq/transcript-query :query/by-time {:start-ms (int 0) :end-ms (int 100)})
          q3 (tq/transcript-query :query/since {:agent-id "a1" :turn (int 5)})
          q4 (tq/transcript-query :query/tail {:agent-id "a1" :n (int 10)})]
      (is (= :query/by-agent (adt-variant q1)))
      (is (= :query/by-time (adt-variant q2)))
      (is (= :query/since (adt-variant q3)))
      (is (= :query/tail (adt-variant q4)))
      ;; Field access
      (is (= "a1" (:agent-id q1)))
      (is (= 0 (:start-ms q2)))
      (is (= 5 (:turn q3)))
      (is (= 10 (:n q4))))))

(deftest query-exhaustive-dispatch-test
  (testing "adt-case dispatches all 4 without error"
    (doseq [q [(tq/transcript-query :query/by-agent {:agent-id "x"})
               (tq/transcript-query :query/by-time {:start-ms (int 0) :end-ms (int 1)})
               (tq/transcript-query :query/since {:agent-id "x" :turn (int 0)})
               (tq/transcript-query :query/tail {:agent-id "x" :n (int 1)})]]
      (is (keyword?
            (adt-case tq/TranscriptQuery q
              :query/by-agent :agent
              :query/by-time  :time
              :query/since    :since
              :query/tail     :tail))))))

;; =============================================================================
;; Unit Tests: TranscriptSource
;; =============================================================================

(deftest source-adt-registered-test
  (testing "TranscriptSource has 3 variants"
    (is (= #{:source/datalevin :source/jsonl :source/auto}
           (:variants tq/TranscriptSource)))))

(deftest source-construction-test
  (testing "All 3 source variants construct correctly"
    (doseq [v [:source/datalevin :source/jsonl :source/auto]]
      (let [s (tq/transcript-source v)]
        (is (= :TranscriptSource (:adt/type s)))
        (is (= v (adt-variant s)))))))

(deftest source-exhaustive-dispatch-test
  (testing "adt-case dispatches all 3 without error"
    (doseq [v [:source/datalevin :source/jsonl :source/auto]]
      (let [s (tq/transcript-source v)]
        (is (keyword?
              (adt-case tq/TranscriptSource s
                :source/datalevin :dl
                :source/jsonl     :jl
                :source/auto      :auto)))))))

;; =============================================================================
;; Property Tests
;; =============================================================================

(defspec query-always-has-adt-type 200
  (prop/for-all [q gen-any-query]
    (= :TranscriptQuery (:adt/type q))))

(defspec query-predicate-consistent 200
  (prop/for-all [q gen-any-query]
    (tq/transcript-query? q)))

(defspec query-variant-in-closed-set 200
  (prop/for-all [q gen-any-query]
    (contains? #{:query/by-agent :query/by-time :query/since :query/tail}
               (adt-variant q))))

(defspec source-always-has-adt-type 100
  (prop/for-all [s gen-source]
    (= :TranscriptSource (:adt/type s))))

(defspec source-predicate-consistent 100
  (prop/for-all [s gen-source]
    (tq/transcript-source? s)))

(defspec query-dispatch-never-throws 200
  (prop/for-all [q gen-any-query]
    (keyword?
      (adt-case tq/TranscriptQuery q
        :query/by-agent :agent
        :query/by-time  :time
        :query/since    :since
        :query/tail     :tail))))

(defspec by-agent-always-has-agent-id 100
  (prop/for-all [q gen-query-by-agent]
    (string? (:agent-id q))))

(defspec tail-n-always-positive 100
  (prop/for-all [q gen-query-tail]
    (pos? (:n q))))

(defspec by-time-start-lte-end 100
  (prop/for-all [q gen-query-by-time]
    (<= (:start-ms q) (:end-ms q))))

;; =============================================================================
;; Golden Tests
;; =============================================================================

(deftest query-variant-set-golden
  (golden/assert-golden
    "test/golden/transcript-query-variants.edn"
    {:query-variants  (sort (:variants tq/TranscriptQuery))
     :source-variants (sort (:variants tq/TranscriptSource))}))
