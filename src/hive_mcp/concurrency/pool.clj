(ns hive-mcp.concurrency.pool
  "Central bounded thread-pool registry for hive-mcp.

   Thin registry layer: the underlying bounded ThreadPoolExecutor
   construction, safe submit/await, and lifecycle all live in
   `hive-weave.pool`. This namespace holds the *named instances* of
   those pools so the rest of hive-mcp can resolve a pool by role.

   Pools:
   1. :io-pool     — blocking I/O (HTTP, file, DB, embeddings)
                      Size: (+ 2 (* 2 available-processors))
   2. :compute-pool — CPU-bound work (KG traversal, compression)
                      Size: available-processors
   3. :event-pool   — fire-and-forget event dispatch
                      Size: 4
   4. :memory-pool  — memory tool IO (Chroma HTTP, KG mutations, plan
                      indexing). Isolated from io-pool so slow memory
                      calls cannot starve other IO consumers.
                      Size: (max 4 available-processors)

   All pools use bounded queues with CallerRunsPolicy backpressure
   (see `hive-weave.pool/make-pool`).

   Client code depends on `hive-weave.pool` primitives (submit!,
   await!, with-pool-await) and passes one of the named pool instances
   below — it should not reach into `java.util.concurrent` directly."
  (:require [hive-weave.pool :as wpool]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Pool Sizes
;; =============================================================================

(def ^:private available-processors
  (.availableProcessors (Runtime/getRuntime)))

(def ^:private io-pool-size
  (+ 2 (* 2 available-processors)))

(def ^:private compute-pool-size
  available-processors)

(def ^:private event-pool-size
  4)

(def ^:private memory-pool-size
  (max 4 available-processors))

(def ^:private catchup-pool-size
  4)

;; =============================================================================
;; Pool Instances (lazy init via delay)
;; =============================================================================

(defonce ^:private io-pool-delay
  (delay (wpool/make-pool {:name "hive-io" :size io-pool-size})))

(defonce ^:private compute-pool-delay
  (delay (wpool/make-pool {:name "hive-compute" :size compute-pool-size})))

(defonce ^:private event-pool-delay
  (delay (wpool/make-pool {:name "hive-event" :size event-pool-size})))

(defonce ^:private memory-pool-delay
  (delay (wpool/make-pool {:name "hive-memory" :size memory-pool-size})))

(defonce ^:private catchup-pool-delay
  (delay (wpool/make-pool {:name "hive-catchup" :size catchup-pool-size})))

(defn io-pool       [] @io-pool-delay)
(defn compute-pool  [] @compute-pool-delay)
(defn event-pool    [] @event-pool-delay)
(defn memory-pool   [] @memory-pool-delay)
(defn catchup-pool  [] @catchup-pool-delay)

;; =============================================================================
;; Submit API (thin delegation; prefer hive-weave.pool directly)
;; =============================================================================

(defn submit-io!
  "Submit a blocking I/O task to the IO pool."
  [f]
  (wpool/submit! (io-pool) f))

(defn submit-compute!
  "Submit a CPU-bound task to the compute pool."
  [f]
  (wpool/submit! (compute-pool) f))

(defn submit-event!
  "Submit a fire-and-forget event-dispatch task."
  [f]
  (wpool/submit! (event-pool) f))

(defn submit-memory!
  "Submit a memory tool IO task (Chroma HTTP, KG mutation, plan indexing)."
  [f]
  (wpool/submit! (memory-pool) f))

;; =============================================================================
;; DSL Macros — sugar that matches the hive-weave.pool primitives
;; =============================================================================

(defmacro with-io        [& body] `(submit-io!      (fn [] ~@body)))
(defmacro with-compute   [& body] `(submit-compute! (fn [] ~@body)))
(defmacro with-event     [& body] `(submit-event!   (fn [] ~@body)))
(defmacro with-memory    [& body] `(submit-memory!  (fn [] ~@body)))

(defmacro with-solo
  "Submit body to Clojure's solo executor (unbounded cached thread pool).
   Use for coordinator tasks that internally spawn bounded-pool work —
   avoids nested-pool contention where a bounded-pool thread blocks
   while spawning more work on the same pool."
  [& body]
  `(future ~@body))

;; =============================================================================
;; Monitoring
;; =============================================================================

(defn pool-stats
  "Snapshot of every named pool."
  []
  {:io      (wpool/pool-stats (io-pool))
   :compute (wpool/pool-stats (compute-pool))
   :event   (wpool/pool-stats (event-pool))
   :memory  (wpool/pool-stats (memory-pool))
   :catchup (wpool/pool-stats (catchup-pool))})

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn shutdown-pools!
  "Orderly shutdown of all realized pools. Waits up to 5 seconds per
   pool, then force-shuts. Call during JVM shutdown or REPL teardown."
  []
  (doseq [pool-delay [io-pool-delay compute-pool-delay
                      event-pool-delay memory-pool-delay
                      catchup-pool-delay]]
    (when (realized? pool-delay)
      (wpool/shutdown! @pool-delay {:await-ms 5000}))))
