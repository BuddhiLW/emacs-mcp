(ns hive-mcp.tools.migration-test
  "Unit tests for migration tool handlers.

   Tests cover (mocked backends):
   - Command dispatch routing
   - Parameter validation
   - Help command
   - Adapter listing
   - Error handling for missing parameters

   CRITICAL: Uses with-redefs to mock KG operations - never touches real data."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [hive-mcp.tools.migration :as migration]
            [hive-mcp.migration.core :as core]
            [hive-mcp.migration.adapter :as adapter]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture
  "Create temp directory for file-based tests."
  [f]
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "hive-migration-tool-test-" (System/nanoTime)))]
    (.mkdirs tmp-dir)
    (binding [*test-dir* (.getAbsolutePath tmp-dir)]
      (try
        (f)
        (finally
          (when (.exists tmp-dir)
            (doseq [file (reverse (file-seq tmp-dir))]
              (.delete file))))))))

(use-fixtures :each temp-dir-fixture)

;; =============================================================================
;; Help Command Tests
;; =============================================================================

(deftest cmd-help-returns-commands-test
  (testing "cmd-help returns list of commands"
    (let [result (migration/cmd-help {})]
      (is (map? result))
      (is (contains? result :commands))
      (is (sequential? (:commands result)))
      (is (pos? (count (:commands result)))))))

(deftest cmd-help-includes-all-commands-test
  (testing "cmd-help includes all expected commands"
    (let [result (migration/cmd-help {})
          commands (set (map :command (:commands result)))]
      (is (contains? commands "status"))
      (is (contains? commands "backup"))
      (is (contains? commands "restore"))
      (is (contains? commands "list"))
      (is (contains? commands "switch"))
      (is (contains? commands "sync"))
      (is (contains? commands "export"))
      (is (contains? commands "import"))
      (is (contains? commands "validate"))
      (is (contains? commands "adapters")))))

(deftest cmd-help-has-descriptions-test
  (testing "cmd-help commands have descriptions"
    (let [result (migration/cmd-help {})]
      (doseq [cmd (:commands result)]
        (is (string? (:description cmd))
            (str "Missing description for: " (:command cmd)))))))

;; =============================================================================
;; Adapters Command Tests
;; =============================================================================

(deftest cmd-adapters-returns-list-test
  (testing "cmd-adapters returns adapter list"
    (let [result (migration/cmd-adapters {})]
      (is (map? result))
      (is (contains? result :adapters))
      (is (sequential? (:adapters result))))))

(deftest cmd-adapters-includes-builtin-test
  (testing "cmd-adapters includes built-in adapters"
    (let [result (migration/cmd-adapters {})
          ids (set (map :id (:adapters result)))]
      (is (contains? ids :edn))
      (is (contains? ids :json)))))

;; =============================================================================
;; List Command Tests
;; =============================================================================

(deftest cmd-list-returns-backups-test
  (testing "cmd-list returns backups structure"
    (let [result (migration/cmd-list {:dir *test-dir*})]
      (is (map? result))
      (is (contains? result :backups))
      (is (contains? result :count))
      (is (vector? (:backups result)))
      (is (number? (:count result))))))

(deftest cmd-list-finds-backups-test
  (testing "cmd-list finds backup files"
    ;; Create test backup files
    (spit (str *test-dir* "/kg-datascript-20260201T100000.edn")
          (pr-str {:backup/version 1}))
    (spit (str *test-dir* "/kg-datalevin-20260202T100000.edn")
          (pr-str {:backup/version 1}))

    (let [result (migration/cmd-list {:dir *test-dir*})]
      (is (= 2 (:count result)))
      (is (= 2 (count (:backups result)))))))

(deftest cmd-list-filters-by-scope-test
  (testing "cmd-list filters by scope parameter"
    (spit (str *test-dir* "/kg-datascript-20260201T100000.edn")
          (pr-str {:backup/version 1}))
    (spit (str *test-dir* "/memory-datascript-20260202T100000.edn")
          (pr-str {:backup/version 1}))

    (let [result (migration/cmd-list {:dir *test-dir* :scope :kg})]
      (is (= 1 (:count result)))
      (is (= :kg (:scope (first (:backups result))))))))

