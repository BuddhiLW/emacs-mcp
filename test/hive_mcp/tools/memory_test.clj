(ns hive-mcp.tools.memory-test
  "Unit tests for hive-mcp.tools.memory namespace and submodules.

   SOLID: Tests verify the refactored module structure maintains behavior.
   CLARITY: Y - Tests ensure safe failure paths work correctly.

   Structure:
   - scope-test: Project scope utilities
   - format-test: JSON formatting utilities
   - duration-test: Duration calculations (existing)
   - handler integration tests: Verify facade routes correctly"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-mcp.tools.memory :as memory]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-dsl.adt :as adt]))

;; ============================================================
;; Scope Module Tests
;; ============================================================

(deftest scope-inject-project-scope-test
  (testing "inject-project-scope adds scope tag for project"
    (let [tags ["tag1" "tag2"]
          result (scope/inject-project-scope tags "my-project")]
      (is (= 3 (count result)))
      (is (some #(= % "scope:project:my-project") result))))

  (testing "inject-project-scope adds global scope for 'global' project"
    (let [tags ["tag1"]
          result (scope/inject-project-scope tags "global")]
      (is (some #(= % "scope:global") result))))

  (testing "inject-project-scope preserves existing scope tags"
    (let [tags ["tag1" "scope:project:other"]
          result (scope/inject-project-scope tags "my-project")]
      (is (= tags result))
      (is (not (some #(= % "scope:project:my-project") result))))))

(deftest scope-make-scope-tag-test
  (testing "make-scope-tag creates correct format"
    (is (= "scope:global" (scope/make-scope-tag "global")))
    (is (= "scope:project:foo" (scope/make-scope-tag "foo")))
    (is (= "scope:project:my-project-123" (scope/make-scope-tag "my-project-123")))))

(deftest scope-matches-scope-test
  (testing "matches-scope? with nil filter matches everything"
    (is (scope/matches-scope? {:tags ["any"]} nil))
    (is (scope/matches-scope? {:tags []} nil)))

  (testing "matches-scope? with 'all' matches everything"
    (is (scope/matches-scope? {:tags ["scope:project:foo"]} "all"))
    (is (scope/matches-scope? {:tags ["scope:global"]} "all")))

  (testing "matches-scope? with 'global' only matches global scope"
    (is (scope/matches-scope? {:tags ["scope:global"]} "global"))
    (is (not (scope/matches-scope? {:tags ["scope:project:foo"]} "global"))))

  (testing "matches-scope? with specific scope matches scope or global"
    (is (scope/matches-scope? {:tags ["scope:project:foo"]} "scope:project:foo"))
    (is (scope/matches-scope? {:tags ["scope:global"]} "scope:project:foo"))
    (is (not (scope/matches-scope? {:tags ["scope:project:bar"]} "scope:project:foo")))))

(deftest scope-derive-scope-filter-test
  ;; derive-scope-filter answers a ScopeFilter ADT value, not a scope STRING:
  ;; the raw string is parsed once at the IO boundary so downstream code
  ;; dispatches on a closed variant set instead of re-parsing text.
  (testing "'all' is its own variant, not an absent filter"
    (let [f (scope/derive-scope-filter "all" "my-project")]
      (is (= :scope/all (adt/adt-variant f)))))

  (testing "'global' is its own variant"
    (let [f (scope/derive-scope-filter "global" "my-project")]
      (is (= :scope/global (adt/adt-variant f)))))

  (testing "an explicit scope becomes :scope/project carrying that scope"
    (doseq [s ["scope:global" "custom-scope"]]
      (let [f (scope/derive-scope-filter s "my-project")]
        (is (= :scope/project (adt/adt-variant f)))
        (is (= s (:project-id f))))))

  (testing "an absent scope becomes :scope/auto carrying the project-id"
    (doseq [p ["my-project" "global"]]
      (let [f (scope/derive-scope-filter nil p)]
        (is (= :scope/auto (adt/adt-variant f)))
        (is (= p (:project-id f)))))))

;; ============================================================
;; Format Module Tests
;; ============================================================

(deftest format-entry->json-alist-test
  (testing "entry->json-alist removes :document field"
    (let [entry {:id "123" :type "note" :content "test" :document "internal"}
          result (fmt/entry->json-alist entry)]
      (is (nil? (:document result)))
      (is (= "123" (:id result)))))

  (testing "entry->json-alist provides default empty tags"
    (let [entry {:id "123" :type "note" :content "test"}
          result (fmt/entry->json-alist entry)]
      (is (= [] (:tags result))))))

(deftest format-entry->metadata-test
  (testing "entry->metadata creates preview from string content"
    (let [entry {:id "123" :type "note" :content "This is a test note" :created "2024-01-01"}
          result (fmt/entry->metadata entry)]
      (is (= "123" (:id result)))
      (is (= "note" (:type result)))
      (is (= "This is a test note" (:preview result)))
      (is (= "2024-01-01" (:created result)))))

  (testing "entry->metadata truncates long content"
    (let [long-content (apply str (repeat 200 "x"))
          entry {:id "123" :type "note" :content long-content}
          result (fmt/entry->metadata entry)]
      (is (<= (count (:preview result)) 100))
      (is (.endsWith (:preview result) "..."))))

  (testing "entry->metadata extracts preview from map content"
    (let [entry {:id "123" :type "note" :content {:title "My Title" :body "content"}}
          result (fmt/entry->metadata entry)]
      (is (= "My Title" (:preview result))))))

(deftest format-entries->json-test
  (testing "entries->json converts collection to JSON string"
    (let [entries [{:id "1" :type "note" :content "a"}
                   {:id "2" :type "note" :content "b"}]
          result (fmt/entries->json entries)
          parsed (json/read-str result :key-fn keyword)]
      (is (= 2 (count parsed)))
      (is (= "1" (:id (first parsed)))))))

;; ============================================================
;; Duration Module Tests
;; ============================================================

(deftest duration-shift-duration-test
  (testing "shift-duration promotes correctly"
    (let [{:keys [new-duration changed?]} (dur/shift-duration "ephemeral" +1)]
      (is (= "short" new-duration))
      (is changed?)))

  (testing "shift-duration demotes correctly"
    (let [{:keys [new-duration changed?]} (dur/shift-duration "long" -1)]
      (is (= "medium" new-duration))
      (is changed?)))

  (testing "shift-duration at boundary returns unchanged"
    (let [{:keys [new-duration changed?]} (dur/shift-duration "permanent" +1)]
      (is (= "permanent" new-duration))
      (is (not changed?))))

  (testing "shift-duration at minimum boundary returns unchanged"
    (let [{:keys [new-duration changed?]} (dur/shift-duration "ephemeral" -1)]
      (is (= "ephemeral" new-duration))
      (is (not changed?)))))

(deftest duration-valid-duration-test
  (testing "valid-duration? accepts valid values"
    (is (dur/valid-duration? "ephemeral"))
    (is (dur/valid-duration? "short"))
    (is (dur/valid-duration? "medium"))
    (is (dur/valid-duration? "long"))
    (is (dur/valid-duration? "permanent")))

  (testing "valid-duration? rejects invalid values"
    (is (not (dur/valid-duration? "invalid")))
    (is (not (dur/valid-duration? nil)))
    (is (not (dur/valid-duration? "")))))

;; ============================================================
;; Facade Integration Tests
;; ============================================================

(deftest memory-facade-tools-test
  (testing "memory/tools has expected count"
    (is (= 16 (count memory/tools))))

  (testing "memory/tools has required tool names"
    (let [tool-names (set (map :name memory/tools))]
      (is (contains? tool-names "mcp_memory_add"))
      (is (contains? tool-names "mcp_memory_query"))
      (is (contains? tool-names "mcp_memory_query_metadata"))
      (is (contains? tool-names "mcp_memory_get_full"))
      (is (contains? tool-names "mcp_memory_update_tags"))
      (is (contains? tool-names "mcp_memory_search_semantic"))
      (is (contains? tool-names "mcp_memory_set_duration"))
      (is (contains? tool-names "mcp_memory_promote"))
      (is (contains? tool-names "mcp_memory_demote"))
      (is (contains? tool-names "mcp_memory_cleanup_expired"))
      (is (contains? tool-names "mcp_memory_expiring_soon"))
      (is (contains? tool-names "mcp_memory_log_access"))
      (is (contains? tool-names "mcp_memory_feedback"))
      (is (contains? tool-names "mcp_memory_helpfulness_ratio"))
      (is (contains? tool-names "mcp_memory_migrate_project"))
      (is (contains? tool-names "mcp_memory_import_json")))))

(deftest memory-facade-handlers-exist-test
  (testing "all handlers are defined and callable"
    (is (fn? memory/handle-mcp-memory-add))
    (is (fn? memory/handle-mcp-memory-query))
    (is (fn? memory/handle-mcp-memory-query-metadata))
    (is (fn? memory/handle-mcp-memory-get-full))
    (is (fn? memory/handle-mcp-memory-update-tags))
    (is (fn? memory/handle-mcp-memory-search-semantic))
    (is (fn? memory/handle-mcp-memory-set-duration))
    (is (fn? memory/handle-mcp-memory-promote))
    (is (fn? memory/handle-mcp-memory-demote))
    (is (fn? memory/handle-mcp-memory-cleanup-expired))
    (is (fn? memory/handle-mcp-memory-expiring-soon))
    (is (fn? memory/handle-mcp-memory-log-access))
    (is (fn? memory/handle-mcp-memory-feedback))
    (is (fn? memory/handle-mcp-memory-helpfulness-ratio))
    (is (fn? memory/handle-mcp-memory-migrate-project))
    (is (fn? memory/handle-mcp-memory-import-json))))

(deftest memory-tools-have-handlers-test
  (testing "each tool definition has a handler"
    (doseq [tool memory/tools]
      (is (fn? (:handler tool))
          (str "Tool " (:name tool) " should have a handler function")))))

(deftest memory-tools-have-input-schemas-test
  (testing "each tool definition has an inputSchema"
    (doseq [tool memory/tools]
      (is (map? (:inputSchema tool))
          (str "Tool " (:name tool) " should have an inputSchema"))
      (is (= "object" (get-in tool [:inputSchema :type]))
          (str "Tool " (:name tool) " inputSchema should be type object")))))

;; ============================================================
;; Directory Parameter Tests (scope fix)
;; ============================================================

(deftest memory-crud-tools-have-directory-param-test
  (testing "mcp_memory_add has directory parameter for correct scoping"
    (let [add-tool (first (filter #(= (:name %) "mcp_memory_add") memory/tools))
          props (get-in add-tool [:inputSchema :properties])]
      (is (contains? props "directory")
          "mcp_memory_add should have directory property")
      (is (= "string" (get-in props ["directory" :type]))
          "directory should be type string")))

  (testing "mcp_memory_query has directory parameter for correct scoping"
    (let [query-tool (first (filter #(= (:name %) "mcp_memory_query") memory/tools))
          props (get-in query-tool [:inputSchema :properties])]
      (is (contains? props "directory")
          "mcp_memory_query should have directory property")
      (is (= "string" (get-in props ["directory" :type]))
          "directory should be type string")))

  (testing "mcp_memory_query_metadata has directory parameter for correct scoping"
    (let [query-meta-tool (first (filter #(= (:name %) "mcp_memory_query_metadata") memory/tools))
          props (get-in query-meta-tool [:inputSchema :properties])]
      (is (contains? props "directory")
          "mcp_memory_query_metadata should have directory property")
      (is (= "string" (get-in props ["directory" :type]))
          "directory should be type string"))))
