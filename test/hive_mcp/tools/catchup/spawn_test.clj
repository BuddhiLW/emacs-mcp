(ns hive-mcp.tools.catchup.spawn-test
  "Tests for spawn context injection — :full, :hints, and :ref modes.

   W3 Task 2.2: Validates :ref mode alongside existing :full/:hints."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.tools.catchup.spawn :as spawn]
            [hive-mcp.channel.context-store :as ctx-store]
            [hive-mcp.test.stub.extensions :as ext-stub]
            [hive-mcp.test.stub.memory-store :as stub]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn context-store-fixture
  "Reset context-store before each test."
  [f]
  (ctx-store/reset-all!)
  (try (f)
       (finally (ctx-store/reset-all!))))

(use-fixtures :each stub/with-stub-store context-store-fixture)

;; =============================================================================
;; Shared mocks
;; =============================================================================

(def ^:private mock-git-info
  {:branch "main" :uncommitted false :last-commit "abc123"})

(defmacro with-base-mocks
  "Wrap body with the scope/git seams spawn-context reads.

   The memory store is NOT mocked here — the stub fixture registers a real
   one through the port, so `mem-proto/store-set?` answers truthfully."
  [project-id & body]
  `(with-redefs [hive-mcp.tools.memory.scope/get-current-project-id (fn [~'_] ~project-id)
                 hive-mcp.tools.catchup.scope/get-current-project-name (fn [~'_] ~project-id)
                 hive-mcp.tools.catchup.git/gather-git-info (fn [~'_] mock-git-info)]
     ~@body))

(defmacro with-full-mode-mocks
  "with-base-mocks plus the query seams the :full renderer fans out to."
  [project-id axioms & body]
  `(with-redefs [hive-mcp.tools.memory.scope/get-current-project-id (fn [~'_] ~project-id)
                 hive-mcp.tools.catchup.scope/get-current-project-name (fn [~'_] ~project-id)
                 hive-mcp.tools.catchup.scope/query-axioms (fn [~'_] ~axioms)
                 hive-mcp.tools.catchup.scope/query-scoped-entries (fn [~'_ ~'_ ~'_ ~'_] [])
                 hive-mcp.knowledge-graph.disc/top-stale-files (fn [& ~'_] [])
                 hive-mcp.tools.catchup.git/gather-git-info (fn [~'_] mock-git-info)]
     ~@body))

;; =============================================================================
;; :ref mode tests
;; =============================================================================

(defn- unique-project
  "A project id no other test in this run can collide with.

   `ctx-store/reset-all!` is guarded by `when-not-coordinator`, so in a hot
   JVM the reset fixture is a no-op and entries survive between tests. Tagging
   each test's refs with its own project id isolates by construction instead."
  [prefix]
  (str prefix "-" (System/nanoTime)))

(defn- seed-three-categories!
  "Cache one ref per category under PROJECT-ID. Returns the ctx-ids."
  [project-id]
  {:axioms (ctx-store/context-put! [{:id "ax-1" :content "Test axiom"}]
                                   :tags #{"catchup" "axioms" project-id}
                                   :ttl-ms 60000)
   :priority-conventions (ctx-store/context-put! [{:id "cv-1" :content "Test convention"}]
                                                 :tags #{"catchup" "priority-conventions" project-id}
                                                 :ttl-ms 60000)
   :decisions (ctx-store/context-put! [{:id "dc-1" :content "Test decision"}]
                                      :tags #{"catchup" "decisions" project-id}
                                      :ttl-ms 60000)})

(deftest test-ref-mode-with-cached-entries-no-extension
  (testing ":ref mode emits the ref table when no reconstruction extension is registered"
    ;; :cr/i is an OPTIONAL extension point. With nothing registered,
    ;; reconstruct-context returns "" and spawn-context must degrade to the
    ;; self-describing ref table — this is the shipped-core behaviour.
    (let [proj (unique-project "test-proj")
          ids (seed-three-categories! proj)]
      (ext-stub/without-extensions [:cr/i]
        (fn []
          (with-base-mocks proj
            (let [result (spawn/spawn-context "/tmp" {:mode :ref})]
              (is (string? result) "Returns a string")
              (is (str/includes? result "Ref Mode") "Falls back to the ref table")
              (doseq [[category ctx-id] ids]
                (is (str/includes? result ctx-id)
                    (str "Lists the " (name category) " ref")))
              (is (str/includes? result "Branch") "Contains git info")
              (is (< (count result) 2000) "Compact: under 2000 chars"))))))))

(deftest test-ref-mode-with-cached-entries-reconstructed
  (testing ":ref mode returns compressed reconstruction when the extension is registered"
    (let [proj (unique-project "recon-proj")]
      (seed-three-categories! proj)
      (ext-stub/with-extensions
        {:cr/i (fn [ctx-refs _kg-node-ids _scope]
                 (str "## Reconstructed Context\n\n"
                      "### Axioms\n- " (:axioms ctx-refs) "\n"
                      "### Decisions\n- " (:decisions ctx-refs) "\n"
                      "### Priority Conventions\n- " (:priority-conventions ctx-refs) "\n"))}
        (fn []
          (with-base-mocks proj
          (let [result (spawn/spawn-context "/tmp" {:mode :ref})]
            (is (string? result) "Returns a string")
            (is (str/includes? result "Reconstructed Context") "Uses the extension's output")
            (is (str/includes? result "Axioms") "Contains axioms section")
            (is (str/includes? result "Decisions") "Contains decisions summary")
            (is (str/includes? result "Priority Conventions") "Contains priority conventions summary")
            (is (not (str/includes? result "Ref Mode")) "Does NOT fall back to the ref table")
            (is (str/includes? result "Branch") "Git status is appended")
            (is (< (count result) 2000) "Compact: under 2000 chars"))))))))

(deftest test-ref-mode-fallback-to-full
  (testing ":ref mode falls back to :full when no cached refs exist"
    (with-full-mode-mocks "tp" [{:id "ax-1" :content "Fallback axiom" :tags ["axiom"]}]
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
    (with-full-mode-mocks "my-proj" [{:id "ax-1" :content "My axiom" :tags ["axiom"]}]
      (let [result (spawn/spawn-context "/tmp" {:mode :ref})]
        ;; Should fall back to :full because no refs for "my-proj"
        (is (str/includes? result "Project Context") "Falls back to full (no matching refs)")
        (is (not (str/includes? result "Ref Mode")) "Not ref mode")))))

;; =============================================================================
;; Backward compatibility tests
;; =============================================================================

(deftest test-full-mode-backward-compat
  (testing ":full mode (default) still works after :ref addition"
    (with-full-mode-mocks "tp" [{:id "ax-1" :content "Full mode axiom" :tags ["axiom"]}]
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
    (with-redefs [hive-mcp.tools.memory.scope/get-current-project-id (fn [_] "tp")
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

(deftest test-no-memory-store-all-modes
  (testing "All modes return nil when no memory store is registered"
    ;; The gate spawn-context actually consults is the PORT — `store-set?` —
    ;; not any backend-specific configuration predicate.
    (stub/with-no-store
      (fn []
        (is (nil? (spawn/spawn-context "/tmp" {:mode :full})))
        (is (nil? (spawn/spawn-context "/tmp" {:mode :hints})))
        (is (nil? (spawn/spawn-context "/tmp" {:mode :ref})))
        (is (nil? (spawn/spawn-context "/tmp")))))))

(deftest test-ref-mode-partial-refs
  (testing ":ref mode works with partial refs (e.g., only axioms cached)"
    (let [proj (unique-project "partial-proj")]
      (ctx-store/context-put! [{:id "ax-only"}]
                              :tags #{"catchup" "axioms" proj}
                              :ttl-ms 60000)
      (ext-stub/with-extensions
        {:cr/i (fn [ctx-refs _kg-node-ids _scope]
                 (is (= #{:axioms} (set (keys ctx-refs)))
                     "Only the cached category is passed through")
                 (str "## Reconstructed Context\n\n### Axioms\n- " (:axioms ctx-refs) "\n"))}
        (fn []
          (with-base-mocks proj
            (let [result (spawn/spawn-context "/tmp" {:mode :ref})]
              (is (string? result))
              ;; compressed reconstruction produces "Reconstructed Context" header
              (is (str/includes? result "Reconstructed Context") "Uses compressed reconstruction")
              (is (str/includes? result "Axioms") "Contains Axioms category"))))))))
