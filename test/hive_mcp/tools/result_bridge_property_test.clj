(ns hive-mcp.tools.result-bridge-property-test
  "Property tests for shared result-bridge helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.dns.result :as result]))

;; ── Generators ────────────────────────────────────────────────────────────────

(def gen-json-safe
  "Generate values that are safe for JSON serialization (no Characters, etc.)."
  (gen/one-of [gen/string-alphanumeric
               gen/small-integer
               gen/boolean
               (gen/return nil)
               (gen/vector gen/string-alphanumeric 0 3)
               (gen/map gen/string-alphanumeric gen/string-alphanumeric {:max-elements 3})]))

(def gen-ok-result
  (gen/fmap result/ok gen-json-safe))

(def gen-err-result
  (gen/let [cat (gen/fmap keyword gen/string-alphanumeric)
            msg gen/string-alphanumeric]
    (result/err cat {:message msg})))

(def gen-any-result
  (gen/one-of [gen-ok-result gen-err-result]))

(def gen-string-map
  "Map with string keys (simulates MCP JSON params)."
  (gen/map gen/string-alphanumeric gen/any-printable-equatable {:max-elements 10}))

(def gen-keyword-map
  "Map with keyword keys."
  (gen/map gen/keyword gen/any-printable-equatable {:max-elements 10}))

(def gen-mixed-map
  (gen/one-of [gen-string-map gen-keyword-map]))

;; ── P1: result->mcp totality ─────────────────────────────────────────────────

(defspec result->mcp-never-throws 200
  (prop/for-all [r gen-any-result]
                (let [mcp (rb/result->mcp r)]
                  (map? mcp))))

;; ── P2: ok Results -> {:type "text"} response (no :isError) ──────────────────

(defspec ok-result-produces-text-response 200
  (prop/for-all [v gen-json-safe]
                (let [mcp (rb/result->mcp (result/ok v))]
                  (and (= "text" (:type mcp))
                       (not (:isError mcp))))))

;; ── P3: err Results -> {:isError true} response ──────────────────────────────

(defspec err-result-produces-error-response 200
  (prop/for-all [msg gen/string-alphanumeric]
                (let [mcp (rb/result->mcp (result/err :test/error {:message msg}))]
                  (and (:isError mcp)
                       (string? (:text mcp))))))

;; ── P4: try-result totality ──────────────────────────────────────────────────

(defspec try-result-always-returns-result 200
  (prop/for-all [throw? gen/boolean
                 msg gen/string-alphanumeric]
                (let [r (rb/try-result :test/cat
                                       (if throw?
                                         #(throw (ex-info msg {}))
                                         #(result/ok msg)))]
                  (or (result/ok? r) (result/err? r)))))

;; ── P5: keywordize-map idempotent ────────────────────────────────────────────

(defspec keywordize-map-idempotent 200
  (prop/for-all [m gen-mixed-map]
                (let [once (rb/keywordize-map m)
                      twice (rb/keywordize-map once)]
                  (= once twice))))

;; ── P6: keywordize-map preserves map size ────────────────────────────────────

(defspec keywordize-map-preserves-size 200
  (prop/for-all [m gen-string-map]
                (= (count m) (count (rb/keywordize-map m)))))

;; ── P7: result->mcp-text totality ───────────────────────────────────────────

(defspec result->mcp-text-never-throws 200
  (prop/for-all [r gen-any-result]
                (let [mcp (rb/result->mcp-text r)]
                  (and (map? mcp)
                       (string? (:text mcp))))))

;; ── Unit tests ───────────────────────────────────────────────────────────────

(deftest test-try-result-catches-ex-info
  (testing "try-result catches ExceptionInfo and returns err with data + :class"
    (let [r (rb/try-result :test/ex-info
                           #(throw (ex-info "boom" {:detail 42})))]
      (is (result/err? r))
      (is (= :test/ex-info (:error r)))
      (is (= "boom" (:message r)))
      (is (= {:detail 42} (:data r)))
      (is (= "clojure.lang.ExceptionInfo" (:class r))
          "ExceptionInfo path also populates :class for downstream discrimination"))))

(deftest test-try-result-npe-fallback-includes-class
  (testing "NPE fallback returns map containing :class \"java.lang.NullPointerException\""
    (let [r (rb/try-result :test/generic
                           #(throw (NullPointerException. "npe")))]
      (is (result/err? r))
      (is (= "npe" (:message r)))
      (is (= "java.lang.NullPointerException" (:class r))
          ":class must be the fully-qualified class name (no \"class \" prefix)")))
  (testing "NPE with nil message still produces :class"
    (let [r (rb/try-result :test/generic
                           #(throw (NullPointerException.)))]
      (is (result/err? r))
      (is (= "java.lang.NullPointerException" (:class r))))))

(deftest test-try-result-non-npe-errors-retain-class
  (testing "non-NPE generic exceptions also get :class populated"
    (let [r (rb/try-result :test/generic
                           #(throw (IllegalArgumentException. "bad arg")))]
      (is (result/err? r))
      (is (= "bad arg" (:message r)))
      (is (= "java.lang.IllegalArgumentException" (:class r)))))
  (testing "RuntimeException class name is captured"
    (let [r (rb/try-result :test/generic
                           #(throw (RuntimeException. "boom")))]
      (is (= "java.lang.RuntimeException" (:class r)))))
  (testing "ArithmeticException from real division gets :class"
    (let [r (rb/try-result :test/generic #(/ 1 0))]
      (is (result/err? r))
      (is (= "java.lang.ArithmeticException" (:class r))))))

;; ── P8: :class is always populated for any throwable ────────────────────────

(def gen-throwable-thunk
  "Generate a thunk that throws a JVM exception of various types. Using thunks
   (not pre-built throwables) avoids stale stack traces across generator runs."
  (gen/elements
    [#(throw (NullPointerException. "npe"))
     #(throw (NullPointerException.)) ;; nil message
     #(throw (IllegalArgumentException. "bad arg"))
     #(throw (IllegalStateException. "bad state"))
     #(throw (RuntimeException. "runtime"))
     #(throw (ArithmeticException. "arithmetic"))
     #(throw (ClassCastException. "cast"))
     #(throw (IndexOutOfBoundsException. "oob"))
     #(throw (UnsupportedOperationException. "unsupported"))
     #(throw (Exception. "generic"))
     #(throw (ex-info "ex-info" {:k 1}))
     #(throw (ex-info "ex-info-empty" {}))]))

(defspec try-result-always-populates-class 200
  (prop/for-all [thunk gen-throwable-thunk]
                (let [r (rb/try-result :test/any thunk)]
                  (and (result/err? r)
                       (string? (:class r))
                       (pos? (count (:class r)))))))

(deftest test-keywordize-map-empty
  (testing "keywordize-map on empty map returns empty map"
    (is (= {} (rb/keywordize-map {})))))

(deftest test-keywordize-map-mixed-keys
  (testing "keywordize-map handles already-keyword keys"
    (is (= {:a 1 :b 2} (rb/keywordize-map {:a 1 "b" 2})))))
