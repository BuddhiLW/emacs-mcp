(ns hive-mcp.tools.migrate.batch
  "Batched store-to-store migration — the DIP port plus its default impl.

   `sync!` and `verify` need nothing but `IMemoryStore`, so the default
   implementation lives here and core migrates with no backend on the
   classpath. A backend that can move entries faster than entry-at-a-time
   registers its own implementation via `set-migrator!`.

   Contract:
     sync!  => {:extracted N :transformed N :loaded-ok N :loaded-fail N
                :batches N :dry-run? bool}
     verify => {:checked N :ok N :missing [id ...]}"
  (:require [hive-spi.memory.ports :as ports]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Port
;; =============================================================================

(defprotocol IBatchMigrator
  "Move entries between two IMemoryStores in batches."
  (sync! [this opts]
    "Extract via (:source-fn opts), normalize, and upsert into (:target opts).
     Honours :batch-size and :dry-run?. Returns the migration report.")
  (verify [this opts]
    "Round-trip-check (:ids opts) against (:target opts). Returns the report."))

;; =============================================================================
;; Default implementation — pure IMemoryStore, no backend required
;; =============================================================================

(defn- extract
  [{:keys [source-fn]}]
  (assert source-fn "sync! requires :source-fn")
  (source-fn))

(defn- transform
  "Normalize entries to the shape every store accepts."
  [entries]
  (vec
   (for [e entries]
     (-> e
         (update :id   #(or % (str (random-uuid))))
         (update :type #(or % :note))
         (update :tags #(or % []))))))

(defn- load-batch!
  [target batch]
  (reduce (fn [{:keys [ok fail] :as acc} entry]
            (try
              (let [r (ports/add-entry! target entry)]
                (if (:success? r)
                  (assoc acc :ok (inc ok))
                  (assoc acc :fail (inc fail))))
              (catch Throwable t
                (log/warn "[migrate/batch] load failed:" (ex-message t))
                (assoc acc :fail (inc fail)))))
          {:ok 0 :fail 0}
          batch))

(defrecord DefaultBatchMigrator []
  IBatchMigrator

  (sync! [_this {:keys [target batch-size dry-run?]
                 :or   {batch-size 500}
                 :as   opts}]
    (assert target "sync! requires :target IMemoryStore")
    (log/info "[migrate/batch] sync! starting — batch-size" batch-size)
    (let [raw         (extract opts)
          extracted   (count raw)
          entries     (transform raw)
          transformed (count entries)]
      (if dry-run?
        {:extracted   extracted
         :transformed transformed
         :loaded-ok   0
         :loaded-fail 0
         :batches     0
         :dry-run?    true}
        (let [batches (partition-all batch-size entries)
              totals  (reduce (fn [acc batch]
                                (let [{:keys [ok fail]} (load-batch! target batch)]
                                  (-> acc
                                      (update :loaded-ok   + ok)
                                      (update :loaded-fail + fail)
                                      (update :batches     inc))))
                              {:loaded-ok 0 :loaded-fail 0 :batches 0}
                              batches)]
          (merge {:extracted   extracted
                  :transformed transformed
                  :dry-run?    false}
                 totals)))))

  (verify [_this {:keys [target ids]}]
    (assert target "verify requires :target")
    (let [results (for [id ids]
                    (try
                      [id (some? (ports/get-entry target id))]
                      (catch Throwable _ [id false])))]
      {:checked (count ids)
       :ok      (count (filter second results))
       :missing (vec (map first (remove second results)))})))

;; =============================================================================
;; Registry
;; =============================================================================

(defonce ^:private migrator
  (atom (->DefaultBatchMigrator)))

(defn current-migrator
  "The registered IBatchMigrator implementation."
  []
  @migrator)

(defn set-migrator!
  "Register M as the IBatchMigrator implementation. Returns M."
  [m]
  (reset! migrator m)
  m)
