(ns hive-mcp.diag.profiler
  "IProfiler adapter over clj-async-profiler — the toggleable sampling profiler
   that renders flamegraph artifacts (CPU, alloc, wall, lock call-stacks).

   IO-boundary adapter: it wraps exactly ONE library (clj-async-profiler) and is
   STATEFUL — async-profiler permits at most one session at a time. That session
   is held in an atom field on the record: nil when idle, {:event … :started-ms …}
   while sampling. `start-profiling!`/`stop-profiling!` are the on/off toggle.

   Loadable leaf: clj-async-profiler is reached via `requiring-resolve` at call
   time, never a top-level :require, so this namespace compiles on a box where
   the profiler (or its native agent) is absent. When the library is not
   resolvable, `start-profiling!` degrades to an `err` Result — inert, not fatal."
  (:require [hive-spi.diag.ports :as p]
            [hive-spi.diag.schema :as schema]
            [hive-dsl.result :as r]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn- resolve-start
  "clj-async-profiler.core/start, or nil when the library is absent."
  []
  (try (requiring-resolve 'clj-async-profiler.core/start)
       (catch Throwable _ nil)))

(defn- resolve-stop
  "clj-async-profiler.core/stop, or nil when the library is absent."
  []
  (try (requiring-resolve 'clj-async-profiler.core/stop)
       (catch Throwable _ nil)))

(defrecord AsyncProfiler [state]
  p/IProfiler

  (profiler-active? [_]
    (some? @state))

  (start-profiling! [_ opts]
    (if (some? @state)
      (r/err :diag/profiler-already-running {})
      (if-let [start (resolve-start)]
        (try
          (let [event (:diag/event opts :cpu)]
            (start {:event event})
            (reset! state {:event event :started-ms (System/currentTimeMillis)})
            (r/ok {:diag/event event}))
          (catch Throwable t
            (r/err :diag/profiler-unavailable {:reason (ex-message t)})))
        (r/err :diag/profiler-unavailable {}))))

  (stop-profiling! [_]
    (let [prev @state]
      (if (nil? prev)
        (r/err :diag/profiler-not-running {})
        (if-let [stop (resolve-stop)]
          (try
            (let [file (stop {})
                  dur  (- (System/currentTimeMillis) (:started-ms prev))]
              (r/ok (schema/->flamegraph {:diag/event       (:event prev)
                                          :diag/path        (str file)
                                          :diag/duration-ms dur})))
            (catch Throwable t
              (r/err :diag/profiler-unavailable {:reason (ex-message t)}))
            ;; ALWAYS clear our session tracking, even if the native stop threw —
            ;; otherwise profiler-active? stays true forever and start-profiling!
            ;; is permanently wedged at :profiler-already-running.
            (finally (reset! state nil)))
          (r/err :diag/profiler-unavailable {}))))))

(defn make-profiler
  "Construct the clj-async-profiler IProfiler. Zero-config: it resolves the
   library lazily on each toggle, so it is safe to build even on a JVM where
   async-profiler is not on the classpath — it simply degrades to an `err`."
  []
  (->AsyncProfiler (atom nil)))
