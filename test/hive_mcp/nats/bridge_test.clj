(ns hive-mcp.nats.bridge-test
  "Tests for hive-mcp.nats.bridge — focus on summarize-drone-error
   truncation helper and shout message length bounds."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [hive-mcp.nats.bridge :as bridge]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private summarize-drone-error
  (deref #'bridge/summarize-drone-error))

;; =============================================================================
;; summarize-drone-error — helper tests
;; =============================================================================

(deftest string-error-truncation
  (testing "short string passes through unchanged"
    (is (= "boom" (summarize-drone-error "boom"))))
  (testing "long string truncated to 300 chars + ellipsis"
    (let [long-str (apply str (repeat 1000 "x"))
          result   (summarize-drone-error long-str)]
      (is (<= (count result) 301))
      (is (str/ends-with? result "…"))
      (is (str/starts-with? result "xxxxx")))))

(deftest map-error-extracts-salient-fields
  (testing "prefers :error/type when present"
    (let [err {:error/type :timeout :message "timed out after 30s"
               :stack "huge\nstack\ntrace..."}
          result (summarize-drone-error err)]
      (is (str/includes? result ":timeout"))
      (is (str/includes? result "timed out after 30s"))
      (is (not (str/includes? result "huge\nstack")))))
  (testing "falls back to :message when no :error/type"
    (let [err {:message "something broke" :data {:extra "stuff"}}
          result (summarize-drone-error err)]
      (is (str/includes? result "something broke"))))
  (testing "ex-info extracts via ex-message"
    (let [err (ex-info "ex-thing-failed" {:error/type :validation})
          result (summarize-drone-error err)]
      (is (str/includes? result "ex-thing-failed")))))

(deftest collection-error-summarizes
  (testing "small collection (<=5 items) pr-str truncated"
    (let [err [1 2 3]
          result (summarize-drone-error err)]
      (is (<= (count result) 301))))
  (testing "large collection summarized as count + first-truncated"
    (let [err (vec (range 100))
          result (summarize-drone-error err)]
      (is (str/includes? result "100 items"))
      (is (<= (count result) 301)))))

(deftest fallback-truncates
  (testing "arbitrary value falls back to pr-str truncated"
    (let [err 42
          result (summarize-drone-error err)]
      (is (= "42" result))))
  (testing "nil error stays nil-safe"
    (is (some? (summarize-drone-error nil)))))

;; =============================================================================
;; Integration-ish: shout message length bound
;; =============================================================================

(deftest huge-json-array-error-bounded-shout
  (testing "10KB JSON array error produces bounded shout message"
    (let [huge-json (apply str (repeat 10000 "X"))
          summary (summarize-drone-error huge-json)
          shout-msg (str "Drone task-xyz failed: " summary)]
      (is (<= (count shout-msg) 400)
          (str "shout-msg too long: " (count shout-msg))))))

(deftest ex-info-error-shout-has-type-and-message
  (testing "ex-info error surfaces type + top message only"
    (let [err (ex-info "quick-err" {:error/type :parse-fail
                                    :big-noise (apply str (repeat 5000 "N"))})
          summary (summarize-drone-error err)
          shout-msg (str "Drone task-xyz failed: " summary)]
      (is (str/includes? shout-msg "quick-err"))
      (is (str/includes? shout-msg ":parse-fail"))
      (is (not (str/includes? shout-msg "NNNNNNNNNNNNNNN")))
      (is (<= (count shout-msg) 400)))))
