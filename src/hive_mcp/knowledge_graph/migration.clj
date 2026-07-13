(ns hive-mcp.knowledge-graph.migration
  "Migration tooling for Knowledge Graph backends.

   Provides utilities to:
   - Export KG data (edges, disc, synthetic) to portable EDN format
   - Import EDN data into any backend (DataScript, Datalevin, Datahike)
   - Migrate between backends with validation"

  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-dsl.result :as r]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-mcp.knowledge-graph.slots.factory :as factory]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Export Functions
;; =============================================================================

(defn- query-all-edges
  "Query all KG edges from the current store."
  []
  (conn/query '[:find [(pull ?e [*]) ...]
                :where [?e :kg-edge/id]]))

(defn- query-all-disc
  "Query all disc entities from the current store."
  []
  (conn/query '[:find [(pull ?e [*]) ...]
                :where [?e :disc/path]]))

(defn- query-all-synthetic
  "Query all synthetic nodes from the current store."
  []
  (conn/query '[:find [(pull ?e [*]) ...]
                :where [?e :kg-synthetic/id]]))

(defn export-to-edn
  "Export all KG data to a portable EDN map.

   Returns:
     {:edges      [<edge-entities>...]
      :disc       [<disc-entities>...]
      :synthetic  [<synthetic-entities>...]
      :exported-at <timestamp>
      :counts     {:edges N :disc N :synthetic N}}"
  []
  (log/info "Exporting KG data to EDN")
  (let [edges (query-all-edges)
        disc (query-all-disc)
        synthetic (query-all-synthetic)
        counts {:edges (count edges)
                :disc (count disc)
                :synthetic (count synthetic)}]
    (log/info "Export complete" counts)
    {:edges edges
     :disc disc
     :synthetic synthetic
     :exported-at (java.util.Date.)
     :counts counts}))

(defn export-to-file!
  "Export KG data to an EDN file.

   Arguments:
     path - File path for the export

   Returns the export data map."
  [path]
  (log/info "Exporting KG to file" {:path path})
  (let [data (export-to-edn)
        parent-dir (.getParentFile (io/file path))]
    (when (and parent-dir (not (.exists parent-dir)))
      (.mkdirs parent-dir))
    (spit path (pr-str data))
    (log/info "Export saved" {:path path :counts (:counts data)})
    data))

;; =============================================================================
;; Import Functions
;; =============================================================================

(defn- clean-entity-for-import
  "Remove DataScript/Datahike internal fields from entity for re-import.
   Strips :db/id and other internal attrs."
  [entity]
  (dissoc entity :db/id))

(defn- import-entities!
  "Import entities in batches for performance.
   Partitions into chunks of batch-size (default 500), transacts each batch.
   On batch failure, falls back to per-entity import for that batch."
  [entities entity-type transact-fn! imported errors]
  (let [batch-size 500
        cleaned (mapv clean-entity-for-import entities)
        batches (partition-all batch-size cleaned)]
    (doseq [batch batches]
      (let [v (r/guard Exception nil
                       (transact-fn! (vec batch))
                       :ok)]
        (if (= :ok v)
          (swap! imported update entity-type + (count batch))
          ;; Batch failed — fall back to per-entity for granular error tracking
          (doseq [entity batch]
            (let [v2 (r/guard Exception nil
                              (transact-fn! [entity])
                              :ok)]
              (if (= :ok v2)
                (swap! imported update entity-type inc)
                (swap! errors conj {:type entity-type :entity entity
                                    :error (some-> (meta v2) ::r/error :message)})))))))))

(defn import-from-edn
  "Import KG data from an EDN map into the current store.

   Arguments:
     data - EDN map with :edges, :disc, :synthetic keys

   Returns:
     {:imported {:edges N :disc N :synthetic N}
      :errors   [<any-errors>]}"
  [data]
  (log/info "Importing KG data from EDN" {:counts (:counts data)})
  (let [errors (atom [])
        imported (atom {:edges 0 :disc 0 :synthetic 0})]
    (import-entities! (:disc data) :disc conn/transact! imported errors)
    (import-entities! (:synthetic data) :synthetic conn/transact! imported errors)
    (import-entities! (:edges data) :edges conn/transact! imported errors)
    (let [result {:imported @imported :errors @errors}]
      (log/info "Import complete" {:imported @imported :error-count (count @errors)})
      result)))

