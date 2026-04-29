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
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.project.tree :as tree]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.kanban.predicates :as kp]
            [hive-mcp.tools.kanban.transitions :as kt]
            [hive-mcp.tools.memory-kanban :as mem-kanban]
            [hive-mcp.tools.memory.scope :as scope]))
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

;; =============================================================================
;; REGRESSION — Soft-deleted (done) tasks must remain visible across descendant
;; traversal AND when explicitly status-filtered.
;;
;; Bug (kanban id 20260428091102-558757f7): after `kanban soft-delete on done`
;; landed (commit e78a18c, event-driven move + soft-delete when tasks complete),
;; calling `kanban list` with `directory` scope and `include_descendants=true`
;; returned empty for done tasks.
;;
;; ROOT CAUSE: `query-kanban-entries` previously fetched with
;; `:tags ["kanban"]` (status NOT pushed down) and a `:limit` of 100/500.
;; The store's CRUD query sorts by `:created` desc and takes `:limit` rows.
;; Soft-deleted (done) tasks retain their original (older) `:created`
;; timestamp, so once active todo/doing/review tasks accumulated past the
;; window, done tasks were truncated BEFORE the post-fetch status filter
;; ran. Status="done" → empty results. Worse, on leaf projects the limit
;; was 100 (no descendants → fall-through `:else` branch with raw `limit`),
;; making the bug strictly worse for child projects where the user
;; explicitly asked for descendant aggregation.
;;
;; FIX: push status-tag into the store query (server-side AND-filter on
;; tags) so status-restricted lookups don't get truncated by the active-
;; task window. Bump leaf-project limit to `effective-limit` whenever the
;; caller requested `include-descendants?`.
;; =============================================================================

