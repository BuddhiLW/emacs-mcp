(ns hive-mcp.tools.catchup.sessions-freshness-regression-test
  "RED regression test for the catchup-sessions freshness bug.

   BUG (pre-fix): `workflow catchup` returns only ~4 session-summary notes
   despite 21+ existing in the store across sibling projects.

   ROOT CAUSE: `bundle/query-all-scoped` fans out to descendants via
   `hier/chunked-hierarchy-fetch`, which calls `mem-proto/query-entries` with
   `{:project-ids [pid] :limit 50}` per descendant — and Milvus's default
   ordering is NOT `created DESC`. So when a descendant has more than 50
   entries, the 50 returned are an arbitrary cut (insertion-order on most
   backends) that frequently OMITS the freshest session-summaries when older
   static entries (decisions, conventions, plain notes) were inserted earlier.
   Once the metadata-projection scan misses those session-summaries, no amount
   of post-fetch filtering in `split-by-type` can resurrect them.

   SEEDING STRATEGY (mirrors the production failure mode):
     - 5 sibling projects under parent `hive`.
     - Per project: ~10 OLDER decoys (decision / convention / plain note),
       inserted FIRST so they occupy the front of the insertion-order slice.
     - Per project: 5 fresher session-summary notes (timestamps spread one
       per minute over the most recent 25 minutes), inserted AFTER decoys.
     - Total: 25 session-summaries across 5 descendants. With the buggy
       per-descendant `:limit 50` taking the first 50 by insertion order,
       fresh session-summaries get evicted by older decoys.

   FIX (sister-agent in bundle.clj + hierarchy.clj):
     - Dedicated `:sessions-fresh` branch in `query-all-scoped` that targets
       `type=note` + `tag=session-summary` directly (bypassing the
       per-descendant 50-cap fairness fan-out).
     - Bump `split-by-type` :sessions cap 10 -> 25.

   PRECONDITION: This test runs against a reified IMemoryStore — no Milvus,
   no Chroma, no network. Deterministic and CI-safe.

   PRECEDENT: `query_axioms_regression_test.clj` — same bug shape (a real
   query path silently truncating cross-scope entries), same test shape
   (reified store + tightly controlled seed + explicit assertions on what
   the bundle returns)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.tools.catchup.bundle :as bundle]))

;; =============================================================================
;; Test scope topology — `hive` parent with 5 sibling descendants
;; =============================================================================

(def ^:private parent-pid "hive")

(def ^:private descendant-pids
  ["hive-agent" "hive-milvus" "hive-mcp" "hive-knowledge" "hive-events"])

(defn- scope-tag [pid] (str "scope:project:" pid))

;; =============================================================================
;; Reified IMemoryStore — preserves INSERTION ORDER on query (mirrors the
;; Milvus default-ordering bug). Crucial: applying `take limit` BEFORE any
;; created-desc sort is what makes the bug reproducible.
;; =============================================================================

