(ns hive-mcp.tools.catchup.spawn-test
  "Tests for spawn context injection — :full, :hints, and :ref modes.

   W3 Task 2.2: Validates :ref mode alongside existing :full/:hints."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.tools.catchup.spawn :as spawn]
            [hive-mcp.channel.context-store :as ctx-store]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn context-store-fixture
  "Reset context-store before each test."
  [f]
  (ctx-store/reset-all!)
  (try (f)
       (finally (ctx-store/reset-all!))))

(use-fixtures :each context-store-fixture)

;; =============================================================================
;; Shared mocks
;; =============================================================================

(def ^:private mock-git-info
  {:branch "main" :uncommitted false :last-commit "abc123"})

(defmacro with-base-mocks
  "Wrap body with standard store/scope/git mocks."
  [project-id & body]
  `(with-redefs [hive-mcp.protocols.memory/store-set? (fn [] true)
                 hive-mcp.chroma.core/embedding-configured? (fn [] true)
                 hive-mcp.tools.memory.scope/get-current-project-id (fn [~'_] ~project-id)
                 hive-mcp.tools.catchup.scope/get-current-project-name (fn [~'_] ~project-id)
                 hive-mcp.tools.catchup.git/gather-git-info (fn [~'_] mock-git-info)]
     ~@body))

;; =============================================================================
;; :ref mode tests
;; =============================================================================

(deftest test-ref-mode-with-cached-entries
  (testing ":ref mode returns compressed reconstruction when refs are cached"
    (let [ax-id (ctx-store/context-put! [{:id "ax-1" :content "Test axiom"}]
                                        :tags #{"catchup" "axioms" "test-proj"}
                                        :ttl-ms 60000)
          cv-id (ctx-store/context-put! [{:id "cv-1" :content "Test convention"}]
                                        :tags #{"catchup" "priority-conventions" "test-proj"}
                                        :ttl-ms 60000)
          dc-id (ctx-store/context-put! [{:id "dc-1" :content "Test decision"}]
                                        :tags #{"catchup" "decisions" "test-proj"}
                                        :ttl-ms 60000)]
      (with-base-mocks "test-proj"
        (let [result (spawn/spawn-context "/tmp" {:mode :ref})]
          (is (string? result) "Returns a string")
          ;; compressed reconstruction produces compact markdown, not ref table
          (is (str/includes? result "Reconstructed Context") "Contains Compressed header")
          (is (str/includes? result "Axioms") "Contains axioms section")
          (is (str/includes? result "Decisions") "Contains decisions summary")
          (is (str/includes? result "Priority Conventions") "Contains priority conventions summary")
          (is (str/includes? result "Branch") "Contains git info")
          (is (< (count result) 2000) "Compact: under 2000 chars"))))))

(deftest test-ref-mode-fallback-to-full
  (testing ":ref mode falls back to :full when no cached refs exist"
    (with-redefs [hive-mcp.chroma.core/embedding-configured? (fn [] true)
                  hive-mcp.tools.memory.scope/get-current-project-id (fn [_] "tp")
                  hive-mcp.tools.catchup.scope/get-current-project-name (fn [_] "tp")
                  hive-mcp.tools.catchup.scope/query-axioms
                  (fn [_] [{:id "ax-1" :content "Fallback axiom" :tags ["axiom"]}])
                  hive-mcp.tools.catchup.scope/query-scoped-entries
                  (fn [_ _ _ _] [])
                  hive-mcp.knowledge-graph.disc/top-stale-files
                  (fn [& _] [])
                  hive-mcp.tools.catchup.git/gather-git-info
                  (fn [_] mock-git-info)]
      (let [result (spawn/spawn-context "/tmp" {:mode :ref})]
        (is (string? result) "Returns a string (fell back to :full)")
        (is (str/includes? result "Project Context") "Contains full mode header")
        (is (not (str/includes? result "Ref Mode")) "Does NOT contain ref mode header")
        (is (str/includes? result "Fallback axiom") "Contains full axiom content")))))

(deftest test-ref-mode-ignores-other-projects
  (testing ":ref mode only picks up refs for the current project"
    ;; Populate refs for a DIFFERENT project
    (ctx-store/context-put! [{:id "ax-other"}]
                            :tags #{"catchup" "axioms" "other-project"}
                            :ttl-ms 60000)
    (with-redefs [hive-mcp.chroma.core/embedding-configured? (fn [] true)
                  hive-mcp.tools.memory.scope/get-current-project-id (fn [_] "my-proj")
                  hive-mcp.tools.catchup.scope/get-current-project-name (fn [_] "my-proj")
                  hive-mcp.tools.catchup.scope/query-axioms
                  (fn [_] [{:id "ax-1" :content "My axiom" :tags ["axiom"]}])
                  hive-mcp.tools.catchup.scope/query-scoped-entries
                  (fn [_ _ _ _] [])
                  hive-mcp.knowledge-graph.disc/top-stale-files
                  (fn [& _] [])
                  hive-mcp.tools.catchup.git/gather-git-info
                  (fn [_] mock-git-info)]
      (let [result (spawn/spawn-context "/tmp" {:mode :ref})]
        ;; Should fall back to :full because no refs for "my-proj"
        (is (str/includes? result "Project Context") "Falls back to full (no matching refs)")
        (is (not (str/includes? result "Ref Mode")) "Not ref mode")))))

;; =============================================================================
;; Backward compatibility tests
;; =============================================================================

(deftest test-full-mode-backward-compat
  (testing ":full mode (default) still works after :ref addition"
    (with-redefs [hive-mcp.chroma.core/embedding-configured? (fn [] true)
                  hive-mcp.tools.memory.scope/get-current-project-id (fn [_] "tp")
                  hive-mcp.tools.catchup.scope/get-current-project-name (fn [_] "tp")
                  hive-mcp.tools.catchup.scope/query-axioms
                  (fn [_] [{:id "ax-1" :content "Full mode axiom" :tags ["axiom"]}])
                  hive-mcp.tools.catchup.scope/query-scoped-entries
                  (fn [_ _ _ _] [])
                  hive-mcp.knowledge-graph.disc/top-stale-files
                  (fn [& _] [])
                  hive-mcp.tools.catchup.git/gather-git-info
                  (fn [_] mock-git-info)]
      ;; Explicit :full
      (let [r-full (spawn/spawn-context "/tmp" {:mode :full})]
        (is (string? r-full))
        (is (str/includes? r-full "Project Context"))
        (is (not (str/includes? r-full "Memory Hints")))
        (is (not (str/includes? r-full "Ref Mode"))))
      ;; Default (no opts)
      (let [r-default (spawn/spawn-context "/tmp")]
        (is (string? r-default))
        (is (str/includes? r-default "Project Context"))))))

(deftest test-hints-mode-backward-compat
  (testing ":hints mode still works after :ref addition"
    (with-redefs [hive-mcp.chroma.core/embedding-configured? (fn [] true)
                  hive-mcp.tools.memory.scope/get-current-project-id (fn [_] "tp")
                  hive-mcp.tools.catchup.scope/get-current-project-name (fn [_] "tp")
                  hive-mcp.agent.hints/query-axioms
                  (fn [_] [{:id "ax-1"}])
                  hive-mcp.agent.hints/query-scoped-entries
                  (fn [type _ _ _]
                    (case type
                      "convention" [{:id "cv-1"}]
                      "decision" [{:id "dc-1"}]
                      []))
                  hive-mcp.knowledge-graph.edges/edge-stats
                  (fn [] {:total-edges 0})
                  hive-mcp.tools.catchup.git/gather-git-info
                  (fn [_] mock-git-info)]
      (let [result (spawn/spawn-context "/tmp" {:mode :hints})]
        (is (string? result))
        (is (str/includes? result "Memory Hints"))
        (is (not (str/includes? result "Project Context")))
        (is (not (str/includes? result "Ref Mode")))))))

;; =============================================================================
;; Edge cases
;; =============================================================================

(deftest test-chroma-not-configured-all-modes
  (testing "All modes return nil when Chroma not configured"
    (with-redefs [hive-mcp.chroma.core/embedding-configured? (fn [] false)]
      (is (nil? (spawn/spawn-context "/tmp" {:mode :full})))
      (is (nil? (spawn/spawn-context "/tmp" {:mode :hints})))
      (is (nil? (spawn/spawn-context "/tmp" {:mode :ref})))
      (is (nil? (spawn/spawn-context "/tmp"))))))

(deftest test-ref-mode-partial-refs
  (testing ":ref mode works with partial refs (e.g., only axioms cached)"
    (ctx-store/context-put! [{:id "ax-only"}]
                            :tags #{"catchup" "axioms" "partial-proj"}
                            :ttl-ms 60000)
    (with-base-mocks "partial-proj"
      (let [result (spawn/spawn-context "/tmp" {:mode :ref})]
        (is (string? result))
        ;; compressed reconstruction produces "Reconstructed Context" header
        (is (str/includes? result "Reconstructed Context") "Uses compressed reconstruction")
        (is (str/includes? result "Axioms") "Contains Axioms category")))))
