(ns hive-mcp.tools.catchup.scope-filter
  "Pure filter/sort helpers for catchup scope reasoning.

   No I/O, no caches — given entries + scope data, return filtered entries.
   Deliberately keyword-only so the caller can pre-compute
   `full-hierarchy-scope-tags` once and share it across branches."
  (:require [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.dns.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn distinct-by
  "Return distinct elements from coll by the value of (f item)."
  [f coll]
  (let [seen (volatile! #{})]
    (filterv (fn [item]
               (let [key (f item)]
                 (if (contains? @seen key)
                   false
                   (do (vswap! seen conj key) true))))
             coll)))

(defn newest-first
  "Sort entries by :created timestamp, newest first."
  [entries]
  (sort-by :created #(compare %2 %1) entries))

(defn filter-by-tags
  "Filter entries to only those containing all specified tags."
  [entries tags]
  (if (seq tags)
    (filter (fn [entry]
              (let [entry-tags (set (:tags entry))]
                (every? #(contains? entry-tags %) tags)))
            entries)
    entries))

(defn compute-full-scope-tags
  "Compute full hierarchy scope tags for in-memory safety-net filtering."
  [project-id]
  (let [in-project? (and project-id (not= project-id "global"))]
    (cond-> (kg-scope/full-hierarchy-scope-tags project-id)
      in-project? (disj "scope:global"))))

(defn scope-filter-entries
  "Scope filter. An entry's `scope:project:*` tag is authoritative: when present
   it must intersect `scope-tags`. Entries with no `scope:project:*` tag fall
   back to `:project-id ∈ visible-ids`."
  [entries scope-tags visible-ids]
  (let [scope-tags (set scope-tags)
        scope-tag? (fn [t] (.startsWith ^String (str t) "scope:project:"))]
    (filter (fn [entry]
              (let [entry-tags (set (or (:tags entry) []))]
                (if (some scope-tag? entry-tags)
                  (boolean (some scope-tags entry-tags))
                  (contains? visible-ids (:project-id entry)))))
            entries)))

(defn scope-filter-entries-strict
  "Strict variant of `scope-filter-entries` — keeps only entries whose
   tags explicitly intersect `scope-tags`. Does NOT fall back to
   `:project-id ∈ visible-ids`.

   Step 10 tripwire (memory `20260504173159-46dc47f1`): apply this to
   wrap/session entries once step-6 guarantees explicit `scope:project:*`
   tags at write-time. Any future writer regression that drops the tag
   will surface as missing-from-catchup rather than as silent bleed via
   the project-id fallback."
  [entries scope-tags]
  (filter (fn [entry]
            (let [entry-tags (set (or (:tags entry) []))]
              (boolean (some entry-tags scope-tags))))
          entries))

(defn scope-pierce-entries
  "Extract axioms, catchup-priority, and session-summary entries that pierce
   scope boundaries. Returns nil when caller is at global scope (no piercing needed)."
  [entries project-id]
  (let [in-project? (and project-id (not= project-id "global"))]
    (when in-project?
      (filter (fn [entry]
                (let [entry-tags (set (or (:tags entry) []))
                      entry-type (str (or (:type entry) ""))]
                  (or (= entry-type "axiom")
                      (contains? entry-tags "catchup-priority")
                      (contains? entry-tags "session-summary"))))
              entries))))

(defn entry-expiring-soon?
  "Check if entry expires within 7 days."
  [entry]
  (when-let [exp (:expires entry)]
    (rescue false
            (let [exp-time (java.time.ZonedDateTime/parse exp)
                  now (java.time.ZonedDateTime/now)
                  week-later (.plusDays now 7)]
              (.isBefore exp-time week-later)))))