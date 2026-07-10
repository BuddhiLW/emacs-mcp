(ns hive-mcp.knowledge-graph.disc.crud
  "CRUD operations for disc (file-state) entities.

   :disc/* entities are cartography artifacts — file content hashes, last
   analyzed timestamps, git commits — derivable from the codebase scan and
   regenerable. They live in the :carto slot (Datalevin per the storage
   migration plan, decision 20260507133442-017631c2). Bitemporal value is
   nil — these are point-in-time facts about the file system."
  (:require [hive-mcp.knowledge-graph.slots :as slots]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.disc.hash :as hash]
            [hive-mcp.knowledge-graph.disc.volatility :as vol]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private slot
  "All disc CRUD targets the :carto slot — file-state is regenerable
   structural data, not bitemporal."
  :carto)

(defn add-disc!
  "Create or update a disc entity for a file path."
  [{:keys [path content-hash analyzed-at git-commit project-id]}]
  {:pre [(string? path) (seq path)]}
  (let [now (java.util.Date.)
        volatility (vol/classify-volatility path)
        initial-alpha (get vol/initial-alpha-by-volatility volatility 5.0)
        tx-data [{:disc/path path
                  :disc/content-hash (or content-hash "")
                  :disc/analyzed-at (or analyzed-at now)
                  :disc/git-commit (or git-commit "")
                  :disc/project-id (or project-id "global")
                  :disc/certainty-alpha initial-alpha
                  :disc/certainty-beta 2.0
                  :disc/volatility-class volatility
                  :disc/last-observation now}]
        result (slots/transact! slot tx-data)]
    (log/debug "Added/updated disc entity" {:path path :volatility volatility
                                            :initial-certainty (/ initial-alpha (+ initial-alpha 2.0))})
    (-> result :tx-data first :e)))

(defn get-disc
  "Get disc entity by file path. Returns entity map or nil if not found."
  [path]
  {:pre [(string? path)]}
  (let [result (slots/query slot
                            '[:find (pull ?e [*])
                              :in $ ?path
                              :where [?e :disc/path ?path]]
                            [path])]
    (ffirst result)))

(defn disc-exists?
  "Check if a disc entity exists for the given path."
  [path]
  (some? (get-disc path)))

(defn update-disc!
  "Update a disc entity. Path is used to find the entity; other fields update."
  [path updates]
  {:pre [(string? path)]}
  (when-let [_existing (get-disc path)]
    (let [tx-data [(merge {:disc/path path} updates)]]
      (slots/transact! slot tx-data)
      (log/debug "Updated disc entity" {:path path :updates (keys updates)})
      (get-disc path))))

(defn remove-disc!
  "Remove a disc entity by path. Returns true if removed, nil if not found."
  [path]
  {:pre [(string? path)]}
  (when-let [disc (get-disc path)]
    (let [eid (:db/id disc)]
      (slots/transact! slot [[:db.fn/retractEntity eid]])
      (log/debug "Removed disc entity" {:path path})
      true)))

(defn get-all-discs
  "Get all disc entities. Optional project-id filter."
  [& {:keys [project-id]}]
  (let [results (if project-id
                  (slots/query slot
                               '[:find (pull ?e [*])
                                 :in $ ?pid
                                 :where
                                 [?e :disc/path _]
                                 [?e :disc/project-id ?pid]]
                               [project-id])
                  (slots/query slot
                               '[:find (pull ?e [*])
                                 :where [?e :disc/path _]]))]
    (map first results)))

(defn- pair->tx-datum
  "Convert a [path updates-map] pair into a transaction datum.
   Returns nil for invalid pairs (filtered downstream)."
  [[path updates]]
  (when (and (string? path) (seq path) (map? updates))
    (merge {:disc/path path} updates)))

(defn batch-update-discs!
  "Batch-update multiple disc entities in a single transaction."
  [path-updates-pairs]
  (let [tx-data (into [] (comp (map pair->tx-datum) (filter some?))
                      path-updates-pairs)]
    (if (empty? tx-data)
      {:updated 0 :tx-data-size 0}
      (do
        (slots/transact! slot tx-data)
        (log/debug "Batch-updated disc entities" {:count (count tx-data)})
        {:updated (count tx-data) :tx-data-size (count tx-data)}))))

(defn disc-stats
  "Return summary statistics for all disc entities."
  []
  {:total (count (get-all-discs))})

(defn touch-disc!
  "Record a file read, creating the disc entity if needed.

   Note: `conn/with-tx-batch` here is a no-op wrapper — the inner add/update
   fns route through the slots facade which writes directly. Coalescing for
   the :carto slot is a separate Phase 2+ concern."
  [path & {:keys [project-id]}]
  {:pre [(string? path) (seq path)]}
  (conn/with-tx-batch
    (let [now (java.util.Date.)]
      (if (disc-exists? path)
        (let [existing (get-disc path)
              current-count (or (:disc/read-count existing) 0)]
          (update-disc! path {:disc/last-read-at now
                              :disc/read-count (inc current-count)})
          (get-disc path))
        (let [{:keys [hash]} (hash/file-content-hash path)]
          (add-disc! {:path path
                      :content-hash (or hash "")
                      :project-id (or project-id "global")})
          (update-disc! path {:disc/last-read-at now
                              :disc/read-count 1})
          (get-disc path))))))
