(ns hive-mcp.protocols.registry-lifecycle-test
  "Characterization net for the per-protocol single-impl active-slot registries.

   Locks the observable contract (set -> get -> set? -> clear -> empty-policy +
   invalid-impl rejection) of every Shape-A registry BEFORE the registry
   abstraction refactor, and re-runs unchanged after it. Defense-in-depth
   against regression for the consolidation onto hive-mcp.protocols.registry.

   Empty-policy taxonomy under test:
     :throw   kg                              -> get throws ex-info
     :noop    editor, event-backbone,
              agent-bridge, workflow          -> get returns a Noop fallback
     :default spawn-store                     -> get returns a non-nil default

   Fixture snapshots+restores each global slot (tests-must-not-touch-shared-state
   axiom 20260629165653-461fcd11): even run cold these mutate process-global atoms."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.protocols.kg :as kg]
            [hive-mcp.protocols.editor :as editor]
            [hive-mcp.protocols.event-backbone :as backbone]
            [hive-mcp.protocols.agent-bridge :as ab]
            [hive-mcp.protocols.workflow :as wf]
            [hive-mcp.agent.ling.spawn-store :as ss]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Snapshot / restore fixture (no shared-state leakage)
;; =============================================================================

(defn- snapshot []
  {:kg       (when (kg/store-set?) (kg/get-store))
   :editor   (when (editor/editor-set?) (editor/get-editor))
   :backbone (when (backbone/backbone-set?) (backbone/get-backbone))
   :ab       (when (ab/agent-backend-set?) (ab/get-agent-backend))
   :wf       (when (wf/workflow-engine-set?) (wf/get-workflow-engine))
   :ss       (ss/get-store)})

(defn- restore! [{:keys [kg editor backbone ab wf ss]}]
  (if kg (kg/set-store! kg) (kg/clear-store!))
  (if editor (editor/set-editor! editor) (editor/clear-editor!))
  (if backbone (backbone/set-backbone! backbone) (backbone/clear-backbone!))
  (if ab (ab/set-agent-backend! ab) (ab/clear-agent-backend!))
  (if wf (wf/set-workflow-engine! wf) (wf/clear-workflow-engine!))
  (when ss (ss/set-store! ss)))

(use-fixtures :each
  (fn [f]
    (let [prior (snapshot)]
      (try (f) (finally (restore! prior))))))

;; =============================================================================
;; :throw empty-policy  — kg
;; =============================================================================

(deftest kg-slot-lifecycle-test
  (testing "kg active-store: set -> get roundtrip, set?, clear -> throw"
    (let [store (kg/noop-store)]
      (kg/clear-store!)
      (is (false? (kg/store-set?)) "unset after clear")
      (is (identical? store (kg/set-store! store)) "set returns the impl")
      (is (true? (kg/store-set?)))
      (is (identical? store (kg/get-store)) "get returns the installed impl")
      (kg/clear-store!)
      (is (false? (kg/store-set?)))
      (is (thrown? clojure.lang.ExceptionInfo (kg/get-store))
          "empty-policy :throw — get throws ex-info when unset"))))

(deftest kg-slot-rejects-invalid-test
  (testing "set-store! rejects a non-IKGStore via :pre (AssertionError)"
    (is (thrown? AssertionError (kg/set-store! {:not "a store"})))))

;; =============================================================================
;; :noop empty-policy  — editor, event-backbone, agent-bridge, workflow
;; =============================================================================

(deftest editor-slot-lifecycle-test
  (testing "editor: empty -> Noop, set/get/clear, invalid rejected"
    (editor/clear-editor!)
    (is (false? (editor/editor-set?)))
    (is (= :noop (editor/editor-id (editor/get-editor))) "empty-policy :noop")
    (let [e (editor/noop-editor)]
      (is (identical? e (editor/set-editor! e)) "set returns the impl")
      (is (true? (editor/editor-set?)))
      (is (identical? e (editor/get-editor))))
    (editor/clear-editor!)
    (is (false? (editor/editor-set?)))
    (is (= :noop (editor/editor-id (editor/get-editor))))
    (is (thrown? AssertionError (editor/set-editor! {:not "editor"})))))

(deftest backbone-slot-lifecycle-test
  (testing "event-backbone: empty -> Noop, set/get/clear, invalid rejected"
    (backbone/clear-backbone!)
    (is (false? (backbone/backbone-set?)))
    (is (= :noop (backbone/backbone-id (backbone/get-backbone))) "empty-policy :noop")
    (let [b (backbone/noop-backbone)]
      (is (identical? b (backbone/set-backbone! b)) "set returns the impl")
      (is (true? (backbone/backbone-set?)))
      (is (identical? b (backbone/get-backbone))))
    (backbone/clear-backbone!)
    (is (false? (backbone/backbone-set?)))
    (is (= :noop (backbone/backbone-id (backbone/get-backbone))))
    (is (thrown? AssertionError (backbone/set-backbone! {:not "backbone"})))))

(deftest agent-backend-slot-lifecycle-test
  (testing "agent-bridge: empty -> Noop, set/get/clear, invalid rejected"
    (ab/clear-agent-backend!)
    (is (false? (ab/agent-backend-set?)))
    (is (= :noop (ab/backend-id (ab/get-agent-backend))) "empty-policy :noop")
    (let [impl (ab/->NoopAgentBackend)]
      (is (identical? impl (ab/set-agent-backend! impl)) "set returns the impl")
      (is (true? (ab/agent-backend-set?)))
      (is (identical? impl (ab/get-agent-backend))))
    (ab/clear-agent-backend!)
    (is (false? (ab/agent-backend-set?)))
    (is (= :noop (ab/backend-id (ab/get-agent-backend))))
    (is (thrown? AssertionError (ab/set-agent-backend! {:not "backend"})))))

(deftest workflow-slot-lifecycle-test
  (testing "workflow: empty -> Noop engine, set/get/clear, invalid rejected"
    (wf/clear-workflow-engine!)
    (is (false? (wf/workflow-engine-set?)))
    (is (some? (wf/get-workflow-engine)) "empty-policy :noop returns a non-nil engine")
    (is (false? (wf/enhanced?)) "unset engine is not enhanced")
    (let [eng (wf/->NoopWorkflowEngine)]
      (is (identical? eng (wf/set-workflow-engine! eng)) "set returns the impl")
      (is (true? (wf/workflow-engine-set?)))
      (is (identical? eng (wf/get-workflow-engine))))
    (wf/clear-workflow-engine!)
    (is (false? (wf/workflow-engine-set?)))
    (is (false? (wf/enhanced?)))
    (is (thrown? AssertionError (wf/set-workflow-engine! {:not "engine"})))))

;; =============================================================================
;; :default empty-policy  — spawn-store (non-nil default instance)
;; =============================================================================

(deftest spawn-store-slot-lifecycle-test
  (testing "spawn-store: non-nil default, set/get roundtrip, reset -> default"
    (let [default-class (class (ss/->DataScriptSpawnStore))]
      (ss/reset-store!)
      (is (instance? default-class (ss/get-store)) "empty-policy :default — non-nil")
      (let [custom (ss/->DataScriptSpawnStore)]
        (is (identical? custom (ss/set-store! custom)) "set returns the impl")
        (is (identical? custom (ss/get-store))))
      (ss/reset-store!)
      (is (instance? default-class (ss/get-store)) "reset restores a default instance"))))

(deftest spawn-store-rejects-invalid-test
  (testing "spawn-store set-store! rejects non-ISpawnStore via :pre"
    (is (thrown? AssertionError (ss/set-store! {:not "a spawn store"})))))
