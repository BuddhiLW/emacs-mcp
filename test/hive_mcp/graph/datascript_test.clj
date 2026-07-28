(ns hive-mcp.graph.datascript-test
  "Unit tests for GraphStore protocol and Datascript implementation.
   
   Tests cover:
   - Basic CRUD operations via protocol functions
   - find-similar deduplication logic with Jaccard similarity
   - persist! and restore! round-trip persistence
   - Friction entry workflow (create, dedupe, increment count)
   - Knowledge derivation from friction patterns
   
   Uses temporary files for persistence tests.
   
   Architecture:
   - hive-mcp.graph.protocol defines the GraphStore protocol
   - hive-mcp.graph.datascript implements it using Datascript
   - hive-mcp.graph.schema provides entity schemas"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [hive-mcp.graph.protocol :as proto]
            [hive-mcp.graph.datascript :as ds]
            [hive-mcp.graph.schema :as schema]))

;; =============================================================================
;; Test Fixtures and Helpers
;; =============================================================================

(def ^:dynamic *test-store* nil)
(def ^:dynamic *temp-file* nil)

(defn with-temp-store
  "Fixture that creates a fresh DatascriptStore for each test."
  [f]
  (let [temp-file (java.io.File/createTempFile "hive-graph-test" ".edn")]
    (.deleteOnExit temp-file)
    (binding [*test-store* (ds/create-default-store (.getAbsolutePath temp-file))
              *temp-file* temp-file]
      (try
        (f)
        (finally
          (when (.exists temp-file)
            (.delete temp-file)))))))

(defn with-inmem-store
  "Fixture for in-memory store (no persistence)."
  [f]
  (binding [*test-store* (ds/create-default-store nil)]
    (f)))

(use-fixtures :each with-temp-store)

(defn gen-test-id
  "Generate unique test ID."
  []
  (str "test-" (java.util.UUID/randomUUID)))

(defn create-test-agent
  "Create and transact a test agent, returns entity ID."
  [store agent-id agent-type]
  (let [tx-data [(schema/make-agent {:id agent-id :type agent-type})]
        result (proto/transact! store tx-data)]
    (get-in result [:tempids :db/current-tx])))

;; =============================================================================
;; Test: Basic CRUD Operations
;; =============================================================================

(deftest test-transact-basic
  (testing "transact! returns tx-data and tempids"
    (let [agent-data (schema/make-agent {:id "test-agent-1" :type :ling})
          result (proto/transact! *test-store* [agent-data])]
      (is (map? result))
      (is (contains? result :tx-data))
      (is (contains? result :tempids)))))

(deftest test-transact-with-tempid
  (testing "transact! with temp ID returns resolved ID"
    (let [tx-data [{:db/id -1
                    :agent/id "test-agent-2"
                    :agent/type :hivemind
                    :agent/created-at (java.util.Date.)
                    :agent/last-active (java.util.Date.)}]
          result (proto/transact! *test-store* tx-data)]
      (is (some? (get-in result [:tempids -1])))
      (is (pos? (get-in result [:tempids -1]))))))

(deftest test-query-basic
  (testing "query returns matching entities"
    ;; Insert test data. `:coordinator` is the third registered agent type;
    ;; `:hivemind` was retired from hive-mcp.agent.type-registry, and
    ;; make-agent asserts against that registry.
    (proto/transact! *test-store* [(schema/make-agent {:id "agent-a" :type :ling})])
    (proto/transact! *test-store* [(schema/make-agent {:id "agent-b" :type :ling})])
    (proto/transact! *test-store* [(schema/make-agent {:id "agent-c" :type :coordinator})])

    ;; Query all lings
    (let [results (proto/query *test-store*
                               '[:find ?id
                                 :where
                                 [?e :agent/type :ling]
                                 [?e :agent/id ?id]])]
      (is (= 2 (count results)))
      (is (contains? (set (map first results)) "agent-a"))
      (is (contains? (set (map first results)) "agent-b")))))

(deftest test-query-with-args
  (testing "query with arguments works correctly"
    (proto/transact! *test-store* [(schema/make-agent {:id "agent-x" :type :ling})])
    (proto/transact! *test-store* [(schema/make-agent {:id "agent-y" :type :drone})])

    (let [results (proto/query *test-store*
                               '[:find ?id
                                 :in $ ?type
                                 :where
                                 [?e :agent/type ?type]
                                 [?e :agent/id ?id]]
                               [:drone])]
      (is (= 1 (count results)))
      (is (= "agent-y" (ffirst results))))))

