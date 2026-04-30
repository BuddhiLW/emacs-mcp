(ns hive-mcp.tools.memory.crud.retrieve
  "Retrieval operations for memory: get-full, batch-get, check-duplicate, update-tags."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.plan.plans :as plans]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- edge->json-map
  "Convert KG edge to JSON-safe map format."
  [edge]
  (cond-> {:id (:kg-edge/id edge)
           :from (:kg-edge/from edge)
           :to (:kg-edge/to edge)
           :relation (name (:kg-edge/relation edge))
           :confidence (:kg-edge/confidence edge)
           :scope (:kg-edge/scope edge)
           :created_by (:kg-edge/created-by edge)
           :created_at (str (:kg-edge/created-at edge))}
    (:kg-edge/last-verified edge) (assoc :last_verified (str (:kg-edge/last-verified edge)))
    (:kg-edge/source-type edge) (assoc :source_type (name (:kg-edge/source-type edge)))))

(defn- get-kg-edges-for-entry
  "Get KG edges for a memory entry, returning outgoing and incoming lists."
  [entry-id]
  (let [outgoing (kg-edges/get-edges-from entry-id)
        incoming (kg-edges/get-edges-to entry-id)]
    {:outgoing (mapv edge->json-map outgoing)
     :incoming (mapv edge->json-map incoming)}))

(defn handle-get-full
  "Get full content of a memory entry by ID with KG edges.
   Wraps the store read in `with-resilience` so a transient transport
   drop triggers the heal loop + retry before surfacing a not-found."
  [{:keys [id]}]
  (log/info "mcp-memory-get-full:" id)
  (with-store
    (let [store (mem-proto/get-store)]
      (if-let [entry (or (with-resilience (mem-proto/get-entry store id))
                         (plans/get-plan id))]
        (let [base-result (fmt/entry->json-alist entry)
              {:keys [outgoing incoming]}
              (try (get-kg-edges-for-entry id)
                   (catch Exception e
                     (log/warn "KG edge lookup failed for" id ":" (.getMessage e))
                     {:outgoing [] :incoming []}))
              result (cond-> base-result
                       (seq outgoing) (assoc :kg_outgoing outgoing)
                       (seq incoming) (assoc :kg_incoming incoming))]
          (mcp-json result))
        (mcp-json {:error "Entry not found" :id id})))))

(defn handle-batch-get
  "Get multiple memory entries by IDs in a single call with KG edges.
   Each store read is wrapped in `with-resilience` so a dropped transport
   on one ID triggers heal-and-retry rather than poisoning the whole batch."
  [{:keys [ids]}]
  (if (or (nil? ids) (empty? ids))
    (mcp-error "ids is required (array of memory entry ID strings)")
    (with-store
      (let [store (mem-proto/get-store)
            results (mapv (fn [id]
                            (if-let [entry (or (with-resilience (mem-proto/get-entry store id))
                                               (plans/get-plan id))]
                              (let [base (fmt/entry->json-alist entry)
                                    {:keys [outgoing incoming]}
                                    (try (get-kg-edges-for-entry id)
                                         (catch Exception e
                                           (log/warn "KG edge lookup failed for" id ":" (.getMessage e))
                                           {:outgoing [] :incoming []}))]
                                (cond-> base
                                  (seq outgoing) (assoc :kg_outgoing outgoing)
                                  (seq incoming) (assoc :kg_incoming incoming)))
                              {:error "Entry not found" :id id}))
                          ids)
            found   (filterv #(not (:error %)) results)
            missing (filterv :error results)]
        (mcp-json (cond-> {:entries found :count (count found)}
                    (seq missing) (assoc :missing (mapv :id missing))))))))

(defn handle-check-duplicate
  "Check if content already exists in memory.
   Wraps the store lookup in `with-resilience` so a transient transport
   drop yields a heal-and-retry rather than a false 'no duplicate' result."
  [{:keys [type content directory]}]
  (log/info "mcp-memory-check-duplicate:" type "directory:" directory)
  (with-store
    (let [store (mem-proto/get-store)
          project-id (scope/get-current-project-id directory)
          hash (mem-proto/content-hash content)
          existing (with-resilience
                     (mem-proto/find-duplicate store type hash {:project-id project-id}))]
      (mcp-json {:exists (some? existing)
                 :entry (when existing (fmt/entry->json-alist existing))
                 :content_hash hash}))))

(defn handle-update-tags
  "Replace tags on an existing memory entry.
   Both the existence check and the tag-update write are wrapped in
   `with-resilience` so a dropped transport between them kicks the
   heal loop and retries once."
  [{:keys [id tags]}]
  (log/info "mcp-memory-update-tags:" id "tags:" tags)
  (with-store
    (let [store (mem-proto/get-store)]
      (if-let [_existing (with-resilience (mem-proto/get-entry store id))]
        (let [updated (with-resilience
                        (mem-proto/update-entry! store id {:tags (or tags [])}))]
          (log/info "Updated tags for entry:" id)
          (mcp-json (fmt/entry->json-alist updated)))
        (mcp-json {:error "Entry not found" :id id})))))
