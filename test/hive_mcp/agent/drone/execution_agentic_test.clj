(ns hive-mcp.agent.drone.execution-agentic-test
  "Integration tests for in-process agentic drone execution pipeline.

   Validates the full pipeline:
   - delegate-agentic! → run-agentic-execution! → phase:execute-agentic! → backend
   - Session store (Datalevin) creation, recording, cleanup
   - Fallback to DataScript in-memory KG when Datalevin unavailable
   - phase:execute-agentic! resolves IDroneExecutionBackend and dispatches
   - Backend fallback from :hive-agent to :agentic-loop

   Tests mock backend/resolve-backend to avoid external API calls."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.drone.domain :as domain]
            [hive-mcp.agent.drone.execution :as execution]
            [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.agent.drone.augment :as augment]
            [hive-mcp.agent.drone.sandbox :as sandbox]
            [hive-mcp.agent.drone.session-kg :as session-kg]
            [hive-mcp.agent.drone.kg-factory :as kg-factory]
            [hive-mcp.agent.drone.diff-mgmt :as diff-mgmt]
            [hive-mcp.agent.hive-agent-bridge :as ha-bridge]
            [hive-mcp.agent.registry :as registry]
            [hive-mcp.tools.diff :as diff]
            [hive-mcp.agent.drone.execution.finalize :as exec-fin]
            [hive-mcp.events.core :as ev]
            [hive-mcp.hivemind.core :as hivemind]))

;;; ============================================================
;;; Test Backend (implements IDroneExecutionBackend)
;;; ============================================================

(defrecord MockBackend [result-fn captured-ctx]
  backend/IDroneExecutionBackend

  (execute-drone [_this task-context]
    (when captured-ctx
      (reset! captured-ctx task-context))
    (result-fn task-context))

  (supports-validation? [_this] false)

  (backend-type [_this] :mock))

(defn- make-mock-backend
  "Create a MockBackend that returns the given result.
   Optionally captures task-context into an atom."
  ([result]
   (make-mock-backend result nil))
  ([result captured-ctx-atom]
   (->MockBackend (if (fn? result) result (constantly result))
                  captured-ctx-atom)))

;;; ============================================================
;;; Fixtures
;;; ============================================================

(use-fixtures :each
  (fn [f]
    (kg-factory/cleanup-all-drone-stores!)
    (f)
    (kg-factory/cleanup-all-drone-stores!)))

;;; ============================================================
;;; phase:execute-agentic! Tests
;;; ============================================================

