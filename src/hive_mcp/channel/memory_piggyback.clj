(ns hive-mcp.channel.memory-piggyback
  "Memory piggyback channel for incremental delivery of axioms and conventions via cursor+budget drain."
  (:require [taoensso.timbre :as log]
            [hive-dsl.bounded-atom :refer [bounded-atom bput! bget bounded-swap!
                                           bclear! register-sweepable!]]
            [hive-dsl.context.identity :as ctx-id]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.channel.drain-rank :as rank]
            [hive-mcp.channel.drain-telemetry :as telemetry]
            [clojure.string :as str]))
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
  "Convert a catchup entry to compact piggyback format.

   :A and :F carry the stored access count and the net helpful-minus-unhelpful
   feedback forward so `drain-rank/score` can read them without a store hit.
   Both are omitted when the source entry has neither."
  [entry]
  (let [access (:access-count entry)
        net (- (or (:helpful-count entry) 0) (or (:unhelpful-count entry) 0))]
    (cond-> {:id (:id entry)
             :T (or (:type entry) "note")
             :C (or (:content entry) (:preview entry) "")}
      (:severity entry) (assoc :S (:severity entry))
      (seq (:tags entry)) (assoc :tags (vec (:tags entry)))
      (and access (pos? access)) (assoc :A access)
      (not (zero? net)) (assoc :F net))))

(defn enqueue!
  "Enqueue entries into the memory piggyback buffer.

   MERGES with any existing buffer for the same key: entries already buffered or
   already delivered this session (matched by :id) are skipped, genuinely new
   entries are appended, and the drain cursor is preserved so nothing is re-sent
   and nothing queued is discarded.

   Supplying context-refs marks them undelivered so the next drain carries them.

   Session-scoped: keyed by caller-id only (no project dimension)."
  ([caller-id entries]
   (enqueue! caller-id entries nil))
  ([caller-id entries context-refs]
   (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))
         existing   (bget buffers buffer-key)
         formatted  (mapv format-entry entries)
         prior      (vec (:entries existing))
         known      (into (set (:sent existing)) (keep :id) prior)
         fresh      (filterv #(not (and (:id %) (contains? known (:id %)))) formatted)
         merged     (into prior fresh)
         cursor     (min (:cursor existing 0) (count merged))
         refs       (or context-refs (:context-refs existing))]
     (bput! buffers buffer-key
            (cond-> {:entries merged
                     :cursor  cursor
                     :done    (empty? (drop cursor merged))
                     :seq-num (:seq-num existing 0)
                     :sent    (set (:sent existing))}
              (some? refs)
              (assoc :context-refs refs
                     :refs-delivered? (if (some? context-refs)
                                        false
                                        (boolean (:refs-delivered? existing))))))
     (log/info "memory-piggyback: enqueued" (count fresh) "new of" (count formatted)
               "offered for" buffer-key
               "- buffer now" (count merged) "entries, cursor" cursor
               (when (seq (:sent existing)) (str ", " (count (:sent existing)) " already delivered"))
               (when context-refs (str ", " (count context-refs) " context-refs"))))))

(defn drain!
  "Drain next batch of entries within char budget for a caller session.

   Carries :context-refs forward across batches and attaches them to the first
   response after they are enqueued, marking them delivered.

   On exhaustion the buffer collapses to a content-free tombstone retaining the
   delivered ids under :sent, so a later enqueue! for the same caller can skip
   what this session already received.

   The 2-arity takes a drain ctx. A non-empty (:tokens ctx) selects the batch
   two-lane via drain-rank/select-batch, rewrites the undrained tail of the
   buffer into that order, and ages every offered-but-not-taken entry under
   :offers. ctx :pins and :floor-cap are forwarded to the ranker. ctx nil, or
   ctx without :tokens, is FIFO — identical to the 1-arity in both the returned
   batch and the buffer written back.

   Every drain folds its offered / delivered ids into `drain-telemetry`."
  ([caller-id] (drain! caller-id nil))
  ([caller-id ctx]
   (let [buffer-key (ctx-id/caller-id-key (ctx-id/parse-caller-id caller-id))
         buf (bget buffers buffer-key)]
     (when (and buf (not (:done buf)))
       (let [{:keys [entries cursor seq-num context-refs refs-delivered? sent offers]} buf
             total (count entries)
             tokens (:tokens ctx)
             pending (when (seq tokens)
                       (subvec (vec entries) (min cursor total)))
             ranked (when (seq pending)
                      (rank/select-batch pending {:tokens tokens
                                                  :offers offers
                                                  :pins (:pins ctx)
                                                  :floor-cap (:floor-cap ctx)
                                                  :char-budget drain-char-budget}))
             entries* (if ranked
                        (into (subvec (vec entries) 0 (min cursor total))
                              (:ordered ranked))
                        entries)
             [batch new-cursor]
             (if ranked
               [(:batch ranked) (+ cursor (:taken ranked))]
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
                              (inc idx)))))))
             is-done (>= new-cursor total)
             new-seq (inc seq-num)
             delivered new-cursor
             remaining (- total new-cursor)
             send-refs? (and (some? context-refs) (not refs-delivered?))
             taken-ids (when ranked (into #{} (keep :id) (:batch ranked)))
             next-offers (if ranked
                           (reduce (fn [m e]
                                     (let [eid (:id e)]
                                       (if (and eid (not (contains? taken-ids eid)))
                                         (update m eid (fnil inc 0))
                                         m)))
                                   (or offers {})
                                   pending)
                           offers)]
         (telemetry/record!
          {:seq-num new-seq
           :delivered-ids (into [] (keep :id) batch)
           :offered-ids (when ranked
                          (into [] (comp (keep :id) (remove taken-ids)) pending))})
         (bput! buffers buffer-key
                (cond-> (if is-done
                          {:entries []
                           :cursor  0
                           :done    true
                           :seq-num new-seq
                           :sent    (into (set sent) (keep :id) entries*)}
                          {:entries entries*
                           :cursor  new-cursor
                           :done    false
                           :seq-num new-seq
                           :sent    (set sent)})
                  (some? context-refs)
                  (assoc :context-refs context-refs
                         :refs-delivered? (or (boolean refs-delivered?) send-refs?))

                  (some? next-offers)
                  (assoc :offers next-offers)))
         (cond-> {:batch batch
                  :remaining remaining
                  :total total
                  :delivered delivered
                  :seq new-seq}
           is-done (assoc :done true)
           send-refs? (assoc :context-refs context-refs)))))))

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