(defn import-from-file!
  "Import KG data from an EDN file.

   Arguments:
     path - Path to the EDN export file

   Returns import result map."
  [path]
  (log/info "Importing KG from file" {:path path})
  (let [data (-> path slurp edn/read-string)]
    (import-from-edn data)))

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-migration
  "Validate that migration was complete by comparing entity counts.

   Arguments:
     expected-counts - Map of {:edges N :disc N :synthetic N}

   Returns:
     {:valid?     boolean
      :expected   {:edges N :disc N :synthetic N}
      :actual     {:edges N :disc N :synthetic N}
      :missing    {:edges N :disc N :synthetic N}}"
  [expected-counts]
  (log/info "Validating migration" {:expected expected-counts})
  (let [actual {:edges (count (query-all-edges))
                :disc (count (query-all-disc))
                :synthetic (count (query-all-synthetic))}
        missing {:edges (- (:edges expected-counts 0) (:edges actual))
                 :disc (- (:disc expected-counts 0) (:disc actual))
                 :synthetic (- (:synthetic expected-counts 0) (:synthetic actual))}
        valid? (and (= (:edges expected-counts 0) (:edges actual))
                    (= (:disc expected-counts 0) (:disc actual))
                    (= (:synthetic expected-counts 0) (:synthetic actual)))]
    (if valid?
      (log/info "Migration validated successfully" {:counts actual})
      (log/warn "Migration validation failed" {:expected expected-counts
                                               :actual actual
                                               :missing missing}))
    {:valid? valid?
     :expected expected-counts
     :actual actual
     :missing missing}))

;; =============================================================================
;; Full Migration Functions
;; =============================================================================

(defn- create-target-store
  "Create a FRESH store for the target backend via the KG factory.

   Arguments:
     backend - :datascript, :datalevin, or :datahike
     opts    - backend-specific target options (:db-path, :store-name, ...)

   Returns IKGStore. `:fresh? true` forces a distinct store so a migration
   target never aliases the live source store. Throws on unknown backend."
  [backend opts]
  (or (factory/backend->store backend (assoc (or opts {}) :fresh? true))
      (throw (ex-info "Unknown target backend" {:backend backend}))))

(defn migrate-store!
  "Migrate all KG data from source backend to target backend.

   Arguments:
     source-backend - Current backend keyword (:datascript, :datalevin, :datahike)
     target-backend - Target backend keyword
     opts           - Options map:
       :target-opts   - Backend-specific options for target
       :dry-run       - If true, export only without importing (default: false)
       :export-path   - Optional path to save export EDN

   Returns:
     {:source-backend  keyword
      :target-backend  keyword
      :exported        {:edges N :disc N :synthetic N}
      :imported        {:edges N :disc N :synthetic N} (nil if dry-run)
      :validation      {:valid? boolean ...} (nil if dry-run)
      :dry-run         boolean}"
  [source-backend target-backend & [{:keys [target-opts dry-run export-path]
                                     :or {dry-run false}}]]
  (log/info "Starting KG migration" {:source source-backend
                                     :target target-backend
                                     :dry-run dry-run})

  ;; Phase 1: Export from source
  (log/info "Phase 1: Exporting from source backend" {:backend source-backend})
  (let [export-data (export-to-edn)
        exported-counts (:counts export-data)]

    ;; Optionally save export to file
    (when export-path
      (log/info "Saving export to file" {:path export-path})
      (let [parent-dir (.getParentFile (io/file export-path))]
        (when (and parent-dir (not (.exists parent-dir)))
          (.mkdirs parent-dir)))
      (spit export-path (pr-str export-data)))

    (if dry-run
      ;; Dry run - just return export info
      (do
        (log/info "Dry run complete - no import performed")
        {:source-backend source-backend
         :target-backend target-backend
         :exported exported-counts
         :imported nil
         :validation nil
         :dry-run true})

      ;; Phase 2: Create target store and import
      (do
        (log/info "Phase 2: Creating target backend" {:backend target-backend})
        (let [target-store (create-target-store target-backend target-opts)]

          ;; Switch to target store
          (proto/set-store! target-store)
          (proto/ensure-conn! target-store)

          ;; Phase 3: Import data
          (log/info "Phase 3: Importing data to target")
          (let [import-result (import-from-edn export-data)]

            ;; Phase 4: Validate
            (log/info "Phase 4: Validating migration")
            (let [validation (validate-migration exported-counts)]

              (if (:valid? validation)
                (log/info "Migration completed successfully"
                          {:source source-backend :target target-backend})
                (log/warn "Migration completed with discrepancies"
                          {:validation validation}))

              {:source-backend source-backend
               :target-backend target-backend
               :exported exported-counts
               :imported (:imported import-result)
               :errors (:errors import-result)
               :validation validation
               :dry-run false})))))))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn detect-current-backend
  "Detect the currently active backend type.
   Returns :datascript, :datalevin, :datahike, or :unknown."
  []
  (if (proto/store-set?)
    (let [store (proto/get-store)
          store-type-name (-> store type .getName)]
      (cond
        (str/includes? store-type-name "DataScriptStore") :datascript
        (str/includes? store-type-name "DatalevinStore")  :datalevin
        (str/includes? store-type-name "DatahikeStore")   :datahike
        :else :unknown))
    :datascript))