(deftest cmd-list-respects-limit-test
  (testing "cmd-list respects limit parameter"
    (dotimes [i 10]
      (spit (str *test-dir* "/kg-datascript-2026020" i "T100000.edn")
            (pr-str {:backup/version 1})))

    (let [result (migration/cmd-list {:dir *test-dir* :limit 3})]
      (is (= 3 (:count result))))))

;; =============================================================================
;; Validate Command Tests
;; =============================================================================

(deftest cmd-validate-valid-file-test
  (testing "cmd-validate accepts valid backup"
    (let [path (str *test-dir* "/valid-backup.edn")]
      (spit path (pr-str {:backup/version 1
                          :backup/scope :kg
                          :backup/counts {:edges 0}}))
      (let [result (migration/cmd-validate {:path path})]
        (is (:valid? result))
        (is (empty? (:errors result)))))))

(deftest cmd-validate-invalid-file-test
  (testing "cmd-validate rejects invalid backup"
    (let [path (str *test-dir* "/invalid-backup.edn")]
      (spit path (pr-str {:backup/version 1}))  ;; Missing scope and counts
      (let [result (migration/cmd-validate {:path path})]
        (is (not (:valid? result)))
        (is (seq (:errors result)))))))

(deftest cmd-validate-requires-path-test
  (testing "cmd-validate throws when path missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"path required"
                          (migration/cmd-validate {})))))

;; =============================================================================
;; Handle Migration Dispatcher Tests
;; =============================================================================

(deftest handle-migration-dispatches-help-test
  (testing "handle-migration dispatches to help"
    (let [result (migration/handle-migration {:command :help})]
      (is (contains? result :commands)))))

(deftest handle-migration-dispatches-adapters-test
  (testing "handle-migration dispatches to adapters"
    (let [result (migration/handle-migration {:command :adapters})]
      (is (contains? result :adapters)))))

(deftest handle-migration-dispatches-list-test
  (testing "handle-migration dispatches to list"
    (let [result (migration/handle-migration {:command :list :dir *test-dir*})]
      (is (contains? result :backups)))))

(deftest handle-migration-dispatches-validate-test
  (testing "handle-migration dispatches to validate"
    (let [path (str *test-dir* "/test.edn")]
      (spit path (pr-str {:backup/version 1 :backup/scope :kg :backup/counts {}}))
      (let [result (migration/handle-migration {:command :validate :path path})]
        (is (contains? result :valid?))))))

(deftest handle-migration-default-to-help-test
  (testing "handle-migration defaults to help for unknown command"
    (let [result (migration/handle-migration {:command :unknown})]
      (is (contains? result :commands)))))

(deftest handle-migration-string-command-test
  (testing "handle-migration accepts string command"
    (let [result (migration/handle-migration {:command "help"})]
      (is (contains? result :commands)))))

;; =============================================================================
;; Export Command Tests (Mocked)
;; =============================================================================

(deftest cmd-export-requires-path-test
  (testing "cmd-export throws when path missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"path required"
                          (migration/cmd-export {:scope :kg})))))

(deftest cmd-export-with-mock-test
  (testing "cmd-export exports data to file (mocked)"
    (let [path (str *test-dir* "/export-test.edn")]
      ;; Mock the KG export function
      (with-redefs [hive-mcp.knowledge-graph.migration/export-to-edn
                    (fn [] {:edges [{:id "e1"}]
                            :disc []
                            :synthetic []
                            :counts {:edges 1 :disc 0 :synthetic 0}})]
        (let [result (migration/cmd-export {:path path :scope :kg})]
          (is (:success result))
          (is (.exists (io/file path))))))))

;; =============================================================================
;; Import Command Tests (Mocked)
;; =============================================================================

(deftest cmd-import-requires-path-test
  (testing "cmd-import throws when path missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"path required"
                          (migration/cmd-import {})))))

(deftest cmd-import-dry-run-test
  (testing "cmd-import dry-run returns preview"
    (let [path (str *test-dir* "/import-test.edn")]
      (spit path (pr-str {:edges [{:id "e1"} {:id "e2"}]
                          :disc [{:path "/test"}]
                          :synthetic []}))
      (let [result (migration/cmd-import {:path path :dry-run true})]
        (is (:dry-run result))
        (is (= 2 (get-in result [:would-import :edges])))
        (is (= 1 (get-in result [:would-import :disc])))))))

