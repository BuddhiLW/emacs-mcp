(ns hive-mcp.channel.async-result
  "Async tool result buffer for piggyback delivery with cursor+budget drain."
  (:require [taoensso.timbre :as log]
            [hive-dsl.context.identity :as ctx-id])
  (:import [java.time Instant]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Constants

(def ^:const drain-char-budget "Max chars per drain batch (~8K tokens)." 32000)

(def ^:const ttl-seconds "TTL for async results in seconds." 300)

;; Buffer State

(defonce ^{:doc "Map of caller-id-key -> {:entries [...] :cursor 0} (session-scoped)."}
  buffers
  (atom {}))

;; Internal Helpers

(defn- now-epoch-seconds
  "Current epoch seconds for TTL comparison."
  []
  (.getEpochSecond (Instant/now)))

(defn- content-hash
  "Generate content hash for dedup. Uses Clojure hash for speed."
  [result-map]
  (hash (select-keys result-map [:task-id :tool :status :result])))

(defn- expired?
  "Check if an entry has expired based on TTL."
  [entry now-secs]
  (> (- now-secs (:timestamp entry 0)) ttl-seconds))

;; Garbage Collection

(defn gc-expired!
  "Remove expired entries from all buffers, returns count removed."
  []
  (let [now-secs (now-epoch-seconds)
        removed (atom 0)]
    (swap! buffers
           (fn [bufs]
             (reduce-kv
              (fn [acc buffer-key {:keys [entries cursor] :as buf}]
                (let [live-entries (vec (remove #(expired? % now-secs) entries))
                      removed-count (- (count entries) (count live-entries))
                      ;; Adjust cursor: count how many removed entries were before cursor
                      removed-before-cursor (count (filter
                                                    (fn [idx]
                                                      (expired? (nth entries idx) now-secs))
                                                    (range (min cursor (count entries)))))
                      new-cursor (max 0 (- cursor removed-before-cursor))]
                  (swap! removed + removed-count)
                  (if (empty? live-entries)
                    acc ;; Remove empty buffer entirely
                    (assoc acc buffer-key
                           (assoc buf
                                  :entries live-entries
                                  :cursor new-cursor)))))
              {}
              bufs)))
    (let [total @removed]
      (when (pos? total)
        (log/info "async-result: GC removed" total "expired entries"))
      total)))

;; Public API

(defn enqueue-result!
  "Enqueue a completed async result into the buffer with content-hash dedup.
   Session-scoped: keyed by caller-id only (no project dimension)."
  [caller-id result-map]
  (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))
        entry (assoc result-map
                     :timestamp (now-epoch-seconds)
                     :content-hash (content-hash result-map))]
    (swap! buffers update buffer-key
           (fn [buf]
             (let [buf (or buf {:entries [] :cursor 0})
                   ;; Dedup: check if content-hash already exists
                   existing-hashes (set (map :content-hash (:entries buf)))]
               (if (contains? existing-hashes (:content-hash entry))
                 (do
                   (log/debug "async-result: dedup skip for task" (:task-id result-map))
                   buf)
                 (update buf :entries conj entry)))))
    (log/info "async-result: enqueued result for task" (:task-id result-map)
              "tool:" (:tool result-map) "status:" (:status result-map)
              "buffer:" buffer-key)))

(defn drain!
  "Drain next batch of async results within char budget for a caller session."
  [caller-id]
  (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))
        buf (get @buffers buffer-key)]
    (when (and buf (< (:cursor buf) (count (:entries buf))))
      (let [{:keys [entries cursor]} buf
            total (count entries)
            ;; Collect entries within char budget
            [batch new-cursor]
            (loop [batch []
                   chars 0
                   idx cursor]
              (if (>= idx total)
                [batch idx]
                (let [entry (nth entries idx)
                      ;; Format for output (strip internal fields)
                      output-entry (dissoc entry :timestamp :content-hash)
                      entry-str (pr-str output-entry)
                      entry-chars (count entry-str)
                      new-chars (+ chars entry-chars)]
                  (if (and (seq batch) (> new-chars drain-char-budget))
                    ;; Over budget and we have at least one entry
                    [batch idx]
                    ;; Add entry (always add at least one even if over budget)
                    (recur (conj batch output-entry)
                           new-chars
                           (inc idx))))))
            is-done (>= new-cursor total)
            delivered new-cursor
            remaining (- total new-cursor)]
        ;; Update buffer state
        (if is-done
          ;; All delivered - remove buffer
          (swap! buffers dissoc buffer-key)
          ;; Advance cursor
          (swap! buffers assoc buffer-key
                 (assoc buf :cursor new-cursor)))
        (cond-> {:results batch
                 :remaining remaining
                 :total total
                 :delivered delivered}
          is-done (assoc :done true))))))

(defn has-pending?
  "Check if a caller session has undrained async results."
  [caller-id]
  (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))
        buf (get @buffers buffer-key)]
    (and (some? buf)
         (< (:cursor buf) (count (:entries buf))))))

(defn poll-task
  "Get status of a specific async task by task-id across all buffers."
  [task-id]
  (some (fn [[_buffer-key {:keys [entries]}]]
          (some #(when (= task-id (:task-id %))
                   (dissoc % :timestamp :content-hash))
                entries))
        @buffers))

(defn clear-buffer!
  "Clear buffer for a specific caller session. For testing."
  [caller-id]
  (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))]
    (swap! buffers dissoc buffer-key)))

(defn reset-all!
  "Reset all buffers. For testing."
  []
  (reset! buffers {}))

(defn stats
  "Get buffer statistics. For monitoring/debugging."
  []
  (let [bufs @buffers]
    {:buffer-count (count bufs)
     :total-entries (reduce + 0 (map (comp count :entries val) bufs))
     :pending-entries (reduce + 0
                              (map (fn [[_ {:keys [entries cursor]}]]
                                     (- (count entries) cursor))
                                   bufs))
     :buffers (into {}
                    (map (fn [[k {:keys [entries cursor]}]]
                           [k {:entries (count entries)
                               :cursor cursor
                               :pending (- (count entries) cursor)}])
                         bufs))}))
