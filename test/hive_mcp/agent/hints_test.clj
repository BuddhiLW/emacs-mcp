(ns hive-mcp.agent.hints-test
  "Tests for memory hints: generation, serialization, deserialization, execution."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [hive-mcp.agent.hints :as hints]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.catchup :as catchup]
            [clojure.string :as str]))

;; Production code (hints.clj execute-hint-ids/execute-hint-queries/
;; generate-task-hints) fetches via the IMemoryStore protocol —
;; mem-proto/get-entry, mem-proto/search-similar, mem-proto/query-entries —
;; NOT hive-mcp.chroma.core/* directly. So we mock the store layer the way the
;; rest of the codebase does (catchup query-axioms-regression-test, crystal
;; persist-test): redef mem-proto/store-set? + mem-proto/get-store to return a
;; reified store with caller-supplied behavior.
(defn- stub-store
  "Reify IMemoryStore. `fns` overrides selected methods, e.g.
   {:get-entry (fn [id] ...) :search-similar (fn [q opts] ...)
    :query-entries (fn [opts] ...)}."
  [{:keys [get-entry search-similar query-entries]
    :or {get-entry (constantly nil)
         search-similar (fn [_ _] [])
         query-entries (fn [_] [])}}]
  (reify mem-proto/IMemoryStore
    (connect! [_ _] nil)
    (disconnect! [_] nil)
    (connected? [_] true)
    (health-check [_] {:healthy? true})
    (add-entry! [_ _] nil)
    (get-entry [_ id] (get-entry id))
    (update-entry! [_ _ _] nil)
    (delete-entry! [_ _] true)
    (query-entries [_ opts] (query-entries opts))
    (search-similar [_ query opts] (search-similar query opts))
    (supports-semantic-search? [_] true)
    (cleanup-expired! [_] 0)
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status [_] {:backend "stub"})
    (reset-store! [_] nil)))

;; Production spawn-context gathers git via catchup.git/gather-git-info
;; (spawn.clj:96,142,152) — not emacs.client/eval-elisp-with-timeout. Mock the
;; same var the sibling catchup spawn-test does.
(def ^:private mock-git-info
  {:branch "main" :uncommitted false :last-commit "abc123"})

;; =============================================================================
;; Hint Generation Tests (Pure — no Chroma dependency)
;;
;; Note: with-redefs works on private vars by using their fully-qualified names.
;; hints.clj has its own private query-axioms and query-scoped-entries that
;; call Chroma directly — we mock those here.
;; =============================================================================

(deftest test-generate-hints-basic-structure
  (testing "generate-hints returns correct top-level structure"
    ;; Mock hints.clj's private query helpers (they call Chroma internally)
    (with-redefs [hive-mcp.agent.hints/query-axioms
                  (fn [_] [{:id "axiom-1"} {:id "axiom-2"}])
                  hive-mcp.agent.hints/query-scoped-entries
                  (fn [type tags project-id limit]
                    (case type
                      "convention" [{:id "conv-1"} {:id "conv-2"}]
                      "decision" [{:id "dec-1"}]
                      []))
                  hive-mcp.knowledge-graph.edges/edge-stats
                  (fn [] {:total-edges 10 :by-relation {:implements 5}})
                  hive-mcp.knowledge-graph.queries/traverse
                  (fn [_ _] [])]
      (let [result (hints/generate-hints "test-project")]
        (is (map? result))
        (is (contains? result :memory-hints))
        (is (vector? (get-in result [:memory-hints :axiom-ids])))
        (is (= ["axiom-1" "axiom-2"] (get-in result [:memory-hints :axiom-ids])))
        (is (vector? (get-in result [:memory-hints :read-ids])))
        ;; read-ids = priority conventions + decisions
        (is (= #{"conv-1" "conv-2" "dec-1"}
               (set (get-in result [:memory-hints :read-ids]))))))))

(deftest test-generate-hints-with-task
  (testing "generate-hints includes task-derived semantic queries"
    (with-redefs [hive-mcp.agent.hints/query-axioms (fn [_] [])
                  hive-mcp.agent.hints/query-scoped-entries (fn [_ _ _ _] [])
                  hive-mcp.knowledge-graph.edges/edge-stats (fn [] {:total-edges 0})]
      (let [result (hints/generate-hints "test-project" {:task "Refactor authentication module"})]
        (is (seq (get-in result [:memory-hints :queries])))
        (is (str/includes? (first (get-in result [:memory-hints :queries]))
                           "Refactor authentication"))))))

(deftest test-generate-hints-with-extras
  (testing "generate-hints includes extra IDs and queries"
    (with-redefs [hive-mcp.agent.hints/query-axioms (fn [_] [])
                  hive-mcp.agent.hints/query-scoped-entries (fn [_ _ _ _] [])
                  hive-mcp.knowledge-graph.edges/edge-stats (fn [] {:total-edges 0})]
      (let [result (hints/generate-hints "test-project"
                                         {:extra-ids ["extra-1" "extra-2"]
                                          :extra-queries ["find error handling"]
                                          :extra-tags [["TDD" "convention"]]})]
        (is (some #{"extra-1"} (get-in result [:memory-hints :read-ids])))
        (is (some #{"extra-2"} (get-in result [:memory-hints :read-ids])))
        (is (some #{"find error handling"} (get-in result [:memory-hints :queries])))
        (is (= [["TDD" "convention"]] (get-in result [:memory-hints :tags])))))))

(deftest test-generate-hints-empty-project
  (testing "generate-hints handles empty project gracefully"
    (with-redefs [hive-mcp.agent.hints/query-axioms (fn [_] [])
                  hive-mcp.agent.hints/query-scoped-entries (fn [_ _ _ _] [])
                  hive-mcp.knowledge-graph.edges/edge-stats (fn [] {:total-edges 0})]
      (let [result (hints/generate-hints "empty-project")]
        (is (map? result))
        (is (contains? result :memory-hints))
        ;; Empty project has only :axiom-ids (which is empty)
        (is (= [] (get-in result [:memory-hints :axiom-ids])))))))

(deftest test-generate-hints-kg-seeds
  (testing "generate-hints includes KG seeds when edges exist"
    (with-redefs [hive-mcp.agent.hints/query-axioms (fn [_] [])
                  hive-mcp.agent.hints/query-scoped-entries
                  (fn [type _ _ _]
                    (case type
                      "convention" []
                      "decision" [{:id "dec-1"} {:id "dec-2"} {:id "dec-3"}]
                      []))
                  hive-mcp.knowledge-graph.edges/edge-stats
                  (fn [] {:total-edges 10 :by-relation {:implements 5}})]
      (let [result (hints/generate-hints "test-project")]
        (is (seq (get-in result [:memory-hints :kg-seeds])))
        (is (<= (count (get-in result [:memory-hints :kg-seeds])) 3))
        (is (= 2 (get-in result [:memory-hints :kg-depth])))))))

;; =============================================================================
;; Hint Serialization Tests (Pure)
;; =============================================================================

(deftest test-serialize-hints-basic
  (testing "serialize-hints produces readable markdown"
    (let [hint-data {:memory-hints
                     {:axiom-ids ["ax-1" "ax-2"]
                      :read-ids ["conv-1" "dec-1"]
                      :queries ["find testing patterns"]
                      :kg-seeds ["seed-1"]
                      :kg-depth 2}}
          result (hints/serialize-hints hint-data
                                        :project-name "test-project"
                                        :git-info {:branch "main" :last-commit "abc - test"})]
      (is (string? result))
      (is (str/includes? result "## Memory Hints"))
      (is (str/includes? result "test-project"))
      (is (str/includes? result "ax-1"))
      (is (str/includes? result "ax-2"))
      (is (str/includes? result "conv-1"))
      (is (str/includes? result "dec-1"))
      (is (str/includes? result "find testing patterns"))
      (is (str/includes? result "seed-1"))
      (is (str/includes? result "main"))
      (is (str/includes? result "hints")))))

(deftest test-serialize-hints-empty
  (testing "serialize-hints handles empty hints"
    (let [hint-data {:memory-hints {:axiom-ids []}}
          result (hints/serialize-hints hint-data)]
      (is (string? result))
      (is (str/includes? result "## Memory Hints"))
      ;; Should not have L1/L2/L3 sections
      (is (not (str/includes? result "### L1: Axiom IDs")))
      (is (not (str/includes? result "### L2:"))))))

(deftest test-serialize-hints-token-budget
  (testing "serialized hints are within token budget (~2K chars)"
    (let [hint-data {:memory-hints
                     {:axiom-ids (mapv #(str "axiom-" %) (range 20))
                      :read-ids (mapv #(str "read-" %) (range 30))
                      :queries ["query 1" "query 2" "query 3"]
                      :tags [["tag1" "tag2"] ["tag3"]]
                      :kg-seeds ["seed-1" "seed-2"]
                      :kg-depth 2}}
          result (hints/serialize-hints hint-data
                                        :project-name "test-project"
                                        :git-info {:branch "main"})]
      ;; Hints should be much smaller than full text injection
      ;; Full text is ~12K chars, hints should be under 3K
      (is (< (count result) 3000)
          (str "Hints too large: " (count result) " chars")))))

(deftest test-serialize-hints-with-tags
  (testing "serialize-hints includes tag queries"
    (let [hint-data {:memory-hints
                     {:axiom-ids []
                      :tags [["TDD" "convention"] ["refactoring"]]}}
          result (hints/serialize-hints hint-data)]
      (is (str/includes? result "Tag Queries"))
      (is (str/includes? result "TDD,convention"))
      (is (str/includes? result "refactoring")))))

;; =============================================================================
;; Hint Deserialization (Roundtrip) Tests
;; =============================================================================

(deftest test-parse-hints-roundtrip
  (testing "parse-hints-from-context can parse serialized hints"
    (let [hint-data {:memory-hints
                     {:axiom-ids ["ax-1" "ax-2"]
                      :read-ids ["conv-1" "dec-1"]
                      :queries ["find testing patterns"]
                      :kg-seeds ["seed-1"]
                      :kg-depth 2}}
          serialized (hints/serialize-hints hint-data :project-name "test")
          parsed (hints/parse-hints-from-context serialized)]
      (is (some? parsed))
      ;; Axiom IDs recovered
      (is (= #{"ax-1" "ax-2"} (set (:axiom-ids parsed))))
      ;; Read IDs recovered (conv-1 and dec-1 from read-ids section)
      (is (some #{"conv-1"} (:read-ids parsed)))
      (is (some #{"dec-1"} (:read-ids parsed)))
      ;; Queries recovered
      (is (= ["find testing patterns"] (:queries parsed)))
      ;; KG seeds recovered
      (is (= ["seed-1"] (:kg-seeds parsed)))
      (is (= 2 (:kg-depth parsed))))))

(deftest test-parse-hints-no-hints-section
  (testing "parse-hints-from-context returns nil for non-hints context"
    (let [full-context "## Project Context (Auto-Injected)\n\n**Project**: test\n\n### Axioms\n\n1. Be good"]
      (is (nil? (hints/parse-hints-from-context full-context))))))

(deftest test-parse-hints-nil-input
  (testing "parse-hints-from-context handles nil gracefully"
    (is (nil? (hints/parse-hints-from-context nil)))
    (is (nil? (hints/parse-hints-from-context "")))))

(deftest test-parse-hints-partial
  (testing "parse-hints-from-context handles partial hints"
    (let [partial-hints "## Memory Hints (Auto-Injected)\n\n### L1: Axiom IDs (INVIOLABLE)\n```\nmemory get ax-1\n```\n"]
      (let [parsed (hints/parse-hints-from-context partial-hints)]
        (is (some? parsed))
        (is (= ["ax-1"] (:axiom-ids parsed)))
        (is (nil? (:queries parsed)))
        (is (nil? (:kg-seeds parsed)))))))

;; =============================================================================
;; Hint Execution Tests (with-redefs for Chroma)
;; =============================================================================

(deftest test-execute-hint-ids
  (testing "execute-hint-ids fetches entries by ID"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:get-entry
                                           (fn [id]
                                             {:id id
                                              :content (str "Content for " id)
                                              :type "convention"
                                              :tags ["test"]})}))]
      (let [results (hints/execute-hint-ids ["id-1" "id-2"])]
        (is (= 2 (count results)))
        (is (= "id-1" (:id (first results))))
        (is (= "Content for id-1" (:content (first results))))))))

(deftest test-execute-hint-ids-missing
  (testing "execute-hint-ids handles missing entries gracefully"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:get-entry
                                           (fn [id]
                                             (when (= id "exists")
                                               {:id id :content "found" :type "note" :tags []}))}))]
      (let [results (hints/execute-hint-ids ["exists" "missing"])]
        (is (= 1 (count results)))
        (is (= "exists" (:id (first results))))))))

(deftest test-execute-hint-ids-empty
  (testing "execute-hint-ids handles empty input"
    (is (nil? (hints/execute-hint-ids [])))
    (is (nil? (hints/execute-hint-ids nil)))))

(deftest test-execute-hint-queries
  (testing "execute-hint-queries runs semantic searches"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:search-similar
                                           (fn [_query _opts]
                                             [{:id "result-1" :content "Result content" :type "convention"}])}))]
      (let [results (hints/execute-hint-queries ["testing patterns"])]
        (is (= 1 (count results)))
        (is (= "testing patterns" (:query (first results))))
        (is (= 1 (count (:results (first results)))))))))

(deftest test-execute-hint-queries-empty
  (testing "execute-hint-queries handles empty input"
    (is (nil? (hints/execute-hint-queries [])))
    (is (nil? (hints/execute-hint-queries nil)))))

(deftest test-execute-hint-kg-traversal
  (testing "execute-hint-kg-traversal traverses graph"
    (with-redefs [hive-mcp.knowledge-graph.queries/traverse
                  (fn [seed opts]
                    [{:node-id "related-1" :relation :implements :confidence 0.8}
                     {:node-id "related-2" :relation :depends-on :confidence 0.6}])]
      (let [results (hints/execute-hint-kg-traversal ["seed-1"] 2)]
        (is (= 1 (count results)))
        (is (= "seed-1" (:seed (first results))))
        (is (= 2 (count (:related (first results)))))))))

(deftest test-execute-hint-kg-traversal-empty
  (testing "execute-hint-kg-traversal handles empty input"
    (is (nil? (hints/execute-hint-kg-traversal [] 2)))
    (is (nil? (hints/execute-hint-kg-traversal nil 2)))))

(deftest test-execute-all-hints
  (testing "execute-all-hints runs all levels and returns stats"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:get-entry
                                           (fn [id] {:id id :content "content" :type "axiom" :tags []})
                                           :search-similar
                                           (fn [_q _opts] [{:id "sr-1" :content "result" :type "note"}])}))
                  hive-mcp.knowledge-graph.queries/traverse
                  (fn [_ _] [{:node-id "n-1" :relation :implements :confidence 0.9}])]
      (let [hints {:axiom-ids ["ax-1"]
                   :read-ids ["r-1" "r-2"]
                   :queries ["search this"]
                   :kg-seeds ["kg-1"]
                   :kg-depth 2}
            result (hints/execute-all-hints hints)]
        (is (= 1 (:axioms-fetched (:stats result))))
        (is (= 2 (:entries-fetched (:stats result))))
        (is (= 1 (:queries-run (:stats result))))
        (is (= 1 (:kg-seeds-traversed (:stats result))))
        (is (= 1 (count (:axioms result))))
        (is (= 2 (count (:entries result))))
        (is (= 1 (count (:search-results result))))
        (is (= 1 (count (:kg-graph result))))))))

;; =============================================================================
;; Integration: spawn-context with hints mode
;;
;; Tests that catchup's spawn-context correctly delegates to hints.clj
;; when called with {:mode :hints}.
;;
;; Sprint 2: spawn-context now lives in catchup.spawn, re-exported from catchup.
;; with-redefs target the actual sub-namespace functions.
;; =============================================================================

(deftest test-spawn-context-hints-mode-shape
  (testing "spawn-context with :hints mode returns compact markdown"
    (with-redefs [mem-proto/store-set? (fn [] true)
                  mem-proto/get-store (fn ([] (stub-store {})) ([_] (stub-store {})))
                  hive-mcp.tools.memory.scope/get-current-project-id (fn [_] "test-project")
                  ;; Sprint 2: get-current-project-name moved to catchup.scope
                  hive-mcp.tools.catchup.scope/get-current-project-name (fn [_] "test-project")
                  ;; :hints mode resolves the PRIVATE hints/query-axioms (hints.clj:39),
                  ;; not catchup.scope/query-axioms — this redef target is correct.
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
      (let [result (hive-mcp.tools.catchup/spawn-context "/tmp/test" {:mode :hints})]
        (is (string? result))
        (is (str/includes? result "Memory Hints"))
        (is (str/includes? result "ax-1"))
        ;; Hints mode should be significantly smaller than full mode
        (is (< (count result) 3000))))))

(deftest test-spawn-context-full-mode-backward-compat
  (testing "spawn-context without opts retains full mode behavior"
    (with-redefs [mem-proto/store-set? (fn [] true)
                  mem-proto/get-store (fn ([] (stub-store {})) ([_] (stub-store {})))
                  hive-mcp.tools.memory.scope/get-current-project-id (fn [_] "test-project")
                  ;; Sprint 2: moved to catchup.scope
                  hive-mcp.tools.catchup.scope/get-current-project-name (fn [_] "test-project")
                  ;; Sprint 2: moved to catchup.scope
                  hive-mcp.tools.catchup.scope/query-axioms
                  (fn [_] [{:id "ax-1" :content "Test axiom" :tags ["axiom"]}])
                  hive-mcp.tools.catchup.scope/query-scoped-entries
                  (fn [_ _ _ _] [])
                  hive-mcp.knowledge-graph.disc/top-stale-files
                  (fn [& _] [])
                  hive-mcp.tools.catchup.git/gather-git-info
                  (fn [_] mock-git-info)]
      (let [result (hive-mcp.tools.catchup/spawn-context "/tmp/test")]
        (is (string? result))
        ;; Full mode has "Project Context" header, not "Memory Hints"
        (is (str/includes? result "Project Context"))
        (is (not (str/includes? result "Memory Hints")))))))

(deftest test-spawn-context-no-store
  (testing "spawn-context returns nil when no memory store is set"
    ;; spawn-context guards on (mem-proto/store-set?) — spawn.clj:87 — not on
    ;; chroma embedding config; no store ⇒ nil for every mode.
    (with-redefs [mem-proto/store-set? (fn [] false)]
      (is (nil? (hive-mcp.tools.catchup/spawn-context "/tmp/test" {:mode :hints})))
      (is (nil? (hive-mcp.tools.catchup/spawn-context "/tmp/test"))))))

;; =============================================================================
;; generate-task-hints Tests (KG-driven, coordinator-side)
;; =============================================================================

(deftest test-generate-task-hints-with-kg-edges
  (testing "generate-task-hints returns structured hints from KG traversal"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:get-entry
                                           (fn [id]
                                             {:id id :content "Test task" :type "note"
                                              :tags ["convention" "testing" "scope:project:test"]})}))
                  hive-mcp.knowledge-graph.queries/traverse
                  (fn [node-id opts]
                    [{:node-id "related-1" :depth 1
                      :edge {:kg-edge/relation :implements}
                      :path [node-id "related-1"]}
                     {:node-id "related-2" :depth 1
                      :edge {:kg-edge/relation :depends-on}
                      :path [node-id "related-2"]}
                     {:node-id "related-3" :depth 2
                      :edge {:kg-edge/relation :refines}
                      :path [node-id "related-2" "related-3"]}])]
      (let [result (hints/generate-task-hints {:task-id "task-123" :depth 2})]
        (is (map? result))
        ;; L1: discovered node IDs
        (is (vector? (:l1-ids result)))
        (is (= #{"related-1" "related-2" "related-3"} (set (:l1-ids result))))
        ;; L2: semantic queries derived from tags + relations
        (is (vector? (:l2-queries result)))
        (is (seq (:l2-queries result)))
        ;; L3: depth-1 nodes as seeds
        (is (vector? (:l3-seeds result)))
        (is (every? #(and (:id %) (:depth %)) (:l3-seeds result)))
        (is (= #{"related-1" "related-2"} (set (map :id (:l3-seeds result)))))))))

(deftest test-generate-task-hints-fallback-no-edges
  (testing "generate-task-hints falls back to tag search when KG has no edges"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:get-entry
                                           (fn [id]
                                             {:id id :content "Task with tags" :type "decision"
                                              :tags ["architecture" "refactoring" "scope:project:test" "agent:coordinator"]})}))
                  hive-mcp.knowledge-graph.queries/traverse
                  (fn [_ _] [])]  ;; No KG edges
      (let [result (hints/generate-task-hints {:task-id "task-no-edges"})]
        (is (map? result))
        ;; L1: empty (no KG nodes)
        (is (= [] (:l1-ids result)))
        ;; L2: tag-derived query (filtering out scope: and agent: tags)
        (is (vector? (:l2-queries result)))
        (is (seq (:l2-queries result)))
        (is (str/includes? (first (:l2-queries result)) "architecture"))
        (is (str/includes? (first (:l2-queries result)) "refactoring"))
        ;; L3: empty (no KG)
        (is (= [] (:l3-seeds result)))))))

(deftest test-generate-task-hints-nil-node
  (testing "generate-task-hints returns nil for nil input"
    (is (nil? (hints/generate-task-hints {})))
    (is (nil? (hints/generate-task-hints {:task-id nil})))))

(deftest test-generate-task-hints-memory-id-alias
  (testing "generate-task-hints accepts :memory-id as alternative to :task-id"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:get-entry (fn [_] {:id "mem-1" :tags [] :type "note"})}))
                  hive-mcp.knowledge-graph.queries/traverse (fn [_ _] [])]
      (let [result (hints/generate-task-hints {:memory-id "mem-1"})]
        (is (map? result))
        (is (contains? result :l1-ids))))))

(deftest test-generate-task-hints-bounded-output
  (testing "generate-task-hints respects max limits"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:get-entry
                                           (fn [_] {:id "big-task" :type "note"
                                                    :tags (mapv #(str "tag-" %) (range 20))})}))
                  hive-mcp.knowledge-graph.queries/traverse
                  (fn [node-id _]
                    ;; Return 30 nodes (exceeds max-l1-ids of 20)
                    (mapv (fn [i]
                            {:node-id (str "node-" i) :depth (if (< i 5) 1 2)
                             :edge {:kg-edge/relation :implements}
                             :path [node-id (str "node-" i)]})
                          (range 30)))]
      (let [result (hints/generate-task-hints {:task-id "big-task" :depth 2})]
        ;; L1: bounded to max-l1-ids (20)
        (is (<= (count (:l1-ids result)) 20))
        ;; L2: bounded to max-l2-queries (5)
        (is (<= (count (:l2-queries result)) 5))
        ;; L3: bounded to max-l3-seeds (3)
        (is (<= (count (:l3-seeds result)) 3))))))

(deftest test-generate-task-hints-kg-traverse-error
  (testing "generate-task-hints handles KG traverse errors gracefully"
    (with-redefs [mem-proto/store-set? (constantly true)
                  mem-proto/get-store
                  (constantly (stub-store {:get-entry
                                           (fn [_] {:id "err-task" :tags ["testing"] :type "note"})}))
                  hive-mcp.knowledge-graph.queries/traverse
                  (fn [_ _] (throw (Exception. "KG connection failed")))]
      (let [result (hints/generate-task-hints {:task-id "err-task"})]
        ;; Should fallback to tag-based search (traverse error caught internally)
        (is (map? result))
        (is (= [] (:l1-ids result)))
        (is (vector? (:l2-queries result)))))))

(deftest test-generate-task-hints-no-store
  (testing "generate-task-hints works even when no memory store is set"
    (with-redefs [mem-proto/store-set? (constantly false)
                  hive-mcp.knowledge-graph.queries/traverse (fn [_ _] [])]
      (let [result (hints/generate-task-hints {:task-id "no-store"})]
        ;; No store = no entry lookup, fallback to empty tags
        (is (map? result))
        (is (= [] (:l1-ids result)))
        (is (= [] (:l3-seeds result)))))))

(deftest test-extract-semantic-themes-pure
  (testing "extract-semantic-themes generates queries from traversal + entry"
    ;; Access the private fn via var
    (let [extract-fn #'hive-mcp.agent.hints/extract-semantic-themes
          traversal [{:node-id "n1" :edge {:kg-edge/relation :implements}}
                     {:node-id "n2" :edge {:kg-edge/relation :depends-on}}]
          entry {:tags ["convention" "testing" "scope:project:test" "agent:foo"]
                 :type "decision"}
          result (extract-fn traversal entry)]
      (is (vector? result))
      ;; Should have tag-based query (convention, testing — not scope: or agent:)
      (is (some #(str/includes? % "convention") result))
      ;; Should have relation-based query
      (is (some #(str/includes? % "implements") result))
      ;; Should have type-based query
      (is (some #(str/includes? % "decision") result)))))

(deftest test-fallback-tag-search-pure
  (testing "fallback-tag-search generates L2 queries from tags"
    (let [fallback-fn #'hive-mcp.agent.hints/fallback-tag-search
          entry {:tags ["architecture" "refactoring" "scope:project:test" "agent:ling-1"]}
          result (fallback-fn entry)]
      (is (= [] (:l1-ids result)))
      (is (= [] (:l3-seeds result)))
      (is (seq (:l2-queries result)))
      (is (str/includes? (first (:l2-queries result)) "architecture"))
      (is (str/includes? (first (:l2-queries result)) "refactoring"))
      ;; Should NOT include scope: or agent: tags
      (is (not (str/includes? (first (:l2-queries result)) "scope:")))))

  (testing "fallback-tag-search with empty tags"
    (let [fallback-fn #'hive-mcp.agent.hints/fallback-tag-search
          result (fallback-fn {:tags []})]
      (is (= [] (:l1-ids result)))
      (is (= [] (:l2-queries result)))
      (is (= [] (:l3-seeds result)))))

  (testing "fallback-tag-search with only boilerplate tags"
    (let [fallback-fn #'hive-mcp.agent.hints/fallback-tag-search
          result (fallback-fn {:tags ["scope:global" "agent:coordinator"]})]
      (is (= [] (:l2-queries result))))))

;; =============================================================================
;; Constants Tests
;; =============================================================================

(deftest test-hint-bounds-constants
  (testing "hint bounds constants are reasonable"
    (is (= 20 hints/max-l1-ids))
    (is (= 5 hints/max-l2-queries))
    (is (= 3 hints/max-l3-seeds))
    ;; Bounds are positive
    (is (pos? hints/max-l1-ids))
    (is (pos? hints/max-l2-queries))
    (is (pos? hints/max-l3-seeds))))
