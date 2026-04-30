(ns hive-mcp.tools.memory.plan-routing-test
  "Tests for plan type routing in CRUD and search handlers.

   Verifies that type='plan' entries are routed to the plans collection
   (hive-mcp-plans, OpenRouter, 4096 dims) instead of the default memory
   collection (hive-mcp-memory, Ollama, 768 dims).

   Test strategy:
   - Uses with-redefs to mock plans namespace functions
   - Verifies routing decisions (plan vs non-plan) without Chroma dependency
   - Tests: handle-add, handle-query, handle-search-semantic, handle-get-full, plan-to-kanban"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-mcp.tools.memory.crud :as crud]
            [hive-mcp.tools.memory.search :as search]
            [hive-mcp.plan.tool :as plan-tool]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.plan.plans :as plans]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ============================================================
;; Test Helpers
;; ============================================================

(def ^:private call-log
  "Atom tracking which functions were called during test execution."
  (atom []))

(defn- reset-call-log! []
  (reset! call-log []))

(defn- log-call! [fn-name & args]
  (swap! call-log conj {:fn fn-name :args (vec args)}))

(defn- calls-to [fn-name]
  (filter #(= fn-name (:fn %)) @call-log))

;; ============================================================
;; Mock Data
;; ============================================================

(def ^:private mock-plan-entry
  {:id "20260206-test-plan"
   :type "plan"
   :content "Step 1: Do X\nStep 2: Do Y\nStep 3: Do Z"
   :tags ["plan" "test" "scope:hive-mcp"]
   :project-id "hive-mcp"
   :duration "long"
   :expires ""
   :plan-status "draft"
   :steps-count 3
   :abstraction-level 4})

(def ^:private mock-memory-entry
  {:id "20260206-test-note"
   :type "note"
   :content "Some note content"
   :tags ["test" "scope:hive-mcp"]
   :project-id "hive-mcp"
   :duration "long"
   :expires ""
   :abstraction-level 2})

;; ============================================================
;; handle-add: Plan Routing Tests
;; ============================================================

(deftest handle-add-routes-plan-through-imemorystore
  (testing "type=plan flows through IMemoryStore add-entry!, NOT plans/index-plan! (post-IMem-unification)"
    (reset-call-log!)
    (with-redefs [chroma/embedding-configured?
                  (constantly true)
                  hive-mcp.tools.memory.scope/get-current-project-id
                  (constantly "hive-mcp")
                  hive-mcp.tools.memory.scope/inject-project-scope
                  (fn [tags _] tags)
                  chroma/content-hash
                  (constantly "abc123")
                  chroma/find-duplicate
                  (constantly nil)
                  chroma/index-memory-entry!
                  (fn [entry]
                    (log-call! :chroma-index entry)
                    "chroma-id")
                  plans/index-plan!
                  (fn [entry]
                    (log-call! :plans-index entry)
                    "plans-id")
                  plans/get-plan
                  (fn [id]
                    (log-call! :plans-get id)
                    mock-plan-entry)
                  chroma/get-entry-by-id
                  (fn [id]
                    (log-call! :chroma-get id)
                    mock-memory-entry)
                  hive-mcp.knowledge-graph.edges/add-edge!
                  (constantly "edge-1")
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")
                  hive-mcp.agent.context/current-agent-id
                  (constantly nil)
                  hive-mcp.tools.memory.duration/calculate-expires
                  (constantly "2026-06-01")]
      (let [result (crud/handle-add {:type "plan"
                                     :content (pr-str
                                                {:id "plan-routing-test"
                                                 :title "Routing test plan"
                                                 :steps [{:id "s1" :title "Do X"}
                                                         {:id "s2" :title "Do Y" :depends-on ["s1"]}
                                                         {:id "s3" :title "Do Z" :depends-on ["s2"]}]})
                                     :tags []
                                     :duration "long"})]
        ;; Post-IMem-unification: plans no longer route to a separate collection.
        ;; They flow through the same IMemoryStore add-entry! path as every other type.
        (is (= 0 (count (calls-to :plans-index)))
            "plans/index-plan! must NOT be called — type=plan now flows through IMemoryStore")
        (is (= 0 (count (calls-to :plans-get)))
            "plans/get-plan must NOT be called by handle-add — IMemoryStore get-entry handles retrieval")
        (is (not (:isError result)))))))

(deftest handle-add-routes-non-plan-to-memory-collection
  (testing "type=note calls chroma/index-memory-entry! (unchanged behavior)"
    (reset-call-log!)
    (with-redefs [chroma/embedding-configured?
                  (constantly true)
                  hive-mcp.tools.memory.scope/get-current-project-id
                  (constantly "hive-mcp")
                  hive-mcp.tools.memory.scope/inject-project-scope
                  (fn [tags _] tags)
                  chroma/content-hash
                  (constantly "abc123")
                  chroma/find-duplicate
                  (constantly nil)
                  chroma/index-memory-entry!
                  (fn [entry]
                    (log-call! :chroma-index entry)
                    "chroma-id")
                  plans/index-plan!
                  (fn [entry]
                    (log-call! :plans-index entry)
                    "plans-id")
                  chroma/get-entry-by-id
                  (fn [id]
                    (log-call! :chroma-get id)
                    mock-memory-entry)
                  chroma/update-entry!
                  (fn [id updates] nil)
                  hive-mcp.knowledge-graph.edges/add-edge!
                  (constantly "edge-1")
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")
                  hive-mcp.agent.context/current-agent-id
                  (constantly nil)
                  hive-mcp.tools.memory.duration/calculate-expires
                  (constantly "2026-06-01")]
      (let [result (crud/handle-add {:type "note"
                                     :content "Some note"
                                     :tags []
                                     :duration "long"})]
        ;; Should have called chroma/index-memory-entry!, NOT plans/index-plan!
        (is (= 1 (count (calls-to :chroma-index)))
            "chroma/index-memory-entry! should be called for notes")
        (is (= 0 (count (calls-to :plans-index)))
            "plans/index-plan! should NOT be called for notes")
        (is (not (:isError result)))))))

;; ============================================================
;; handle-query: Plan Routing Tests
;; ============================================================

(deftest handle-query-routes-plan-to-plans-collection
  (testing "type=plan calls plans/query-plans instead of chroma/query-entries"
    (reset-call-log!)
    (with-redefs [chroma/embedding-configured?
                  (constantly true)
                  hive-mcp.tools.memory.scope/get-current-project-id
                  (constantly "hive-mcp")
                  hive-mcp.knowledge-graph.scope/visible-scopes
                  (constantly ["hive-mcp"])
                  hive-mcp.knowledge-graph.scope/visible-scope-tags
                  (constantly #{"scope:hive-mcp"})
                  hive-mcp.knowledge-graph.edges/record-co-access!
                  (constantly nil)
                  chroma/query-entries
                  (fn [& args]
                    (log-call! :chroma-query args)
                    [])
                  plans/query-plans
                  (fn [& args]
                    (log-call! :plans-query args)
                    [mock-plan-entry])
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")]
      (let [result (crud/handle-query {:type "plan"
                                       :directory "/tmp/test"})]
        ;; Should have called plans/query-plans, NOT chroma/query-entries
        (is (= 1 (count (calls-to :plans-query)))
            "plans/query-plans should be called for type=plan")
        (is (= 0 (count (calls-to :chroma-query)))
            "chroma/query-entries should NOT be called for type=plan")
        (is (not (:isError result)))))))

(deftest handle-query-routes-non-plan-to-chroma
  (testing "type=note calls chroma/query-entries (unchanged behavior)"
    (reset-call-log!)
    (with-redefs [chroma/embedding-configured?
                  (constantly true)
                  hive-mcp.tools.memory.scope/get-current-project-id
                  (constantly "hive-mcp")
                  hive-mcp.knowledge-graph.scope/visible-scopes
                  (constantly ["hive-mcp"])
                  hive-mcp.knowledge-graph.scope/visible-scope-tags
                  (constantly #{"scope:hive-mcp"})
                  hive-mcp.knowledge-graph.edges/record-co-access!
                  (constantly nil)
                  chroma/query-entries
                  (fn [& args]
                    (log-call! :chroma-query args)
                    [mock-memory-entry])
                  plans/query-plans
                  (fn [& args]
                    (log-call! :plans-query args)
                    [])
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")]
      (let [result (crud/handle-query {:type "note"
                                       :directory "/tmp/test"})]
        ;; Should have called chroma/query-entries, NOT plans/query-plans
        (is (= 1 (count (calls-to :chroma-query)))
            "chroma/query-entries should be called for notes")
        (is (= 0 (count (calls-to :plans-query)))
            "plans/query-plans should NOT be called for notes")
        (is (not (:isError result)))))))

;; ============================================================
;; handle-get-full: Transparent Fallback Tests
;; ============================================================

(deftest handle-get-full-finds-in-memory-collection
  (testing "get-full finds entry in memory collection first"
    (reset-call-log!)
    (with-redefs [chroma/embedding-configured?
                  (constantly true)
                  chroma/get-entry-by-id
                  (fn [id]
                    (log-call! :chroma-get id)
                    mock-memory-entry)
                  plans/get-plan
                  (fn [id]
                    (log-call! :plans-get id)
                    nil)
                  hive-mcp.knowledge-graph.edges/get-edges-from
                  (constantly [])
                  hive-mcp.knowledge-graph.edges/get-edges-to
                  (constantly [])]
      (let [result (crud/handle-get-full {:id "20260206-test-note"})]
        (is (not (:isError result)))
        ;; Memory collection found it
        (is (= 1 (count (calls-to :chroma-get))))))))

(deftest handle-get-full-falls-back-to-plans-collection
  (testing "get-full falls back to plans collection when not found in memory"
    (reset-call-log!)
    (with-redefs [chroma/embedding-configured?
                  (constantly true)
                  chroma/get-entry-by-id
                  (fn [id]
                    (log-call! :chroma-get id)
                    nil)  ;; Not found in memory
                  plans/get-plan
                  (fn [id]
                    (log-call! :plans-get id)
                    mock-plan-entry)  ;; Found in plans
                  hive-mcp.knowledge-graph.edges/get-edges-from
                  (constantly [])
                  hive-mcp.knowledge-graph.edges/get-edges-to
                  (constantly [])]
      (let [result (crud/handle-get-full {:id "20260206-test-plan"})
            parsed (json/read-str (:text result) :key-fn keyword)]
        (is (not (:isError result)))
        ;; Both should be tried
        (is (= 1 (count (calls-to :chroma-get))))
        (is (= 1 (count (calls-to :plans-get))))
        ;; Should return plan entry
        (is (= "plan" (:type parsed)))))))

(deftest handle-get-full-returns-not-found-when-both-miss
  (testing "get-full returns error when entry not found in either collection"
    (reset-call-log!)
    (with-redefs [chroma/embedding-configured?
                  (constantly true)
                  chroma/get-entry-by-id
                  (constantly nil)
                  plans/get-plan
                  (constantly nil)
                  hive-mcp.knowledge-graph.edges/get-edges-from
                  (constantly [])
                  hive-mcp.knowledge-graph.edges/get-edges-to
                  (constantly [])]
      (let [result (crud/handle-get-full {:id "nonexistent"})
            parsed (json/read-str (:text result) :key-fn keyword)]
        (is (not (:isError result)))
        (is (= "Entry not found" (:error parsed)))))))

;; ============================================================
;; handle-search-semantic: Plan Routing Tests
;; ============================================================

(deftest handle-search-semantic-routes-plan-to-plans-collection
  (testing "type=plan calls plans/search-plans instead of chroma/search-similar"
    (reset-call-log!)
    (with-redefs [chroma/status
                  (constantly {:configured? true})
                  chroma/embedding-configured?
                  (constantly true)
                  hive-mcp.tools.memory.scope/get-current-project-id
                  (constantly "hive-mcp")
                  hive-mcp.knowledge-graph.scope/visible-scopes
                  (constantly ["hive-mcp"])
                  hive-mcp.knowledge-graph.edges/record-co-access!
                  (constantly nil)
                  chroma/search-similar
                  (fn [query & args]
                    (log-call! :chroma-search query)
                    [])
                  plans/search-plans
                  (fn [query & args]
                    (log-call! :plans-search query)
                    [{:id "plan-1" :type "plan" :tags ["test"]
                      :project-id "hive-mcp" :plan-status "draft"
                      :distance 0.1 :preview "Plan preview..."}])
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")]
      (let [result (search/handle-search-semantic {:query "authentication plan"
                                                   :type "plan"
                                                   :directory "/tmp/test"})
            parsed (json/read-str (:text result) :key-fn keyword)]
        ;; Should have called plans/search-plans, NOT chroma/search-similar
        (is (= 1 (count (calls-to :plans-search)))
            "plans/search-plans should be called for type=plan")
        (is (= 0 (count (calls-to :chroma-search)))
            "chroma/search-similar should NOT be called for type=plan")
        (is (not (:isError result)))
        (is (= 1 (:count parsed)))))))

(deftest handle-search-semantic-routes-non-plan-to-chroma
  (testing "type=note calls chroma/search-similar (unchanged behavior)"
    (reset-call-log!)
    (with-redefs [chroma/status
                  (constantly {:configured? true})
                  chroma/embedding-configured?
                  (constantly true)
                  hive-mcp.tools.memory.scope/get-current-project-id
                  (constantly "hive-mcp")
                  hive-mcp.tools.memory.scope/make-scope-tag
                  (constantly "scope:hive-mcp")
                  hive-mcp.knowledge-graph.scope/visible-scopes
                  (constantly ["hive-mcp"])
                  hive-mcp.knowledge-graph.edges/record-co-access!
                  (constantly nil)
                  chroma/search-similar
                  (fn [query & args]
                    (log-call! :chroma-search query)
                    [{:id "note-1" :document "Note content"
                      :metadata {:type "note" :tags "test,scope:hive-mcp"}
                      :distance 0.2}])
                  plans/search-plans
                  (fn [query & args]
                    (log-call! :plans-search query)
                    [])
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")]
      (let [result (search/handle-search-semantic {:query "some note"
                                                   :type "note"
                                                   :directory "/tmp/test"})]
        ;; Should have called chroma/search-similar, NOT plans/search-plans
        (is (= 1 (count (calls-to :chroma-search)))
            "chroma/search-similar should be called for notes")
        (is (= 0 (count (calls-to :plans-search)))
            "plans/search-plans should NOT be called for notes")
        (is (not (:isError result)))))))

(deftest handle-search-semantic-nil-type-uses-chroma
  (testing "nil type uses chroma (backward compat)"
    (reset-call-log!)
    (with-redefs [chroma/status
                  (constantly {:configured? true})
                  chroma/embedding-configured?
                  (constantly true)
                  hive-mcp.tools.memory.scope/get-current-project-id
                  (constantly "hive-mcp")
                  hive-mcp.tools.memory.scope/make-scope-tag
                  (constantly "scope:hive-mcp")
                  hive-mcp.knowledge-graph.scope/visible-scopes
                  (constantly ["hive-mcp"])
                  hive-mcp.knowledge-graph.edges/record-co-access!
                  (constantly nil)
                  chroma/search-similar
                  (fn [query & args]
                    (log-call! :chroma-search query)
                    [])
                  plans/search-plans
                  (fn [query & args]
                    (log-call! :plans-search query)
                    [])
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")]
      (let [result (search/handle-search-semantic {:query "something"
                                                   :directory "/tmp/test"})]
        (is (= 1 (count (calls-to :chroma-search)))
            "chroma should be used when type is nil")
        (is (= 0 (count (calls-to :plans-search)))
            "plans should NOT be searched when type is nil")))))

;; ============================================================
;; plan-to-kanban: Fallback Tests
;; ============================================================

(deftest plan-to-kanban-tries-plans-first
  (testing "plan-to-kanban tries plans collection before chroma"
    (reset-call-log!)
    (with-redefs [plans/get-plan
                  (fn [id]
                    (log-call! :plans-get id)
                    {:id id
                     :content "# Plan\n1. Step A\n2. Step B"
                     :type "plan"
                     :project-id "hive-mcp"})
                  chroma/get-entry-by-id
                  (fn [id]
                    (log-call! :chroma-get id)
                    nil)
                  hive-mcp.plan.parser/parse-plan
                  (fn [content opts]
                    {:success true
                     :plan {:title "Test Plan"
                            :steps [{:id "step-1" :title "Step A" :depends-on []}
                                    {:id "step-2" :title "Step B" :depends-on ["step-1"]}]}})
                  hive-mcp.plan.schema/validate-dependencies
                  (constantly {:valid true})
                  hive-mcp.plan.schema/detect-cycles
                  (constantly {:valid true})
                  hive-mcp.tools.memory-kanban/handle-mem-kanban-create
                  (fn [{:keys [title]}]
                    {:type "text"
                     :text (json/write-str {:id (str "task-" (hash title))})})
                  hive-mcp.knowledge-graph.edges/add-edge!
                  (constantly "edge-1")
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")]
      (let [result (plan-tool/plan-to-kanban "test-plan-id" :directory "/tmp/test")]
        ;; plans/get-plan should be called first
        (is (= 1 (count (calls-to :plans-get)))
            "plans/get-plan should be called")
        ;; chroma should NOT be called since plans found it (short-circuit `or`)
        (is (= 0 (count (calls-to :chroma-get)))
            "chroma should not be called when plans has the entry")
        (is (not (:isError result)))))))

(deftest plan-to-kanban-falls-back-to-chroma
  (testing "plan-to-kanban falls back to chroma when not in plans"
    (reset-call-log!)
    (with-redefs [plans/get-plan
                  (fn [id]
                    (log-call! :plans-get id)
                    nil)  ;; Not found in plans
                  chroma/get-entry-by-id
                  (fn [id]
                    (log-call! :chroma-get id)
                    {:id id
                     :content "# Plan\n1. Step A"
                     :type "plan"
                     :project-id "hive-mcp"})
                  hive-mcp.plan.parser/parse-plan
                  (fn [content opts]
                    {:success true
                     :plan {:title "Legacy Plan"
                            :steps [{:id "step-1" :title "Step A" :depends-on []}]}})
                  hive-mcp.plan.schema/validate-dependencies
                  (constantly {:valid true})
                  hive-mcp.plan.schema/detect-cycles
                  (constantly {:valid true})
                  hive-mcp.tools.memory-kanban/handle-mem-kanban-create
                  (fn [{:keys [title]}]
                    {:type "text"
                     :text (json/write-str {:id (str "task-" (hash title))})})
                  hive-mcp.knowledge-graph.edges/add-edge!
                  (constantly "edge-1")
                  hive-mcp.agent.context/current-directory
                  (constantly "/tmp/test")]
      (let [result (plan-tool/plan-to-kanban "legacy-plan-id" :directory "/tmp/test")]
        ;; Both should be tried
        (is (= 1 (count (calls-to :plans-get))))
        (is (= 1 (count (calls-to :chroma-get))))
        (is (not (:isError result)))))))
