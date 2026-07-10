(ns hive-mcp.tools.memory.crud.reembed
  "Reembed handler for memory entries.

   Re-vectorize an entry without rewriting content. Useful when:
     - The embedding model changed and old vectors are stale.
     - The vector index was rebuilt and an entry needs refreshing.
     - KG edges shifted and the semantic neighborhood needs to catch up.

   Implementation note: any call to IMemoryStore/update-entry! routes through
   the backend's index-memory-entry! path, which always re-embeds the merged
   document. So re-embedding without content rewrite is a no-content update
   (we touch :updated only) that nevertheless triggers a full re-index.

   Preserves entry id → all KG edges (keyed by id) survive untouched. Tags,
   duration, abstraction-level, project-id, content-hash all flow through the
   merge unchanged.

   Acceptance origin: kanban 20260427004720-6447110d."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.dns.result :refer [rescue-log]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn- publish-reembed! [id existing]
  (rescue-log (str "[memory] reembed publish " id) nil
    (when-let [publish-fn (requiring-resolve 'hive-mcp.channel.core/publish!)]
      (publish-fn {:type :memory-reembedded
                   :id id
                   :memory-type (:type existing)
                   :tags (:tags existing)
                   :project-id (:project-id existing)}))))

(defn- reembed-one!
  "Re-embed a single entry. Returns a result map or nil when not found."
  [store id]
  (when-let [existing (mem-proto/get-entry store id)]
    (let [updated (mem-proto/update-entry! store id {:updated (now-iso)})]
      (log/info "Memory reembed:" id)
      (publish-reembed! id existing)
      {:id        id
       :reembedded true
       :updated    updated
       :existing   existing})))

(defn handle-reembed
  "Re-embed a single memory entry by id. No content change required.

   Params:
     :id     — required, entry to re-embed
     :force  — accepted; currently every reembed forces re-index (the
               backend's update-entry! always re-vectorizes). Kept for
               forward compatibility once a content-hash skip lands."
  [{:keys [id]}]
  (cond
    (or (nil? id) (str/blank? id))
    (mcp-error "id is required (non-blank string)")

    :else
    (with-store
      (let [store  (mem-proto/get-store)
            result (reembed-one! store id)]
        (if (nil? result)
          (mcp-json {:error "Entry not found" :id id})
          (mcp-json (assoc (fmt/entry->json-alist (:updated result))
                           :reembedded true)))))))

(defn- batch-op-result
  [store id]
  (rescue-log (str "Batch-reembed op " id) {:id id :status :error}
    (if (reembed-one! store id)
      {:id id :status :reembedded}
      {:id id :status :not-found})))

(defn- tally-statuses
  [results]
  (let [status->count (frequencies (map :status results))]
    {:total      (count results)
     :reembedded (get status->count :reembedded 0)
     :not-found  (get status->count :not-found 0)
     :errors     (get status->count :error 0)}))

(defn handle-batch-reembed
  "Re-embed a batch of entries by id. Sequential per-op.

   Params:
     :ids — required, seq of entry ids"
  [{:keys [ids]}]
  (cond
    (empty? ids)
    (mcp-error "ids is required (non-empty array of entry ids)")

    :else
    (with-store
      (let [store   (mem-proto/get-store)
            results (mapv #(batch-op-result store %) ids)
            summary (tally-statuses results)]
        (mcp-json (assoc summary :results results))))))
