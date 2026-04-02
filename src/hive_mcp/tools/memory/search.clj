(ns hive-mcp.tools.memory.search
  "Semantic search handler for memory operations."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.plan.plans :as plans]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.chroma.search :as chroma-search]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.memory.domain :as domain]
            [hive-mcp.tools.core :refer [coerce-int!]]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.dns.result :as result :refer [rescue]]
            [hive-mcp.agent.context :as ctx]
            [clojure.set]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- extract-title
  "Extract a one-line title from content or document text.
   Skips the 'Type:/Tags:/Content:' metadata prefix in document text."
  [document metadata]
  (let [content (get metadata :content)
        text    (or content document "")]
    (when (seq text)
      (let [;; Skip metadata prefix if present
            clean (if (str/starts-with? text "Type:")
                    (let [idx (str/index-of text "Content:")]
                      (if idx (subs text (+ idx 9)) text))
                    text)
            first-line (first (str/split-lines (str/trim clean)))]
        (when (seq first-line)
          (subs first-line 0 (min 120 (count first-line))))))))

(defn- format-search-result
  "Format a single search result — compact by default.
   Returns id, type, top 5 tags (excluding scope/agent), distance, one-line title."
  [{:keys [id document metadata distance]}]
  (let [raw-tags (when-let [t (get metadata :tags)]
                   (when (not= t "")
                     (str/split t #",")))
        clean-tags (filterv #(not (or (str/starts-with? % "agent:")
                                      (str/starts-with? % "scope:")
                                      (= % "carto")))
                            (or raw-tags []))]
    {:id       id
     :type     (get metadata :type)
     :tags     (vec (take 5 clean-tags))
     :distance distance
     :title    (extract-title document metadata)}))

(defn- matches-scope-filter?
  "Check if a search result matches the scope filter."
  [result scope-filter]
  (if (nil? scope-filter)
    true
    (let [tags-str (get-in result [:metadata :tags] "")
          tags (when (and tags-str (not= tags-str ""))
                 (set (str/split tags-str #",")))
          scope-set (if (set? scope-filter) scope-filter #{scope-filter})]
      (or (some tags scope-set)
          (contains? tags "scope:global")))))

(defn- record-co-access!
  "Fire-and-forget co-access recording for search results."
  [formatted project-id created-by]
  (when (>= (count formatted) 2)
    (future
      (rescue nil
              (kg-edges/record-co-access!
               (mapv :id formatted)
               {:scope project-id :created-by created-by})))))

(defn- search-plans*
  "Search high-abstraction plans. Returns Result."
  [query limit-val type project-id in-project?]
  (let [results (plans/search-plans query
                                    :limit limit-val
                                    :type type
                                    :project-id (when in-project? project-id))
        formatted (mapv (fn [{:keys [id type tags distance preview]}]
                          {:id id
                           :type (or type "plan")
                           :tags tags
                           :distance distance
                           :preview preview})
                        results)]
    (record-co-access! formatted project-id "system:high-abstraction-search")
    (result/ok {:results formatted
                :count (count formatted)
                :query query
                :scope project-id})))

(def ^:private default-exclude-tags
  "Tags excluded from semantic search by default.
   Carto (L1/L2 codebase-mapping snippets) drowns out high-level knowledge."
  ["carto"])

(defn- search-chroma*
  "Search Chroma vector store with scope and tag filtering. Returns Result."
  [query limit-val type project-id in-project? include_descendants exclude-tags]
  (let [visible-ids (when in-project?
                      (let [visible (kg-scope/visible-scopes project-id)
                            descendants (when include_descendants
                                          (kg-scope/descendant-scopes project-id))
                            all-ids (distinct (concat visible descendants))]
                        (vec (remove #(= "global" %) all-ids))))
        effective-excludes (into (vec default-exclude-tags) exclude-tags)
        results (chroma-search/search-federated query
                                                :limit (* limit-val 2)
                                                :type type
                                                :project-ids visible-ids
                                                :exclude-tags effective-excludes)
        scope-filter (when in-project?
                       (let [base-tags (kg-scope/visible-scope-tags project-id)
                             desc-tags (when include_descendants
                                         (kg-scope/descendant-scope-tags project-id))
                             all-tags (if desc-tags
                                        (clojure.set/union base-tags desc-tags)
                                        base-tags)]
                         (disj all-tags "scope:global")))
        filtered (if scope-filter
                   (filter #(matches-scope-filter? % scope-filter) results)
                   results)
        limited (take limit-val filtered)
        formatted (mapv format-search-result limited)]
    (record-co-access! formatted project-id "system:semantic-search")
    (result/ok {:results formatted
                :count (count formatted)
                :query query
                :scope project-id})))

(defn- search-semantic*
  "Pure search logic returning Result. Validates inputs and dispatches to appropriate search backend.
   exclude_tags defaults to [\"carto\"] — pass [] to include carto snippets explicitly."
  [{:keys [query limit type directory include_descendants scope exclude_tags]}]
  (let [directory (or directory (ctx/current-directory))
        openrouter? (plans/high-abstraction-type? type)
        limit-val (coerce-int! limit :limit 10)
        store-ready? (and (mem-proto/store-set?)
                          (mem-proto/supports-semantic-search? (mem-proto/get-store)))]
    (log/info "mcp-memory-search-semantic:" query "type:" type "directory:" directory
              "scope:" scope "exclude_tags:" exclude_tags)
    (if-not store-ready?
      (result/err :memory/store-not-configured
                  {:message (str "Semantic search not configured. "
                                 "Configure a memory store with an embedding provider.")})
      (let [project-id (scope/get-current-project-id directory)
            sf (domain/parse-scope scope project-id)
            [effective-pid in-project?] (domain/scope->effective sf)]
        (if openrouter?
          (search-plans* query limit-val type effective-pid in-project?)
          (search-chroma* query limit-val type effective-pid in-project?
                          include_descendants (if (some? exclude_tags)
                                               exclude_tags
                                               default-exclude-tags)))))))

(defn handle-search-semantic
  "Search project memory using semantic similarity (vector search).
   HCR Wave 4: Pass include_descendants=true to include child project memories."
  [params]
  (rb/result->mcp (rb/try-result :memory/search #(search-semantic* params))))