;; =============================================================================
;; Backup Command Tests (Mocked)
;; =============================================================================

(deftest cmd-backup-with-mock-test
  (testing "cmd-backup creates backup file (mocked)"
    (with-redefs [hive-mcp.knowledge-graph.migration/export-to-edn
                  (fn [] {:edges [] :disc [] :synthetic []
                          :counts {:edges 0 :disc 0 :synthetic 0}})
                  hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datascript)]
      (let [result (migration/cmd-backup {:dir *test-dir* :scope :kg})]
        (is (:success result))
        (is (string? (:path result)))
        (is (.exists (io/file (:path result))))))))

(deftest cmd-backup-memory-scope-test
  (testing "cmd-backup :memory scope creates Chroma backup (mocked)"
    (with-redefs [hive-mcp.chroma.core/query-entries
                  (fn [& _] [{:id "e1" :type "note" :content "test" :tags ["test"]}
                             {:id "e2" :type "decision" :content "test2" :tags []}])]
      (let [result (migration/cmd-backup {:dir *test-dir* :scope :memory})]
        (is (:success result))
        (is (string? (:path result)))
        (is (.exists (io/file (:path result))))
        ;; Verify file structure
        (let [data (-> (:path result) slurp clojure.edn/read-string)]
          (is (= :memory (:backup/scope data)))
          (is (= :chroma (:backup/backend data)))
          (is (= 2 (get-in data [:data :total]))))))))

(deftest cmd-backup-full-scope-test
  (testing "cmd-backup :full scope creates combined KG + Memory backup (mocked)"
    (with-redefs [hive-mcp.knowledge-graph.migration/export-to-edn
                  (fn [] {:edges [{:id "kg1"}] :disc [] :synthetic []
                          :counts {:edges 1 :disc 0 :synthetic 0}})
                  hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datascript)
                  hive-mcp.chroma.core/query-entries
                  (fn [& _] [{:id "m1" :type "note" :content "mem" :tags []}])]
      (let [result (migration/cmd-backup {:dir *test-dir* :scope :full})]
        (is (:success result))
        (is (string? (:path result)))
        ;; Verify combined structure
        (let [data (-> (:path result) slurp clojure.edn/read-string)]
          (is (= :full (:backup/scope data)))
          (is (map? (get-in data [:backup/counts :kg])))
          (is (map? (get-in data [:backup/counts :memory])))
          (is (= 1 (count (get-in data [:data :kg :edges]))))
          (is (= 1 (count (get-in data [:data :memory :entries])))))))))

;; =============================================================================
;; Restore Command Tests
;; =============================================================================

(deftest cmd-restore-requires-path-or-latest-test
  (testing "cmd-restore throws when no path or latest specified"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No backup path"
                          (migration/cmd-restore {})))))

(deftest cmd-restore-dry-run-test
  (testing "cmd-restore dry-run returns metadata"
    (let [path (str *test-dir* "/restore-test.edn")]
      (spit path (pr-str {:backup/version 1
                          :backup/scope :kg
                          :backup/counts {:edges 5 :disc 2 :synthetic 1}
                          :data {:edges []}}))
      (let [result (migration/cmd-restore {:path path :dry-run true})]
        (is (:dry-run result))
        (is (= path (:path result)))
        (is (some? (:metadata result)))))))

(deftest cmd-restore-fails-invalid-backup-test
  (testing "cmd-restore fails on invalid backup"
    (let [path (str *test-dir* "/invalid-restore.edn")]
      (spit path (pr-str {:invalid "data"}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"validation failed"
                            (migration/cmd-restore {:path path}))))))

;; =============================================================================
;; Switch Command Tests
;; =============================================================================

(deftest cmd-switch-requires-target-test
  (testing "cmd-switch throws when target missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Target backend required"
                          (migration/cmd-switch {})))))

