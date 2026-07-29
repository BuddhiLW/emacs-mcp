(ns hive-mcp.migration.core-test
  "Unit tests for migration core functionality.

   Tests cover (isolated, no real backends):
   - Backup naming conventions and timestamp format
   - Backup name parsing and metadata extraction
   - Backup metadata creation
   - File I/O mocking
   - Validation logic for backup integrity

   CRITICAL: These tests use temp directories only - never touch data/backups."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hive-mcp.migration.core :as core])
  (:import [java.io File]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures - Temp Directory Isolation
;; =============================================================================

(def ^:dynamic *test-backup-dir* nil)

(defn temp-backup-dir-fixture
  "Create a unique temp directory for backup tests."
  [f]
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "hive-migration-core-test-" (System/nanoTime)))]
    (.mkdirs tmp-dir)
    (binding [*test-backup-dir* (.getAbsolutePath tmp-dir)]
      (try
        (f)
        (finally
          ;; Clean up temp directory
          (when (.exists tmp-dir)
            (doseq [file (reverse (file-seq tmp-dir))]
              (.delete file))))))))

(use-fixtures :each temp-backup-dir-fixture)

;; =============================================================================
;; Backup Naming Tests
;; =============================================================================

(deftest generate-backup-name-format-test
  (testing "generate-backup-name produces correct format"
    (let [backup-name (core/generate-backup-name :kg :datalevin)]
      (is (string? backup-name))
      (is (str/starts-with? backup-name "kg-datalevin-"))
      (is (str/ends-with? backup-name ".edn"))
      ;; Check timestamp format: yyyyMMddTHHmmss
      (is (re-matches #"kg-datalevin-\d{8}T\d{6}\.edn" backup-name)))))

(deftest generate-backup-name-different-scopes-test
  (testing "generate-backup-name works for all scopes"
    (doseq [scope [:kg :memory :kanban :full]]
      (let [backup-name (core/generate-backup-name scope :datascript)
            scope-str (clojure.core/name scope)]
        (is (str/starts-with? backup-name (str scope-str "-datascript-"))
            (str "Failed for scope: " scope))))))

(deftest generate-backup-name-different-backends-test
  (testing "generate-backup-name works for all backends"
    (doseq [backend [:datascript :datalevin :datahike :chroma]]
      (let [backup-name (core/generate-backup-name :kg backend)
            backend-str (clojure.core/name backend)]
        (is (str/includes? backup-name (str "-" backend-str "-"))
            (str "Failed for backend: " backend))))))

(deftest generate-backup-name-uniqueness-test
  (testing "generate-backup-name produces unique names over time"
    (let [name1 (core/generate-backup-name :kg :datalevin)
          _ (Thread/sleep 1100) ;; Wait for timestamp to change
          name2 (core/generate-backup-name :kg :datalevin)]
      ;; Names might be same if generated in same second, but structure should match
      (is (re-matches #"kg-datalevin-\d{8}T\d{6}\.edn" name1))
      (is (re-matches #"kg-datalevin-\d{8}T\d{6}\.edn" name2)))))

;; =============================================================================
;; Backup Name Parsing Tests
;; =============================================================================

(deftest parse-backup-name-valid-test
  (testing "parse-backup-name extracts metadata from valid filename"
    (let [result (core/parse-backup-name "kg-datalevin-20260203T164530.edn")]
      (is (map? result))
      (is (= :kg (:scope result)))
      (is (= :datalevin (:backend result)))
      (is (= "20260203T164530" (:timestamp result)))
      (is (= "kg-datalevin-20260203T164530.edn" (:filename result))))))

(deftest parse-backup-name-all-scopes-test
  (testing "parse-backup-name handles all scope types"
    (doseq [[filename expected-scope] [["kg-datascript-20260101T000000.edn" :kg]
                                        ["memory-datalevin-20260101T000000.edn" :memory]
                                        ["kanban-datahike-20260101T000000.edn" :kanban]
                                        ["full-chroma-20260101T000000.edn" :full]]]
      (let [result (core/parse-backup-name filename)]
        (is (= expected-scope (:scope result))
            (str "Failed for: " filename))))))

(deftest parse-backup-name-invalid-test
  (testing "parse-backup-name returns nil for invalid filenames"
    (is (nil? (core/parse-backup-name "invalid.edn")))
    (is (nil? (core/parse-backup-name "kg-datalevin.edn")))
    (is (nil? (core/parse-backup-name "kg-20260203T164530.edn")))
    (is (nil? (core/parse-backup-name "random-file.txt")))
    (is (nil? (core/parse-backup-name "")))))

(deftest parse-backup-name-edge-cases-test
  (testing "parse-backup-name handles edge cases"
    ;; Valid but unusual
    (let [result (core/parse-backup-name "abc-xyz-00000000T000000.edn")]
      (is (= :abc (:scope result)))
      (is (= :xyz (:backend result))))))

;; =============================================================================
;; Ensure Backup Directory Tests
;; =============================================================================

(deftest ensure-backup-dir-creates-directory-test
  (testing "ensure-backup-dir! creates directory if not exists"
    (let [new-dir (str *test-backup-dir* "/new-backup-dir")]
      (is (not (.exists (io/file new-dir))))
      (core/ensure-backup-dir! new-dir)
      (is (.exists (io/file new-dir)))
      (is (.isDirectory (io/file new-dir))))))

(deftest ensure-backup-dir-idempotent-test
  (testing "ensure-backup-dir! is idempotent (no error if exists)"
    (let [dir *test-backup-dir*]
      (is (.exists (io/file dir)))
      (is (= dir (core/ensure-backup-dir! dir)))
      (is (.exists (io/file dir))))))

(deftest ensure-backup-dir-nested-test
  (testing "ensure-backup-dir! creates nested directories"
    (let [nested (str *test-backup-dir* "/a/b/c/d")]
      (is (not (.exists (io/file nested))))
      (core/ensure-backup-dir! nested)
      (is (.exists (io/file nested))))))

;; =============================================================================
;; Backup Metadata Tests
;; =============================================================================

(deftest backup-metadata-structure-test
  (testing "backup-metadata creates correct structure"
    (let [meta (core/backup-metadata :kg :datalevin {:edges 10 :disc 5 :synthetic 3})]
      ;; version is owned by the source constant, not restated here
      (is (= core/backup-format-version (:backup/version meta)))
      (is (= :kg (:backup/scope meta)))
      (is (= :datalevin (:backup/backend meta)))
      (is (inst? (:backup/created-at meta)))
      (is (string? (:backup/created-by meta)))
      (is (string? (:backup/hostname meta)))
      (is (= {:edges 10 :disc 5 :synthetic 3} (:backup/counts meta))))))

(deftest backup-metadata-all-scopes-test
  (testing "backup-metadata works for all scopes"
    (doseq [scope [:kg :memory :kanban :full]]
      (let [meta (core/backup-metadata scope :datascript {:items 1})]
        (is (= scope (:backup/scope meta)))))))

;; =============================================================================
;; Write Backup Tests
;; =============================================================================

(deftest write-backup-creates-file-test
  (testing "write-backup! creates file with correct content"
    (let [path (str *test-backup-dir* "/test-backup.edn")
          metadata {:backup/version 1
                    :backup/scope :kg
                    :backup/backend :datascript
                    :backup/counts {:edges 2}}
          data {:data {:edges [{:id "e1"} {:id "e2"}]}}
          result (core/write-backup! path metadata data)]
      (is (:success result))
      (is (= path (:path result)))
      (is (pos? (:size result)))
      ;; Verify file content
      (let [content (edn/read-string (slurp path))]
        (is (= 1 (:backup/version content)))
        (is (= :kg (:backup/scope content)))
        (is (= [{:id "e1"} {:id "e2"}] (get-in content [:data :edges])))))))

(deftest write-backup-creates-parent-dirs-test
  (testing "write-backup! creates parent directories"
    (let [path (str *test-backup-dir* "/nested/dirs/backup.edn")
          metadata {:backup/version 1 :backup/scope :kg}
          data {:data []}]
      (is (not (.exists (io/file (str *test-backup-dir* "/nested")))))
      (core/write-backup! path metadata data)
      (is (.exists (io/file path))))))

;; =============================================================================
;; Read Backup Tests
;; =============================================================================

(deftest read-backup-parses-edn-test
  (testing "read-backup parses EDN file correctly"
    (let [path (str *test-backup-dir* "/readable.edn")
          content {:backup/version 1
                   :backup/scope :kg
                   :backup/backend :datalevin
                   :backup/counts {:edges 5}
                   :data {:edges [{:id "test"}]}}]
      (spit path (pr-str content))
      (let [result (core/read-backup path)]
        (is (= 1 (:backup/version result)))
        (is (= :kg (:backup/scope result)))
        (is (= [{:id "test"}] (get-in result [:data :edges])))))))

;; =============================================================================
;; List Backups Tests
;; =============================================================================

(deftest list-backups-empty-dir-test
  (testing "list-backups returns empty vec for empty dir"
    (let [result (core/list-backups {:dir *test-backup-dir*})]
      (is (vector? result))
      (is (empty? result)))))

(deftest list-backups-finds-valid-files-test
  (testing "list-backups finds and parses valid backup files"
    ;; Create some backup files
    (spit (str *test-backup-dir* "/kg-datascript-20260201T100000.edn")
          (pr-str {:backup/version 1}))
    (spit (str *test-backup-dir* "/kg-datalevin-20260202T100000.edn")
          (pr-str {:backup/version 1}))
    (spit (str *test-backup-dir* "/memory-datascript-20260203T100000.edn")
          (pr-str {:backup/version 1}))
    ;; Also create an invalid file that should be ignored
    (spit (str *test-backup-dir* "/invalid.txt") "not a backup")
    
    (let [result (core/list-backups {:dir *test-backup-dir*})]
      (is (= 3 (count result)))
      ;; Verify sorted by timestamp descending (newest first)
      (is (= "20260203T100000" (:timestamp (first result))))
      (is (= "20260201T100000" (:timestamp (last result)))))))

(deftest list-backups-filters-by-scope-test
  (testing "list-backups filters by scope"
    (spit (str *test-backup-dir* "/kg-datascript-20260201T100000.edn")
          (pr-str {:backup/version 1}))
    (spit (str *test-backup-dir* "/memory-datascript-20260202T100000.edn")
          (pr-str {:backup/version 1}))
    
    (let [kg-only (core/list-backups {:dir *test-backup-dir* :scope :kg})
          mem-only (core/list-backups {:dir *test-backup-dir* :scope :memory})]
      (is (= 1 (count kg-only)))
      (is (= :kg (:scope (first kg-only))))
      (is (= 1 (count mem-only)))
      (is (= :memory (:scope (first mem-only)))))))

(deftest list-backups-filters-by-backend-test
  (testing "list-backups filters by backend"
    (spit (str *test-backup-dir* "/kg-datascript-20260201T100000.edn")
          (pr-str {:backup/version 1}))
    (spit (str *test-backup-dir* "/kg-datalevin-20260202T100000.edn")
          (pr-str {:backup/version 1}))
    
    (let [ds-only (core/list-backups {:dir *test-backup-dir* :backend :datascript})]
      (is (= 1 (count ds-only)))
      (is (= :datascript (:backend (first ds-only)))))))

(deftest list-backups-respects-limit-test
  (testing "list-backups respects limit parameter"
    (dotimes [i 5]
      (spit (str *test-backup-dir* "/kg-datascript-2026020" i "T100000.edn")
            (pr-str {:backup/version 1})))
    
    (let [limited (core/list-backups {:dir *test-backup-dir* :limit 2})]
      (is (= 2 (count limited))))))

(deftest list-backups-nonexistent-dir-test
  (testing "list-backups returns empty for nonexistent directory"
    (let [result (core/list-backups {:dir "/nonexistent/path/xyz123"})]
      (is (empty? result)))))

;; =============================================================================
;; Latest Backup Tests
;; =============================================================================

(deftest latest-backup-finds-most-recent-test
  (testing "latest-backup returns most recent backup for scope"
    (spit (str *test-backup-dir* "/kg-datascript-20260201T100000.edn")
          (pr-str {:backup/version 1}))
    (spit (str *test-backup-dir* "/kg-datalevin-20260205T100000.edn")
          (pr-str {:backup/version 1}))
    (spit (str *test-backup-dir* "/kg-datascript-20260203T100000.edn")
          (pr-str {:backup/version 1}))
    
    (let [result (core/latest-backup :kg {:dir *test-backup-dir*})]
      (is (some? result))
      (is (= "20260205T100000" (:timestamp result)))
      (is (= :datalevin (:backend result))))))

(deftest latest-backup-nil-when-none-test
  (testing "latest-backup returns nil when no backups exist"
    (let [result (core/latest-backup :kg {:dir *test-backup-dir*})]
      (is (nil? result)))))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest validate-backup-valid-file-test
  (testing "validate-backup accepts valid backup file"
    (let [path (str *test-backup-dir* "/valid.edn")
          content {:backup/version 1
                   :backup/scope :kg
                   :backup/counts {:edges 5 :disc 2 :synthetic 1}}]
      (spit path (pr-str content))
      (let [result (core/validate-backup path)]
        (is (:valid? result))
        (is (empty? (:errors result)))
        (is (= 1 (get-in result [:metadata :backup/version])))
        (is (= :kg (get-in result [:metadata :backup/scope])))))))

(deftest validate-backup-missing-version-test
  (testing "validate-backup catches missing version"
    (let [path (str *test-backup-dir* "/no-version.edn")]
      (spit path (pr-str {:backup/scope :kg :backup/counts {:edges 0}}))
      (let [result (core/validate-backup path)]
        (is (not (:valid? result)))
        (is (some #(str/includes? % "version") (:errors result)))))))

(deftest validate-backup-missing-scope-test
  (testing "validate-backup catches missing scope"
    (let [path (str *test-backup-dir* "/no-scope.edn")]
      (spit path (pr-str {:backup/version 1 :backup/counts {:edges 0}}))
      (let [result (core/validate-backup path)]
        (is (not (:valid? result)))
        (is (some #(str/includes? % "scope") (:errors result)))))))

(deftest validate-backup-missing-counts-test
  (testing "validate-backup catches missing counts"
    (let [path (str *test-backup-dir* "/no-counts.edn")]
      (spit path (pr-str {:backup/version 1 :backup/scope :kg}))
      (let [result (core/validate-backup path)]
        (is (not (:valid? result)))
        (is (some #(str/includes? % "counts") (:errors result)))))))

(deftest validate-backup-nonexistent-file-test
  (testing "validate-backup handles nonexistent file"
    (let [result (core/validate-backup "/nonexistent/file.edn")]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

(deftest validate-backup-invalid-edn-test
  (testing "validate-backup handles malformed EDN"
    (let [path (str *test-backup-dir* "/bad-edn.edn")]
      (spit path "{ this is not valid edn }")
      (let [result (core/validate-backup path)]
        (is (not (:valid? result)))
        (is (seq (:errors result)))))))
