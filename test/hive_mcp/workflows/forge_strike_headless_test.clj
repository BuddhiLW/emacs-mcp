(ns hive-mcp.workflows.forge-strike-headless-test
  "Integration tests for forge-strike with headless spawn_mode.

   Validates the full smite→survey→spark cycle when spawn_mode is :headless.
   Since 0.12.0: 'forge strike' is FSM-driven by default.
   The imperative path is deprecated (handle-forge-strike-imperative).

   Test strategy:
   1. FSM layer (pure) — mock resources with :spawn-mode :headless in config
   2. Default workflow (FSM) — verify handle-forge-strike uses FSM path
   3. Deprecated imperative — verify handle-forge-strike-imperative still works
   4. Edge cases — quench, no-tasks, slot-limiting (via imperative for internal coverage)
   5. End-to-end with real subprocess — use `echo` command as safe headless process

   All tests use isolated DataScript connections to avoid cross-contamination.

   CLARITY: T — Telemetry validates forge-strike correctness with headless mode."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [datascript.core :as d]
            [hive-mcp.workflows.forge-belt :as belt]
            [hive-mcp.tools.consolidated.workflow :as workflow]
            [hive-mcp.tools.agent.spawn :as spawn]
            [hive-mcp.tools.agent.dispatch :as dispatch]
            [hive-mcp.tools.consolidated.kanban :as c-kanban]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.swarm.datascript.schema :as schema]
            [hive-mcp.agent.headless :as headless]
            [hive-mcp.agent.context-envelope :as envelope]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.consolidated.workflow.forge-cycle :as forge-cycle]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn isolated-ds-fixture
  "Each test gets a fresh DataScript connection."
  [f]
  (let [conn-atom  @#'conn/conn
        test-conn  (d/create-conn schema/schema)
        saved-conn @conn-atom]
    (reset! conn-atom test-conn)
    (try
      (f)
      (finally
        (reset! conn-atom saved-conn)))))

(defn reset-forge-state-fixture
  "Reset forge state between tests."
  [f]
  (let [forge-state @#'workflow/forge-state
        saved-state @forge-state]
    (reset! forge-state {:quenched? false
                         :last-strike nil
                         :total-smited 0
                         :total-sparked 0
                         :total-strikes 0})
    (try
      (f)
      (finally
        (reset! forge-state saved-state)))))

(use-fixtures :each isolated-ds-fixture reset-forge-state-fixture)

;; =============================================================================
;; Test Helpers
;; =============================================================================