(deftest cmd-switch-dry-run-test
  (testing "cmd-switch dry-run returns preview (mocked)"
    (with-redefs [hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datascript)
                  hive-mcp.knowledge-graph.migration/export-to-edn
                  (fn [] {:edges [] :disc [] :synthetic []
                          :counts {:edges 0 :disc 0 :synthetic 0}})]
      (let [result (migration/cmd-switch {:target :datalevin :dry-run true})]
        (is (:dry-run result))
        (is (= :datascript (:from result)))
        (is (= :datalevin (:to result)))))))

(deftest cmd-switch-same-backend-throws-test
  (testing "cmd-switch throws when target equals current"
    (with-redefs [hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datalevin)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Already using target backend"
                            (migration/cmd-switch {:target :datalevin}))))))

;; =============================================================================
;; Sync Command Tests
;; =============================================================================

(deftest cmd-sync-requires-target-test
  (testing "cmd-sync throws when target missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Target backend required"
                          (migration/cmd-sync {})))))

;; =============================================================================
;; Project Scope Awareness Tests - Core
;; =============================================================================

(deftest backup-metadata-includes-project-id-test
  (testing "backup-metadata includes :backup/project-id when provided"
    (let [meta (core/backup-metadata :kg :datascript {:edges 5} "hive-mcp")]
      (is (= "hive-mcp" (:backup/project-id meta)))
      (is (= 2 (:backup/version meta)))))

  (testing "backup-metadata defaults to 'global' when no project-id"
    (let [meta (core/backup-metadata :kg :datascript {:edges 5})]
      (is (= "global" (:backup/project-id meta)))))

  (testing "backup-metadata defaults to 'global' when nil project-id"
    (let [meta (core/backup-metadata :kg :datascript {:edges 5} nil)]
      (is (= "global" (:backup/project-id meta))))))

(deftest check-scope-compatibility-same-project-test
  (testing "Same project is always compatible"
    (let [result (core/check-scope-compatibility
                  {:backup/project-id "hive-mcp"} "hive-mcp")]
      (is (:compatible? result))
      (is (= "hive-mcp" (:backup-project-id result)))
      (is (= "hive-mcp" (:target-project-id result))))))

(deftest check-scope-compatibility-global-backup-test
  (testing "Global backup is compatible with any project"
    (let [result (core/check-scope-compatibility
                  {:backup/project-id "global"} "hive-mcp")]
      (is (:compatible? result))
      (is (= "global" (:backup-project-id result))))))

(deftest check-scope-compatibility-v1-backup-test
  (testing "V1 backup (no project-id) is treated as global"
    (let [result (core/check-scope-compatibility
                  {:backup/version 1} "hive-mcp")]
      (is (:compatible? result))
      (is (= "global" (:backup-project-id result))))))

(deftest check-scope-compatibility-cross-project-test
  (testing "Cross-project is incompatible by default"
    (let [result (core/check-scope-compatibility
                  {:backup/project-id "project-a"} "project-b")]
      (is (not (:compatible? result)))
      (is (= "project-a" (:backup-project-id result)))
      (is (= "project-b" (:target-project-id result)))
      (is (string? (:reason result))))))

(deftest check-scope-compatibility-nil-target-test
  (testing "nil target-project-id skips scope check"
    (let [result (core/check-scope-compatibility
                  {:backup/project-id "project-a"} nil)]
      (is (:compatible? result)))))

;; =============================================================================
;; Project Scope Awareness Tests - Backup Command
;; =============================================================================

(deftest cmd-backup-includes-project-id-in-file-test
  (testing "cmd-backup includes project-id in backup file metadata"
    (with-redefs [hive-mcp.knowledge-graph.migration/export-to-edn
                  (fn [] {:edges [] :disc [] :synthetic []
                          :counts {:edges 0 :disc 0 :synthetic 0}})
                  hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datascript)]
      (let [result (migration/cmd-backup {:dir *test-dir*
                                          :scope :kg
                                          :project-id "my-project"})]
        (is (:success result))
        ;; Read back the file and check project-id
        (let [data (-> (:path result) slurp clojure.edn/read-string)]
          (is (= "my-project" (:backup/project-id data)))
          (is (= 2 (:backup/version data))))))))

