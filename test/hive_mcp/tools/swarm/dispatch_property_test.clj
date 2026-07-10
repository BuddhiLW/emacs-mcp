(ns hive-mcp.tools.swarm.dispatch-property-test
  "Property-based tests for the pure Layer 1 functions in dispatch.clj.

   Tests verify invariants of:
   - resolve-spawn-mode: totality, always returns keyword
   - inject-shout-reminder: suffix presence, prompt preservation
   - build-changes-section: totality for any entry list
   - inject-changes-section: prompt preservation, identity on nil
   - inject-staleness-context: identity on empty context
   - extract-file-paths: totality
   - format-time-ago: totality, nil-safety
   - format-lines-delta: totality"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.generators.core :as gen-core]
            [hive-test.properties :as props]
            [hive-mcp.tools.swarm.dispatch :as dispatch]
            [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-spawn-mode
  "Generator for valid spawn mode keywords (post-:openrouter cleanup)."
  (gen/elements [:claude :vterm :headless :agent-sdk]))

(def gen-slave-data
  "Generator for slave data maps (as returned by DataScript query)."
  (gen/one-of
   [(gen/return nil)
    (gen/return {})
    (gen/hash-map :ling/spawn-mode gen-spawn-mode)]))

(def gen-change-entry
  "Generator for recent change entries."
  (gen/hash-map
   :file (gen/fmap #(str "/src/" % ".clj") gen/string-alphanumeric)
   :slave-id (gen/fmap #(str "slave-" %) gen/string-alphanumeric)
   :lines-added gen/nat
   :lines-removed gen/nat
   :released-at (gen/fmap #(java.util.Date.
                            (max 0 (- (System/currentTimeMillis)
                                      (* (long %) 60000))))
                          (gen/choose 0 1440))))

;; =============================================================================
;; Private function access
;; =============================================================================

(def ^:private extract-file-paths @#'dispatch/extract-file-paths)
(def ^:private format-time-ago @#'dispatch/format-time-ago)
(def ^:private format-lines-delta @#'dispatch/format-lines-delta)

;; =============================================================================
;; P1: resolve-spawn-mode — totality, always returns keyword
;; =============================================================================

(props/defprop-total p1-resolve-spawn-mode-totality
  dispatch/resolve-spawn-mode gen-slave-data
  {:pred keyword?})

(deftest p1-resolve-spawn-mode-defaults
  (testing "Returns :claude for nil slave data"
    (is (= :claude (dispatch/resolve-spawn-mode nil))))
  (testing "Returns :claude for empty slave data"
    (is (= :claude (dispatch/resolve-spawn-mode {}))))
  (testing "Returns specified mode when present"
    (is (= :headless (dispatch/resolve-spawn-mode {:ling/spawn-mode :headless})))
    (is (= :agent-sdk (dispatch/resolve-spawn-mode {:ling/spawn-mode :agent-sdk})))))

;; =============================================================================
;; P2: inject-shout-reminder — suffix presence, prompt preservation
;; =============================================================================

(defspec p2-shout-reminder-appends-suffix 200
  (prop/for-all [prompt gen-core/gen-non-blank-string]
                (str/ends-with? (dispatch/inject-shout-reminder prompt)
                                dispatch/shout-reminder-suffix)))

(defspec p2-shout-reminder-preserves-prompt 200
  (prop/for-all [prompt gen-core/gen-non-blank-string]
                (str/starts-with? (dispatch/inject-shout-reminder prompt)
                                  prompt)))

(defspec p2-shout-reminder-length-invariant 200
  (prop/for-all [prompt gen-core/gen-non-blank-string]
                (= (count (dispatch/inject-shout-reminder prompt))
                   (+ (count prompt) (count dispatch/shout-reminder-suffix)))))

(defspec p2-shout-reminder-contains-hivemind 200
  (prop/for-all [prompt gen-core/gen-non-blank-string]
                (str/includes? (dispatch/inject-shout-reminder prompt)
                               "hivemind_shout")))

;; =============================================================================
;; P3: build-changes-section — totality for any entry list
;; =============================================================================

(props/defprop-total p3-build-changes-section-totality
  dispatch/build-changes-section (gen/vector gen-change-entry 0 20)
  {:pred #(or (nil? %) (string? %))})

(defspec p3-build-changes-section-nil-for-empty 100
  (prop/for-all [_ gen/any-printable]
                (nil? (dispatch/build-changes-section []))))

(defspec p3-build-changes-section-nil-for-nil 100
  (prop/for-all [_ gen/any-printable]
                (nil? (dispatch/build-changes-section nil))))

(defspec p3-build-changes-section-non-nil-for-nonempty 200
  (prop/for-all [changes (gen/vector gen-change-entry 1 10)]
                (some? (dispatch/build-changes-section changes))))

(defspec p3-build-changes-section-contains-header 200
  (prop/for-all [changes (gen/vector gen-change-entry 1 5)]
                (str/includes? (dispatch/build-changes-section changes)
                               "Recent File Changes")))

;; =============================================================================
;; P4: inject-changes-section — prompt preservation, identity on nil
;; =============================================================================

(defspec p4-inject-changes-preserves-prompt 200
  (prop/for-all [prompt gen-core/gen-non-blank-string
                 changes (gen/vector gen-change-entry 0 5)]
                (let [section (dispatch/build-changes-section changes)
                      result (dispatch/inject-changes-section prompt section)]
                  (str/includes? result prompt))))

(props/defprop-idempotent p4-inject-changes-identity-when-nil
  #(dispatch/inject-changes-section % nil)
  gen-core/gen-non-blank-string)

(defspec p4-inject-changes-prepends-section 200
  (prop/for-all [prompt gen-core/gen-non-blank-string
                 changes (gen/vector gen-change-entry 1 3)]
                (let [section (dispatch/build-changes-section changes)
                      result (dispatch/inject-changes-section prompt section)]
                  (str/starts-with? result section))))

;; =============================================================================
;; P5: inject-staleness-context — identity with empty context
;; =============================================================================

(props/defprop-idempotent p5-staleness-identity-empty-map
  #(dispatch/inject-staleness-context % {})
  gen-core/gen-non-blank-string)

(defspec p5-staleness-identity-empty-stale 200
  (prop/for-all [prompt gen-core/gen-non-blank-string]
                (= prompt (dispatch/inject-staleness-context prompt {:stale []}))))

(defspec p5-staleness-identity-nil-stale 200
  (prop/for-all [prompt gen-core/gen-non-blank-string]
                (= prompt (dispatch/inject-staleness-context prompt {:stale nil}))))

;; =============================================================================
;; P6: extract-file-paths — totality
;; =============================================================================

(props/defprop-total p6-extract-file-paths-totality
  extract-file-paths gen/string-alphanumeric
  {:pred #(or (nil? %) (vector? %))})

(deftest p6-extract-file-paths-known-inputs
  (testing "Extracts Clojure file paths"
    (let [result (extract-file-paths "Fix the bug in /src/core.clj please")]
      (is (some #(str/ends-with? % ".clj") result))))
  (testing "Returns nil or empty for no paths"
    (let [result (extract-file-paths "hello world")]
      (is (or (nil? result) (empty? result)))))
  (testing "Handles nil/empty input"
    (is (nil? (extract-file-paths nil)))
    (is (nil? (extract-file-paths "")))))

;; =============================================================================
;; P7: format-time-ago — nil-safety, returns string for valid dates
;; =============================================================================

(def ^:private gen-recent-date
  "Generator for Date objects within the last 7 days."
  (gen/fmap #(java.util.Date. (- (System/currentTimeMillis)
                                 (* (long %) 60000)))
            (gen/choose 0 (* 7 24 60))))

(props/defprop-total p7-format-time-ago-totality
  format-time-ago gen-recent-date
  {:num-tests 100 :pred string?})

(deftest p7-format-time-ago-nil-safety
  (testing "Returns nil for nil timestamp"
    (is (nil? (format-time-ago nil)))))

(deftest p7-format-time-ago-ranges
  (testing "Just now for recent timestamps"
    (is (= "just now" (format-time-ago (java.util.Date.)))))
  (testing "Minutes for sub-hour"
    (let [result (format-time-ago (java.util.Date. (- (System/currentTimeMillis) 300000)))]
      (is (str/ends-with? result "m ago"))))
  (testing "Hours for sub-day"
    (let [result (format-time-ago (java.util.Date. (- (System/currentTimeMillis) 7200000)))]
      (is (str/ends-with? result "h ago")))))

;; =============================================================================
;; P8: format-lines-delta — totality
;; =============================================================================

(props/defprop-total p8-format-lines-delta-totality
  format-lines-delta (gen/hash-map :lines-added gen/nat :lines-removed gen/nat)
  {:pred string?})

(deftest p8-format-lines-delta-no-changes
  (testing "Shows '(no line changes)' for zeros"
    (is (= "(no line changes)" (format-lines-delta {:lines-added 0 :lines-removed 0}))))
  (testing "Handles missing keys"
    (is (= "(no line changes)" (format-lines-delta {})))))
