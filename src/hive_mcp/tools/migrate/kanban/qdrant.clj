(ns hive-mcp.tools.migrate.kanban.qdrant
  "Qdrant adapters for the kanban migrator. Two wrinkles motivate a
   bespoke adapter rather than going through `proto/get-entry`:

     1. The qdrant store's `get-entry` returns raw protobuf and skips the
        clj-qdrant `point->map` decoder, so reads come back empty even
        when the underlying point has full payload. We bypass via
        `clj-qdrant.api/get-points` + `point->map` directly.

     2. Writes batch into a single `upsert-points` call, eliminating
        per-id grpc overhead during migration."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [hive-dsl.result :as r]
            [hive-mcp.tools.migrate.kanban.ports :as ports]
            [taoensso.timbre :as log]
            [hive-mcp.tools.migrate.optional :as opt])
  (:import [java.util UUID]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def default-collection "hive_qdrant_memory")
(def default-vector-size 384)

;; =============================================================================
;; Internals
;; =============================================================================

(defn- ->qdrant-uuid
  [id-str]
  (try (UUID/fromString id-str)
       (catch Throwable _
         (UUID/nameUUIDFromBytes (.getBytes (str id-str) "UTF-8")))))

(defn- parse-content
  "Qdrant payloads round-trip :content as a JSON string (hive-mcp writes)
   or as an EDN-printed map (legacy migrations). Try JSON first, then EDN,
   then return the raw string. Map values pass through untouched."
  [c]
  (cond
    (nil? c)        nil
    (map? c)        c
    (not (string? c)) c
    :else
    (or (try (json/read-str c :key-fn keyword) (catch Throwable _ nil))
        (try (edn/read-string c)               (catch Throwable _ nil))
        c)))

(defn- decoded-point->entry
  "Take a clj-qdrant decoded point ({:id :payload}) and rebuild the
   canonical hive-mcp entry shape. Prefers the payload's `:id` over the
   qdrant point UUID so move/delete-by-id flows still work."
  [{:keys [id payload]} fallback-id]
  (when (seq payload)
    (-> payload
        (assoc :id (or (:id payload) id fallback-id))
        (update :content parse-content))))

(defn- qclient-of [qstore]
  (some-> qstore :client-atom deref))

(defn- collection-of [qstore]
  (or (-> qstore :config :collection-name) default-collection))

(defn- vector-size-of [qstore]
  (or (-> qstore :config :vector-size) default-vector-size))

(defn- zero-vec [n] (vec (repeat n 0.0)))

;; =============================================================================
;; QdrantEntryReader — batched, decoder-correct
;; =============================================================================

(defn- decode-results-by-id
  "Map of id-string → entry, only for points that came back with non-empty
   payload (stub points are dropped at the boundary)."
  [points ids-vec]
  (let [decoded   (mapv (opt/backend-var "clj-qdrant.api" (quote point->map)) points)
        ;; clj-qdrant's get-points preserves order; map UUID-string back
        ;; to original id.
        uuid->id  (into {} (map (fn [id] [(str (->qdrant-uuid id)) id]) ids-vec))]
    (reduce (fn [acc dp]
              (let [pt-uuid-str (str (:id dp))
                    orig-id     (get uuid->id pt-uuid-str)
                    entry       (decoded-point->entry dp orig-id)]
                (cond-> acc
                  (and orig-id entry) (assoc orig-id entry))))
            {}
            decoded)))

(defrecord QdrantEntryReader [qstore]
  ports/IEntryReader
  (read-by-ids [_ ids]
    (try
      (let [client (qclient-of qstore)
            coll   (collection-of qstore)]
        (cond
          (empty? ids) (r/ok {})
          (nil? client) (r/err :qdrant/disconnected {})
          :else
          (let [uuids  (mapv ->qdrant-uuid ids)
                res    ((opt/backend-var "clj-qdrant.api" (quote get-points)) client :collection coll :ids uuids)
                points (:points res)]
            (r/ok (decode-results-by-id points ids)))))
      (catch Throwable t
        (r/err :qdrant/read-by-ids-failed
               {:message (.getMessage t) :n (count ids)})))))

(defn make-entry-reader [qstore] (->QdrantEntryReader qstore))

;; =============================================================================
;; QdrantEntryWriter — batched upsert
;; =============================================================================

(defn- entry->point
  "Build a clj-qdrant point from an entry. Mirrors the qdrant store's
   internal `entry->point` but tolerates legacy entry shapes from
   milvus reads (content as string/map, type as keyword/string)."
  [entry vector-size]
  (let [id     (:id entry)
        embed  (:embedding entry)
        ;; Stringify content if it came back as a map (milvus reads parse JSON).
        content (let [c (:content entry)]
                  (cond
                    (string? c) c
                    (map? c)    (json/write-str c)
                    :else       c))]
    {:id      (->qdrant-uuid id)
     :vector  (or embed (zero-vec vector-size))
     :payload (-> entry
                  (assoc :id id :content content)
                  (dissoc :embedding)
                  (update :type #(if (keyword? %) (name %) (str (or % "note"))))
                  (update :tags #(when (seq %) (mapv str %)))
                  (->> (into {} (remove (comp nil? val)))))}))

(defrecord QdrantEntryWriter [qstore]
  ports/IEntryWriter
  (write-entries [_ entries]
    (try
      (let [client (qclient-of qstore)
            coll   (collection-of qstore)
            vs     (vector-size-of qstore)]
        (cond
          (empty? entries) (r/ok [])
          (nil? client) (r/err :qdrant/disconnected {})
          :else
          (let [points (mapv #(entry->point % vs) entries)
                _      ((opt/backend-var "clj-qdrant.api" (quote upsert-points)) client :collection coll :points points)
                results (mapv (fn [e] {:id (:id e) :ok? true}) entries)]
            (r/ok results))))
      (catch Throwable t
        (log/warn t "qdrant batched upsert failed" {:n (count entries)})
        (r/err :qdrant/write-entries-failed
               {:message (.getMessage t) :n (count entries)})))))

(defn make-entry-writer [qstore] (->QdrantEntryWriter qstore))
