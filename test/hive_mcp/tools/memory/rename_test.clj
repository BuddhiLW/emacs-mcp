(ns hive-mcp.tools.memory.rename-test
  "Tests for handle-rename-project unified rename command.

   Coverage:
   - Validation: missing params, same old/new
   - Dry-run: returns preview without modifying
   - Full rename: orchestrates the memory store + KG + EDN + config
   - EDN update: appends old-project-id to :aliases, idempotent
   - Config re-registration: kg-scope gets updated
   - Error handling: graceful on store/KG failures

   Entries and edges are seeded through the ports and asserted through the
   ports; no backend is named."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [hive-mcp.tools.memory.migration :as migration]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.registry :as registry]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.test.stub.memory-store :as mem-stub]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ============================================================
;; Test Helpers
;; ============================================================

(defn- parse-mcp-result
  "Parse the JSON from an MCP result.
   mcp-json returns {:type 'text' :text '{...json...}'}."
  [result]
  (try
    (let [text (or (get-in result [:result 0 :text])
                   (:text result))]
      (when text
        (json/read-str text :key-fn keyword)))
    (catch Exception _
      result)))

(defn- create-temp-dir!
  "Create a temporary directory for testing. Returns absolute path string."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "hive-rename-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- write-edn!
  "Write EDN data to a .hive-project.edn file in the given directory."
  [directory config]
  (let [edn-file (io/file directory ".hive-project.edn")]
    (spit (.getAbsolutePath edn-file) (pr-str config))))

(defn- read-edn
  "Read .hive-project.edn from a directory."
  [directory]
  (let [edn-file (io/file directory ".hive-project.edn")]
    (when (.exists edn-file)
      (edn/read-string (slurp edn-file)))))

(defn- cleanup-dir!
  "Remove a temp directory and its contents."
  [dir-path]
  (let [dir (io/file dir-path)]
    (doseq [f (reverse (file-seq dir))]
      (.delete f))))

(defn- seed-entries!
  "Add ENTRIES to the registered memory store. Returns the store."
  [entries]
  (let [store (registry/get-store)]
    (doseq [e entries] (ports/add-entry! store e))
    store))

(defn- seed-edges!
  "Create N KG edges in SCOPE. Returns the edge ids."
  [scope n]
  (mapv (fn [i]
          (kg-edges/add-edge! {:from (str scope "-from-" i)
                               :to   (str scope "-to-" i)
                               :relation :relates
                               :scope scope}))
        (range n)))

(defn- stored-entry
  "The entry the memory store currently holds for ID."
  [id]
  (ports/get-entry (registry/get-store) id))

;; Clean up kg-scope state between tests
(use-fixtures :each
  mem-stub/with-stub-store
  (iso/with-isolations :kg-conn)
  (fn [test-fn]
    (kg-scope/clear-config-cache!)
    (try
      (test-fn)
      (finally
        (kg-scope/clear-config-cache!)))))

;; ============================================================
;; Validation Tests
;; ============================================================

(deftest rename-missing-old-project-id-test
  (testing "rename fails when old-project-id is missing"
    (let [result (parse-mcp-result
                  (migration/handle-rename-project
                   {:new-project-id "new-project"}))]
      (is (some? result))
      ;; mcp-error returns isError true
      (is (true? (get-in (migration/handle-rename-project
                          {:new-project-id "new-project"})
                         [:isError]))))))

(deftest rename-missing-new-project-id-test
  (testing "rename fails when new-project-id is missing"
    (let [result (migration/handle-rename-project
                  {:old-project-id "old-project"})]
      (is (true? (:isError result))))))

(deftest rename-same-project-id-test
  (testing "rename fails when old and new are the same"
    (let [result (migration/handle-rename-project
                  {:old-project-id "same-id"
                   :new-project-id "same-id"})]
      (is (true? (:isError result))))))

;; ============================================================
;; Dry-Run Tests
;; ============================================================

(deftest rename-dry-run-test
  (testing "dry-run returns preview without modifying anything"
    (let [dir (create-temp-dir!)]
      (write-edn! dir {:project-id "old-project" :aliases []})
      (seed-entries! [{:id "e1" :type "note" :content "a" :project-id "old-project"}
                      {:id "e2" :type "note" :content "b" :project-id "old-project"}
                      {:id "e3" :type "note" :content "c" :project-id "old-project"}])
      (seed-edges! "old-project" 2)
      (try
        (let [result (parse-mcp-result
                      (migration/handle-rename-project
                       {:old-project-id "old-project"
                        :new-project-id "new-project"
                        :directory dir
                        :dry-run true}))]
          (is (= "dry-run" (:status result)))
          (is (= 3 (get-in result [:chroma :would-migrate])))
          (is (= 2 (get-in result [:kg-edges :would-migrate])))
          (is (true? (get-in result [:edn :exists])))
          (is (= [] (get-in result [:edn :current-aliases])))
          (is (true? (get-in result [:edn :would-add-alias])))
          (is (= "old-project" (:old-project-id result)))
          (is (= "new-project" (:new-project-id result)))

          (let [edn-after (read-edn dir)]
            (is (= "old-project" (:project-id edn-after)))
            (is (= [] (:aliases edn-after))))

          (is (= "old-project" (:project-id (stored-entry "e1")))
              "dry-run leaves the stored entries untouched"))
        (finally
          (cleanup-dir! dir))))))

(deftest rename-dry-run-no-directory-test
  (testing "dry-run works without directory (edn section shows nil)"
    (let [result (parse-mcp-result
                  (migration/handle-rename-project
                   {:old-project-id "old"
                    :new-project-id "new"
                    :dry-run true}))]
      (is (= "dry-run" (:status result)))
      (is (= 0 (get-in result [:chroma :would-migrate])))
      (is (false? (get-in result [:edn :exists]))))))

;; ============================================================
;; Full Rename Tests
;; ============================================================

(deftest rename-full-flow-test
  (testing "full rename orchestrates the memory store + KG + EDN + config"
    (let [dir (create-temp-dir!)]
      (write-edn! dir {:project-id "old-project"
                       :aliases []
                       :project-type :clojure-cli})
      (seed-entries! [{:id "e1" :type "note" :content "a"
                       :project-id "old-project"
                       :tags ["scope:project:old-project" "note"]}
                      {:id "e2" :type "convention" :content "b"
                       :project-id "old-project"
                       :tags ["scope:project:old-project" "convention"]}])
      (seed-edges! "old-project" 3)
      (try
        (let [result (parse-mcp-result
                      (migration/handle-rename-project
                       {:old-project-id "old-project"
                        :new-project-id "new-project"
                        :directory dir}))]
          (is (= "success" (:status result)))
          (is (= "old-project" (:old-project-id result)))
          (is (= "new-project" (:new-project-id result)))

          (is (= 2 (get-in result [:chroma :migrated])))
          (is (= 2 (get-in result [:chroma :updated-scopes])))

          (let [e1 (stored-entry "e1")]
            (is (= "new-project" (:project-id e1))
                "the stored entry carries the new project-id")
            (is (some #(= % "scope:project:new-project") (:tags e1))
                "the scope tag is rewritten in place")
            (is (not-any? #(= % "scope:project:old-project") (:tags e1))
                "the old scope tag is gone"))

          (is (= 3 (get-in result [:kg-edges :migrated])))
          (is (= 3 (count (kg-edges/get-edges-by-scope "new-project")))
              "the edges now answer under the new scope")
          (is (empty? (kg-edges/get-edges-by-scope "old-project"))
              "and no longer under the old one")

          (is (true? (get-in result [:edn :updated])))
          (is (= ["old-project"] (get-in result [:edn :aliases])))

          (let [edn-after (read-edn dir)]
            (is (= "new-project" (:project-id edn-after)))
            (is (= ["old-project"] (:aliases edn-after)))
            (is (= :clojure-cli (:project-type edn-after))))

          (is (true? (:config-registered result))))
        (finally
          (cleanup-dir! dir))))))

(deftest rename-without-directory-test
  (testing "rename works without directory (skips EDN update)"
    (with-redefs [hive-mcp.chroma.core/query-entries
                  (fn [& _args] [])

                  hive-mcp.chroma.core/embedding-configured?
                  (fn [] true)

                  hive-mcp.knowledge-graph.edges/migrate-edge-scopes!
                  (fn [_ _] {:migrated 0})]

      (let [result (parse-mcp-result
                    (migration/handle-rename-project
                     {:old-project-id "old"
                      :new-project-id "new"}))]
        (is (= "success" (:status result)))
        (is (false? (get-in result [:edn :updated])))
        (is (= "no directory provided" (get-in result [:edn :reason])))))))

;; ============================================================
;; EDN Update Tests
;; ============================================================

(deftest rename-edn-alias-appended-test
  (testing "old-project-id is appended to :aliases vector"
    (let [dir (create-temp-dir!)
          _ (write-edn! dir {:project-id "old-project"
                             :aliases ["even-older"]})]
      (try
        (with-redefs [hive-mcp.chroma.core/query-entries (fn [& _] [])
                      hive-mcp.chroma.core/embedding-configured? (fn [] true)
                      hive-mcp.knowledge-graph.edges/migrate-edge-scopes!
                      (fn [_ _] {:migrated 0})]

          (migration/handle-rename-project
           {:old-project-id "old-project"
            :new-project-id "new-project"
            :directory dir})

          ;; Verify aliases chain
          (let [edn-after (read-edn dir)]
            (is (= "new-project" (:project-id edn-after)))
            (is (= ["even-older" "old-project"] (:aliases edn-after)))))
        (finally
          (cleanup-dir! dir))))))

(deftest rename-edn-alias-idempotent-test
  (testing "re-running rename doesn't duplicate alias"
    (let [dir (create-temp-dir!)
          _ (write-edn! dir {:project-id "old-project"
                             :aliases ["old-project"]})]
      (try
        (with-redefs [hive-mcp.chroma.core/query-entries (fn [& _] [])
                      hive-mcp.chroma.core/embedding-configured? (fn [] true)
                      hive-mcp.knowledge-graph.edges/migrate-edge-scopes!
                      (fn [_ _] {:migrated 0})]

          (migration/handle-rename-project
           {:old-project-id "old-project"
            :new-project-id "new-project"
            :directory dir})

          ;; old-project should only appear once
          (let [edn-after (read-edn dir)]
            (is (= "new-project" (:project-id edn-after)))
            (is (= ["old-project"] (:aliases edn-after)))
            (is (= 1 (count (filter #(= "old-project" %) (:aliases edn-after)))))))
        (finally
          (cleanup-dir! dir))))))

(deftest rename-edn-no-existing-file-test
  (testing "rename creates .hive-project.edn if directory exists but file doesn't"
    (let [dir (create-temp-dir!)]
      (try
        (with-redefs [hive-mcp.chroma.core/query-entries (fn [& _] [])
                      hive-mcp.chroma.core/embedding-configured? (fn [] true)
                      hive-mcp.knowledge-graph.edges/migrate-edge-scopes!
                      (fn [_ _] {:migrated 0})]

          (migration/handle-rename-project
           {:old-project-id "old-project"
            :new-project-id "new-project"
            :directory dir})

          ;; Should have created .hive-project.edn
          (let [edn-after (read-edn dir)]
            (is (some? edn-after))
            (is (= "new-project" (:project-id edn-after)))
            (is (= ["old-project"] (:aliases edn-after)))))
        (finally
          (cleanup-dir! dir))))))

;; ============================================================
;; Config Registration Tests
;; ============================================================

(deftest rename-config-registered-test
  (testing "kg-scope config is re-registered after rename"
    (let [dir (create-temp-dir!)
          _ (write-edn! dir {:project-id "old-project"
                             :parent-id "parent-proj"
                             :aliases []})]
      (try
        (with-redefs [hive-mcp.chroma.core/query-entries (fn [& _] [])
                      hive-mcp.chroma.core/embedding-configured? (fn [] true)
                      hive-mcp.knowledge-graph.edges/migrate-edge-scopes!
                      (fn [_ _] {:migrated 0})]

          (migration/handle-rename-project
           {:old-project-id "old-project"
            :new-project-id "new-project"
            :directory dir})

          ;; Verify config is registered in kg-scope
          (let [config (kg-scope/get-project-config "new-project")]
            (is (some? config))
            (is (= "new-project" (:project-id config)))
            (is (= ["old-project"] (:aliases config)))))
        (finally
          (cleanup-dir! dir))))))

(deftest rename-alias-resolution-works-test
  (testing "after rename, old-project-id resolves to new via alias index"
    (let [dir (create-temp-dir!)
          _ (write-edn! dir {:project-id "old-project"
                             :aliases []})]
      (try
        (with-redefs [hive-mcp.chroma.core/query-entries (fn [& _] [])
                      hive-mcp.chroma.core/embedding-configured? (fn [] true)
                      hive-mcp.knowledge-graph.edges/migrate-edge-scopes!
                      (fn [_ _] {:migrated 0})]

          (migration/handle-rename-project
           {:old-project-id "old-project"
            :new-project-id "new-project"
            :directory dir})

          ;; old-project should resolve to new-project via alias
          (is (= "new-project" (kg-scope/resolve-project-id "old-project")))
          ;; new-project should resolve to itself
          (is (= "new-project" (kg-scope/resolve-project-id "new-project"))))
        (finally
          (cleanup-dir! dir))))))

;; ============================================================
;; Error Handling Tests
;; ============================================================

(deftest rename-chroma-failure-non-blocking-test
  (testing "Chroma failure doesn't prevent EDN update"
    (let [dir (create-temp-dir!)
          _ (write-edn! dir {:project-id "old-project"
                             :aliases []})]
      (try
        (with-redefs [hive-mcp.chroma.core/query-entries
                      (fn [& _] (throw (Exception. "Chroma unavailable")))

                      hive-mcp.chroma.core/embedding-configured?
                      (fn [] true)

                      hive-mcp.knowledge-graph.edges/migrate-edge-scopes!
                      (fn [_ _] {:migrated 0})]

          (let [result (parse-mcp-result
                        (migration/handle-rename-project
                         {:old-project-id "old-project"
                          :new-project-id "new-project"
                          :directory dir}))]
            ;; Result should still succeed overall
            (is (= "success" (:status result)))
            ;; Chroma section should have error info
            (is (= 0 (get-in result [:chroma :migrated])))
            ;; EDN should still be updated
            (is (true? (get-in result [:edn :updated])))))
        (finally
          (cleanup-dir! dir))))))

;; ============================================================
;; Consolidated Tool Integration
;; ============================================================

(deftest consolidated-memory-rename-dispatch-test
  (testing "consolidated memory tool dispatches rename command"
    (let [dir (create-temp-dir!)]
      (try
        (with-redefs [hive-mcp.chroma.core/query-entries (fn [& _] [])
                      hive-mcp.chroma.core/embedding-configured? (fn [] true)
                      hive-mcp.knowledge-graph.edges/migrate-edge-scopes!
                      (fn [_ _] {:migrated 0})]

          ;; Verify the handler is in the handlers map
          (let [handlers (requiring-resolve 'hive-mcp.tools.consolidated.memory/handlers)]
            (is (some? (get @handlers :rename))
                "rename command should be in handlers map")))
        (finally
          (cleanup-dir! dir))))))
