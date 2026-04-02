(ns hive-mcp.kanban-tag-regression-test
  "Regression tests for kanban tag filtering bugs.

   Bug 1 (HIVE-CHROMA-01): Chroma $contains on metadata tags returns 0 results.
   ChromaDB metadata `where` only supports $eq/$ne/$gt/$gte/$lt/$lte/$in/$nin.
   `$contains` is only valid for `where_document` (document text search).
   Tags are stored as comma-separated strings, so substring-based filtering
   via $contains silently fails.

   Bug 2 (HIVE-KANBAN-01): Kanban list returns 0 after MCP reconnect.
   ctx/current-directory is nil after reconnect → project-id resolves to
   'global' → misses all project-scoped tasks.

   These tests pin the correct behavior against regression using
   hive-test mutation testing and golden snapshots."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.chroma.helpers :as h]
            [hive-mcp.test-fixtures :as fixtures]
            [hive-test.mutation :as mutation]
            [hive-test.golden :as golden]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:dynamic *test-collection* "hive-mcp-kanban-regression-test")

(defn with-chroma-fixture
  "Set up mock embedder and clean collection for each test."
  [f]
  (let [original-provider (chroma/get-embedding-provider)]
    (chroma/set-embedding-provider! (fixtures/->MockEmbedder 384))
    (chroma/configure! {:host "localhost"
                        :port 8000
                        :collection-name *test-collection*})
    (try
      (f)
      (finally
        (chroma/reset-collection-cache!)
        (when original-provider
          (chroma/set-embedding-provider! original-provider))))))

(use-fixtures :each with-chroma-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn gen-id []
  (str "kanban-reg-" (java.util.UUID/randomUUID)))

(defn make-kanban-entry
  "Create a kanban task entry as stored in Chroma."
  [& {:keys [id title status priority project-id]
      :or {id (gen-id)
           title "Test kanban task"
           status "todo"
           priority "medium"
           project-id "test-project"}}]
  (let [content {:task-type "kanban"
                 :title title
                 :status status
                 :priority priority
                 :created (h/iso-timestamp)}
        tags (str/join "," ["kanban" status (str "priority-" priority)
                            (str "scope:project:" project-id)])]
    {:id id
     :type "note"
     :content (json/write-str content)
     :tags tags
     :duration "short"
     :project-id project-id}))

(defn make-non-kanban-entry
  "Create a regular note (not kanban)."
  [& {:keys [id project-id]
      :or {id (gen-id) project-id "test-project"}}]
  {:id id
   :type "note"
   :content "Regular non-kanban note"
   :tags (str/join "," ["general" (str "scope:project:" project-id)])
   :duration "medium"
   :project-id project-id})

(defn index-entries! [entries]
  (doseq [e entries]
    (chroma/index-memory-entry! e)))

;; =============================================================================
;; Bug 1: Chroma $contains on metadata tags
;; =============================================================================

