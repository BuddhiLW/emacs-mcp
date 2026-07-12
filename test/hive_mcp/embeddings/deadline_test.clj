(ns hive-mcp.embeddings.deadline-test
  "Trifecta for the deadline arithmetic.

   The property + mutation facets are SYNTHESIZED from the malli schemas by
   hive-schemas.test — no hand-written generator, oracle, or mutant. The schema
   is the single source: tighten `Budget`/`Ms` and the tests tighten with it.

   Hand-written below only what a schema cannot state: that the clock-aware
   layer actually delegates to the arithmetic (a FixedClock proves it without
   sleeping)."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-schemas.test :as hst]
            [hive-test.mutation :as mut]
            [hive-mcp.embeddings.deadline :as dl]))

;; ============================================================================
;; Schema-synthesized — property + mutation, generated from the malli schemas
;; ============================================================================

;; THE THEOREM: a capped budget never exceeds what remains, and never exceeds
;; what the attempt asked for. That conjunction is exactly what stops N attempts
;; of B ms from summing past the total — the defect that lost two memory writes.
(hst/deftrifecta-from-schema cap-budget-never-overspends
  hive-mcp.embeddings.deadline/cap-budget
  {:in       dl/Budget
   :out      dl/Ms
   :rel      (fn [in out]
               (and (<= out (:remaining-ms in))
                    (<= out (:per-attempt-ms in))
                    (= out (min (:remaining-ms in) (:per-attempt-ms in)))))
   :mutation false
   :num-tests 300})

;; Would the oracle actually CATCH a broken cap? Each mutant is a plausible way
;; to reintroduce the incident.
(mut/deftest-mutations cap-budget-mutants-are-caught
  hive-mcp.embeddings.deadline/cap-budget
  [["ignores what remains — the original bug: every attempt gets its full budget"
    (fn [{:keys [per-attempt-ms]}] per-attempt-ms)]
   ["takes the max instead of the min"
    (fn [{:keys [remaining-ms per-attempt-ms]}] (max per-attempt-ms remaining-ms))]
   ["ignores the attempt's own budget"
    (fn [{:keys [remaining-ms]}] remaining-ms)]]
  (fn []
    ;; Both bounds must be pinned. Asserting only `<= remaining` lets the
    ;; "ignores the attempt's own budget" mutant survive, since it returns
    ;; remaining-ms itself — so the two cases below are deliberately asymmetric.
    (let [starved {:remaining-ms 2000 :per-attempt-ms 12000}
          ample   {:remaining-ms 30000 :per-attempt-ms 12000}]
      (is (<= (dl/cap-budget starved) (:remaining-ms starved))
          "an attempt must never be handed more time than the deadline has left")
      (is (<= (dl/cap-budget ample) (:per-attempt-ms ample))
          "nor more than the attempt's own budget, however much time is left"))))

;; A viable remainder is exactly what `viable-remaining?` must recognize —
;; positive cases from the schema's generator, negatives from its corruptions.
(hst/deftrifecta-predicate viable-remaining-detection
  hive-mcp.embeddings.deadline/viable-remaining?
  {:schema dl/ViableRemaining})

;; ============================================================================
;; Unit — the clock-aware layer delegates (FixedClock: no sleeping)
;; ============================================================================

(deftest deadline-delegates-to-the-arithmetic
  (let [c (dl/fixed-clock 0)
        d (dl/deadline c 10000)]
    (testing "a fresh deadline grants the attempt its full budget"
      (is (= 3000 (dl/attempt-budget-ms d 3000))))
    (testing "once spent, an attempt may only have what is left"
      (dl/advance! c 8000)
      (is (= 2000 (dl/remaining-ms d)))
      (is (= 2000 (dl/attempt-budget-ms d 3000))
          "asked for 3000, only 2000 remains — the cap is what bounds the chain"))
    (testing "past expiry nothing is granted"
      (dl/advance! c 5000)
      (is (= 0 (dl/remaining-ms d)))
      (is (dl/expired? d)))))

(deftest a-sliver-of-budget-is-not-worth-an-attempt
  (let [c (dl/fixed-clock 0)
        d (dl/deadline c 10000)]
    (dl/advance! c 9900)
    (is (= 100 (dl/remaining-ms d)))
    (is (dl/expired? d)
        "100ms cannot buy a useful embed — stop rather than burn the remainder")))
