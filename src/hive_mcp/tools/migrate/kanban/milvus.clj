(ns hive-mcp.tools.migrate.kanban.milvus
  "Milvus-side adapters for the kanban migrator.

   `MilvusIdLister`  — implements IIdLister; scans all known collections
                      (per-dim shards) for the kanban filter, dedups + sorts.

   `MilvusEntryReader` — implements IEntryReader; batches `id in [...]`
                         queries per collection so a 50-id batch costs
                         3 round-trips total instead of 150."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.tools.migrate.kanban.pure :as pure]
            [hive-mcp.tools.migrate.kanban.ports :as ports]
            [hive-milvus.store.lookup :as lookup]
            [hive-milvus.store.schema :as schema]
            [milvus-clj.api :as milvus]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def default-tag-filter "tags like \"%kanban%\"")
(def default-page-cap 10000)

;; =============================================================================
;; Helpers (private)
;; =============================================================================

(defn- config-atom-of
  "Pull the underlying milvus config-atom from a hive-milvus store record.
   Returns nil if the store doesn't expose one — caller decides whether
   that's fatal."
  [store]
  (some-> store :config-atom))

(defn- ids-in-filter
  "Build a milvus `id in [\"a\",\"b\"]` clause."
  [ids]
  (str "id in ["
       (str/join "," (map #(str "\"" % "\"") ids))
       "]"))

(defn- query-coll-ids
  "Single-collection scan: list ids matching `tag-filter`, capped at
   `page-cap`. Returns vector of id strings."
  [coll tag-filter page-cap]
  (try
    (let [rows @(milvus/query-scalar coll
                  {:filter            tag-filter
                   :limit             page-cap
                   :output-fields     ["id"]
                   :consistency-level :bounded})]
      (mapv :id rows))
    (catch Throwable t
      (log/warn t "milvus IdLister coll error" {:coll coll})
      [])))

(defn- query-coll-entries-by-ids
  "Single-collection batched read: `id in [...]` constrained to `ids`.
   Returns vector of entries (decoded via schema/record->entry)."
  [coll ids]
  (when (seq ids)
    (try
      (let [rows @(milvus/query-scalar coll
                    {:filter            (ids-in-filter ids)
                     :limit             (count ids)
                     :output-fields     schema/default-read-fields
                     :consistency-level :bounded})]
        (mapv schema/record->entry rows))
      (catch Throwable t
        (log/warn t "milvus EntryReader coll error" {:coll coll :n (count ids)})
        []))))

(defn- pick-best-entry
  "When the same id surfaces in multiple per-dim collections, keep the
   one with non-empty content. Falls back to the first if all are
   stub-only — adapter never fabricates data."
  [entries]
  (or (first (filter pure/full-payload? entries))
      (first entries)))

;; =============================================================================
;; IIdLister
;; =============================================================================

(defrecord MilvusIdLister [milvus-store tag-filter page-cap]
  ports/IIdLister
  (list-ids [_]
    (try
      (if-let [cfg (config-atom-of milvus-store)]
        (let [colls (lookup/known-collections cfg)
              per-coll (mapv (fn [c] (query-coll-ids c tag-filter page-cap)) colls)]
          (r/ok {:ids       (pure/dedup-sorted-ids per-coll)
                 :per-collection (zipmap colls (mapv count per-coll))}))
        (r/err :milvus/no-config-atom
               {:hint "Store does not expose :config-atom; not a hive-milvus record."}))
      (catch Throwable t
        (r/err :milvus/list-ids-failed {:message (.getMessage t)})))))

(defn make-id-lister
  "Build a MilvusIdLister. Defaults: kanban tag filter, 10k per-coll cap."
  ([milvus-store]
   (make-id-lister milvus-store {}))
  ([milvus-store {:keys [tag-filter page-cap]}]
   (->MilvusIdLister milvus-store
                     (or tag-filter default-tag-filter)
                     (or page-cap default-page-cap))))

;; =============================================================================
;; IEntryReader
;; =============================================================================

(defrecord MilvusEntryReader [milvus-store]
  ports/IEntryReader
  (read-by-ids [_ ids]
    (try
      (if (empty? ids)
        (r/ok {})
        (if-let [cfg (config-atom-of milvus-store)]
          (let [colls (lookup/known-collections cfg)
                ;; Fan-out: one batched query per coll, group by id.
                by-id (->> colls
                           (mapcat (fn [c] (query-coll-entries-by-ids c ids)))
                           (group-by :id))
                resolved (into {}
                               (map (fn [[id es]] [id (pick-best-entry es)])
                                    by-id))]
            (r/ok resolved))
          (r/err :milvus/no-config-atom {})))
      (catch Throwable t
        (r/err :milvus/read-by-ids-failed
               {:message (.getMessage t) :n (count ids)})))))

(defn make-entry-reader
  [milvus-store]
  (->MilvusEntryReader milvus-store))
