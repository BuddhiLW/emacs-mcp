(ns hive-mcp.tools.migrate.kanban.pure
  "Pure helpers for the kanban cross-store migration. No IO, no side effects.
   Every fn here is data-in/data-out so trifecta tests can exercise the full
   surface without spinning up milvus or qdrant.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Predicates
;; =============================================================================

(defn task-type-kanban?
  "True iff the entry's :content map carries the kanban task-type marker.

   Tolerates both keyword and string keys because content travels through
   JSON-encoded payload at qdrant rest and clojure-printed maps in milvus
   legacy collections."
  [entry]
  (let [c (:content entry)]
    (boolean
     (or (= "kanban" (get c :task-type))
         (= "kanban" (get c "task-type"))))))

(defn full-payload?
  "True iff the entry has a non-empty :content map. A bare {:id ...} stub
   from a half-finished cutover counts as empty."
  [entry]
  (boolean (and entry (map? (:content entry)) (seq (:content entry)))))

;; =============================================================================
;; Outcome classification — single decision point, no IO
;; =============================================================================

(def classifier-outcome-types
  "Closed enum of classifier outcomes (pre-write). `classify-outcome`
   returns one of these. Use cases consume them and may transform
   `:ready-to-write` into a post-write outcome (`:written`, `:would-write`,
   `:failed`)."
  #{:missing-from-source
    :not-task
    :already-full
    :ready-to-write})

(def post-write-outcome-types
  "Outcomes emitted only after the writer runs."
  #{:written :would-write :failed})

(def outcome-types
  "Union of all outcomes that may appear in a tally — classifier results
   plus post-write transformations."
  (into classifier-outcome-types post-write-outcome-types))

(defn classify-outcome
  "Decide what to do for an id given the (already-fetched) entries from
   each store. Pure: callers fetch then classify, never mixed.

   `source-entry`  — entry read from the migration source (milvus :default)
   `target-entry`  — entry read from the migration target (qdrant :kanban)
                     or nil when target lookup found nothing/stub.

   Returns a keyword from `outcome-types`."
  [source-entry target-entry]
  (cond
    (nil? source-entry)              :missing-from-source
    (not (task-type-kanban? source-entry)) :not-task
    (and target-entry
         (task-type-kanban? target-entry)
         (full-payload? target-entry)) :already-full
    :else                            :ready-to-write))

;; =============================================================================
;; Cursor / batch slicing
;; =============================================================================

(defn slice-batch
  "Return [batch new-cursor done?] for `cursor` advancing by `batch-size`
   over `ids` (vector). Never reads past the end. `done?` indicates the
   cursor reached or passed `(count ids)`."
  [ids cursor batch-size]
  (let [total (count ids)
        start (max 0 (min cursor total))
        end   (min (+ start batch-size) total)
        batch (if (< start end) (subvec ids start end) [])
        new-cursor end
        done?     (>= new-cursor total)]
    [batch new-cursor done?]))

;; =============================================================================
;; Tally
;; =============================================================================

(def empty-tally
  "Zero-initialized per-outcome counts so callers can sum into a known shape."
  (zipmap outcome-types (repeat 0)))

(defn tally-outcomes
  "Frequency count of `:outcome` values across an outcomes seq. Always
   returns the full `empty-tally` shape so downstream stat-merging is
   total."
  [outcomes]
  (reduce (fn [acc o]
            (update acc (:outcome o) (fnil inc 0)))
          empty-tally
          outcomes))

(defn merge-tally
  "Sum two tally maps key-by-key. Missing keys default to 0."
  [a b]
  (reduce-kv (fn [m k v] (update m k (fnil + 0) v))
             a
             b))

;; =============================================================================
;; Id deduplication
;; =============================================================================

(defn dedup-sorted-ids
  "Given a seq of seqs of ids, flatten + dedup + sort ascending. Used by
   the milvus adapter after fanning across per-dim collections."
  [id-seqs]
  (->> id-seqs (apply concat) distinct sort vec))
