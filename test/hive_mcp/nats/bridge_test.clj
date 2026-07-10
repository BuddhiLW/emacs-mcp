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

;; =============================================================================
;; Integration: real Throwable (deep cause + huge message + 50-frame trace)
;; goes through summarize-drone-error → bounded shout payload.
;;
;; Mirrors the real shout site:
;;   (str "Drone " task-id " failed: " (summarize-drone-error error))
;; =============================================================================

(def ^:private throwable-shout-budget
  "Shout payload budget for real Throwables — keep under ~600 chars so a
   single drone failure never balloons piggyback blocks."
  600)

(defn- build-deep-error
  "Construct a deeply-nested Throwable mimicking a runaway drone:
     - top: ExceptionInfo with :error/type + small message
     - mid: IllegalStateException
     - root: NullPointerException with deep (1.5KB) message + ex-data noise"
  []
  (let [root (NullPointerException.
              (apply str (repeat 1500 "R")))
        mid  (IllegalStateException.
              "drone middleware exploded" root)]
    (ex-info "drone exploded"
             {:error/type :drone/model-error
              :diagnostic (apply str (repeat 8000 "D"))}
             mid)))

(deftest deep-throwable-shout-bounded
  (testing "huge throwable + cause chain → bounded shout < budget"
    (let [err     (build-deep-error)
          summary (summarize-drone-error err)
          shout   (str "Drone task-deep failed: " summary)]
      (is (<= (count shout) throwable-shout-budget)
          (str "shout too long (" (count shout) " > " throwable-shout-budget ")"))
      (is (str/includes? summary ":drone/model-error")
          "should retain :error/type")
      (is (str/includes? summary "drone exploded")
          "should retain top message")
      (is (str/includes? summary "←")
          "should mention the cause chain when budget allows")
      (is (not (str/includes? summary "DDDDDDDDDDDD"))
          "should drop ex-data noise")
      (is (not (str/includes? summary "RRRRRRRRRRRRRRRRRRRRRRRRRRR"))
          "deep cause message bounded — never dump 1.5KB"))))

(deftest auto-shout-payload-respects-budget
  (testing "shout-msg built at handle-drone-failed site stays bounded"
    ;; Real shout site is in `handle-drone-failed` (private fn). It builds:
    ;;   (str "Drone " task-id " failed: " (summarize-drone-error error))
    ;; and forwards via `auto-shout-drone-event!` as {:message <msg>}.
    ;; Asserting the same string-construction pipeline keeps the contract.
    (let [errors  [(NullPointerException. "raw npe")
                   (RuntimeException. (apply str (repeat 5000 "X")))
                   (ex-info "outer" {:error/type :validation}
                            (RuntimeException.
                             (apply str (repeat 3000 "Y"))))]
          payload (mapv (fn [e]
                          (let [msg (str "Drone task-x failed: "
                                         (summarize-drone-error e))]
                            {:message msg :len (count msg)}))
                        errors)]
      (doseq [{:keys [len message]} payload]
        (is (<= len throwable-shout-budget)
            (str "payload too long: " len " :: " message))))))

;; =============================================================================
;; Subject-token guard — empty/nil components must never yield a dangling
;; token. NATS rejects "subject cannot end with '.'".  [NATS-SUBJ]
;; =============================================================================

(defn- well-formed-subject?
  [s]
  (and (string? s)
       (not (str/starts-with? s "."))
       (not (str/ends-with? s "."))
       (not (str/includes? s ".."))))

(deftest shout-subject-guards-empty-components
  (testing "nil / blank project-id and agent-id never produce a dangling token"
    (doseq [[p a] [[nil nil] ["hive" ""] ["hive" "   "] [nil "ling-1"] ["" "ling-1"]]]
      (is (well-formed-subject? (bridge/shout-subject p a))
          (str "malformed for " [p a] ": " (bridge/shout-subject p a)))))
  (testing "valid components pass through unchanged"
    (is (= "hive.v1.shout.hive.ling-1" (bridge/shout-subject "hive" "ling-1")))
    (is (= "hive.v1.shout.kw-proj.kw-agent" (bridge/shout-subject :kw-proj :kw-agent)))))

(deftest tool-subject-guards-empty-name
  (testing "nil / blank tool-name never throws and never dangles"
    (doseq [t [nil "" "   "]]
      (is (well-formed-subject? (bridge/tool-subject t))
          (str "malformed for " (pr-str t) ": " (bridge/tool-subject t)))))
  (testing "valid tool-name passes through unchanged"
    (is (= "hive.v1.tool.memory-add" (bridge/tool-subject :memory-add)))
    (is (= "hive.v1.tool.memory-add" (bridge/tool-subject "memory-add")))))
