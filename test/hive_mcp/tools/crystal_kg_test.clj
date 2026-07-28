(ns hive-mcp.tools.crystal-kg-test
  "Tests for Knowledge Graph edge creation patterns used in wrap_crystallize.

   These tests verify the KG edge wiring without requiring the full crystal
   dependency chain (which needs Emacs/nrepl)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.connection :as kg-conn]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

;; Reset KG before each test
(use-fixtures :each (iso/with-isolations :kg-conn))

;; ============================================================================
;; Simulated extract-source-ids (mirrors crystal.clj implementation)
;; ============================================================================

(defn- extract-source-ids
  "Extract memory entry IDs from harvested session data.
   Mirror of hive-mcp.tools.crystal/extract-source-ids"
  [{:keys [progress-notes completed-tasks memory-ids-created]}]
  (->> (concat (keep :id progress-notes)
               (keep :id completed-tasks)
               (keep :id memory-ids-created))
       (filter some?)
       (distinct)
       (vec)))

;; ============================================================================
;; Simulated create-derived-from-edges! (mirrors crystal.clj implementation)
;; ============================================================================

(defn- create-derived-from-edges!
  "Create :derived-from KG edges linking a summary to its source entries.
   Mirror of hive-mcp.tools.crystal/create-derived-from-edges!"
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

;; ============================================================================
;; Unit tests for extract-source-ids
;; ============================================================================

(deftest extract-source-ids-test
  (testing "extracts IDs from progress-notes and completed-tasks"
    (let [harvested {:progress-notes [{:id "note-1" :content "foo"}
                                      {:id "note-2" :content "bar"}]
                     :completed-tasks [{:id "task-1" :title "Do thing"}]}
          result (extract-source-ids harvested)]
      (is (= #{"note-1" "note-2" "task-1"} (set result)))))

  (testing "extracts IDs from memory-ids-created"
    (let [harvested {:progress-notes []
                     :completed-tasks []
                     :memory-ids-created [{:id "mem-1"} {:id "mem-2"}]}
          result (extract-source-ids harvested)]
      (is (= #{"mem-1" "mem-2"} (set result)))))

  (testing "extracts IDs from all three sources combined"
    (let [harvested {:progress-notes [{:id "note-1" :content "foo"}]
                     :completed-tasks [{:id "task-1" :title "Do thing"}]
                     :memory-ids-created [{:id "mem-1"}]}
          result (extract-source-ids harvested)]
      (is (= #{"note-1" "task-1" "mem-1"} (set result)))))

  (testing "handles entries without :id"
    (let [harvested {:progress-notes [{:content "no id"}
                                      {:id "note-1" :content "has id"}]
                     :completed-tasks []}
          result (extract-source-ids harvested)]
      (is (= ["note-1"] result))))

  (testing "deduplicates IDs across all sources"
    (let [harvested {:progress-notes [{:id "dup" :content "first"}]
                     :completed-tasks [{:id "dup" :title "same id"}]
                     :memory-ids-created [{:id "dup"}]}
          result (extract-source-ids harvested)]
      (is (= ["dup"] result))))

  (testing "returns empty vector when no IDs"
    (let [harvested {:progress-notes []
                     :completed-tasks []
                     :memory-ids-created []}
          result (extract-source-ids harvested)]
      (is (= [] result)))))

;; ============================================================================
;; Unit tests for create-derived-from-edges!
;; ============================================================================

(deftest create-derived-from-edges-test
  (testing "creates edges from summary to sources"
    ;; Reset FIRST: this block asserts a GLOBAL edge count, so it cannot start
    ;; from whatever the isolated conn happens to hold. The other blocks below
    ;; already did this; omitting it here made the assertion read ambient state.
    (kg-conn/reset-conn!)
    (let [summary-id "chroma-summary-123"
          source-ids ["emacs-note-1" "emacs-note-2"]
          project-id "test-project"
          agent-id "test-agent"
          result (create-derived-from-edges! summary-id source-ids project-id agent-id)]
      (is (= 2 (:created-count result)))
      (is (= 2 (count (:edge-ids result))))
      (let [stats (kg-edges/edge-stats)]
        (is (= 2 (:total-edges stats)))
        (is (= {:derived-from 2} (:by-relation stats))))))

  (testing "returns nil when summary-id is nil"
    (kg-conn/reset-conn!)
    (let [result (create-derived-from-edges! nil ["source-1"] "project" "agent")]
      (is (nil? result))))

  (testing "returns nil when source-ids is empty"
    (kg-conn/reset-conn!)
    (let [result (create-derived-from-edges! "summary-123" [] "project" "agent")]
      (is (nil? result))))

  (testing "edges have correct attributes"
    (kg-conn/reset-conn!)
    (let [summary-id "summary-xyz"
          source-ids ["source-abc"]
          project-id "my-project"
          agent-id "my-agent"
          result (create-derived-from-edges! summary-id source-ids project-id agent-id)
          edge-id (first (:edge-ids result))
          edge (kg-edges/get-edge edge-id)]
      (is (= summary-id (:kg-edge/from edge)))
      (is (= "source-abc" (:kg-edge/to edge)))
      (is (= :derived-from (:kg-edge/relation edge)))
      (is (= project-id (:kg-edge/scope edge)))
      (is (= agent-id (:kg-edge/created-by edge))))))

;; ============================================================================
;; Integration test: KG edges flow through wrap_crystallize
;; ============================================================================

(deftest kg-stats-after-edge-creation
  (testing "kg_stats reflects created edges"
    (kg-conn/reset-conn!)
    ;; Simulate what wrap_crystallize does
    (let [summary-id "chroma-summary-456"
          source-ids ["note-a" "note-b" "task-c"]
          project-id "integration-test"
          _ (create-derived-from-edges! summary-id source-ids project-id "test-agent")
          stats (kg-edges/edge-stats)]
      (is (= 3 (:total-edges stats)))
      (is (= {:derived-from 3} (:by-relation stats)))
      (is (= {"integration-test" 3} (:by-scope stats))))))

;; ============================================================================
;; Full wrap_crystallize simulation
;; ============================================================================

(deftest wrap-crystallize-kg-integration
  (testing "wrap_crystallize flow creates KG edges when source entries have IDs"
    (kg-conn/reset-conn!)
    ;; Simulate harvested data with IDs
    (let [harvested {:progress-notes [{:id "emacs-note-001" :content "Session progress"}
                                      {:id "emacs-note-002" :content "Another note"}]
                     :completed-tasks [{:id "ds-task-001" :title "Completed task"}
                                       {:title "Task without ID"}]}  ; No ID
          ;; Simulate crystallize-session returning a summary ID
          summary-id "chroma-summary-wrap-test"
          project-id "test-project"
          agent-id "test-agent"
          ;; Extract source IDs (should get 3: 2 notes + 1 task with ID)
          source-ids (extract-source-ids harvested)]

      ;; Verify extract got the right IDs
      (is (= 3 (count source-ids)))
      (is (contains? (set source-ids) "emacs-note-001"))
      (is (contains? (set source-ids) "emacs-note-002"))
      (is (contains? (set source-ids) "ds-task-001"))

      ;; Create edges
      (let [result (create-derived-from-edges! summary-id source-ids project-id agent-id)]
        (is (= 3 (:created-count result)))

        ;; Verify via kg_stats
        (let [stats (kg-edges/edge-stats)]
          (is (= 3 (:total-edges stats)))
          (is (= {:derived-from 3} (:by-relation stats))))

        ;; Verify edges point correctly
        (let [outgoing (kg-edges/get-edges-from summary-id)]
          (is (= 3 (count outgoing)))
          (is (every? #(= summary-id (:kg-edge/from %)) outgoing))
          (is (every? #(= :derived-from (:kg-edge/relation %)) outgoing)))))))
