(ns hive-mcp.engine.hprof.policy-test
  "Pure tests for ENGINE-L0.3 rotation decisions. No filesystem access."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.engine.hprof.spec   :as spec]
            [hive-mcp.engine.hprof.policy :as policy]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- h
  ([path mtime] (h path mtime 13000000000))
  ([path mtime bytes] (spec/->HprofFile path bytes mtime)))

(deftest classify-keeps-newest-n
  (testing "keeps the N most-recent dumps"
    (let [hprofs [(h "/x/a.hprof" 100)
                  (h "/x/b.hprof" 300)
                  (h "/x/c.hprof" 200)
                  (h "/x/d.hprof" 400)]
          {:keys [keep delete]} (policy/classify hprofs {:keep-n 2 :gzip? false})]
      (is (= ["/x/d.hprof" "/x/b.hprof"] (mapv :path keep)))
      (is (= ["/x/c.hprof" "/x/a.hprof"] (mapv :path delete))))))

(deftest classify-keeps-nothing-when-zero
  (testing "keep-n = 0 deletes every hprof"
    (let [hprofs [(h "/x/a.hprof" 100) (h "/x/b.hprof" 200)]
          {:keys [keep delete]} (policy/classify hprofs {:keep-n 0 :gzip? false})]
      (is (empty? keep))
      (is (= 2 (count delete))))))

(deftest classify-negative-keep-clamps-to-zero
  (testing "negative keep-n is treated as 0 (defensive)"
    (let [hprofs [(h "/x/a.hprof" 100)]
          {:keys [keep delete]} (policy/classify hprofs {:keep-n -3 :gzip? false})]
      (is (empty? keep))
      (is (= 1 (count delete))))))

(deftest classify-gzip-targets-only-non-gz
  (testing "gzip set excludes survivors already .gz"
    (let [hprofs [(h "/x/raw.hprof" 300)
                  (h "/x/old.hprof.gz" 200)]
          {:keys [keep gzip]} (policy/classify hprofs {:keep-n 5 :gzip? true})]
      (is (= 2 (count keep)))
      (is (= ["/x/raw.hprof"] (mapv :path gzip))))))

(deftest classify-gzip-false-yields-empty-gzip-set
  (let [hprofs [(h "/x/a.hprof" 100)]
        {:keys [gzip keep]} (policy/classify hprofs {:keep-n 5 :gzip? false})]
    (is (empty? gzip))
    (is (= 1 (count keep)))))

(deftest total-bytes-sums-correctly
  (is (= 0   (policy/total-bytes [])))
  (is (= 300 (policy/total-bytes [(h "/x/a" 1 100) (h "/x/b" 2 200)])))
  (testing "nil byte values are treated as zero"
    (is (= 100 (policy/total-bytes [(h "/x/a" 1 100)
                                    (spec/->HprofFile "/x/b" nil 2)])))))

(deftest merge-policy-overrides-defaults
  (let [merged (spec/merge-policy {:keep-n 7 :min-free-gb 99})]
    (is (= 7  (:keep-n merged)))
    (is (= 99 (:min-free-gb merged)))
    (is (true? (:gzip? merged)) "untouched default carries through")
    (is (string? (:dir merged)))))

(deftest merge-policy-nil-is-safe
  (let [merged (spec/merge-policy nil)]
    (is (= spec/default-policy merged))))
