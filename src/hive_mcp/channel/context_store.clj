(ns hive-mcp.channel.context-store
  "Ephemeral context store for pass-by-reference agent communication with TTL auto-eviction."
  (:require [taoensso.timbre :as log]
            [hive-mcp.server.guards :as guards])
  (:import [java.util.concurrent ConcurrentHashMap ScheduledExecutorService
            Executors TimeUnit]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Constants

(def ^:const default-ttl-ms
  "Default time-to-live for context entries (5 minutes)."
  300000)

(def ^:const reaper-interval-sec
  "Reaper runs every 60 seconds."
  60)

(def ^:const max-entries
  "Maximum number of live entries the store will hold. Enforced at
   `context-put!` time (TTL + reaper still apply independently). Generous on
   purpose: pass-by-reference callers fetch their ctx-id shortly after putting,
   so the cap must never be tight enough to drop a just-put, still-pending
   entry. When at/over cap we reap expired entries first, then evict
   oldest-by-:created-at — newest (just-put) entries are evicted last."
  2000)

;; Store (ConcurrentHashMap)

(defonce ^{:doc "ConcurrentHashMap<String, Map> — the context store."}
  ^ConcurrentHashMap store
  (ConcurrentHashMap.))

(defonce ^{:doc "ScheduledExecutorService for reaper. nil when stopped."}
  reaper-executor
  (atom nil))

;; ID Generation

(defn- generate-ctx-id
  "Generate a unique context ID."
  []
  (let [ts (System/currentTimeMillis)
        hex (format "%08x" (bit-and (hash (random-uuid)) 0xFFFFFFFF))]
    (str "ctx-" ts "-" hex)))

;; Internal Helpers

(defn- now-ms
  "Current epoch milliseconds."
  []
  (System/currentTimeMillis))

(defn- expired?
  "Check if an entry has expired."
  [entry]
  (> (now-ms) (:expires-at entry)))

(declare reap-expired!)

(defn- evict-oldest!
  "Evict oldest-by-:created-at entries until the store is below `max-entries`.
   Assumes expired entries were already reaped by the caller. Entries are pure
   data, so .remove makes them GC-eligible. Returns count evicted."
  []
  (let [evicted (atom 0)
        ;; Snapshot, sorted oldest-first; newest (just-put) entries sort last.
        by-age (sort-by (comp :created-at val) (into {} store))]
    (loop [pairs by-age]
      (when (and (seq pairs) (>= (.size store) max-entries))
        (let [[id _] (first pairs)]
          (when (.remove store id)
            (swap! evicted inc)))
        (recur (rest pairs))))
    (let [n @evicted]
      (when (pos? n)
        (log/info "[context-store] evicted" n "oldest entries (capacity cap" max-entries ")"))
      n)))

(defn- enforce-capacity!
  "Ensure room for one more entry before a put. When the store is at/over
   `max-entries`, reap expired entries first, then — if still at/over cap —
   evict oldest-by-:created-at until under cap. Expired-first + oldest-first
   eviction keeps just-put, still-pending entries safe."
  []
  (when (>= (.size store) max-entries)
    (reap-expired!)
    (when (>= (.size store) max-entries)
      (evict-oldest!))))

;; Public API

(defn context-put!
  "Store data in the context store, returns ctx-id."
  [data & {:keys [tags ttl-ms] :or {tags #{} ttl-ms default-ttl-ms}}]
  (let [id (generate-ctx-id)
        now (now-ms)
        entry {:id id
               :data data
               :tags (set tags)
               :created-at now
               :ttl-ms ttl-ms
               :expires-at (+ now ttl-ms)
               :access-count 0
               :last-accessed nil}]
    (enforce-capacity!)
    (.put store id entry)
    (log/debug "[context-store] put" id "tags:" tags "ttl:" ttl-ms)
    id))

(defn context-put-batch!
  "Store multiple entries in the context store in parallel.
   Accepts a map of {category-keyword {:data d :tags t :ttl-ms ttl}} entries.
   Entries with nil/empty :data are skipped.
   Returns a map of {category-keyword ctx-id} for entries that were stored.

   Example:
     (context-put-batch! {:axioms    {:data axioms    :tags #{\"catchup\" \"axioms\"} :ttl-ms 600000}
                          :decisions {:data decisions :tags #{\"catchup\" \"decisions\"} :ttl-ms 600000}})
     ;; => {:axioms \"ctx-...\", :decisions \"ctx-...\"}"
  [entries-map]
  (let [;; Filter out entries with no data
        live-entries (into {} (filter (fn [[_ v]] (seq (:data v)))) entries-map)
        ;; Fire all puts in parallel via futures
        futures-map (into {}
                          (map (fn [[category {:keys [data tags ttl-ms]}]]
                                 [category (future (context-put! data
                                                                 :tags (or tags #{})
                                                                 :ttl-ms (or ttl-ms default-ttl-ms)))]))
                          live-entries)
        ;; Collect results (deref all futures)
        refs (into {}
                   (keep (fn [[category fut]]
                           (try
                             (let [id (deref fut 5000 nil)]
                               (when id [category id]))
                             (catch Exception e
                               (log/warn "[context-store] batch-put failed for" category ":" (.getMessage e))
                               nil))))
                   futures-map)]
    (when (seq refs)
      (log/debug "[context-store] batch-put stored" (count refs) "entries:" (keys refs)))
    refs))

(defn context-get
  "Retrieve entry by ID, returns nil for expired entries."
  [ctx-id]
  (let [result (volatile! nil)]
    (.computeIfPresent store ctx-id
                       (reify java.util.function.BiFunction
                         (apply [_ _k entry]
                           (if (expired? entry)
                             (do (vreset! result nil)
                                 nil) ;; returning nil removes the key
                             (let [now (now-ms)
                                   updated (-> entry
                                               (update :access-count inc)
                                               (assoc :last-accessed now))]
                               (vreset! result updated)
                               updated)))))
    @result))

(defn context-query
  "Query entries by tags, returns matching non-expired entries."
  [& {:keys [tags limit] :or {limit 100}}]
  (let [query-tags (set tags)
        results (java.util.ArrayList.)]
    (doseq [entry (vals (into {} store))]
      (when (and (not (expired? entry))
                 (every? (:tags entry) query-tags))
        (.add results entry)))
    ;; Sort by created-at descending (newest first), take limit
    (->> (vec results)
         (sort-by :created-at >)
         (take limit)
         vec)))

(defn context-evict!
  "Remove entry by ID. Returns true if entry existed, false otherwise."
  [ctx-id]
  (some? (.remove store ctx-id)))

(defn evict-by-tags!
  "Evict all entries matching any of the given tags, returns count evicted."
  [tags]
  (let [query-tags (set tags)
        evicted (atom 0)]
    (doseq [[id entry] (into {} store)]
      (when (and (not (expired? entry))
                 (some query-tags (:tags entry)))
        (when (.remove store id)
          (swap! evicted inc))))
    (let [n @evicted]
      (when (pos? n)
        (log/info "[context-store] evicted" n "entries by tags:" query-tags))
      n)))

(defn context-stats
  "Return store statistics."
  []
  (let [entries (vec (vals (into {} store)))
        live (remove expired? entries)
        live-vec (vec live)
        timestamps (map :created-at live-vec)]
    {:total (count live-vec)
     :oldest (when (seq timestamps) (apply min timestamps))
     :newest (when (seq timestamps) (apply max timestamps))
     :bytes-approx (reduce + 0 (map #(count (pr-str (:data %))) live-vec))}))

;; Reaper

(defn reap-expired!
  "Remove all expired entries from the store. Returns count removed."
  []
  (let [removed (atom 0)]
    (doseq [[id entry] (into {} store)]
      (when (expired? entry)
        (when (.remove store id)
          (swap! removed inc))))
    (let [n @removed]
      (when (pos? n)
        (log/info "[context-store] reaped" n "expired entries"))
      n)))

(defn start-reaper!
  "Start the background reaper. Idempotent — no-op if already running."
  []
  (when-not @reaper-executor
    (let [executor (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleAtFixedRate executor
                            ^Runnable (fn []
                                        (try
                                          (reap-expired!)
                                          (catch Exception e
                                            (log/error e "[context-store] reaper error"))))
                            reaper-interval-sec
                            reaper-interval-sec
                            TimeUnit/SECONDS)
      (reset! reaper-executor executor)
      (log/info "[context-store] reaper started, interval:" reaper-interval-sec "s"))))

(defn stop-reaper!
  "Stop the background reaper. Idempotent — no-op if not running."
  []
  (when-let [^ScheduledExecutorService executor @reaper-executor]
    (.shutdownNow executor)
    (reset! reaper-executor nil)
    (log/info "[context-store] reaper stopped")))

;; Reset (for testing)

(defn reset-all!
  "Clear all entries and stop reaper. For testing.

   Guarded by `when-not-coordinator` — no-op when the live coordinator
   is running so test fixtures cannot wipe the live context store or
   stop the live reaper."
  []
  (guards/when-not-coordinator
   "channel.context-store/reset-all! blocked"
   (.clear store)
   (stop-reaper!)))