(deftest test-entity-retrieval
  (testing "entity returns full entity map"
    (let [agent-data (schema/make-agent {:id "entity-test-agent" :type :coordinator})
          result (proto/transact! *test-store* [agent-data])
          ;; Get entity by lookup ref
          eid (ffirst (proto/query *test-store*
                                   '[:find ?e
                                     :where [?e :agent/id "entity-test-agent"]]))]
      (let [entity (proto/entity *test-store* eid)]
        (is (map? entity))
        (is (= "entity-test-agent" (:agent/id entity)))
        (is (= :coordinator (:agent/type entity)))))))

(deftest test-entity-not-found
  (testing "entity returns nil for non-existent ID"
    (is (nil? (proto/entity *test-store* 999999)))))

;; =============================================================================
;; Test: find-similar Deduplication Logic
;; =============================================================================

(deftest test-find-similar-no-matches
  (testing "find-similar returns empty when no similar entries exist"
    (let [results (proto/find-similar *test-store* :friction "some unique content")]
      (is (empty? results)))))

(deftest test-find-similar-exact-match
  (testing "find-similar finds exact text matches"
    ;; Create agent first
    (proto/transact! *test-store* [(schema/make-agent {:id "reporter-1" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "reporter-1"]]))]
      ;; Create friction with specific content
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "Missing semantic search tool"
                                               :reported-by agent-eid})])

      ;; Find similar with exact same content
      (let [results (proto/find-similar *test-store* :friction "Missing semantic search tool")]
        (is (= 1 (count results)))
        (is (= 1.0 (:similarity (first results))))))))

(deftest test-find-similar-partial-match
  (testing "find-similar finds partial word overlaps"
    (proto/transact! *test-store* [(schema/make-agent {:id "reporter-2" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "reporter-2"]]))]
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :workflow-blocker
                                               :context "REPL connection fails intermittently"
                                               :reported-by agent-eid})])

      ;; Find with overlapping words
      (let [results (proto/find-similar *test-store* :friction "REPL connection timeout")]
        (is (= 1 (count results)))
        (is (> (:similarity (first results)) 0.1))
        (is (< (:similarity (first results)) 1.0))))))

(deftest test-find-similar-threshold
  (testing "find-similar filters out low-similarity matches"
    (proto/transact! *test-store* [(schema/make-agent {:id "reporter-3" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "reporter-3"]]))]
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :preset-gap
                                               :context "TDD preset missing assertions"
                                               :reported-by agent-eid})])

      ;; Search with completely different words
      (let [results (proto/find-similar *test-store* :friction "database migration scripts")]
        (is (empty? results))))))

(deftest test-find-similar-case-insensitive
  (testing "find-similar is case-insensitive"
    (proto/transact! *test-store* [(schema/make-agent {:id "reporter-4" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "reporter-4"]]))]
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "UPPERCASE CONTENT HERE"
                                               :reported-by agent-eid})])

      (let [results (proto/find-similar *test-store* :friction "uppercase content here")]
        (is (= 1 (count results)))
        (is (= 1.0 (:similarity (first results))))))))

(deftest test-find-similar-whitespace-normalization
  (testing "find-similar normalizes whitespace"
    (proto/transact! *test-store* [(schema/make-agent {:id "reporter-5" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "reporter-5"]]))]
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "  content   with   extra   spaces  "
                                               :reported-by agent-eid})])

      (let [results (proto/find-similar *test-store* :friction "content with extra spaces")]
        (is (= 1 (count results)))
        (is (= 1.0 (:similarity (first results))))))))

(deftest test-find-similar-multiple-matches
  (testing "find-similar returns multiple matches sorted by similarity"
    (proto/transact! *test-store* [(schema/make-agent {:id "reporter-6" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "reporter-6"]]))]
      ;; Create multiple frictions
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "file search broken"
                                               :reported-by agent-eid})])
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "file search slow"
                                               :reported-by agent-eid})])
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "search functionality missing"
                                               :reported-by agent-eid})])

      (let [results (proto/find-similar *test-store* :friction "file search")]
        (is (>= (count results) 2))
        ;; Should be sorted descending by similarity
        (is (apply >= (map :similarity results)))))))

;; =============================================================================
;; Test: persist! and restore! Round-Trip
;; =============================================================================

(deftest test-persist-creates-file
  (testing "persist! creates persistence file"
    (proto/transact! *test-store* [(schema/make-agent {:id "persist-test" :type :ling})])
    (let [result (proto/persist! *test-store*)]
      (is (map? result))
      (is (contains? result :persisted-at))
      (is (.exists *temp-file*)))))

(deftest test-restore-empty
  (testing "restore! returns nil when no persisted state exists"
    (let [empty-store (ds/create-default-store "/tmp/nonexistent-file-12345.edn")]
      (is (nil? (proto/restore! empty-store))))))

