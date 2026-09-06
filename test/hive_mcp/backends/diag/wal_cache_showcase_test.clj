(ns hive-mcp.backends.diag.wal-cache-showcase-test
  "Before/after showcase for the datalevin WAL records-cache heap leak, driven
   through hive-test's golden paradigm. Exercises the real fork fn
   datalevin.kv/limit-txlog-records-cache-map across three regimes and snapshots
   the retained-heap table so the fix is a diffable artifact, not a claim."
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.kv]
            [datalevin.constants :as c]
            [hive-test.golden :as golden]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def ^:private limit-cache #'datalevin.kv/limit-txlog-records-cache-map)

(def ^:private mib (* 1024 1024))

;; The observed leak fingerprint: ~10 GiB of decoded WAL held in the records
;; cache. *wal-retention-bytes* (8 GiB) worth of *wal-segment-max-bytes*
;; (256 MiB) segments, all under the 64-segment count cap, all fully decoded.
(def ^:private leak-shape-cache
  (into {} (for [i (range 40)] [(long i) {:scan-bytes (* 256 mib)}])))

(defn- kept-mib
  [cache]
  (int (/ (reduce + (map (comp long :scan-bytes val) cache)) mib)))

(defn- retained-mib-under
  "Retained-heap MiB after limiting with the given caps against the leak shape."
  [max-segments max-bytes]
  (binding [c/*wal-records-cache-segments* max-segments
            c/*wal-records-cache-max-bytes* max-bytes]
    (kept-mib (limit-cache leak-shape-cache))))

(def ^:private showcase
  {:leak-shape           {:segments 40 :segment-mib 256 :total-mib (kept-mib leak-shape-cache)}
   ;; datalevin 0.10.7 (production): no cache-limiting fn at all — every
   ;; retained segment stays decoded in heap.
   :prod-0-10-7-mib      (kept-mib leak-shape-cache)
   ;; HEAD commit 11d2402f: segment-count cap only (byte budget disabled).
   ;; 40 segments < 64 cap, so nothing is evicted — the count cap never bites.
   :head-count-only-mib  (retained-mib-under 64 0)
   ;; HEAD + our byte-budget fix (256 MiB default): heap is bounded regardless
   ;; of how many full segments retention keeps on disk.
   :head-byte-budget-mib (retained-mib-under 64 (* 256 mib))})

(deftest wal-cache-before-after-invariant
  (testing "the segment-count cap alone does NOT bound heap for the leak shape"
    (is (= 10240 (:prod-0-10-7-mib showcase))     "0.10.7 holds all 10 GiB")
    (is (= 10240 (:head-count-only-mib showcase)) "count-only cap still holds all 10 GiB (40 < 64)"))
  (testing "the byte budget bounds heap to the configured budget"
    (is (>= 256 (:head-byte-budget-mib showcase)) "byte budget caps retained heap at <= 256 MiB")
    (is (> (:prod-0-10-7-mib showcase)
           (* 30 (:head-byte-budget-mib showcase)))
        "the fix cuts retained heap by more than 30x on the leak shape")))

(golden/deftest-golden wal-cache-showcase-golden
  "test/golden/wal-cache-bytebudget.edn"
  showcase)