(defn migrate-to-datahike!
  "Convenience function to migrate from any backend to Datahike.

   Arguments:
     opts - Options map:
       :db-path     - Datahike storage path (default: data/kg/datahike-migrated)
       :backend     - Datahike backend :file or :mem (default: :file)
       :dry-run     - Preview without migrating
       :export-path - Optional path to save EDN backup

   Returns migration result map."
  [& [{:keys [db-path backend dry-run export-path]
       :or {db-path "data/kg/datahike-migrated"
            backend :file}}]]
  (let [source-backend (detect-current-backend)]
    (migrate-store! source-backend :datahike
                    {:target-opts {:db-path db-path :backend backend}
                     :dry-run dry-run
                     :export-path export-path})))

(defn migrate-to-datalevin!
  "Convenience function to migrate from any backend to Datalevin.

   Arguments:
     opts - Options map:
       :db-path     - Datalevin storage path (default: data/kg/datalevin-migrated)
       :dry-run     - Preview without migrating
       :export-path - Optional path to save EDN backup

   Returns migration result map."
  [& [{:keys [db-path dry-run export-path]
       :or {db-path "data/kg/datalevin-migrated"}}]]
  (let [source-backend (detect-current-backend)]
    (migrate-store! source-backend :datalevin
                    {:target-opts {:db-path db-path}
                     :dry-run dry-run
                     :export-path export-path})))

(defn sync-to-backend!
  "Sync current KG data to a secondary backend without switching.
   Useful for maintaining backup/mirror stores.

   Arguments:
     target-backend - :datascript, :datalevin, or :datahike
     opts           - Backend-specific options + :export-path

   Returns:
     {:target-backend keyword
      :exported       {:edges N :disc N :synthetic N}
      :imported       {:edges N :disc N :synthetic N}
      :validation     {...}}"
  [target-backend & [{:keys [target-opts export-path]}]]
  (log/info "Syncing KG to secondary backend" {:target target-backend})

  ;; Export from current store
  (let [export-data (export-to-edn)
        exported-counts (:counts export-data)
        ;; Save backup if requested
        _ (when export-path
            (let [parent-dir (.getParentFile (io/file export-path))]
              (when (and parent-dir (not (.exists parent-dir)))
                (.mkdirs parent-dir)))
            (spit export-path (pr-str export-data)))
        ;; Create target store (without switching global store)
        target-store (create-target-store target-backend target-opts)
        _ (proto/ensure-conn! target-store)]

    ;; Import to target store directly (bypass global conn)
    (log/info "Importing to sync target" {:target target-backend})
    (let [errors (atom [])
          imported (atom {:edges 0 :disc 0 :synthetic 0})
          target-transact! (fn [tx-data] (proto/transact! target-store tx-data))]
      (import-entities! (:disc export-data) :disc target-transact! imported errors)
      (import-entities! (:synthetic export-data) :synthetic target-transact! imported errors)
      (import-entities! (:edges export-data) :edges target-transact! imported errors)

      ;; Validate on target
      (let [target-counts {:edges (count (proto/query target-store
                                                      '[:find [(pull ?e [*]) ...]
                                                        :where [?e :kg-edge/id]]))
                           :disc (count (proto/query target-store
                                                     '[:find [(pull ?e [*]) ...]
                                                       :where [?e :disc/path]]))
                           :synthetic (count (proto/query target-store
                                                          '[:find [(pull ?e [*]) ...]
                                                            :where [?e :kg-synthetic/id]]))}
            valid? (and (= (:edges exported-counts) (:edges target-counts))
                        (= (:disc exported-counts) (:disc target-counts))
                        (= (:synthetic exported-counts) (:synthetic target-counts)))]

        (log/info "Sync complete" {:target target-backend
                                   :imported @imported
                                   :valid? valid?})

        {:source-backend (detect-current-backend)
         :target-backend target-backend
         :exported exported-counts
         :imported @imported
         :errors @errors
         :validation {:valid? valid?
                      :expected exported-counts
                      :actual target-counts}
         :target-store target-store}))))
