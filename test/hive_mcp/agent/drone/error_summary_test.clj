(ns hive-mcp.agent.drone.error-summary-test
  "Unit + property tests for the pure drone-error summarizer.

   Coverage:
   - NPE input → class/message/5-frame summary
   - nil → sentinel summary
   - Nested cause chain → top-level cause kept (Throwable getCause + ex-info :cause)
   - Long messages truncated at 512 chars
   - Property: forall throwable, :message ≤ 512 + :frames ≤ 5
   - Property: serialized summary fits configurable budget"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.agent.drone.error-summary :as es]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Unit — Throwable shape
;; =============================================================================

(deftest npe-input-yields-structured-summary
  (testing "NullPointerException → :class + :message + ≤5 :frames"
    (let [e (NullPointerException. "oops")
          s (es/summarize-error e)]
      (is (= "java.lang.NullPointerException" (:class s)))
      (is (= "oops" (:message s)))
      (is (vector? (:frames s)))
      (is (<= (count (:frames s)) 5))
      (is (every? (fn [f]
                    (and (string? (:class f))
                         (string? (:method f))
                         (number? (:line f))))
                  (:frames s)))
      (testing "no cause → no :cause key"
        (is (not (contains? s :cause)))))))

(deftest npe-line-rendering-is-bounded
  (testing "summary->line stays within budget for NPE"
    (let [e    (NullPointerException. "oops")
          s    (es/summarize-error e)
          line (es/summary->line s {:budget 512})]
      (is (<= (count line) 512))
      (is (str/includes? line "java.lang.NullPointerException"))
      (is (str/includes? line "oops")))))

;; =============================================================================
;; Unit — nil sentinel
;; =============================================================================

(deftest nil-input-yields-sentinel
  (testing "nil → empty/sentinel summary, never nil"
    (let [s (es/summarize-error nil)]
      (is (some? s))
      (is (= "nil" (:class s)))
      (is (= "unknown error" (:message s)))
      (is (= [] (:frames s)))
      (is (not (contains? s :cause))))))

;; =============================================================================
;; Unit — nested cause chain
;; =============================================================================

(deftest nested-cause-keeps-top-cause
  (testing "Throwable getCause → :cause walked, top-level retained"
    (let [inner (NullPointerException. "inner-npe")
          outer (RuntimeException. "outer-runtime" inner)
          s     (es/summarize-error outer)]
      (is (= "java.lang.RuntimeException" (:class s)))
      (is (= "outer-runtime" (:message s)))
      (is (= "java.lang.NullPointerException" (-> s :cause :class)))
      (is (= "inner-npe" (-> s :cause :message))))))

(deftest ex-info-extracts-error-type-and-walks-cause
  (testing "ex-info → :error-type from ex-data + cause walked"
    (let [inner (IllegalArgumentException. "bad-arg")
          outer (ex-info "drone-failed" {:error/type :validation} inner)
          s     (es/summarize-error outer)]
      (is (= :validation (:error-type s)))
      (is (= "drone-failed" (:message s)))
      (is (= "java.lang.IllegalArgumentException" (-> s :cause :class)))
      (is (= "bad-arg" (-> s :cause :message))))))

(deftest cause-depth-bounded
  (testing "cause chain bounded by :max-cause-depth"
    (let [deepest (NullPointerException. "deepest")
          mid     (RuntimeException. "mid" deepest)
          top     (RuntimeException. "top" mid)
          shallow (es/summarize-error top {:max-cause-depth 1})
          deep    (es/summarize-error top {:max-cause-depth 5})]
      (testing "depth 1 keeps only one cause level"
        (is (some? (:cause shallow)))
        (is (not (contains? (:cause shallow) :cause))))
      (testing "deeper budget retains full chain"
        (is (= "deepest" (-> deep :cause :cause :message)))))))

;; =============================================================================
;; Unit — message truncation
;; =============================================================================

(deftest long-message-truncated-at-512
  (testing "messages > 512 chars trimmed (with ellipsis suffix)"
    (let [long-msg (apply str (repeat 2000 \x))
          e        (RuntimeException. long-msg)
          s        (es/summarize-error e)]
      (is (<= (count (:message s)) 513))     ; 512 chars + ellipsis
      (is (str/starts-with? (:message s) "xxxxx"))
      (is (str/ends-with? (:message s) "…")))))

(deftest custom-max-message-honored
  (let [e (RuntimeException. (apply str (repeat 100 \z)))
        s (es/summarize-error e {:max-message 32})]
    (is (<= (count (:message s)) 33))))

;; =============================================================================
;; Unit — non-throwable cases (kept for API completeness)
;; =============================================================================

(deftest string-error-summary
  (let [s (es/summarize-error "boom")]
    (is (= "java.lang.String" (:class s)))
    (is (= "boom" (:message s)))
    (is (= [] (:frames s)))))

(deftest map-error-extracts-fields
  (let [s (es/summarize-error {:error/type :timeout :message "stalled"})]
    (is (= :timeout (:error-type s)))
    (is (str/includes? (:message s) "stalled"))))

;; =============================================================================
;; Unit — line renderer progressive trim
;; =============================================================================

(deftest line-renderer-progressive-trim
  (let [inner (NullPointerException. "inner")
        outer (ex-info "outer" {:error/type :validation} inner)
        s     (es/summarize-error outer)]
    (testing "wide budget keeps full line including frame count + cause"
      (let [line (es/summary->line s {:budget 4096})]
        (is (str/includes? line ":validation"))
        (is (str/includes? line "outer"))
        (is (str/includes? line "frames"))
        (is (str/includes? line "←"))))
    (testing "narrow budget drops cause/frames"
      (let [line (es/summary->line s {:budget 40})]
        (is (<= (count line) 40))))
    (testing "very narrow budget falls back to truncated head"
      (let [line (es/summary->line s {:budget 10})]
        (is (<= (count line) 11))))))   ; 10 + ellipsis fallback

;; =============================================================================
;; Unit — fit-to-budget
;; =============================================================================

(deftest fit-to-budget-trims-progressively
  (let [long-msg (apply str (repeat 1000 \z))
        e        (RuntimeException. long-msg
                   (RuntimeException. "mid"
                     (NullPointerException. "deepest")))
        s        (es/summarize-error e)]
    (testing "wide budget keeps full structure"
      (let [fitted (es/fit-to-budget s 8192)]
        (is (= 5 (count (:frames fitted))))
        (is (some? (:cause fitted)))))
    (testing "tight budget drops frames first"
      (let [fitted (es/fit-to-budget s 600)]
        (is (<= (count (pr-str fitted)) 600))))
    (testing "very tight budget produces minimal"
      (let [fitted (es/fit-to-budget s 200)]
        (is (<= (count (pr-str fitted)) 200))))))

;; =============================================================================
;; Property tests
;; =============================================================================

(def gen-throwable
  "Generate a Throwable from a small alphabet of classes, with random message
   length (0..2000) and a 50% chance of a single-level cause."
  (gen/let [msg-len   (gen/choose 0 2000)
            ctor-tag  (gen/elements [:rt :ise :iae :npe :ex])
            has-cause gen/boolean
            cause-msg gen/string-alphanumeric]
    (let [msg   (apply str (repeat msg-len \m))
          mk    (fn [tag m c]
                  (case tag
                    :rt  (if c (RuntimeException. m c)         (RuntimeException. m))
                    :ise (if c (IllegalStateException. m c)    (IllegalStateException. m))
                    :iae (if c (IllegalArgumentException. m c) (IllegalArgumentException. m))
                    :npe (NullPointerException. m)             ; no cause ctor
                    :ex  (ex-info m {:error/type :prop-gen}
                                  (when c c))))
          cause (when has-cause (RuntimeException. cause-msg))]
      (mk ctor-tag msg cause))))

(defspec prop-message-never-exceeds-512 100
  (prop/for-all [t gen-throwable]
    (let [s (es/summarize-error t)]
      (<= (count (:message s)) 513))))   ; 512 + ellipsis

(defspec prop-frames-never-exceed-5 100
  (prop/for-all [t gen-throwable]
    (let [s (es/summarize-error t)]
      (<= (count (:frames s)) 5))))

(defspec prop-cause-frames-also-bounded 100
  (prop/for-all [t gen-throwable]
    (let [s (es/summarize-error t)]
      (or (nil? (:cause s))
          (<= (count (-> s :cause :frames)) 5)))))

(defspec prop-fit-respects-budget 50
  (prop/for-all [t      gen-throwable
                 budget (gen/choose 256 8192)]
    (let [s      (es/summarize-error t)
          fitted (es/fit-to-budget s budget)]
      ;; Tolerate ±32 chars for the minimal-fallback guarantee
      (<= (count (pr-str fitted)) (+ budget 32)))))

(defspec prop-line-respects-budget 50
  (prop/for-all [t      gen-throwable
                 budget (gen/choose 64 4096)]
    (let [s    (es/summarize-error t)
          line (es/summary->line s {:budget budget})]
      (<= (count line) (inc budget)))))   ; +1 for ellipsis fallback

(defspec prop-summary-always-has-required-keys 50
  (prop/for-all [t gen-throwable]
    (let [s (es/summarize-error t)]
      (and (string? (:class s))
           (string? (:message s))
           (vector? (:frames s))))))
