(ns hive-mcp.tools.memory.crud.query
  "Query operations for memory with filtered retrieval and scope hierarchy."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.core :refer [mcp-json mcp-error coerce-int! coerce-vec!]]
            [hive-mcp.memory.domain :as domain]
            [hive-dsl.adt :refer [adt-case]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.agent.context :as ctx]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- apply-tag-filter
  "Filter entries by required tags."
  [tags entries]
  (if (seq tags)
    (filter (fn [entry]
              (let [entry-tags (set (:tags entry))]
                (every? #(contains? entry-tags %) tags)))
            entries)
    entries))

(defn- apply-duration-filter
  "Filter entries by duration."
  [duration entries]
  (if duration
    (filter #(= (:duration %) duration) entries)
    entries))

(defn entry-after?
  "True iff the entry timestamp under `k` (:created or :updated) is strictly
   greater than `threshold` (ISO-8601 lexicographic compare, same contract as
   kanban filters/entry-after-ts?). Nil threshold matches every entry; a
   non-nil threshold with no entry timestamp excludes the entry."
  [entry k threshold]
  (or (nil? threshold)
      (let [ts (get entry k)]
        (boolean (and ts (pos? (compare (str ts) (str threshold))))))))

(defn- record-batch-co-access!
  "Record co-access pattern for batch query results (non-blocking)."
  [result-ids scope]
  (when (>= (count result-ids) 2)
    (future
      (try
        (kg-edges/record-co-access!
         result-ids
         {:scope scope :created-by "system:batch-recall"})
        (catch Exception e
          (log/debug "Co-access recording failed (non-fatal):" (.getMessage e)))))))

(defn apply-auto-scope-filter
  "Filter entries for auto-scope mode using hierarchical scope resolution."
  ([entries project-id]
   (apply-auto-scope-filter entries project-id false))
  ([entries project-id include-descendants?]
   (let [in-project? (and project-id (not= project-id "global"))
         scope-tags (cond-> (if include-descendants?
                              (kg-scope/full-hierarchy-scope-tags project-id)
                              (kg-scope/visible-scope-tags project-id))
                      in-project? (disj "scope:global"))
         visible-ids (cond-> (set (if include-descendants?
                                    (into (vec (kg-scope/visible-scopes project-id))
                                          (kg-scope/descendant-scopes project-id))
                                    (kg-scope/visible-scopes project-id)))
                       in-project? (disj "global"))]
     (filter (fn [entry]
               (let [tags (set (or (:tags entry) []))]
                 (or
                  (some tags scope-tags)
                  (contains? visible-ids (:project-id entry)))))
             entries))))

(defn- resolve-project-ids-for-db
  "Compute visible project-ids for DB-level filtering.
   Dispatches via ScopeFilter ADT."
  [sf include-descendants?]
  (domain/scope->project-ids sf include-descendants?))

(defn- fetch-entries
  "Fetch entries from the IMemoryStore with over-fetch factor.

   Temporal thresholds are pushed INTO the store query (stores that predate
   them ignore unknown opts and degrade to client-side filtering); `:order-by`
   asks for newest-first. Milvus scalar query cannot sort server-side, so a
   temporal query ALSO widens the fetch window (min 200, cap 500) — otherwise
   per-collection limit truncation favors the oldest qualifying rows and the
   client-side sort never sees the newest. Qualifying sets beyond the cap
   need a narrower threshold.

   Wraps the IMemoryStore `query-entries` call in `with-resilience` so a
   dropped Milvus HTTP transport is recovered via the heal loop and the
   call retries once before surfacing an error."
  [type project-ids-for-db tags limit-val include-descendants?
   & {:keys [exclude-tags created-after updated-after]}]
  (let [over-fetch-factor (if include-descendants? 4 3)
        base-limit        (* limit-val over-fetch-factor)
        fetch-limit       (if (or created-after updated-after)
                            (min 500 (max 200 base-limit))
                            base-limit)]
    (with-resilience
      (mem-proto/query-entries (mem-proto/get-store)
                               {:type type
                                :project-ids project-ids-for-db
                                :tags tags
                                :exclude-tags exclude-tags
                                :created-after created-after
                                :updated-after updated-after
                                :order-by [:created :desc]
                                :limit fetch-limit}))))

(defn- apply-scope-filter
  "Apply in-memory scope filter as safety net.
   Dispatches via ScopeFilter ADT."
  [entries sf include-descendants?]
  (adt-case domain/ScopeFilter sf
            :scope/all     entries
            :scope/global  (let [scope-filter #{"scope:global"}]
                             (filter #(scope/matches-hierarchy-scopes? % scope-filter) entries))
            :scope/project (let [scope-filter (if include-descendants?
                                                (kg-scope/full-hierarchy-scope-tags (:project-id sf))
                                                (scope/derive-hierarchy-scope-filter sf))]
                             (if scope-filter
                               (filter #(scope/matches-hierarchy-scopes? % scope-filter) entries)
                               entries))
            :scope/auto    (apply-auto-scope-filter entries (:project-id sf) include-descendants?)))

(defn- apply-post-filters
  "Chain tag, duration, and temporal filters on scope-filtered entries,
   sort newest-first by :created, then cap at limit."
  [entries {:keys [tags duration created-after updated-after limit-val]}]
  (->> entries
       (apply-tag-filter tags)
       (apply-duration-filter duration)
       (filter #(entry-after? % :created created-after))
       (filter #(entry-after? % :updated updated-after))
       (sort-by (comp str :created) #(compare %2 %1))
       (take limit-val)))

(defn- format-query-results
  "Format results and record co-access pattern asynchronously."
  [results project-id metadata-only?]
  (record-batch-co-access! (mapv :id results) project-id)
  (if metadata-only?
    (mcp-json (mapv fmt/entry->metadata results))
    (mcp-json (mapv fmt/entry->json-alist results))))

(defn- tag-encodes-scope?
  "True when a tag string explicitly anchors a scope (e.g. \"scope:project:topic:X\")."
  [tag]
  (and (string? tag)
       (clojure.string/starts-with? tag "scope:project:")))

(defn- resolve-scope
  "Honor a caller-supplied :tags scope-predicate by skipping pwd auto-derivation.

   When `:scope` is unspecified AND any tag in `tags` encodes an explicit scope
   (e.g. `scope:project:topic:data-intensive`), the caller has already pinned
   the scope structurally — auto-deriving a competing pwd scope would silently
   AND-filter to zero results. In that case, fall through to `\"all\"` so the
   tag predicate becomes the sole scope filter.

   Otherwise pass scope through unchanged (nil → auto-derive via parse-scope)."
  [scope tags]
  (cond
    (some? scope)                       scope
    (some tag-encodes-scope? tags)      "all"
    :else                                nil))

(defn- unconstrained?
  "True when NOTHING narrows the result set: no type, no tags, no exclude-tags,
   no scope, no duration, no temporal threshold.

   Such a query has no predicate at all — the backend filter collapses to
   project-scope + not-expired and the `limit` default silently caps it at 20
   arbitrary rows. That is a noop default masquerading as an answer, so it is
   rejected rather than served. Any single narrowing predicate is enough to keep
   type-less browsing legal (tag-only, scope-only, and since-timestamp queries
   are supported paths)."
  [type tags exclude-tags scope duration created-after updated-after]
  (and (nil? type)
       (empty? tags)
       (empty? exclude-tags)
       (nil? scope)
       (nil? duration)
       (nil? created-after)
       (nil? updated-after)))

(defn handle-query
  "Query project memory by type with scope, temporal, and verbosity filtering.
   `created_after` / `updated_after` are ISO-8601 strings — entries whose
   timestamp is strictly greater survive (pushed down to the store filter
   AND re-checked client-side). Results sort newest-first."
  [{:keys [type tags exclude_tags limit duration scope directory include_descendants verbosity query
           created_after updated_after]}]
  (let [directory (or directory (ctx/current-directory))
        include-descendants? (if (some? include_descendants)
                               (boolean include_descendants)
                               true)
        metadata-only? (not= verbosity "full")
        tags         (coerce-vec! tags :tags [])
        exclude-tags (coerce-vec! exclude_tags :exclude_tags [])
        raw-scope    scope
        scope        (resolve-scope scope tags)]
    (log/info "mcp-memory-query:" type "scope:" scope "directory:" directory
              "include_descendants:" include-descendants? "verbosity:" (or verbosity "metadata"))
    (try
      (cond
        (some? query)
        (mcp-error (str "memory query is structured filtering and does not accept :query text. "
                        "Use command=search with :query for semantic retrieval."))

        (unconstrained? type tags exclude-tags raw-scope duration created_after updated_after)
        (mcp-error (str "memory query: no filter given. Provide :type "
                        "(or narrow with :tags / :exclude_tags / :scope / :duration / "
                        ":created_after / :updated_after) — an unfiltered query would "
                        "return an arbitrary slice of memory, not an answer."))

        :else
        (let [limit-val (coerce-int! limit :limit 20)]
          (with-store
            (let [project-id  (scope/get-current-project-id directory)
                  sf          (domain/parse-scope scope project-id)
                  project-ids (resolve-project-ids-for-db sf include-descendants?)
                  entries     (fetch-entries type project-ids tags limit-val include-descendants?
                                             :exclude-tags exclude-tags
                                             :created-after created_after
                                             :updated-after updated_after)
                  filtered    (apply-scope-filter entries sf include-descendants?)
                  results     (apply-post-filters filtered
                                                  {:tags tags
                                                   :duration duration
                                                   :created-after created_after
                                                   :updated-after updated_after
                                                   :limit-val limit-val})]
              (format-query-results results project-id metadata-only?)))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :coercion-error (:type (ex-data e)))
          (mcp-error (.getMessage e))
          (throw e))))))

(defn handle-query-metadata
  "Backward-compatible alias: delegates to handle-query with verbosity=metadata."
  [params]
  (handle-query (assoc params :verbosity "metadata")))