(ns hive-mcp.diag.clinic-test
  "Behavioural contract tests for the IMemoryClinic FACADE (hive-mcp.diag.clinic)
   against FAKE ports — the real value of the diag module's test surface. The
   clinic owns no capability; it only threads injected ports into railway
   workflows, so fakes returning canned Results exercise every branch WITHOUT any
   diagnostic library (clj-memory-meter / async-profiler / datalevin) present:

     - diagnose    : verdict classification (static / churning / unknown) and
                     tolerance of fallible parts (a failing sampler or cache
                     must NOT abort the report).
     - relieve!    : reclaim accounting + per-cache attribution, skipping caches
                     whose eviction errored.
     - hunt-retainers : sizer-availability gate, the free-heap OOM headroom
                     guard (skip, never abort), and bytes-desc/nils-last sort.

   The concrete adapters (probe/sizer/alloc/profiler/cache) are tested elsewhere;
   here every port is a fake so the composition logic is isolated."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.diag.ports :as p]
            [hive-dsl.result :as r]
            [hive-mcp.diag.clinic :as clinic]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; ---------------------------------------------------------------------------
;; Fakes — canned-Result ports, no library, no side effects.
;; ---------------------------------------------------------------------------

(defrecord FakeProbe [snap gc]
  p/IHeapProbe
  (heap-snapshot   [_]     snap)
  (class-histogram [_ _n]  {:diag/entries []})
  (request-gc!     [_]     gc)
  (dump-heap!      [_ _ _] (r/ok {:diag/path "/x" :diag/bytes 0})))

;; sizes: map keyed by candidate object -> Result (ok {:diag/bytes n} | err …).
(defrecord FakeSizer [avail sizes]
  p/IRetainedSizer
  (sizer-available? [_]     avail)
  (retained-size    [_ obj] (get sizes obj (r/err :diag/sizer-refused {:reason "unmapped"}))))

(defrecord FakeSampler [res]
  p/IAllocationSampler
  (sample-allocation [_ _ms] res))

(defrecord FakeProfiler []
  p/IProfiler
  (profiler-active? [_]   false)
  (start-profiling! [_ _] (r/err :diag/profiler-unavailable {}))
  (stop-profiling!  [_]   (r/err :diag/profiler-not-running {})))

(defrecord FakeCache [id occ ev]
  p/ICacheProbe
  (cache-id        [_] id)
  (cache-occupancy [_] occ)
  (evict-cache!    [_] ev))

(defn- snap [used max*]
  {:diag/used-bytes used :diag/committed-bytes used :diag/max-bytes max*})

(defn- alloc-ok [mean-bps]
  (r/ok {:diag/duration-ms 3000 :diag/samples-bps [mean-bps] :diag/mean-bps mean-bps :diag/peak-bps mean-bps}))

(def ^:private one-mib (* 1024 1024))

;; ---------------------------------------------------------------------------
;; diagnose
;; ---------------------------------------------------------------------------

(deftest diagnose-verdict-static
  (testing "mean alloc below the 5 MiB/s churn threshold => :static-residency (retained)"
    (let [c   (clinic/make-clinic (->FakeProbe (snap 1000 10000) nil)
                                  (->FakeSizer false {})
                                  (->FakeSampler (alloc-ok one-mib)) ; 1 MiB/s < 5 MiB/s
                                  (->FakeProfiler)
                                  [(->FakeCache :c (r/ok {:diag/cache-id :c :diag/stores []}) (r/ok {}))])
          res (p/diagnose c)]
      (is (r/ok? res))
      (is (= :static-residency (:diag/verdict (:ok res))))
      (is (some? (:diag/allocation (:ok res))) "allocation present when the sampler succeeds")
      (is (= 1 (count (:diag/caches (:ok res)))) "the one ok cache occupancy is included"))))

(deftest diagnose-verdict-churning
  (testing "mean alloc at/above the threshold => :churning"
    (let [c   (clinic/make-clinic (->FakeProbe (snap 1000 10000) nil)
                                  (->FakeSizer false {})
                                  (->FakeSampler (alloc-ok (* 10 one-mib))) ; 10 MiB/s >= 5
                                  (->FakeProfiler)
                                  [])
          res (p/diagnose c)]
      (is (r/ok? res))
      (is (= :churning (:diag/verdict (:ok res)))))))

(deftest diagnose-tolerates-failing-sampler
  (testing "a sampler err does not abort diagnose; verdict degrades to :unknown, allocation omitted"
    (let [c   (clinic/make-clinic (->FakeProbe (snap 1000 10000) nil)
                                  (->FakeSizer false {})
                                  (->FakeSampler (r/err :diag/sampler-unavailable {}))
                                  (->FakeProfiler)
                                  [])
          res (p/diagnose c)]
      (is (r/ok? res))
      (is (= :unknown (:diag/verdict (:ok res))))
      (is (nil? (:diag/allocation (:ok res)))))))

(deftest diagnose-tolerates-failing-cache
  (testing "a cache whose occupancy errs is filtered out, not fatal"
    (let [c   (clinic/make-clinic (->FakeProbe (snap 1000 10000) nil)
                                  (->FakeSizer false {})
                                  (->FakeSampler (alloc-ok one-mib))
                                  (->FakeProfiler)
                                  [(->FakeCache :bad (r/err :diag/cache-unavailable {}) (r/err :diag/cache-unavailable {}))])
          res (p/diagnose c)]
      (is (r/ok? res))
      (is (= [] (:diag/caches (:ok res)))))))

;; ---------------------------------------------------------------------------
;; relieve!
;; ---------------------------------------------------------------------------

(deftest relieve-accounts-reclaim-and-attributes-caches
  (testing "before from snapshot, after from GC, reclaimed = before-after, detail = evicted caches"
    (let [gc  {:diag/used-before-bytes 9000 :diag/used-after-bytes 4000 :diag/reclaimed-bytes 5000}
          c   (clinic/make-clinic (->FakeProbe (snap 9000 20000) gc)
                                  (->FakeSizer false {})
                                  (->FakeSampler (r/err :x {}))
                                  (->FakeProfiler)
                                  [(->FakeCache :c (r/ok {:diag/cache-id :c :diag/stores []}) (r/ok {}))])
          res (p/relieve! c)]
      (is (r/ok? res))
      (is (= 9000 (:diag/used-before-bytes (:ok res))))
      (is (= 4000 (:diag/used-after-bytes  (:ok res))))
      (is (= 5000 (:diag/reclaimed-bytes   (:ok res))))
      (is (= 1 (count (:diag/detail (:ok res)))) "the evicted cache is attributed"))))

(deftest relieve-skips-cache-that-failed-to-evict
  (testing "a cache whose evict errs is NOT attributed; no :diag/detail key when none evicted"
    (let [gc  {:diag/used-before-bytes 9000 :diag/used-after-bytes 9000 :diag/reclaimed-bytes 0}
          c   (clinic/make-clinic (->FakeProbe (snap 9000 20000) gc)
                                  (->FakeSizer false {})
                                  (->FakeSampler (r/err :x {}))
                                  (->FakeProfiler)
                                  [(->FakeCache :c (r/ok {:diag/cache-id :c :diag/stores []}) (r/err :diag/cache-unavailable {}))])
          res (p/relieve! c)]
      (is (r/ok? res))
      (is (= 0 (:diag/reclaimed-bytes (:ok res))))
      (is (nil? (:diag/detail (:ok res)))))))

;; ---------------------------------------------------------------------------
;; hunt-retainers
;; ---------------------------------------------------------------------------

(deftest hunt-requires-available-sizer
  (testing "no sizer => err :diag/sizer-unavailable, never a walk"
    (let [c   (clinic/make-clinic (->FakeProbe (snap 1000 20000) nil)
                                  (->FakeSizer false {})
                                  (->FakeSampler (r/err :x {}))
                                  (->FakeProfiler) [])
          res (p/hunt-retainers c [{:diag/label "a" :diag/object :o}])]
      (is (r/err? res))
      (is (= :diag/sizer-unavailable (:error res))))))

(deftest hunt-headroom-guard-skips-when-heap-tight
  (testing "free heap (max-used) below guard => candidate SKIPPED with insufficient-headroom, not measured"
    (let [walked (atom false)
          sizer  (reify p/IRetainedSizer
                   (sizer-available? [_] true)
                   (retained-size [_ _] (reset! walked true) (r/ok {:diag/bytes 1})))
          ;; free = 10000-9000 = 1000 < guard 5000 => skip
          c   (clinic/make-clinic (->FakeProbe (snap 9000 10000) nil)
                                  sizer (->FakeSampler (r/err :x {})) (->FakeProfiler) []
                                  5000)
          res (p/hunt-retainers c [{:diag/label "a" :diag/object :o}])]
      (is (r/ok? res))
      (is (false? @walked) "the deep walk must NOT run under the guard")
      (let [root (first (:diag/roots (:ok res)))]
        (is (nil? (:diag/bytes root)))
        (is (re-find #"insufficient-headroom" (:diag/skipped root)))))))

(deftest hunt-measures-and-sorts-desc-nils-last
  (testing "with headroom, roots are measured and ranked by bytes desc; refused (nil) sort last"
    (let [;; free = 10000-2000 = 8000 >= guard 1000 => measure
          c   (clinic/make-clinic (->FakeProbe (snap 2000 10000) nil)
                                  (->FakeSizer true {:small (r/ok {:diag/bytes 100})
                                                     :big   (r/ok {:diag/bytes 500})})
                                  (->FakeSampler (r/err :x {})) (->FakeProfiler) []
                                  1000)
          res (p/hunt-retainers c [{:diag/label "small"   :diag/object :small}
                                   {:diag/label "big"     :diag/object :big}
                                   {:diag/label "refused" :diag/object :unmapped}])]
      (is (r/ok? res))
      (let [roots (:diag/roots (:ok res))]
        (is (= ["big" "small" "refused"] (mapv :diag/label roots)) "bytes desc, nil last")
        (is (= 500 (:diag/bytes (first roots))))
        (is (nil? (:diag/bytes (last roots))))
        (is (re-find #"unmapped" (:diag/skipped (last roots))) "refusal reason is recorded")))))
