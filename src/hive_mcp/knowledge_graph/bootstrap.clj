(ns hive-mcp.knowledge-graph.bootstrap
  "Bootstrap KG edges from existing memory entries.

   Backfill KG edges for entries that predate the edge inference hook (P0.1).
   Applies the same inference rules as handle-add:
   - Tag overlap ≥ 3 → :refines edge (confidence = overlap/max-tags)
   - Content contains 'supersedes' + references entry ID → :supersedes
   - Content contains 'depends on' + references entry ID → :depends-on
   - Session summaries linked chronologically (N refines N-1)

   Bounded: max 200 entries, max 500 edges per run.
   Idempotent: skips if edge already exists between pair."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-dsl.result :as r]
            [clojure.string :as str]
            [clojure.set :as cset]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const max-entries
  "Maximum entries to process per backfill run."
  200)

(def ^:const max-edges
  "Maximum edges to create per backfill run."
  500)

(def ^:const min-tag-overlap
  "Minimum shared tags to infer a :refines edge."
  3)

;; Tags to exclude from overlap computation (too common, not meaningful)
(def ^:private noise-tags
  #{"scope:global" "scope:project:hive-mcp" "catchup-priority" "permanent"
    "agent:coordinator"})

;; =============================================================================
;; Pure Inference Functions
;; =============================================================================

(defn meaningful-tags
  "Filter out noise tags that are too common to be meaningful for inference.
   Returns a set of meaningful tags."
  [tags]
  (when (seq tags)
    (->> tags
         (remove #(or (str/starts-with? % "scope:")
                      (str/starts-with? % "agent:")
                      (contains? noise-tags %)))
         set)))

(defn tag-overlap-score
  "Compute tag overlap between two entries.
   Returns {:overlap <count> :confidence <float> :shared-tags <set>}
   or nil if overlap < min-tag-overlap."
  [entry-a entry-b]
  (let [tags-a (meaningful-tags (:tags entry-a))
        tags-b (meaningful-tags (:tags entry-b))
        shared (cset/intersection tags-a tags-b)
        overlap (count shared)
        max-tags (max (count tags-a) (count tags-b) 1)]
    (when (>= overlap min-tag-overlap)
      {:overlap overlap
       :confidence (double (/ overlap max-tags))
       :shared-tags shared})))

