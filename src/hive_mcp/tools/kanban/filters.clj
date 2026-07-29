(ns hive-mcp.tools.kanban.filters
  "Pure list-filter predicates for `handle-mem-kanban-list-slim`. Kept
   separate from `hive-mcp.tools.kanban.transitions` so transitions stays
   focused on state-transition derivation; filters here are post-fetch
   shaping over already-materialised entries.

   Token-budget filters: query / tags / created_after / updated_after /
   limit / offset / fields. Composed by `list-slim*` after the store
   query + tag pre-filter, before slim projection."
  (:require [clojure.string :as str]
            [hive-mcp.tools.kanban.transitions :as kt]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn substring-ci?
  "Case-insensitive substring match. Nil-safe."
  [needle haystack]
  (boolean (and needle haystack
                (str/includes? (str/lower-case (str haystack))
                               (str/lower-case (str needle))))))

(defn entry-priority?
  "True iff entry's priority equals `priority`. Nil priority => match all."
  [entry priority]
  (or (nil? priority)
      (= priority (kt/content-val (:content entry) :priority nil))))

(defn entry-matches-query?
  "True iff `q` (case-insensitive substring) appears in entry's title or
   description. Blank/nil query => match all."
  [entry q]
  (or (nil? q) (and (string? q) (str/blank? q))
      (let [content (:content entry)
            title   (kt/content-val content :title "")
            desc    (kt/content-val content :description "")]
        (or (substring-ci? q title)
            (substring-ci? q desc)))))

(defn entry-tags-match?
  "True iff entry's tags satisfy `extra-tags` under `mode`.
   mode = :all (every tag present, AND) | :any (at least one, OR).
   Empty/nil extra-tags => match all."
  [entry extra-tags mode]
  (if (empty? extra-tags)
    true
    (let [entry-tags (set (:tags entry))]
      (case mode
        :any (boolean (some #(contains? entry-tags %) extra-tags))
        ;; default :all
        (every? #(contains? entry-tags %) extra-tags)))))

(defn entry-after-ts?
  "True iff the entry's timestamp for `kind` (:created or :updated) is
   strictly greater than `threshold` (ISO-8601 string compare).
   Nil threshold => match all."
  [entry kind threshold]
  (or (nil? threshold)
      (let [content (:content entry)
            ts (case kind
                 :created (or (kt/content-val content :created nil)
                              (:created entry))
                 :updated (or (:updated entry)
                              (kt/content-val content :updated nil)
                              (kt/content-val content :started nil)
                              (kt/content-val content :completed nil))
                 nil)]
        (boolean (and ts
                      (pos? (compare (str ts) (str threshold))))))))

(defn paginate
  "Skip `offset` then take `limit`. Both optional, both positive numbers
   when provided."
  [coll offset limit]
  (cond->> coll
    (and (number? offset) (pos? offset)) (drop offset)
    (and (number? limit)  (pos? limit))  (take limit)))

(defn project-fields
  "Project a task map down to a subset of fields. `fields` is a seq of
   strings or keywords; nil/empty returns the task untouched."
  [task fields]
  (if (or (nil? fields) (empty? fields))
    task
    (select-keys task (mapv #(if (keyword? %) % (keyword (name %))) fields))))

(defn post-filters?
  "True iff any clojure-side filter (post-store-fetch) is in play. Used
   to bump the store fetch window so narrow matches aren't truncated by
   the default active-task cap."
  [{:keys [query priority created_after updated_after
           tags tag_match offset limit fields]}]
  (boolean (or (and (string? query) (not (str/blank? query)))
               priority
               created_after
               updated_after
               (and (or (= tag_match "any") (= tag_match :any)) (seq tags))
               offset
               limit
               (seq fields))))

(defn narrowing-post-filters?
  "True iff a client-side NARROWING filter is active — query, priority,
   created_after, updated_after, or OR-tags (tag_match=any) — as opposed to
   pagination-only params (offset/limit/fields)."
  [{:keys [query priority created_after updated_after tags tag_match]}]
  (boolean (or (and (string? query) (not (str/blank? query)))
               priority
               created_after
               updated_after
               (and (or (= tag_match "any") (= tag_match :any)) (seq tags)))))