(ns hive-mcp.hivemind.messaging.cap-test
  "Unit tests for `cap-message` — the pure helper that bounds shout payloads.

   Why: one oversized shout fans out across (per-agent ring × backbone ×
   subscribers) and inflates every downstream context window. cap-message is
   applied at canonical ingestion in shout!. These tests cover the pure
   boundary contract; integration coverage lives in messaging_test.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.hivemind.messaging :as msg]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private cap 2048)
(def ^:private ellipsis "…")

(defn- big-string [n]
  (apply str (repeat n "x")))

(deftest nil-passthrough
  (testing "nil input returns nil"
    (is (nil? (msg/cap-message nil cap)))))

(deftest empty-string-passthrough
  (testing "empty string returns empty string unchanged"
    (is (= "" (msg/cap-message "" cap)))))

(deftest under-cap-passthrough
  (testing "strings shorter than cap pass through verbatim"
    (is (= "hello" (msg/cap-message "hello" cap)))
    (is (= (big-string (dec cap))
           (msg/cap-message (big-string (dec cap)) cap)))
    (is (= (big-string cap)
           (msg/cap-message (big-string cap) cap))
        "exactly cap chars is under the ≤ boundary")))

(deftest over-cap-truncation
  (testing "strings longer than cap truncate to (cap - 3) chars + ellipsis"
    (let [result (msg/cap-message (big-string 5000) cap)]
      (is (<= (count result) cap)
          (str "expected ≤ " cap ", got " (count result)))
      (is (.endsWith result ellipsis)
          "truncated payload must end with ellipsis marker")
      (is (= (- cap 3) (count (subs result 0 (- (count result) 1))))
          "head portion should be exactly (cap - 3) chars before the 1-char ellipsis"))))

(deftest non-string-bounded
  (testing "coll-shaped payloads are pr-str'd then capped"
    (let [payload (vec (repeat 500 {:title "x" :status "todo" :id "y"}))
          result (msg/cap-message payload cap)]
      (is (string? result))
      (is (<= (count result) cap))
      (is (.endsWith result ellipsis)))))

(deftest default-arity-uses-config
  (testing "single-arg arity resolves cap from config (fallback = default)"
    (let [result (msg/cap-message (big-string 10000))]
      (is (<= (count result) msg/default-shout-message-cap)
          "single-arg cap-message must bound within default cap"))))

(deftest tiny-cap-degenerate
  (testing "cap=3 collapses head to empty; output is just the ellipsis"
    (let [result (msg/cap-message (big-string 100) 3)]
      (is (= ellipsis result))
      (is (<= (count result) 3)))))

(deftest boundary-one-over
  (testing "input of length cap+1 triggers truncation"
    (let [result (msg/cap-message (big-string (inc cap)) cap)]
      (is (<= (count result) cap))
      (is (.endsWith result ellipsis)))))
