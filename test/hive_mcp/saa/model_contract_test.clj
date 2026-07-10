(ns hive-mcp.saa.model-contract-test
  "W1 contract suite: phase model, scorer, planner, and ADT types.

   C3 scorer Korzybski values are exact and ranked desc.
   C4 grounding-score formula matches the lifted handlers.clj formula.
   C5 PhaseMessage + SaaRegistryEntry adt-case exhaustiveness — every
        variant constructs and round-trips."
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [hive-mcp.protocols.saa :as psaa]
            [hive-mcp.saa.model :as model]
            [hive-mcp.saa.prompt :as prompt]
            [hive-mcp.saa.scorer :as scorer]
            [hive-mcp.saa.planner :as planner]
            [hive-mcp.saa.types :as types]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Phase model — ordered, vendor-free
;; =============================================================================

(deftest phase-model-ordering
  (testing "ordered-phases is the canonical silence→abstract→act sequence"
    (is (= [:silence :abstract :act] (model/ordered-phases))))
  (testing "phase->intent surfaces tool + permission intent per phase"
    (is (= {:tool-intent #{:read :search :web} :permission-intent :observe-only}
           (model/phase->intent :silence)))
    (is (= {:tool-intent #{:read :write :exec} :permission-intent :mutate}
           (model/phase->intent :act)))
    (is (nil? (model/phase->intent :no-such-phase)))))

(deftest phase-model-no-vendor-tokens
  (testing "no goal-prompt-fragment leaks a vendor token (build-options is the sole emitter)"
    (doseq [{:keys [goal-prompt-fragment]} model/saa-phase-model]
      (is (not (re-find #"(?i)claude|anthropic|sonnet|opus|gpt|openai"
                        goal-prompt-fragment))
          "phase model fragments are provider-neutral"))))

(deftest build-phase-prompt-embeds-fragment
  (testing "build-phase-prompt* prefixes the phase fragment then the body"
    (let [p (prompt/build-phase-prompt* :silence "do the task" nil)]
      (is (re-find #"SILENCE phase" p))
      (is (re-find #"do the task" p)))))

;; =============================================================================
;; C3 — Korzybski scorer exact values, ranked desc
;; =============================================================================

(def ^:private scorer* (scorer/->default-scorer))

(deftest c3-korzybski-exact-values
  (testing "bug+pattern+test = base 1.0 + issue 3.0 + pattern 2.0 + test 1.5 = 7.5"
    (let [obs "found a bug; follows a pattern; needs a test"
          [scored] (psaa/score scorer* [{:data obs}])]
      (is (= 7.5 (:score scored)))))
  (testing "each Korzybski term contributes its exact weight off the 1.0 base"
    (are [text expected] (= expected (:score (first (psaa/score scorer* [{:data text}]))))
      "neutral observation text"  1.0            ; base only
      "a recurring pattern"       (+ 1.0 2.0)    ; +pattern
      "an error occurred"         (+ 1.0 3.0)    ; +issue
      "a unit test"               (+ 1.0 1.5)))) ; +test

(deftest c3-ranked-descending
  (testing "score returns observations ranked by score, descending"
    (let [obs    [{:data "neutral"}                          ; 1.0
                  {:data "bug pattern test"}                 ; 7.5
                  {:data "a pattern"}]                        ; 3.0
          ranked (psaa/score scorer* obs)
          scores (mapv :score ranked)]
      (is (= [7.5 3.0 1.0] scores))
      (is (apply >= scores) "scores are monotonically non-increasing")
      (is (= (count obs) (count ranked)) "scoring is total — no obs dropped"))))

(defspec c3-scoring-never-loses-observations 50
  (prop/for-all [obs (gen/vector gen/string-ascii 0 20)]
    (let [scored (psaa/score scorer* (mapv (fn [s] {:data s}) obs))
          scores (mapv :score scored)]
      (and (= (count obs) (count scored))
           (every? #(>= (:score %) 1.0) scored)
           (or (< (count scores) 2) (apply >= scores))))))

;; =============================================================================
;; C4 — grounding-score formula parity with the lifted handlers.clj formula
;; =============================================================================
;;
;; Lifted formula:
;;   min(1.0, (if (seq observations) 0.3 0.0)
;;            + (min 0.4 (* 0.1 (count observations)))
;;            + (if (pos? files-read) 0.3 0.0))

(defn- reference-grounding
  "Independent re-statement of the lifted formula."
  [observations files-read]
  (double
   (min 1.0
        (+ (if (seq observations) 0.3 0.0)
           (min 0.4 (* 0.1 (count observations)))
           (if (pos? (or files-read 0)) 0.3 0.0)))))

(deftest c4-grounding-representative-inputs
  (testing "scorer grounding-score matches the reference on representative inputs"
    (are [obs files] (= (reference-grounding obs files)
                        (psaa/grounding-score scorer* obs files))
      []                              0
      [{:data "a"}]                   0
      []                              3
      [{:data "a"}]                   3
      [{:data "a"} {:data "b"}]       5
      (repeat 10 {:data "x"})         7)   ; obs term saturates at 0.4
    )
  (testing "fully-grounded inputs saturate at 1.0"
    (is (= 1.0 (psaa/grounding-score scorer* (repeat 5 {:data "x"}) 9)))))

(defspec c4-grounding-matches-reference 100
  (prop/for-all [n     (gen/choose 0 25)
                 files (gen/choose 0 25)]
    (let [obs (repeat n {:data "x"})]
      (and (= (reference-grounding obs files)
              (psaa/grounding-score scorer* obs files))
           (<= 0.0 (psaa/grounding-score scorer* obs files) 1.0)))))

;; =============================================================================
;; Noop planner — synthesizes nothing
;; =============================================================================

(deftest noop-planner-synthesizes-nil
  (let [p (planner/->noop-planner)]
    (is (satisfies? psaa/IPlanSynthesizer p))
    (is (nil? (psaa/synthesize p [{:observation {} :score 1.0}] "task")))))

;; =============================================================================
;; C5 — PhaseMessage adt-case exhaustiveness, every variant round-trips
;; =============================================================================

(def ^:private phase-message-variants
  [[:pm/started        {:phase :silence}]
   [:pm/chunk          {:phase :silence :content "x"}]
   [:pm/observation    {:phase :silence :observation {:k 1}}]
   [:pm/phase-complete {:phase :act :payload {:ok true}}]
   [:pm/error          {:phase :act :error "e"}]
   [:pm/saa-complete   {:summary {:phases 3}}]])

(deftest c5-phase-message-variants-roundtrip
  (testing "every PhaseMessage variant constructs and is recognized"
    (doseq [[variant data] phase-message-variants]
      (let [pm (types/phase-message variant data)]
        (is (types/phase-message? pm)
            (str variant " did not satisfy phase-message?"))
        (is (= variant (:adt/variant pm)))
        (is (= :PhaseMessage (:adt/type pm)))
        (is (= data (dissoc pm :adt/type :adt/variant))
            "data fields round-trip onto the ADT value")))))

(deftest c5-phase-message-rejects-unknown-variant
  (testing "constructing an undeclared variant throws (closed sum)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (types/phase-message :pm/not-a-variant {:phase :x})))))

;; =============================================================================
;; C5 — SaaRegistryEntry adt-case exhaustiveness, every variant round-trips
;; =============================================================================

(def ^:private registry-entry-variants
  [[:saa/phase-provider {:provider :PROV    :owner :o}]
   [:saa/scorer         {:scorer   :SCORER  :owner :o}]
   [:saa/planner        {:planner  :PLANNER :owner :o}]
   [:saa/tool-intent    {:intent :read :tools ["read"] :owner :o}]])

(deftest c5-registry-entry-variants-roundtrip
  (testing "every SaaRegistryEntry variant constructs and is recognized"
    (doseq [[variant data] registry-entry-variants]
      (let [e (types/saa-registry-entry variant data)]
        (is (types/saa-registry-entry? e)
            (str variant " did not satisfy saa-registry-entry?"))
        (is (= variant (:adt/variant e)))
        (is (= :SaaRegistryEntry (:adt/type e)))))))

(deftest c5-registry-entry-rejects-unknown-variant
  (testing "constructing an undeclared variant throws (closed sum)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (types/saa-registry-entry :saa/not-a-variant {:owner :o})))))
