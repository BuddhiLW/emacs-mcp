(ns hive-mcp.workflows.saa-workflow
  "SAA (Silence-Abstract-Act) workflow aggregate.

   Composes predicates (saa.predicates) and handlers (saa.handlers) into
   the FSM spec, handler/predicate maps, and compile/run API.

   This namespace is the public API — callers require only this ns.

   State graph:
   ```
   ::fsm/start --> ::catchup --> ::silence <--> ::silence-review
                                                    |
                                               ::abstract <--> ::validate-plan
                                                                    |
                                                               ::store-plan
                                                                /          \\
                                                    ::fsm/end          ::act-dispatch
                                                  (plan-only?)              |
                                                                       ::act-verify
                                                                            |
                                                                       ::fsm/end
   ```"

  (:require [hive.events.fsm :as fsm]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.workflows.saa.predicates :as pred]
            [hive-mcp.workflows.saa.handlers :as h]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Re-exports for backward compatibility (tests reference sut/predicate-name)
;; =============================================================================

(def has-required-fields?           pred/has-required-fields?)
(def has-startup-error?             pred/has-startup-error?)
(def context-loaded?                pred/context-loaded?)
(def context-not-loaded?            pred/context-not-loaded?)
(def has-observations?              pred/has-observations?)
(def has-error?                     pred/has-error?)
(def grounding-sufficient?          pred/grounding-sufficient?)
(def grounding-insufficient-retryable? pred/grounding-insufficient-retryable?)
(def grounding-max-iterations?      pred/grounding-max-iterations?)
(def has-plan?                      pred/has-plan?)
(def plan-valid?                    pred/plan-valid?)
(def plan-invalid-retryable?        pred/plan-invalid-retryable?)
(def plan-invalid-final?            pred/plan-invalid-final?)
(def plan-only?                     pred/plan-only?)
(def full-execution?                pred/full-execution?)
(def has-execution-result?          pred/has-execution-result?)
(def tests-passed?                  pred/tests-passed?)
(def tests-failed?                  pred/tests-failed?)
(def plan-nil-with-error?           pred/plan-nil-with-error?)
(def always                         pred/always)
(def noop-subscription              pred/noop-subscription)
(def trace-log-enter                pred/trace-log-enter)
(def trace-log-exit                 pred/trace-log-exit)

(def handle-start                   h/handle-start)
(def handle-catchup                 h/handle-catchup)
(def handle-silence                 h/handle-silence)
(def handle-silence-review          h/handle-silence-review)
(def handle-abstract                h/handle-abstract)
(def handle-validate-plan           h/handle-validate-plan)
(def handle-store-plan              h/handle-store-plan)
(def handle-act-dispatch            h/handle-act-dispatch)
(def handle-act-verify              h/handle-act-verify)
(def handle-end                     h/handle-end)
(def handle-error                   h/handle-error)


;; =============================================================================
;; Handler & Predicate Maps (for EDN spec keyword resolution)
;; =============================================================================

(def handler-map
  "Maps EDN keyword handlers to implementation functions."
  {:start          h/handle-start
   :catchup        h/handle-catchup
   :silence        h/handle-silence
   :silence-review h/handle-silence-review
   :abstract       h/handle-abstract
   :validate-plan  h/handle-validate-plan
   :store-plan     h/handle-store-plan
   :act-dispatch   h/handle-act-dispatch
   :act-verify     h/handle-act-verify
   :end            h/handle-end
   :error          h/handle-error})

(def predicate-map
  "Maps EDN keyword predicates to implementation functions."
  {:has-required-fields?              pred/has-required-fields?
   :has-startup-error?                pred/has-startup-error?
   :context-loaded?                   pred/context-loaded?
   :context-not-loaded?               pred/context-not-loaded?
   :has-observations?                 pred/has-observations?
   :has-error?                        pred/has-error?
   :grounding-sufficient?             pred/grounding-sufficient?
   :grounding-insufficient-retryable? pred/grounding-insufficient-retryable?
   :grounding-max-iterations?         pred/grounding-max-iterations?
   :has-plan?                         pred/has-plan?
   :plan-valid?                       pred/plan-valid?
   :plan-invalid-retryable?           pred/plan-invalid-retryable?
   :plan-invalid-final?               pred/plan-invalid-final?
   :plan-only?                        pred/plan-only?
   :full-execution?                   pred/full-execution?
   :has-execution-result?             pred/has-execution-result?
   :tests-passed?                     pred/tests-passed?
   :tests-failed?                     pred/tests-failed?
   :plan-nil-with-error?              pred/plan-nil-with-error?
   :always                            pred/always})

(def spec-ref-map
  "Keyword -> fn table for every NON-`:handler` reference the EDN spec uses:
   dispatch predicates, `:subscriptions` handlers and the `:pre`/`:post` hooks.

   Contract: any compiler of `resources/fsm/saa-workflow.edn` that is not
   `compile-saa` (e.g. `hive-mcp.workflows.registry`) must resolve those
   keyword references through THIS map before calling `fsm/compile`."
  (merge predicate-map
         {:noop-subscription pred/noop-subscription
          :trace-log-enter   pred/trace-log-enter
          :trace-log-exit    pred/trace-log-exit}))


;; =============================================================================
;; In-Code FSM Spec (inline functions, fallback for EDN)
;; =============================================================================

(def saa-workflow-spec
  "Inline FSM spec with direct function references. Fallback when EDN unavailable."
  {:fsm
   {::fsm/start
    {:handler    h/handle-start
     :dispatches [[::fsm/error pred/has-startup-error?]
                  [::catchup   pred/has-required-fields?]]}

    ::catchup
    {:handler    h/handle-catchup
     :dispatches [[::silence   pred/context-loaded?]
                  [::fsm/error pred/context-not-loaded?]]}

    ::silence
    {:handler    h/handle-silence
     :dispatches [[::fsm/error     pred/has-error?]
                  [::silence-review pred/has-observations?]]}

    ::silence-review
    {:handler    h/handle-silence-review
     :dispatches [[::abstract  pred/grounding-sufficient?]
                  [::silence   pred/grounding-insufficient-retryable?]
                  [::abstract  pred/grounding-max-iterations?]]}

    ::abstract
    {:handler    h/handle-abstract
     :dispatches [[::fsm/error     pred/plan-nil-with-error?]
                  [::validate-plan pred/has-plan?]]}

    ::validate-plan
    {:handler    h/handle-validate-plan
     :dispatches [[::store-plan pred/plan-valid?]
                  [::abstract   pred/plan-invalid-retryable?]
                  [::fsm/error  pred/plan-invalid-final?]]}

    ::store-plan
    {:handler    h/handle-store-plan
     :dispatches [[::fsm/end      pred/plan-only?]
                  [::act-dispatch pred/full-execution?]]}

    ::act-dispatch
    {:handler    h/handle-act-dispatch
     :dispatches [[::fsm/error  pred/has-error?]
                  [::act-verify pred/has-execution-result?]]}

    ::act-verify
    {:handler    h/handle-act-verify
     :dispatches [[::fsm/end   pred/tests-passed?]
                  [::fsm/error pred/tests-failed?]]}

    ::fsm/end
    {:handler h/handle-end}

    ::fsm/error
    {:handler h/handle-error}}

   :opts
   {:max-trace 100

    :subscriptions
    {[:grounding-score]    {:handler pred/noop-subscription}
     [:plan-valid?]        {:handler pred/noop-subscription}
     [:tests-passed?]      {:handler pred/noop-subscription}
     [:silence-iterations] {:handler pred/noop-subscription}}

    :pre  pred/trace-log-enter
    :post pred/trace-log-exit}})


;; =============================================================================
;; EDN Spec Resolution
;; =============================================================================

(defn- resolve-keyword
  "Resolve a keyword reference to a function via handler-map or spec-ref-map.
   Unknown keywords and non-keywords pass through unchanged."
  [k]
  (if (keyword? k)
    (or (get handler-map k)
        (get spec-ref-map k)
        k)
    k))

(defn- resolve-dispatches [dispatches]
  (mapv (fn [[state pred]] [state (resolve-keyword pred)]) dispatches))

(defn- resolve-spec
  "Walk an EDN spec and resolve all keyword references to functions."
  [spec]
  (-> spec
      (update :fsm
              (fn [states]
                (reduce-kv
                 (fn [m state-key state-def]
                   (assoc m state-key
                          (cond-> state-def
                            (:handler state-def)
                            (update :handler resolve-keyword)
                            (:dispatches state-def)
                            (update :dispatches resolve-dispatches))))
                 {} states)))
      (update-in [:opts :pre] resolve-keyword)
      (update-in [:opts :post] resolve-keyword)
      (update-in [:opts :subscriptions]
                 (fn [subs]
                   (when subs
                     (reduce-kv
                      (fn [m k v] (assoc m k (update v :handler resolve-keyword)))
                      {} subs))))))

(defn load-edn-spec
  "Load the SAA workflow spec from resources/fsm/saa-workflow.edn
   and resolve keyword references to handler/predicate functions."
  []
  (-> (io/resource "fsm/saa-workflow.edn")
      slurp
      edn/read-string
      resolve-spec))


;; =============================================================================
;; Compilation & Execution API
;; =============================================================================

(defn compile-saa
  "Compile the SAA workflow FSM spec. Call once, reuse the compiled FSM.
   Loads from resources/fsm/saa-workflow.edn. Falls back to inline spec."
  []
  (let [spec (or (rescue nil (load-edn-spec))
                 (do (log/warn "[saa-fsm] EDN spec load failed, using inline spec")
                     saa-workflow-spec))]
    (fsm/compile spec)))

(defn run-saa
  "Execute a compiled SAA workflow FSM.
   Args: compiled-fsm, resources (side-effect fns), opts (must include :task, :agent-id)"
  ([compiled-fsm resources]
   (run-saa compiled-fsm resources {}))
  ([compiled-fsm resources opts]
   (fsm/run compiled-fsm
            resources
            {:data (merge {:agent-id nil
                           :directory nil
                           :task nil
                           :plan-only? false
                           :grounding-threshold 0.6
                           :silence-iterations 0
                           :abstract-retries 0}
                          opts)})))

(defn run-full-saa
  "Convenience: compile and run a full SAA cycle."
  [resources opts]
  (run-saa (compile-saa) resources opts))

(defn run-plan-only
  "Convenience: compile and run SAA without Act phase (plan-only mode)."
  [resources opts]
  (run-saa (compile-saa) resources (assoc opts :plan-only? true)))
