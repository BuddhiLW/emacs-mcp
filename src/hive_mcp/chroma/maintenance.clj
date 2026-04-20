(ns hive-mcp.chroma.maintenance
  "Cleanup, repair, and expiration management for Chroma entries."
  (:require [clojure-chroma-client.api :as chroma]
            [hive-mcp.chroma.connection :as conn]
            [hive-mcp.chroma.crud :as crud]
            [hive-mcp.chroma.embeddings :as emb]
            [hive-mcp.chroma.gate :as gate]
            [hive-mcp.chroma.helpers :as h]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- repair-missing-expires!
  "Repair entries with missing duration or expires timestamps."
  [all-entries]
  (let [needs-repair (->> all-entries
                          (filter (fn [e]
                                    (let [dur (get-in e [:metadata :duration])
                                          exp (get-in e [:metadata :expires])
                                          canonical (h/normalize-duration dur)]
                                      (or
                                       (not= dur canonical)
                                       (and (contains? #{"ephemeral" "short" "medium" "long"} canonical)
                                            (empty? (str exp))))))))]
    (doseq [entry needs-repair]
      (try
        (let [id (:id entry)
              raw-dur (get-in entry [:metadata :duration])
              canonical (h/normalize-duration raw-dur)
              days (case canonical
                     "ephemeral" 1 "short" 7 "medium" 30 "long" 90
                     nil)
              expires (when days
                        (str (.plusDays (java.time.ZonedDateTime/now) days)))]
          (when (or (not= raw-dur canonical) expires)
            (let [coll (conn/get-or-create-collection)
                  updates (cond-> {}
                            (not= raw-dur canonical) (assoc :duration canonical)
                            expires (assoc :expires expires))]
              (when-let [existing (first (gate/deref-read
                                         (chroma/get coll :ids [id]
                                                     :include #{:documents :metadatas :embeddings})))]
                (gate/deref-write
                  (chroma/add coll [{:id id
                                     :embedding (:embedding existing)
                                     :document (:document existing)
                                     :metadata (merge (:metadata existing) updates)}]
                              :upsert? true))
                (log/debug "Repaired entry" id ":" raw-dur "->" canonical
                           (when expires "(set expires)"))))))
        (catch Exception e
          (log/warn "Failed to repair entry" (:id entry) ":" (.getMessage e)))))
    (count needs-repair)))

(defn cleanup-expired!
  "Delete expired entries and repair entries with missing expires."
  []
  (emb/require-embedding!)
  (let [coll (conn/get-or-create-collection)
        all-entries (gate/deref-read (chroma/get coll :include #{:metadatas} :limit 10000))
        repaired (try (repair-missing-expires! all-entries)
                      (catch Exception e
                        (log/warn "Repair phase failed (non-blocking):" (.getMessage e))
                        0))
        entries-to-check (if (pos? repaired)
                           (gate/deref-read (chroma/get coll :include #{:metadatas} :limit 10000))
                           all-entries)
        expired-ids (->> entries-to-check
                         (filter #(h/expired? (:metadata %)))
                         (map :id)
                         vec)]
    (when (seq expired-ids)
      (gate/deref-write (chroma/delete coll :ids expired-ids))
      (log/info "Cleaned up" (count expired-ids) "expired entries"))
    (when (pos? repaired)
      (log/info "Repaired" repaired "entries with missing/invalid duration or expires"))
    {:count (count expired-ids)
     :deleted-ids expired-ids
     :repaired repaired}))

(defn entries-expiring-soon
  "Get entries expiring within the given number of days."
  [days & {:keys [project-id]}]
  (emb/require-embedding!)
  (let [now (java.time.ZonedDateTime/now)
        threshold (.plusDays now days)
        entries (crud/query-entries :project-id project-id :limit 10000 :include-expired? false)]
    (->> entries
         (filter (fn [entry]
                   (when-let [exp-time (h/parse-zoned-datetime (:expires entry))]
                     (h/time-between? exp-time now threshold))))
         (sort-by :expires)
         vec)))
