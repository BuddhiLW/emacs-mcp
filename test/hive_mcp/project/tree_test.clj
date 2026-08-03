(ns hive-mcp.project.tree-test
  "Unit tests for project tree discovery and hierarchy.

   HCR Wave 3: Tests for descendant scope tag resolution.

   Tests cover:
   - build-project-tree: Constructs tree from flat entity list
   - get-descendants: BFS traversal to find all children
   - get-ancestors: Walk up the tree to find parents
   - get-descendant-scope-tags: Convert descendant IDs to scope tags
   - get-descendant-scopes: Get raw descendant project IDs"
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.project.tree :as tree]))

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-entities
  "Sample project entities representing a hierarchy:

   hive-mcp (root)
   ├── hive-mcp:agora
   │   └── hive-mcp:agora:consensus
   ├── hive-mcp:tools
   │   ├── hive-mcp:tools:memory
   │   └── hive-mcp:tools:wave
   └── hive-mcp:crystal

   other-project (root)
   └── other-project:sub"
  [{:project/id "hive-mcp"
    :project/path "/home/user/hive-mcp"
    :project/type :clojure}
   {:project/id "hive-mcp:agora"
    :project/path "/home/user/hive-mcp/src/agora"
    :project/parent-id "hive-mcp"
    :project/type :clojure}
   {:project/id "hive-mcp:agora:consensus"
    :project/path "/home/user/hive-mcp/src/agora/consensus"
    :project/parent-id "hive-mcp:agora"
    :project/type :clojure}
   {:project/id "hive-mcp:tools"
    :project/path "/home/user/hive-mcp/src/tools"
    :project/parent-id "hive-mcp"
    :project/type :clojure}
   {:project/id "hive-mcp:tools:memory"
    :project/path "/home/user/hive-mcp/src/tools/memory"
    :project/parent-id "hive-mcp:tools"
    :project/type :clojure}
   {:project/id "hive-mcp:tools:wave"
    :project/path "/home/user/hive-mcp/src/tools/wave"
    :project/parent-id "hive-mcp:tools"
    :project/type :clojure}
   {:project/id "hive-mcp:crystal"
    :project/path "/home/user/hive-mcp/src/crystal"
    :project/parent-id "hive-mcp"
    :project/type :clojure}
   {:project/id "other-project"
    :project/path "/home/user/other"
    :project/type :clojure}
   {:project/id "other-project:sub"
    :project/path "/home/user/other/sub"
    :project/parent-id "other-project"
    :project/type :clojure}])

;; =============================================================================
;; Test build-project-tree
;; =============================================================================

(deftest test-build-project-tree-roots
  (testing "Identifies root projects (no parent)"
    (let [tree (tree/build-project-tree sample-entities)]
      (is (= #{"hive-mcp" "other-project"}
             (set (:roots tree)))))))

(deftest test-build-project-tree-by-id
  (testing "Indexes entities by project ID"
    (let [tree (tree/build-project-tree sample-entities)]
      (is (= "hive-mcp:agora"
             (:project/id (get (:by-id tree) "hive-mcp:agora"))))
      (is (= "/home/user/hive-mcp/src/agora"
             (:project/path (get (:by-id tree) "hive-mcp:agora")))))))

(deftest test-build-project-tree-children
  (testing "Maps parent to children correctly"
    (let [tree (tree/build-project-tree sample-entities)]
      ;; hive-mcp has 3 direct children
      (is (= #{"hive-mcp:agora" "hive-mcp:tools" "hive-mcp:crystal"}
             (set (get (:children tree) "hive-mcp"))))
      ;; hive-mcp:tools has 2 children
      (is (= #{"hive-mcp:tools:memory" "hive-mcp:tools:wave"}
             (set (get (:children tree) "hive-mcp:tools"))))
      ;; leaf node has no children
      (is (empty? (get (:children tree) "hive-mcp:crystal"))))))

;; =============================================================================
;; Test get-descendants
;; =============================================================================

(deftest test-get-descendants-root
  (testing "Gets all descendants of root project"
    (let [tree (tree/build-project-tree sample-entities)
          descendants (tree/get-descendants tree "hive-mcp")]
      ;; Should include all nested children: agora, agora:consensus, tools,
      ;; tools:memory, tools:wave, crystal
      (is (= 6 (count descendants)))
      (is (= #{"hive-mcp:agora" "hive-mcp:agora:consensus"
               "hive-mcp:tools" "hive-mcp:tools:memory" "hive-mcp:tools:wave"
               "hive-mcp:crystal"}
             (set descendants))))))

(deftest test-get-descendants-intermediate
  (testing "Gets descendants of intermediate node"
    (let [tree (tree/build-project-tree sample-entities)
          descendants (tree/get-descendants tree "hive-mcp:tools")]
      ;; tools has memory and wave children
      (is (= #{"hive-mcp:tools:memory" "hive-mcp:tools:wave"}
             (set descendants))))))

(deftest test-get-descendants-leaf
  (testing "Leaf node has no descendants"
    (let [tree (tree/build-project-tree sample-entities)
          descendants (tree/get-descendants tree "hive-mcp:crystal")]
      (is (empty? descendants)))))

(deftest test-get-descendants-nonexistent
  (testing "Nonexistent project returns empty"
    (let [tree (tree/build-project-tree sample-entities)
          descendants (tree/get-descendants tree "nonexistent")]
      (is (empty? descendants)))))

;; =============================================================================
;; Test get-ancestors
;; =============================================================================

(deftest test-get-ancestors-root
  (testing "Root project has no ancestors"
    (let [tree (tree/build-project-tree sample-entities)
          ancestors (tree/get-ancestors tree "hive-mcp")]
      (is (empty? ancestors)))))

