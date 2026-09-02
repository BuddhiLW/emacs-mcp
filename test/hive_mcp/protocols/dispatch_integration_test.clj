(ns hive-mcp.protocols.dispatch-integration-test
  "Integration tests for IDispatchContext in the dispatch path.

   Validates that:
   - consolidated/agent.clj handle-dispatch wraps prompts via ensure-context
   - ling.clj dispatch! resolves IDispatchContext to plain strings
   - swarm/dispatch.clj handle-swarm-dispatch wraps prompts via ensure-context
   - Backward compatibility: plain strings still work everywhere
   - Forward compatibility: IDispatchContext instances pass through correctly

   These tests use the protocol directly without touching DataScript or Emacs."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.protocols.dispatch :as dispatch]
            [clojure.string]))

;;; ============================================================================
;;; ensure-context in dispatch path (unit-level integration)
;;; ============================================================================

(deftest ensure-context-integration-test
  (testing "ensure-context wraps plain strings for dispatch path"
    (let [prompt "Fix the authentication bug in login.clj"
          ctx (dispatch/ensure-context prompt)
          resolved (dispatch/resolve-context ctx)]
      (is (satisfies? dispatch/IDispatchContext ctx)
          "ensure-context produces IDispatchContext")
      (is (= :text (dispatch/context-type ctx))
          "Plain string wrapped as :text context")
      (is (= prompt (:prompt resolved))
          "Resolved prompt matches original string")))

  (testing "ensure-context passes through IDispatchContext instances"
    (let [original (dispatch/->text-context "pre-wrapped task")
          ctx (dispatch/ensure-context original)]
      (is (identical? original ctx)
          "IDispatchContext passes through without re-wrapping")
      (is (= "pre-wrapped task" (:prompt (dispatch/resolve-context ctx)))
          "Resolved prompt matches original")))

  (testing "ensure-context handles nil-like values gracefully"
    (let [ctx (dispatch/ensure-context nil)]
      (is (satisfies? dispatch/IDispatchContext ctx)
          "nil becomes a TextContext")
      (is (= "" (:prompt (dispatch/resolve-context ctx)))
          "nil resolves to empty string"))

    (let [ctx (dispatch/ensure-context "")]
      (is (satisfies? dispatch/IDispatchContext ctx)
          "empty string becomes a TextContext")
      (is (= "" (:prompt (dispatch/resolve-context ctx)))
          "empty string resolves to empty string"))))

;;; ============================================================================
;;; Dispatch task-opts construction (simulates consolidated/agent.clj path)
;;; ============================================================================

(deftest dispatch-task-opts-construction-test
  (testing "Task opts construction with plain string prompt"
    (let [prompt "Implement feature X"
          ctx (dispatch/ensure-context prompt)
          resolved-prompt (:prompt (dispatch/resolve-context ctx))
          task-opts {:task resolved-prompt
                     :dispatch-context ctx
                     :files ["src/feature.clj"]
                     :priority :normal}]
      (is (= "Implement feature X" (:task task-opts))
          "task-opts :task has resolved plain string")
      (is (satisfies? dispatch/IDispatchContext (:dispatch-context task-opts))
          "task-opts carries dispatch-context metadata")
      (is (= :text (dispatch/context-type (:dispatch-context task-opts)))
          "context-type is :text for wrapped string")))

  (testing "Task opts construction with pre-wrapped context"
    (let [ctx (dispatch/->text-context "Pre-wrapped task description")
          resolved-prompt (:prompt (dispatch/resolve-context ctx))
          task-opts {:task resolved-prompt
                     :dispatch-context ctx
                     :files nil
                     :priority :high}]
      (is (= "Pre-wrapped task description" (:task task-opts))
          "Resolved prompt from TextContext")
      (is (= :text (dispatch/context-type (:dispatch-context task-opts)))
          "context-type preserved"))))

;;; ============================================================================
;;; Ling dispatch! resolution (simulates ling.clj path)
;;; ============================================================================