(deftest cmd-backup-derives-project-id-from-directory-test
  (testing "cmd-backup derives project-id from directory param"
    (with-redefs [hive-mcp.knowledge-graph.migration/export-to-edn
                  (fn [] {:edges [] :disc [] :synthetic []
                          :counts {:edges 0 :disc 0 :synthetic 0}})
                  hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datascript)]
      (let [result (migration/cmd-backup {:dir *test-dir*
                                          :scope :kg
                                          :directory "/home/user/projects/cool-project"})]
        (is (:success result))
        (let [data (-> (:path result) slurp clojure.edn/read-string)]
          (is (= "cool-project" (:backup/project-id data))))))))

(deftest cmd-backup-defaults-to-global-without-directory-test
  (testing "cmd-backup defaults to 'global' when no directory or project-id"
    (with-redefs [hive-mcp.knowledge-graph.migration/export-to-edn
                  (fn [] {:edges [] :disc [] :synthetic []
                          :counts {:edges 0 :disc 0 :synthetic 0}})
                  hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datascript)]
      (let [result (migration/cmd-backup {:dir *test-dir* :scope :kg})]
        (is (:success result))
        (let [data (-> (:path result) slurp clojure.edn/read-string)]
          (is (= "global" (:backup/project-id data))))))))

;; =============================================================================
;; Project Scope Awareness Tests - List Command
;; =============================================================================

(deftest cmd-list-filters-by-project-id-test
  (testing "cmd-list filters backups by project-id"
    ;; Create two backups with different project-ids
    (spit (str *test-dir* "/kg-datascript-20260201T100000.edn")
          (pr-str {:backup/version 2
                   :backup/scope :kg
                   :backup/backend :datascript
                   :backup/project-id "project-a"
                   :backup/counts {:edges 0}}))
    (spit (str *test-dir* "/kg-datascript-20260202T100000.edn")
          (pr-str {:backup/version 2
                   :backup/scope :kg
                   :backup/backend :datascript
                   :backup/project-id "project-b"
                   :backup/counts {:edges 0}}))

    ;; Filter by project-a
    (let [result (migration/cmd-list {:dir *test-dir* :project-id "project-a"})]
      (is (= 1 (:count result)))
      (is (= "project-a" (:project-id-filter result))))

    ;; Filter by project-b
    (let [result (migration/cmd-list {:dir *test-dir* :project-id "project-b"})]
      (is (= 1 (:count result))))

    ;; No filter - shows all
    (let [result (migration/cmd-list {:dir *test-dir*})]
      (is (= 2 (:count result)))
      (is (not (contains? result :project-id-filter))))))

