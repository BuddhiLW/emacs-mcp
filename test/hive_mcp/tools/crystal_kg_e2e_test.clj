(ns hive-mcp.tools.crystal-kg-e2e-test
  "End-to-end test for KG edge creation via crystallize workflow.

   Tests the full flow:
   1. Create source entries in Chroma (simulating session notes/tasks)
   2. Call crystallize with harvested data containing source IDs
   3. Verify :derived-from edges exist in KG

   Unlike crystal-kg-test which uses mocked functions, this test
   exercises the real Chroma and KG components end-to-end."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.knowledge-graph.connection :as kg-conn]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.crystal.hooks :as crystal-hooks]
            [hive-mcp.tools.crystal]
            [hive-mcp.test-fixtures :as fixtures]
            [hive-mcp.emacs.client :as ec]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.chroma.connection :as chroma-conn]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-collection* "hive-mcp-e2e-test")

(defn with-test-environment
  "Setup mock embedder for each test. KG isolation handled by :kg-conn fixture."
  [f]
  ;; Save original Chroma state
  (let [original-provider (chroma/get-embedding-provider)
        original-config   (chroma-conn/get-config)]
    ;; Configure Chroma with mock embedder
    (chroma/set-embedding-provider! (fixtures/->MockEmbedder 384))
    (chroma-conn/configure! {:host "localhost"
                        :port 8000
                        :collection-name *test-collection*})
    (try
      (f)
      (finally
        ;; Cleanup — restore original collection name + provider
        (chroma-conn/reset-collection-cache!)
        (chroma-conn/configure! (select-keys original-config [:host :port :collection-name]))
        (chroma/set-embedding-provider! original-provider)))))

(use-fixtures :each
  (iso/with-isolations :kg-conn)
  with-test-environment)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn gen-test-id
  "Generate unique test ID."
  []
  (str "e2e-test-" (java.util.UUID/randomUUID)))

(defn create-source-entry!
  "Create a source memory entry in Chroma.
   Returns the entry ID."
  [content & {:keys [type tags]
              :or {type "note" tags []}}]
  (let [id (gen-test-id)]
    (chroma/index-memory-entry!
     {:id id
      :type type
      :content content
      :tags tags
      :duration "ephemeral"
      :created (str (java.time.Instant/now))})
    id))

(defn mock-harvest-result
  "Create a mock harvest result with given progress notes, completed tasks,
   and memory-ids-created.

   source-ids: seq of {:id :content :type} maps"
  ([progress-notes completed-tasks]
   (mock-harvest-result progress-notes completed-tasks []))
  ([progress-notes completed-tasks memory-ids-created]
   {:progress-notes progress-notes
    :completed-tasks completed-tasks
    :memory-ids-created memory-ids-created
    :git-commits []
    :recalls {}
    :session "2026-01-26"
    :directory "/tmp/test-project"
    :summary {:progress-count (count progress-notes)
              :task-count (count completed-tasks)
              :commit-count 0
              :recall-count 0}}))

(defn create-derived-from-edges!
  "Create :derived-from KG edges linking a summary to its source entries.
   Local implementation — original was removed from crystal.clj during IP
   migration (replaced by delegate-or-noop stub :ck/a)."
  [summary-id source-ids project-id agent-id]
  (when (and summary-id (seq source-ids))
    (try
      (let [edge-ids (reduce
                      (fn [acc source-id]
                        (try
                          (let [edge-id (kg-edges/add-edge!
                                         {:from summary-id
                                          :to source-id
                                          :relation :derived-from
                                          :scope project-id
                                          :created-by agent-id})]
                            (conj acc edge-id))
                          (catch Exception _e
                            acc)))
                      []
                      source-ids)]
        {:created-count (count edge-ids)
         :edge-ids edge-ids})
      (catch Exception e
        {:error (.getMessage e)
         :created-count 0}))))

;; =============================================================================
;; E2E Test: Crystallize Creates Derived-From Edges
;; =============================================================================