(defn- make-insertion-order-store
  "Reified store that returns matches in the order they were added.
   Critically does NOT sort by :created — that's what causes the bug.
   `entries-atom` is the shared seed vector."
  [entries-atom]
  (reify mem-proto/IMemoryStore
    (connect! [_ _] nil)
    (disconnect! [_] nil)
    (connected? [_] true)
    (health-check [_] {:healthy? true})
    (add-entry! [_ _] nil)
    (get-entry [_ id] (first (filter #(= id (:id %)) @entries-atom)))
    (update-entry! [_ _ _] nil)
    (delete-entry! [_ _] true)
    (query-entries [_ opts]
      (let [{:keys [type tags project-id project-ids limit]} opts]
        (->> @entries-atom
             (filter (fn [e]
                       (and (or (nil? type) (= type (:type e)))
                            (or (nil? project-id)
                                (= project-id (:project-id e)))
                            (or (nil? project-ids)
                                (some #{(:project-id e)} project-ids))
                            (or (nil? tags)
                                (every? (set (:tags e)) tags)))))
             (take (or limit 100))
             vec)))
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] 0)
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status [_] {:backend "insertion-order-mock"})
    (reset-store! [_] (reset! entries-atom []))))

;; =============================================================================
;; Seed builders
;; =============================================================================

(defn- iso-instant
  "ISO-8601 string for `now - n` minutes. Deterministic ordering by argument."
  [minutes-ago]
  (.toString (.minusSeconds (java.time.Instant/now) (* 60 minutes-ago))))

(defn- decoy-entries
  "OLD non-session-summary entries for `pid`. Mixed types (note without
   the session-summary tag, decision, convention) so the per-descendant
   slice contains plausible distractors. Timestamps are 60-200 minutes ago."
  [pid]
  (let [stag (scope-tag pid)]
    (vec
      (for [i (range 10)
            :let [type (nth ["note" "decision" "convention" "decision" "convention"
                             "note" "decision" "convention" "decision" "convention"]
                            i)
                  ;; OLDER than any session-summary (>= 60 min ago)
                  ts   (iso-instant (+ 60 (* i 14)))]]
        {:id         (str "decoy-" pid "-" i)
         :type       type
         :project-id pid
         :tags       [stag "decoy"]
         :content    (str "decoy " i " for " pid)
         :created    ts}))))

(defn- session-summary-entries
  "5 fresh session-summary notes for `pid`. Timestamps slot 0..24 minutes ago,
   `idx-base` controlling the slot so all 25 globally form a strict total
   order when sorted by :created desc."
  [pid idx-base]
  (let [stag (scope-tag pid)]
    (vec
      (for [i (range 5)
            :let [slot (+ idx-base i)
                  ts   (iso-instant (- 24 slot))]]
        {:id         (str "ss-" pid "-" i)
         :type       "note"
         :project-id pid
         :tags       [stag "session-summary"]
         :content    (str "Session Summary " i " for " pid)
         :created    ts}))))

(defn- build-seed
  "Build the full seeded entry vector. Decoys for ALL projects come FIRST
   (so they occupy the head of any insertion-order slice), session-summaries
   appended last."
  []
  (let [decoys    (vec (mapcat decoy-entries descendant-pids))
        summaries (vec (mapcat (fn [idx pid]
                                 (session-summary-entries pid (* idx 5)))
                               (range)
                               descendant-pids))]
    (into [] (concat decoys summaries))))

;; =============================================================================
;; Fixture: install scope topology + reified store as the active store
;; =============================================================================

(def ^:private entries-state (atom []))

(defn- with-scope-and-store [t]
  (reset! entries-state (build-seed))
  (let [store (make-insertion-order-store entries-state)
        scope-tags (into #{(scope-tag parent-pid) "scope:global"}
                         (map scope-tag descendant-pids))]
    (with-redefs [mem-proto/store-set?         (constantly true)
                  mem-proto/get-store          (constantly store)
                  ;; Mock kg-scope so `hive` has 5 descendants and visible-scopes
                  ;; mirrors a parent-with-children topology. No filesystem,
                  ;; no project.tree resolution.
                  kg-scope/visible-scopes      (fn [pid]
                                                 (if (= pid parent-pid)
                                                   [parent-pid "global"]
                                                   [pid parent-pid "global"]))
                  kg-scope/descendant-scopes   (fn [pid]
                                                 (if (= pid parent-pid)
                                                   descendant-pids
                                                   []))
                  kg-scope/full-hierarchy-scope-tags (fn [_pid] scope-tags)]
      (t))))

(use-fixtures :each with-scope-and-store)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- session-summary? [entry]
  (contains? (set (:tags entry)) "session-summary"))

(defn- created-desc?
  "True when the seq is sorted newest-first by :created (string compare)."
  [entries]
  (every? (fn [[a b]] (>= (compare (:created a) (:created b)) 0))
          (partition 2 1 entries)))

;; =============================================================================
;; RED — Bundle must surface ≥20 of the 25 seeded session-summaries
;; =============================================================================

(deftest ^:regression catchup-bundle-returns-fresh-session-summaries-test
  (testing "query-catchup-bundle :sessions yields ≥20 session-summaries"
    ;; Pre-condition guard: confirm the seed actually contains 25 SS entries.
    (let [seeded-ss (filter session-summary? @entries-state)]
      (is (= 25 (count seeded-ss))
          "seed sanity — exactly 25 session-summaries across 5 descendants"))

    (let [bundle   (bundle/query-catchup-bundle parent-pid)
          sessions (:sessions bundle)]
      (is (some? bundle) "query-catchup-bundle returned a bundle")
      (is (vector? sessions)
          ":sessions should be a vector of trimmed entries")
      ;; PRIMARY assertion — the freshness fix delivers ≥20/25.
      ;; Pre-fix (per-descendant 50-cap on unsorted Milvus output evicts
      ;; freshest session-summaries) this lands well below 20.
      (is (>= (count sessions) 20)
          (str "expected ≥20 session-summaries in bundle :sessions, got "
               (count sessions)
               " — fresh session-summaries are being crowded out by the "
               "per-descendant fairness cap before split-by-type runs")))))

;; =============================================================================
;; RED — Newest-first ordering invariant
;; =============================================================================

(deftest ^:regression catchup-bundle-sessions-are-newest-first-test
  (testing ":sessions entries are sorted newest-first by :created"
    (let [bundle   (bundle/query-catchup-bundle parent-pid)
          sessions (:sessions bundle)]
      (is (seq sessions) ":sessions must be non-empty for ordering to mean anything")
      (is (created-desc? sessions)
          (str ":sessions must be in created-desc order; got "
               (mapv :created sessions))))))

;; =============================================================================
;; RED — Tag invariant: every returned :sessions entry IS a session-summary
;; =============================================================================

(deftest ^:regression catchup-bundle-sessions-tag-invariant-test
  (testing "every :sessions entry has the session-summary tag"
    (let [bundle   (bundle/query-catchup-bundle parent-pid)
          sessions (:sessions bundle)]
      (is (seq sessions) ":sessions must be non-empty for tag check to mean anything")
      (is (every? session-summary? sessions)
          (str "non-session-summary entries leaked into :sessions: "
               (->> sessions
                    (remove session-summary?)
                    (mapv (juxt :id :type :tags))))))))
