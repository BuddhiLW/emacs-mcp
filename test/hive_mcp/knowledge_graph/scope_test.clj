(ns hive-mcp.knowledge-graph.scope-test
  "Unit tests for Knowledge Graph scope hierarchy.

   Tests cover:
   - get-parent-scope: Parent resolution from config or string inference
   - visible-scopes: Hierarchical visibility including ancestors
   - scope-contains?: Containment checks for inheritance
   - Scope tag generation and normalization

   Inheritance Rules:
   - Down (parent→child): Automatic - child sees parent knowledge
   - Up (child→parent): NOT automatic - requires explicit promotion
   - Across (sibling→sibling): Via common ancestor only"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.scope :as scope]
            [clojure.set]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-state-fixture
  "Clear caches and registries before each test."
  [f]
  (scope/clear-config-cache!)
  (f))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; Test get-parent-scope
;; =============================================================================

(deftest test-get-parent-scope-nil
  (testing "nil scope has no parent"
    (is (nil? (scope/get-parent-scope nil)))))

(deftest test-get-parent-scope-global
  (testing "global scope has no parent"
    (is (nil? (scope/get-parent-scope "global")))))

(deftest test-get-parent-scope-inferred-single-level
  (testing "Single-level scope infers global as parent"
    (is (= "global" (scope/get-parent-scope "hive-mcp")))))

(deftest test-get-parent-scope-inferred-two-level
  (testing "Two-level scope infers parent from string"
    (is (= "hive-mcp" (scope/get-parent-scope "hive-mcp:agora")))))

(deftest test-get-parent-scope-inferred-three-level
  (testing "Three-level scope infers parent from string"
    (is (= "hive-mcp:agora" (scope/get-parent-scope "hive-mcp:agora:feature")))))

(deftest test-get-parent-scope-explicit-config
  (testing "Explicit parent-id from registered config takes precedence"
    ;; Register config with explicit parent
    (scope/register-project-config! "custom-project" {:parent-id "explicit-parent"})
    (is (= "explicit-parent" (scope/get-parent-scope "custom-project")))))

(deftest test-get-parent-scope-legacy-parent-field
  (testing "Legacy :parent field works when :parent-id not present"
    (scope/register-project-config! "legacy-project" {:parent "legacy-parent"})
    (is (= "legacy-parent" (scope/get-parent-scope "legacy-project")))))

(deftest test-get-parent-scope-scope-tag-format
  (testing "Handles scope:project: prefix normalization"
    (is (= "hive-mcp" (scope/get-parent-scope "scope:project:hive-mcp:agora")))))

(deftest test-get-parent-scope-scope-global-normalized
  (testing "scope:global normalizes to global"
    (is (nil? (scope/get-parent-scope "scope:global")))))

;; =============================================================================
;; Test visible-scopes
;; =============================================================================

(deftest test-visible-scopes-nil
  (testing "nil scope sees only global"
    (is (= ["global"] (scope/visible-scopes nil)))))

(deftest test-visible-scopes-global
  (testing "global scope sees only itself"
    (is (= ["global"] (scope/visible-scopes "global")))))

(deftest test-visible-scopes-single-level
  (testing "Single-level scope sees itself and global"
    (is (= ["hive-mcp" "global"] (scope/visible-scopes "hive-mcp")))))

(deftest test-visible-scopes-two-level
  (testing "Two-level scope sees hierarchy up to global"
    (is (= ["hive-mcp:agora" "hive-mcp" "global"]
           (scope/visible-scopes "hive-mcp:agora")))))

(deftest test-visible-scopes-three-level
  (testing "Three-level scope sees full hierarchy"
    (is (= ["hive-mcp:agora:feature" "hive-mcp:agora" "hive-mcp" "global"]
           (scope/visible-scopes "hive-mcp:agora:feature")))))

(deftest test-visible-scopes-with-explicit-parent
  (testing "Explicit parent follows config, not string inference"
    ;; Register a config where parent is not inferable from string
    (scope/register-project-config! "submodule-x" {:parent-id "parent-project"})
    (scope/register-project-config! "parent-project" {})
    (is (= ["submodule-x" "parent-project" "global"]
           (scope/visible-scopes "submodule-x")))))

