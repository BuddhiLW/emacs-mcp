(ns hive-mcp.events.handlers.resilience-test
  "Tests for :resilience/dim-mismatch handler (ENGINE-L1.4)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.events.handlers.resilience :as res]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each
  (fn [t]
    (res/reset-registration!)
    (try (t) (finally (res/reset-registration!)))))

;; =============================================================================
;; handle-dim-mismatch — pure handler
;; =============================================================================

(deftest test-handle-dim-mismatch-emits-log-effect
  (testing "produces :log effect with warn level + event key"
    (let [event [:resilience/dim-mismatch
                 {:message "dim drift" :details {:expected 384 :got 768}
                  :ex-class "java.lang.IllegalStateException"}]
          result (res/handle-dim-mismatch {} event)]
      (is (contains? result :log) "must produce :log effect")
      (is (= :warn (get-in result [:log :level])))
      (is (= :resilience/dim-mismatch (get-in result [:log :event]))))))

(deftest test-handle-dim-mismatch-carries-structured-fields
  (testing ":log effect carries message, ex-class, details verbatim"
    (let [data {:message "embedder bumped from 384 → 768"
                :details {:collection "memories" :expected 384 :got 768}
                :ex-class "clojure.lang.ExceptionInfo"}
          result (res/handle-dim-mismatch {} [:resilience/dim-mismatch data])]
      (is (= (:message data) (get-in result [:log :message])))
      (is (= (:ex-class data) (get-in result [:log :ex-class])))
      (is (= (:details data) (get-in result [:log :details])))
      (is (= data (get-in result [:log :data]))
          "raw data preserved for downstream telemetry sinks"))))

(deftest test-handle-dim-mismatch-tolerates-nil-fields
  (testing "handler does not throw on missing optional keys"
    (let [result (res/handle-dim-mismatch {} [:resilience/dim-mismatch {}])]
      (is (contains? result :log))
      (is (nil? (get-in result [:log :message])))
      (is (nil? (get-in result [:log :ex-class])))
      (is (nil? (get-in result [:log :details]))))))

;; =============================================================================
;; register-handlers! — idempotent registration guard
;; =============================================================================

(deftest test-register-handlers-is-idempotent
  (testing "first call registers, subsequent calls no-op"
    (is (true? (res/register-handlers!)) "first registration returns true")
    (is (nil? (res/register-handlers!)) "second call no-ops (returns nil)")))
