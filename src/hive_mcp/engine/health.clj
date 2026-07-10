(ns hive-mcp.engine.health
  "Per-subsystem health-budget accounting (ENGINE-L2.2).

   Each `subsystem-id` (`:hive/embedding`, `:hive/kg`, `:hive/nats`, …)
   gets a small counter map tracking:

     :alloc-bytes  — bytes allocated on the calling thread inside the
                     subsystem's tracked code, accumulated since the
                     last reset. Sourced from
                     `com.sun.management.ThreadMXBean`; nil on JVMs
                     that don't expose it.
     :cycle-ms     — wall-clock milliseconds the subsystem spent in
                     tracked code per cycle. Accumulator — divide by
                     `:cycle-count` for averages.
     :cycle-count  — number of tracked cycles since reset.
     :restarts     — count of supervisor-initiated restarts since
                     reset. Incremented from outside (see L2.1
                     supervisor follow-up).
     :last-reset   — epoch-ms timestamp of last `reset!`.

   The atom is global because subsystems are global. Tests reset
   between cases via `reset-all!`.

   ## Budget enforcement

   `budget-exceeded?` checks a subsystem's counters against a budget
   map and returns a vector of breach keywords. `with-cycle-tracking`
   bumps the counters and fires a `:health/budget-exceeded` event on
   any breach. Telemetry is best-effort — a missing event handler
   never propagates back into the caller.

   ## Why this lives next to L1.1 / L2.3

   Slot breakers (L1.1) and channel breakers (inside L2.3) react to
   *individual* failures; health budgets accumulate *aggregate* cost.
   A subsystem can be 'healthy' by breaker standards (no consecutive
   failures) while still violating its allocation budget — health
   budgets are the layer that catches the slow leak before it
   manifests as the next OOM cascade."
  (:require [hive-dsl.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Allocation source — late-resolved through ThreadMXBean
;; -----------------------------------------------------------------------------

(defn- ^Long thread-allocated-bytes
  "Bytes allocated by the calling thread since it started, or nil if
   the JVM (e.g. non-HotSpot) doesn't expose
   `com.sun.management.ThreadMXBean/getThreadAllocatedBytes`. Cached
   reflection: resolves the bean once per call."
  []
  (rescue nil
    (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)]
      (when (instance? com.sun.management.ThreadMXBean bean)
        (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean
                                  (Thread/currentThread))))))

(defn allocation-tracking-supported?
  "True when the JVM exposes per-thread allocation. Tests can ignore
   `:alloc-bytes` assertions on JVMs returning false."
  []
  (some? (thread-allocated-bytes)))

;; -----------------------------------------------------------------------------
;; State
;; -----------------------------------------------------------------------------

(defn- now-ms [] (System/currentTimeMillis))

(defn fresh
  "Initial counter map for a subsystem."
  []
  {:alloc-bytes 0
   :cycle-ms    0
   :cycle-count 0
   :restarts    0
   :last-reset  (now-ms)})

(defonce ^{:private true
           :doc "subsystem-id -> counter map. Lives for the JVM. Reset
                 via `reset-all!` (tests / operator)."}
  *health (atom {}))

(defn register!
  "Ensure `subsystem-id` has counters. Idempotent — never resets an
   existing entry. Returns the (possibly preexisting) counter map."
  [subsystem-id]
  (-> (swap! *health update subsystem-id #(or % (fresh)))
      (get subsystem-id)))

(defn snapshot
  "Read-only snapshot of every subsystem's counters."
  []
  @*health)

(defn snapshot-of
  "Counters for one subsystem, or nil if unregistered."
  [subsystem-id]
  (get @*health subsystem-id))

(defn reset!
  "Zero counters for `subsystem-id`. Use on supervisor restart so the
   replacement subsystem isn't penalised for its predecessor's bytes."
  [subsystem-id]
  (swap! *health assoc subsystem-id (fresh))
  nil)

(defn reset-all!
  "Clear every subsystem's counters. Tests / ops surface."
  []
  (clojure.core/reset! *health {}))

;; -----------------------------------------------------------------------------
;; Accounting
;; -----------------------------------------------------------------------------

(defn record-cycle!
  "Accumulate `bytes-delta` and `elapsed-ms` against a subsystem's
   counters. Bumps `:cycle-count` by 1. Either delta can be nil
   (treated as 0). Returns the post-update counter map."
  [subsystem-id bytes-delta elapsed-ms]
  (register! subsystem-id)
  (-> (swap! *health update subsystem-id
             (fn [{:keys [alloc-bytes cycle-ms cycle-count] :as m}]
               (assoc m
                      :alloc-bytes (+ (or alloc-bytes 0) (or bytes-delta 0))
                      :cycle-ms    (+ (or cycle-ms 0) (or elapsed-ms 0))
                      :cycle-count (inc (or cycle-count 0)))))
      (get subsystem-id)))

(defn record-restart!
  "Increment `:restarts` for `subsystem-id`. Called by L2.1 supervisor
   on each restart event."
  [subsystem-id]
  (register! subsystem-id)
  (-> (swap! *health update-in [subsystem-id :restarts] (fnil inc 0))
      (get subsystem-id)))

;; -----------------------------------------------------------------------------
;; Budget enforcement
;; -----------------------------------------------------------------------------

(def default-budget
  "Tunable per-subsystem ceilings. nil = no ceiling for that key.
   Override via `with-cycle-tracking` opts or `budget-exceeded?`."
  {:max-alloc-bytes    nil          ;; cumulative bytes since last reset
   :max-cycle-ms       nil          ;; cumulative wall-time since reset
   :max-restarts       10
   :max-cycle-ms-once  5000})       ;; single cycle's wall-time

(defn- exceeded
  "Pure breach check — returns a vector of breach keywords for
   `counters` vs `budget`."
  [counters {:keys [max-alloc-bytes max-cycle-ms max-restarts]} once-ms once-budget]
  (cond-> []
    (and max-alloc-bytes (> (:alloc-bytes counters 0) ^long max-alloc-bytes))
    (conj :alloc-bytes)

    (and max-cycle-ms (> (:cycle-ms counters 0) ^long max-cycle-ms))
    (conj :cycle-ms)

    (and max-restarts (> (:restarts counters 0) ^long max-restarts))
    (conj :restarts)

    (and once-budget once-ms (> ^long once-ms ^long once-budget))
    (conj :cycle-ms-once)))

(defn budget-exceeded?
  "Returns a vector of breach keywords (empty when within budget)."
  [subsystem-id budget]
  (when-let [c (snapshot-of subsystem-id)]
    (exceeded c budget nil (:max-cycle-ms-once budget))))

;; -----------------------------------------------------------------------------
;; Telemetry
;; -----------------------------------------------------------------------------

(defn- emit-breach!
  "Best-effort dispatch of `:health/budget-exceeded`. Wrapped in
   `rescue` so a missing handler is non-fatal. Late-resolves to keep
   this ns free of compile-time event deps."
  [payload]
  (rescue nil
    (when-let [dispatch (requiring-resolve 'hive-mcp.events.core/dispatch)]
      (dispatch [:health/budget-exceeded payload]))))

;; -----------------------------------------------------------------------------
;; Cycle wrapper — bracket a body with start/end + emit on breach
;; -----------------------------------------------------------------------------

(defn track-cycle!
  "Run `(f)` under a health-tracked bracket: capture start bytes/ms,
   run, capture end, record into counters, check budget, emit breach
   event if exceeded. Returns whatever `f` returned. Re-throws any
   exception `f` raised AFTER counters have been updated — partial
   work still counts against the budget.

   `opts`:
     :budget   merged into `default-budget`; nil keys disable that
               ceiling. Pass a per-call budget when subsystem-wide
               defaults aren't enough.

   Exceptions from `f` count toward the cycle budget (we still
   recorded the wasted bytes/time) but propagate to the caller."
  [subsystem-id {:keys [budget]} f]
  (let [bytes-before (thread-allocated-bytes)
        t0           (now-ms)
        result       (try {:ok (f)} (catch Throwable t {:ex t}))
        t1           (now-ms)
        bytes-after  (thread-allocated-bytes)
        delta-bytes  (when (and bytes-before bytes-after)
                       (- bytes-after bytes-before))
        elapsed      (- t1 t0)
        counters     (record-cycle! subsystem-id delta-bytes elapsed)
        full-budget  (merge default-budget budget)
        breaches     (exceeded counters full-budget elapsed
                               (:max-cycle-ms-once full-budget))]
    (when (seq breaches)
      (log/warn "[health] budget exceeded for" subsystem-id
                {:breaches breaches :counters counters})
      (emit-breach! {:subsystem-id subsystem-id
                     :breaches breaches
                     :counters counters
                     :budget full-budget}))
    (if-let [ex (:ex result)]
      (throw ex)
      (:ok result))))

(defmacro with-cycle-tracking
  "Macro sugar for `track-cycle!`.

   (with-cycle-tracking :hive/embedding
     {:budget {:max-cycle-ms-once 2000}}
     (do-embedding-work))"
  [subsystem-id opts & body]
  `(track-cycle! ~subsystem-id ~opts (fn [] ~@body)))
