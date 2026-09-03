(ns hive-mcp.agent.drone.execution-agentic-test
  "Integration tests for in-process agentic drone execution pipeline.

   Validates the full pipeline:
   - delegate-agentic! → run-agentic-execution! → phase:execute-agentic! → backend
   - Session store (Datalevin) creation, recording, cleanup
   - Fallback to DataScript in-memory KG when Datalevin unavailable
   - phase:execute-agentic! resolves IDroneExecutionBackend and dispatches
   - Backend key handed to resolve-backend comes from ext-router/best-backend

   Backend execution is stubbed by ADDING methods to the resolve-backend
   multimethod (never with-redefs over it), and ext-router's discovery registry
   is ARRANGED by a fixture so its preference is observably distinct from
   execution.env/resolve-backend's hardcoded :agentic-loop fallback."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.drone.domain :as domain]
            [hive-mcp.agent.drone.execution :as execution]
            [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.agent.drone.augment :as augment]
            [hive-mcp.agent.drone.sandbox :as sandbox]
            [hive-mcp.agent.drone.session-kg :as session-kg]
            [hive-mcp.agent.drone.kg-factory :as kg-factory]
            [hive-mcp.agent.drone.diff-mgmt :as diff-mgmt]
            [hive-mcp.agent.registry :as registry]
            [hive-mcp.tools.diff :as diff]
            [hive-mcp.agent.drone.execution.finalize :as exec-fin]
            [hive-mcp.events.core :as ev]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.agent.drone.ext-router :as ext-router]
            [clojure.string]))

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

