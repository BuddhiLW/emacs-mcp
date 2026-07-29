(ns hive-mcp.tools.consolidated.migration-test
  "Tests for consolidated migration CLI tool.

   Test Coverage:
   1. Tool definition - schema, name, consolidated flag, handler fn
   2. CLI handler routing - dispatches to correct handlers
   3. Help command - returns available commands listing
   4. Unknown command - returns error
   5. Handlers map - all commands wired, all fns
   6. Parameter pass-through - CLI handler passes params to underlying handlers

   CRITICAL: Uses with-redefs to mock KG/Chroma operations - never touches real data."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [hive-mcp.tools.consolidated.migration :as c-migration]
            [hive-mcp.tools.migration :as migration-handlers]
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
                         (str "hive-consolidated-migration-test-" (System/nanoTime)))]
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
;; Tool Definition Tests
;; =============================================================================

(deftest test-tool-def-structure
  (testing "tool-def has required fields"
    (is (= "migration" (:name c-migration/tool-def)))
    (is (string? (:description c-migration/tool-def)))
    (is (map? (:inputSchema c-migration/tool-def)))
    (is (fn? (:handler c-migration/tool-def)))))

(deftest test-tool-def-consolidated-flag
  (testing "tool-def has :consolidated true"
    (is (true? (:consolidated c-migration/tool-def)))))

(deftest test-tool-def-input-schema
  (testing "inputSchema has expected properties"
    (let [schema (:inputSchema c-migration/tool-def)
          props (:properties schema)]
      (is (= "object" (:type schema)))
      (is (contains? props "command"))
      (is (contains? props "scope"))
      (is (contains? props "dir"))
      (is (contains? props "adapter"))
      (is (contains? props "path"))
      (is (contains? props "latest"))
      (is (contains? props "dry-run"))
      (is (contains? props "target"))
      (is (contains? props "db-path"))
      (is (contains? props "backup"))
      (is (contains? props "backend"))
      (is (contains? props "limit"))
      (is (= ["command"] (:required schema))))))

(deftest test-tool-def-command-enum
  (testing "command enum includes all subcommands"
    (let [enum (get-in c-migration/tool-def [:inputSchema :properties "command" :enum])]
      (is (contains? (set enum) "status"))
      (is (contains? (set enum) "backup"))
      (is (contains? (set enum) "restore"))
      (is (contains? (set enum) "list"))
      (is (contains? (set enum) "switch"))
      (is (contains? (set enum) "sync"))
      (is (contains? (set enum) "export"))
      (is (contains? (set enum) "import"))
      (is (contains? (set enum) "validate"))
      (is (contains? (set enum) "adapters"))
      (is (contains? (set enum) "help")))))

(deftest test-tools-vector
  (testing "tools vector contains exactly one tool-def"
    (is (= 1 (count c-migration/tools)))
    (is (= c-migration/tool-def (first c-migration/tools)))))

;; =============================================================================
;; Handlers Map Tests
;; =============================================================================

(deftest test-handlers-map-completeness
  (testing "all handlers are registered"
    (is (contains? c-migration/handlers :status))
    (is (contains? c-migration/handlers :backup))
    (is (contains? c-migration/handlers :restore))
    (is (contains? c-migration/handlers :list))
    (is (contains? c-migration/handlers :switch))
    (is (contains? c-migration/handlers :sync))
    (is (contains? c-migration/handlers :export))
    (is (contains? c-migration/handlers :import))
    (is (contains? c-migration/handlers :validate))
    (is (contains? c-migration/handlers :adapters))))

(deftest test-handlers-are-functions
  (testing "all handlers are functions"
    (doseq [[k v] c-migration/handlers]
      (is (fn? v) (str "Handler " k " should be a function")))))

(deftest test-handlers-map-delegates-to-migration-handlers
  (testing "handlers map points to migration-handlers namespace fns"
    (is (= migration-handlers/cmd-status (:status c-migration/handlers)))
    (is (= migration-handlers/cmd-backup (:backup c-migration/handlers)))
    (is (= migration-handlers/cmd-restore (:restore c-migration/handlers)))
    (is (= migration-handlers/cmd-list (:list c-migration/handlers)))
    (is (= migration-handlers/cmd-switch (:switch c-migration/handlers)))
    (is (= migration-handlers/cmd-sync (:sync c-migration/handlers)))
    (is (= migration-handlers/cmd-export (:export c-migration/handlers)))
    (is (= migration-handlers/cmd-import (:import c-migration/handlers)))
    (is (= migration-handlers/cmd-validate (:validate c-migration/handlers)))
    (is (= migration-handlers/cmd-adapters (:adapters c-migration/handlers)))))

