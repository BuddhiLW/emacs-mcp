(ns hive-mcp.hivemind.messaging.cap-property-test
  "Property-based tests for `cap-message`.

   Properties proven:
   - forall string s, cap≥3: (count (cap-message s cap)) ≤ cap
   - forall string s, cap≥3 with (count s) > cap: result ends with '…'
   - forall string s with (count s) ≤ cap: (cap-message s cap) = s (identity)
   - nil is always preserved as nil
   - empty string is always preserved as \"\""
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.hivemind.messaging :as msg]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private ellipsis "…")

;; ============================================================
;; Generators
;; ============================================================

(def ^:private gen-cap
  "Caps ≥ 3 (below 3 there isn't enough room for head + ellipsis)."
  (gen/fmap #(+ 3 %) gen/nat))

(def ^:private gen-payload-string
  "Arbitrary strings — includes empty and oversized cases."
  gen/string)

;; ============================================================
;; Properties
;; ============================================================

(defspec cap-never-exceeds 200
  (prop/for-all [s gen-payload-string
                 cap gen-cap]
    (let [result (msg/cap-message s cap)]
      (or (nil? result)
          (<= (count result) cap)))))

(defspec over-cap-ends-with-ellipsis 200
  (prop/for-all [s gen-payload-string
                 cap gen-cap]
    (let [result (msg/cap-message s cap)]
      (if (and (some? result) (> (count s) cap))
        (.endsWith ^String result ellipsis)
        true))))

(defspec under-cap-is-identity 200
  (prop/for-all [s gen-payload-string
                 cap gen-cap]
    (if (<= (count s) cap)
      (= s (msg/cap-message s cap))
      true)))

(defspec nil-preserved 50
  (prop/for-all [cap gen-cap]
    (nil? (msg/cap-message nil cap))))

(defspec empty-preserved 50
  (prop/for-all [cap gen-cap]
    (= "" (msg/cap-message "" cap))))

;; ============================================================
;; Concrete smoke asserts (ensures defspec wiring didn't silently pass)
;; ============================================================

(deftest smoke-cap-invariant
  (is (<= (count (msg/cap-message (apply str (repeat 10000 "a")) 2048)) 2048))
  (is (.endsWith ^String (msg/cap-message (apply str (repeat 10000 "a")) 2048) ellipsis)))
