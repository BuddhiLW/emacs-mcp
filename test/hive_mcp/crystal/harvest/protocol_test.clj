(ns hive-mcp.crystal.harvest.protocol-test
  "Property + golden tests for IHarvestSource protocol and HarvestOutcome ADT.

   Properties:
   - P1: Any IHarvestSource.harvest returns a valid HarvestOutcome
   - P2: source-id always returns a keyword
   - P3: available? always returns boolean
   - P4: HarvestOutcome constructors are total (never throw)
   - P5: harvest-ok data is recoverable via adt-case

   Golden:
   - G1: Known session data → expected harvest shape per source"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.properties :as props]
            [hive-test.generators.core :as gen-core]
            [hive-dsl.adt :as adt :refer [adt-case]]
            [hive-mcp.crystal.harvest.protocol :as proto
             :refer [HarvestOutcome
                     harvest-outcome harvest-outcome?
                     harvest-ok harvest-empty harvest-error]]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-source-keyword
  "Generator for source keywords."
  (gen/elements [:memory :hivemind :kanban :git :custom]))

(def gen-harvest-data
  "Generator for harvest data maps (always has :count)."
  (gen/let [cnt (gen/choose 0 100)
            extra-keys (gen/map gen/keyword gen/string-alphanumeric {:max-elements 3})]
    (assoc extra-keys :count cnt)))

(def gen-elapsed-ms
  "Generator for elapsed milliseconds."
  (gen/choose 0 30000))

(def gen-reason-string
  "Generator for reason strings."
  (gen/elements ["no data found" "source empty" "nothing to harvest" "timeout"]))

(def gen-error-map
  "Generator for error maps."
  (gen/let [t (gen/elements [:io-error :timeout :parse-error :unavailable])
            msg gen-core/gen-non-blank-string]
    {:type t :message msg}))

(def gen-harvest-opts
  "Generator for harvest opts maps."
  (gen/let [dir gen-core/gen-non-blank-string
            agent-id gen-core/gen-agent-id
            project-id gen-core/gen-project-id]
    {:directory dir :agent-id agent-id :project-id project-id}))

;; =============================================================================
;; Stub IHarvestSource for property testing
;; =============================================================================

(defn stub-source
  "Create a stub IHarvestSource that returns a fixed HarvestOutcome."
  [id outcome-fn]
  (reify proto/IHarvestSource
    (source-id [_] id)
    (harvest [_ opts] (outcome-fn opts))
    (available? [_] true)))

(def gen-stub-source
  "Generator for stub IHarvestSource instances with random outcomes."
  (gen/let [src-kw gen-source-keyword
            data gen-harvest-data
            ms gen-elapsed-ms
            reason gen-reason-string
            err gen-error-map
            variant (gen/elements [:ok :empty :error])]
    (stub-source src-kw
                 (case variant
                   :ok    (fn [_] (harvest-ok src-kw data ms))
                   :empty (fn [_] (harvest-empty src-kw reason))
                   :error (fn [_] (harvest-error src-kw err))))))

;; =============================================================================
;; P1 — Any IHarvestSource.harvest returns a valid HarvestOutcome
;; =============================================================================

(defspec p1-harvest-returns-valid-outcome 200
  (prop/for-all [source gen-stub-source
                 opts gen-harvest-opts]
    (let [result (proto/harvest source opts)]
      (harvest-outcome? result))))

;; =============================================================================
;; P2 — source-id always returns a keyword
;; =============================================================================

(defspec p2-source-id-is-keyword 200
  (prop/for-all [source gen-stub-source]
    (keyword? (proto/source-id source))))

;; =============================================================================
;; P3 — available? always returns boolean
;; =============================================================================

(defspec p3-available-is-boolean 200
  (prop/for-all [source gen-stub-source]
    (boolean? (proto/available? source))))

;; =============================================================================
;; P4 — HarvestOutcome constructors are total
;; =============================================================================

(props/defprop-total p4a-harvest-ok-total
  #(harvest-ok (:source %) (:data %) (:ms %))
  (gen/let [s gen-source-keyword
            d gen-harvest-data
            ms gen-elapsed-ms]
    {:source s :data d :ms ms}))

(props/defprop-total p4b-harvest-empty-total
  #(harvest-empty (:source %) (:reason %))
  (gen/let [s gen-source-keyword
            r gen-reason-string]
    {:source s :reason r}))

(props/defprop-total p4c-harvest-error-total
  #(harvest-error (:source %) (:error %))
  (gen/let [s gen-source-keyword
            e gen-error-map]
    {:source s :error e}))

;; =============================================================================
;; P5 — harvest-ok data is recoverable via adt-case
;; =============================================================================

(defspec p5-adt-case-exhaustive 200
  (prop/for-all [src-kw gen-source-keyword
                 data gen-harvest-data
                 ms gen-elapsed-ms
                 reason gen-reason-string
                 err gen-error-map
                 variant (gen/elements [:ok :empty :error])]
    (let [outcome (case variant
                    :ok    (harvest-ok src-kw data ms)
                    :empty (harvest-empty src-kw reason)
                    :error (harvest-error src-kw err))
          matched (adt-case HarvestOutcome outcome
                    :harvest/ok    [:ok (:source outcome)]
                    :harvest/empty [:empty (:source outcome)]
                    :harvest/error [:error (:source outcome)])]
      (and (vector? matched)
           (= (first matched) variant)
           (= (second matched) src-kw)))))

;; =============================================================================
;; Golden: Known harvest outcomes have expected shape
;; =============================================================================

(deftest golden-harvest-ok-shape
  (testing "harvest-ok contains all required fields"
    (let [outcome (harvest-ok :memory {:notes [] :count 0} 42)]
      (is (harvest-outcome? outcome))
      (is (= :HarvestOutcome (:adt/type outcome)))
      (is (= :harvest/ok (:adt/variant outcome)))
      (is (= :memory (:source outcome)))
      (is (= 42 (:elapsed-ms outcome)))
      (is (map? (:data outcome))))))

(deftest golden-harvest-empty-shape
  (testing "harvest-empty contains source and reason"
    (let [outcome (harvest-empty :git "no commits")]
      (is (harvest-outcome? outcome))
      (is (= :harvest/empty (:adt/variant outcome)))
      (is (= :git (:source outcome)))
      (is (= "no commits" (:reason outcome))))))

(deftest golden-harvest-error-shape
  (testing "harvest-error contains source and error map"
    (let [outcome (harvest-error :hivemind {:type :timeout :ms 5000})]
      (is (harvest-outcome? outcome))
      (is (= :harvest/error (:adt/variant outcome)))
      (is (= :hivemind (:source outcome)))
      (is (= :timeout (get-in outcome [:error :type]))))))

(deftest golden-protocol-contract
  (testing "stub source satisfies full IHarvestSource contract"
    (let [src (stub-source :test (fn [_] (harvest-ok :test {:count 5} 10)))]
      (is (= :test (proto/source-id src)))
      (is (true? (proto/available? src)))
      (let [result (proto/harvest src {:directory "/tmp"})]
        (is (harvest-outcome? result))
        (is (= :harvest/ok (:adt/variant result)))
        (is (= 5 (get-in result [:data :count])))))))