;; =============================================================================
;; CLI Handler Routing Tests
;; =============================================================================

(deftest test-cli-handler-help-command
  (testing "CLI handler returns help for 'help' command"
    (let [result (c-migration/handle-migration {:command "help"})]
      (is (not (:isError result)))
      (is (re-find #"Available commands" (:text result)))
      (is (re-find #"status" (:text result)))
      (is (re-find #"backup" (:text result)))
      (is (re-find #"restore" (:text result)))
      (is (re-find #"list" (:text result)))
      (is (re-find #"switch" (:text result)))
      (is (re-find #"sync" (:text result)))
      (is (re-find #"export" (:text result)))
      (is (re-find #"import" (:text result)))
      (is (re-find #"validate" (:text result)))
      (is (re-find #"adapters" (:text result))))))

(deftest test-cli-handler-unknown-command
  (testing "CLI handler returns error for unknown command"
    (let [result (c-migration/handle-migration {:command "nonexistent"})]
      (is (:isError result))
      (is (re-find #"Unknown command" (:text result))))))

(deftest test-cli-handler-routes-to-adapters
  (testing "CLI handler routes 'adapters' to cmd-adapters"
    (let [result (c-migration/handle-migration {:command "adapters"})]
      (is (map? result))
      (is (contains? result :adapters))
      (is (sequential? (:adapters result))))))

(deftest test-cli-handler-routes-to-list
  (testing "CLI handler routes 'list' to cmd-list"
    (let [result (c-migration/handle-migration {:command "list" :dir *test-dir*})]
      (is (map? result))
      (is (contains? result :backups))
      (is (contains? result :count)))))

(deftest test-cli-handler-routes-to-validate
  (testing "CLI handler routes 'validate' to cmd-validate"
    (let [path (str *test-dir* "/valid-test.edn")]
      (spit path (pr-str {:backup/version 1
                          :backup/scope :kg
                          :backup/counts {:edges 0}}))
      (let [result (c-migration/handle-migration {:command "validate" :path path})]
        (is (map? result))
        (is (contains? result :valid?))))))

(deftest test-cli-handler-routes-to-backup
  (testing "CLI handler routes 'backup' to cmd-backup (mocked)"
    (with-redefs [hive-mcp.knowledge-graph.migration/export-to-edn
                  (fn [] {:edges [] :disc [] :synthetic []
                          :counts {:edges 0 :disc 0 :synthetic 0}})
                  hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datascript)]
      ;; scope passed as string (MCP sends JSON strings) - coerce-params converts to keyword
      (let [result (c-migration/handle-migration {:command "backup"
                                                  :dir *test-dir*
                                                  :scope "kg"})]
        (is (map? result))
        (is (:success result))
        (is (string? (:path result)))))))

(deftest test-cli-handler-routes-to-export
  (testing "CLI handler routes 'export' to cmd-export (mocked)"
    (let [path (str *test-dir* "/export-consolidated.edn")]
      (with-redefs [hive-mcp.knowledge-graph.migration/export-to-edn
                    (fn [] {:edges [{:id "e1"}]
                            :disc []
                            :synthetic []
                            :counts {:edges 1 :disc 0 :synthetic 0}})]
        ;; Note: scope must be keyword - underlying handler uses (case scope :kg ...)
        (let [result (c-migration/handle-migration {:command "export"
                                                    :path path
                                                    :scope :kg})]
          (is (map? result))
          (is (:success result))
          (is (.exists (io/file path))))))))

(deftest test-cli-handler-routes-to-import-dry-run
  (testing "CLI handler routes 'import' with dry-run to cmd-import"
    (let [path (str *test-dir* "/import-consolidated.edn")]
      (spit path (pr-str {:edges [{:id "e1"} {:id "e2"}]
                          :disc [{:path "/test"}]
                          :synthetic []}))
      (let [result (c-migration/handle-migration {:command "import"
                                                  :path path
                                                  :dry-run true})]
        (is (map? result))
        (is (:dry-run result))
        (is (= 2 (get-in result [:would-import :edges])))))))

(deftest test-cli-handler-routes-to-switch-dry-run
  (testing "CLI handler routes 'switch' with dry-run to cmd-switch (mocked)"
    (with-redefs [hive-mcp.knowledge-graph.migration/detect-current-backend
                  (fn [] :datascript)
                  hive-mcp.knowledge-graph.migration/export-to-edn
                  (fn [] {:edges [] :disc [] :synthetic []
                          :counts {:edges 0 :disc 0 :synthetic 0}})]
      (let [target "datalevin"
            result (c-migration/handle-migration {:command "switch"
                                                  :target target
                                                  :dry-run true})]
        (is (map? result))
        (is (:dry-run result))
        (is (= :datascript (:from result)))
        ;; :target is a declared keyword-param, so the MCP boundary hands
        ;; cmd-switch a keyword (cmd-switch dispatches on it with `case`)
        ;; and echoes that keyword back as :to.
        (is (contains? c-migration/keyword-params :target))
        (is (= (keyword target) (:to result)))))))

;; =============================================================================
;; Parameter Pass-Through Tests
;; =============================================================================

(deftest test-cli-handler-passes-dir-to-list
  (testing "CLI handler passes dir param to list command"
    ;; Create test backup files in test dir
    (spit (str *test-dir* "/kg-datascript-20260201T100000.edn")
          (pr-str {:backup/version 1}))

    (let [result (c-migration/handle-migration {:command "list" :dir *test-dir*})]
      (is (= 1 (:count result)))
      (is (= 1 (count (:backups result)))))))

(deftest test-cli-handler-passes-limit-to-list
  (testing "CLI handler passes limit param to list command"
    (dotimes [i 5]
      (spit (str *test-dir* "/kg-datascript-2026020" i "T100000.edn")
            (pr-str {:backup/version 1})))

    (let [result (c-migration/handle-migration {:command "list"
                                                :dir *test-dir*
                                                :limit 2})]
      (is (= 2 (:count result))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-cli-handler-validate-requires-path
  (testing "validate through CLI handler throws when path missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"path required"
                          (c-migration/handle-migration {:command "validate"})))))

(deftest test-cli-handler-export-requires-path
  (testing "export through CLI handler throws when path missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"path required"
                          (c-migration/handle-migration {:command "export"
                                                         :scope "kg"})))))

(deftest test-cli-handler-import-requires-path
  (testing "import through CLI handler throws when path missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"path required"
                          (c-migration/handle-migration {:command "import"})))))

(deftest test-cli-handler-switch-requires-target
  (testing "switch through CLI handler throws when target missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Target backend required"
                          (c-migration/handle-migration {:command "switch"})))))

(deftest test-cli-handler-sync-requires-target
  (testing "sync through CLI handler throws when target missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Target backend required"
                          (c-migration/handle-migration {:command "sync"})))))

(deftest test-cli-handler-restore-requires-path-or-latest
  (testing "restore through CLI handler throws when no path or latest"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No backup path"
                          (c-migration/handle-migration {:command "restore"})))))

;; =============================================================================
;; Integration: tool-def :handler is handle-migration
;; =============================================================================

(deftest test-tool-def-handler-is-handle-migration
  (testing "tool-def :handler is the consolidated handle-migration fn"
    (is (= c-migration/handle-migration (:handler c-migration/tool-def)))))

(deftest test-tool-def-handler-routes-help
  (testing "tool-def handler routes help command correctly"
    (let [handler (:handler c-migration/tool-def)
          result (handler {:command "help"})]
      (is (not (:isError result)))
      (is (re-find #"Available commands" (:text result))))))

(deftest test-tool-def-handler-routes-adapters
  (testing "tool-def handler routes adapters command correctly"
    (let [handler (:handler c-migration/tool-def)
          result (handler {:command "adapters"})]
      (is (map? result))
      (is (contains? result :adapters)))))

;; =============================================================================
;; Registration Tests (structural - verifying tool shape for tools.clj inclusion)
;; =============================================================================

(deftest test-tool-def-has-required-fields-for-registration
  (testing "tool-def has all fields needed for tools.clj registration"
    ;; tools.clj concats c-migration/tools into get-base-tools
    ;; These fields are required by server/routes/make-tool
    (let [tool c-migration/tool-def]
      (is (string? (:name tool)) "name required for make-tool")
      (is (string? (:description tool)) "description required for make-tool")
      (is (map? (:inputSchema tool)) "inputSchema required for make-tool")
      (is (fn? (:handler tool)) "handler required for make-tool")
      (is (true? (:consolidated tool)) "consolidated flag needed for get-consolidated-tools"))))

(deftest test-tools-vector-is-valid-for-concat
  (testing "tools vector is compatible with tools.clj concat pattern"
    ;; tools.clj does: (concat ... c-migration/tools ...)
    (is (sequential? c-migration/tools))
    (is (pos? (count c-migration/tools)))
    (is (every? #(and (string? (:name %))
                      (fn? (:handler %)))
                c-migration/tools))))
