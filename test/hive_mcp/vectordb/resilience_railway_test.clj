(ns hive-mcp.vectordb.resilience-railway-test
  "Smoke tests for the SLAP-decomposed railway in
   `hive-mcp.vectordb.resilience`.

   Pin the bug-fix invariants end-to-end via the public Result API:

   1. A thunk that throws Milvus 1804 returns
      `{:error :embedder/dim-mismatch}` — NOT `:err/transient`.
   2. A thunk that returns a value returns `{:ok value}`.
   3. A thunk that throws an unrelated RuntimeException returns
      `{:error :resilience/fatal}` carrying the original throwable.
   4. The legacy throwing shim re-raises `:embedder/dim-mismatch`
      ex-info on schema mismatch (so existing callers see the
      actionable error rather than the raw Milvus log line)."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-mcp.vectordb.resilience :as res]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- throwing-thunk [t]
  (fn [] (throw t)))

(def ^:private milvus-1804
  (ex-info "Milvus HTTP /v2/vectordb/entities/upsert returned code=1804"
           {:milvus-clj.client/transport :http
            :code    1804
            :message "fail to deal the insert data"}))

(deftest schema-mismatch-returns-dim-mismatch-err
  (testing "1804 → :embedder/dim-mismatch err, never :err/transient"
    (let [result (res/call-with-resilience-result
                   (throwing-thunk milvus-1804) 0)]
      (is (r/err? result))
      (is (= :embedder/dim-mismatch (:error result))))))

(deftest happy-path-returns-ok
  (testing "successful thunk → ok"
    (let [result (res/call-with-resilience-result (constantly :v) 0)]
      (is (r/ok? result))
      (is (= :v (:ok result))))))

(deftest fatal-returns-fatal-err-with-throwable
  (testing "non-classified throw → :resilience/fatal preserving throwable"
    (let [boom (RuntimeException. "boom")
          result (res/call-with-resilience-result (throwing-thunk boom) 0)]
      (is (r/err? result))
      (is (= :resilience/fatal (:error result)))
      (is (identical? boom (:throwable result))))))

(deftest legacy-shim-rethrows-as-dim-mismatch
  (testing "throwing API converts schema-mismatch to :embedder/dim-mismatch"
    (let [thrown (try (res/call-with-resilience (throwing-thunk milvus-1804) 0)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :embedder/dim-mismatch (:err/tag (ex-data thrown)))))))

(deftest legacy-shim-passes-value-through
  (testing "throwing API returns value on success"
    (is (= 42 (res/call-with-resilience (constantly 42) 0)))))