(def ^:private entry-id-pattern
  "Regex pattern matching memory entry IDs (timestamp-hex format)."
  #"\b(\d{14}-[0-9a-f]{8})\b")

(defn extract-referenced-ids
  "Extract memory entry IDs referenced in content.
   Returns a set of entry ID strings."
  [content]
  (when (and content (not (str/blank? (str content))))
    (->> (re-seq entry-id-pattern (str content))
         (map second)
         set)))

(defn infer-supersedes
  "Check if entry-a's content indicates it supersedes another entry.
   Returns referenced entry IDs that are superseded, or nil."
  [entry]
  (let [content (str (:content entry))]
    (when (re-find #"(?i)\b(supersedes?|replaces?|obsoletes?)\b" content)
      (extract-referenced-ids content))))

(defn infer-depends-on
  "Check if entry-a's content indicates it depends on another entry.
   Returns referenced entry IDs it depends on, or nil."
  [entry]
  (let [content (str (:content entry))]
    (when (re-find #"(?i)\b(depends?\s+on|requires?|builds?\s+on|based\s+on)\b" content)
      (extract-referenced-ids content))))

(defn session-summaries-chronological
  "Sort session summary entries chronologically by created timestamp.
   Returns entries sorted oldest-first."
  [entries]
  (->> entries
       (filter #(= "note" (:type %)))
       (filter #(some (fn [t] (or (= t "session-summary")
                                  (= t "session-wrap")
                                  (str/includes? (str t) "wrap")))
                      (or (:tags %) [])))
       (sort-by :created)))

;; =============================================================================
;; Edge Creation (with dedup)
;; =============================================================================

(defn- edge-exists?
  "Check if an edge already exists between two nodes with given relation."
  [from-id to-id relation]
  (some? (kg-edges/find-edge from-id to-id relation)))

(defn- try-create-edge!
  "Try to create an edge, returning :created or :skipped or :error.
   Idempotent: skips if edge already exists."
  [from-id to-id relation confidence source-type]
  (let [result (r/guard Exception :error
                        (if (edge-exists? from-id to-id relation)
                          :skipped
                          (do
                            (kg-edges/add-edge! {:from from-id
                                                 :to to-id
                                                 :relation relation
                                                 :confidence confidence
                                                 :source-type source-type
                                                 :created-by "system:kg-bootstrap"})
                            :created)))]
    (when (= :error result)
      (when-let [err (::r/error (meta result))]
        (log/debug "Bootstrap edge creation failed:" from-id "->" to-id
                   relation (:message err))))
    result))

;; =============================================================================
;; Backfill Orchestrator
;; =============================================================================

(defn backfill-edges-from-memory!
  "Bootstrap KG edges from existing memory entries.

   Queries all memory entries from Chroma and infers edges based on:
   1. Tag overlap ≥ 3 → :refines edge (confidence = overlap/max-tags)
   2. Content 'supersedes' + entry ID reference → :supersedes
   3. Content 'depends on' + entry ID reference → :depends-on
   4. Session summaries linked chronologically (N refines N-1)

   Options:
     :entry-limit  - Max entries to fetch (default: 200)
     :edge-limit   - Max edges to create (default: 500)
     :project-id   - Filter entries by project (default: all)
     :dry-run?     - If true, compute but don't create edges (default: false)

   Returns:
     {:total-entries  <n>
      :edges-created  <n>
      :edges-skipped  <n>
      :edges-errored  <n>
      :by-relation    {:refines <n> :supersedes <n> :depends-on <n>}
      :session-links  <n>}

   Idempotent: safe to run multiple times.
   Bounded: respects entry-limit and edge-limit."
  [& [{:keys [entry-limit edge-limit project-id dry-run?]
       :or {entry-limit max-entries
            edge-limit  max-edges
            dry-run?    false}}]]
  (log/info "KG Bootstrap: starting backfill"
            (when project-id (str " project:" project-id))
            (when dry-run? " (DRY RUN)"))
  (let [;; Fetch entries from Chroma — get high-value types
        store (mem-proto/get-store)
        all-entries (concat
                     (mem-proto/query-entries store {:type "axiom" :limit entry-limit})
                     (mem-proto/query-entries store {:type "decision" :limit entry-limit})
                     (mem-proto/query-entries store {:type "convention" :limit entry-limit})
                     (mem-proto/query-entries store {:type "note" :limit entry-limit})
                     (mem-proto/query-entries store {:type "snippet" :limit entry-limit}))
        ;; Deduplicate by ID and apply project filter
        entries (->> all-entries
                     (reduce (fn [acc e] (if (contains? acc (:id e)) acc (assoc acc (:id e) e))) {})
                     vals
                     (filter #(or (nil? project-id) (= project-id (:project-id %))))
                     (take entry-limit)
                     vec)
        entry-ids (set (map :id entries))
        _entry-by-id (zipmap (map :id entries) entries)

        ;; Track results
        state (atom {:edges-created 0
                     :edges-skipped 0
                     :edges-errored 0
                     :by-relation {:refines 0 :supersedes 0 :depends-on 0}
                     :session-links 0})

        ;; Helper to create edge and track stats
        create! (fn [from-id to-id relation confidence]
                  (when (< (:edges-created @state) edge-limit)
                    (if dry-run?
                      (do (swap! state update :edges-created inc)
                          (swap! state update-in [:by-relation relation] (fnil inc 0)))
                      (let [result (try-create-edge! from-id to-id relation confidence :inferred)]
                        (case result
                          :created (do (swap! state update :edges-created inc)
                                       (swap! state update-in [:by-relation relation] (fnil inc 0)))
                          :skipped (swap! state update :edges-skipped inc)
                          :error   (swap! state update :edges-errored inc)
                          nil)))))

        ;; --- Phase 1: Tag overlap → :refines ---
        _ (log/info "KG Bootstrap Phase 1: Tag overlap inference on" (count entries) "entries")
        entry-vec (vec entries)
        n (count entry-vec)]

    ;; Phase 1: Pairwise tag overlap (bounded by entry count)
    (doseq [i (range n)
            j (range (inc i) n)
            :while (< (:edges-created @state) edge-limit)]
      (let [a (nth entry-vec i)
            b (nth entry-vec j)]
        (when-let [{:keys [confidence]} (tag-overlap-score a b)]
          (create! (:id a) (:id b) :refines (min confidence 0.8)))))

    ;; --- Phase 2: Content-based supersedes/depends-on ---
    (log/info "KG Bootstrap Phase 2: Content reference inference")
    (doseq [entry entries
            :while (< (:edges-created @state) edge-limit)]
      ;; Check supersedes
      (when-let [superseded-ids (infer-supersedes entry)]
        (doseq [target-id superseded-ids
                :when (contains? entry-ids target-id)
                :while (< (:edges-created @state) edge-limit)]
          (create! (:id entry) target-id :supersedes 0.7)))

      ;; Check depends-on
      (when-let [depended-ids (infer-depends-on entry)]
        (doseq [target-id depended-ids
                :when (contains? entry-ids target-id)
                :while (< (:edges-created @state) edge-limit)]
          (create! (:id entry) target-id :depends-on 0.6))))

    ;; --- Phase 3: Chronological session summary linking ---
    (log/info "KG Bootstrap Phase 3: Chronological session linking")
    (let [sessions (session-summaries-chronological entries)]
      (doseq [[prev curr] (partition 2 1 sessions)
              :while (< (:edges-created @state) edge-limit)]
        (create! (:id curr) (:id prev) :refines 0.5)
        (swap! state update :session-links inc)))

    (let [result (assoc @state :total-entries (count entries))]
      (log/info "KG Bootstrap complete:" result)
      result)))
