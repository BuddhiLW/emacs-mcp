(ns hive-mcp.diag.alloc
  "IAllocationSampler adapter over jvm-alloc-rate-meter — answers the first fork
   in any RAM investigation: is the heap CHURNING (hot re-allocation) or STATIC
   (retained)? A near-zero mean allocation rate says the residency is retained,
   so an allocation profiler will not find it — reach for the sizer/hunt instead.

   IO-boundary adapter: it reaches jvm-alloc-rate-meter's background sampling
   thread lazily via `requiring-resolve`, so this namespace is a loadable LEAF —
   it compiles and loads even when the (dev-only) meter library is absent from
   the classpath. When the capability is missing, `sample-allocation` degrades
   to an `err` Result — the sampler is inert, not fatal."
  (:require [hive-spi.diag.ports :as p]
            [hive-spi.diag.schema :as schema]
            [hive-dsl.result :as r]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn- resolve-meter
  "Resolve [start stop] from jvm-alloc-rate-meter.core, or nil when the library
   is not on the classpath."
  []
  (try
    (let [start (requiring-resolve 'jvm-alloc-rate-meter.core/start-alloc-rate-meter)
          stop  (requiring-resolve 'jvm-alloc-rate-meter.core/stop-alloc-rate-meter)]
      (when (and start stop) [start stop]))
    (catch Throwable _ nil)))

(defrecord AllocRateSampler []
  p/IAllocationSampler

  (sample-allocation [_ duration-ms]
    (if-let [[start stop] (resolve-meter)]
      (try
        (let [samples (atom [])
              meter   (start (fn [bps] (swap! samples conj bps)))]
          (try
            (Thread/sleep (long duration-ms))
            (finally
              (stop meter)))
          (let [xs   (mapv long @samples)
                n    (count xs)
                mean (if (pos? n) (long (/ (reduce + 0 xs) n)) 0)
                peak (if (pos? n) (long (reduce max xs)) 0)]
            (r/ok (schema/->allocation
                   {:diag/duration-ms (long duration-ms)
                    :diag/samples-bps xs
                    :diag/mean-bps    mean
                    :diag/peak-bps    peak}))))
        (catch Throwable e
          (r/err :diag/sampler-unavailable
                 {:reason (.getMessage e) :duration-ms duration-ms})))
      (r/err :diag/sampler-unavailable
             {:reason "jvm-alloc-rate-meter.core not resolvable"
              :duration-ms duration-ms}))))

(defn make-sampler
  "Construct the jvm-alloc-rate-meter IAllocationSampler. Zero-config: it locates
   the meter library lazily on each `sample-allocation` call, so it is safe to
   build even when the library is absent."
  []
  (->AllocRateSampler))