;; =============================================================================
;; Test scope-contains?
;; =============================================================================

(deftest test-scope-contains-same-scope
  (testing "Scope contains itself"
    (is (scope/scope-contains? "hive-mcp" "hive-mcp"))))

(deftest test-scope-contains-global-contains-all
  (testing "Global scope contains everything"
    (is (scope/scope-contains? "global" "hive-mcp"))
    (is (scope/scope-contains? "global" "hive-mcp:agora"))
    (is (scope/scope-contains? "global" "any-project"))))

(deftest test-scope-contains-parent-contains-child
  (testing "Parent scope contains child scope"
    (is (scope/scope-contains? "hive-mcp" "hive-mcp:agora"))
    (is (scope/scope-contains? "hive-mcp" "hive-mcp:agora:feature"))))

(deftest test-scope-contains-child-not-contains-parent
  (testing "Child scope does NOT contain parent scope"
    (is (not (scope/scope-contains? "hive-mcp:agora" "hive-mcp")))
    (is (not (scope/scope-contains? "hive-mcp:agora:feature" "hive-mcp:agora")))))

(deftest test-scope-contains-siblings-not-contain-each-other
  (testing "Sibling scopes do NOT contain each other"
    (is (not (scope/scope-contains? "hive-mcp:agora" "hive-mcp:tools")))
    (is (not (scope/scope-contains? "hive-mcp:tools" "hive-mcp:agora")))))

(deftest test-scope-contains-nil-edge-cases
  (testing "nil scope edge cases"
    (is (scope/scope-contains? nil nil))
    (is (not (scope/scope-contains? nil "hive-mcp")))
    (is (scope/scope-contains? "global" nil))))

;; =============================================================================
;; Test Scope Tag Generation
;; =============================================================================

(deftest test-scope-tag-global
  (testing "Global scope generates scope:global tag"
    (is (= "scope:global" (scope/scope->tag "global")))
    (is (= "scope:global" (scope/scope->tag nil)))))

(deftest test-scope-tag-project
  (testing "Project scope generates scope:project:X tag"
    (is (= "scope:project:hive-mcp" (scope/scope->tag "hive-mcp")))
    (is (= "scope:project:hive-mcp:agora" (scope/scope->tag "hive-mcp:agora")))))

(deftest test-visible-scope-tags
  (testing "visible-scope-tags returns tag set for Chroma filtering"
    (is (= #{"scope:project:hive-mcp:agora"
             "scope:project:hive-mcp"
             "scope:global"}
           (scope/visible-scope-tags "hive-mcp:agora")))))