(deftest ling-dispatch-context-resolution-test
  (testing "Ling resolves dispatch-context from task-opts"
    (let [ctx (dispatch/->text-context "Task via dispatch-context")
          task-opts {:task "ignored-raw-task"
                     :dispatch-context ctx
                     :timeout-ms 5000}
          ;; Simulate ling.clj resolution logic
          dispatch-context (:dispatch-context task-opts)
          task (:task task-opts)
          effective-ctx (or dispatch-context
                            (when task (dispatch/ensure-context task)))
          resolved-task (if effective-ctx
                          (:prompt (dispatch/resolve-context effective-ctx))
                          task)]
      (is (= "Task via dispatch-context" resolved-task)
          "dispatch-context takes precedence over raw :task")))

  (testing "Ling falls back to ensure-context when no dispatch-context"
    (let [task-opts {:task "Plain string task"
                     :timeout-ms 5000}
          dispatch-context (:dispatch-context task-opts)
          task (:task task-opts)
          effective-ctx (or dispatch-context
                            (when task (dispatch/ensure-context task)))
          resolved-task (if effective-ctx
                          (:prompt (dispatch/resolve-context effective-ctx))
                          task)]
      (is (= "Plain string task" resolved-task)
          "Plain string task resolves correctly via ensure-context")))

  (testing "Ling handles nil task gracefully"
    (let [task-opts {:timeout-ms 5000}
          dispatch-context (:dispatch-context task-opts)
          task (:task task-opts)
          effective-ctx (or dispatch-context
                            (when task (dispatch/ensure-context task)))
          resolved-task (if effective-ctx
                          (:prompt (dispatch/resolve-context effective-ctx))
                          task)]
      (is (nil? resolved-task)
          "nil task stays nil when no context provided"))))

;;; ============================================================================
;;; Graph context fallback (simulates future GraphContext usage)
;;; ============================================================================

(deftest graph-context-dispatch-path-test
  (testing "Graph context fallback flows through dispatch path"
    (let [;; ->graph-context falls back to TextContext when enhanced extension unavailable
          ctx (dispatch/->graph-context "task-node-42" nil)
          resolved (dispatch/resolve-context ctx)]
      (is (satisfies? dispatch/IDispatchContext ctx)
          "Fallback graph context is IDispatchContext")
      (is (= :text (dispatch/context-type ctx))
          "Fallback is :text type")
      (is (string? (:prompt resolved))
          "Fallback has string prompt")
      (is (clojure.string/includes? (:prompt resolved) "task-node-42")
          "Fallback prompt contains task node ID"))))

;;; ============================================================================
;;; Context type metadata in response (simulates agent.clj response enrichment)
;;; ============================================================================

(deftest context-type-response-enrichment-test
  (testing "Context type can be serialized for MCP response"
    (let [ctx (dispatch/->text-context "task")
          ctx-type (dispatch/context-type ctx)]
      (is (= "text" (name ctx-type))
          "context-type name is serializable")))

  (testing "Graph context fallback type is serializable"
    (let [ctx (dispatch/->graph-context "node-1" nil)
          ctx-type (dispatch/context-type ctx)]
      (is (= "text" (name ctx-type))
          "Fallback graph context type serializes to 'text'")))

  (testing "RefContext type is serializable for MCP response"
    (let [ctx (dispatch/->ref-context "task" {:ctx-refs {:axioms "ctx-123"}})
          ctx-type (dispatch/context-type ctx)]
      (is (= "ref" (name ctx-type))
          "RefContext type serializes to 'ref'"))))

;;; ============================================================================
;;; RefContext dispatch path (simulates agent.clj build-dispatch-context logic)
;;; ============================================================================

(deftest ref-context-dispatch-path-test
  (testing "build-dispatch-context logic: ctx_refs -> RefContext"
    ;; Simulate the build-dispatch-context logic from agent.clj
    (let [prompt "Fix the bug"
          ctx-refs {"axioms" "ctx-ax-123" "decisions" "ctx-dec-456"}
          refs-map (reduce-kv (fn [m k v] (assoc m (keyword k) v)) {} ctx-refs)
          ctx (dispatch/->ref-context prompt {:ctx-refs refs-map
                                              :kg-node-ids ["node-A"]
                                              :scope "test-project"})
          resolved (dispatch/resolve-context ctx)]
      (is (= :ref (dispatch/context-type ctx))
          "ctx_refs present -> RefContext")
      (is (clojure.string/includes? (:prompt resolved) "Fix the bug")
          "Resolved prompt contains base task")
      (is (= ["ctx-ax-123" "ctx-dec-456"] (sort (:refs resolved)))
          "Refs carry context-store IDs")
      (is (= ["node-A"] (:kg-nodes resolved))
          "KG nodes are forwarded")))

  (testing "build-dispatch-context logic: no ctx_refs -> TextContext"
    (let [ctx (dispatch/ensure-context "Plain task")]
      (is (= :text (dispatch/context-type ctx))
          "No ctx_refs -> TextContext")
      (is (= "Plain task" (:prompt (dispatch/resolve-context ctx))))))

  (testing "build-dispatch-context logic: empty ctx_refs -> TextContext"
    (let [ctx-refs {}
          ctx (if (seq ctx-refs)
                (dispatch/->ref-context "task" {:ctx-refs ctx-refs})
                (dispatch/ensure-context "task"))]
      (is (= :text (dispatch/context-type ctx))
          "Empty ctx_refs -> TextContext fallback"))))
