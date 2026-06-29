(ns hive-mcp.extensions.delegate-test
  "Unit/property tests for the shared delegate-or-noop helper, plus a
   characterization that a consumer (context.budget) still degrades gracefully
   to its noop default. Locks behavior before the 8-site dedup and after."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.extensions.delegate :as d]
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
    (is (= 0 (budget/estimate-tokens "any text here")))))
