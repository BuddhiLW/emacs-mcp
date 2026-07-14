(ns hive-mcp.tools.memory.crud.query-temporal-test
  "Temporal filtering levers for memory query: entry-after? + the post-filter
   chain (created_after/updated_after reach the predicate, newest-first sort,
   limit caps after sort) + the handle-query gate (a temporal-only query is a
   legal filter; :query text still rejected).

   Regression: kanban 20260713154842-295d14a0 — created_after/updated_after
   were silently dropped by handle-query's destructure and results came back
   unsorted."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.memory.crud.query :as q]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private entries
  [{:id "a" :created "2026-05-01T10:00:00-03:00" :updated "2026-05-02T10:00:00-03:00" :tags ["x"]}
   {:id "b" :created "2026-07-12T09:00:00-03:00" :updated "2026-07-13T09:30:00-03:00" :tags ["x"]}
   {:id "c" :created "2026-07-13T08:00:00-03:00" :tags ["x"]}])

(deftest entry-after?-contract
  (testing "nil threshold matches every entry"
    (is (every? #(q/entry-after? % :created nil) entries))
    (is (q/entry-after? {} :updated nil)))
  (testing "strictly-after semantics on ISO strings"
    (is (q/entry-after? {:created "2026-07-13T08:00:00"} :created "2026-07-12T00:00:00"))
    (is (not (q/entry-after? {:created "2026-07-12T00:00:00"} :created "2026-07-12T00:00:00")))
    (is (not (q/entry-after? {:created "2026-07-11T23:59:59"} :created "2026-07-12T00:00:00"))))
  (testing "non-nil threshold with missing timestamp excludes the entry"
    (is (not (q/entry-after? {} :updated "2026-07-12T00:00:00")))))

(deftest post-filters-thread-temporal-and-sort
  (let [run (fn [opts]
              (mapv :id (#'q/apply-post-filters
                         entries (merge {:tags [] :limit-val 10} opts))))]
    (testing "created-after drops entries at-or-before the threshold"
      (is (= ["c" "b"] (run {:created-after "2026-07-12T00:00:00"}))))
    (testing "updated-after also excludes entries lacking :updated"
      (is (= ["b"] (run {:updated-after "2026-07-13T00:00:00"}))))
    (testing "no thresholds -> all entries, newest-first by :created"
      (is (= ["c" "b" "a"] (run {}))))
    (testing "limit caps AFTER the newest-first sort"
      (is (= ["c"] (run {:limit-val 1}))))
    (testing "tag and temporal filters compose"
      (is (= [] (run {:tags ["missing-tag"] :created-after "2026-01-01T00:00:00"}))))))

(deftest handle-query-gate-accepts-temporal-only
  (testing "created_after alone is a legal filter (gate passes; no 'no filter' rejection)"
    (let [resp (q/handle-query {:created_after "2026-07-12T00:00:00" :directory "/tmp"})]
      (is (not (re-find #"no filter given" (pr-str resp))))))
  (testing ":query text is still rejected on the structured path"
    (let [resp (q/handle-query {:query "how do I..." :created_after "2026-07-12T00:00:00"
                                :directory "/tmp"})]
      (is (re-find #"does not accept :query" (pr-str resp))))))
