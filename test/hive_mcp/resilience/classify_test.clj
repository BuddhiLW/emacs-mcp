(ns hive-mcp.resilience.classify-test
  "Tests for `hive-mcp.resilience.classify`.

   Pin the bug-fix invariants:

   1. Milvus `code=1804` (and other codes ≥1100 carrying the
      `:milvus-clj.client/transport` tag) classifies as
      `:err/schema-mismatch`, NEVER `:err/transient`. This is the
      precise regression that the prior `transient-failure?` had —
      treating the transport tag alone as transient.
   2. Genuine IO drops (IOException, `:cause :io`) still classify as
      `:err/transient` so the heal loop is preserved for real
      reconnects.
   3. ExecutionException wrapping a tagged ex-info classifies via the
      cause chain — the wrapper's empty ex-data does not hide the
      classification."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.adt :as adt]
            [hive-mcp.resilience.classify :as classify]
            [hive-mcp.resilience.protocol :as proto])
  (:import [java.io IOException]
           [java.util.concurrent ExecutionException]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- variant [err-class]
  (adt/adt-variant err-class))

(deftest milvus-1804-is-schema-mismatch
  (testing "Milvus dim-mismatch (code=1804) classifies as :err/schema-mismatch"
    (let [ex (ex-info "Milvus HTTP /v2/vectordb/entities/upsert returned code=1804 message=fail to deal the insert data, error: []float32 size 4096 doesn't equal to vector dimension 768 of FloatVector"
                      {:milvus-clj.client/transport :http
                       :code    1804
                       :message "fail to deal the insert data"
                       :path    "/v2/vectordb/entities/upsert"})
          result (classify/classify ex)]
      (is (= :err/schema-mismatch (variant result))
          "1804 must NOT be classified as transient — it's the bug we are fixing")
      (is (= 1804 (-> result :details :code))))))

(deftest milvus-validation-codes-are-schema-mismatch
  (testing "Milvus codes ≥1100 with transport tag → :err/schema-mismatch"
    (doseq [code [1100 1101 1804 1805 2000 9999]]
      (let [ex (ex-info "validation"
                        {:milvus-clj.client/transport :http :code code})]
        (is (= :err/schema-mismatch (variant (classify/classify ex)))
            (str "code=" code " must classify as schema-mismatch"))))))

(deftest milvus-low-code-with-io-cause-is-transient
  (testing ":cause :io overrides transport-tag-only assumption"
    (let [ex (ex-info "transport drop"
                      {:milvus-clj.client/transport :http
                       :cause :io
                       :code  500})]
      (is (= :err/transient (variant (classify/classify ex)))))))

(deftest io-exception-is-transient
  (testing "raw IOException → :err/transient"
    (is (= :err/transient
           (variant (classify/classify (IOException. "Connection reset")))))))

(deftest execution-exception-wrapping-1804-is-schema-mismatch
  (testing "ExecutionException wrap does NOT hide the cause's 1804 classification"
    (let [inner (ex-info "Milvus code=1804" {:milvus-clj.client/transport :http :code 1804})
          wrapper (ExecutionException. "wrap" inner)]
      (is (= :err/schema-mismatch (variant (classify/classify wrapper)))
          "Walking the cause chain must reach the inner ex-info"))))

(deftest auth-failures-classify-as-auth
  (testing "HTTP 401/403 with transport tag → :err/auth"
    (doseq [status [401 403]]
      (let [ex (ex-info "auth" {:milvus-clj.client/transport :http :status status})]
        (is (= :err/auth (variant (classify/classify ex))))))))

(deftest validation-input-too-large-classifies-as-validation
  (testing "embedder/input-too-large surfaces as :err/validation, not :err/transient"
    (let [ex (ex-info "Content too large for embedder ollama-qwen3 (10000 est. tokens > 6500 max)"
                      {:err/tag    :embedder/input-too-large
                       :max-tokens 6500
                       :actual     10000})]
      (is (= :err/validation (variant (classify/classify ex)))))))

(deftest dim-mismatch-tag-classifies-as-schema-mismatch
  (testing ":err/tag :embedder/dim-mismatch surfaces as :err/schema-mismatch"
    (let [ex (ex-info "dim mismatch"
                      {:err/tag :embedder/dim-mismatch
                       :err/cause "vector dim 4096 != collection dim 768"})]
      (is (= :err/schema-mismatch (variant (classify/classify ex)))))))

(deftest message-marker-fallback-still-works
  (testing "Bare RuntimeException with transient marker → :err/transient"
    (is (= :err/transient
           (variant (classify/classify (RuntimeException. "selector manager closed")))))))

(deftest unknown-fallback
  (testing "Untagged RuntimeException without markers → :err/unknown"
    (is (= :err/unknown
           (variant (classify/classify (RuntimeException. "something else")))))))

(deftest classifier-is-total
  (testing "Classifier never throws on any throwable"
    (doseq [t [(RuntimeException.)
               (Exception. "")
               (NullPointerException.)
               (ex-info "" {})
               (ex-info "" {:nothing :interesting})
               (IOException.)]]
      (is (some? (classify/classify t)))
      (is (instance? clojure.lang.IPersistentMap (classify/classify t))))))

(deftest default-classifier-protocol
  (testing "DefaultClassifier satisfies IErrorClassifier"
    (let [c (classify/default-classifier)]
      (is (satisfies? proto/IErrorClassifier c))
      (is (= :err/schema-mismatch
             (variant (proto/classify
                       c
                       (ex-info "x" {:milvus-clj.client/transport :http :code 1804}))))))))