(def ^:private router-backend-keys
  "ext-router's declared backend keys, highest priority first."
  (mapv first @#'ext-router/backend-nses))

(defn- call-with-router-order
  "Pin ext-router's discovery registry to ORDER (highest priority first) with an
   empty failed set for the duration of F, restoring the previous registry after.

   Contract: while F runs, (ext-router/best-backend) returns (first ORDER), and
   load-available-backends! is a no-op — the registry is initialized and nothing
   is marked failed, so its (require ns :reload) discovery loop never executes."
  [order f]
  (let [available      @#'ext-router/available-backends
        failed         @#'ext-router/failed-backends
        prev-available @available
        prev-failed    @failed]
    (try
      (reset! available (vec order))
      (reset! failed #{})
      (f)
      (finally
        (reset! available prev-available)
        (reset! failed prev-failed)))))

(defn- arrange-router-registry
  "clojure.test :each fixture form of `call-with-router-order`, pinning every
   declared backend key in ext-router's own priority order.

   Tests that must prove production CONSULTS the router (rather than restating a
   key) cannot use this default order — one fixed order is satisfied by the
   constant that happens to head it. They re-arrange per assertion instead; see
   `e2e-agentic-execution-with-mock-backend`."
  [f]
  (call-with-router-order router-backend-keys f))

(defn- call-with-backend-stub
  "Install stub-fn as backend/resolve-backend's method for every key in ks,
   call thunk, then restore the previous dispatch table entry for each key.

   Contract: backend/resolve-backend stays a MultiFn throughout. with-redefs
   over that var replaces it with a plain fn, and any (require ns :reload)
   — ext-router/load-available-backends! issues one per backend namespace —
   then fails to compile that namespace's (defmethod resolve-backend ...)."
  [ks stub-fn thunk]
  (let [prev (into {} (map (fn [k] [k (get (methods backend/resolve-backend) k)])) ks)]
    (try
      (doseq [k ks]
        (.addMethod ^clojure.lang.MultiFn backend/resolve-backend k stub-fn))
      (thunk)
      (finally
        (doseq [k ks]
          (if-let [m (get prev k)]
            (.addMethod ^clojure.lang.MultiFn backend/resolve-backend k m)
            (remove-method backend/resolve-backend k)))))))

(defmacro ^:private with-backend-stub
  "Run body with stub-fn resolving EVERY router backend key, so the key
   production picks is observable without constraining which key it picks."
  [stub-fn & body]
  `(call-with-backend-stub router-backend-keys ~stub-fn (fn [] ~@body)))

(defn- clean-drone-stores
  "Fixture: drop every registered drone KG store before and after f."
  [f]
  (kg-factory/cleanup-all-drone-stores!)
  (f)
  (kg-factory/cleanup-all-drone-stores!))

;;; ============================================================
;;; Fixtures
;;; ============================================================

(use-fixtures :each clean-drone-stores arrange-router-registry)

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
                    hivemind/shout! (fn [& _] nil)]
        ;; Backend resolution is stubbed per dispatch key — the multimethod
        ;; stays a MultiFn, so ext-router's (require ns :reload) still works.
        (with-backend-stub
          (fn [_context]
            (make-mock-backend
             (fn [_tc]
               (reset! backend-called true)
               mock-result)))
          (let [result (execution/phase:execute-agentic! ctx task-spec config)]
            ;; Verify backend was called
            (is @backend-called
                "Should dispatch through IDroneExecutionBackend")
            ;; Verify result passes through
            (is (= :completed (:status result)))
            ;; After execution, dynamic var should remain unbound
            (is (nil? domain/*drone-kg-store*))))))))

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
                    hivemind/shout! (fn [& _] nil)]
        (with-backend-stub
          (fn [_context]
            (make-mock-backend
             {:status :completed :result "ok"
              :tokens {:input-tokens 0 :output-tokens 0}
              :model "test-model" :steps 0}
             captured-ctx))
          (execution/phase:execute-agentic! ctx task-spec config)))
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
  (testing "E2E: phase:execute-agentic! dispatches to whichever backend the router prefers"
    ;; The claim under test is that execution.env/resolve-backend ASKS
    ;; ext-router rather than restating a key. One arranged order cannot pin
    ;; that: production hardcoding the key that happens to head it would pass.
    ;; So the router's preference is VARIED — each declared backend takes the
    ;; head in turn — and production must follow it every time. No constant
    ;; satisfies two different heads.
    (let [candidate-heads (vec (remove #{:agentic-loop} router-backend-keys))]
      (is (< 1 (count candidate-heads))
          (str "Vacuous otherwise: pinning router-consultation needs at least two "
               "declared backends distinct from execution.env's :agentic-loop "
               "fallback, got " (pr-str candidate-heads)))
      (doseq [head candidate-heads]
        (testing (str "router prefers " head)
          (let [drone-id          (str "e2e-test-" head "-" (System/currentTimeMillis))
                backend-type-used (atom nil)
                ctx (domain/->execution-context
                     {:drone-id     drone-id
                      :task-id      (str "task-" drone-id)
                      :parent-id    nil
                      :project-root "/tmp"})
                task-spec (domain/->task-spec {:task "Fix the nil check"
                                               :files []})
                config {:tools [] :preset nil :model "mock-model" :step-budget 3}]
            (with-redefs [augment/augment-task (fn [task _files _opts] task)
                          sandbox/create-sandbox (fn [files root]
                                                   {:allowed-files    (set files)
                                                    :allowed-dirs     #{root}
                                                    :blocked-patterns []
                                                    :blocked-tools    #{}
                                                    :rejected-files   []})
                          ev/dispatch (fn [_] nil)
                          hivemind/shout! (fn [& _] nil)]
              ;; Every router key resolves to the mock, so the captured key is the
              ;; one production chose — the stub constrains nothing about that choice.
              (with-backend-stub
                (fn [context]
                  (reset! backend-type-used (:backend context))
                  (make-mock-backend
                   {:status     :completed
                    :result     "I've fixed the nil check."
                    :tokens     {:input-tokens 100 :output-tokens 50}
                    :model      "mock-model"
                    :steps      2
                    :tool-calls 1
                    :metadata   {:backend :mock}}))
                (call-with-router-order
                 (cons head (remove #{head} router-backend-keys))
                 (fn []
                   (let [result    (execution/phase:execute-agentic! ctx task-spec config)
                         preferred (ext-router/best-backend)]
                     ;; Guard: the arrangement must have taken, and must differ
                     ;; from the :agentic-loop key execution.env/resolve-backend
                     ;; hardcodes as its no-router fallback — otherwise the next
                     ;; assertion is (= :agentic-loop :agentic-loop).
                     (is (= head preferred)
                         "Arranged router order must decide best-backend")
                     (is (not= :agentic-loop preferred)
                         "Arranged preference must differ from the hardcoded fallback")
                     ;; With no explicit :backend in task-spec options, ext-router
                     ;; owns the choice: production must ASK, not restate.
                     (is (= preferred @backend-type-used)
                         "Should dispatch to the router's preferred backend, not a fixed key")
                     ;; Result still flows back from the backend.
                     (is (= :completed (:status result))
                         "Agentic execution should complete successfully via backend")
                     (is (= "I've fixed the nil check." (:result result)))
                     (is (= 2 (:steps result))))))))))))))

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
                    hivemind/shout! (fn [& _] nil)]
        (with-backend-stub
          (fn [_context]
            (make-mock-backend
             {:status     :completed
              :result     "Refactoring complete."
              :tokens     {:input-tokens 200 :output-tokens 100}
              :model      "mock-model"
              :steps      3
              :tool-calls 2
              :metadata   {:backend :mock}}
             captured-ctx))
          (let [result (execution/phase:execute-agentic! ctx task-spec config)]
            ;; Verify task-context was passed to backend
            (is (some? @captured-ctx) "Backend should receive task-context")
            (is (= "mock-model" (:model @captured-ctx)))
            (is (= 5 (:max-steps @captured-ctx)))
            ;; Verify result
            (is (= :completed (:status result)))
            (is (= 3 (:steps result)))
            (is (= "Refactoring complete." (:result result)))))))))

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
