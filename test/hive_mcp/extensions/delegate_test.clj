(ns hive-mcp.extensions.delegate-test
  "Unit/property tests for the shared delegate-or-noop helper, plus a
   characterization that a consumer (context.budget) still degrades gracefully
   to its noop default. Locks behavior before the 8-site dedup and after."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.extensions.delegate :as d]
            [hive-mcp.test.stub.extensions :as ext-stub]
            [hive-mcp.context.budget :as budget]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private test-key ::test-ext)

(use-fixtures :each
  (fn [f]
    (let [prior (ext/get-extension test-key)]
      (try (f)
           (finally
             (if prior (ext/register! test-key prior) (ext/deregister! test-key)))))))

(deftest delegate-when-present-test
  (testing "applies the registered extension fn to args, returns its result"
    (ext/register! test-key (fn [a b] (+ a b)))
    (is (= 7 (d/delegate-or-noop test-key :unused [3 4])))))

(deftest noop-when-absent-test
  (testing "returns default-val when no extension is registered"
    (ext/deregister! test-key)
    (is (= :default (d/delegate-or-noop test-key :default [1 2 3])))
    (is (nil? (d/delegate-or-noop test-key nil [])))))

(deftest consumer-degrades-gracefully-test
  (testing "context.budget/estimate-tokens returns the noop default (0) when its extension is absent"
    ;; Absence is ARRANGED, not assumed: a cold JVM has an empty registry but a
    ;; live image has the addon loaded, so omitting the deregistration makes
    ;; this pass cold and fail hot.
    (ext-stub/without-extensions [:cb/a]
      (fn []
        (is (= 0 (budget/estimate-tokens "any text here")))))))

;; =============================================================================
;; Trifecta: golden + property + mutation over the DEFAULT branch
;;
;; Every optional-behaviour namespace in core inherits its degradation
;; behaviour from this one function, so it earns more than a single example.
;; =============================================================================

(def ^:private gen-absent-args
  (gen/tuple (gen/return ::never-registered)
             gen/any-printable
             (gen/vector gen/small-integer 0 3)))

(deftrifecta delegate-or-noop-default
  hive-mcp.extensions.delegate/delegate-or-noop
  {:apply?        true
   :golden-path   "test/golden/delegate_or_noop.edn"
   :cases         {:nil-default    [::absent-a nil []]
                   :map-default    [::absent-b {:x 1} [1 2]]
                   :string-default [::absent-c "" ["ignored"]]
                   :vector-default [::absent-d [:a :b] [{:k 1}]]}
   :gen           gen-absent-args
   :property-type :totality-fn
   :num-tests     200
   :mutations     [["always-nil" (fn [_ _ _] nil)]
                   ["drops-default" (fn [_ _ args] args)]
                   ["returns-key" (fn [k _ _] k)]]})
