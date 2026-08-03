(ns hive-mcp.tools.catchup.relevance
  "Pure relevance scoring for catchup piggyback entries.

   Catchup formerly drained every axiom unconditionally (axioms 'pierce scope'),
   which polluted hive-mcp Clojure sessions with axioms tagged
   `windows-ntlm`, `bufferbloat`, `java-memory-model`, `typography` etc. —
   ~30% of context budget on unrelated material.

   This namespace implements the fix: score each axiom against the current
   session context (project keywords + co-loaded entry tags + always-pierce
   markers) and drop axioms below a configurable threshold.

   Pure — no IO, deterministic. Caller owns context construction."
  (:require [clojure.set :as set]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-threshold
  "Drop entries scoring below this. 0.3 admits weak-overlap (1 tag in
   common with project vocabulary); 0.0 retains everything."
  0.3)

;; ---------------------------------------------------------------------------
;; Tag noise filter — strip generic/structural tags before computing overlap
;; so the Jaccard isn't dominated by ubiquitous markers.
;; ---------------------------------------------------------------------------

(def ^:private noise-tag-prefixes
  ["agent:" "scope:" "kg:" "qn:" "ns:" "carto" "kanban" "priority-"])

(def ^:private noise-tag-exact
  #{"axiom" "principle" "convention" "decision" "snippet" "note"
    "todo" "doing" "review" "done" "permanent" "long" "medium" "short"
    "ephemeral" "global"})

(defn- noise-tag?
  "Filter tags that don't carry topic signal — namespacing prefixes,
   memory-shape markers, status/duration words. Anything left after
   this filter is real domain vocabulary."
  [tag]
  (let [s (str tag)]
    (or (contains? noise-tag-exact s)
        (some #(.startsWith ^String s ^String %) noise-tag-prefixes))))

(defn- expand-compound-tokens
  "Split a compound tag into its constituent tokens on -, _ and . so cross
   project sibling matches still register. Tag `hive-knowledge` =>
   #{\"hive-knowledge\" \"hive\" \"knowledge\"}. Single-token tags pass
   through unchanged."
  [tag]
  (let [s (str tag)
        parts (->> (str/split s #"[-_.]+")
                   (remove str/blank?)
                   set)]
    (conj parts s)))

(defn topic-tags
  "Extract topic-bearing tags from a tag collection. Drops noise prefixes
   and meta-markers, then expands compound tags into their tokens so
   loose matches across sibling projects (`hive-knowledge` <-> `hive`)
   still score. Result: a flat string set of domain vocabulary.

   Case is preserved: callers matching against case-folded vocabularies
   (`drain-rank`) pass already-lowercased tags in."
  [tags]
  (->> (or tags [])
       (mapv str)
       (remove noise-tag?)
       (mapcat expand-compound-tokens)
       (into #{})))

;; ---------------------------------------------------------------------------
;; Project keyword derivation — split project-id on '-' so `hive-mcp`
;; matches axioms tagged `hive` OR `mcp` (loose), in addition to the full id.
;; ---------------------------------------------------------------------------

(defn project-keywords
  "Derive a tag-overlap candidate set from project-id.
   `hive-mcp` => #{\"hive-mcp\" \"hive\" \"mcp\"}.
   Single-token projects yield a singleton set."
  [project-id]
  (if (or (nil? project-id) (= "global" project-id))
    #{}
    (let [base #{(str project-id)}
          parts (->> (str/split (str project-id) #"-")
                     (remove str/blank?)
                     (remove #{"hive"})
                     set)]
      (set/union base parts #{(first (str/split (str project-id) #"-"))}))))

;; ---------------------------------------------------------------------------
;; Context vocabulary — the live tag soup from co-loaded conventions /
;; decisions / sessions. Reflects what the project is actually working on
;; right now, beyond the static project-id keywords.
;; ---------------------------------------------------------------------------

(defn context-vocabulary
  "Distinct topic tags across a list of entries (e.g. priority-conventions
   + decisions + recent sessions). Used to give axioms credit for
   matching the project's current vocabulary."
  [entries]
  (->> entries
       (mapcat :tags)
       topic-tags))

(defn build-context
  "Assemble a relevance-scoring context map from catchup inputs.

   - :project-id        current project (string or nil)
   - :project-keywords  derived from project-id
   - :vocabulary        topic tags from co-loaded entries
                        (priority-conventions ∪ decisions ∪ sessions)"
  [{:keys [project-id co-loaded-entries]}]
  {:project-id       project-id
   :project-keywords (project-keywords project-id)
   :vocabulary       (context-vocabulary co-loaded-entries)})

;; ---------------------------------------------------------------------------
;; Scoring
;; ---------------------------------------------------------------------------

(defn axiom-relevance
  "Pure relevance score [0.0..1.0] for an entry against context.

   Always-pierce signals (score 1.0):
   - `catchup-priority` tag — explicit pin from author
   - `scope:project:<current>` tag — author scoped to current project

   Otherwise: topic-tag overlap with project-keywords ∪ vocabulary.
   - 2+ overlap     => 1.0
   - 1 overlap      => 0.5
   - 0 overlap      => 0.0

   Pure: no IO, no state."
  [{:keys [tags] :as _entry}
   {:keys [project-id project-keywords vocabulary] :as _ctx}]
  (let [tag-set       (set (mapv str (or tags [])))
        scope-tag     (when project-id (str "scope:project:" project-id))
        always-pierce (or (contains? tag-set "catchup-priority")
                          (and scope-tag (contains? tag-set scope-tag)))]
    (cond
      always-pierce 1.0

      :else
      (let [topic    (topic-tags tag-set)
            relevant (set/union (or project-keywords #{})
                                (or vocabulary #{}))
            overlap  (count (set/intersection topic relevant))]
        (cond
          (>= overlap 2) 1.0
          (= overlap 1)  0.5
          :else          0.0)))))

(defn filter-by-relevance
  "Drop entries scoring below `threshold` (default `default-threshold`).
   Non-axiom entries pass through unchanged — this filter is targeted
   at axioms, which formerly always pierced scope. Caller decides
   which sub-list to filter."
  ([entries ctx]
   (filter-by-relevance entries ctx default-threshold))
  ([entries ctx threshold]
   (filterv #(>= (axiom-relevance % ctx) threshold) entries)))