(deftest test-persist-restore-roundtrip
  (testing "persist! and restore! preserve data correctly"
    ;; Create and persist data
    (proto/transact! *test-store* [(schema/make-agent {:id "roundtrip-agent" :type :coordinator})])
    (proto/transact! *test-store* [(schema/make-agent {:id "roundtrip-ling" :type :ling})])
    (proto/persist! *test-store*)

    ;; Create new store and restore
    (let [new-store (ds/create-default-store (.getAbsolutePath *temp-file*))
          restore-result (proto/restore! new-store)]
      (is (map? restore-result))
      (is (contains? restore-result :restored-at))
      (is (contains? restore-result :entity-count))
      (is (>= (:entity-count restore-result) 2))

      ;; Verify data was restored
      (let [agents (proto/query new-store
                                '[:find ?id
                                  :where [?e :agent/id ?id]])]
        (is (= 2 (count agents)))
        (is (contains? (set (map first agents)) "roundtrip-agent"))
        (is (contains? (set (map first agents)) "roundtrip-ling"))))))

(deftest test-persist-nil-path
  (testing "persist! with nil path is a no-op"
    (let [inmem-store (ds/create-default-store nil)]
      (proto/transact! inmem-store [(schema/make-agent {:id "inmem-test" :type :drone})])
      (is (nil? (proto/persist! inmem-store))))))

;; =============================================================================
;; Test: Friction Entry Workflow
;; =============================================================================

(deftest test-friction-create
  (testing "Can create friction entry with all required fields"
    (proto/transact! *test-store* [(schema/make-agent {:id "friction-reporter" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "friction-reporter"]]))]
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "Need a code formatting tool"
                                               :reported-by agent-eid})])

      (let [frictions (proto/query *test-store*
                                   '[:find ?e
                                     :where [?e :friction/type _]])]
        (is (= 1 (count frictions)))))))

(deftest test-friction-find-similar-and-increment
  (testing "Friction workflow: find similar, increment count instead of duplicate"
    (proto/transact! *test-store* [(schema/make-agent {:id "friction-ling" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "friction-ling"]]))]
      ;; Create initial friction
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "Missing git diff tool"
                                               :reported-by agent-eid})])

      ;; Try to report same friction - should find similar
      (let [similar (proto/find-similar *test-store* :friction "Missing git diff tool")]
        (is (= 1 (count similar)))
        (is (= 1.0 (:similarity (first similar))))

        ;; Increment count on existing instead of creating duplicate
        (let [existing-eid (:eid (first similar))
              existing (proto/entity *test-store* existing-eid)]
          (is (= 1 (:friction/count existing)))

          ;; Increment count
          (proto/transact! *test-store*
                           [[:db/add existing-eid :friction/count 2]])

          ;; Verify count updated
          (let [updated (proto/entity *test-store* existing-eid)]
            (is (= 2 (:friction/count updated))))

          ;; Verify no duplicates created
          (let [all-frictions (proto/query *test-store*
                                           '[:find ?e :where [?e :friction/type _]])]
            (is (= 1 (count all-frictions)))))))))

(deftest test-friction-with-workaround
  (testing "Friction can have optional workaround"
    (proto/transact! *test-store* [(schema/make-agent {:id "workaround-reporter" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "workaround-reporter"]]))]
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :workflow-blocker
                                               :context "Build takes too long"
                                               :reported-by agent-eid
                                               :workaround "Use incremental compilation"})])

      (let [eid (ffirst (proto/query *test-store*
                                     '[:find ?e :where [?e :friction/context "Build takes too long"]]))
            friction (proto/entity *test-store* eid)]
        (is (= "Use incremental compilation" (:friction/workaround friction)))))))

;; =============================================================================
;; Test: Knowledge Derivation from Frictions
;; =============================================================================

(deftest test-knowledge-create
  (testing "Can create knowledge entry"
    (let [knowledge-data (schema/make-knowledge {:type :convention
                                                 :content "Always use structured logging"
                                                 :confidence 0.8
                                                 :tags [:logging :observability]})]
      (proto/transact! *test-store* [knowledge-data])

      (let [knowledge (proto/query *test-store*
                                   '[:find ?e ?content
                                     :where
                                     [?e :knowledge/type :convention]
                                     [?e :knowledge/content ?content]])]
        (is (= 1 (count knowledge)))
        (is (= "Always use structured logging" (second (first knowledge))))))))