(deftest phase-execute-agentic-routes-through-backend
  (testing "phase:execute-agentic! resolves and dispatches to IDroneExecutionBackend"
    (let [backend-called (atom false)
          ctx (domain/->execution-context
               {:drone-id "drone-agentic-bind-test"
                :task-id "task-agentic-bind"
                :parent-id nil
                :project-root "/tmp"})
          task-spec (domain/->task-spec {:task "test agentic binding"
                                         :files []})
          config {:tools [] :preset nil :model "test-model" :step-budget 1}
          mock-result {:status :completed :result "mocked"
                       :tokens {:input-tokens 0 :output-tokens 0}
                       :model "test-model" :steps 0}]
      ;; Stub dependencies
      (with-redefs [augment/augment-task (fn [task _files _opts] task)
                    sandbox/create-sandbox (fn [files root]
                                             {:allowed-files (set files)
                                              :allowed-dirs #{root}
                                              :blocked-patterns []
                                              :blocked-tools #{}
                                              :rejected-files []})
                    ev/dispatch (fn [_] nil)
                    hivemind/shout! (fn [& _] nil)
                    ;; Mock backend resolution — return our MockBackend
                    backend/resolve-backend
                    (fn [_context]
                      (make-mock-backend
                       (fn [_tc]
                         (reset! backend-called true)
                         mock-result)))]
        (let [result (execution/phase:execute-agentic! ctx task-spec config)]
          ;; Verify backend was called
          (is @backend-called
              "Should dispatch through IDroneExecutionBackend")
          ;; Verify result passes through
          (is (= :completed (:status result)))
          ;; After execution, dynamic var should remain unbound
          (is (nil? domain/*drone-kg-store*)))))))

(deftest phase-execute-agentic-passes-config-to-backend
  (testing "phase:execute-agentic! passes correct params via task-context"
    (let [captured-ctx (atom nil)
          ctx (domain/->execution-context
               {:drone-id "drone-cfg-test"
                :task-id "task-cfg"
                :parent-id nil
                :project-root "/tmp"})
          task-spec (domain/->task-spec {:task "config test task"
                                         :files ["a.clj"]
                                         :cwd "/project"})
          config {:tools ["read_file" "grep"] :preset "tdd" :model "test-model" :step-budget 5}]
      (with-redefs [augment/augment-task (fn [task _files _opts] (str "augmented: " task))
                    sandbox/create-sandbox (fn [files root]
                                             {:allowed-files (set files)
                                              :allowed-dirs #{root}
                                              :blocked-patterns []
                                              :blocked-tools #{}
                                              :rejected-files []})
                    ev/dispatch (fn [_] nil)
                    hivemind/shout! (fn [& _] nil)
                    backend/resolve-backend
                    (fn [_context]
                      (make-mock-backend
                       {:status :completed :result "ok"
                        :tokens {:input-tokens 0 :output-tokens 0}
                        :model "test-model" :steps 0}
                       captured-ctx))]
        (execution/phase:execute-agentic! ctx task-spec config))
      ;; Verify task-context received correct values
      (let [tc @captured-ctx]
        ;; Task should be augmented
        (is (string? (:task tc)))
        (is (clojure.string/includes? (:task tc) "augmented"))
        ;; Model from config
        (is (= "test-model" (:model tc)))
        ;; Max steps from step-budget
        (is (= 5 (:max-steps tc)))
        ;; Preset from config
        (is (= "tdd" (:preset tc)))
        ;; Tools from config
        (is (= ["read_file" "grep"] (:tools tc)))
        ;; Drone-id from ctx
        (is (= "drone-cfg-test" (:drone-id tc)))
        ;; Sandbox should be populated
        (is (map? (:sandbox tc)))))))

(deftest phase-execute-agentic-rejects-path-escape
  (testing "phase:execute-agentic! rejects sandbox path escape attempts"
    (let [ctx (domain/->execution-context
               {:drone-id "drone-escape-test"
                :task-id "task-escape"
                :parent-id nil
                :project-root "/safe/dir"})
          task-spec (domain/->task-spec {:task "escape test"
                                         :files ["../../etc/passwd"]})
          config {:tools [] :preset nil :model "test" :step-budget 1}]
      (with-redefs [augment/augment-task (fn [task _files _opts] task)
                    sandbox/create-sandbox (fn [_files _root]
                                             {:allowed-files #{}
                                              :allowed-dirs #{}
                                              :blocked-patterns []
                                              :blocked-tools #{}
                                              :rejected-files ["../../etc/passwd"]})
                    ev/dispatch (fn [_] nil)
                    hivemind/shout! (fn [& _] nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"File paths escape"
                              (execution/phase:execute-agentic! ctx task-spec config)))))))

;;; ============================================================
;;; run-agentic-execution! Tests
;;; ============================================================

(deftest run-agentic-execution-creates-session-kg
  (testing "run-agentic-execution! creates and cleans up session store"
    (let [session-created (atom false)
          session-closed (atom false)
          task-spec (domain/->task-spec {:task "session kg test"
                                         :files []})]
      ;; Stub everything to focus on session store lifecycle
      (with-redefs [session-kg/create-session-kg!
                    (fn [_drone-id]
                      (reset! session-created true)
                      (kg-factory/create-drone-store "session-kg-stub"))
                    session-kg/close-session-kg!
                    (fn [_store _drone-id]
                      (reset! session-closed true))
                    execution/phase:prepare
                    (fn [_] {:task-type :general :tools [] :preset nil
                             :model "test" :step-budget 3
                             :model-selection {:model "test"}})
                    execution/phase:register!
                    (fn [ctx _] ctx)
                    execution/phase:validate
                    (fn [ctx _] ctx)
                    execution/phase:execute-agentic!
                    (fn [_ctx _spec _config]
                      {:status :completed :result "ok"})
                    exec-fin/phase:finalize!
                    (fn [_ctx _spec _config raw diffs]
                      raw)
                    execution/phase:cleanup!
                    (fn [_ctx _] nil)
                    domain/generate-drone-id
                    (fn [] "test-drone-session")
                    domain/generate-task-id
                    (fn [did] (str "task-" did))
                    diff/get-project-root
                    (fn [] "/tmp")
                    diff-mgmt/capture-diffs-before
                    (fn [] #{})]
        (execution/run-agentic-execution! task-spec))
      (is @session-created "Session store should be created")
      (is @session-closed "Session store should be closed in finally"))))

(deftest run-agentic-execution-fallback-to-datascript
  (testing "run-agentic-execution! falls back to DataScript when Datalevin fails"
    (let [fallback-used (atom false)
          task-spec (domain/->task-spec {:task "fallback test"
                                         :files []})]
      (with-redefs [session-kg/create-session-kg!
                    (fn [_] (throw (Exception. "Datalevin unavailable")))
                    kg-factory/create-drone-store
                    (fn [drone-id]
                      (reset! fallback-used true)
                      ;; Return nil to test nil KG path
                      nil)
                    session-kg/close-session-kg!
                    (fn [_ _] nil)
                    execution/phase:prepare
                    (fn [_] {:task-type :general :tools [] :preset nil
                             :model "test" :step-budget 3
                             :model-selection {:model "test"}})
                    execution/phase:register!
                    (fn [ctx _] ctx)
                    execution/phase:validate
                    (fn [ctx _] ctx)
                    execution/phase:execute-agentic!
                    (fn [_ctx _spec _config]
                      {:status :completed :result "ok"})
                    exec-fin/phase:finalize!
                    (fn [_ctx _spec _config raw diffs]
                      raw)
                    execution/phase:cleanup!
                    (fn [_ctx _] nil)
                    domain/generate-drone-id
                    (fn [] "test-drone-fallback")
                    domain/generate-task-id
                    (fn [did] (str "task-" did))
                    diff/get-project-root
                    (fn [] "/tmp")
                    diff-mgmt/capture-diffs-before
                    (fn [] #{})]
        (execution/run-agentic-execution! task-spec))
      (is @fallback-used "Should attempt DataScript fallback when Datalevin fails"))))

;;; ============================================================
;;; E2E: Backend Dispatch (Mock IDroneExecutionBackend)
;;; ============================================================

(deftest e2e-agentic-execution-with-mock-backend
  (testing "E2E: phase:execute-agentic! dispatches to resolved backend"
    (let [drone-id (str "e2e-test-" (System/currentTimeMillis))
          backend-type-used (atom nil)
          ctx (domain/->execution-context
               {:drone-id drone-id
                :task-id (str "task-" drone-id)
                :parent-id nil
                :project-root "/tmp"})
          task-spec (domain/->task-spec {:task "Fix the nil check"
                                         :files []})
          config {:tools [] :preset nil :model "mock-model" :step-budget 3}]
      (with-redefs [augment/augment-task (fn [task _files _opts] task)
                    sandbox/create-sandbox (fn [files root]
                                             {:allowed-files (set files)
                                              :allowed-dirs #{root}
                                              :blocked-patterns []
                                              :blocked-tools #{}
                                              :rejected-files []})
                    ev/dispatch (fn [_] nil)
                    hivemind/shout! (fn [& _] nil)
                    backend/resolve-backend
                    (fn [context]
                      (reset! backend-type-used (:backend context))
                      (make-mock-backend
                       {:status    :completed
                        :result    "I've fixed the nil check."
                        :tokens    {:input-tokens 100 :output-tokens 50}
                        :model     "mock-model"
                        :steps     2
                        :tool-calls 1
                        :metadata  {:backend :mock}}))]
        (let [result (execution/phase:execute-agentic! ctx task-spec config)]
          ;; Verify backend dispatch
          (is (= :hive-agent @backend-type-used)
              "Should resolve :hive-agent backend first")
          ;; Verify result from backend
          (is (= :completed (:status result))
              "Agentic execution should complete successfully via backend")
          (is (= "I've fixed the nil check." (:result result)))
          (is (= 2 (:steps result))))))))

(deftest e2e-agentic-backend-multi-turn
  (testing "E2E: phase:execute-agentic! backend handles multi-turn execution"
    (let [drone-id (str "e2e-backend-multi-" (System/currentTimeMillis))
          captured-ctx (atom nil)
          ctx (domain/->execution-context
               {:drone-id drone-id
                :task-id (str "task-" drone-id)
                :parent-id nil
                :project-root "/tmp"})
          task-spec (domain/->task-spec {:task "Refactor the parser"
                                         :files ["src/parser.clj"]})
          config {:tools [] :preset nil :model "mock-model" :step-budget 5}]
      (with-redefs [augment/augment-task (fn [task _files _opts] task)
                    sandbox/create-sandbox (fn [files root]
                                             {:allowed-files (set files)
                                              :allowed-dirs #{root}
                                              :blocked-patterns []
                                              :blocked-tools #{}
                                              :rejected-files []})
                    ev/dispatch (fn [_] nil)
                    hivemind/shout! (fn [& _] nil)
                    backend/resolve-backend
                    (fn [_context]
                      (make-mock-backend
                       {:status     :completed
                        :result     "Refactoring complete."
                        :tokens     {:input-tokens 200 :output-tokens 100}
                        :model      "mock-model"
                        :steps      3
                        :tool-calls 2
                        :metadata   {:backend :mock}}
                       captured-ctx))]
        (let [result (execution/phase:execute-agentic! ctx task-spec config)]
          ;; Verify task-context was passed to backend
          (is (some? @captured-ctx) "Backend should receive task-context")
          (is (= "mock-model" (:model @captured-ctx)))
          (is (= 5 (:max-steps @captured-ctx)))
          ;; Verify result
          (is (= :completed (:status result)))
          (is (= 3 (:steps result)))
          (is (= "Refactoring complete." (:result result))))))))

;;; ============================================================
;;; delegate-agentic! API Tests
;;; ============================================================

(deftest delegate-agentic-creates-task-spec
  (testing "delegate-agentic! creates proper TaskSpec and delegates to run-agentic-execution!"
    (let [captured-spec (atom nil)]
      (with-redefs [execution/run-agentic-execution!
                    (fn [task-spec]
                      (reset! captured-spec task-spec)
                      {:status :completed :result "ok"})]
        (let [drone-ns (requiring-resolve 'hive-mcp.agent.drone/delegate-agentic!)
              result (drone-ns {:task "Test task"
                                :files ["a.clj" "b.clj"]
                                :task-type :refactoring
                                :preset "tdd"
                                :cwd "/project"
                                :parent-id "parent-ling"
                                :wave-id "wave-123"
                                :trace true
                                :skip-auto-apply false})]
          (is (= :completed (:status result)))
          ;; Verify TaskSpec was created correctly
          (let [spec @captured-spec]
            (is (= "Test task" (:task spec)))
            (is (= ["a.clj" "b.clj"] (:files spec)))
            (is (= :refactoring (:task-type spec)))
            (is (= "tdd" (:preset spec)))
            (is (= "/project" (:cwd spec)))
            (is (= "parent-ling" (:parent-id spec)))))))))