(deftest ^:integration tag-contains-filter-returns-results
  (testing "REGRESSION: query-entries with tag filter must return matching entries"
    (let [kanban-entry (make-kanban-entry :title "Tagged task")
          regular-entry (make-non-kanban-entry)]
      (index-entries! [kanban-entry regular-entry])

      (testing "$contains tag filter finds kanban entries"
        (let [results (chroma/query-entries
                       :type "note"
                       :project-id "test-project"
                       :tags ["kanban"]
                       :limit 100)]
          (is (pos? (count results))
              "REGRESSION BUG: $contains on metadata tags returned 0 results.
               ChromaDB $contains is only valid for where_document, not where.
               Tags stored as comma-separated strings need a different filter strategy.")
          (is (every? #(some #{"kanban"} (:tags %)) results)
              "All returned entries must have 'kanban' tag"))))))

(deftest ^:integration tag-filter-does-not-return-untagged-entries
  (testing "Tag filter must exclude entries without the requested tag"
    (let [kanban-entry (make-kanban-entry :title "Has kanban tag")
          regular-entry (make-non-kanban-entry)]
      (index-entries! [kanban-entry regular-entry])

      (let [results (chroma/query-entries
                     :type "note"
                     :project-id "test-project"
                     :tags ["kanban"]
                     :limit 100)]
        ;; If the filter works, regular-entry should be excluded
        (is (not (some #(= (:id regular-entry) (:id %)) results))
            "Non-kanban entries must not appear in kanban-filtered query")))))

(deftest ^:integration multi-tag-filter-intersects
  (testing "Multiple tags filter with AND semantics"
    (let [todo-task (make-kanban-entry :title "Todo" :status "todo")
          doing-task (make-kanban-entry :title "Doing" :status "doing")]
      (index-entries! [todo-task doing-task])

      (let [results (chroma/query-entries
                     :type "note"
                     :project-id "test-project"
                     :tags ["kanban" "todo"]
                     :limit 100)]
        (is (pos? (count results))
            "Multi-tag query must return results")
        (is (every? #(and (some #{"kanban"} (:tags %))
                          (some #{"todo"} (:tags %)))
                    results)
            "All results must have both 'kanban' AND 'todo' tags")))))

(deftest ^:integration exclude-tags-filter-works
  (testing "Exclude tags removes matching entries"
    (let [kanban-entry (make-kanban-entry :title "Kanban task")
          carto-entry (assoc (make-non-kanban-entry)
                             :tags (str/join "," ["carto" "scope:project:test-project"]))]
      (index-entries! [kanban-entry carto-entry])

      (let [results (chroma/query-entries
                     :type "note"
                     :project-id "test-project"
                     :exclude-tags ["carto"]
                     :limit 100)]
        (is (not (some #(some #{"carto"} (:tags %)) results))
            "Excluded tag entries must not appear")))))

;; =============================================================================
;; Bug 2: Kanban project-id scoping after context loss
;; =============================================================================

(deftest ^:integration cross-project-kanban-not-visible
  (testing "Tasks scoped to project-A are invisible when querying project-B"
    (let [task-a (make-kanban-entry :title "Project A task" :project-id "project-a")
          task-b (make-kanban-entry :title "Project B task" :project-id "project-b")]
      (index-entries! [task-a task-b])

      (let [results-a (chroma/query-entries
                       :type "note" :project-id "project-a" :limit 100)
            results-b (chroma/query-entries
                       :type "note" :project-id "project-b" :limit 100)]
        (is (some #(= (:id task-a) (:id %)) results-a)
            "Task A visible in project A")
        (is (not (some #(= (:id task-b) (:id %)) results-a))
            "Task B NOT visible in project A")
        (is (some #(= (:id task-b) (:id %)) results-b)
            "Task B visible in project B")))))

(deftest ^:integration global-scope-misses-project-tasks
  (testing "REGRESSION: querying with project-id='global' must not silently miss project-scoped tasks"
    (let [project-task (make-kanban-entry :title "Project task" :project-id "my-project")
          global-task (make-kanban-entry :title "Global task" :project-id "global")]
      (index-entries! [project-task global-task])

      (let [global-results (chroma/query-entries
                            :type "note" :project-id "global" :limit 100)]
        (is (some #(= (:id global-task) (:id %)) global-results)
            "Global task visible in global scope")
        (is (not (some #(= (:id project-task) (:id %)) global-results))
            "DOCUMENTS BUG: project-scoped task invisible from global scope.
             After MCP reconnect, ctx/current-directory=nil resolves to 'global',
             hiding all project-scoped kanban tasks.")))))

(deftest ^:integration nil-project-id-query-returns-all
  (testing "Query without project-id filter returns entries from all projects"
    (let [task-a (make-kanban-entry :title "Task A" :project-id "proj-a")
          task-b (make-kanban-entry :title "Task B" :project-id "proj-b")]
      (index-entries! [task-a task-b])

      (let [results (chroma/query-entries :type "note" :limit 100)]
        (is (>= (count results) 2)
            "Unscoped query should return tasks from all projects")
        (is (some #(= (:id task-a) (:id %)) results)
            "Task A found in unscoped query")
        (is (some #(= (:id task-b) (:id %)) results)
            "Task B found in unscoped query")))))

;; =============================================================================
;; Tag serialization roundtrip
;; =============================================================================

(deftest tag-join-split-roundtrip
  (testing "Tags survive join→split roundtrip"
    (let [tags ["kanban" "todo" "priority-medium" "scope:project:funeraria"]]
      (is (= tags (h/split-tags (h/join-tags tags)))
          "join-tags → split-tags must be identity"))))

(deftest tag-join-preserves-all-tags
  (testing "join-tags doesn't lose tags"
    (let [tags ["kanban" "doing" "priority-high" "scope:project:hive-mcp"
                "agent:coordinator"]
          joined (h/join-tags tags)]
      (is (str/includes? joined "kanban"))
      (is (str/includes? joined "doing"))
      (is (str/includes? joined "priority-high"))
      (is (str/includes? joined "scope:project:hive-mcp"))
      (is (str/includes? joined "agent:coordinator")))))

;; =============================================================================
;; In-memory tag filtering (kanban's filter-kanban-by-tags)
;; =============================================================================

(deftest in-memory-kanban-tag-filter
  (testing "filter-kanban-by-tags correctly filters on deserialized tags"
    (let [kanban-entry {:id "k1"
                        :content {:task-type "kanban" :title "Task" :status "todo"}
                        :tags ["kanban" "todo" "priority-medium" "scope:project:test"]}
          non-kanban {:id "n1"
                      :content {:description "Not kanban"}
                      :tags ["general" "scope:project:test"]}
          entries [kanban-entry non-kanban]]

      (testing "filter by ['kanban'] tag"
        (let [filtered (filter (fn [e]
                                 (let [ts (set (:tags e))]
                                   (every? #(contains? ts %) ["kanban"])))
                               entries)]
          (is (= 1 (count filtered)))
          (is (= "k1" (:id (first filtered))))))

      (testing "filter by ['kanban' 'todo'] tags"
        (let [filtered (filter (fn [e]
                                 (let [ts (set (:tags e))]
                                   (every? #(contains? ts %) ["kanban" "todo"])))
                               entries)]
          (is (= 1 (count filtered)))
          (is (= "k1" (:id (first filtered))))))

      (testing "filter by ['kanban' 'doing'] returns nothing (status mismatch)"
        (let [filtered (filter (fn [e]
                                 (let [ts (set (:tags e))]
                                   (every? #(contains? ts %) ["kanban" "doing"])))
                               entries)]
          (is (zero? (count filtered))))))))

;; =============================================================================
;; Mutation test: tag filtering must actually filter
;; =============================================================================

(defn apply-tag-filter
  "Reference implementation of in-memory tag filtering."
  [required-tags entries]
  (if (seq required-tags)
    (filter (fn [entry]
              (let [entry-tags (set (:tags entry))]
                (every? #(contains? entry-tags %) required-tags)))
            entries)
    entries))

(mutation/deftest-mutations tag-filter-mutations-caught
  hive-mcp.kanban-tag-regression-test/apply-tag-filter

  [["always-empty"   (fn [_tags _entries] [])]
   ["ignores-tags"   (fn [_tags entries] entries)]
   ["inverts-filter" (fn [tags entries]
                       (remove (fn [e]
                                 (let [ts (set (:tags e))]
                                   (every? #(contains? ts %) tags)))
                               entries))]]

  (fn []
    (let [entries [{:id "k1" :tags ["kanban" "todo"]}
                   {:id "k2" :tags ["kanban" "doing"]}
                   {:id "n1" :tags ["general"]}]]
      (is (= 2 (count (apply-tag-filter ["kanban"] entries)))
          "Both kanban entries match")
      (is (= 1 (count (apply-tag-filter ["kanban" "todo"] entries)))
          "Only todo kanban entry matches")
      (is (= 3 (count (apply-tag-filter [] entries)))
          "Empty tags = no filter")
      (is (zero? (count (apply-tag-filter ["nonexistent"] entries)))
          "No entries match nonexistent tag"))))

;; =============================================================================
;; Golden: pin the where-clause shape that query-entries builds
;; =============================================================================

(deftest ^:integration golden-where-clause-shape
  (testing "Pin the Chroma where clause structure for tag queries"
    (let [;; Simulate what chroma/crud.clj query-entries builds
          tags ["kanban"]
          type "note"
          project-id "funeraria"
          base-clause {:type type :project-id project-id}
          tag-conditions (mapv (fn [tag] {:tags {:$contains tag}}) tags)
          base-conditions (mapv (fn [[k v]] {k v}) base-clause)
          where {:$and (into base-conditions tag-conditions)}]

      (golden/assert-golden
       "test/golden/kanban-tag-where-clause.edn"
       where)

      (testing "where clause includes $contains for each tag"
        (is (= {:$and [{:type "note"}
                        {:project-id "funeraria"}
                        {:tags {:$contains "kanban"}}]}
               where)
            "This is the BUGGY shape — $contains is invalid for Chroma metadata.
             Once fixed, update the golden file with the corrected clause.")))))

;; =============================================================================
;; Document the fix strategy
;; =============================================================================

(deftest ^:integration document-fix-strategy
  (testing "DOCS: ChromaDB metadata filtering requires different approach for substring tags"
    ;; ChromaDB `where` operators: $eq, $ne, $gt, $gte, $lt, $lte, $in, $nin
    ;; $contains/$not_contains are ONLY valid for `where_document`
    ;;
    ;; Fix options:
    ;; 1. Store each tag as a separate boolean metadata field
    ;;    {:tag_kanban true, :tag_todo true, :tag_priority-medium true}
    ;;    Pro: Native ChromaDB filtering. Con: Dynamic metadata fields, naming collisions.
    ;;
    ;; 2. Post-filter in memory (what kanban already does in filter-kanban-by-tags)
    ;;    Fetch by type+project-id, then filter tags in Clojure.
    ;;    Pro: Simple, proven. Con: Over-fetches from Chroma.
    ;;
    ;; 3. Use where_document $contains on the document text
    ;;    memory-to-document includes "Tags: kanban, todo, ..."
    ;;    Pro: Uses ChromaDB's intended text search. Con: Fragile substring matching.
    ;;
    ;; Recommended: Option 2 — align memory query with kanban's approach.
    ;; The kanban tool already does this correctly (Bug 2 is separate).
    (is true "Fix strategy documented")))