(deftest test-get-ancestors-depth-one
  (testing "Depth-1 project has root as ancestor"
    (let [tree (tree/build-project-tree sample-entities)
          ancestors (tree/get-ancestors tree "hive-mcp:agora")]
      (is (= ["hive-mcp"] ancestors)))))

(deftest test-get-ancestors-depth-two
  (testing "Depth-2 project has two ancestors"
    (let [tree (tree/build-project-tree sample-entities)
          ancestors (tree/get-ancestors tree "hive-mcp:tools:memory")]
      (is (= ["hive-mcp:tools" "hive-mcp"] ancestors)))))

(deftest test-get-ancestors-depth-three
  (testing "Depth-3 project has three ancestors"
    (let [tree (tree/build-project-tree sample-entities)
          ancestors (tree/get-ancestors tree "hive-mcp:agora:consensus")]
      (is (= ["hive-mcp:agora" "hive-mcp"] ancestors)))))

;; =============================================================================
;; HCR Wave 3: Test Scope Tag Functions
;; =============================================================================

;; Note: These tests verify the pure functions using the tree structure.
;; The get-descendant-scope-tags and get-descendant-scopes public functions
;; query DataScript internally, which is tested in scope_test.clj.

(deftest test-descendant-scope-tags-format
  (testing "Descendant IDs convert to scope:project:X format"
    (let [tree (tree/build-project-tree sample-entities)
          descendant-ids (tree/get-descendants tree "hive-mcp:tools")
          scope-tags (set (map #(str "scope:project:" %) descendant-ids))]
      (is (= #{"scope:project:hive-mcp:tools:memory"
               "scope:project:hive-mcp:tools:wave"}
             scope-tags)))))

(deftest test-full-hierarchy-descendants
  (testing "Full hierarchy walk from root captures entire subtree"
    (let [tree (tree/build-project-tree sample-entities)
          all-hive (tree/get-descendants tree "hive-mcp")
          all-other (tree/get-descendants tree "other-project")]
      ;; hive-mcp has 6 descendants
      (is (= 6 (count all-hive)))
      ;; other-project has 1 descendant
      (is (= 1 (count all-other)))
      (is (= ["other-project:sub"] all-other)))))

;; =============================================================================
;; Integration: Tree + Scope Tag Combination
;; =============================================================================

(deftest test-descendants-do-not-include-self
  (testing "Descendants do not include the queried project itself"
    (let [tree (tree/build-project-tree sample-entities)
          descendants (tree/get-descendants tree "hive-mcp")]
      (is (not (contains? (set descendants) "hive-mcp"))))))

(deftest test-ancestors-do-not-include-self
  (testing "Ancestors do not include the queried project itself"
    (let [tree (tree/build-project-tree sample-entities)
          ancestors (tree/get-ancestors tree "hive-mcp:agora:consensus")]
      (is (not (contains? (set ancestors) "hive-mcp:agora:consensus"))))))

(deftest test-cross-tree-isolation
  (testing "Different root trees don't see each other's descendants"
    (let [tree (tree/build-project-tree sample-entities)
          hive-descendants (set (tree/get-descendants tree "hive-mcp"))
          other-descendants (set (tree/get-descendants tree "other-project"))]
      ;; No overlap
      (is (empty? (clojure.set/intersection hive-descendants other-descendants))))))

;; =============================================================================
;; Duplicate project-id election (git worktrees)
;; =============================================================================

(def ^:private dedupe-by-project-id #'tree/dedupe-by-project-id)

(defn- disc [path pid linked?]
  {:path path :config {:project-id pid} :linked-worktree? linked?})

(def ^:private carto-forest
  [(disc "/w/hive-carto" "hive-carto" false)
   (disc "/w/hive-carto-base" "hive-carto" true)
   (disc "/w/hive-carto-wt" "hive-carto" true)
   (disc "/w/hive-mcp" "hive-mcp" false)])

(deftest test-dedupe-elects-primary-checkout
  (testing "The primary checkout wins over linked worktrees sharing its id"
    (let [{:keys [kept shadowed]} (dedupe-by-project-id carto-forest)]
      (is (= #{"/w/hive-carto" "/w/hive-mcp"} (set (map :path kept))))
      (is (= #{"/w/hive-carto-base" "/w/hive-carto-wt"} (set (map :path shadowed))))))
  (testing "Every discovery is either kept or shadowed — none is dropped"
    (let [{:keys [kept shadowed]} (dedupe-by-project-id carto-forest)]
      (is (= (count carto-forest) (+ (count kept) (count shadowed)))))))

(deftest test-dedupe-is-order-independent
  (testing "Discovery order does not decide the winner"
    (is (= 1 (count (distinct (for [_ (range 50)]
                                (->> (shuffle carto-forest)
                                     dedupe-by-project-id
                                     :kept
                                     (map :path)
                                     sort
                                     vec)))))))) 

(deftest test-dedupe-total-when-no-primary
  (testing "All-linked duplicates still elect one deterministic winner"
    (let [only-linked [(disc "/w/bbb" "p" true) (disc "/w/a" "p" true)]
          {:keys [kept shadowed]} (dedupe-by-project-id only-linked)]
      (is (= ["/w/a"] (map :path kept)))
      (is (= ["/w/bbb"] (map :path shadowed))))))

(deftest test-dedupe-passes-through-idless-discoveries
  (testing "A config with no :project-id is never shadowed against another"
    (let [idless [{:path "/w/x" :config {} :linked-worktree? false}
                  {:path "/w/y" :config {} :linked-worktree? false}]
          {:keys [kept shadowed]} (dedupe-by-project-id idless)]
      (is (= 2 (count kept)))
      (is (empty? shadowed)))))
