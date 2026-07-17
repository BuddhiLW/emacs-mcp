(ns hive-mcp.knowledge-graph.disc.volatility-test
  "Unit tests for disc.volatility pure computation functions.

   Tests cover:
   - Volatility classification
   - Bayesian certainty (current-certainty, beta-lower-bound, needs-read?, update-certainty)
   - Time decay
   - Staleness scoring (pure, with pre-computed hash result)
   - Entry staleness scoring
   - Format staleness warnings

   All functions tested here are PURE — no DataScript fixtures needed."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.disc.volatility :as vol]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Volatility Classification Tests
;; =============================================================================

(deftest classify-volatility-stable-files-test
  (testing "classify-volatility identifies stable files"
    (is (= :stable (vol/classify-volatility "deps.edn")))
    (is (= :stable (vol/classify-volatility "/foo/bar/project.clj")))
    (is (= :stable (vol/classify-volatility "config/pom.xml")))
    (is (= :stable (vol/classify-volatility ".gitignore")))))

(deftest classify-volatility-volatile-files-test
  (testing "classify-volatility identifies volatile files"
    (is (= :volatile (vol/classify-volatility "app.log")))
    (is (= :volatile (vol/classify-volatility "/tmp/foo.tmp")))
    (is (= :volatile (vol/classify-volatility "target/classes/foo.class")))
    (is (= :volatile (vol/classify-volatility ".nrepl-port")))))

(deftest classify-volatility-moderate-default-test
  (testing "classify-volatility defaults to moderate for source files"
    (is (= :moderate (vol/classify-volatility "src/core.clj")))
    (is (= :moderate (vol/classify-volatility "test/foo_test.clj")))
    (is (= :moderate (vol/classify-volatility "README.md")))
    (is (= :moderate (vol/classify-volatility "build.gradle")))))

;; =============================================================================
;; Constants Tests
;; =============================================================================

(deftest decay-rates-test
  (testing "decay-rates has all volatility classes"
    (is (contains? vol/decay-rates :stable))
    (is (contains? vol/decay-rates :moderate))
    (is (contains? vol/decay-rates :volatile))
    (is (< (:stable vol/decay-rates) (:moderate vol/decay-rates) (:volatile vol/decay-rates)))))

(deftest staleness-decay-factor-test
  (testing "staleness-decay-factor is 0.5 (halves per hop)"
    (is (= 0.5 vol/staleness-decay-factor))))

(deftest staleness-min-threshold-test
  (testing "staleness-min-threshold stops propagation at 0.3"
    (is (= 0.3 vol/staleness-min-threshold))))

(deftest staleness-max-depth-test
  (testing "staleness-max-depth limits propagation to 5 hops"
    (is (= 5 vol/staleness-max-depth))))

(deftest base-staleness-values-test
  (testing "base-staleness-values has correct severity ordering"
    (is (> (:hash-mismatch vol/base-staleness-values)
           (:git-commit vol/base-staleness-values)))
    (is (> (:git-commit vol/base-staleness-values)
           (:time-decay vol/base-staleness-values)))))

(deftest initial-alpha-by-volatility-test
  (testing "initial-alpha-by-volatility has correct confidence ordering"
    (is (> (:stable vol/initial-alpha-by-volatility)
           (:moderate vol/initial-alpha-by-volatility)
           (:volatile vol/initial-alpha-by-volatility)))))

;; =============================================================================
;; Bayesian Certainty Tests
;; =============================================================================

(deftest current-certainty-default-priors-test
  (testing "current-certainty with default priors (alpha=5, beta=2)"
    (let [disc {}]
      ;; Expected: 5 / (5 + 2) = 0.714...
      (is (< 0.71 (vol/current-certainty disc) 0.72)))))

(deftest current-certainty-custom-values-test
  (testing "current-certainty with explicit alpha/beta values"
    ;; alpha=10, beta=5 → 10/(10+5) = 0.666...
    (is (< 0.66 (vol/current-certainty {:disc/certainty-alpha 10.0
                                        :disc/certainty-beta 5.0}) 0.67))
    ;; alpha=1, beta=1 → 0.5 (maximum uncertainty)
    (is (= 0.5 (vol/current-certainty {:disc/certainty-alpha 1.0
                                       :disc/certainty-beta 1.0})))
    ;; alpha=99, beta=1 → ~0.99 (very high certainty)
    (is (< 0.98 (vol/current-certainty {:disc/certainty-alpha 99.0
                                        :disc/certainty-beta 1.0}) 1.0))))

(deftest beta-lower-bound-test
  (testing "beta-lower-bound returns conservative credible interval"
    (let [low-confidence {:disc/certainty-alpha 2.0 :disc/certainty-beta 2.0}
          high-confidence {:disc/certainty-alpha 50.0 :disc/certainty-beta 10.0}]
      (is (< (vol/beta-lower-bound low-confidence) 0.5))
      (is (> (vol/beta-lower-bound high-confidence) 0.7)))))

(deftest needs-read-threshold-test
  (testing "needs-read? triggers below threshold"
    (let [high-certainty {:disc/certainty-alpha 20.0 :disc/certainty-beta 2.0}
          low-certainty {:disc/certainty-alpha 2.0 :disc/certainty-beta 10.0}]
      (is (false? (vol/needs-read? high-certainty)))
      (is (true? (vol/needs-read? low-certainty))))))

(deftest needs-read-custom-threshold-test
  (testing "needs-read? respects custom threshold"
    (let [disc {:disc/certainty-alpha 8.0 :disc/certainty-beta 2.0}]
      ;; Certainty is 8/10 = 0.8
      (is (false? (vol/needs-read? disc 0.7)))
      (is (true? (vol/needs-read? disc 0.9))))))

;; =============================================================================
;; Certainty Update Tests
;; =============================================================================

(deftest update-certainty-read-confirmed-test
  (testing "update-certainty :read-confirmed increases alpha by 3"
    (let [disc {:disc/certainty-alpha 5.0 :disc/certainty-beta 2.0}
          updated (vol/update-certainty disc :read-confirmed)]
      (is (= 8.0 (:disc/certainty-alpha updated)))
      (is (= 2.0 (:disc/certainty-beta updated))))))

(deftest update-certainty-hash-mismatch-test
  (testing "update-certainty :hash-mismatch increases beta by 5"
    (let [disc {:disc/certainty-alpha 5.0 :disc/certainty-beta 2.0}
          updated (vol/update-certainty disc :hash-mismatch)]
      (is (= 5.0 (:disc/certainty-alpha updated)))
      (is (= 7.0 (:disc/certainty-beta updated))))))

(deftest update-certainty-git-commit-touched-test
  (testing "update-certainty :git-commit-touched increases beta by 2"
    (let [disc {:disc/certainty-alpha 5.0 :disc/certainty-beta 2.0}
          updated (vol/update-certainty disc :git-commit-touched)]
      (is (= 5.0 (:disc/certainty-alpha updated)))
      (is (= 4.0 (:disc/certainty-beta updated))))))

(deftest update-certainty-time-decay-test
  (testing "update-certainty :time-decay increases beta by 0.5"
    (let [disc {:disc/certainty-alpha 5.0 :disc/certainty-beta 2.0}
          updated (vol/update-certainty disc :time-decay)]
      (is (= 5.0 (:disc/certainty-alpha updated)))
      (is (= 2.5 (:disc/certainty-beta updated))))))

(deftest update-certainty-unknown-event-test
  (testing "update-certainty with unknown event leaves values unchanged"
    (let [disc {:disc/certainty-alpha 5.0 :disc/certainty-beta 2.0}
          updated (vol/update-certainty disc :unknown-event)]
      (is (= 5.0 (:disc/certainty-alpha updated)))
      (is (= 2.0 (:disc/certainty-beta updated))))))

(deftest certainty-increases-with-confirmations-test
  (testing "Repeated read-confirmed events monotonically increase certainty"
    (let [initial {:disc/certainty-alpha 5.0 :disc/certainty-beta 2.0}
          after-1 (vol/update-certainty initial :read-confirmed)
          after-2 (vol/update-certainty after-1 :read-confirmed)
          after-3 (vol/update-certainty after-2 :read-confirmed)]
      (is (< (vol/current-certainty initial)
             (vol/current-certainty after-1)
             (vol/current-certainty after-2)
             (vol/current-certainty after-3))))))

(deftest certainty-decreases-with-refutations-test
  (testing "Hash mismatch events decrease certainty"
    (let [initial {:disc/certainty-alpha 10.0 :disc/certainty-beta 2.0}
          after-mismatch (vol/update-certainty initial :hash-mismatch)]
      (is (> (vol/current-certainty initial)
             (vol/current-certainty after-mismatch))))))

;; =============================================================================
;; Time Decay Tests
;; =============================================================================

(deftest apply-time-decay-uses-volatility-rate-test
  (testing "apply-time-decay uses rate based on volatility class"
    (let [ten-days-ago (java.util.Date. (- (System/currentTimeMillis) (* 10 86400000)))
          stable-disc {:disc/certainty-alpha 7.0
                       :disc/certainty-beta 2.0
                       :disc/volatility-class :stable
                       :disc/last-observation ten-days-ago}
          volatile-disc {:disc/certainty-alpha 3.0
                         :disc/certainty-beta 2.0
                         :disc/volatility-class :volatile
                         :disc/last-observation ten-days-ago}
          decayed-stable (vol/apply-time-decay stable-disc)
          decayed-volatile (vol/apply-time-decay volatile-disc)]
      ;; Stable: 10 days * 0.01/day = 0.1 added to beta
      (is (< (Math/abs (- (:disc/certainty-beta decayed-stable) 2.1)) 0.05))
      ;; Volatile: 10 days * 0.15/day = 1.5 added to beta
      (is (< (Math/abs (- (:disc/certainty-beta decayed-volatile) 3.5)) 0.05)))))

(deftest apply-time-decay-updates-last-observation-test
  (testing "apply-time-decay updates last-observation timestamp"
    (let [old-date (java.util.Date. (- (System/currentTimeMillis) 86400000))
          disc {:disc/certainty-beta 2.0
                :disc/volatility-class :moderate
                :disc/last-observation old-date}
          decayed (vol/apply-time-decay disc)]
      (is (< (- (System/currentTimeMillis)
                (.getTime ^java.util.Date (:disc/last-observation decayed)))
             1000)))))

(deftest apply-time-decay-no-decay-when-recent-test
  (testing "apply-time-decay adds minimal decay when last observation is recent"
    (let [now (java.util.Date.)
          disc {:disc/certainty-alpha 5.0
                :disc/certainty-beta 2.0
                :disc/volatility-class :moderate
                :disc/last-observation now}
          decayed (vol/apply-time-decay disc)]
      (is (< (Math/abs (- (:disc/certainty-beta decayed) 2.0)) 0.01)))))

;; =============================================================================
;; Staleness Score Tests (Pure — accepts pre-computed hash)
;; =============================================================================

(deftest staleness-score-fresh-file-test
  (testing "staleness-score returns low score for fresh file with matching hash"
    (let [now (java.util.Date.)
          disc {:disc/content-hash "abc123"
                :disc/last-read-at now
                :disc/analyzed-at now}
          hash-result {:hash "abc123" :exists? true}]
      (is (= 0.0 (vol/staleness-score disc hash-result))))))

(deftest staleness-score-hash-mismatch-test
  (testing "staleness-score adds 0.5 for hash mismatch"
    (let [now (java.util.Date.)
          disc {:disc/content-hash "old-hash"
                :disc/last-read-at now
                :disc/analyzed-at now}
          hash-result {:hash "new-hash" :exists? true}]
      (is (= 0.5 (vol/staleness-score disc hash-result))))))

(deftest staleness-score-never-read-test
  (testing "staleness-score adds 0.3 when never read"
    (let [disc {:disc/content-hash "abc"
                :disc/analyzed-at (java.util.Date.)}
          hash-result {:hash "abc" :exists? true}]
      ;; No :disc/last-read-at → +0.3
      (is (= 0.3 (vol/staleness-score disc hash-result))))))

(deftest staleness-score-never-analyzed-test
  (testing "staleness-score adds 0.2 when never analyzed"
    (let [now (java.util.Date.)
          disc {:disc/content-hash "abc"
                :disc/last-read-at now}
          hash-result {:hash "abc" :exists? true}]
      ;; No :disc/analyzed-at → +0.2
      (is (= 0.2 (vol/staleness-score disc hash-result))))))

(deftest staleness-score-old-file-test
  (testing "staleness-score adds 0.5 for >30 days since read"
    (let [old-date (java.util.Date. (- (System/currentTimeMillis)
                                       (* 45 24 60 60 1000)))
          disc {:disc/content-hash "abc"
                :disc/last-read-at old-date
                :disc/analyzed-at old-date}
          hash-result {:hash "abc" :exists? true}]
      (is (= 0.5 (vol/staleness-score disc hash-result))))))

(deftest staleness-score-nil-hash-result-test
  (testing "staleness-score works with nil hash result (no hash check)"
    (let [now (java.util.Date.)
          disc {:disc/content-hash "abc"
                :disc/last-read-at now
                :disc/analyzed-at now}]
      (is (= 0.0 (vol/staleness-score disc nil))))))

(deftest staleness-score-max-one-test
  (testing "staleness-score caps at 1.0"
    (let [old-date (java.util.Date. (- (System/currentTimeMillis)
                                       (* 45 24 60 60 1000)))
          disc {:disc/content-hash "old"
                :disc/last-read-at old-date}
          hash-result {:hash "new" :exists? true}]
      ;; hash-mismatch(0.5) + old(0.5) + never-analyzed(0.2) = 1.2 → capped at 1.0
      (is (= 1.0 (vol/staleness-score disc hash-result))))))

;; =============================================================================
;; Staleness Report Tests
;; =============================================================================

(deftest staleness-report-structure-test
  (testing "staleness-report returns correct structure"
    (let [now (java.util.Date.)
          disc {:disc/content-hash "abc"
                :disc/last-read-at now
                :disc/analyzed-at now}
          hash-result {:hash "abc" :exists? true}
          report (vol/staleness-report disc hash-result)]
      (is (contains? report :score))
      (is (contains? report :days-since-read))
      (is (contains? report :hash-mismatch?))
      (is (contains? report :never-analyzed?))
      (is (= 0.0 (:score report)))
      (is (false? (:hash-mismatch? report)))
      (is (false? (:never-analyzed? report))))))

(deftest staleness-report-detects-mismatch-test
  (testing "staleness-report detects hash mismatch"
    (let [disc {:disc/content-hash "old"
                :disc/last-read-at (java.util.Date.)
                :disc/analyzed-at (java.util.Date.)}
          hash-result {:hash "new" :exists? true}
          report (vol/staleness-report disc hash-result)]
      (is (true? (:hash-mismatch? report)))
      (is (>= (:score report) 0.5)))))

;; =============================================================================
;; Entry Staleness Tests
;; =============================================================================

(deftest entry-staleness-score-default-test
  (testing "entry-staleness-score with default alpha=1, beta=1 returns 0.5"
    (is (= 0.5 (vol/entry-staleness-score {})))))

(deftest entry-staleness-score-high-alpha-test
  (testing "entry-staleness-score with high alpha is low (fresh)"
    (let [entry {:staleness-alpha 9.0 :staleness-beta 1.0}]
      (is (< (vol/entry-staleness-score entry) 0.15)))))

(deftest entry-staleness-score-high-beta-test
  (testing "entry-staleness-score with high beta is high (stale)"
    (let [entry {:staleness-alpha 1.0 :staleness-beta 9.0}]
      (is (> (vol/entry-staleness-score entry) 0.85)))))

(deftest entry-staleness-report-structure-test
  (testing "entry-staleness-report returns correct structure"
    (let [entry {:id "test-123"
                 :staleness-alpha 5.0
                 :staleness-beta 2.0
                 :staleness-source :hash-mismatch
                 :staleness-depth 1
                 :grounded-from "/path/to/file.clj"}
          report (vol/entry-staleness-report entry)]
      (is (= "test-123" (:id report)))
      (is (number? (:score report)))
      (is (= 5.0 (:alpha report)))
      (is (= 2.0 (:beta report)))
      (is (= :hash-mismatch (:source report)))
      (is (= 1 (:depth report)))
      (is (= "/path/to/file.clj" (:grounded-from report))))))

(deftest entry-staleness-report-computes-score-test
  (testing "entry-staleness-report score matches entry-staleness-score"
    (let [entry {:staleness-alpha 3.0 :staleness-beta 7.0}
          report (vol/entry-staleness-report entry)]
      ;; 1 - (3/10) = 0.7
      (is (< (Math/abs (- (:score report) 0.7)) 0.01)))))

;; =============================================================================
;; Format Staleness Warnings Tests
;; =============================================================================

(deftest format-staleness-warnings-test
  (testing "format-staleness-warnings produces markdown output"
    (let [warnings [{:path "/a.clj" :staleness 0.5 :message "File /a.clj is stale"}
                    {:path "/b.clj" :staleness 0.7 :message "File /b.clj is stale"}]
          formatted (vol/format-staleness-warnings warnings)]
      (is (string? formatted))
      (is (.contains formatted "file-level Staleness Warnings"))
      (is (.contains formatted "/a.clj"))
      (is (.contains formatted "/b.clj")))))

(deftest format-staleness-warnings-nil-for-empty-test
  (testing "format-staleness-warnings returns nil for empty"
    (is (nil? (vol/format-staleness-warnings [])))))

;; =============================================================================
;; Staleness Decay Calculation Tests
;; =============================================================================

(deftest staleness-decay-calculation-test
  (testing "Staleness decays correctly with depth"
    (let [base 5.0
          depth-0 (* base (Math/pow vol/staleness-decay-factor 0))
          depth-1 (* base (Math/pow vol/staleness-decay-factor 1))
          depth-2 (* base (Math/pow vol/staleness-decay-factor 2))
          depth-3 (* base (Math/pow vol/staleness-decay-factor 3))]
      (is (= 5.0 depth-0))
      (is (= 2.5 depth-1))
      (is (= 1.25 depth-2))
      (is (< (Math/abs (- depth-3 0.625)) 0.001))
      (is (>= depth-3 vol/staleness-min-threshold)))))
