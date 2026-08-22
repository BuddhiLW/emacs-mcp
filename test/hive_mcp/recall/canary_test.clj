(ns hive-mcp.recall.canary-test
  "The canary's own tests. Every fault predicate is proven BOTH ways: it must
   stay silent on a healthy observation and it must fire on the broken one.
   A canary that cannot be made to fail proves nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.recall.canary :as canary]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures — the canary owns its anchor, and must keep owning it
;; =============================================================================

(deftest every-fixture-is-discoverable-by-tag
  (testing "each fixture carries the shared canary tag plus a role tag, so it is
            re-found by TAG and never by an id someone else can delete"
    (doseq [{:keys [role tags]} canary/fixtures]
      (is (some #{canary/canary-tag} tags)
          (str role " is missing the shared canary tag"))
      (is (= 2 (count tags))
          (str role " must carry exactly [canary-tag role-tag]"))
      (is (= tags (canary/fixture-tags role))))))

(deftest fixture-roles-are-unique-and-complete
  (testing "the three roles the probes address all exist, exactly once"
    (let [roles (mapv :role canary/fixtures)]
      (is (= (count roles) (count (set roles))))
      (is (= #{:anchor :superseded :current} (set roles))))))

(deftest the-anchor-query-tokens-live-in-the-anchor
  (testing "the query the canary fires must actually be answerable by the entry
            it writes — otherwise the probe fails forever for the wrong reason"
    (let [content (:content (canary/fixture :anchor))]
      (doseq [tok ["quokka" "vestibule" "7731"]]
        (is (re-find (re-pattern tok) content)
            (str "anchor content does not contain the rare token " tok))))))

(deftest the-supersession-pair-answers-one-query
  (testing "both supersession fixtures must be plausible answers to the same
            query, or suppression is never exercised"
    (doseq [role [:superseded :current]]
      (is (re-find #"pelican index rebuild" (:content (canary/fixture role)))
          (str role " does not answer the supersession query")))))

;; =============================================================================
;; recall-fault
;; =============================================================================

(deftest recall-fault-is-silent-when-the-anchor-comes-back
  (is (nil? (canary/recall-fault {:label :t :populated? true
                                  :results [{:id "a"} {:id "b"}]
                                  :must-contain ["a"]}))))

(deftest an-empty-store-returning-nothing-is-honest
  (testing "a canary that cries wolf on an empty store gets ignored"
    (is (nil? (canary/recall-fault {:label :t :populated? false
                                    :results [] :must-contain ["a"]})))))

(deftest zero-rows-from-a-populated-store-is-a-system-fault
  (let [f (canary/recall-fault {:label :t :populated? true
                                :results [] :must-contain ["a"]})]
    (is (= :recall/empty-from-populated-store (:fault f)))
    (is (= ["a"] (:expected-ids f)))))

(deftest confident-rows-without-the-anchor-are-the-dangerous-shape
  (let [f (canary/recall-fault {:label :t :populated? true
                                :results [{:id "x"} {:id "y"}]
                                :must-contain ["a"]})]
    (is (= :recall/anchor-missing (:fault f)))
    (is (= ["a"] (:missing-ids f)))
    (is (= ["x" "y"] (:returned-ids f)))))

;; =============================================================================
;; rank-fault
;; =============================================================================

(deftest ascending-distances-pass
  (is (nil? (canary/rank-fault {:label :t :results [{:distance 0.1} {:distance 0.2}]}))))

(deftest descending-distances-are-an-inverted-rank
  (let [f (canary/rank-fault {:label :t :results [{:distance 0.9} {:distance 0.2}]})]
    (is (= :recall/rank-inverted (:fault f)))
    (is (= [0.9 0.2] (:distances f)))))

(deftest rows-without-a-distance-are-ignored
  (testing "tag and KG enrichment hits legitimately carry no distance"
    (is (nil? (canary/rank-fault {:label :t :results [{:id "a"} {:id "b"}]})))))

;; =============================================================================
;; dimension-fault
;; =============================================================================

(deftest matching-widths-pass
  (is (nil? (canary/dimension-fault
             {:label :t :readings [{:collection "c" :expected 2560 :actual 2560}]}))))

(deftest a-width-mismatch-fires
  (let [f (canary/dimension-fault
           {:label :t :readings [{:collection "c" :expected 2560 :actual 768}]})]
    (is (= :recall/dimension-mismatch (:fault f)))
    (is (= 1 (count (:mismatched f))))))

(deftest an-unreadable-width-is-not-a-passing-width
  (testing "nil actual against a known expected must FAIL, not skip — this is
            exactly the shape that made the 2026-07-12 drift invisible"
    (is (= :recall/dimension-mismatch
           (:fault (canary/dimension-fault
                    {:label :t :readings [{:collection "c" :expected 2560 :actual nil}]}))))))

(deftest no-derivable-collection-is-vacuous-not-green
  (let [f (canary/dimension-fault {:label :t :readings [{:collection "c"}]})]
    (is (= :recall/no-collections-configured (:fault f)))))

;; =============================================================================
;; supersession-fault
;; =============================================================================

(deftest the-current-row-alone-passes
  (is (nil? (canary/supersession-fault {:label :t
                                        :results [{:id "new"}]
                                        :superseded-id "old"
                                        :current-id "new"}))))

(deftest returning-the-retracted-row-fires
  (let [f (canary/supersession-fault {:label :t
                                      :results [{:id "new"} {:id "old"}]
                                      :superseded-id "old"
                                      :current-id "new"})]
    (is (= :recall/superseded-returned (:fault f)))
    (is (= "old" (:superseded-id f)))))

(deftest a-dead-lane-is-not-working-suppression
  (testing "neither row came back: clean, but it proves nothing about
            suppression, so it must be reported as its own fault"
    (let [f (canary/supersession-fault {:label :t
                                        :results [{:id "unrelated"}]
                                        :superseded-id "old"
                                        :current-id "new"})]
      (is (= :recall/current-missing (:fault f))))))

;; =============================================================================
;; presence-fault
;; =============================================================================

(deftest a-positive-count-passes
  (is (nil? (canary/presence-fault {:label :t :count 5 :probe "p"}))))

(deftest zero-and-nil-counts-fire
  (is (= :recall/probe-empty (:fault (canary/presence-fault {:label :t :count 0 :probe "p"}))))
  (is (= :recall/probe-empty (:fault (canary/presence-fault {:label :t :count nil :probe "p"})))))

;; =============================================================================
;; verdict — the part that decides whether the tick is green
;; =============================================================================

(deftest all-passing-is-ok
  (let [v (canary/verdict [(canary/outcome :a nil) (canary/outcome :b nil)])]
    (is (:ok? v))
    (is (= 2 (:passed v)))
    (is (empty? (:faults v)))))

(deftest one-fault-fails-the-whole-verdict
  (let [v (canary/verdict [(canary/outcome :a nil)
                           (canary/outcome :b {:fault :recall/anchor-missing})])]
    (is (false? (:ok? v)))
    (is (= 1 (count (:faults v))))))

(deftest a-skipped-probe-never-counts-as-a-pass
  (testing "the whole point: a probe that did not run must be visible, not
            folded into the green count"
    (let [v (canary/verdict [(canary/outcome :a nil)
                             (canary/outcome :b nil "hive-carto not loaded")])]
      (is (:ok? v))
      (is (= 1 (:passed v)) "the skipped probe was counted as passing")
      (is (= [{:label :b :reason "hive-carto not loaded"}] (:skipped v))))))

(deftest an-all-skipped-run-reports-zero-passes
  (testing "a canary that silently stopped running looks identical to a healthy
            one unless the skips are surfaced"
    (let [v (canary/verdict [(canary/outcome :a nil "no store")
                             (canary/outcome :b nil "no store")])]
      (is (zero? (:passed v)))
      (is (= 2 (count (:skipped v)))))))
