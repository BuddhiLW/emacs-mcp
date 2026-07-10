(ns hive-mcp.knowledge-graph.connection.writer
  (:require [clojure.core.async :as async]
            [hive-dsl.batch :as dsl-batch]
            [hive-dsl.result :as r]
            [hive-mcp.knowledge-graph.connection.store :as store]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [taoensso.timbre :as log]))

(declare coalesce-window-ms coalesce-max-batch writer-state writer-metrics in-flight flush-batch! start-writer-loop! ensure-writer! stop-writer! writer-stats flush-pending! drain-writer! offer-coalesced! write-sync-fallback!)

(def ^:private coalesce-window-ms
  "Time window to drain additional items before flushing batch.
   Balances latency vs batch size. 25ms gives good coalescing
   without noticeable delay on interactive operations."
  25)

(def ^:private coalesce-max-batch
  "Maximum batch size before forcing a flush, even within the window."
  200)

(defonce ^:private writer-state
  (atom {:running? false :tx-chan nil :ctrl-chan nil}))

(defonce ^:private writer-metrics
  (atom {:batches-flushed 0 :items-written 0 :items-dropped 0 :largest-batch 0}))

;; Count of items enqueued on tx-chan but not yet flushed. Public so callers
;; (e.g. hive-ingestor's writer guard) can observe queue depth.
(defonce in-flight (atom 0))

(defn- flush-batch!
  "Flush accumulated tx-data as a single transaction.
   `batch-item-count` is the number of producer-side items this batch drained
   from tx-chan (used to decrement in-flight); it may differ from (count batch)
   after dsl-batch/normalize-tx-datum expansion."
  [batch batch-item-count]
  (when (seq batch)
    (let [n (count batch)]
      (try
        (proto/transact! (store/ensure-store!) batch)
        (swap! writer-metrics (fn [m]
                                (-> m
                                    (update :batches-flushed inc)
                                    (update :items-written + n)
                                    (update :largest-batch max n))))
        (catch Throwable t
          (log/error "Coalesced batch transact failed, falling back to individual writes"
                     {:batch-size n :error (.getMessage t)})
          ;; Fallback: retry items individually so we don't lose data
          (doseq [item batch]
            (try
              (proto/transact! (store/ensure-store!) [item])
              (swap! writer-metrics update :items-written inc)
              (catch Throwable t2
                (log/error "Individual fallback transact also failed"
                           {:item item :error (.getMessage t2)})
                (swap! writer-metrics update :items-dropped inc)))))
        (finally
          (swap! in-flight - batch-item-count))))))

(defn- start-writer-loop!
  "Start the background write-coalescing consumer loop.
   Creates fresh tx-chan + ctrl-chan each time (fixes channel death on stop).
   Returns map with :tx-chan :ctrl-chan :go-chan."
  []
  (let [tx-chan   (async/chan 4096)
        ctrl-chan (async/chan)
        go-chan   (async/go-loop []
                    (let [[val port] (async/alts! [ctrl-chan tx-chan])]
                      (cond
                       ;; ctrl-chan closed or signaled — graceful shutdown
                        (= port ctrl-chan)
                        (log/debug "Writer loop received shutdown signal")

                       ;; tx-chan closed — also done
                        (nil? val)
                        (log/debug "Writer loop tx-chan closed")

                        :else
                        (let [first-item val
                              [batch producer-count]
                              (loop [batch (into [] (dsl-batch/normalize-tx-datum first-item))
                                     producer-count 1
                                     remaining coalesce-window-ms]
                                (if (or (<= remaining 0)
                                        (>= (count batch) coalesce-max-batch))
                                  [batch producer-count]
                                  (let [t0 (System/currentTimeMillis)
                                        [item port] (async/alts! [ctrl-chan
                                                                  tx-chan
                                                                  (async/timeout remaining)])]
                                    (cond
                                      (= port ctrl-chan) [batch producer-count]
                                      (nil? item)        [batch producer-count]
                                      :else
                                      (recur (into batch (dsl-batch/normalize-tx-datum item))
                                             (inc producer-count)
                                             (- remaining (- (System/currentTimeMillis) t0)))))))]
                          (flush-batch! batch producer-count)
                          (recur)))))]
    {:tx-chan tx-chan :ctrl-chan ctrl-chan :go-chan go-chan}))

(defn ensure-writer!
  "Ensure the write-coalescing loop is running.
   Uses locking + double-check to prevent concurrent starts."
  []
  (when-not (:running? @writer-state)
    (locking writer-state
      (when-not (:running? @writer-state)
        (let [{:keys [tx-chan ctrl-chan go-chan]} (start-writer-loop!)]
          (reset! writer-state {:running? true
                                :tx-chan   tx-chan
                                :ctrl-chan ctrl-chan
                                :go-chan   go-chan})
          (log/debug "Started KG write-coalescing queue"))))))

(defn stop-writer!
  "Stop the write-coalescing loop. Idempotent — safe to call multiple times."
  []
  (locking writer-state
    (let [{:keys [running? ctrl-chan tx-chan]} @writer-state]
      (when running?
        (when ctrl-chan (async/close! ctrl-chan))
        (when tx-chan (async/close! tx-chan))
        (reset! writer-state {:running? false :tx-chan nil :ctrl-chan nil})
        (log/debug "Stopped KG write-coalescing queue")))))

(defn writer-stats
  "Return writer metrics + running state for observability."
  []
  (merge @writer-metrics
         {:running? (:running? @writer-state)}))

(defn flush-pending!
  "Busy-wait until the write-coalescing queue is empty and no items are in flight.
   Deterministic replacement for (Thread/sleep N) after transact! in tests.
   Bounded deadline prevents indefinite hang if the writer is dead — returns
   `:weave/timeout` after deadline-ms (default 5000ms) rather than blocking forever.
   Returns `:ok` when drained. No-op (returns `:ok`) if writer not running."
  ([] (flush-pending! 5000))
  ([deadline-ms]
   (if-not (:running? @writer-state)
     :ok
     (let [deadline (+ (System/currentTimeMillis) deadline-ms)]
       (loop []
         (cond
           (zero? @in-flight) :ok
           (> (System/currentTimeMillis) deadline)
           (do (log/warn "flush-pending! deadline exceeded, items still in flight:" @in-flight)
               :weave/timeout)
           :else
           (do (Thread/sleep 5)
               (recur))))))))

(defn drain-writer!
  "Deprecated — prefer flush-pending!. Retained as alias for callers and tests
   that still reference the old name."
  {:deprecated "use flush-pending!"}
  []
  (flush-pending!))

(defn offer-coalesced!
  "Enqueue tx-data on the write-coalescing channel. Pre-increments in-flight
   before put! so flush-pending! never observes a transient zero while an item
   is mid-enqueue. Returns true when accepted, false when the channel is
   full/closed (compensating the in-flight bump)."
  [tx-data]
  (let [tx-chan (:tx-chan @writer-state)]
    (swap! in-flight inc)
    (if (and tx-chan (async/put! tx-chan tx-data))
      true
      (do (swap! in-flight dec) false))))

(defn write-sync-fallback!
  "Direct synchronous write used when the coalescing queue cannot accept an
   item, so data is never lost. Records the queue-miss in writer-metrics."
  [store tx-data]
  (log/warn "Write-coalescing queue put! failed, falling back to sync transact"
            {:tx-data-count (if (sequential? tx-data) (count tx-data) 1)})
  (swap! writer-metrics update :items-dropped
         + (if (sequential? tx-data) (count tx-data) 1))
  (r/rescue nil
            (proto/transact! store (dsl-batch/normalize-tx-datum tx-data))))