(deftest test-knowledge-derived-from-frictions
  (testing "Knowledge can be derived from multiple frictions"
    ;; Create reporter agent
    (proto/transact! *test-store* [(schema/make-agent {:id "derive-reporter" :type :ling})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "derive-reporter"]]))]
      ;; Create multiple related frictions
      (proto/transact! *test-store*
                       [{:db/id -1
                         :friction/type :preset-gap
                         :friction/context "TDD preset missing assertion helpers"
                         :friction/reported-by agent-eid
                         :friction/count 3
                         :friction/created-at (java.util.Date.)
                         :friction/resolved? false}])
      (proto/transact! *test-store*
                       [{:db/id -2
                         :friction/type :preset-gap
                         :friction/context "TDD preset needs better test organization"
                         :friction/reported-by agent-eid
                         :friction/count 2
                         :friction/created-at (java.util.Date.)
                         :friction/resolved? false}])

      ;; Get friction IDs
      (let [friction-eids (map first (proto/query *test-store*
                                                  '[:find ?e
                                                    :where [?e :friction/type :preset-gap]]))]
        ;; Derive knowledge from these frictions
        (proto/transact! *test-store*
                         [{:db/id -3
                           :knowledge/type :pattern
                           :knowledge/content "TDD preset needs comprehensive assertion library and test organization support"
                           :knowledge/derived-from (set friction-eids)
                           :knowledge/confidence 0.75
                           :knowledge/created-at (java.util.Date.)
                           :knowledge/tags #{:tdd :testing :presets}}])

        ;; Verify knowledge links back to frictions
        (let [knowledge-eid (ffirst (proto/query *test-store*
                                                 '[:find ?e
                                                   :where [?e :knowledge/type :pattern]]))
              knowledge (proto/entity *test-store* knowledge-eid)]
          (is (= :pattern (:knowledge/type knowledge)))
          (is (= 0.75 (:knowledge/confidence knowledge)))
          (is (= 2 (count (:knowledge/derived-from knowledge)))))))))

(deftest test-knowledge-tags-search
  (testing "Knowledge can be queried by tags"
    (proto/transact! *test-store*
                     [(schema/make-knowledge {:type :convention
                                              :content "Use kebab-case for function names"
                                              :tags [:naming :style]})])
    (proto/transact! *test-store*
                     [(schema/make-knowledge {:type :convention
                                              :content "Prefer pure functions"
                                              :tags [:fp :style]})])

    ;; Query by tag
    (let [style-entries (proto/query *test-store*
                                     '[:find ?content
                                       :where
                                       [?e :knowledge/tags :style]
                                       [?e :knowledge/content ?content]])]
      (is (= 2 (count style-entries))))))

;; =============================================================================
;; Test: Store Statistics
;; =============================================================================

(deftest test-store-stats
  (testing "store-stats returns correct counts"
    (proto/transact! *test-store* [(schema/make-agent {:id "stats-agent" :type :coordinator})])
    (let [agent-eid (ffirst (proto/query *test-store*
                                         '[:find ?e :where [?e :agent/id "stats-agent"]]))]
      (proto/transact! *test-store*
                       [(schema/make-friction {:type :tool-missing
                                               :context "stats test friction"
                                               :reported-by agent-eid})])
      (proto/transact! *test-store*
                       [(schema/make-knowledge {:type :decision
                                                :content "stats test knowledge"})])

      (let [stats (ds/store-stats *test-store*)]
        (is (= 1 (:agent-count stats)))
        (is (= 1 (:friction-count stats)))
        (is (= 1 (:knowledge-count stats)))
        (is (some? (:persist-path stats)))))))

;; =============================================================================
;; Test: History (Datascript limitation)
;; =============================================================================

(deftest test-history-returns-nil
  (testing "history returns nil for Datascript (no native history support)"
    (proto/transact! *test-store* [(schema/make-agent {:id "history-test" :type :ling})])
    (let [eid (ffirst (proto/query *test-store*
                                   '[:find ?e :where [?e :agent/id "history-test"]]))]
      (is (nil? (proto/history *test-store* eid))))))

;; =============================================================================
;; Test: Schema Validation
;; =============================================================================

(deftest test-schema-friction-type-validation
  (testing "make-friction validates friction type"
    (is (thrown? AssertionError
                 (schema/make-friction {:type :invalid-type
                                        :context "test"
                                        :reported-by 1})))))

(deftest test-schema-knowledge-type-validation
  (testing "make-knowledge validates knowledge type"
    (is (thrown? AssertionError
                 (schema/make-knowledge {:type :invalid-type
                                         :content "test"})))))

(deftest test-schema-agent-type-validation
  (testing "make-agent validates agent type"
    (is (thrown? AssertionError
                 (schema/make-agent {:id "test"
                                     :type :invalid-type})))))
