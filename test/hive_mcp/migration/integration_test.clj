(ns hive-mcp.migration.integration-test
  "Integration tests for migration system with real backends.

   CRITICAL CONSTRAINTS:
   - All backends use TEMPORARY directories under java.io.tmpdir
   - Tests are idempotent and clean up after themselves
   - NEVER touch production data in data/ directory
   - Each test runs in complete isolation

   Tests cover:
   - DataScript -> Datalevin migration cycle
   - Datalevin -> Datahike migration cycle
   - Backup -> Restore roundtrip
   - Export EDN -> Import EDN roundtrip
   - Export JSON -> Import JSON roundtrip
   - Data integrity verification after each migration"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [hive-mcp.migration.core :as core]
            [hive-mcp.migration.adapter :as adapter]
            [hive-mcp.knowledge-graph.migration :as kg-mig]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.datascript :as ds-store]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods])
  (:import [java.util UUID]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test State & Fixtures
;; =============================================================================

(def ^:dynamic *test-root* nil)
(def ^:dynamic *backup-dir* nil)
(def ^:dynamic *datalevin-dir* nil)
(def ^:dynamic *datahike-dir* nil)

(defn- delete-dir-recursive!
  "Recursively delete directory and all contents."
  [dir]
  (when (.exists dir)
    (doseq [file (reverse (file-seq dir))]
      (try
        (.delete file)
        (catch Exception _ nil)))))

(defn- gen-test-uuid []
  (str (UUID/randomUUID)))

(defn integration-test-fixture
  "Create isolated temp directories for each test.

   Structure:
   /tmp/hive-migration-integration-{uuid}/
     ├── backups/
     ├── datalevin/
     └── datahike/

   Binds *test-store* to a fresh DataScript store so the override-aware
   ensure-store! returns it without touching the global proto store."
  [f]
  (let [uuid (gen-test-uuid)
        root (io/file (System/getProperty "java.io.tmpdir")
                      (str "hive-migration-integration-" uuid))
        backup-dir (io/file root "backups")
        datalevin-dir (io/file root "datalevin")
        datahike-dir (io/file root "datahike")
        store (ds-store/create-store)
        test-store-var (requiring-resolve
                         'hive-mcp.knowledge-graph.connection.store/*test-store*)]
    (.mkdirs backup-dir)
    (.mkdirs datalevin-dir)
    (.mkdirs datahike-dir)
    (proto/ensure-conn! store)
    (binding [*test-root* (.getAbsolutePath root)
              *backup-dir* (.getAbsolutePath backup-dir)
              *datalevin-dir* (.getAbsolutePath datalevin-dir)
              *datahike-dir* (.getAbsolutePath datahike-dir)]
      (with-bindings* {test-store-var store}
        (fn []
          (try
            (f)
            (finally
              (try (proto/close! store) (catch Exception _ nil))
              (delete-dir-recursive! root))))))))

(use-fixtures :each integration-test-fixture)

;; =============================================================================
;; Test Data Helpers
;; =============================================================================

(defn- create-test-edge!
  "Create a test KG edge."
  [id from to relation & [confidence]]
  (conn/transact! [{:kg-edge/id id
                    :kg-edge/from from
                    :kg-edge/to to
                    :kg-edge/relation relation
                    :kg-edge/confidence (or confidence 0.9)
                    :kg-edge/created-at (java.util.Date.)}]))

(defn- create-test-disc!
  "Create a test disc entity."
  [path content-hash]
  (conn/transact! [{:disc/path path
                    :disc/content-hash content-hash
                    :disc/analyzed-at (java.util.Date.)
                    :disc/certainty-alpha 5.0
                    :disc/certainty-beta 2.0}]))

(defn- create-test-synthetic!
  "Create a test synthetic node."
  [id synth-type members]
  (conn/transact! [{:kg-synthetic/id id
                    :kg-synthetic/type synth-type
                    :kg-synthetic/members (set members)
                    :kg-synthetic/confidence 0.8
                    :kg-synthetic/created-at (java.util.Date.)}]))

(defn- create-standard-test-data!
  "Create a standard set of test data for migration tests."
  []
  (create-test-edge! "edge-1" "entry-a" "entry-b" :implements 0.95)
  (create-test-edge! "edge-2" "entry-b" "entry-c" :depends-on 0.8)
  (create-test-edge! "edge-3" "entry-c" "entry-a" :refines 0.7)
  (create-test-disc! "/src/core.clj" "abc123hash")
  (create-test-disc! "/src/utils.clj" "def456hash")
  (create-test-synthetic! "synth-1" :co-access #{"entry-a" "entry-b"})
  {:expected-counts {:edges 3 :disc 2 :synthetic 1}})

(defn- verify-data-integrity
  "Verify that all expected data exists in current store."
  [expected-counts]
  (let [edges (conn/query '[:find [(pull ?e [*]) ...]
                            :where [?e :kg-edge/id]])
        discs (conn/query '[:find [(pull ?e [*]) ...]
                            :where [?e :disc/path]])
        synths (conn/query '[:find [(pull ?e [*]) ...]
                             :where [?e :kg-synthetic/id]])]
    {:valid? (and (= (:edges expected-counts) (count edges))
                  (= (:disc expected-counts) (count discs))
                  (= (:synthetic expected-counts) (count synths)))
     :actual {:edges (count edges)
              :disc (count discs)
              :synthetic (count synths)}
     :expected expected-counts}))

;; =============================================================================
;; Export/Import EDN Roundtrip Tests
;; =============================================================================

(deftest export-import-edn-roundtrip-test
  (testing "Export to EDN and import back preserves all data"
    (let [{:keys [expected-counts]} (create-standard-test-data!)
          ;; Export
          export-data (kg-mig/export-to-edn)]

      (is (= 3 (count (:edges export-data))))
      (is (= 2 (count (:disc export-data))))
      (is (= 1 (count (:synthetic export-data))))

      ;; Reset store (simulates new database)
      (conn/reset-conn!)

      ;; Verify empty
      (is (= 0 (count (conn/query '[:find ?e :where [?e :kg-edge/id]]))))

      ;; Import
      (let [import-result (kg-mig/import-from-edn export-data)]
        (is (empty? (:errors import-result)))
        (is (= 3 (:edges (:imported import-result))))
        (is (= 2 (:disc (:imported import-result))))
        (is (= 1 (:synthetic (:imported import-result)))))

      ;; Verify integrity
      (let [integrity (verify-data-integrity expected-counts)]
        (is (:valid? integrity)
            (str "Integrity check failed: " integrity))))))

(deftest export-import-file-roundtrip-test
  (testing "Export to file and import from file preserves all data"
    (let [{:keys [expected-counts]} (create-standard-test-data!)
          export-path (str *backup-dir* "/file-roundtrip.edn")]

      ;; Export to file
      (kg-mig/export-to-file! export-path)
      (is (.exists (io/file export-path)))

      ;; Verify file is valid EDN
      (let [file-content (edn/read-string (slurp export-path))]
        (is (map? file-content))
        (is (= 3 (count (:edges file-content)))))

      ;; Reset store
      (conn/reset-conn!)

      ;; Import from file
      (let [result (kg-mig/import-from-file! export-path)]
        (is (empty? (:errors result))))

      ;; Verify integrity
      (let [integrity (verify-data-integrity expected-counts)]
        (is (:valid? integrity))))))

;; =============================================================================
;; Backup/Restore Roundtrip Tests  
;; =============================================================================

(deftest backup-restore-roundtrip-test
  (testing "Full backup and restore cycle preserves all data"
    (let [{:keys [expected-counts]} (create-standard-test-data!)
          ;; Create backup using migration core
          export-data (kg-mig/export-to-edn)
          filename (core/generate-backup-name :kg :datascript)
          backup-path (str *backup-dir* "/" filename)
          metadata (core/backup-metadata :kg :datascript (:counts export-data))]

      ;; Write backup
      (core/write-backup! backup-path metadata {:data export-data})
      (is (.exists (io/file backup-path)))

      ;; Validate backup
      (let [validation (core/validate-backup backup-path)]
        (is (:valid? validation)
            (str "Backup validation failed: " (:errors validation))))

      ;; Reset store
      (conn/reset-conn!)

      ;; Read and restore
      (let [backup-data (core/read-backup backup-path)
            import-result (kg-mig/import-from-edn (:data backup-data))]
        (is (empty? (:errors import-result))))

      ;; Verify integrity
      (let [integrity (verify-data-integrity expected-counts)]
        (is (:valid? integrity))))))

(deftest backup-list-and-latest-test
  (testing "Multiple backups can be listed and latest found"
    (create-standard-test-data!)

    ;; Create multiple backups with different timestamps
    (let [export-data (kg-mig/export-to-edn)]
      (doseq [i (range 3)]
        (let [filename (str "kg-datascript-2026020" (inc i) "T100000.edn")
              path (str *backup-dir* "/" filename)
              metadata (core/backup-metadata :kg :datascript (:counts export-data))]
          (core/write-backup! path metadata {:data export-data})
          (Thread/sleep 10))))  ;; Ensure different mtimes

    ;; List backups
    (let [backups (core/list-backups {:dir *backup-dir*})]
      (is (= 3 (count backups)))
      ;; Should be sorted by timestamp descending
      (is (= "20260203T100000" (:timestamp (first backups)))))

    ;; Get latest
    (let [latest (core/latest-backup :kg {:dir *backup-dir*})]
      (is (some? latest))
      (is (= "20260203T100000" (:timestamp latest))))))

;; =============================================================================
;; JSON Adapter Roundtrip Tests
;; =============================================================================

(deftest json-adapter-roundtrip-test
  (testing "JSON adapter transforms data structure"
    ;; Note: The JSON adapter converts keywords to strings and back
    ;; This test verifies the adapter works without crashing
    (let [export-data (kg-mig/export-to-edn)
          ;; Transform to JSON format
          json-data (adapter/transform-export :json export-data)]

      ;; Verify export transformed keywords to strings
      (is (not-any? keyword? (keys json-data)))
      ;; Verify the map structure is preserved
      (is (map? json-data))
      (is (pos? (count json-data)))

      ;; Import transforms matching strings back to keywords
      (let [imported (adapter/transform-import :json json-data)]
        (is (map? imported))
        ;; The map should have the same number of top-level keys
        (is (= (count json-data) (count imported)))))))

;; =============================================================================
;; DataScript -> Datalevin Migration Tests
;; =============================================================================

(deftest datascript-to-datalevin-migration-test
  (testing "Migration from DataScript to Datalevin preserves all data"
    (let [{:keys [expected-counts]} (create-standard-test-data!)]
      (try
        ;; Perform migration
        (let [result (kg-mig/migrate-store! :datascript :datalevin
                                            {:target-opts {:db-path *datalevin-dir*}
                                             :dry-run false})]
          (is (= :datascript (:source-backend result)))
          (is (= :datalevin (:target-backend result)))
          (is (empty? (:errors result)))
          (is (:valid? (:validation result)))

          ;; Verify data exists in new store
          (let [integrity (verify-data-integrity expected-counts)]
            (is (:valid? integrity)
                (str "Migration integrity check failed: " integrity))))

        (catch Exception e
          ;; Datalevin might not be available - skip test
          (if (re-find #"Could not locate|No implementation" (str e))
            (println "Datalevin not available, skipping migration test")
            (throw e)))

        (finally
          ;; Clean up - close and switch back to DataScript
          (try
            (when (proto/store-set?)
              (proto/close! (proto/get-store)))
            (let [store (ds-store/create-store)]
              (proto/set-store! store)
              (proto/ensure-conn! store))
            (catch Exception _ nil)))))))

(deftest datascript-to-datalevin-dry-run-test
  (testing "Dry run migration exports without changing backend"
    (let [{:keys [expected-counts]} (create-standard-test-data!)
          original-backend (kg-mig/detect-current-backend)]

      ;; Dry run
      (let [result (kg-mig/migrate-store! :datascript :datalevin
                                          {:dry-run true})]
        (is (:dry-run result))
        (is (= expected-counts (:exported result)))
        (is (nil? (:imported result)))
        (is (nil? (:validation result))))

      ;; Backend should be unchanged
      (is (= original-backend (kg-mig/detect-current-backend)))

      ;; Data should still exist
      (let [integrity (verify-data-integrity expected-counts)]
        (is (:valid? integrity))))))

;; =============================================================================
;; DataScript -> Datahike Migration Tests
;; =============================================================================

(deftest datascript-to-datahike-migration-test
  (testing "Migration from DataScript to Datahike preserves all data"
    (let [{:keys [expected-counts]} (create-standard-test-data!)]
      (try
        ;; Perform migration
        (let [result (kg-mig/migrate-to-datahike!
                      {:db-path *datahike-dir*
                       :dry-run false})]
          (is (= :datascript (:source-backend result)))
          (is (= :datahike (:target-backend result)))
          (is (empty? (:errors result)))
          (is (:valid? (:validation result)))

          ;; Verify current backend is now Datahike
          (is (= :datahike (kg-mig/detect-current-backend)))

          ;; Verify data exists
          (let [integrity (verify-data-integrity expected-counts)]
            (is (:valid? integrity))))

        (catch Exception e
          (if (re-find #"Could not locate|No implementation|already exists" (str e))
            (println "Datahike not available or store exists, skipping migration test")
            (throw e)))

        (finally
          (try
            (when (proto/store-set?)
              (proto/close! (proto/get-store)))
            (let [store (ds-store/create-store)]
              (proto/set-store! store)
              (proto/ensure-conn! store))
            (catch Exception _ nil)))))))

;; =============================================================================
;; Sync to Secondary Backend Tests
;; =============================================================================

(deftest sync-to-datalevin-test
  (testing "Sync to Datalevin without switching primary backend"
    (let [{:keys [expected-counts]} (create-standard-test-data!)
          original-backend (kg-mig/detect-current-backend)]
      (try
        ;; Sync to Datalevin (secondary)
        (let [result (kg-mig/sync-to-backend! :datalevin
                                              {:target-opts {:db-path *datalevin-dir*}})]
          (is (= expected-counts (:exported result)))
          #_{:clj-kondo/ignore [:type-mismatch]}
          (is (:valid? (:validation result))))

        ;; Primary backend should be unchanged
        (is (= original-backend (kg-mig/detect-current-backend)))

        ;; Data should still exist in primary
        (let [integrity (verify-data-integrity expected-counts)]
          (is (:valid? integrity)))

        (catch Exception e
          (if (re-find #"Could not locate|No implementation" (str e))
            (println "Datalevin not available, skipping sync test")
            (throw e)))))))

;; =============================================================================
;; Migration Validation Tests
;; =============================================================================

(deftest validate-migration-complete-test
  (testing "Validation detects complete migration"
    (let [{:keys [expected-counts]} (create-standard-test-data!)
          validation (kg-mig/validate-migration expected-counts)]
      (is (:valid? validation))
      (is (= expected-counts (:expected validation)))
      (is (= expected-counts (:actual validation)))
      (is (= {:edges 0 :disc 0 :synthetic 0} (:missing validation))))))

(deftest validate-migration-incomplete-test
  (testing "Validation detects incomplete migration"
    (create-standard-test-data!)
    ;; Claim we expected more
    (let [validation (kg-mig/validate-migration {:edges 10 :disc 5 :synthetic 3})]
      (is (not (:valid? validation)))
      (is (= {:edges 10 :disc 5 :synthetic 3} (:expected validation)))
      (is (= {:edges 3 :disc 2 :synthetic 1} (:actual validation)))
      (is (= {:edges 7 :disc 3 :synthetic 2} (:missing validation))))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest empty-database-migration-test
  (testing "Migration of empty database succeeds"
    ;; Don't create any data
    (let [export-data (kg-mig/export-to-edn)]
      (is (= 0 (count (:edges export-data))))
      (is (= {:edges 0 :disc 0 :synthetic 0} (:counts export-data)))

      ;; Backup empty
      (let [backup-path (str *backup-dir* "/empty.edn")
            metadata (core/backup-metadata :kg :datascript {:edges 0 :disc 0 :synthetic 0})]
        (core/write-backup! backup-path metadata {:data export-data})

        ;; Validate and restore
        (let [validation (core/validate-backup backup-path)]
          (is (:valid? validation)))

        (conn/reset-conn!)

        (let [backup-data (core/read-backup backup-path)
              result (kg-mig/import-from-edn (:data backup-data))]
          (is (empty? (:errors result)))
          (is (= 0 (:edges (:imported result)))))))))

(deftest large-dataset-migration-test
  (testing "Migration handles larger dataset"
    ;; Create 100 edges, 50 disc, 10 synthetic
    (dotimes [i 100]
      (create-test-edge! (str "edge-" i) (str "from-" i) (str "to-" i) :implements))
    (dotimes [i 50]
      (create-test-disc! (str "/src/file-" i ".clj") (str "hash-" i)))
    (dotimes [i 10]
      (create-test-synthetic! (str "synth-" i) :co-access #{(str "a-" i) (str "b-" i)}))

    (let [expected {:edges 100 :disc 50 :synthetic 10}
          export-data (kg-mig/export-to-edn)]
      (is (= 100 (count (:edges export-data))))
      (is (= 50 (count (:disc export-data))))
      (is (= 10 (count (:synthetic export-data))))

      ;; Reset and import
      (conn/reset-conn!)
      (let [result (kg-mig/import-from-edn export-data)]
        (is (empty? (:errors result)))
        (is (= 100 (:edges (:imported result)))))

      ;; Verify
      (let [integrity (verify-data-integrity expected)]
        (is (:valid? integrity))))))

(deftest special-characters-in-data-test
  (testing "Migration preserves special characters in data"
    (create-test-edge! "edge-special" "entry/with/slashes" "entry:with:colons" :implements)
    (create-test-disc! "/path/to/special file (1).clj" "hash-special")
    (create-test-synthetic! "synth-special" :co-access #{"unicode-αβγ" "emoji-🎉"})

    (let [export-data (kg-mig/export-to-edn)]
      (conn/reset-conn!)
      (kg-mig/import-from-edn export-data)

      ;; Verify special characters preserved
      ;; Query returns tuples [[from to]]
      (let [edges (conn/query '[:find ?from ?to
                                :where
                                [?e :kg-edge/id "edge-special"]
                                [?e :kg-edge/from ?from]
                                [?e :kg-edge/to ?to]])]
        (is (= #{"entry/with/slashes" "entry:with:colons"}
               (set (first edges)))))

      (let [discs (conn/query '[:find [?path ...]
                                :where [?e :disc/path ?path]])]
        (is (some #(= "/path/to/special file (1).clj" %) discs))))))
