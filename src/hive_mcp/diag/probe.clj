(ns hive-mcp.diag.probe
  "IHeapProbe adapter over the JVM's OWN management surface — no third-party
   dependency. It is the always-available, zero-cost baseline probe of the diag
   clinic: it reads live heap/non-heap occupancy and per-pool breakdown from the
   JMX MemoryMXBean, process RSS from /proc/self/status, a live class histogram
   from the HotSpot DiagnosticCommand MBean (gcClassHistogram), forces a GC to
   measure reclaimable residency, and writes an hprof dump via the HotSpot
   Diagnostic MXBean for offline dominator analysis.

   IO-boundary adapter: the JDK management beans and /proc are the ONLY things it
   touches, so it loads on any HotSpot JVM with nothing on the classpath but the
   platform. The three read methods are infallible per the port contract (they
   degrade to empty/nil rather than throw); only `dump-heap!` — which writes a
   file and can fail on IO/mbean grounds — returns a Result."
  (:require [hive-spi.diag.ports :as p]
            [hive-spi.diag.schema :as schema]
            [hive-dsl.result :as r]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang.management ManagementFactory MemoryUsage]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn- nn
  "Coerce a JMX byte reading to a NonNegLong: negative (e.g. an undefined -1 max)
   or nil becomes nil; a non-negative value is returned as-is."
  [x]
  (when (and x (>= x 0)) x))

(defn- read-rss-bytes
  "Process resident-set size in bytes, parsed from /proc/self/status 'VmRSS:'
   (reported in kB), or nil when unreadable (non-Linux, sandboxed /proc, …)."
  []
  (try
    (some->> (str/split-lines (slurp "/proc/self/status"))
             (some (fn [line]
                     (when (str/starts-with? line "VmRSS:")
                       (some-> (re-find #"(\d+)" line) second parse-long (* 1024))))))
    (catch Throwable _ nil)))

(defn- pool-maps
  "Per-pool occupancy from ManagementFactory/getMemoryPoolMXBeans, skipping any
   pool whose usage is momentarily nil."
  []
  (vec
   (for [pool (ManagementFactory/getMemoryPoolMXBeans)
         :let [usage (.getUsage pool)]
         :when usage]
     {:diag/name (.getName pool)
      :diag/used-bytes (nn (.getUsed ^MemoryUsage usage))
      :diag/committed-bytes (nn (.getCommitted ^MemoryUsage usage))})))

(defn- parse-histogram-line
  "Parse one gcClassHistogram row '  <rank>:  <instances>  <bytes>  <class>' into
   {:diag/rank :diag/class :diag/instances :diag/bytes}, or nil for the header,
   the Total footer, and any non-conformant line."
  [line]
  (let [toks (-> line str/trim (str/split #"\s+"))
        [rank instances bytes] toks
        rank* (some-> rank (str/replace #":$" "") parse-long)]
    (when (and rank* (>= (count toks) 4))
      (when-let [inst (parse-long instances)]
        (when-let [b (parse-long bytes)]
          {:diag/rank rank*
           :diag/class (str/join " " (drop 3 toks))
           :diag/instances inst
           :diag/bytes b})))))

(defn- parse-total-bytes
  "Retained-bytes total from the histogram's trailing 'Total  <instances>  <bytes>'
   line, or nil when absent."
  [lines]
  (some (fn [line]
          (let [t (str/trim line)]
            (when (str/starts-with? t "Total")
              (let [[_ _ bytes] (str/split t #"\s+")]
                (some-> bytes parse-long)))))
        lines))

(defrecord HotSpotHeapProbe []
  p/IHeapProbe

  (heap-snapshot [_]
    (let [mx (ManagementFactory/getMemoryMXBean)
          heap (.getHeapMemoryUsage mx)
          non-heap (.getNonHeapMemoryUsage mx)]
      (schema/->heap-snapshot
       (cond-> {:diag/used-bytes (nn (.getUsed heap))
                :diag/committed-bytes (nn (.getCommitted heap))
                :diag/max-bytes (nn (.getMax heap))
                :diag/non-heap-bytes (nn (.getUsed non-heap))
                :diag/pools (pool-maps)
                :diag/at-ms (System/currentTimeMillis)}
         (read-rss-bytes) (assoc :diag/rss-bytes (read-rss-bytes))))))

  (class-histogram [_ n]
    ;; Pure read per contract: on any mbean failure, degrade to an empty
    ;; histogram rather than throw.
    (let [now (System/currentTimeMillis)
          {:keys [entries total]}
          (try
            (let [srv (ManagementFactory/getPlatformMBeanServer)
                  on (javax.management.ObjectName. "com.sun.management:type=DiagnosticCommand")
                  out (.invoke srv on "gcClassHistogram"
                               (into-array Object [(make-array String 0)])
                               (into-array String ["[Ljava.lang.String;"]))
                  lines (str/split-lines (str out))]
              {:entries (->> lines (keep parse-histogram-line) (take n) vec)
               :total (parse-total-bytes lines)})
            (catch Throwable _ {:entries [] :total nil}))]
      (schema/->class-histogram
       (cond-> {:diag/entries entries :diag/at-ms now}
         total (assoc :diag/total-bytes total)))))

  (request-gc! [_]
    (let [rt (Runtime/getRuntime)
          used #(- (.totalMemory rt) (.freeMemory rt))
          before (used)]
      (dotimes [_ 2] (System/gc) (Thread/sleep 200))
      (let [after (used)]
        (schema/->reclamation
         {:diag/used-before-bytes (nn before)
          :diag/used-after-bytes (nn after)
          :diag/reclaimed-bytes (- before after)}))))

  (dump-heap! [_ path live?]
    (if (.exists (io/file path))
      (r/err :diag/dump-failed {:reason "path exists" :path path})
      (try
        (let [bean (ManagementFactory/getPlatformMXBean
                    com.sun.management.HotSpotDiagnosticMXBean)]
          (.dumpHeap bean path (boolean live?))
          (r/ok {:diag/path path :diag/bytes (.length (io/file path))}))
        (catch Throwable t
          (r/err :diag/dump-failed {:reason (.getMessage t) :path path}))))))

(defn make-heap-probe
  "Construct the JDK-only IHeapProbe. Zero-config and always loadable: it binds
   the platform management beans lazily inside each method, so building it never
   requires a capability check."
  []
  (->HotSpotHeapProbe))