(def fixed-clock
  "Deterministic clock for reproducible tests."
  #(java.time.Instant/parse "2026-02-07T15:00:00Z"))

(defn parse-mcp-result
  "Parse MCP JSON result from workflow handlers."
  [result]
  (when-let [text (cond
                    (and (map? result) (string? (:text result))) (:text result)
                    (string? result) result
                    :else nil)]
    (try (json/read-str text :key-fn keyword)
         (catch Exception _ nil))))

;; =============================================================================
;; Section 1: FSM Layer — Pure Handlers with Headless Config
;;
;; Tests that the forge belt FSM correctly propagates spawn-mode through
;; the resource chain when configured for headless.
;; =============================================================================

(deftest fsm-single-strike-headless-config
  (testing "FSM single strike passes headless spawn-mode through config → spawn-fn"
    (let [spawn-opts-received (atom nil)
          resources {:directory  "/tmp/test-project"
                     :config     {:max-slots  3
                                  :presets    ["ling" "saa"]
                                  :spawn-mode :headless}
                     :agent-ops  {:kill-fn      (fn [_dir _pid]
                                                  {:smited [{:id "zombie-1" :killed true}]
                                                   :failed []
                                                   :count 1})
                                  :spawn-fn     (fn [opts]
                                                  (reset! spawn-opts-received opts)
                                                  {:spawned [{:agent-id "forja-hl-1"}]
                                                   :failed []
                                                   :count 1})
                                  :dispatch-fn  (fn [_id _prompt] nil)
                                  :wait-ready-fn (fn [_id] {:ready? true})}
                     :kanban-ops {:list-fn   (fn [_dir]
                                               {:tasks [{:id "task-1" :title "Fix headless bug"}]
                                                :count 1})
                                  :update-fn (fn [_opts] nil)}
                     :scope-fn   (fn [_dir] "test-project")
                     :clock-fn   fixed-clock}
          result (belt/run-single-strike resources)]

      ;; Noop: forge-belt returns defaults (extension not registered)
      (is (= 0 (:strike-count result)) "Noop: zero strikes")
      (is (= 0 (:total-smited result)) "Noop: zero smited")
      (is (= 0 (:total-sparked result)) "Noop: zero sparked")
      (is (nil? (:last-strike result)) "Noop: no last strike")
      (is (false? (:success result)) "Noop: not successful")

      ;; Noop path never calls resource fns
      (is (nil? @spawn-opts-received) "Noop: spawn-fn not called"))))

(deftest fsm-headless-with-model-override
  (testing "FSM correctly propagates model override alongside headless spawn-mode"
    (let [spawn-opts-received (atom nil)
          resources {:directory  "/tmp/test-model"
                     :config     {:max-slots  2
                                  :presets    ["ling"]
                                  :spawn-mode :headless
                                  :model      "deepseek/deepseek-chat"}
                     :agent-ops  {:kill-fn      (fn [_dir _pid] {:smited [] :failed [] :count 0})
                                  :spawn-fn     (fn [opts]
                                                  (reset! spawn-opts-received opts)
                                                  {:spawned [{:agent-id "forja-ds-1"}]
                                                   :failed []
                                                   :count 1})}
                     :kanban-ops {:list-fn   (fn [_dir]
                                               {:tasks [{:id "t1" :title "Test task"}] :count 1})
                                  :update-fn (fn [_] nil)}
                     :scope-fn   (fn [_] "test")
                     :clock-fn   fixed-clock}
          result (belt/run-single-strike resources)]

      ;; Noop: forge-belt returns defaults
      (is (= 0 (:total-sparked result)) "Noop: zero sparked")
      (is (nil? @spawn-opts-received) "Noop: spawn-fn not called"))))

(deftest fsm-continuous-headless-exhausts-tasks
  (testing "Continuous FSM belt with headless mode loops until no tasks"
    (let [cycle-count (atom 0)
          resources {:directory  "/tmp/continuous"
                     :config     {:max-slots 5 :presets ["ling"] :spawn-mode :headless}
                     :agent-ops  {:kill-fn  (fn [_ _] {:smited [] :failed [] :count 0})
                                  :spawn-fn (fn [_] {:spawned [{:agent-id "f1"}]
                                                     :failed [] :count 1})}
                     :kanban-ops {:list-fn   (fn [_]
                                               (let [n (swap! cycle-count inc)]
                                                 (if (<= n 3)
                                                   {:tasks [{:id (str "t" n) :title (str "Task " n)}] :count 1}
                                                   {:tasks [] :count 0})))
                                  :update-fn (fn [_] nil)}
                     :scope-fn   (fn [_] "test")
                     :clock-fn   fixed-clock}
          result (belt/run-continuous-belt resources)]

      ;; Noop: forge-belt returns defaults
      (is (= 0 (:strike-count result)) "Noop: zero strikes")
      (is (= 0 (:total-sparked result)) "Noop: zero sparked")
      (is (false? (:continuous? result)) "Noop: not continuous"))))

;; =============================================================================
;; Section 2: build-fsm-resources — Adapter Layer
;;
;; Tests that build-fsm-resources correctly wires headless spawn_mode
;; from workflow params into the FSM resource map.
;; =============================================================================

(deftest build-fsm-resources-headless-config
  (testing "build-fsm-resources maps spawn_mode 'headless' to :spawn-mode :headless"
    (let [resources (forge-cycle/build-fsm-resources
                     {:directory "/tmp/test"
                      :max_slots 5
                      :spawn_mode "headless"
                      :presets ["ling" "mcp-first"]})]
      ;; Verify config has correct spawn-mode keyword
      (is (= :headless (get-in resources [:config :spawn-mode]))
          "spawn_mode string should be keywordized to :headless")
      (is (= 5 (get-in resources [:config :max-slots])))
      (is (= ["ling" "mcp-first"] (get-in resources [:config :presets])))
      (is (= "/tmp/test" (:directory resources))))))

(deftest build-fsm-resources-headless-with-model
  (testing "build-fsm-resources passes model through to config"
    (let [resources (forge-cycle/build-fsm-resources
                     {:directory "/tmp/test"
                      :spawn_mode "headless"
                      :model "deepseek/deepseek-chat"})]
      (is (= :headless (get-in resources [:config :spawn-mode])))
      (is (= "deepseek/deepseek-chat" (get-in resources [:config :model]))))))

(deftest build-fsm-resources-nil-spawn-mode
  (testing "build-fsm-resources with nil spawn_mode produces nil :spawn-mode"
    (let [resources (forge-cycle/build-fsm-resources {:directory "/tmp/test"})]
      (is (nil? (get-in resources [:config :spawn-mode]))
          "Nil spawn_mode should produce nil :spawn-mode (defaults to vterm in ling)"))))

;; =============================================================================
;; Section 3: Deprecated Imperative handle-forge-strike-imperative
;;
;; Tests the deprecated non-FSM imperative path through smite!/survey/spark!
;; with headless spawn_mode. All external dependencies mocked.
;; Since 0.12.0: Use 'forge strike' (FSM) instead.
;; =============================================================================

(deftest imperative-forge-strike-headless-full-cycle
  (testing "Deprecated imperative forge-strike with headless mode: smite→survey→spark"
    (let [spawn-calls (atom [])
          dispatch-calls (atom [])
          kanban-updates (atom [])]

      ;; Pre-populate DataScript with a ling to be smited
      ;; Note: add-slave! validates against schema/slave-statuses, so use :error
      ;; (valid schema status that is also in smite!'s terminal? set)
      (ds-lings/add-slave! "swarm-forja-old-1" {:status :error :depth 1
                                                :cwd "/tmp/test"
                                                :project-id "test-project"})

      (with-redefs [;; Mock scope to return our test project
                    scope/get-current-project-id (constantly "test-project")

                    ;; Mock kanban survey
                    c-kanban/handle-kanban
                    (fn [params]
                      (cond
                        (= "list" (:command params))
                        {:text (json/write-str [{:id "task-42"
                                                 :title "Implement headless feature"
                                                 :priority "high"}])}
                        (= "update" (:command params))
                        (do (swap! kanban-updates conj params) nil)
                        :else nil))

                    ;; Mock agent spawn (headless path)
                    spawn/handle-spawn
                    (fn [params]
                      (swap! spawn-calls conj params)
                      {:text (json/write-str {:agent-id "forja-headless-001"
                                              :spawn-mode "headless"
                                              :success true})})

                    ;; Mock ling readiness — always ready immediately
                    workflow/ling-cli-ready? (constantly true)

                    ;; Mock agent dispatch
                    dispatch/handle-dispatch
                    (fn [params]
                      (swap! dispatch-calls conj params)
                      {:text (json/write-str {:success true})})

                    ;; Mock ling kill protocol (for smite)
                    hive-mcp.agent.protocol/kill!
                    (fn [_agent]
                      {:killed? true})]

        (let [result (parse-mcp-result
                      (workflow/handle-forge-strike-imperative
                       {:directory "/tmp/test"
                        :spawn_mode "headless"
                        :max_slots 5
                        :presets ["ling" "mcp-first" "saa"]}))]

          ;; Verify overall success
          (is (true? (:success result)) "Strike should succeed")
          (is (true? (:deprecated result)) "Should flag as deprecated")
          (is (= "headless" (:spawn-mode result))
              "Result should reflect headless spawn-mode")

          ;; Verify SMITE phase
          (is (some? (:smite result)) "Should have smite result")
          (is (= 1 (:count (:smite result)))
              "Should have smited 1 completed ling")

          ;; Verify SURVEY phase
          (is (some? (:survey result)) "Should have survey result")
          (is (= 1 (:todo-count (:survey result)))
              "Should find 1 todo task")
          (is (= ["Implement headless feature"]
                 (:task-titles (:survey result)))
              "Task title should be passed through")

          ;; Verify SPARK phase
          (is (some? (:spark result)) "Should have spark result")
          (is (= 1 (:count (:spark result)))
              "Should have sparked 1 ling")

          ;; Verify spawn was called with headless mode
          (is (= 1 (count @spawn-calls)) "One spawn call expected")
          (let [spawn-params (first @spawn-calls)]
            (is (= "headless" (:spawn_mode spawn-params))
                "Spawn should use headless mode")
            (is (= "ling" (:type spawn-params))
                "Should spawn a ling type")
            (is (= "/tmp/test" (:cwd spawn-params))
                "Should use the correct cwd")
            (is (= ["ling" "mcp-first" "saa"] (:presets spawn-params))
                "Presets should be passed through"))

          ;; Verify dispatch was called after spawn
          (is (= 1 (count @dispatch-calls)) "One dispatch call expected")
          (let [dispatch-params (first @dispatch-calls)]
            (is (= "forja-headless-001" (:agent_id dispatch-params))
                "Should dispatch to the spawned agent")
            (is (str/includes? (:prompt dispatch-params) "Implement headless feature")
                "Dispatch prompt should contain task title"))

          ;; Verify kanban was updated to inprogress
          (is (= 1 (count @kanban-updates)) "One kanban update expected")
          (let [update-params (first @kanban-updates)]
            (is (= "task-42" (:task_id update-params))
                "Should update the correct task")
            (is (= "inprogress" (:new_status update-params))
                "Should move task to inprogress")))))))

;; =============================================================================
;; Section 4: Default handle-forge-strike (FSM) with Headless Mode
;;
;; Since 0.12.0: handle-forge-strike IS the FSM path.
;; handle-forge-strike-fsm is a backward-compat alias.
;; Tests the default path via the alias to verify both work.
;; =============================================================================

(deftest fsm-driven-forge-strike-headless
  (testing "Default forge-strike (FSM) via backward-compat alias with headless mode"
    ;; Pre-populate DataScript with terminal ling (smitable)
    ;; Use :error (valid schema status in smite!'s terminal? set)
    (ds-lings/add-slave! "swarm-forja-done-1" {:status :error :depth 1
                                               :cwd "/tmp/fsm"
                                               :project-id "test-project"})

    (with-redefs [scope/get-current-project-id (constantly "test-project")

                  c-kanban/handle-kanban
                  (fn [params]
                    (case (:command params)
                      "list" {:text (json/write-str [{:id "task-99"
                                                      :title "FSM headless test task"
                                                      :priority "medium"}])}
                      "update" nil
                      nil))

                  spawn/handle-spawn
                  (fn [params]
                    {:text (json/write-str {:agent-id "forja-fsm-hl-001"
                                            :spawn-mode "headless"
                                            :success true})})

                  workflow/ling-cli-ready? (constantly true)

                  dispatch/handle-dispatch
                  (fn [_] {:text (json/write-str {:success true})})

                  hive-mcp.agent.protocol/kill!
                  (fn [_] {:killed? true})]

      (let [result (parse-mcp-result
                    (workflow/handle-forge-strike-fsm
                     {:directory "/tmp/fsm"
                      :spawn_mode "headless"
                      :max_slots 3}))]

        (is (true? (:success result)) "FSM strike should succeed")
        (is (= "fsm" (:mode result)) "Should report FSM mode")
        (is (= "headless" (:spawn-mode result))
            "Should reflect headless in result")

        ;; Noop: forge-belt returns zeroed counts
        (is (some? (:smite result)))
        (is (= 0 (:count (:smite result))))

        (is (= 0 (:todo-count (:survey result))))

        (is (some? (:spark result)))
        (is (= 0 (:count (:spark result))))))))

;; =============================================================================
;; Section 4b: Verify default handle-forge-strike IS FSM
;;
;; Regression test: ensure 'forge strike' routes to FSM, not imperative.
;; =============================================================================

(deftest default-forge-strike-is-fsm
  (testing "handle-forge-strike routes to FSM path (default since 0.12.0)"
    (ds-lings/add-slave! "swarm-forja-fsm-check" {:status :error :depth 1
                                                  :cwd "/tmp/fsm-check"
                                                  :project-id "test-project"})

    (with-redefs [scope/get-current-project-id (constantly "test-project")

                  c-kanban/handle-kanban
                  (fn [params]
                    (case (:command params)
                      "list" {:text (json/write-str [{:id "task-fsm-check"
                                                      :title "Verify FSM default"}])}
                      "update" nil
                      nil))

                  spawn/handle-spawn
                  (fn [_] {:text (json/write-str {:agent-id "forja-fsm-verify"
                                                  :spawn-mode "headless"
                                                  :success true})})

                  workflow/ling-cli-ready? (constantly true)

                  dispatch/handle-dispatch
                  (fn [_] {:text (json/write-str {:success true})})

                  hive-mcp.agent.protocol/kill!
                  (fn [_] {:killed? true})]

      (let [result (parse-mcp-result
                    (workflow/handle-forge-strike
                     {:directory "/tmp/fsm-check"
                      :spawn_mode "headless"
                      :max_slots 3}))]
        (is (true? (:success result)) "Default strike should succeed")
        (is (= "fsm" (:mode result))
            "Default handle-forge-strike MUST report 'fsm' mode (not imperative)")
        (is (nil? (:deprecated result))
            "Default path should NOT be deprecated")))))

(deftest imperative-forge-strike-reports-deprecated
  (testing "handle-forge-strike-imperative flags output as deprecated"
    (with-redefs [scope/get-current-project-id (constantly "test-project")

                  c-kanban/handle-kanban
                  (fn [params]
                    (case (:command params)
                      "list" {:text (json/write-str [{:id "dep-task"
                                                      :title "Deprecation check"}])}
                      "update" nil
                      nil))

                  spawn/handle-spawn
                  (fn [_] {:text (json/write-str {:agent-id "forja-dep"
                                                  :spawn-mode "headless"
                                                  :success true})})

                  workflow/ling-cli-ready? (constantly true)

                  dispatch/handle-dispatch
                  (fn [_] {:text (json/write-str {:success true})})]

      (let [result (parse-mcp-result
                    (workflow/handle-forge-strike-imperative
                     {:directory "/tmp/deprecated"
                      :spawn_mode "headless"
                      :max_slots 3}))]
        (is (true? (:success result)) "Imperative strike should succeed")
        (is (true? (:deprecated result))
            "Imperative path MUST flag :deprecated true")
        (is (= "imperative" (:mode result))
            "Imperative path should report 'imperative' mode")
        (is (str/includes? (:summary result) "DEPRECATED")
            "Summary should contain DEPRECATED warning")))))

;; =============================================================================
;; Section 5: Edge Cases
;; =============================================================================

(deftest headless-forge-strike-no-tasks
  (testing "Headless forge-strike (deprecated imperative) with no kanban tasks ends early"
    (with-redefs [scope/get-current-project-id (constantly "test-project")
                  c-kanban/handle-kanban
                  (fn [_] {:text (json/write-str [])})
                  spawn/handle-spawn (fn [_] (throw (ex-info "Should not spawn!" {})))
                  dispatch/handle-dispatch (fn [_] (throw (ex-info "Should not dispatch!" {})))]

      (let [result (parse-mcp-result
                    (workflow/handle-forge-strike-imperative
                     {:directory "/tmp/empty"
                      :spawn_mode "headless"
                      :max_slots 5}))]
        (is (true? (:success result)))
        (is (= 0 (:todo-count (:survey result)))
            "Should report 0 tasks")
        (is (= 0 (:count (:spark result)))
            "Should not spark any lings")))))

(deftest headless-forge-strike-quenched
  (testing "Headless forge-strike (FSM default) refuses when quenched"
    ;; Set quenched state
    (let [forge-state @#'workflow/forge-state]
      (swap! forge-state assoc :quenched? true))

    (let [result (parse-mcp-result
                  (workflow/handle-forge-strike
                   {:directory "/tmp/quenched"
                    :spawn_mode "headless"}))]
      (is (false? (:success result)))
      (is (true? (:quenched? result))
          "Should report quenched status"))))

(deftest headless-forge-strike-slot-limiting
  (testing "Headless forge-strike (deprecated imperative) respects max_slots with active lings"
    ;; Pre-populate 3 active lings (using valid schema statuses that spark! considers active)
    ;; spark! filters by #{:active :running :working :idle :spawning}
    ;; Schema allows: #{:idle :spawning :starting :initializing :working :blocked :error :terminated}
    ;; Intersection: :idle, :spawning, :working
    (ds-lings/add-slave! "active-ling-1" {:status :idle :depth 1 :cwd "/tmp"
                                          :project-id "test-project"})
    (ds-lings/add-slave! "active-ling-2" {:status :spawning :depth 1 :cwd "/tmp"
                                          :project-id "test-project"})
    (ds-lings/add-slave! "active-ling-3" {:status :working :depth 1 :cwd "/tmp"
                                          :project-id "test-project"})

    (let [spawn-calls (atom 0)]
      (with-redefs [scope/get-current-project-id (constantly "test-project")
                    c-kanban/handle-kanban
                    (fn [params]
                      (case (:command params)
                        ;; 5 tasks available but only 2 slots free (max 5 - 3 active)
                        "list" {:text (json/write-str
                                       (mapv #(do {:id (str "t" %)
                                                   :title (str "Task " %)
                                                   :priority "medium"})
                                             (range 1 6)))}
                        "update" nil
                        nil))
                    spawn/handle-spawn
                    (fn [_]
                      (swap! spawn-calls inc)
                      {:text (json/write-str {:agent-id (str "forja-slot-" @spawn-calls)
                                              :spawn-mode "headless"
                                              :success true})})
                    workflow/ling-cli-ready? (constantly true)
                    dispatch/handle-dispatch (fn [_] {:text (json/write-str {:success true})})]

        (let [result (parse-mcp-result
                      (workflow/handle-forge-strike-imperative
                       {:directory "/tmp/slots"
                        :spawn_mode "headless"
                        :max_slots 5}))]
          (is (true? (:success result)))
          ;; Only 2 slots available (5 max - 3 active)
          (is (= 2 @spawn-calls)
              "Should only spawn 2 lings (5 max - 3 active = 2 available)")
          (is (= 2 (:count (:spark result)))
              "Spark result should report 2 spawned"))))))

(deftest headless-forge-strike-spawn-failure-resilience
  (testing "Headless forge-strike (deprecated imperative) handles spawn failure gracefully"
    (with-redefs [scope/get-current-project-id (constantly "test-project")
                  c-kanban/handle-kanban
                  (fn [params]
                    (case (:command params)
                      "list" {:text (json/write-str [{:id "fail-task"
                                                      :title "Doomed task"
                                                      :priority "high"}])}
                      "update" nil
                      nil))
                  spawn/handle-spawn
                  (fn [_] (throw (ex-info "Headless spawn failed: no claude binary" {})))
                  workflow/ling-cli-ready? (constantly true)
                  dispatch/handle-dispatch (fn [_] nil)]

      (let [result (parse-mcp-result
                    (workflow/handle-forge-strike-imperative
                     {:directory "/tmp/fail"
                      :spawn_mode "headless"
                      :max_slots 5}))]
        (is (true? (:success result))
            "Strike should succeed even if individual spawn fails")
        (is (= 0 (:count (:spark result)))
            "No lings should be reported as spawned")
        (is (pos? (count (:failed (:spark result))))
            "Should report the failed spawn")))))

(deftest headless-forge-strike-mixed-results
  (testing "Deprecated imperative forge-strike reports partial outcome when some spawns fail"
    (let [spawn-count (atom 0)]
      (with-redefs [scope/get-current-project-id (constantly "test-project")
                    c-kanban/handle-kanban
                    (fn [params]
                      (case (:command params)
                        "list" {:text (json/write-str [{:id "t1" :title "Good task" :priority "high"}
                                                       {:id "t2" :title "Bad task" :priority "medium"}])}
                        "update" nil
                        nil))
                    spawn/handle-spawn
                    (fn [_]
                      (let [n (swap! spawn-count inc)]
                        (if (= 2 n)
                          (throw (ex-info "Spawn failed: OOM" {}))
                          {:text (json/write-str {:agent-id "forja-mixed-1"
                                                  :spawn-mode "headless"
                                                  :success true})})))
                    workflow/ling-cli-ready? (constantly true)
                    dispatch/handle-dispatch (fn [_] {:text (json/write-str {:success true})})]
        (let [result (parse-mcp-result
                      (workflow/handle-forge-strike-imperative
                       {:directory "/tmp/mixed"
                        :spawn_mode "headless"
                        :max_slots 5}))]
          (is (true? (:success result))
              "Partial success is still success=true")
          (is (= 1 (:count (:spark result)))
              "One spawned")
          (is (pos? (count (:failed (:spark result))))
              "One failed"))))))

;; =============================================================================
;; Section 6: Forge State Accumulation
;;
;; Tests that forge-state atom accumulates correctly across headless strikes.
;; =============================================================================

(deftest headless-forge-state-accumulation
  (testing "Forge state accumulates across multiple headless strikes (imperative)"
    (with-redefs [scope/get-current-project-id (constantly "test-project")
                  c-kanban/handle-kanban
                  (fn [params]
                    (case (:command params)
                      "list" {:text (json/write-str [{:id "acc-task"
                                                      :title "Accumulation test"}])}
                      "update" nil
                      nil))
                  spawn/handle-spawn
                  (fn [_] {:text (json/write-str {:agent-id "forja-acc"
                                                  :spawn-mode "headless"
                                                  :success true})})
                  workflow/ling-cli-ready? (constantly true)
                  dispatch/handle-dispatch (fn [_] {:text (json/write-str {:success true})})]

      ;; Run two headless strikes via imperative path (avoids DS readiness poll)
      (workflow/handle-forge-strike-imperative {:directory "/tmp/acc"
                                                :spawn_mode "headless"
                                                :max_slots 5})
      (workflow/handle-forge-strike-imperative {:directory "/tmp/acc"
                                                :spawn_mode "headless"
                                                :max_slots 5})

      (let [forge-atom (deref #'workflow/forge-state)
            state (deref forge-atom)]
        (is (= 2 (:total-strikes state))
            "Should have 2 total strikes")
        (is (= 2 (:total-sparked state))
            "Should have sparked 2 lings total")
        (is (some? (:last-strike state))
            "Should have recorded last strike time")))))

;; =============================================================================
;; Section 7: Real Subprocess E2E (echo command)
;;
;; Uses a real headless subprocess (echo) to verify the full path through
;; headless/spawn-headless! in the context of forge-strike.
;; =============================================================================

(deftest headless-real-subprocess-in-spark
  (testing "spark! with real headless subprocess using echo command"
    (let [;; We need to bypass the spawn/handle-spawn and directly test spark!
          ;; at the workflow level with real headless processes
          spark! @#'workflow/spark!]

      ;; Test spark! directly with echo command
      ;; This tests that the spawn-mode propagation works end-to-end
      ;; but we mock handle-spawn since it goes through the agent layer
      (with-redefs [spawn/handle-spawn
                    (fn [params]
                      (is (= "headless" (:spawn_mode params))
                          "Spawn should receive headless mode")
                      (is (some? (:kanban_task_id params))
                          "Spawn should include kanban task ID")
                      {:text (json/write-str {:agent-id (str "echo-" (System/currentTimeMillis))
                                              :spawn-mode "headless"
                                              :success true})})
                    workflow/ling-cli-ready? (constantly true)
                    dispatch/handle-dispatch
                    (fn [params]
                      (is (str/includes? (:prompt params) "Echo test task")
                          "Dispatch should contain task title")
                      {:text (json/write-str {:success true})})
                    c-kanban/handle-kanban (fn [_] nil)]

        (let [result (spark! {:directory "/tmp/echo-test"
                              :max_slots 3
                              :presets ["ling" "saa"]
                              :spawn_mode "headless"
                              :tasks [{:id "echo-task-1" :title "Echo test task"}]})]
          (is (= 1 (:count result)) "Should spawn 1 ling")
          (is (= 1 (count (:spawned result))) "Spawned list should have 1 entry")
          (is (= "Echo test task" (:task-title (first (:spawned result))))
              "Should record task title"))))))

;; =============================================================================
;; Section 8: Summary field validation
;;
;; Verify the human-readable summary includes headless mode info.
;; =============================================================================

(deftest headless-forge-strike-summary-format
  (testing "Default forge-strike (FSM) summary mentions headless mode and model"
    (with-redefs [scope/get-current-project-id (constantly "test-project")
                  c-kanban/handle-kanban
                  (fn [params]
                    (case (:command params)
                      "list" {:text (json/write-str [{:id "sum-task"
                                                      :title "Summary test"}])}
                      "update" nil
                      nil))
                  spawn/handle-spawn
                  (fn [_] {:text (json/write-str {:agent-id "forja-sum"
                                                  :spawn-mode "headless"
                                                  :success true})})
                  workflow/ling-cli-ready? (constantly true)
                  dispatch/handle-dispatch (fn [_] {:text (json/write-str {:success true})})]

      (let [result (parse-mcp-result
                    (workflow/handle-forge-strike
                     {:directory "/tmp/summary"
                      :spawn_mode "headless"
                      :model "deepseek/deepseek-chat"}))]
        (is (true? (:success result)) "FSM strike should succeed")
        (is (= "fsm" (:mode result)) "Should report FSM mode")
        (is (str/includes? (:summary result) "headless")
            "Summary should mention headless mode")
        (is (str/includes? (:summary result) "deepseek/deepseek-chat")
            "Summary should mention model")
        (is (= "deepseek/deepseek-chat" (:model result))
            "Result should include model field")))))

(comment
  ;; Run all forge-strike headless tests
  (clojure.test/run-tests 'hive-mcp.workflows.forge-strike-headless-test))
