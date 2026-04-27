(ns hive-mcp.tools.memory-kanban-hcr-test
  "HCR Wave 5 tests for kanban descendant aggregation with cached hierarchy tree.

   Tests cover:
   - resolve-project-ids-with-descendants: tree-based descendant resolution
   - task->slim with multi-project flag: adds :project field
   - extract-project-id-from-tags: parses scope:project:X from tags
   - handle-mem-kanban-list-slim: descendant-aware listing
   - handle-mem-kanban-stats: per-project breakdown in :by-project

   These tests verify the pure helper functions using mock data,
   without requiring a live Chroma connection."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.project.tree :as tree]
            [hive-mcp.tools.kanban.predicates :as kp]
            [hive-mcp.tools.kanban.transitions :as kt]
            [hive-mcp.tools.memory-kanban]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Private fn accessors
;; =============================================================================
;; Helpers extracted to hive-mcp.tools.kanban.{predicates,transitions} as part
;; of the event-driven kanban refactor; resolve-project-ids-with-descendants
;; remains a private query helper inside memory-kanban.

(def ^:private resolve-project-ids
  @(resolve 'hive-mcp.tools.memory-kanban/resolve-project-ids-with-descendants))

(def ^:private extract-project-id kt/extract-project-id-from-tags)
(def ^:private task->slim         kt/task->slim)
(def ^:private kanban-entry?      kp/kanban-entry?)

;; =============================================================================
;; Test Fixtures: Inject mock tree cache
;; =============================================================================

(def ^:private mock-tree
  "Mock project hierarchy:
     hive (root)
     ├── hive-mcp
     │   └── hive-agent-bridge
     └── hive-ext (leaf)"
  {:roots ["hive"]
   :by-id {"hive"               {:project/id "hive" :project/type :workspace}
           "hive-mcp"           {:project/id "hive-mcp" :project/parent-id "hive" :project/type :clojure}
           "hive-agent-bridge"  {:project/id "hive-agent-bridge" :project/parent-id "hive-mcp" :project/type :clojure}
           "hive-ext"     {:project/id "hive-ext" :project/parent-id "hive" :project/type :clojure}}
   :children {"hive"     ["hive-mcp" "hive-ext"]
              "hive-mcp" ["hive-agent-bridge"]}})

(defn inject-tree-fixture
  "Inject mock tree cache for tests, restore after."
  [f]
  (let [cache-atom (deref (var hive-mcp.project.tree/tree-cache))
        original @cache-atom]
    (reset! cache-atom mock-tree)
    (try
      (f)
      (finally
        (reset! cache-atom original)))))

(use-fixtures :each inject-tree-fixture)

;; =============================================================================
;; Test Data: Mock kanban entries
;; =============================================================================

(def ^:private entry-hive-mcp-1
  {:id "kb-hive-mcp-1"
   :content {:task-type "kanban" :title "Fix bug in MCP" :status "todo" :priority "high"}
   :tags ["kanban" "todo" "priority-high" "scope:project:hive-mcp"]
   :project-id "hive-mcp"})

(def ^:private entry-hive-mcp-2
  {:id "kb-hive-mcp-2"
   :content {:task-type "kanban" :title "Add feature" :status "doing" :priority "medium"}
   :tags ["kanban" "doing" "priority-medium" "scope:project:hive-mcp"]
   :project-id "hive-mcp"})

(def ^:private entry-bridge-1
  {:id "kb-bridge-1"
   :content {:task-type "kanban" :title "Bridge test" :status "todo" :priority "low"}
   :tags ["kanban" "todo" "priority-low" "scope:project:hive-agent-bridge"]
   :project-id "hive-agent-bridge"})

(def ^:private entry-knowledge-1
  {:id "kb-knowledge-1"
   :content {:task-type "kanban" :title "Knowledge task" :status "review" :priority "medium"}
   :tags ["kanban" "review" "priority-medium" "scope:project:hive-ext"]
   :project-id "hive-ext"})

(def ^:private non-kanban-entry
  {:id "note-1"
   :content {:some "note" :not "kanban"}
   :tags ["scope:project:hive-mcp"]
   :project-id "hive-mcp"})

;; =============================================================================
;; resolve-project-ids-with-descendants Tests
;; =============================================================================

(deftest test-resolve-parent-includes-descendants
  (testing "Parent project resolves to self + all descendants"
    (let [result (resolve-project-ids "hive-mcp")]
      (is (vector? result) "Returns a vector")
      (is (= "hive-mcp" (first result)) "Self is first element")
      (is (contains? (set result) "hive-agent-bridge") "Includes child")
      (is (= 2 (count result)) "hive-mcp has exactly 1 descendant"))))

(deftest test-resolve-root-includes-all
  (testing "Root project resolves to self + all nested descendants"
    (let [result (resolve-project-ids "hive")]
      (is (vector? result))
      (is (= "hive" (first result)))
      (is (= 4 (count result)) "hive has 3 descendants: hive-mcp, hive-agent-bridge, hive-ext")
      (is (= #{"hive" "hive-mcp" "hive-agent-bridge" "hive-ext"}
             (set result))))))

(deftest test-resolve-leaf-returns-nil
  (testing "Leaf project (no children) returns nil"
    (is (nil? (resolve-project-ids "hive-agent-bridge"))
        "Leaf with no descendants returns nil (caller should use singular :project-id)")))

(deftest test-resolve-global-returns-nil
  (testing "Global project-id returns nil"
    (is (nil? (resolve-project-ids "global"))
        "Global should never aggregate descendants")))

(deftest test-resolve-nil-returns-nil
  (testing "Nil project-id returns nil"
    (is (nil? (resolve-project-ids nil)))))

(deftest test-resolve-unknown-returns-nil
  (testing "Unknown project-id (not in tree) returns nil"
    (is (nil? (resolve-project-ids "nonexistent-project"))
        "Project not in tree has no descendants")))

;; =============================================================================
;; extract-project-id-from-tags Tests
;; =============================================================================

(deftest test-extract-project-id-from-scope-tag
  (testing "Extracts project-id from scope:project:X tag"
    (is (= "hive-mcp"
           (extract-project-id entry-hive-mcp-1)))))

(deftest test-extract-project-id-from-bridge-entry
  (testing "Extracts project-id from child project entry"
    (is (= "hive-agent-bridge"
           (extract-project-id entry-bridge-1)))))

(deftest test-extract-project-id-no-scope-tag
  (testing "Returns nil when entry has no scope:project: tag"
    (let [entry {:tags ["kanban" "todo" "priority-medium"]}]
      (is (nil? (extract-project-id entry))
          "Entry without scope tag returns nil"))))

(deftest test-extract-project-id-global-scope
  (testing "scope:global is NOT treated as a project scope"
    (let [entry {:tags ["kanban" "todo" "scope:global"]}]
      (is (nil? (extract-project-id entry))
          "scope:global should not match scope:project: pattern"))))

;; =============================================================================
;; task->slim Tests
;; =============================================================================

(deftest test-slim-basic-fields
  (testing "Slim format includes id, title, status, priority"
    (let [slim (task->slim entry-hive-mcp-1)]
      (is (= "kb-hive-mcp-1" (:id slim)))
      (is (= "Fix bug in MCP" (:title slim)))
      (is (= "todo" (:status slim)))
      (is (= "high" (:priority slim))))))

(deftest test-slim-no-project-by-default
  (testing "Default slim format omits :project field"
    (let [slim (task->slim entry-hive-mcp-1)]
      (is (not (contains? slim :project))
          "Single-project mode should NOT include :project"))))

(deftest test-slim-multi-project-includes-project
  (testing "Multi-project slim format includes :project field"
    (let [slim (task->slim entry-hive-mcp-1 true)]
      (is (contains? slim :project))
      (is (= "hive-mcp" (:project slim))))))

(deftest test-slim-multi-project-bridge-entry
  (testing "Multi-project slim correctly labels child project"
    (let [slim (task->slim entry-bridge-1 true)]
      (is (= "hive-agent-bridge" (:project slim))))))

(deftest test-slim-string-key-content
  (testing "Handles content with string keys (from JSON roundtrip)"
    (let [entry {:id "kb-str-1"
                 :content {"task-type" "kanban" "title" "String keys" "status" "doing" "priority" "low"}
                 :tags ["kanban" "doing" "scope:project:hive-mcp"]}
          slim (task->slim entry)]
      (is (= "String keys" (:title slim)))
      (is (= "doing" (:status slim)))
      (is (= "low" (:priority slim))))))

;; =============================================================================
;; kanban-entry? Tests
;; =============================================================================

(deftest test-kanban-entry-keyword-key
  (testing "Detects kanban entry with keyword :task-type key"
    (is (true? (kanban-entry? entry-hive-mcp-1)))))

(deftest test-kanban-entry-string-key
  (testing "Detects kanban entry with string \"task-type\" key"
    (let [entry {:content {"task-type" "kanban"}}]
      (is (true? (kanban-entry? entry))))))

(deftest test-non-kanban-entry
  (testing "Rejects non-kanban entries"
    (is (false? (kanban-entry? non-kanban-entry)))))

(deftest test-nil-content
  (testing "Handles nil content gracefully"
    (is (false? (kanban-entry? {:content nil})))))

;; =============================================================================
;; Tree cache integration: has-children? / get-descendant-ids
;; =============================================================================

(deftest test-has-children-parent
  (testing "Parent project reports having children"
    (is (true? (tree/has-children? "hive-mcp"))
        "hive-mcp has hive-agent-bridge as child")))

(deftest test-has-children-leaf
  (testing "Leaf project reports no children"
    (is (not (tree/has-children? "hive-agent-bridge"))
        "hive-agent-bridge is a leaf")))

(deftest test-has-children-root
  (testing "Root project reports having children"
    (is (true? (tree/has-children? "hive"))
        "hive has hive-mcp and hive-ext as children")))

(deftest test-descendant-ids-set
  (testing "get-descendant-ids returns set of all nested descendants"
    (let [ids (tree/get-descendant-ids "hive")]
      (is (set? ids))
      (is (= #{"hive-mcp" "hive-agent-bridge" "hive-ext"} ids)))))

(deftest test-descendant-ids-leaf-empty
  (testing "Leaf project returns empty set"
    (is (= #{} (tree/get-descendant-ids "hive-ext")))))

;; =============================================================================
;; Scope tag generation from tree
;; =============================================================================

(deftest test-descendant-scope-tags
  (testing "get-descendant-scope-tags produces scope:project:X format"
    (let [tags (tree/get-descendant-scope-tags "hive-mcp")]
      (is (set? tags))
      (is (= #{"scope:project:hive-agent-bridge"} tags)))))

(deftest test-descendant-scope-tags-root
  (testing "Root's descendant scope tags include all nested projects"
    (let [tags (tree/get-descendant-scope-tags "hive")]
      (is (= #{"scope:project:hive-mcp"
               "scope:project:hive-agent-bridge"
               "scope:project:hive-ext"} tags)))))

(deftest test-descendant-scope-tags-global-returns-nil
  (testing "Global project-id returns nil scope tags"
    (is (nil? (tree/get-descendant-scope-tags "global")))))
