(ns hive-mcp.chroma.search
  "Semantic search operations for Chroma memory entries."
  (:require [clojure-chroma-client.api :as chroma]
            [hive-mcp.chroma.connection :as conn]
            [hive-mcp.chroma.embeddings :as emb]
            [hive-mcp.chroma.gate :as gate]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn build-where-clause
  "Build Chroma where clause from type and project-id filters.
   Pure function — no IO."
  [{:keys [type project-ids]}]
  (let [base (cond-> {}
               type        (assoc :type type)
               project-ids (assoc :project-id {:$in (vec project-ids)}))]
    (when (seq base) base)))

(defn build-where-document-clause
  "Build Chroma where-document clause for tag exclusion.
   Uses document-level $not_contains since tags are embedded in document text.
   Pure function — no IO."
  [exclude-tags]
  (when (seq exclude-tags)
    (if (= 1 (count exclude-tags))
      {:$not_contains (first exclude-tags)}
      {:$and (mapv (fn [t] {:$not_contains t}) exclude-tags)})))

(defn search-similar
  "Search for memory entries similar to the query text.
   :exclude-tags — seq of tags to exclude via where-document $not_contains."
  [query-text & {:keys [limit type project-ids exclude-tags] :or {limit 10}}]
  (emb/require-embedding!)
  (let [coll (conn/get-or-create-collection)
        query-embedding (gate/with-embedding-gate
                          (emb/embed-text (emb/get-embedding-provider) query-text))
        where-clause (build-where-clause {:type type :project-ids project-ids})
        where-doc-clause (build-where-document-clause exclude-tags)
        results (gate/deref-read (chroma/query coll query-embedding
                                               :num-results limit
                                               :where where-clause
                                               :where-document where-doc-clause
                                               :include #{:documents :metadatas :distances}))]
    (log/debug "Semantic search for:" (subs query-text 0 (min 50 (count query-text))) "..."
               "found:" (count results))
    results))

(defn search-by-id
  "Get a specific entry by ID from Chroma."
  [id]
  (let [coll (conn/get-or-create-collection)
        results (gate/deref-read (chroma/get coll :ids [id] :include #{:documents :metadatas}))]
    (first results)))

;; --- Federated Search (default + ingest collections) ---

(defn- resolve-ingest-search
  "Runtime-resolve ingest cross-collection search. Zero compile-time coupling."
  []
  (r/guard Exception nil
    (requiring-resolve 'hive-ingestor.storage.chroma/search-across-collections!)))

(defn- normalize-ingest-results
  "Normalize ingest search results to match search-similar output shape."
  [raw-results]
  (when (sequential? raw-results)
    (->> raw-results
         (mapcat (fn [coll-result]
                   (let [coll-name (:collection coll-result)
                         ids       (:ids coll-result)
                         docs      (:documents coll-result)
                         metas     (:metadatas coll-result)
                         dists     (:distances coll-result)]
                     (when (and ids docs)
                       (map (fn [id doc meta dist]
                              {:id id
                               :document doc
                               :metadata (or meta {})
                               :distance (or dist 999.0)
                               :collection coll-name})
                            (first ids) (first docs) (first metas) (first dists))))))
         (remove nil?)
         vec)))

(defn merge-and-rerank
  "Merge two result sequences, deduplicate by :id keeping closest distance, sort ascending.
   Pure function — no IO."
  [results-a results-b limit]
  (->> (concat results-a results-b)
       (reduce (fn [acc entry]
                 (let [id (:id entry)]
                   (if (contains? acc id)
                     (let [existing-dist (or (:distance (get acc id)) 999.0)
                           new-dist      (or (:distance entry) 999.0)]
                       (if (< new-dist existing-dist)
                         (assoc acc id entry)
                         acc))
                     (assoc acc id entry))))
               {})
       vals
       (sort-by #(or (:distance %) 999.0))
       (take limit)
       vec))

(defn search-federated
  "Search default memory collection + all ingest collections.
   Merges results, deduplicates by ID, re-ranks by distance (ascending).
   Returns unified result vector matching search-similar output shape."
  [query-text & {:keys [limit type project-ids exclude-tags] :or {limit 10}}]
  (let [default-results (search-similar query-text
                                        :limit limit :type type
                                        :project-ids project-ids
                                        :exclude-tags exclude-tags)
        ingest-results  (when-let [search-fn (resolve-ingest-search)]
                          (r/guard Exception nil
                            (let [result (search-fn query-text {:limit limit})]
                              (when (and (map? result) (:ok result))
                                (normalize-ingest-results (:ok result))))))
        merged (merge-and-rerank default-results ingest-results limit)]
    (log/debug "Federated search:" (count default-results) "default +"
               (count ingest-results) "ingest =" (count merged) "merged")
    merged))
