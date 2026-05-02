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
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))
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

   Wraps the IMemoryStore `query-entries` call in `with-resilience` so a
   dropped Milvus HTTP transport is recovered via the heal loop and the
   call retries once before surfacing an error."
  [type project-ids-for-db tags limit-val include-descendants?
   & {:keys [exclude-tags]}]
  (let [over-fetch-factor (if include-descendants? 4 3)]
    (with-resilience
      (mem-proto/query-entries (mem-proto/get-store)
                               {:type type
                                :project-ids project-ids-for-db
                                :tags tags
                                :exclude-tags exclude-tags
                                :limit (* limit-val over-fetch-factor)}))))

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
  "Chain tag, duration, and limit filters on scope-filtered entries."
  [entries tags duration limit-val]
  (->> entries
       (apply-tag-filter tags)
       (apply-duration-filter duration)
       (take limit-val)))

(defn- format-query-results
  "Format results and record co-access pattern asynchronously."
  [results project-id metadata-only?]
  (record-batch-co-access! (mapv :id results) project-id)
  (if metadata-only?
    (mcp-json (mapv fmt/entry->metadata results))
    (mcp-json (mapv fmt/entry->json-alist results))))

(defn handle-query
  "Query project memory by type with scope and verbosity filtering."
  [{:keys [type tags exclude_tags limit duration scope directory include_descendants verbosity]}]
  (let [directory (or directory (ctx/current-directory))
        include-descendants? (boolean include_descendants)
        metadata-only? (not= verbosity "full")
        tags         (coerce-vec! tags :tags [])
        exclude-tags (coerce-vec! exclude_tags :exclude_tags [])]
    (log/info "mcp-memory-query:" type "scope:" scope "directory:" directory
              "include_descendants:" include-descendants? "verbosity:" (or verbosity "metadata"))
    (try
      (let [limit-val (coerce-int! limit :limit 20)]
        (with-store
          (let [project-id  (scope/get-current-project-id directory)
                sf          (domain/parse-scope scope project-id)
                project-ids (resolve-project-ids-for-db sf include-descendants?)
                entries     (fetch-entries type project-ids tags limit-val include-descendants?
                                           :exclude-tags exclude-tags)
                filtered    (apply-scope-filter entries sf include-descendants?)
                results     (apply-post-filters filtered tags duration limit-val)]
            (format-query-results results project-id metadata-only?))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :coercion-error (:type (ex-data e)))
          (mcp-error (.getMessage e))
          (throw e))))))

(defn handle-query-metadata
  "Backward-compatible alias: delegates to handle-query with verbosity=metadata."
  [params]
  (handle-query (assoc params :verbosity "metadata")))
