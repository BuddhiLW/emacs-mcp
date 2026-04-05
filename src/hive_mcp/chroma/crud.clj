(ns hive-mcp.chroma.crud
  "Core CRUD operations for Chroma memory entries."
  (:require [clojure-chroma-client.api :as chroma]
            [hive-mcp.chroma.connection :as conn]
            [hive-mcp.chroma.embeddings :as emb]
            [hive-mcp.chroma.gate :as gate]
            [hive-mcp.chroma.helpers :as h]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- evict-expired-async!
  "Async deletion of expired entry IDs from Chroma."
  [expired-ids]
  (when (seq expired-ids)
    (future
      (try
        (let [coll (conn/get-or-create-collection)]
          (gate/deref-write (chroma/delete coll :ids (vec expired-ids)))
          (log/info "Lazy eviction: deleted" (count expired-ids) "expired entries"))
        (catch Exception e
          (log/warn "Lazy eviction failed (non-blocking):" (.getMessage e)))))))

(defn index-memory-entry!
  "Index a memory entry in Chroma, returns entry ID."
  [{:keys [id content type tags created updated duration expires
           content-hash access-count helpful-count unhelpful-count project-id
           kg-outgoing kg-incoming abstraction-level
           grounded-at grounded-from knowledge-gaps source-hash source-file
           staleness-alpha staleness-beta staleness-source staleness-depth]
    :as entry}]
  (emb/require-embedding!)
  (let [coll (conn/get-or-create-collection)
        entry-id (or id (h/generate-id))
        now (h/iso-timestamp)
        doc-text (h/memory-to-document entry)
        embedding (gate/with-embedding-gate
                    (emb/embed-text (emb/get-embedding-provider) doc-text))
        provided {:type type :tags (h/join-tags tags) :content (h/serialize-content content)
                  :content-hash content-hash :created (or created now) :updated (or updated now)
                  :duration duration :expires expires :access-count access-count
                  :helpful-count helpful-count :unhelpful-count unhelpful-count
                  :project-id project-id
                  :kg-outgoing (h/join-tags kg-outgoing)
                  :kg-incoming (h/join-tags kg-incoming)
                  :abstraction-level abstraction-level
                  :grounded-at grounded-at
                  :grounded-from grounded-from
                  :knowledge-gaps (h/join-tags knowledge-gaps)
                  :source-hash source-hash
                  :source-file source-file
                  :staleness-alpha staleness-alpha
                  :staleness-beta staleness-beta
                  :staleness-source (when staleness-source (name staleness-source))
                  :staleness-depth staleness-depth}
        meta (merge h/metadata-defaults (into {} (remove (comp nil? val) provided)))]
    (gate/deref-write (chroma/add coll [{:id entry-id :embedding embedding :document doc-text :metadata meta}]
                                      :upsert? true))
    (log/debug "Indexed memory entry:" entry-id)
    entry-id))

(defn index-memory-entries!
  "Index multiple memory entries in batch with full metadata.
   Uses embed-batch for single GPU call. Entries should have :id pre-set."
  [entries]
  (emb/require-embedding!)
  (let [coll (conn/get-or-create-collection)
        now  (h/iso-timestamp)
        docs (mapv h/memory-to-document entries)
        embeddings (gate/with-embedding-gate
                     (emb/embed-batch (emb/get-embedding-provider) docs))
        records (mapv (fn [entry doc emb-vec]
                        (let [provided {:type          (:type entry)
                                        :tags          (h/join-tags (:tags entry))
                                        :content       (h/serialize-content (:content entry))
                                        :content-hash  (:content-hash entry)
                                        :created       (or (:created entry) now)
                                        :updated       (or (:updated entry) now)
                                        :duration      (:duration entry)
                                        :project-id    (:project-id entry)
                                        :access-count  (:access-count entry)
                                        :source-file   (:source-file entry)}
                              meta (merge h/metadata-defaults
                                          (into {} (remove (comp nil? val) provided)))]
                          {:id        (:id entry)
                           :embedding emb-vec
                           :document  doc
                           :metadata  meta}))
                      entries docs embeddings)]
    (gate/deref-write (chroma/add coll records :upsert? true))
    (log/info "Indexed" (count entries) "memory entries in batch")
    (mapv :id entries)))

(defn get-entry-by-id
  "Get a memory entry by ID from Chroma."
  [id]
  (emb/require-embedding!)
  (let [coll (conn/get-or-create-collection)
        results (gate/deref-read (chroma/get coll :ids [id] :include #{:documents :metadatas}))]
    (some-> (first results) h/metadata->entry)))

(defn query-entries
  "Query memory entries from Chroma with filtering.
   :exclude-tags — seq of tags to exclude via $not_contains."
  [& {:keys [type project-id project-ids tags exclude-tags limit include-expired?]
      :or {limit 100 include-expired? false}}]
  (emb/require-embedding!)
  (let [coll (conn/get-or-create-collection)
        base-clause (cond-> {}
                      type (assoc :type type)
                      project-ids (assoc :project-id {:$in (vec project-ids)})
                      (and project-id (not project-ids)) (assoc :project-id project-id))
        tag-conditions (when (seq tags)
                         (mapv (fn [tag] {:tags {:$contains tag}}) tags))
        exclude-conditions (when (seq exclude-tags)
                             (mapv (fn [tag] {:tags {:$not_contains tag}}) exclude-tags))
        all-extra (concat tag-conditions exclude-conditions)
        where (cond
                (seq all-extra)
                (let [base-conditions (mapv (fn [[k v]] {k v}) base-clause)]
                  {:$and (into base-conditions all-extra)})
                (seq base-clause) base-clause
                :else nil)
        fetch-limit (if include-expired? limit (+ limit 50))
        results (gate/deref-read (chroma/get coll
                                            :where where
                                            :include #{:documents :metadatas}
                                            :limit fetch-limit))
        entries (map h/metadata->entry results)
        {expired true live false} (group-by #(boolean (h/expired? %)) entries)]
    (when-not include-expired?
      (evict-expired-async! (mapv :id expired)))
    (->> (if include-expired? entries live)
         (sort-by :created #(compare %2 %1))
         (take limit)
         vec)))

(defn query-grounded-from
  "Query entries grounded from a specific disc path."
  [disc-path]
  (emb/require-embedding!)
  (let [coll (conn/get-or-create-collection)
        results (gate/deref-read (chroma/get coll
                                            :where {:grounded-from disc-path}
                                            :include #{:documents :metadatas}))]
    (map h/metadata->entry results)))

(defn update-entry!
  "Update a memory entry in Chroma by ID."
  [id updates]
  (emb/require-embedding!)
  (when-let [existing (get-entry-by-id id)]
    (let [merged (merge existing updates {:updated (h/iso-timestamp)})
          _ (index-memory-entry! merged)]
      (get-entry-by-id id))))

(defn update-staleness!
  "Update staleness fields for a Chroma entry."
  [entry-id {:keys [alpha beta source depth]}]
  (emb/require-embedding!)
  (let [updates (-> {}
                    (cond-> alpha (assoc :staleness-alpha alpha))
                    (cond-> beta (assoc :staleness-beta beta))
                    (cond-> source (assoc :staleness-source source))
                    (cond-> depth (assoc :staleness-depth depth)))]
    (update-entry! entry-id updates)))

(defn delete-entry!
  "Delete a memory entry from the Chroma index."
  [id]
  (let [coll (conn/get-or-create-collection)]
    (gate/deref-write (chroma/delete coll :ids [id]))
    (log/debug "Deleted entry from Chroma:" id)
    id))

(defn find-duplicate
  "Find entry with matching content-hash in the given type."
  [type content-hash & {:keys [project-id]}]
  (emb/require-embedding!)
  (let [entries (query-entries :type type :project-id project-id :limit 1000)]
    (first (filter #(= (:content-hash %) content-hash) entries))))

(defn collection-stats
  "Get statistics about the Chroma collection."
  []
  (try
    (let [coll (conn/get-or-create-collection)
          all-entries (gate/deref-read (chroma/get coll :include [:metadatas]))]
      {:count (count all-entries)
       :types (frequencies (map #(get-in % [:metadata :type]) all-entries))})
    (catch Exception e
      {:error (str e)})))
