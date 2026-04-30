(ns hive-mcp.knowledge-graph.edges.migration-trifecta-test
  "Trifecta tests for hive-mcp.knowledge-graph.edges.migration.

   Pure input-guard assertions (nil args, equal args). DB-level migration
   round-trips live in the broader knowledge-graph test suite."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.knowledge-graph.edges.migration :as migration]))

;; =============================================================================
;; Unit: input guards throw
;; =============================================================================

(deftest migrate-nil-old-scope-test
  (testing "migrate-edge-scopes! throws on nil old-scope"
    (is (thrown? clojure.lang.ExceptionInfo
                 (migration/migrate-edge-scopes! nil "new-scope")))))

(deftest migrate-nil-new-scope-test
  (testing "migrate-edge-scopes! throws on nil new-scope"
    (is (thrown? clojure.lang.ExceptionInfo
                 (migration/migrate-edge-scopes! "old-scope" nil)))))

(deftest migrate-both-nil-test
  (testing "migrate-edge-scopes! throws when both scopes are nil"
    (is (thrown? clojure.lang.ExceptionInfo
                 (migration/migrate-edge-scopes! nil nil)))))

(deftest migrate-same-scope-test
  (testing "migrate-edge-scopes! throws when old-scope = new-scope"
    (is (thrown? clojure.lang.ExceptionInfo
                 (migration/migrate-edge-scopes! "same-scope" "same-scope")))))

;; =============================================================================
;; Property: input guards hold for arbitrary strings
;; =============================================================================

(defspec migrate-same-scope-always-throws 50
  (prop/for-all [s gen/string-alphanumeric]
    (try
      (migration/migrate-edge-scopes! s s)
      false
      (catch clojure.lang.ExceptionInfo _ true))))

(defspec migrate-any-nil-always-throws 50
  (prop/for-all [s gen/string-alphanumeric
                 side (gen/elements [:left :right :both])]
    (try
      (case side
        :left  (migration/migrate-edge-scopes! nil s)
        :right (migration/migrate-edge-scopes! s nil)
        :both  (migration/migrate-edge-scopes! nil nil))
      false
      (catch clojure.lang.ExceptionInfo _ true))))

;; =============================================================================
;; Golden: ex-info data carries the offending scopes
;; =============================================================================

(deftest migrate-ex-info-golden-test
  (testing "ex-info payload carries scopes on nil"
    (try (migration/migrate-edge-scopes! nil "b")
         (is false "should have thrown")
         (catch clojure.lang.ExceptionInfo e
           (is (= {:old-scope nil :new-scope "b"} (ex-data e))))))
  (testing "ex-info payload carries scopes on same"
    (try (migration/migrate-edge-scopes! "a" "a")
         (is false "should have thrown")
         (catch clojure.lang.ExceptionInfo e
           (is (= {:old-scope "a" :new-scope "a"} (ex-data e)))))))
