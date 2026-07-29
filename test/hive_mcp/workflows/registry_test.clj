(ns hive-mcp.workflows.registry-test
  "Tests for the EDN workflow registry.

   Test categories:
   1. Scanning — read .edn specs from classpath
   2. Handler registration — associate handler-maps with workflows
   3. Compilation — compile specs with handler-maps via fsm/compile
   4. Lookup — retrieve compiled FSMs by name
   5. Lifecycle — init!, reload!, reset-registry!
   6. Forge-belt integration — first built-in workflow round-trip

   SOLID-D: Tests depend on registry abstractions, not file I/O.
   CLARITY-T: Each test category isolated."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.workflows.registry :as reg]
            [hive.events.fsm :as fsm]
            [hive-mcp.test.stub.forge-belt :as stub-fb]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn clean-registry-fixture
  "Reset registry before each test for isolation."
  [f]
  (reg/reset-registry!)
  (f)
  (reg/reset-registry!))

(use-fixtures :each clean-registry-fixture stub-fb/forge-belt-fixture)

;; =============================================================================
;; Mock FSM Spec (minimal valid spec for testing)
;; =============================================================================

(def mock-handler-map
  "Minimal handler-map for testing compilation."
  {:start (fn [_resources data]
            (assoc data :started true))
   :process (fn [_resources data]
              (assoc data :processed true))
   :end (fn [_resources {:keys [data]}]
          (select-keys data [:started :processed]))
   :error (fn [_resources fsm]
            (throw (ex-info "test error" {:data (:data fsm)})))})

;; =============================================================================
;; 1. Scanning Tests
;; =============================================================================

(deftest test-scan-fsm-specs
  (testing "scan-fsm-specs finds .edn files in resources/fsm/"
    (let [specs (reg/scan-fsm-specs)]
      (is (map? specs) "Returns a map")
      (is (contains? specs :forge-belt) "Found forge-belt.edn")
      (is (map? (get-in specs [:forge-belt :spec])) "Spec is a parsed map")
      (is (contains? (get-in specs [:forge-belt :spec]) :fsm) "Spec has :fsm key"))))

(deftest test-scan-spec-structure
  (testing "Scanned forge-belt spec has expected structure"
    (let [specs (reg/scan-fsm-specs)
          fb-spec (get-in specs [:forge-belt :spec])]
      (is (map? (:fsm fb-spec)) "Has FSM state map")
      (is (map? (:opts fb-spec)) "Has opts map")
      ;; Check handler keywords exist (not resolved yet)
      (is (keyword? (get-in fb-spec [:fsm :hive.events.fsm/start :handler]))
          "Start handler is a keyword (not yet resolved)"))))

;; =============================================================================
;; 2. Handler Registration Tests
;; =============================================================================