(defn- make-store
  "Reified IMemoryStore that mirrors the Chroma/Milvus pathology:
   sort returned rows by `:created` desc and apply `:limit` BEFORE the
   caller's post-filter. Honours the `:tags` AND-filter pushdown so the
   fix is observable: with status='done' pushed in, only the 1 done entry
   is fetched and returned — without the pushdown, it would be truncated."
  [entries-atom]
  (reify mem-proto/IMemoryStore
    (connect! [_ _] nil)
    (disconnect! [_] nil)
    (connected? [_] true)
    (health-check [_] {:healthy? true})
    (add-entry! [_ _] nil)
    (get-entry [_ id] (first (filter #(= id (:id %)) @entries-atom)))
    (update-entry! [_ _ _] nil)
    (delete-entry! [_ _] true)
    (query-entries [_ opts]
      (let [{:keys [type tags project-id project-ids limit]} opts]
        (->> @entries-atom
             (filter (fn [e]
                       (and (or (nil? type) (= type (:type e)))
                            (or (nil? project-id)
                                (= project-id (:project-id e)))
                            (or (nil? project-ids)
                                (some #{(:project-id e)} project-ids))
                            (or (nil? tags)
                                (every? (set (:tags e)) tags)))))
             (sort-by :created #(compare %2 %1))
             (take (or limit 100))
             vec)))
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] 0)
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status [_] {:backend "kanban-soft-delete-mock"})
    (reset-store! [_] (reset! entries-atom []))))

(defn- iso [n-mins-ago]
  (.toString (.minusSeconds (java.time.Instant/now) (* 60 n-mins-ago))))

(defn- kanban-entry
  "Build a kanban-shaped memory entry. `mins-ago` controls `:created` so
   we can deterministically bury done tasks behind newer active tasks."
  [{:keys [id title status priority project-id mins-ago]}]
  {:id         id
   :type       "note"
   :project-id project-id
   :tags       ["kanban" status (str "priority-" priority)
                (str "scope:project:" project-id)]
   :content    {:task-type "kanban" :title title :status status :priority priority}
   :created    (iso mins-ago)})

(defn- parse-list-result
  "Decode the JSON payload from handle-mem-kanban-list-slim's mcp-json wrapper."
  [resp]
  (json/read-str (:text resp) :key-fn keyword))

(deftest ^:regression done-tasks-visible-via-descendants-after-soft-delete-test
  (testing "soft-deleted (done) child task surfaces when parent project lists with include_descendants"
    ;; Topology: parent = hive-mcp, child = hive-agent-bridge.
    ;; Seed: many newer active tasks in the child + 1 done task with old
    ;; `:created`. Pre-fix, the store sorts created-desc and the limited
    ;; window evicts the done task BEFORE the status post-filter runs.
    ;; With the fix, status=done is pushed into the store query so only
    ;; matching tasks are fetched — the active-task window is irrelevant.
    (let [active-bursts
          (vec (for [i (range 20)]
                 (kanban-entry
                  {:id         (str "kb-bridge-active-" i)
                   :title      (str "Active task " i)
                   :status     "todo"
                   :priority   "medium"
                   :project-id "hive-agent-bridge"
                   :mins-ago   (- 300 i)})))   ;; recent
          done-task
          (kanban-entry
           {:id         "kb-bridge-done-1"
            :title      "Completed child task"
            :status     "done"
            :priority   "high"
            :project-id "hive-agent-bridge"
            :mins-ago   1000})                 ;; old
          state (atom (conj active-bursts done-task))
          store (make-store state)]
      (with-redefs [mem-proto/store-set?           (constantly true)
                    mem-proto/get-store            (constantly store)
                    scope/get-current-project-id   (constantly "hive-mcp")]
        (let [resp     (mem-kanban/handle-mem-kanban-list-slim
                        {:status              "done"
                         :directory           "/fake/hive-mcp"
                         :include_descendants true})
              parsed   (parse-list-result resp)
              ids      (set (map :id parsed))]
          (is (vector? parsed) "list-slim returned a JSON array")
          (is (contains? ids "kb-bridge-done-1")
              (str "done child task missing from descendant traversal — "
                   "soft-delete + active-task window truncation regressed; "
                   "got: " (mapv :id parsed))))))))

(deftest ^:regression done-tasks-visible-on-leaf-project-with-include-descendants-test
  (testing "leaf project with include_descendants=true still surfaces old done tasks"
    ;; Pre-fix: leaf branch used raw :limit (100), so a backlog of >100
    ;; active tasks evicted older done ones. Fix bumps to effective-limit
    ;; whenever the caller asked for descendants.
    (let [actives  (vec (for [i (range 150)]
                          (kanban-entry
                           {:id         (str "kb-leaf-active-" i)
                            :title      (str "Leaf active " i)
                            :status     "todo"
                            :priority   "medium"
                            :project-id "hive-agent-bridge"
                            :mins-ago   (- 500 i)})))
          done-old (kanban-entry
                    {:id         "kb-leaf-done-old"
                     :title      "Old leaf done"
                     :status     "done"
                     :priority   "low"
                     :project-id "hive-agent-bridge"
                     :mins-ago   2000})
          state (atom (conj actives done-old))
          store (make-store state)]
      (with-redefs [mem-proto/store-set?           (constantly true)
                    mem-proto/get-store            (constantly store)
                    scope/get-current-project-id   (constantly "hive-agent-bridge")]
        (let [resp   (mem-kanban/handle-mem-kanban-list-slim
                      {:status              "done"
                       :directory           "/fake/hive-agent-bridge"
                       :include_descendants true})
              parsed (parse-list-result resp)
              ids    (set (map :id parsed))]
          (is (contains? ids "kb-leaf-done-old")
              (str "old done task on leaf project missing — leaf-branch "
                   "limit-100 truncated it before the status post-filter; "
                   "got: " (mapv :id parsed))))))))

(deftest ^:regression done-status-filter-pushed-into-store-query-test
  (testing "status filter pushed down — store query receives [\"kanban\" \"done\"], not [\"kanban\"] alone"
    ;; This is the surgical assertion: we observe the tag set the
    ;; reified store sees. Pre-fix it was ["kanban"] and clojure-side
    ;; post-filter cut to "done". Post-fix it's ["kanban" "done"] —
    ;; the store filters server-side, no truncation possible.
    (let [observed (atom [])
          state    (atom [(kanban-entry
                           {:id "kb-mcp-done-1" :title "Done"
                            :status "done" :priority "high"
                            :project-id "hive-mcp" :mins-ago 100})])
          store    (reify mem-proto/IMemoryStore
                     (connect! [_ _] nil) (disconnect! [_] nil)
                     (connected? [_] true) (health-check [_] {:healthy? true})
                     (add-entry! [_ _] nil)
                     (get-entry [_ id] (first (filter #(= id (:id %)) @state)))
                     (update-entry! [_ _ _] nil) (delete-entry! [_ _] true)
                     (query-entries [_ opts]
                       (swap! observed conj (:tags opts))
                       (vec (filter (fn [e]
                                      (every? (set (:tags e))
                                              (or (:tags opts) [])))
                                    @state)))
                     (search-similar [_ _ _] [])
                     (supports-semantic-search? [_] false)
                     (cleanup-expired! [_] 0)
                     (entries-expiring-soon [_ _ _] [])
                     (find-duplicate [_ _ _ _] nil)
                     (store-status [_] {:backend "tag-observer"})
                     (reset-store! [_] (reset! state [])))]
      (with-redefs [mem-proto/store-set?           (constantly true)
                    mem-proto/get-store            (constantly store)
                    scope/get-current-project-id   (constantly "hive-mcp")]
        (mem-kanban/handle-mem-kanban-list-slim
         {:status              "done"
          :directory           "/fake/hive-mcp"
          :include_descendants true})
        (is (some #(= ["kanban" "done"] %) @observed)
            (str "store query did NOT receive status-tag pushed down; "
                 "observed tag-sets: " (pr-str @observed)))))))

;; =============================================================================
;; List filter regression tests (token-budget filters)
;; =============================================================================

(defn- seed-store
  "Mock store seeded with diverse kanban entries spanning priorities,
   statuses, and projects so list filters have real data."
  []
  (atom
   [(kanban-entry {:id "kb-q-1" :title "Refactor auth flow"
                   :status "todo" :priority "high"
                   :project-id "hive-mcp" :mins-ago 60})
    (kanban-entry {:id "kb-q-2" :title "Add login retry"
                   :status "todo" :priority "low"
                   :project-id "hive-mcp" :mins-ago 30})
    (kanban-entry {:id "kb-q-3" :title "Fix indexing crash"
                   :status "doing" :priority "high"
                   :project-id "hive-mcp" :mins-ago 10})
    (kanban-entry {:id "kb-q-4" :title "Bridge ping handler"
                   :status "todo" :priority "medium"
                   :project-id "hive-agent-bridge" :mins-ago 5})]))

(defmacro ^:private with-list-fixtures
  [state pid & body]
  `(let [store# (make-store ~state)]
     (with-redefs [mem-proto/store-set?         (constantly true)
                   mem-proto/get-store          (constantly store#)
                   scope/get-current-project-id (constantly ~pid)]
       ~@body)))

;; Pure-predicate behavior is pinned via deftrifecta in
;; hive-mcp.tools.kanban.list-filter-trifecta-test. The integration tests
;; below cover orchestration concerns that the trifecta cannot: store
;; query observation, project-id override semantics, and end-to-end shape.

(deftest ^:regression list-filter-tag-match-all-pushed-to-store-test
  (testing "tag_match=all pushes extra tags into store query"
    (let [observed (atom [])
          state    (atom [(kanban-entry
                           {:id "kb-tag-pushed" :title "T"
                            :status "todo" :priority "high"
                            :project-id "hive-mcp" :mins-ago 5})])
          store    (reify mem-proto/IMemoryStore
                     (connect! [_ _] nil) (disconnect! [_] nil)
                     (connected? [_] true) (health-check [_] {:healthy? true})
                     (add-entry! [_ _] nil)
                     (get-entry [_ id] (first (filter #(= id (:id %)) @state)))
                     (update-entry! [_ _ _] nil) (delete-entry! [_ _] true)
                     (query-entries [_ opts]
                       (swap! observed conj (:tags opts))
                       (vec (filter (fn [e]
                                      (every? (set (:tags e))
                                              (or (:tags opts) [])))
                                    @state)))
                     (search-similar [_ _ _] [])
                     (supports-semantic-search? [_] false)
                     (cleanup-expired! [_] 0)
                     (entries-expiring-soon [_ _ _] [])
                     (find-duplicate [_ _ _ _] nil)
                     (store-status [_] {:backend "tag-pushdown-observer"})
                     (reset-store! [_] (reset! state [])))]
      (with-redefs [mem-proto/store-set?         (constantly true)
                    mem-proto/get-store          (constantly store)
                    scope/get-current-project-id (constantly "hive-mcp")]
        (mem-kanban/handle-mem-kanban-list-slim
         {:tags ["priority-high"] :tag_match "all"
          :directory "/fake/hive-mcp"
          :include_descendants false})
        (is (some #(and (some #{"kanban"} %)
                        (some #{"priority-high"} %))
                  @observed)
            (str "extra AND-tag NOT pushed to store query; "
                 "observed tag-sets: " (pr-str @observed)))))))

(deftest ^:regression list-filter-pagination-test
  (testing "limit caps result count"
    (with-list-fixtures (seed-store) "hive-mcp"
      (let [resp (mem-kanban/handle-mem-kanban-list-slim
                  {:limit 1 :directory "/fake/hive-mcp"
                   :include_descendants false})
            parsed (parse-list-result resp)]
        (is (= 1 (count parsed))
            (str "limit=1 should return 1 task; got " (count parsed))))))
  (testing "offset skips first N (sort: priority asc, then id asc)"
    (with-list-fixtures (seed-store) "hive-mcp"
      (let [resp (mem-kanban/handle-mem-kanban-list-slim
                  {:offset 2 :directory "/fake/hive-mcp"
                   :include_descendants false})
            ids  (mapv :id (parse-list-result resp))]
        (is (= ["kb-q-2"] ids)
            (str "offset=2 should skip two high-priority tasks; got: " ids))))))

(deftest ^:regression list-filter-fields-projection-test
  (testing "fields projects each task to a subset"
    (with-list-fixtures (seed-store) "hive-mcp"
      (let [resp (mem-kanban/handle-mem-kanban-list-slim
                  {:fields ["id" "title"]
                   :limit 1
                   :directory "/fake/hive-mcp"
                   :include_descendants false})
            parsed (parse-list-result resp)
            row (first parsed)]
        (is (= #{:id :title} (set (keys row)))
            (str "row keys should be only id+title; got: " (keys row)))))))

(deftest ^:regression list-filter-project-id-override-test
  (testing "project_id overrides directory-derived scope"
    (with-list-fixtures (seed-store) "hive-mcp"
      (let [resp (mem-kanban/handle-mem-kanban-list-slim
                  {:project_id "hive-agent-bridge"
                   :include_descendants false
                   :directory "/fake/hive-mcp"})
            ids  (set (map :id (parse-list-result resp)))]
        (is (= #{"kb-q-4"} ids)
            (str "explicit project_id should scope to bridge; got: " ids))))))

(deftest ^:regression list-filter-combo-test
  (testing "query + status + priority intersect"
    (with-list-fixtures (seed-store) "hive-mcp"
      (let [resp (mem-kanban/handle-mem-kanban-list-slim
                  {:query "fix" :status "inprogress" :priority "high"
                   :directory "/fake/hive-mcp"
                   :include_descendants false})
            ids  (set (map :id (parse-list-result resp)))]
        (is (= #{"kb-q-3"} ids)
            (str "expected only kb-q-3; got: " ids))))))

(deftest ^:regression list-filter-no-args-backwards-compat-test
  (testing "no filters preserves prior list-slim behavior"
    (with-list-fixtures (seed-store) "hive-mcp"
      (let [resp (mem-kanban/handle-mem-kanban-list-slim
                  {:directory "/fake/hive-mcp"
                   :include_descendants false})
            parsed (parse-list-result resp)]
        (is (vector? parsed))
        (is (= 3 (count parsed))
            (str "expected 3 hive-mcp tasks; got: " (count parsed)))))))
