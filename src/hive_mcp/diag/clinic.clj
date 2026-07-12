(ns hive-mcp.diag.clinic
  "IMemoryClinic FACADE — the composed diagnostic surface of the memory clinic.

   This is a PURE composition leaf: it depends ONLY on the SPI port contracts
   (hive-spi.diag.ports) and their value-object schemas (hive-spi.diag.schema),
   receiving every capability by INJECTION through its record fields. It reaches
   into no adapter internals and requires no diagnostic library of its own —
   which is precisely why it is trivially loadable and testable against fakes.

   It threads the injected ports into railway workflows: `diagnose` composes a
   heap snapshot, class histogram, allocation sample and per-cache occupancy into
   one report (tolerating the fallible parts); `relieve!` evicts every registered
   cache probe then GCs, attributing reclaimed bytes to named caches; and
   `hunt-retainers` ranks suspected roots by deep retained size, gated on an
   available sizer. Fatal steps short-circuit as `err` Results; environmental
   softness (a sampler with no agent, a cache backend not loaded) is tolerated,
   never thrown."
  (:require [hive-spi.diag.ports :as p]
            [hive-spi.diag.schema :as schema]
            [hive-dsl.result :as r]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def ^:private churn-threshold-bps
  "Mean allocation rate (bytes/sec) at or above which the heap is deemed to be
   CHURNING rather than holding STATIC residency: 5 MiB/s."
  (* 5 1024 1024))

(def default-guard-bytes
  "Default minimum FREE heap (max - used) required before the clinic will risk a
   deep retained-size walk. clj-memory-meter has no bounded mode: sizing an
   object allocates a transient identity set roughly proportional to its
   reachable-object count, so on a nearly-full heap under
   -XX:+ExitOnOutOfMemoryError a single hunt can tip the VM over and kill it.
   The guard refuses the walk unless this much headroom exists. 4 GiB, override
   via hive-di.presets.diag DiagConfig :sizer-guard-bytes."
  (* 4 1024 1024 1024))

(defn- free-heap-bytes
  "Free heap = max - used, from a fresh probe snapshot (nil-safe)."
  [probe]
  (let [s (p/heap-snapshot probe)]
    (- (or (:diag/max-bytes s) 0) (or (:diag/used-bytes s) 0))))

(defn- bytes-desc-nils-last
  "Comparator over retainer roots: :diag/bytes descending, nil (skipped) last."
  [a b]
  (let [x (:diag/bytes a) y (:diag/bytes b)]
    (cond
      (and (nil? x) (nil? y)) 0
      (nil? x)                1
      (nil? y)                -1
      :else                   (compare y x))))

(defn- skip-reason
  "A string reason for a skipped candidate, drawn from the sizer's err Result."
  [res]
  (str (or (:error/reason res) (:reason res) (:error res))))

(defrecord MemoryClinic [probe sizer sampler profiler cache-probes guard-bytes]
  p/IMemoryClinic

  (diagnose [_]
    ;; snapshot + histogram are raw value-objects (IHeapProbe never throws and
    ;; returns no Result); allocation + caches are fallible and TOLERATED.
    (r/let-ok
     [:let [snapshot   (p/heap-snapshot probe)
            histogram  (p/class-histogram probe 25)
            alloc-res  (p/sample-allocation sampler 3000)
            alloc      (when (r/ok? alloc-res) (:ok alloc-res))
            verdict    (cond
                         (nil? alloc)                                     :unknown
                         (< (:diag/mean-bps alloc) churn-threshold-bps)   :static-residency
                         :else                                            :churning)
            caches     (into []
                             (comp (map #(p/cache-occupancy %))
                                   (filter r/ok?)
                                   (map :ok))
                             cache-probes)]]
      (r/ok
       (schema/->diagnosis
        (cond-> {:diag/snapshot  snapshot
                 :diag/histogram histogram
                 :diag/caches    caches
                 :diag/verdict   verdict}
          alloc (assoc :diag/allocation alloc))))))

  (relieve! [_]
    ;; Aggregate before-heap from the snapshot; evict each cache (tolerating
    ;; errs), attributing pre-evict occupancy of the caches that evicted ok;
    ;; then GC and take the after-heap from its Reclamation.
    (let [before (:diag/used-bytes (p/heap-snapshot probe))
          detail (into []
                       (keep (fn [cp]
                               (let [occ (p/cache-occupancy cp)
                                     ev  (p/evict-cache! cp)]
                                 (when (and (r/ok? ev) (r/ok? occ))
                                   (:ok occ)))))
                       cache-probes)
          after  (:diag/used-after-bytes (p/request-gc! probe))]
      (r/ok
       (schema/->reclamation
        (cond-> {:diag/used-before-bytes before
                 :diag/used-after-bytes  after
                 :diag/reclaimed-bytes   (- before after)}
          (seq detail) (assoc :diag/detail detail))))))

  (hunt-retainers [_ candidates]
    (if-not (p/sizer-available? sizer)
      (r/err :diag/sizer-unavailable {})
      ;; OOM GUARD: re-check free heap before EACH candidate (a prior measure's
      ;; identity set is GC'd between candidates, restoring headroom). If free
      ;; heap is below the guard, REFUSE the deep walk and skip the candidate —
      ;; never risk tipping the VM under -XX:+ExitOnOutOfMemoryError.
      (let [guard (or guard-bytes default-guard-bytes)
            roots (->> candidates
                       (mapv (fn [{:diag/keys [label object]}]
                               (let [free (free-heap-bytes probe)]
                                 (if (< free guard)
                                   {:diag/label   label
                                    :diag/bytes   nil
                                    :diag/skipped (str "insufficient-headroom: free "
                                                       free " < guard " guard " bytes")}
                                   (let [res (p/retained-size sizer object)]
                                     (if (r/ok? res)
                                       {:diag/label label :diag/bytes (:diag/bytes (:ok res))}
                                       {:diag/label label :diag/bytes nil
                                        :diag/skipped (skip-reason res)}))))))
                       (sort bytes-desc-nils-last)
                       vec)]
        (r/ok (schema/->retainer-report {:diag/roots roots}))))))

(defn make-clinic
  "Construct the composed memory-clinic facade from injected ports.

   Arguments (all by injection — the clinic owns none of these capabilities):
     probe        — IHeapProbe        (heap/RSS reads, GC, hprof dumps).
     sizer        — IRetainedSizer    (deep retained size of one object).
     sampler      — IAllocationSampler (allocation-rate window).
     profiler     — IProfiler         (toggleable flamegraph profiler).
     cache-probes — vector of ICacheProbe (each an inspectable/evictable cache).

   Returns a MemoryClinic implementing IMemoryClinic. Pure; never throws.

   The 6-arg arity takes an explicit guard-bytes (minimum free heap before a
   `hunt-retainers` deep-walk is attempted); the 5-arg arity uses
   `default-guard-bytes` (4 GiB). Wire guard-bytes from DiagConfig
   :sizer-guard-bytes."
  ([probe sizer sampler profiler cache-probes]
   (make-clinic probe sizer sampler profiler cache-probes default-guard-bytes))
  ([probe sizer sampler profiler cache-probes guard-bytes]
   (->MemoryClinic probe sizer sampler profiler (vec cache-probes) guard-bytes)))
