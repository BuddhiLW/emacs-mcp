(ns hive-mcp.diag.boot
  "ORCHESTRATION FACADE for the memory-diagnostics clinic — the single wiring
   point that folds the five capability adapters into one injected IMemoryClinic
   and exposes a flat, REPL-ergonomic toggle surface over it.

   Mirrors hive-mcp.engine.hprof.boot: it owns NO capability of its own and
   implements no port — it merely `requiring`s each loadable-leaf adapter
   (probe, sizer, sampler, profiler, cache), constructs each via its smart
   constructor, and injects them into `clinic/make-clinic`. The composed clinic
   is memoized behind a lazy `defonce` singleton so the (agent-attaching,
   library-probing) adapters are built at most once, on first use.

   The convenience passthroughs (`snapshot`, `histogram`, `diagnose`, `caches`,
   `relieve!`, `on!`/`off!`) are the human-facing toggle surface for a live RAM
   investigation from a REPL: they route to the matching SPI port — the clinic
   facade for composed workflows, and the clinic's injected sub-adapters
   (`:probe`, `:profiler`, `:cache-probes`) for the single-capability reads and
   the profiler on/off toggle, which the clinic does not itself expose."
  (:require [hive-spi.diag.ports   :as p]
            [hive-mcp.diag.probe    :as probe]
            [hive-mcp.diag.sizer    :as sizer]
            [hive-mcp.diag.alloc    :as alloc]
            [hive-mcp.diag.profiler :as profiler]
            [hive-mcp.diag.cache    :as cache]
            [hive-mcp.diag.cache-datahike :as cache-dh]
            [hive-mcp.diag.clinic   :as clinic]
            [hive-di.presets.diag   :as diag-cfg]
            [hive-dsl.result        :as r]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defonce ^:private clinic-atom
  (atom nil))

(defn- resolve-sizer-guard-bytes
  "Read :sizer-guard-bytes (min free-heap headroom before a deep retained-size
   walk) from DiagConfig — env `HIVE_DIAG_SIZER_GUARD` > default. Falls back to
   `clinic/default-guard-bytes` only if config resolution itself errors
   (defensive; the field carries a :default so this should not happen)."
  []
  (let [result (diag-cfg/resolve-DiagConfig)]
    (if (r/ok? result)
      (:sizer-guard-bytes (:ok result))
      clinic/default-guard-bytes)))

(defn build-clinic
  "Construct a fresh composed clinic: build each adapter via its smart ctor and
   inject them into `clinic/make-clinic` (positional: probe, sizer, sampler,
   profiler, cache-probes, guard-bytes). Every adapter is a loadable leaf, so
   this never requires any diagnostic library to be present — absent
   capabilities simply degrade to `err` Results at call time. The sizer
   OOM-guard headroom is wired from DiagConfig (see `resolve-sizer-guard-bytes`)."
  []
  (clinic/make-clinic
   (probe/make-heap-probe)
   (sizer/make-sizer)
   (alloc/make-sampler)
   (profiler/make-profiler)
   [(cache/make-datalevin-query-cache)
    (cache-dh/make-datahike-query-cache)]
   (resolve-sizer-guard-bytes)))

(defn clinic
  "The lazily-built, process-wide IMemoryClinic singleton. First call constructs
   and caches it; subsequent calls return the cached instance."
  []
  (or @clinic-atom (reset! clinic-atom (build-clinic))))

;; ---------------------------------------------------------------------------
;; REPL toggle surface — thin passthroughs over the clinic + its injected ports.
;; ---------------------------------------------------------------------------

(defn snapshot
  "Live heap + RSS reading (IHeapProbe/heap-snapshot). Pure; never throws."
  []
  (p/heap-snapshot (:probe (clinic))))

(defn histogram
  "Top-`n` live classes by retained bytes (IHeapProbe/class-histogram); forces a
   full GC. Defaults to 25 rows."
  ([]  (histogram 25))
  ([n] (p/class-histogram (:probe (clinic)) n)))

(defn diagnose
  "One-shot situational report: snapshot + histogram + allocation verdict +
   per-cache occupancy (IMemoryClinic/diagnose)."
  []
  (p/diagnose (clinic)))

(defn caches
  "Occupancy of every registered cache probe (counts/capacity only, no value
   realization). Returns a vector of ICacheProbe/cache-occupancy Results."
  []
  (mapv p/cache-occupancy (:cache-probes (clinic))))

(defn relieve!
  "Best-effort RSS relief: evict every registered cache probe, GC, and report the
   aggregate Reclamation (IMemoryClinic/relieve!). Safe — pure caches only."
  []
  (p/relieve! (clinic)))

(defn on!
  "Start the sampling profiler (the toggle's ON). Defaults to a :cpu profile;
   pass an event in #{:cpu :alloc :wall :lock}. Returns a Result."
  ([]      (on! :cpu))
  ([event] (p/start-profiling! (:profiler (clinic)) {:diag/event event})))

(defn off!
  "Stop the active profiling session and render its flamegraph (the toggle's
   OFF). Returns a Result — ok FlamegraphArtifact, or err :diag/profiler-not-running."
  []
  (p/stop-profiling! (:profiler (clinic))))

(comment
  ;; Typical live RAM investigation, from symptom to relief to root cause.

  ;; 1. Situational snapshot — where is the heap right now?
  (snapshot)                    ;; => used / committed / max heap + RSS bytes

  ;; 2. Who is holding it? Top-N live classes by retained bytes (forces a GC).
  (histogram)                   ;; or (histogram 50)

  ;; 3. Alloc verdict — CHURNING or STATIC? A near-zero mean says the residency
  ;;    is RETAINED, so a profiler won't find it — reach for caches/hunt instead.
  (diagnose)                    ;; snapshot + histogram + alloc verdict + caches

  ;; 4. Interrogate the usual invisible retainers — library-private caches.
  (caches)                      ;; per-cache occupancy, no value realization

  ;; 5. Best-effort RSS relief — evict pure caches, GC, report reclaimed bytes.
  (relieve!)

  ;; 6. Still heavy? Hunt named roots by deep retained size (needs the sizer).
  (p/hunt-retainers (clinic)
                    [{:diag/label "app-cache" :diag/object @some-app-atom}])

  ;; 7. Toggle the sampling profiler around a suspect workload → flamegraph.
  (on! :alloc)                  ;; or (on!) for :cpu
  ;; … exercise the workload …
  (off!)                        ;; renders the flamegraph, returns its path
  )