(deftest test-visible-scope-tags-global
  (testing "visible-scope-tags for global returns just global tag"
    (is (= #{"scope:global"} (scope/visible-scope-tags "global")))))

;; =============================================================================
;; Test derive-hierarchy-scope-filter (backward compat)
;; =============================================================================

(deftest test-derive-hierarchy-scope-filter-all
  (testing "scope 'all' returns nil (no filtering)"
    (is (nil? (scope/derive-hierarchy-scope-filter "all")))))

(deftest test-derive-hierarchy-scope-filter-nil
  (testing "scope nil returns nil (caller handles auto mode)"
    (is (nil? (scope/derive-hierarchy-scope-filter nil)))))

(deftest test-derive-hierarchy-scope-filter-specific
  (testing "specific scope returns visible scope tags set"
    (is (= #{"scope:project:hive-mcp:agora"
             "scope:project:hive-mcp"
             "scope:global"}
           (scope/derive-hierarchy-scope-filter "hive-mcp:agora")))))

;; =============================================================================
;; Test Project Config Registration
;; =============================================================================

(deftest test-register-and-get-project-config
  (testing "Registered project config is retrievable"
    (scope/register-project-config! "test-project"
                                    {:project-id "test-project"
                                     :parent-id "parent"
                                     :project-type :clojure})
    (let [config (scope/get-project-config "test-project")]
      (is (= "test-project" (:project-id config)))
      (is (= "parent" (:parent-id config)))
      (is (= :clojure (:project-type config))))))

(deftest test-get-project-config-not-found
  (testing "Non-existent project returns nil"
    (is (nil? (scope/get-project-config "non-existent-project")))))

;; =============================================================================
;; Integration Tests - Inheritance Scenarios
;; =============================================================================

(deftest test-inheritance-down-automatic
  (testing "Down inheritance: child automatically sees parent knowledge"
    ;; Setup: hive-mcp -> hive-mcp:agora
    ;; A query from hive-mcp:agora should include hive-mcp scope
    (let [child-visible (set (scope/visible-scopes "hive-mcp:agora"))]
      (is (contains? child-visible "hive-mcp"))
      (is (contains? child-visible "global")))))

(deftest test-inheritance-up-not-automatic
  (testing "Up inheritance: parent does NOT automatically see child knowledge"
    ;; A query from hive-mcp should NOT include hive-mcp:agora scope
    (let [parent-visible (set (scope/visible-scopes "hive-mcp"))]
      (is (not (contains? parent-visible "hive-mcp:agora")))
      ;; But should see global
      (is (contains? parent-visible "global")))))

(deftest test-inheritance-across-via-ancestor
  (testing "Across inheritance: siblings share only via common ancestor"
    ;; hive-mcp:agora and hive-mcp:tools are siblings
    ;; They share only hive-mcp and global
    (let [agora-visible (set (scope/visible-scopes "hive-mcp:agora"))
          tools-visible (set (scope/visible-scopes "hive-mcp:tools"))
          shared (clojure.set/intersection agora-visible tools-visible)]
      ;; Shared should be parent + global
      (is (= #{"hive-mcp" "global"} shared))
      ;; Each should NOT see the other
      (is (not (contains? agora-visible "hive-mcp:tools")))
      (is (not (contains? tools-visible "hive-mcp:agora"))))))

(deftest test-inheritance-cross-project-via-global
  (testing "Cross-project inheritance: unrelated projects share only global"
    (let [project-a-visible (set (scope/visible-scopes "project-a"))
          project-b-visible (set (scope/visible-scopes "project-b"))
          shared (clojure.set/intersection project-a-visible project-b-visible)]
      ;; Only global is shared
      (is (= #{"global"} shared)))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest test-empty-string-scope
  (testing "Empty string scope treated as nil"
    (is (= ["global"] (scope/visible-scopes "")))
    (is (nil? (scope/get-parent-scope "")))))

(deftest test-whitespace-scope
  (testing "Whitespace-only scope treated as nil"
    (is (= ["global"] (scope/visible-scopes "   ")))))

(deftest test-deeply-nested-scope
  (testing "Deeply nested scope works correctly"
    (let [deep-scope "org:team:project:submodule:feature"]
      (is (= ["org:team:project:submodule:feature"
              "org:team:project:submodule"
              "org:team:project"
              "org:team"
              "org"
              "global"]
             (scope/visible-scopes deep-scope))))))

(deftest test-scope-with-special-characters
  (testing "Scope with special characters (except colons) works"
    (let [scope-name "my-project_v2.0"]
      (is (= ["my-project_v2.0" "global"]
             (scope/visible-scopes scope-name))))))

;; =============================================================================
;; HCR Wave 3: Descendant Scope Tags
;; =============================================================================

(deftest test-descendant-scope-tags-nil-scope
  (testing "nil scope returns nil (no descendants)"
    (is (nil? (scope/descendant-scope-tags nil)))))

(deftest test-descendant-scope-tags-global-scope
  (testing "global scope returns nil (global has no descendant concept)"
    (is (nil? (scope/descendant-scope-tags "global")))))

(deftest test-descendant-scope-tags-empty-tree
  (testing "Project with no scanned tree returns empty set"
    ;; With no tree scanned, descendants should be empty set
    (is (= #{} (scope/descendant-scope-tags "unknown-project")))))

(deftest test-descendant-scopes-nil-scope
  (testing "nil scope returns nil"
    (is (nil? (scope/descendant-scopes nil)))))

(deftest test-descendant-scopes-global-scope
  (testing "global scope returns nil"
    (is (nil? (scope/descendant-scopes "global")))))

(deftest test-descendant-scopes-empty-tree
  (testing "Project with no scanned tree returns empty vector"
    (is (= [] (scope/descendant-scopes "unknown-project")))))

(deftest test-full-hierarchy-scope-tags-nil
  (testing "nil scope returns just global"
    (is (= #{"scope:global"} (scope/full-hierarchy-scope-tags nil)))))

(deftest test-full-hierarchy-scope-tags-global
  (testing "global scope returns just global"
    (is (= #{"scope:global"} (scope/full-hierarchy-scope-tags "global")))))

(deftest test-full-hierarchy-scope-tags-includes-ancestors
  (testing "full-hierarchy includes ancestors (visible-scope-tags behavior)"
    (let [tags (scope/full-hierarchy-scope-tags "hive-mcp:agora")]
      ;; Should include self
      (is (contains? tags "scope:project:hive-mcp:agora"))
      ;; Should include parent
      (is (contains? tags "scope:project:hive-mcp"))
      ;; Should include global
      (is (contains? tags "scope:global")))))

;; =============================================================================
;; Alias Resolution
;; =============================================================================

(deftest test-resolve-project-id-alias
  (testing "Alias resolves to canonical project-id"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp" "hive-emacs"]})
    (is (= "hive-mcp" (scope/resolve-project-id "emacs-mcp")))
    (is (= "hive-mcp" (scope/resolve-project-id "hive-emacs")))))

(deftest test-resolve-project-id-canonical
  (testing "Canonical project-id passes through unchanged"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp"]})
    (is (= "hive-mcp" (scope/resolve-project-id "hive-mcp")))))

(deftest test-resolve-project-id-unknown
  (testing "Unknown project-id passes through unchanged"
    (is (= "unknown-project" (scope/resolve-project-id "unknown-project")))))

(deftest test-resolve-project-id-nil
  (testing "nil returns nil"
    (is (nil? (scope/resolve-project-id nil)))))

(deftest test-reverse-alias-index
  (testing "Reverse alias index is populated by register-project-config!"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp" "hive-emacs"]})
    (let [idx (scope/get-alias-index)]
      (is (= {"emacs-mcp" "hive-mcp"
              "hive-emacs" "hive-mcp"} idx)))))

(deftest test-reverse-alias-index-no-aliases
  (testing "Config without aliases doesn't pollute alias index"
    (scope/register-project-config! "simple-project"
                                    {:project-id "simple-project"})
    (is (= {} (scope/get-alias-index)))))

(deftest test-reverse-alias-index-ignores-self-alias
  (testing "Alias same as project-id is skipped"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["hive-mcp" "emacs-mcp"]})
    ;; Only emacs-mcp should be indexed, not hive-mcp
    (let [idx (scope/get-alias-index)]
      (is (= {"emacs-mcp" "hive-mcp"} idx))
      (is (not (contains? idx "hive-mcp"))))))

(deftest test-reverse-alias-index-ignores-nil-alias
  (testing "nil values in aliases vector are skipped"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases [nil "emacs-mcp" nil]})
    (is (= {"emacs-mcp" "hive-mcp"} (scope/get-alias-index)))))

(deftest test-reverse-alias-index-ignores-non-string-alias
  (testing "Non-string values in aliases vector are skipped"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases [:keyword 42 "emacs-mcp"]})
    (is (= {"emacs-mcp" "hive-mcp"} (scope/get-alias-index)))))

;; =============================================================================
;; Alias-Aware Scope Functions
;; =============================================================================

(deftest test-visible-scopes-alias-resolves
  (testing "visible-scopes with alias resolves to canonical and walks hierarchy"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp"]})
    ;; Querying via alias should produce same result as canonical
    (is (= ["hive-mcp" "global"] (scope/visible-scopes "emacs-mcp")))
    (is (= (scope/visible-scopes "hive-mcp")
           (scope/visible-scopes "emacs-mcp")))))

(deftest test-visible-scope-tags-alias-resolves
  (testing "visible-scope-tags with alias uses canonical project-id in tags"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp"]})
    (is (= #{"scope:project:hive-mcp" "scope:global"}
           (scope/visible-scope-tags "emacs-mcp")))))

(deftest test-get-parent-scope-alias-resolves
  (testing "get-parent-scope with alias resolves to canonical first"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :parent-id "parent-org"
                                     :aliases ["emacs-mcp"]})
    ;; Alias should resolve, then find parent from canonical config
    (is (= "parent-org" (scope/get-parent-scope "emacs-mcp")))
    (is (= (scope/get-parent-scope "hive-mcp")
           (scope/get-parent-scope "emacs-mcp")))))

(deftest test-scope-contains-alias-resolves
  (testing "scope-contains? with alias resolves both sides"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp"]})
    ;; Alias = canonical (same scope after resolution)
    (is (scope/scope-contains? "hive-mcp" "emacs-mcp"))
    (is (scope/scope-contains? "emacs-mcp" "hive-mcp"))
    ;; Global contains alias
    (is (scope/scope-contains? "global" "emacs-mcp"))))

(deftest test-get-project-config-via-alias
  (testing "get-project-config returns canonical config when queried by alias"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp"]
                                     :kg-backend :datahike})
    (let [config (scope/get-project-config "emacs-mcp")]
      (is (some? config))
      (is (= "hive-mcp" (:project-id config)))
      (is (= :datahike (:kg-backend config))))))

;; =============================================================================
;; Deregister and Cleanup
;; =============================================================================

(deftest test-deregister-removes-config-and-aliases
  (testing "deregister-project-config! removes config and all alias mappings"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp" "hive-emacs"]})
    ;; Verify registered
    (is (some? (scope/get-project-config "hive-mcp")))
    (is (= 2 (count (scope/get-alias-index))))
    ;; Deregister
    (scope/deregister-project-config! "hive-mcp")
    ;; Verify removed
    (is (nil? (scope/get-project-config "hive-mcp")))
    (is (= {} (scope/get-alias-index)))
    ;; Alias no longer resolves
    (is (= "emacs-mcp" (scope/resolve-project-id "emacs-mcp")))))

(deftest test-deregister-nil-is-noop
  (testing "deregister-project-config! with nil is safe noop"
    (scope/deregister-project-config! nil)
    (is (= {} (scope/get-alias-index)))))

(deftest test-clear-config-cache-resets-all
  (testing "clear-config-cache! resets config cache, project configs, and alias index"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp"]})
    ;; Verify populated
    (is (some? (scope/get-project-config "hive-mcp")))
    (is (= 1 (count (scope/get-alias-index))))
    ;; Clear all
    (scope/clear-config-cache!)
    ;; Verify empty
    (is (nil? (scope/get-project-config "hive-mcp")))
    (is (= {} (scope/get-alias-index)))))

;; =============================================================================
;; Multi-Project Alias Scenarios
;; =============================================================================

(deftest test-multiple-projects-with-aliases
  (testing "Multiple projects each with their own aliases"
    (scope/register-project-config! "hive-mcp"
                                    {:project-id "hive-mcp"
                                     :aliases ["emacs-mcp"]})
    (scope/register-project-config! "my-app"
                                    {:project-id "my-app"
                                     :aliases ["my-app-old" "my-app-v1"]})
    ;; Each alias resolves to its own canonical
    (is (= "hive-mcp" (scope/resolve-project-id "emacs-mcp")))
    (is (= "my-app" (scope/resolve-project-id "my-app-old")))
    (is (= "my-app" (scope/resolve-project-id "my-app-v1")))
    ;; Index has all mappings
    (is (= 3 (count (scope/get-alias-index))))))

(deftest test-alias-with-explicit-parent
  (testing "Alias resolves correctly with explicit parent chain"
    (scope/register-project-config! "parent-org" {:project-id "parent-org"})
    (scope/register-project-config! "child-project"
                                    {:project-id "child-project"
                                     :parent-id "parent-org"
                                     :aliases ["child-old-name"]})
    ;; Querying visible-scopes via alias should walk the full chain
    (is (= ["child-project" "parent-org" "global"]
           (scope/visible-scopes "child-old-name")))
    ;; Parent should contain the alias (resolves to child)
    (is (scope/scope-contains? "parent-org" "child-old-name"))))