(deftest cmd-list-v1-backups-match-global-filter-test
  (testing "V1 backups (no project-id) are global-scoped and HCR-visible"
    (let [v1 (str *test-dir* "/kg-datascript-20260201T100000.edn")
          v2 (str *test-dir* "/kg-datascript-20260202T100000.edn")
          names #(set (map (fn [b] (.getName (io/file (:path b)))) (:backups %)))]
      ;; v1 backup: no :backup/project-id at all
      (spit v1 (pr-str {:backup/version 1
                        :backup/scope :kg
                        :backup/counts {:edges 0}}))
      ;; v2 backup: explicitly scoped to hive-mcp
      (spit v2 (pr-str {:backup/version 2
                        :backup/scope :kg
                        :backup/project-id "hive-mcp"
                        :backup/counts {:edges 0}}))

      ;; From "global" only the un-scoped v1 backup is visible: a
      ;; project-scoped backup does not leak up to its ancestor scope.
      (let [result (migration/cmd-list {:dir *test-dir* :project-id "global"})]
        (is (= #{(.getName (io/file v1))} (names result)))
        (is (= 1 (:count result)))
        (is (= "global" (:project-id-filter result))))

      ;; From "hive-mcp" both are visible — core/list-backups documents
      ;; hierarchical scope matching: a child scope inherits every ancestor's
      ;; backups, and "global" is the root ancestor of every project.
      (let [result (migration/cmd-list {:dir *test-dir* :project-id "hive-mcp"})]
        (is (= #{(.getName (io/file v1)) (.getName (io/file v2))} (names result)))
        (is (= 2 (:count result)))))))

;; =============================================================================
;; Project Scope Awareness Tests - Restore Command
;; =============================================================================

(deftest cmd-restore-dry-run-shows-scope-check-test
  (testing "cmd-restore dry-run shows scope compatibility info"
    (let [path (str *test-dir* "/restore-scope-test.edn")]
      (spit path (pr-str {:backup/version 2
                          :backup/scope :kg
                          :backup/project-id "hive-mcp"
                          :backup/counts {:edges 5}
                          :data {:edges []}}))
      (let [result (migration/cmd-restore {:path path
                                           :dry-run true
                                           :project-id "hive-mcp"})]
        (is (:dry-run result))
        (is (some? (:scope-check result)))
        (is (:compatible? (:scope-check result)))))))

(deftest cmd-restore-blocks-cross-project-test
  (testing "cmd-restore blocks cross-project restore by default"
    (let [path (str *test-dir* "/cross-project.edn")]
      (spit path (pr-str {:backup/version 2
                          :backup/scope :kg
                          :backup/project-id "project-a"
                          :backup/counts {:edges 5}
                          :data {:edges []}}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cross-project restore blocked"
                            (migration/cmd-restore {:path path
                                                    :project-id "project-b"}))))))

(deftest cmd-restore-allows-cross-project-when-forced-test
  (testing "cmd-restore allows cross-project when :force-cross-project is true"
    (let [path (str *test-dir* "/cross-project-forced.edn")]
      (spit path (pr-str {:backup/version 2
                          :backup/scope :kg
                          :backup/project-id "project-a"
                          :backup/counts {:edges 1}
                          :data {:edges [{:id "e1"}]}}))
      (with-redefs [hive-mcp.knowledge-graph.migration/import-from-edn
                    (fn [data] {:imported {:edges (count (:edges data))}
                                :errors []})]
        (let [result (migration/cmd-restore {:path path
                                             :project-id "project-b"
                                             :force-cross-project true})]
          (is (:success result))
          (is (= "project-b" (:project-id result)))
          (is (not (:compatible? (:scope-check result)))))))))

(deftest cmd-restore-allows-global-backup-to-any-project-test
  (testing "cmd-restore allows global backups to restore to any project"
    (let [path (str *test-dir* "/global-backup.edn")]
      (spit path (pr-str {:backup/version 2
                          :backup/scope :kg
                          :backup/project-id "global"
                          :backup/counts {:edges 1}
                          :data {:edges [{:id "e1"}]}}))
      (with-redefs [hive-mcp.knowledge-graph.migration/import-from-edn
                    (fn [data] {:imported {:edges (count (:edges data))}
                                :errors []})]
        (let [result (migration/cmd-restore {:path path
                                             :project-id "any-project"})]
          (is (:success result))
          (is (:compatible? (:scope-check result))))))))

(deftest cmd-restore-allows-v1-backup-to-any-project-test
  (testing "cmd-restore allows v1 backups (no project-id) to restore to any project"
    (let [path (str *test-dir* "/v1-backup.edn")]
      (spit path (pr-str {:backup/version 1
                          :backup/scope :kg
                          :backup/counts {:edges 1}
                          :data {:edges [{:id "e1"}]}}))
      (with-redefs [hive-mcp.knowledge-graph.migration/import-from-edn
                    (fn [data] {:imported {:edges (count (:edges data))}
                                :errors []})]
        (let [result (migration/cmd-restore {:path path
                                             :project-id "any-project"})]
          (is (:success result))
          (is (:compatible? (:scope-check result))))))))

;; =============================================================================
;; Project Scope Awareness Tests - Validate Command
;; =============================================================================

(deftest cmd-validate-includes-project-id-test
  (testing "cmd-validate includes project-id in metadata"
    (let [path (str *test-dir* "/v2-backup.edn")]
      (spit path (pr-str {:backup/version 2
                          :backup/scope :kg
                          :backup/project-id "hive-mcp"
                          :backup/counts {:edges 0}}))
      (let [result (migration/cmd-validate {:path path})]
        (is (:valid? result))
        (is (= "hive-mcp" (get-in result [:metadata :backup/project-id])))))))
