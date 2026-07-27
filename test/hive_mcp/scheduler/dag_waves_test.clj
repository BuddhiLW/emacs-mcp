(ns hive-mcp.scheduler.dag-waves-test
  "Tests for the DAGWave scheduler.
   
   Tests the core scheduling logic with mocked dependencies:
   - Kanban queries mocked via with-redefs
   - KG edge queries mocked via with-redefs
   - Ling spawning mocked (no actual processes)
   - Channel subscription mocked (no pub/sub)
   
   Test categories:
   1. find-ready-tasks (pure logic)
   2. dispatch-wave! (stateful but mocked)
   3. on-ling-complete (event handler)
   4. start-dag!/stop-dag! (lifecycle)
   5. dag-status (query)
   6. Edge cases (cycles, failures, dry-run)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.scheduler.dag-waves :as dag]
            [hive-mcp.tools.memory-kanban :as mem-kanban]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.tools.memory.scope :as scope]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [hive-mcp.test.stub.memory-store :as stub]
            [hive-spi.memory.registry :as sreg]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-dag-state-fixture
  "Reset dag-state before each test."
  [f]
  (reset! dag/dag-state
          {:active    false
           :plan-id   nil
           :max-slots 5
           :wave-log  []
           :dispatched {}
           :completed  #{}
           :failed     #{}
           :opts       {}})
  (f)
  ;; Cleanup after test
  (reset! dag/dag-state
          {:active    false
           :plan-id   nil
           :max-slots 5
           :wave-log  []
           :dispatched {}
           :completed  #{}
           :failed     #{}
           :opts       {}}))

;; =============================================================================
;; Test Data
;; =============================================================================

(def test-directory "/tmp/test-project")
(def test-project-id "test-project")

;; Simulated kanban tasks
(def task-a {:id "task-a" :title "Setup database" :status "todo" :priority "high"})
(def task-b {:id "task-b" :title "Write API" :status "todo" :priority "medium"})
(def task-c {:id "task-c" :title "Write tests" :status "todo" :priority "medium"})
(def task-d {:id "task-d" :title "Deploy" :status "todo" :priority "low"})

;; DAG: A -> B -> D, A -> C -> D
;; Wave 0: A
;; Wave 1: B, C (both depend on A)
;; Wave 2: D (depends on B and C)

(def mock-edges
  "KG edges representing task dependencies.
   task-B --depends-on--> task-A
   task-C --depends-on--> task-A
   task-D --depends-on--> task-B
   task-D --depends-on--> task-C"
  {"task-a" []  ; A has no outgoing depends-on
   "task-b" [{:kg-edge/from "task-b" :kg-edge/to "task-a" :kg-edge/relation :depends-on}]
   "task-c" [{:kg-edge/from "task-c" :kg-edge/to "task-a" :kg-edge/relation :depends-on}]
   "task-d" [{:kg-edge/from "task-d" :kg-edge/to "task-b" :kg-edge/relation :depends-on}
             {:kg-edge/from "task-d" :kg-edge/to "task-c" :kg-edge/relation :depends-on}]})

;; Chroma entry existence tracker (for done detection)
(def ^:private *chroma-entries (atom {"task-a" task-a
                                      "task-b" task-b
                                      "task-c" task-c
                                      "task-d" task-d}))

;; =============================================================================
;; Mock Helpers
;; =============================================================================

