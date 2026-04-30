(ns hive-mcp.channel.memory-piggyback
  "Memory piggyback channel for incremental delivery of axioms and conventions via cursor+budget drain."
  (:require [taoensso.timbre :as log]
            [hive-dsl.bounded-atom :refer [bounded-atom bput! bget bounded-swap!
                                           bclear! register-sweepable!]]
            [hive-dsl.context.identity :as ctx-id]
            [hive-mcp.server.guards :as guards]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const drain-char-budget
  "Max chars per drain batch. Kept modest so each tool response
   stays readable — full content absorbed over several calls."
  12000)

;; gc-fix-3: buffers — LRU eviction, 200 entries, 30min TTL
;; Piggyback buffers are ephemeral per-agent state. Orphaned buffers waste memory.
(defonce ^{:doc "Map of caller-id-key -> buffer state (session-scoped)."}
  buffers
  (bounded-atom {:max-entries 200
                 :ttl-ms 1800000    ;; 30 minutes
                 :eviction-policy :lru}))
(register-sweepable! buffers :piggyback-buffers)

(defn- format-entry
  "Convert a catchup entry to compact piggyback format."
  [entry]
  (cond-> {:id (:id entry)
           :T (or (:type entry) "note")
           :C (or (:content entry) (:preview entry) "")}
    (:severity entry) (assoc :S (:severity entry))
    (seq (:tags entry)) (assoc :tags (vec (:tags entry)))))

(defn enqueue!
  "Enqueue entries into the memory piggyback buffer.
   Replaces any existing buffer for the same key (fresh catchup supersedes stale).
   Session-scoped: keyed by caller-id only (no project dimension)."
  ([caller-id entries]
   (enqueue! caller-id entries nil))
  ([caller-id entries context-refs]
   (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))
         existing (bget buffers buffer-key)
         formatted (mapv format-entry entries)]
     (when existing
       (log/info "memory-piggyback: replacing existing buffer for" buffer-key
                 "(had" (- (count (:entries existing)) (:cursor existing 0)) "undrained entries)"))
     (bput! buffers buffer-key
            (cond-> {:entries formatted
                     :cursor 0
                     :done false
                     :seq-num 0}
              (some? context-refs)
              (assoc :context-refs context-refs)))
     (log/info "memory-piggyback: enqueued" (count formatted) "entries for" buffer-key
               (when context-refs (str " with " (count context-refs) " context-refs"))))))

(defn drain!
  "Drain next batch of entries within char budget for a caller session."
  [caller-id]
  (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))
        buf (bget buffers buffer-key)]
    (when (and buf (not (:done buf)))
      (let [{:keys [entries cursor seq-num]} buf
            total (count entries)
            [batch new-cursor]
            (loop [batch []
                   chars 0
                   idx cursor]
              (if (>= idx total)
                [batch idx]
                (let [entry (nth entries idx)
                      entry-str (pr-str entry)
                      entry-chars (count entry-str)
                      new-chars (+ chars entry-chars)]
                  (if (and (seq batch) (> new-chars drain-char-budget))
                    [batch idx]
                    (recur (conj batch entry)
                           new-chars
                           (inc idx))))))
            is-done (>= new-cursor total)
            new-seq (inc seq-num)
            delivered new-cursor
            remaining (- total new-cursor)]
        (if is-done
          (bounded-swap! buffers dissoc buffer-key)
          (bput! buffers buffer-key
                 {:entries entries
                  :cursor new-cursor
                  :done false
                  :seq-num new-seq}))
        (cond-> {:batch batch
                 :remaining remaining
                 :total total
                 :delivered delivered
                 :seq new-seq}
          is-done (assoc :done true)
          (and (= new-seq 1) (some? (:context-refs buf)))
          (assoc :context-refs (:context-refs buf)))))))

(defn has-pending?
  "Check if a caller session has undrained memory entries."
  [caller-id]
  (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))
        buf (bget buffers buffer-key)]
    (and (some? buf) (not (:done buf)))))

(defn clear-buffer!
  "Clear buffer for a specific caller session. For testing."
  [caller-id]
  (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))]
    (bounded-swap! buffers dissoc buffer-key)))

(defn reset-all!
  "Reset all buffers. For testing.

   Guarded by `when-not-coordinator` — no-op when the live coordinator
   is running so test fixtures cannot wipe live piggyback buffers."
  []
  (guards/when-not-coordinator
   "channel.memory-piggyback/reset-all! blocked"
   (bclear! buffers)))

(defn adopt-buffer!
  "Adopt an orphaned buffer from a previous coordinator instance.
   When a coordinator restarts (new instance-id), the old buffer is orphaned.
   This function finds a matching buffer from any coordinator instance
   and re-keys it to the new caller.

   Session-scoped: matches by caller prefix only, no project dimension.

   Returns true if a buffer was adopted, false otherwise."
  [new-caller-id]
  (let [new-key (ctx-id/caller-id-key (ctx-id/parse-caller-id new-caller-id))
        ;; Find orphaned coordinator buffer (any coordinator instance)
        donor (->> @(:atom buffers)
                   (filter (fn [[aid entry]]
                             (let [buf (:data entry)]
                               (and (not= aid new-key)
                                    (clojure.string/starts-with? (str aid) "coordinator:")
                                    (not (:done buf))))))
                   first)]
    (when-let [[donor-key donor-entry] donor]
      (let [donor-buf (:data donor-entry)]
        (bounded-swap! buffers dissoc donor-key)
        (bput! buffers new-key donor-buf)
        (log/info "memory-piggyback: adopted buffer from" donor-key "->" new-key
                  "(" (- (count (:entries donor-buf)) (:cursor donor-buf)) "entries remaining)")
        true))))

(defn evict-orphaned-buffers!
  "Evict coordinator buffers that have no matching active caller.
   Called during catchup to clean up buffers from dead bb-mcp processes.

   Takes an active caller-id (with instance suffix) and evicts
   coordinator buffers whose key doesn't match the active caller prefix.

   Returns count of evicted buffers."
  [active-caller-id]
  (let [orphaned (->> @(:atom buffers)
                      (filter (fn [[aid _entry]]
                                (and (clojure.string/starts-with? (str aid) "coordinator:")
                                     (not (clojure.string/starts-with? (str aid) (str active-caller-id "-"))))))
                      (map first)
                      vec)]
    (when (seq orphaned)
      (bounded-swap! buffers #(apply dissoc % orphaned))
      (log/info "memory-piggyback: evicted" (count orphaned) "orphaned buffers:" orphaned))
    (count orphaned)))