(deftest test-register-handlers-success
  (testing "register-handlers! associates handler-map with existing workflow"
    ;; First scan to populate registry
    (let [specs (reg/scan-fsm-specs)]
      (reset! @#'reg/registry specs)
      (reg/register-handlers! :forge-belt {:start identity :smite identity})
      (let [wfs (reg/list-workflows)]
        (is (true? (get-in wfs [:forge-belt :has-handlers?]))
            "Workflow now has handlers")))))

(deftest test-register-handlers-missing-workflow
  (testing "register-handlers! warns when workflow not found"
    ;; Registry is empty — should not throw
    (reg/register-handlers! :nonexistent {:start identity})
    (is (nil? (reg/get-workflow :nonexistent))
        "No workflow created for nonexistent name")))

;; =============================================================================
;; 3. Compilation Tests
;; =============================================================================

(deftest test-compile-registry
  (testing "compile-registry! compiles workflows with both spec and handlers"
    (let [;; Create a minimal in-memory spec (simulates scan result)
          mock-spec {:fsm {:hive.events.fsm/start
                           {:handler    :start
                            :dispatches [[:test/process '(fn [_] true)]]}
                           :test/process
                           {:handler    :process
                            :dispatches [[:hive.events.fsm/end '(fn [_] true)]]}
                           :hive.events.fsm/end
                           {:handler :end}
                           :hive.events.fsm/error
                           {:handler :error}}}]
      ;; Manually populate registry
      (reset! @#'reg/registry {:test-wf {:spec mock-spec}})
      (reg/register-handlers! :test-wf mock-handler-map)
      (reg/compile-registry!)
      (let [compiled (reg/get-workflow :test-wf)]
        (is (some? compiled) "Compiled workflow returned")
        (is (map? compiled) "Compiled result is a map")
        (is (contains? compiled :fsm) "Has :fsm key")))))

(deftest test-compile-skips-without-handlers
  (testing "compile-registry! skips workflows without handler-maps"
    (reset! @#'reg/registry {:orphan {:spec {:fsm {}}}})
    (reg/compile-registry!)
    (is (nil? (reg/get-workflow :orphan))
        "Unhandled workflow not compiled")))

;; =============================================================================
;; 4. Lookup Tests
;; =============================================================================

(deftest test-get-workflow-nil-when-missing
  (testing "get-workflow returns nil for unknown workflows"
    (is (nil? (reg/get-workflow :nonexistent)))))

(deftest test-get-spec
  (testing "get-spec returns raw EDN spec"
    (let [specs (reg/scan-fsm-specs)]
      (reset! @#'reg/registry specs)
      (let [spec (reg/get-spec :forge-belt)]
        (is (some? spec) "Spec found")
        (is (contains? spec :fsm) "Has :fsm key")))))

(deftest test-list-workflows
  (testing "list-workflows returns status for all registered workflows"
    (reset! @#'reg/registry {:wf-a {:spec {:fsm {}} :handler-map {:x identity}}
                             :wf-b {:spec {:fsm {}}}})
    (let [listing (reg/list-workflows)]
      (is (= 2 (count listing)) "Two workflows listed")
      (is (true? (get-in listing [:wf-a :has-handlers?])))
      (is (false? (get-in listing [:wf-b :has-handlers?])))
      (is (false? (get-in listing [:wf-a :compiled?]))
          "Not yet compiled"))))

;; =============================================================================
;; 5. Lifecycle Tests
;; =============================================================================

(deftest test-reset-registry
  (testing "reset-registry! clears all state"
    (reset! @#'reg/registry {:something {:spec {:fsm {}}}})
    (reg/reset-registry!)
    (is (empty? (reg/list-workflows)) "Registry is empty after reset")))

(deftest test-reload-preserves-handlers
  (testing "reload! re-scans specs but preserves handler registrations"
    ;; Setup: scan + register handlers
    (reset! @#'reg/registry (reg/scan-fsm-specs))
    (reg/register-handlers! :forge-belt {:start identity :smite identity})
    ;; Verify handlers registered
    (is (true? (get-in (reg/list-workflows) [:forge-belt :has-handlers?])))
    ;; Reload — should preserve handlers
    (reg/reload!)
    (is (true? (get-in (reg/list-workflows) [:forge-belt :has-handlers?]))
        "Handlers preserved after reload")))

;; =============================================================================
;; 6. Forge Belt Integration Tests
;; =============================================================================

(deftest test-forge-belt-full-roundtrip
  (testing "init! scans, registers forge-belt, compiles, and produces runnable FSM"
    (let [result (reg/init!)]
      ;; init! returns the list-workflows output
      (is (map? result) "Returns workflow status map")
      (is (true? (get-in result [:forge-belt :compiled?]))
          "Forge-belt compiled successfully")
      ;; Now verify the compiled FSM is usable
      (let [compiled (reg/get-workflow :forge-belt)]
        (is (some? compiled) "Compiled FSM available")
        (is (map? (:fsm compiled)) "Has FSM state graph")
        (is (map? (:opts compiled)) "Has opts")))))

(deftest test-forge-belt-compiled-fsm-runs
  (testing "Compiled forge-belt FSM from registry runs correctly with mock resources"
    (reg/init!)
    (let [compiled (reg/get-workflow :forge-belt)
          ;; Mock resources matching forge-belt contract
          resources {:ds-conn    nil
                     :directory  "/tmp/test"
                     :config     {:max-slots 5 :presets ["ling"]}
                     :agent-ops  {:kill-fn      (fn [_dir _pid] {:smited [] :failed [] :count 0})
                                  :spawn-fn     (fn [_opts] {:spawned [] :failed [] :count 0})
                                  :dispatch-fn  (fn [_id _prompt] nil)
                                  :wait-ready-fn (fn [_id] {:ready? true})}
                     :kanban-ops {:list-fn   (fn [_dir] {:tasks [] :count 0})
                                  :update-fn (fn [_opts] nil)}
                     :scope-fn   (fn [_dir] "test-project")
                     :clock-fn   #(java.time.Instant/parse "2026-02-07T12:00:00Z")}
          ;; Run: should go start → smite → survey (no tasks) → end
          result (fsm/run compiled resources
                          {:data {:quenched? false
                                  :continuous? false
                                  :strike-count 0
                                  :total-smited 0
                                  :total-sparked 0}})]
      (is (map? result) "Returns result map")
      (is (= 0 (:total-sparked result)) "No lings sparked (no tasks)")
      (is (= 0 (:total-smited result)) "No lings smited"))))

(deftest test-forge-belt-registry-matches-direct
  (testing "Registry-compiled FSM produces same results as direct compilation"
    (reg/init!)
    (let [registry-compiled (reg/get-workflow :forge-belt)
          ;; Direct compilation from forge-belt ns
          direct-compiled (do (require 'hive-mcp.workflows.forge-belt)
                              ((resolve 'hive-mcp.workflows.forge-belt/compile-belt)))
          resources {:ds-conn    nil
                     :directory  "/tmp/test"
                     :config     {:max-slots 2 :presets ["ling"]}
                     :agent-ops  {:kill-fn      (fn [_dir _pid] {:smited [{:id "x"}] :failed [] :count 1})
                                  :spawn-fn     (fn [_opts] {:spawned [{:agent-id "f1"}] :failed [] :count 1})
                                  :dispatch-fn  (fn [_id _prompt] nil)
                                  :wait-ready-fn (fn [_id] {:ready? true})}
                     :kanban-ops {:list-fn   (fn [_dir] {:tasks [{:id "t1" :title "Task"}] :count 1})
                                  :update-fn (fn [_opts] nil)}
                     :scope-fn   (fn [_dir] "test-project")
                     :clock-fn   #(java.time.Instant/parse "2026-02-07T15:00:00Z")}
          init-data {:quenched? false :continuous? false
                     :strike-count 0 :total-smited 0 :total-sparked 0}
          ;; Run both
          registry-result (fsm/run registry-compiled resources {:data init-data})
          direct-result (fsm/run direct-compiled resources {:data init-data})]
      ;; Same strike counts
      (is (= (:strike-count registry-result) (:strike-count direct-result))
          "Same strike count")
      (is (= (:total-smited registry-result) (:total-smited direct-result))
          "Same total smited")
      (is (= (:total-sparked registry-result) (:total-sparked direct-result))
          "Same total sparked"))))