(defn mock-kanban-list-slim
  "Mock kanban list that returns tasks from *chroma-entries."
  [{:keys [status directory]}]
  (let [entries (vals @*chroma-entries)
        filtered (if status
                   (filter #(= status (:status %)) entries)
                   entries)]
    {:type "text" :text (json/write-str filtered)}))

(defn mock-get-edges-from
  "Mock KG edge query."
  ([task-id] (get mock-edges task-id []))
  ([task-id _scope] (get mock-edges task-id [])))

(defn mock-get-entry-by-id
  "The entry table the injected stub store answers `get-entry` from.
   Returns nil for a 'done' (deleted) task — that absence is how the scheduler
   decides a dependency is satisfied. A test needing a different table rebinds
   this var."
  [task-id]
  (get @*chroma-entries task-id))

(defn mock-kanban-move
  "Mock kanban move. When moving to 'done', removes from *chroma-entries."
  [{:keys [task_id new_status]}]
  (when (= new_status "done")
    (swap! *chroma-entries dissoc task_id))
  {:type "text" :text (json/write-str {:id task_id :status new_status})})

(def ^:private *spawned-lings (atom []))

(defn mock-create-ling!
  "Mock ling creation. Records spawn without starting actual processes."
  [id opts]
  (swap! *spawned-lings conj {:id id :opts opts})
  id)

(defn mock-shout!
  "Mock hivemind shout. No-op."
  [& _args]
  true)

(defn mock-subscribe!
  "Mock channel subscribe. Returns a channel that won't receive events."
  [_event-type]
  (async/chan 1))

(defn mock-unsubscribe!
  "Mock channel unsubscribe."
  [_event-type _ch]
  nil)

(defn mock-get-scope
  "Mock scope resolution."
  [_dir]
  test-project-id)

(defn mock-get-slave
  "Mock DataScript slave query."
  [agent-id]
  (let [spawned (first (filter #(= agent-id (:id %)) @*spawned-lings))]
    (when spawned
      {:slave/id agent-id
       :slave/kanban-task-id (get-in spawned [:opts :kanban-task-id])
       :slave/cwd test-directory})))

;; =============================================================================
;; Macro for test isolation
;; =============================================================================

(defmacro with-dag-mocks
  "Run body with all DAG dependencies mocked.

   Entry existence is NOT mocked here — it arrives through the stub memory
   store the `with-task-store` fixture installs at the port."
  [& body]
  `(do
     ;; Reset mutable test state
     (reset! *chroma-entries {"task-a" task-a
                              "task-b" task-b
                              "task-c" task-c
                              "task-d" task-d})
     (reset! *spawned-lings [])
     (with-redefs [mem-kanban/handle-mem-kanban-list-slim mock-kanban-list-slim
                   mem-kanban/handle-mem-kanban-move mock-kanban-move
                   kg-edges/get-edges-from mock-get-edges-from
                   ling/create-ling! mock-create-ling!
                   hivemind/shout! mock-shout!
                   channel/subscribe! mock-subscribe!
                   channel/unsubscribe! mock-unsubscribe!
                   scope/get-current-project-id mock-get-scope
                   ds-queries/get-slave mock-get-slave]
       ~@body)))

(defn with-task-store
  "clojure.test fixture: install a stub IMemoryStore whose `get-entry` answers
   from `mock-get-entry-by-id`.

   The scheduler asks whether a dependency still exists through
   hive-mcp.vectordb.facade, which resolves the active IMemoryStore from the
   hive-spi registry. The table therefore has to be injected at the port; a
   `with-redefs` on any vectordb namespace binds a var the scheduler no longer
   calls, and every dependency then reads as already-satisfied."
  [f]
  (let [prior (sreg/registered-stores)]
    (try
      (stub/install! (stub/->stub nil {:get-entry-fn (fn [id] (mock-get-entry-by-id id))}))
      (f)
      (finally
        (sreg/reset-registry!)
        (doseq [[k s] prior] (sreg/register-store! k s))))))

(use-fixtures :each reset-dag-state-fixture with-task-store)

;; =============================================================================
;; Tests: find-ready-tasks
;; =============================================================================

(deftest find-ready-tasks-wave-0
  (testing "Tasks with no dependencies are immediately ready"
    (with-dag-mocks
      (let [ready (dag/find-ready-tasks test-directory #{} {} #{})]
        (is (= 1 (count ready)) "Only task-a has no dependencies")
        (is (= "task-a" (:task-id (first ready))))
        (is (= 0 (:dep-count (first ready))))))))

(deftest find-ready-tasks-wave-1
  (testing "Tasks become ready when dependencies complete"
    (with-dag-mocks
      ;; After A completes, B and C should be ready
      (let [ready (dag/find-ready-tasks test-directory #{"task-a"} {} #{})]
        (is (= 2 (count ready)) "Both B and C depend only on A")
        (is (= #{"task-b" "task-c"} (set (map :task-id ready))))))))

(deftest find-ready-tasks-wave-2
  (testing "Tasks with multiple deps wait for all"
    (with-dag-mocks
      ;; After A and B complete, D is not ready (still needs C)
      (let [ready (dag/find-ready-tasks test-directory #{"task-a" "task-b"} {} #{})]
        (is (= 1 (count ready)) "Only C is ready, D still needs C")
        (is (= "task-c" (:task-id (first ready)))))

      ;; After A, B, C complete, D is ready
      (let [ready (dag/find-ready-tasks test-directory #{"task-a" "task-b" "task-c"} {} #{})]
        (is (= 1 (count ready)) "D is now ready")
        (is (= "task-d" (:task-id (first ready))))))))

(deftest find-ready-tasks-excludes-dispatched
  (testing "Already dispatched tasks are excluded"
    (with-dag-mocks
      (let [ready (dag/find-ready-tasks test-directory #{} {"task-a" "ling-1"} #{})]
        (is (= 0 (count ready)) "task-a is already dispatched")))))

(deftest find-ready-tasks-excludes-failed
  (testing "Failed tasks are excluded"
    (with-dag-mocks
      (let [ready (dag/find-ready-tasks test-directory #{} {} #{"task-a"})]
        (is (= 0 (count ready)) "task-a is in failed set")))))

(deftest find-ready-tasks-empty-dag
  (testing "No tasks returns empty vector"
    (with-redefs [mem-kanban/handle-mem-kanban-list-slim
                  (fn [_] {:type "text" :text "[]"})
                  kg-edges/get-edges-from (fn [& _] [])
                  mock-get-entry-by-id (fn [_] nil)]
      (let [ready (dag/find-ready-tasks test-directory #{} {} #{})]
        (is (= 0 (count ready)))))))

;; =============================================================================
;; Tests: dispatch-wave!
;; =============================================================================

(deftest dispatch-wave-basic
  (testing "Dispatches lings for ready tasks"
    (with-dag-mocks
      ;; Initialize state
      (reset! dag/dag-state (assoc @dag/dag-state :active true))

      (let [ready [{:task-id "task-a" :title "Setup database" :deps #{} :dep-count 0}]
            result (dag/dispatch-wave! ready 5
                                       {:cwd test-directory
                                        :presets ["ling"]
                                        :project-id test-project-id})]
        (is (= 1 (:dispatched-count result)))
        (is (= 1 (count @*spawned-lings)))
        (is (= "task-a" (get-in (first @*spawned-lings) [:opts :kanban-task-id])))
        (is (contains? (:dispatched @dag/dag-state) "task-a"))))))

(deftest dispatch-wave-respects-slots
  (testing "Only dispatches up to available slots"
    (with-dag-mocks
      (reset! dag/dag-state (assoc @dag/dag-state
                                   :active true
                                   :dispatched {"existing-1" "ling-x"
                                                "existing-2" "ling-y"}))

      (let [ready [{:task-id "task-a" :title "A" :deps #{} :dep-count 0}
                   {:task-id "task-b" :title "B" :deps #{} :dep-count 0}
                   {:task-id "task-c" :title "C" :deps #{} :dep-count 0}]
            ;; max-slots=3 but 2 already dispatched = 1 available
            result (dag/dispatch-wave! ready 3
                                       {:cwd test-directory
                                        :presets ["ling"]
                                        :project-id test-project-id})]
        (is (= 1 (:dispatched-count result)))
        (is (= 2 (:skipped-count result)))))))

(deftest dispatch-wave-zero-slots-dry-run
  (testing "max-slots=0 dispatches nothing (dry-run)"
    (with-dag-mocks
      (reset! dag/dag-state (assoc @dag/dag-state :active true))

      (let [ready [{:task-id "task-a" :title "A" :deps #{} :dep-count 0}]
            result (dag/dispatch-wave! ready 0
                                       {:cwd test-directory
                                        :presets ["ling"]
                                        :project-id test-project-id})]
        (is (= 0 (:dispatched-count result)))
        (is (= 0 (count @*spawned-lings)))))))

(deftest dispatch-wave-threads-role
  (testing "A ready task carrying a :role yields a spawn request with that role"
    (with-dag-mocks
      (reset! dag/dag-state (assoc @dag/dag-state :active true))

      (let [ready [{:task-id "task-a" :title "Setup" :role "architect"
                    :deps #{} :dep-count 0}]
            _ (dag/dispatch-wave! ready 5
                                  {:cwd test-directory
                                   :presets ["ling"]
                                   :project-id test-project-id})
            spawn-opts (:opts (first @*spawned-lings))]
        (is (= {:role "architect"} (:spawn/request spawn-opts))
            "role threaded into create-ling! via :spawn/request")))))

(deftest dispatch-wave-role-absent-fail-soft
  (testing "A ready task with no :role produces no :spawn/request (fail-soft)"
    (with-dag-mocks
      (reset! dag/dag-state (assoc @dag/dag-state :active true))

      (let [ready [{:task-id "task-a" :title "Setup" :deps #{} :dep-count 0}]
            _ (dag/dispatch-wave! ready 5
                                  {:cwd test-directory
                                   :presets ["ling"]
                                   :project-id test-project-id})
            spawn-opts (:opts (first @*spawned-lings))]
        (is (nil? (:spawn/request spawn-opts))
            "no role => no spawn request key")))))

;; =============================================================================
;; Tests: on-ling-complete
;; =============================================================================

(deftest on-ling-complete-success
  (testing "Successful completion marks task done and dispatches next wave"
    (with-dag-mocks
      ;; Setup: A is dispatched
      (reset! dag/dag-state
              {:active true
               :plan-id "test-plan"
               :max-slots 5
               :wave-log []
               :dispatched {"task-a" "swarm-dag-test-123"}
               :completed #{}
               :failed #{}
               :opts {:cwd test-directory
                      :presets ["ling"]
                      :project-id test-project-id}})
      ;; Mock the slave lookup to return kanban-task-id
      (with-redefs [ds-queries/get-slave
                    (fn [agent-id]
                      (when (= agent-id "swarm-dag-test-123")
                        {:slave/id "swarm-dag-test-123"
                         :slave/kanban-task-id "task-a"
                         :slave/cwd test-directory}))]

        (dag/on-ling-complete {:agent-id "swarm-dag-test-123"
                               :project-id test-project-id
                               :data {:result "success"}})

        ;; Verify state updates
        (is (contains? (:completed @dag/dag-state) "task-a"))
        (is (not (contains? (:dispatched @dag/dag-state) "task-a")))
        ;; A was deleted from chroma (moved to done)
        (is (nil? (get @*chroma-entries "task-a")))
        ;; B and C should now be dispatched (they depend only on A)
        (is (= 2 (count @*spawned-lings)) "B and C should be auto-dispatched")))))

(deftest on-ling-complete-failure
  (testing "Failed ling moves task to failed set"
    (with-dag-mocks
      (reset! dag/dag-state
              {:active true
               :plan-id "test-plan"
               :max-slots 5
               :wave-log []
               :dispatched {"task-a" "swarm-dag-fail-123"}
               :completed #{}
               :failed #{}
               :opts {:cwd test-directory
                      :presets ["ling"]
                      :project-id test-project-id}})

      (with-redefs [ds-queries/get-slave
                    (fn [agent-id]
                      (when (= agent-id "swarm-dag-fail-123")
                        {:slave/id "swarm-dag-fail-123"
                         :slave/kanban-task-id "task-a"
                         :slave/cwd test-directory}))]

        (dag/on-ling-complete {:agent-id "swarm-dag-fail-123"
                               :project-id test-project-id
                               :data {:result "failure"}})

        ;; Task should be in failed, not completed
        (is (contains? (:failed @dag/dag-state) "task-a"))
        (is (not (contains? (:completed @dag/dag-state) "task-a")))
        ;; Task should NOT be deleted from chroma
        (is (some? (get @*chroma-entries "task-a")))))))

(deftest on-ling-complete-ignores-non-dag-lings
  (testing "Lings without kanban-task-id are ignored"
    (with-dag-mocks
      (reset! dag/dag-state (assoc @dag/dag-state :active true))

      (with-redefs [ds-queries/get-slave
                    (fn [_] {:slave/id "random-ling" :slave/kanban-task-id nil})]
        ;; Should not throw
        (dag/on-ling-complete {:agent-id "random-ling"
                               :project-id test-project-id
                               :data {}})
        (is (= 0 (count (:completed @dag/dag-state))))))))

(deftest on-ling-complete-ignores-when-inactive
  (testing "Events are ignored when DAG is not active"
    (with-dag-mocks
      (reset! dag/dag-state (assoc @dag/dag-state :active false))

      ;; Should not throw or modify state
      (dag/on-ling-complete {:agent-id "test"
                             :project-id test-project-id
                             :data {}})
      (is (= 0 (count (:completed @dag/dag-state)))))))

;; =============================================================================
;; Tests: Lifecycle (start-dag!/stop-dag!)
;; =============================================================================

(deftest start-dag-initializes-state
  (testing "start-dag! initializes state and dispatches first wave"
    (with-dag-mocks
      (let [result (dag/start-dag! "test-plan-001"
                                   {:cwd test-directory
                                    :max-slots 3
                                    :presets ["ling"]})]
        (is (:started result))
        (is (= "test-plan-001" (:plan-id result)))
        (is (= 3 (:max-slots result)))
        ;; Task A should be ready (wave 0)
        (is (= 1 (:ready-count result)))
        ;; Task A should have been dispatched
        (is (= 1 (get-in result [:initial-dispatch :dispatched-count])))
        ;; State should reflect dispatch
        (is (:active @dag/dag-state))
        (is (= "test-plan-001" (:plan-id @dag/dag-state)))))))

(deftest start-dag-rejects-when-active
  (testing "start-dag! throws when already active"
    (with-dag-mocks
      (dag/start-dag! "plan-1" {:cwd test-directory})
      (is (thrown? clojure.lang.ExceptionInfo
                   (dag/start-dag! "plan-2" {:cwd test-directory}))))))

(deftest stop-dag-returns-stats
  (testing "stop-dag! returns final statistics"
    (with-dag-mocks
      (dag/start-dag! "test-plan" {:cwd test-directory})
      ;; Simulate some progress
      (swap! dag/dag-state update :completed conj "fake-task")

      (let [result (dag/stop-dag!)]
        (is (:stopped result))
        (is (= "test-plan" (:plan-id result)))
        (is (= 1 (:completed-count result)))
        (is (not (:active @dag/dag-state)))))))

;; =============================================================================
;; Tests: dag-status
;; =============================================================================

(deftest dag-status-when-inactive
  (testing "dag-status returns inactive state"
    (let [status (dag/dag-status)]
      (is (not (:active status)))
      (is (nil? (:plan-id status))))))

(deftest dag-status-when-active
  (testing "dag-status returns full progress"
    (with-dag-mocks
      (dag/start-dag! "status-test" {:cwd test-directory :max-slots 5})

      (let [status (dag/dag-status)]
        (is (:active status))
        (is (= "status-test" (:plan-id status)))
        (is (= 5 (:max-slots status)))
        ;; task-a was dispatched
        (is (= 1 (:dispatched status)))
        (is (= 0 (:completed status)))
        (is (= 0 (:failed status)))))))

;; =============================================================================
;; Tests: Full DAG Execution (Integration-style)
;; =============================================================================

(deftest full-dag-execution
  (testing "Complete DAG execution: A -> B,C -> D"
    (with-dag-mocks
      ;; Start the DAG
      (dag/start-dag! "full-test" {:cwd test-directory :max-slots 5})

      ;; Wave 0: A dispatched
      (is (= 1 (count @*spawned-lings)))
      (let [a-ling-id (:id (first @*spawned-lings))]

        ;; Simulate A completing
        (with-redefs [ds-queries/get-slave
                      (fn [agent-id]
                        (when (= agent-id a-ling-id)
                          {:slave/id a-ling-id
                           :slave/kanban-task-id "task-a"
                           :slave/cwd test-directory}))]
          (dag/on-ling-complete {:agent-id a-ling-id
                                 :project-id test-project-id
                                 :data {:result "success"}})))

      ;; Wave 1: B and C should now be dispatched (total: 3 lings spawned)
      (is (= 3 (count @*spawned-lings)) "A + B + C = 3 lings spawned")
      (is (contains? (:completed @dag/dag-state) "task-a"))

      ;; Simulate B completing
      (let [b-ling (nth @*spawned-lings 1)
            b-ling-id (:id b-ling)]
        (with-redefs [ds-queries/get-slave
                      (fn [agent-id]
                        (when (= agent-id b-ling-id)
                          {:slave/id b-ling-id
                           :slave/kanban-task-id (get-in b-ling [:opts :kanban-task-id])
                           :slave/cwd test-directory}))]
          (dag/on-ling-complete {:agent-id b-ling-id
                                 :project-id test-project-id
                                 :data {:result "success"}})))

      ;; D still not ready (needs C too)
      (is (= 3 (count @*spawned-lings)) "D not yet dispatched, waiting on C")

      ;; Simulate C completing
      (let [c-ling (nth @*spawned-lings 2)
            c-ling-id (:id c-ling)]
        (with-redefs [ds-queries/get-slave
                      (fn [agent-id]
                        (when (= agent-id c-ling-id)
                          {:slave/id c-ling-id
                           :slave/kanban-task-id (get-in c-ling [:opts :kanban-task-id])
                           :slave/cwd test-directory}))]
          (dag/on-ling-complete {:agent-id c-ling-id
                                 :project-id test-project-id
                                 :data {:result "success"}})))

      ;; Wave 2: D should now be dispatched (total: 4 lings)
      (is (= 4 (count @*spawned-lings)) "A + B + C + D = 4 lings spawned")

      ;; Simulate D completing
      (let [d-ling (nth @*spawned-lings 3)
            d-ling-id (:id d-ling)]
        (with-redefs [ds-queries/get-slave
                      (fn [agent-id]
                        (when (= agent-id d-ling-id)
                          {:slave/id d-ling-id
                           :slave/kanban-task-id (get-in d-ling [:opts :kanban-task-id])
                           :slave/cwd test-directory}))]
          (dag/on-ling-complete {:agent-id d-ling-id
                                 :project-id test-project-id
                                 :data {:result "success"}})))

      ;; All tasks complete, DAG should auto-stop
      (is (= 4 (count (:completed @dag/dag-state))))
      (is (= 0 (count (:dispatched @dag/dag-state))))
      (is (not (:active @dag/dag-state)) "DAG should auto-stop when all tasks done"))))

;; =============================================================================
;; Tests: Wave Log
;; =============================================================================

(deftest wave-log-tracks-events
  (testing "Wave log records dispatch and completion events"
    (with-dag-mocks
      (dag/start-dag! "log-test" {:cwd test-directory})

      ;; Check dispatch was logged
      (let [log (:wave-log @dag/dag-state)]
        (is (= 1 (count log)))
        (is (= :dispatched (:event (first log))))
        (is (= "task-a" (:task-id (first log))))
        (is (some? (:timestamp (first log))))))))

;; =============================================================================
;; Tests: E2E — Failure Blocks Dependents
;; =============================================================================

(deftest failure-blocks-downstream-tasks
  (testing "When task-a fails, its dependents B and C never dispatch"
    (with-dag-mocks
      ;; Start DAG — A gets dispatched (wave 0)
      (dag/start-dag! "fail-block-test" {:cwd test-directory :max-slots 5})
      (is (= 1 (count @*spawned-lings)) "Only A dispatched initially")

      (let [a-ling-id (:id (first @*spawned-lings))]
        ;; Simulate A FAILING (error event)
        (with-redefs [ds-queries/get-slave
                      (fn [agent-id]
                        (when (= agent-id a-ling-id)
                          {:slave/id a-ling-id
                           :slave/kanban-task-id "task-a"
                           :slave/cwd test-directory}))]
          (dag/on-ling-complete {:agent-id a-ling-id
                                 :project-id test-project-id
                                 :data {:event-type :error
                                        :result "failure"}})))

      ;; A is in failed set
      (is (contains? (:failed @dag/dag-state) "task-a"))
      ;; No new lings spawned — B and C blocked because A not in completed
      (is (= 1 (count @*spawned-lings)) "B and C NOT dispatched after A failed")
      ;; Verify B and C are not dispatchable
      (let [ready (dag/find-ready-tasks test-directory
                                        (:completed @dag/dag-state)
                                        (:dispatched @dag/dag-state)
                                        (:failed @dag/dag-state))]
        (is (= 0 (count ready)) "No tasks ready — A failed, B/C deps unsatisfied")))))

(deftest partial-failure-independent-branch-continues
  (testing "Failure in one branch doesn't block independent tasks"
    ;; Extended DAG: A->B->D, A->C->D, E (independent, no deps)
    (let [task-e {:id "task-e" :title "Independent task" :status "todo" :priority "low"}
          extended-edges (assoc mock-edges "task-e" [])]
      (with-redefs [mem-kanban/handle-mem-kanban-list-slim
                    (fn [{:keys [status]}]
                      (let [entries (vals @*chroma-entries)
                            filtered (if status (filter #(= status (:status %)) entries) entries)]
                        {:type "text" :text (json/write-str filtered)}))
                    mem-kanban/handle-mem-kanban-move mock-kanban-move
                    kg-edges/get-edges-from (fn ([tid] (get extended-edges tid []))
                                              ([tid _] (get extended-edges tid [])))
                    ling/create-ling! mock-create-ling!
                    hivemind/shout! mock-shout!
                    channel/subscribe! mock-subscribe!
                    channel/unsubscribe! mock-unsubscribe!
                    scope/get-current-project-id mock-get-scope
                    ds-queries/get-slave mock-get-slave]
        ;; Add task-e to the entry table
        (reset! *chroma-entries {"task-a" task-a "task-b" task-b
                                 "task-c" task-c "task-d" task-d
                                 "task-e" task-e})
        (reset! *spawned-lings [])

        ;; Start: A and E should both be ready (no deps)
        (dag/start-dag! "partial-fail" {:cwd test-directory :max-slots 5})
        (is (= 2 (count @*spawned-lings)) "A and E dispatched (both have no deps)")

        ;; Find which ling is A and which is E
        (let [a-ling (first (filter #(= "task-a" (get-in % [:opts :kanban-task-id])) @*spawned-lings))
              e-ling (first (filter #(= "task-e" (get-in % [:opts :kanban-task-id])) @*spawned-lings))]
          (is (some? a-ling) "A was dispatched")
          (is (some? e-ling) "E was dispatched")

          ;; Simulate A failing
          (with-redefs [ds-queries/get-slave
                        (fn [agent-id]
                          (cond
                            (= agent-id (:id a-ling))
                            {:slave/id (:id a-ling) :slave/kanban-task-id "task-a" :slave/cwd test-directory}
                            (= agent-id (:id e-ling))
                            {:slave/id (:id e-ling) :slave/kanban-task-id "task-e" :slave/cwd test-directory}))]
            (dag/on-ling-complete {:agent-id (:id a-ling)
                                   :project-id test-project-id
                                   :data {:event-type :error :result "failure"}})

            ;; A failed, but E is still dispatched — no new spawns for B/C
            (is (contains? (:failed @dag/dag-state) "task-a"))
            (is (= 2 (count @*spawned-lings)) "Still only A and E spawned, B/C blocked")

            ;; E completes successfully
            (dag/on-ling-complete {:agent-id (:id e-ling)
                                   :project-id test-project-id
                                   :data {:result "success"}}))

          ;; E is completed, A is failed, B/C/D still blocked
          (is (contains? (:completed @dag/dag-state) "task-e"))
          (is (contains? (:failed @dag/dag-state) "task-a"))
          (is (= 2 (count @*spawned-lings)) "No further dispatches — B/C blocked by A"))))))

;; =============================================================================
;; Tests: E2E — Slot Saturation & Drain
;; =============================================================================

(deftest slot-saturation-then-drain
  (testing "When slots are full, completions free slots for next wave"
    ;; Use max-slots=1 to force sequential execution
    (with-dag-mocks
      (dag/start-dag! "slot-test" {:cwd test-directory :max-slots 1})

      ;; Wave 0: A dispatched (1 slot used, 0 available)
      (is (= 1 (count @*spawned-lings)))
      (is (= 1 (count (:dispatched @dag/dag-state))))

      ;; Even though nothing else is ready yet, verify slot is saturated
      (let [a-ling-id (:id (first @*spawned-lings))]
        ;; Complete A — frees 1 slot, B and C become ready but only 1 slot
        (with-redefs [ds-queries/get-slave
                      (fn [agent-id]
                        (when (= agent-id a-ling-id)
                          {:slave/id a-ling-id
                           :slave/kanban-task-id "task-a"
                           :slave/cwd test-directory}))]
          (dag/on-ling-complete {:agent-id a-ling-id
                                 :project-id test-project-id
                                 :data {:result "success"}})))

      ;; Only 1 of B/C dispatched (slot limit = 1)
      (is (= 2 (count @*spawned-lings)) "A + one of B/C = 2 total")
      (is (= 1 (count (:dispatched @dag/dag-state))) "Only 1 slot occupied")

      ;; Complete the second task — next one gets its slot
      (let [second-ling (nth @*spawned-lings 1)
            second-id (:id second-ling)
            second-task-id (get-in second-ling [:opts :kanban-task-id])]
        (with-redefs [ds-queries/get-slave
                      (fn [agent-id]
                        (when (= agent-id second-id)
                          {:slave/id second-id
                           :slave/kanban-task-id second-task-id
                           :slave/cwd test-directory}))]
          (dag/on-ling-complete {:agent-id second-id
                                 :project-id test-project-id
                                 :data {:result "success"}})))

      ;; Third task dispatched (the other of B/C, or D if both B/C done)
      (is (= 3 (count @*spawned-lings)) "Third task dispatched after slot freed")
      (is (= 1 (count (:dispatched @dag/dag-state))) "Still respecting 1-slot limit"))))

(deftest blocked-event-marks-failure
  (testing "A :blocked event type is treated as failure"
    (with-dag-mocks
      (reset! dag/dag-state
              {:active true
               :plan-id "blocked-test"
               :max-slots 5
               :wave-log []
               :dispatched {"task-a" "swarm-dag-blocked-123"}
               :completed #{}
               :failed #{}
               :opts {:cwd test-directory
                      :presets ["ling"]
                      :project-id test-project-id}})

      (with-redefs [ds-queries/get-slave
                    (fn [agent-id]
                      (when (= agent-id "swarm-dag-blocked-123")
                        {:slave/id "swarm-dag-blocked-123"
                         :slave/kanban-task-id "task-a"
                         :slave/cwd test-directory}))]
        (dag/on-ling-complete {:agent-id "swarm-dag-blocked-123"
                               :project-id test-project-id
                               :data {:event-type :blocked
                                      :message "Need admin access"}}))

      ;; Blocked = failure in DAG context
      (is (contains? (:failed @dag/dag-state) "task-a"))
      (is (not (contains? (:completed @dag/dag-state) "task-a")))
      ;; Kanban entry NOT deleted (still in chroma)
      (is (some? (get @*chroma-entries "task-a"))))))

;; =============================================================================
;; Tests: E2E — Wave Log Completeness
;; =============================================================================

(deftest wave-log-captures-full-lifecycle
  (testing "Wave log captures dispatched, completed, and failed events"
    (with-dag-mocks
      (dag/start-dag! "lifecycle-log" {:cwd test-directory :max-slots 5})

      ;; Complete A
      (let [a-ling-id (:id (first @*spawned-lings))]
        (with-redefs [ds-queries/get-slave
                      (fn [agent-id]
                        (when (= agent-id a-ling-id)
                          {:slave/id a-ling-id
                           :slave/kanban-task-id "task-a"
                           :slave/cwd test-directory}))]
          (dag/on-ling-complete {:agent-id a-ling-id
                                 :project-id test-project-id
                                 :data {:result "success"}})))

      (let [log (:wave-log @dag/dag-state)
            events (map :event log)]
        ;; Should have: dispatch A, complete A, dispatch B, dispatch C
        (is (>= (count log) 4) "At least 4 events logged")
        (is (some #{:dispatched} events))
        (is (some #{:completed} events))))))

;; =============================================================================
;; Tests: E2E — Cycle Detection (Circular Dependencies)
;; =============================================================================

(deftest circular-deps-no-ready-tasks
  (testing "Tasks with circular dependencies are never ready"
    ;; Create a cycle: X -> Y -> Z -> X
    ;; None of these should ever be ready since each waits for the other
    (let [task-x {:id "task-x" :title "Task X" :status "todo" :priority "medium"}
          task-y {:id "task-y" :title "Task Y" :status "todo" :priority "medium"}
          task-z {:id "task-z" :title "Task Z" :status "todo" :priority "medium"}
          cycle-edges {"task-x" [{:kg-edge/from "task-x" :kg-edge/to "task-z" :kg-edge/relation :depends-on}]
                       "task-y" [{:kg-edge/from "task-y" :kg-edge/to "task-x" :kg-edge/relation :depends-on}]
                       "task-z" [{:kg-edge/from "task-z" :kg-edge/to "task-y" :kg-edge/relation :depends-on}]}]
      (with-redefs [mem-kanban/handle-mem-kanban-list-slim
                    (fn [{:keys [status]}]
                      (let [entries [task-x task-y task-z]
                            filtered (if status (filter #(= status (:status %)) entries) entries)]
                        {:type "text" :text (json/write-str filtered)}))
                    kg-edges/get-edges-from (fn ([tid] (get cycle-edges tid []))
                                              ([tid _] (get cycle-edges tid [])))
                    mock-get-entry-by-id (fn [tid]
                                           ({"task-x" task-x "task-y" task-y "task-z" task-z} tid))]
        (let [ready (dag/find-ready-tasks test-directory #{} {} #{})]
          (is (= 0 (count ready)) "Circular deps: no tasks should be ready"))))))

(deftest mixed-dag-with-cycle-island
  (testing "Tasks not in cycle are still ready; cycled tasks stuck forever"
    ;; A (no deps) + cycle: X -> Y -> X
    (let [task-x {:id "task-x" :title "Task X" :status "todo" :priority "medium"}
          task-y {:id "task-y" :title "Task Y" :status "todo" :priority "medium"}
          mixed-edges (merge mock-edges
                             {"task-x" [{:kg-edge/from "task-x" :kg-edge/to "task-y" :kg-edge/relation :depends-on}]
                              "task-y" [{:kg-edge/from "task-y" :kg-edge/to "task-x" :kg-edge/relation :depends-on}]})]
      (with-redefs [mem-kanban/handle-mem-kanban-list-slim
                    (fn [{:keys [status]}]
                      (let [entries (concat (vals @*chroma-entries) [task-x task-y])
                            filtered (if status (filter #(= status (:status %)) entries) entries)]
                        {:type "text" :text (json/write-str filtered)}))
                    mem-kanban/handle-mem-kanban-move mock-kanban-move
                    kg-edges/get-edges-from (fn ([tid] (get mixed-edges tid []))
                                              ([tid _] (get mixed-edges tid [])))
                    ling/create-ling! mock-create-ling!
                    hivemind/shout! mock-shout!
                    channel/subscribe! mock-subscribe!
                    channel/unsubscribe! mock-unsubscribe!
                    scope/get-current-project-id mock-get-scope
                    ds-queries/get-slave mock-get-slave]
        (reset! *chroma-entries {"task-a" task-a "task-b" task-b
                                 "task-c" task-c "task-d" task-d
                                 "task-x" task-x "task-y" task-y})
        (reset! *spawned-lings [])

        ;; A has no deps → ready; X and Y are in a cycle → stuck
        (let [ready (dag/find-ready-tasks test-directory #{} {} #{})]
          (is (= 1 (count ready)) "Only task-a is ready (no deps)")
          (is (= "task-a" (:task-id (first ready)))))))))

;; =============================================================================
;; Tests: E2E — Manual Kanban Move Unblocks Dependents
;; =============================================================================

(deftest manual-kanban-done-unblocks-dependents
  (testing "Manually moving a task to done (deleting from Chroma) unblocks its dependents"
    (with-dag-mocks
      ;; task-a exists in chroma. B depends on A.
      ;; Simulate manual kanban move to done by removing from Chroma
      (swap! *chroma-entries dissoc "task-a")

      ;; Now B and C should be ready because task-a is no longer in Chroma
      ;; (kanban-task-done? returns true when entry doesn't exist)
      (let [ready (dag/find-ready-tasks test-directory #{} {} #{})]
        ;; A is gone from chroma so B/C deps are satisfied
        ;; But A is also gone from kanban todos, so only B and C return
        (is (= 2 (count ready)) "B and C are ready after A manually done")
        (is (= #{"task-b" "task-c"} (set (map :task-id ready))))))))

(deftest manual-kanban-done-during-active-dag
  (testing "Manually completing a task while DAG is running triggers frontier update"
    (with-dag-mocks
      ;; Start DAG — A is dispatched
      (dag/start-dag! "manual-test" {:cwd test-directory :max-slots 5})
      (is (= 1 (count @*spawned-lings)) "A dispatched")

      ;; Instead of on-ling-complete, simulate manual kanban move:
      ;; Remove A from chroma (as if moved to done externally)
      (swap! *chroma-entries dissoc "task-a")
      ;; Also update dag-state to reflect A is done
      (swap! dag/dag-state (fn [s]
                             (-> s
                                 (update :dispatched dissoc "task-a")
                                 (update :completed conj "task-a"))))

      ;; Now manually trigger a frontier check (simulating next dispatch cycle)
      (let [ready (dag/find-ready-tasks test-directory
                                        (:completed @dag/dag-state)
                                        (:dispatched @dag/dag-state)
                                        (:failed @dag/dag-state))]
        (is (= 2 (count ready)) "B and C ready after manual A completion")
        ;; Dispatch the new wave
        (dag/dispatch-wave! ready (:max-slots @dag/dag-state) (:opts @dag/dag-state))
        (is (= 3 (count @*spawned-lings)) "A + B + C spawned")))))

;; =============================================================================
;; Tests: E2E — Flat DAG (No Dependencies)
;; =============================================================================

(deftest flat-dag-all-tasks-wave-zero
  (testing "Plan with no dependencies dispatches all tasks at once (up to max-slots)"
    (let [flat-tasks [{:id "ft-1" :title "Task 1" :status "todo" :priority "medium"}
                      {:id "ft-2" :title "Task 2" :status "todo" :priority "medium"}
                      {:id "ft-3" :title "Task 3" :status "todo" :priority "medium"}
                      {:id "ft-4" :title "Task 4" :status "todo" :priority "medium"}]
          no-edges (zipmap (map :id flat-tasks) (repeat []))]
      (with-redefs [mem-kanban/handle-mem-kanban-list-slim
                    (fn [{:keys [status]}]
                      (let [filtered (if status (filter #(= status (:status %)) flat-tasks) flat-tasks)]
                        {:type "text" :text (json/write-str filtered)}))
                    mem-kanban/handle-mem-kanban-move mock-kanban-move
                    kg-edges/get-edges-from (fn ([tid] (get no-edges tid []))
                                              ([tid _] (get no-edges tid [])))
                    mock-get-entry-by-id (fn [tid]
                                           (first (filter #(= tid (:id %)) flat-tasks)))
                    ling/create-ling! mock-create-ling!
                    hivemind/shout! mock-shout!
                    channel/subscribe! mock-subscribe!
                    channel/unsubscribe! mock-unsubscribe!
                    scope/get-current-project-id mock-get-scope
                    ds-queries/get-slave mock-get-slave]
        (reset! *spawned-lings [])

        ;; All 4 tasks should be ready
        (let [ready (dag/find-ready-tasks test-directory #{} {} #{})]
          (is (= 4 (count ready)) "All 4 tasks ready with no deps"))

        ;; Start DAG with max-slots=5 (enough for all)
        (dag/start-dag! "flat-test" {:cwd test-directory :max-slots 5})
        (is (= 4 (count @*spawned-lings)) "All 4 dispatched in wave 0")
        (is (= 4 (count (:dispatched @dag/dag-state))))))))

(deftest flat-dag-respects-max-slots
  (testing "Flat DAG with max-slots=2 only dispatches 2 of 4 tasks"
    (let [flat-tasks [{:id "ft-1" :title "Task 1" :status "todo" :priority "medium"}
                      {:id "ft-2" :title "Task 2" :status "todo" :priority "medium"}
                      {:id "ft-3" :title "Task 3" :status "todo" :priority "medium"}
                      {:id "ft-4" :title "Task 4" :status "todo" :priority "medium"}]
          no-edges (zipmap (map :id flat-tasks) (repeat []))]
      (with-redefs [mem-kanban/handle-mem-kanban-list-slim
                    (fn [{:keys [status]}]
                      (let [filtered (if status (filter #(= status (:status %)) flat-tasks) flat-tasks)]
                        {:type "text" :text (json/write-str filtered)}))
                    mem-kanban/handle-mem-kanban-move mock-kanban-move
                    kg-edges/get-edges-from (fn ([tid] (get no-edges tid []))
                                              ([tid _] (get no-edges tid [])))
                    mock-get-entry-by-id (fn [tid]
                                           (first (filter #(= tid (:id %)) flat-tasks)))
                    ling/create-ling! mock-create-ling!
                    hivemind/shout! mock-shout!
                    channel/subscribe! mock-subscribe!
                    channel/unsubscribe! mock-unsubscribe!
                    scope/get-current-project-id mock-get-scope
                    ds-queries/get-slave mock-get-slave]
        (reset! *spawned-lings [])

        (dag/start-dag! "flat-limited" {:cwd test-directory :max-slots 2})
        (is (= 2 (count @*spawned-lings)) "Only 2 of 4 dispatched (max-slots=2)")
        (is (= 2 (count (:dispatched @dag/dag-state))))))))

;; =============================================================================
;; Tests: E2E — Dispatch Error Handling (create-ling! throws)
;; =============================================================================

(deftest dispatch-handles-ling-spawn-failure
  (testing "When create-ling! throws, task moves to failed set"
    (with-dag-mocks
      (reset! dag/dag-state (assoc @dag/dag-state :active true))

      ;; Override mock to throw on spawn
      (with-redefs [ling/create-ling! (fn [_id _opts]
                                        (throw (ex-info "Spawn failed" {:reason "Emacs crashed"})))]
        (let [ready [{:task-id "task-a" :title "Setup database" :deps #{} :dep-count 0}]
              result (dag/dispatch-wave! ready 5
                                         {:cwd test-directory
                                          :presets ["ling"]
                                          :project-id test-project-id})]
          ;; Dispatch should report failure, not crash
          (is (= 0 (:dispatched-count result)) "No successful dispatches")
          (is (= :failed (:status (first (:dispatched-tasks result)))))
          ;; Task should be in failed set to prevent retry loops
          (is (contains? (:failed @dag/dag-state) "task-a")))))))

(deftest dispatch-partial-spawn-failure
  (testing "Some lings fail to spawn but others succeed"
    (with-dag-mocks
      (reset! dag/dag-state (assoc @dag/dag-state :active true))

      (let [spawn-count (atom 0)]
        ;; First spawn succeeds, second throws
        (with-redefs [ling/create-ling! (fn [id opts]
                                          (swap! spawn-count inc)
                                          (if (= 1 @spawn-count)
                                            (do (swap! *spawned-lings conj {:id id :opts opts}) id)
                                            (throw (ex-info "Spawn failed" {}))))]
          (let [ready [{:task-id "task-a" :title "A" :deps #{} :dep-count 0}
                       {:task-id "task-b" :title "B" :deps #{} :dep-count 0}]
                result (dag/dispatch-wave! ready 5
                                           {:cwd test-directory
                                            :presets ["ling"]
                                            :project-id test-project-id})]
            (is (= 1 (:dispatched-count result)) "One succeeded")
            (is (contains? (:dispatched @dag/dag-state) "task-a") "A dispatched")
            (is (contains? (:failed @dag/dag-state) "task-b") "B failed")))))))

;; =============================================================================
;; Tests: E2E — Idempotent Double Completion
;; =============================================================================

(deftest double-completion-is-safe
  (testing "Same ling completing twice does not corrupt state"
    (with-dag-mocks
      (reset! dag/dag-state
              {:active true
               :plan-id "idempotent-test"
               :max-slots 5
               :wave-log []
               :dispatched {"task-a" "swarm-dag-idem-123"}
               :completed #{}
               :failed #{}
               :opts {:cwd test-directory
                      :presets ["ling"]
                      :project-id test-project-id}})

      (with-redefs [ds-queries/get-slave
                    (fn [agent-id]
                      (when (= agent-id "swarm-dag-idem-123")
                        {:slave/id "swarm-dag-idem-123"
                         :slave/kanban-task-id "task-a"
                         :slave/cwd test-directory}))]
        ;; First completion
        (dag/on-ling-complete {:agent-id "swarm-dag-idem-123"
                               :project-id test-project-id
                               :data {:result "success"}})

        (is (contains? (:completed @dag/dag-state) "task-a"))
        (let [spawned-after-first (count @*spawned-lings)]

          ;; Second completion (duplicate event)
          (dag/on-ling-complete {:agent-id "swarm-dag-idem-123"
                                 :project-id test-project-id
                                 :data {:result "success"}})

          ;; State should NOT have changed (task-a was already completed,
          ;; no longer in dispatched map so the second call is a no-op)
          (is (contains? (:completed @dag/dag-state) "task-a"))
          (is (= spawned-after-first (count @*spawned-lings))
              "No additional lings spawned on duplicate completion"))))))

;; =============================================================================
;; Tests: E2E — Deep DAG Chain (Linear Pipeline)
;; =============================================================================

(deftest deep-linear-chain
  (testing "Linear chain A -> B -> C -> D executes sequentially"
    ;; Unlike the diamond DAG, this is purely sequential
    (let [linear-edges {"task-a" []
                        "task-b" [{:kg-edge/from "task-b" :kg-edge/to "task-a" :kg-edge/relation :depends-on}]
                        "task-c" [{:kg-edge/from "task-c" :kg-edge/to "task-b" :kg-edge/relation :depends-on}]
                        "task-d" [{:kg-edge/from "task-d" :kg-edge/to "task-c" :kg-edge/relation :depends-on}]}]
      (with-redefs [mem-kanban/handle-mem-kanban-list-slim mock-kanban-list-slim
                    mem-kanban/handle-mem-kanban-move mock-kanban-move
                    kg-edges/get-edges-from (fn ([tid] (get linear-edges tid []))
                                              ([tid _] (get linear-edges tid [])))
                    ling/create-ling! mock-create-ling!
                    hivemind/shout! mock-shout!
                    channel/subscribe! mock-subscribe!
                    channel/unsubscribe! mock-unsubscribe!
                    scope/get-current-project-id mock-get-scope
                    ds-queries/get-slave mock-get-slave]
        (reset! *chroma-entries {"task-a" task-a "task-b" task-b
                                 "task-c" task-c "task-d" task-d})
        (reset! *spawned-lings [])

        ;; Only A should be ready initially
        (let [ready (dag/find-ready-tasks test-directory #{} {} #{})]
          (is (= 1 (count ready)))
          (is (= "task-a" (:task-id (first ready)))))

        ;; After A completes, only B ready
        (let [ready (dag/find-ready-tasks test-directory #{"task-a"} {} #{})]
          (is (= 1 (count ready)))
          (is (= "task-b" (:task-id (first ready)))))

        ;; After A+B complete, only C ready
        (let [ready (dag/find-ready-tasks test-directory #{"task-a" "task-b"} {} #{})]
          (is (= 1 (count ready)))
          (is (= "task-c" (:task-id (first ready)))))

        ;; After A+B+C complete, only D ready
        (let [ready (dag/find-ready-tasks test-directory #{"task-a" "task-b" "task-c"} {} #{})]
          (is (= 1 (count ready)))
          (is (= "task-d" (:task-id (first ready)))))

        ;; After all complete, nothing ready
        (let [ready (dag/find-ready-tasks test-directory #{"task-a" "task-b" "task-c" "task-d"} {} #{})]
          (is (= 0 (count ready))))))))