(deftest crystallize-creates-derived-from-edges-e2e
  (testing "crystallize-session links summary to source entries via KG edges"
    ;; Step 1: Create source entries in Chroma
    (let [source-id-1 (create-source-entry! "Session progress note: implemented feature X"
                                            :type "note"
                                            :tags ["session-progress"])
          source-id-2 (create-source-entry! "Completed task: write tests"
                                            :type "note"
                                            :tags ["completed-task"])
          source-id-3 (create-source-entry! "Another progress note"
                                            :type "note"
                                            :tags ["session-progress"])]

      ;; Verify sources exist in Chroma
      (is (some? (chroma/search-by-id source-id-1)) "Source 1 should exist in Chroma")
      (is (some? (chroma/search-by-id source-id-2)) "Source 2 should exist in Chroma")
      (is (some? (chroma/search-by-id source-id-3)) "Source 3 should exist in Chroma")

      ;; Step 2: Create harvested data with source IDs
      (let [harvested (mock-harvest-result
                       ;; progress-notes with IDs
                       [{:id source-id-1 :content "Session progress note: implemented feature X"}
                        {:id source-id-3 :content "Another progress note"}]
                       ;; completed-tasks with IDs
                       [{:id source-id-2 :title "Completed task: write tests"}])]

        ;; Step 3: Call crystallize-session (bypasses Emacs, stores to Chroma directly)
        (let [result (crystal-hooks/crystallize-session harvested)]
          (is (some? (:summary-id result)) "crystallize-session should return summary-id")
          (is (not (:error result)) "crystallize-session should not error")

          (let [summary-id (:summary-id result)]
            ;; Verify summary was created in Chroma
            (is (some? (chroma/search-by-id summary-id)) "Summary should exist in Chroma")

            ;; Step 4: Verify KG edges were NOT created yet
            ;; (crystallize-session creates summary but doesn't create edges -
            ;;  that's done by wrap_crystallize which calls create-derived-from-edges!)

            ;; We need to call the edge creation function directly since
            ;; crystallize-session doesn't create edges (that's in handle-wrap-crystallize)
            (let [source-ids [source-id-1 source-id-2 source-id-3]
                  project-id "test-project"
                  agent-id "e2e-test-agent"
                  ;; Call the edge creation function (this is what wrap_crystallize does)
                  edge-result (create-derived-from-edges!
                               summary-id source-ids project-id agent-id)]

              ;; Step 5: Verify edges were created
              (is (= 3 (:created-count edge-result)) "Should create 3 edges")
              (is (= 3 (count (:edge-ids edge-result))) "Should have 3 edge IDs")

              ;; Verify via kg_stats
              (let [stats (kg-edges/edge-stats)]
                (is (= 3 (:total-edges stats)) "KG should have 3 total edges")
                (is (= {:derived-from 3} (:by-relation stats)) "All edges should be :derived-from")
                (is (= {"test-project" 3} (:by-scope stats)) "All edges should be in test-project scope"))

              ;; Verify edges point correctly
              (let [outgoing (kg-edges/get-edges-from summary-id)]
                (is (= 3 (count outgoing)) "Summary should have 3 outgoing edges")
                (is (every? #(= summary-id (:kg-edge/from %)) outgoing)
                    "All edges should come from summary")
                (is (every? #(= :derived-from (:kg-edge/relation %)) outgoing)
                    "All edges should be :derived-from")
                (is (= (set source-ids) (set (map :kg-edge/to outgoing)))
                    "Edges should point to all source IDs")))))))))

;; =============================================================================
;; E2E Test: Full wrap_crystallize Handler Flow
;; =============================================================================

(deftest wrap-crystallize-handler-creates-edges-e2e
  (testing "handle-wrap-crystallize creates KG edges via FOSS default (build-crystal-edges-default)"
    ;; Without the :ck/a extension, edge creation falls back to build-crystal-edges-default
    ;; which creates :derived-from edges from summary to all source entries.
    (let [source-id-1 (create-source-entry! "Work note 1" :tags ["session-progress"])
          source-id-2 (create-source-entry! "Work note 2" :tags ["session-progress"])]

      ;; Mock the harvest function to return our source IDs
      (with-redefs [crystal-hooks/harvest-all
                    (fn [_]
                      (mock-harvest-result
                       [{:id source-id-1 :content "Work note 1"}
                        {:id source-id-2 :content "Work note 2"}]
                       []))
                    ;; Mock Emacs check for hive-mcp.el
                    ec/eval-elisp (fn [_] {:success true :result "nil" :duration-ms 5})]

        ;; Call the actual handler
        (let [result-json (-> (hive-mcp.tools.crystal/handle-wrap-crystallize
                               {:agent_id "test-agent"
                                :directory "/tmp/test-project"})
                              :text
                              (json/read-str :key-fn keyword))]

          (is (not (:error result-json)) "Handler should not return error")
          (is (some? (:summary-id result-json)) "Handler should return summary-id")

          ;; FOSS default creates :derived-from edges from summary to source entries
          (let [kg-edges-result (:kg-edges result-json)]
            (is (some? kg-edges-result) "Handler should return :kg-edges")
            (is (= 2 (:total-edges kg-edges-result))
                "Should create 2 edges (one per source entry)")
            (is (some? (:derived-from kg-edges-result))
                "Should have :derived-from edge stats")
            (is (= 2 (get-in kg-edges-result [:derived-from :created-count]))
                "Should report 2 derived-from edges created")
            (is (false? (:capped? kg-edges-result)) "Should not be capped")))))))

;; =============================================================================
;; E2E Test: Edge Creation with Mixed Source Types
;; =============================================================================

(deftest edge-creation-handles-mixed-source-types-e2e
  (testing "edges are created for both notes and tasks with IDs"
    ;; Create mixed source types
    (let [note-id (create-source-entry! "A session note" :type "note")
          task-id-1 (create-source-entry! "Task 1 completed" :type "note" :tags ["kanban"])
          task-id-2 (create-source-entry! "Task 2 completed" :type "note" :tags ["kanban"])]

      ;; Harvested data with mixed types
      (let [harvested (mock-harvest-result
                       [{:id note-id :content "A session note"}]
                       [{:id task-id-1 :title "Task 1 completed"}
                        {:id task-id-2 :title "Task 2 completed"}
                        {:title "Task without ID (should be ignored)"}])

            ;; Create summary
            result (crystal-hooks/crystallize-session harvested)
            summary-id (:summary-id result)

            ;; Create edges
            source-ids [note-id task-id-1 task-id-2]
            _ (create-derived-from-edges!
               summary-id source-ids "test-project" "test-agent")

            ;; Query edges
            stats (kg-edges/edge-stats)]

        ;; Should have edges for all 3 sources (ignoring the one without ID)
        (is (= 3 (:total-edges stats)))

        ;; Verify each source has an incoming edge
        (is (seq (kg-edges/get-edges-to note-id)) "Note should have incoming edge")
        (is (seq (kg-edges/get-edges-to task-id-1)) "Task 1 should have incoming edge")
        (is (seq (kg-edges/get-edges-to task-id-2)) "Task 2 should have incoming edge")))))

;; =============================================================================
;; E2E Test: Empty Source IDs
;; =============================================================================

(deftest no-edges-when-no-source-ids-e2e
  (testing "no edges created when harvested data has no IDs"
    (let [harvested (mock-harvest-result
                     ;; Notes without IDs
                     [{:content "Note without ID"}]
                     ;; Tasks without IDs
                     [{:title "Task without ID"}])

          result (crystal-hooks/crystallize-session harvested)
          summary-id (:summary-id result)

          ;; Try to create edges with empty source list
          source-ids []
          edge-result (create-derived-from-edges!
                       summary-id source-ids "test-project" "test-agent")]

      ;; Should return nil (no edges created)
      (is (nil? edge-result) "Should return nil when no source IDs")

      ;; KG should have no edges
      (let [stats (kg-edges/edge-stats)]
        (is (= 0 (:total-edges stats)) "KG should have 0 edges")))))

;; =============================================================================
;; E2E Test: Idempotent Edge Creation
;; =============================================================================

(deftest duplicate-edges-handled-gracefully-e2e
  (testing "creating duplicate edges doesn't cause errors"
    (let [source-id (create-source-entry! "Source entry")
          harvested (mock-harvest-result [{:id source-id :content "Source entry"}] [])
          result (crystal-hooks/crystallize-session harvested)
          summary-id (:summary-id result)]

      ;; Create edges first time
      (let [result-1 (create-derived-from-edges!
                      summary-id [source-id] "test-project" "test-agent")]
        (is (= 1 (:created-count result-1))))

      ;; Create edges second time (should create another edge - no dedup at this level)
      (let [result-2 (create-derived-from-edges!
                      summary-id [source-id] "test-project" "test-agent")]
        (is (= 1 (:created-count result-2))))

      ;; KG should have 2 edges (no deduplication at edge creation level)
      (let [stats (kg-edges/edge-stats)]
        (is (= 2 (:total-edges stats)) "KG should have 2 edges (duplicates allowed)")))))
