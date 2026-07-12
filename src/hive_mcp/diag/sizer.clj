(ns hive-mcp.diag.sizer
  "IRetainedSizer adapter over clj-memory-meter (JOL) — the deep retained-size
   workhorse for confirming ONE suspected retainer once the histogram/hunt has
   narrowed it. A thin wrapper: it hand-rolls no object walk, delegating the
   entire graph traversal to clj-memory-meter.core/measure (which drives JOL).

   Capability-gated IO-boundary adapter: JOL self-attaches its instrumentation
   agent only when the JVM was launched with `-Djdk.attach.allowAttachSelf=true`.
   Without that flag `measure` throws, so this adapter probes the capability once
   (cached) and degrades every method to an `err` Result — the sizer is inert,
   not fatal. The library is reached lazily via `requiring-resolve`, so this ns
   is a loadable leaf even when clj-memory-meter is off the classpath.

   HAZARD: deep-walking a multi-GB graph allocates an identity set proportional
   to object count; under -XX:+ExitOnOutOfMemoryError a reckless `retained-size`
   on such a graph can kill the VM. This adapter adds NO size guard — that guard
   is the clinic's job (see IMemoryClinic/hunt-retainers). Keeping this a pure
   thin wrapper is deliberate."
  (:require [hive-spi.diag.ports :as p]
            [hive-spi.diag.schema :as schema]
            [hive-dsl.result :as r]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn- measure-fn
  "clj-memory-meter.core/measure, or nil when the library is absent."
  []
  (try
    (requiring-resolve 'clj-memory-meter.core/measure)
    (catch Throwable _ nil)))

(defn- probe-available?
  "Attempt one trivial measurement to decide whether JOL's agent is usable in
   this JVM. True iff `measure` returns a number without throwing. A throw (no
   -Djdk.attach.allowAttachSelf=true, or library absent) => unavailable."
  []
  (boolean
   (try
     (when-let [measure (measure-fn)]
       (number? (measure (object-array 4) :bytes true)))
     (catch Throwable _ false))))

(defrecord MemoryMeterSizer [available]
  p/IRetainedSizer

  (sizer-available? [_]
    @available)

  (retained-size [_ obj]
    (if-not @available
      (r/err :diag/sizer-unavailable
             {:reason "clj-memory-meter agent not attached; needs -Djdk.attach.allowAttachSelf=true"})
      (try
        ;; ONE walk only: get bytes, render the human string from them. A second
        ;; (measure obj) call would re-walk the whole graph and double the OOM
        ;; hazard the ns docstring warns about.
        (let [measure (measure-fn)
              bytes   (measure obj :bytes true)]
          (r/ok {:diag/bytes bytes :diag/human (schema/human-bytes bytes)}))
        (catch Throwable t
          (r/err :diag/sizer-refused {:reason (ex-message t)}))))))

(defn make-sizer
  "Construct the clj-memory-meter IRetainedSizer. Availability is probed once,
   lazily, on first `sizer-available?`/`retained-size` call and cached in a
   delay, so repeated calls are cheap and the (potentially agent-attaching)
   probe never runs at construction time."
  []
  (->MemoryMeterSizer (delay (probe-available?))))
