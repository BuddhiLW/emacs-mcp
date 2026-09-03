(ns hive-mcp.tools.multi-async
  "Async batch dispatch for cross-tool operations.

   Wraps tools.multi/run-multi in a future, stores results in context-store.
   Enables non-blocking batch execution with collect-by-id semantics.

   Architecture:
     multi_async.clj (THIS — async wrapper)
       -> tools/multi.clj (batch engine)
       -> channel/context_store.clj (result storage)
       -> dns/result.clj (error handling)

   Lifecycle:
     1. run-multi-async: launch future, return batch-id immediately
     2. collect-async-result: poll for result by batch-id
     3. list-async-batches: enumerate active batches
     4. cancel-async-batch: future-cancel a running batch

   Design decision: Phase 3 of Multi DSL plan (20260211212019-b8499942)"
  (:require [hive-dsl.bounded-atom :refer [bounded-atom bput! bget bounded-swap! bclear! register-sweepable!]]
            [hive-mcp.channel.context-store :as ctx]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const default-result-ttl-ms
  "Default TTL for async batch results in context-store (10 minutes)."
  600000)

(def ^:const default-await-timeout-ms
  "Default bound for await-async-batch (30 seconds)."
  30000)

;; =============================================================================
;; Batch Registry (in-process state for future lifecycle management)
;; =============================================================================
;;
;; Futures cannot be serialized into context-store, so the registry holds
;; the future reference + metadata locally. Completed results are stored
;; in context-store for cross-agent access and TTL-based auto-eviction.

;; gc-fix-3: batches — LRU eviction, 100 entries, 30min TTL
;; Batch entries include future refs; stale completed batches waste memory.
(defonce ^{:doc "Registry of async batches.
  {batch-id -> {:future, :status, :ops, :created-at, :result-ctx-id, :error}}"}
  batches
  (bounded-atom {:max-entries 100
                 :ttl-ms 1800000    ;; 30 minutes
                 :eviction-policy :lru}))
(register-sweepable! batches :batches)

;; =============================================================================
;; ID Generation
;; =============================================================================

(defn- generate-batch-id
  "Generate a unique batch ID: batch-<uuid>."
  []
  (str "batch-" (java.util.UUID/randomUUID)))

;; =============================================================================
;; Lazy Resolution (avoid circular deps with tools.multi)
;; =============================================================================

(defn- resolve-run-multi
  "Lazily resolve hive-mcp.tools.multi/run-multi.
   Returns the function or nil if unavailable."
  []
  (try
    (requiring-resolve 'hive-mcp.tools.multi/run-multi)
    (catch Exception e
      (log/error {:event :async-resolve-error :error (ex-message e)})
      nil)))

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- now-ms
  "Current epoch milliseconds."
  []
  (System/currentTimeMillis))

(defn- make-batch-entry
  "Build a batch registry entry for a newly dispatched batch."
  [fut ops-count created-at]
  {:future        fut
   :status        "running"
   :ops           ops-count
   :created-at    created-at
   :completed-at  nil
   :result-ctx-id nil
   :error         nil})

(defn- transition-batch!
  "Atomically merge `updates` into the registry entry for `batch-id`, but only
   while its :status is one of `from-statuses`.
   Returns true when the transition was applied, false when the batch is absent
   or already left `from-statuses`."
  [batch-id from-statuses updates]
  (let [applied (volatile! false)]
    (bounded-swap! batches
                   (fn [m]
                     (vreset! applied false)
                     (if (contains? from-statuses (get-in m [batch-id :data :status]))
                       (do (vreset! applied true)
                           (update-in m [batch-id :data] merge updates))
                       m)))
    @applied))

(defn- complete-batch!
  "Mark a batch as completed and store result in context-store.
   Called from within the batch future on successful completion.
   The status write applies only while the batch is still \"running\", so a
   concurrent cancel is never overwritten."
  [batch-id batch-result ttl-ms]
  (result/try-effect* :multi-async/store-failed
                      (let [ctx-id (ctx/context-put! batch-result
                                                     :tags #{"async-batch" (str "batch:" batch-id)}
                                                     :ttl-ms ttl-ms)]
                        (transition-batch! batch-id #{"running"}
                                           {:status        "completed"
                                            :result-ctx-id ctx-id
                                            :completed-at  (now-ms)})
                        (log/info "[multi-async] Batch completed" batch-id "ctx-id:" ctx-id)
                        ctx-id)))

(defn- fail-batch!
  "Mark a batch as failed with error details.
   Called from within the batch future on exception. The status write applies
   only while the batch is still \"running\" — an already cancelled or completed
   batch keeps its terminal status."
  [batch-id error-msg]
  (when (transition-batch! batch-id #{"running"}
                           {:status       "failed"
                            :error        error-msg
                            :completed-at (now-ms)})
    (log/error "[multi-async] Batch failed" batch-id "error:" error-msg)
    true))

;; =============================================================================
;; Public API: Dispatch
;; =============================================================================

(defn run-multi-async
  "Execute a vector of cross-tool operations asynchronously.
   Wraps tools.multi/run-multi in a future, stores result in context-store.

   The batch is registered before its future is allowed to run, so a batch that
   finishes faster than the dispatching thread can never lose its terminal
   status.

   Returns immediately with batch descriptor:
   {:batch-id   \"batch-<uuid>\"
    :status     \"running\"
    :ops        N
    :created-at <epoch-ms>}

   On engine resolution failure, returns Result error:
   {:error :multi-async/engine-unavailable ...}

   Options:
   - :ttl-ms    — TTL for result in context-store (default: 600000 = 10 min)
   - :dry-run   — validate and plan only, don't execute"
  [ops & {:keys [ttl-ms dry-run] :or {ttl-ms default-result-ttl-ms}}]
  (if-let [run-multi-fn (resolve-run-multi)]
    (let [batch-id   (generate-batch-id)
          created-at (now-ms)
          ops-count  (count ops)
          registered (promise)
          fut        (future
                       ;; Registration gate — no status transition may be
                       ;; attempted before the entry exists in the registry.
                       @registered
                       (try
                         (let [r (if dry-run
                                   (run-multi-fn ops :dry-run true)
                                   (run-multi-fn ops))]
                           (complete-batch! batch-id r ttl-ms)
                           r)
                         (catch Exception e
                           (fail-batch! batch-id (ex-message e))
                           nil)))]
      (try
        (bput! batches batch-id (make-batch-entry fut ops-count created-at))
        (finally
          ;; Always open the gate — a registry write that blew up must not
          ;; strand the batch thread on the promise.
          (deliver registered true)))
      (log/info "[multi-async] Batch dispatched" batch-id "ops:" ops-count)
      {:batch-id   batch-id
       :status     "running"
       :ops        ops-count
       :created-at created-at})
    ;; Engine not available
    (result/err :multi-async/engine-unavailable
                {:message "Batch engine not available (hive-mcp.tools.multi/run-multi not resolved)"})))

;; =============================================================================
;; Public API: Collect
;; =============================================================================

(defn collect-async-result
  "Retrieve the result of an async batch by batch-id.

   Returns one of:
   {:status \"running\"   :batch-id ... :ops N :elapsed-ms M}
   {:status \"completed\" :batch-id ... :results <run-multi-output>}
   {:status \"failed\"    :batch-id ... :error \"...\"}
   {:status \"cancelled\" :batch-id ...}
   {:status \"expired\"   :batch-id ... :message \"...\"}
   {:status \"not-found\" :batch-id ...}"
  [batch-id]
  (if-let [entry (bget batches batch-id)]
    (case (:status entry)
      "running"
      {:status     "running"
       :batch-id   batch-id
       :ops        (:ops entry)
       :created-at (:created-at entry)
       :elapsed-ms (- (now-ms) (:created-at entry))}

      "completed"
      (if-let [ctx-entry (ctx/context-get (:result-ctx-id entry))]
        {:status   "completed"
         :batch-id batch-id
         :results  (:data ctx-entry)}
        {:status  "expired"
         :batch-id batch-id
         :message "Result TTL exceeded, data evicted from context-store"})

      "failed"
      {:status   "failed"
       :batch-id batch-id
       :error    (:error entry)}

      "cancelled"
      {:status   "cancelled"
       :batch-id batch-id}

      ;; Unknown status fallback
      {:status     "unknown"
       :batch-id   batch-id
       :raw-status (:status entry)})

    ;; Batch-id not in registry
    {:status "not-found" :batch-id batch-id}))

(defn await-async-batch
  "Block until an async batch leaves the \"running\" state, then collect it.

   Waits on the batch future itself — every terminal status write happens inside
   the future body — so callers need no polling or sleeping to observe a settled
   batch.

   Returns the same map as collect-async-result: on timeout that is the still
   \"running\" descriptor; for an unknown batch-id, {:status \"not-found\" ...}.

   Options:
   - :timeout-ms — maximum wait in ms (default: 30000)"
  [batch-id & {:keys [timeout-ms] :or {timeout-ms default-await-timeout-ms}}]
  (when-let [fut (:future (bget batches batch-id))]
    (try
      (deref fut timeout-ms ::timeout)
      ;; Cancelled/interrupted futures settle their status before throwing.
      (catch Exception _ nil)))
  (collect-async-result batch-id))

;; =============================================================================
;; Public API: List
;; =============================================================================

(defn list-async-batches
  "List all tracked async batches with their status.
   Returns a vector of batch summary maps (without :future refs)."
  []
  (mapv (fn [[batch-id entry]]
          (let [data (:data entry)]
            (cond-> {:batch-id   batch-id
                     :status     (:status data)
                     :ops        (:ops data)
                     :created-at (:created-at data)}
              (:completed-at data)        (assoc :completed-at (:completed-at data))
              (= "running" (:status data)) (assoc :elapsed-ms (- (now-ms) (:created-at data)))
              (:error data)               (assoc :error (:error data)))))
        @(:atom batches)))

;; =============================================================================
;; Public API: Cancel
;; =============================================================================

(defn cancel-async-batch
  "Cancel a running async batch via future-cancel.

   The registry is moved to \"cancelled\" BEFORE the future is interrupted, so
   the interrupt handler inside the future observes a terminal status and
   cannot overwrite it with \"failed\".

   Returns:
   {:cancelled true  :batch-id ...}              — successfully cancelled
   {:cancelled false :batch-id ... :reason \"not-running\"} — terminal state
   {:cancelled false :batch-id ... :reason \"not-found\"}   — invalid id"
  [batch-id]
  (if-let [entry (bget batches batch-id)]
    (if (transition-batch! batch-id #{"running"}
                           {:status       "cancelled"
                            :completed-at (now-ms)})
      (do
        (when-let [fut (:future entry)]
          (future-cancel fut))
        (log/info "[multi-async] Batch cancelled" batch-id)
        {:cancelled true :batch-id batch-id})
      {:cancelled false :batch-id batch-id :reason "not-running"})
    {:cancelled false :batch-id batch-id :reason "not-found"}))

;; =============================================================================
;; Lifecycle: Cleanup & GC
;; =============================================================================

(defn gc-completed!
  "Remove terminal batches (completed/failed/cancelled) whose results have
   expired from context-store. Returns count of entries purged from registry."
  []
  (let [terminal #{"completed" "failed" "cancelled"}
        raw-entries @(:atom batches)
        to-purge (filterv (fn [[_id entry]]
                            (let [data (:data entry)]
                              (and (terminal (:status data))
                                   (if-let [ctx-id (:result-ctx-id data)]
                                     (nil? (ctx/context-get ctx-id))
                                     true))))
                          raw-entries)
        ids      (mapv first to-purge)]
    (when (seq ids)
      (bounded-swap! batches #(apply dissoc % ids))
      (log/info "[multi-async] GC purged" (count ids) "terminal batches"))
    (count ids)))

(defn reset-all!
  "Cancel all in-flight futures and clear batch registry. For testing.
   Marks each batch cancelled before interrupting it, so a deliberate shutdown
   is never reported as a batch failure."
  []
  (doseq [[batch-id entry] @(:atom batches)]
    (let [data (:data entry)
          fut (:future data)]
      (when (and fut (not (future-done? fut)))
        (transition-batch! batch-id #{"running"} {:status       "cancelled"
                                                  :completed-at (now-ms)})
        (future-cancel fut)
        (try (deref fut 100 nil) (catch Exception _)))))
  (bclear! batches))
