(ns hive-mcp.workflows.sub-fsms.drone-loop
  "Parent drone-loop FSM — composable multi-turn agentic execution.

   Replaces the imperative recur loop in drone/loop.clj with a deterministic
   FSM that composes three sub-FSMs:
   1. context-gather — compressed context reconstruction
   2. llm-call       — message building + API call + response parsing
   3. tool-execution — permission check + execute + capture result

   This is the first hive-events FSM with an internal loop edge:
   ::check-done dispatches back to ::reconstruct-context for continuation.

   ## State Graph

   ::init -> ::reconstruct-context -> ::llm-call -> ::dispatch-response
     -> (tool_calls) ::execute-tools -> ::compress -> ::check-done
     -> (text/error) ::check-done
   ::check-done -> ::reconstruct-context (LOOP) | ::finalize -> ::fsm/end

   ## Data Flow

   Input (from parent/caller):
     {:task          string    ;; task description (required)
      :files         [string]  ;; target file paths
      :drone-id      string    ;; drone identifier (required)
      :max-turns     int       ;; max loop iterations (default 10)
      :model         string    ;; LLM model name
      :tools         [string]  ;; tool name allowlist (nil = all)
      :permissions   #{...}    ;; tool permission set
      :trace?        boolean   ;; emit progress events}

   Output:
     {:status         :completed|:max_steps|:error|:noop
      :result         string    ;; final text response
      :steps          [...]     ;; turn-by-turn history
      :tool_calls_made int
      :tokens         {:input :output :total}
      :turns          int
      :model          string
      :kg-stats       {:observations :reasoning :facts}}

   ## Resources (injected at run time)

     :backend          — LLMBackend instance (required)
     :tool-schemas     — vector of tool schema maps
     :session-store    — IKGStore for session store (optional)
     :agent-id         — agent ID for executor/tracking
     :execute-tools-fn — (fn [agent-id calls permissions] -> [result-msgs])
     :reconstruct-fn   — (fn [store task turn] -> context-string)

   Fire-and-forget effects (seed, emit, record) are declared as FX data
   by handlers and executed by registered FX handlers in
   hive-mcp.events.effects.drone-loop.

   ## Module Structure

   Handlers are in hive-mcp.workflows.sub-fsms.drone-loop-handlers.
   This namespace contains: predicates, FSM spec, compiled FSM, public API."

  (:require [hive.events.fsm :as fsm]
            [hive-mcp.workflows.sub-fsms.drone-loop-handlers :as handlers]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Re-exports (backward compatibility)
;; =============================================================================

(def ^:const default-max-turns
  "Default maximum agentic loop iterations."
  handlers/default-max-turns)

(def ^:const failure-threshold
  "Consecutive failures before forced termination."
  handlers/failure-threshold)

(def should-terminate?
  "Evaluate termination heuristics for the agentic loop.
   Delegated to handlers namespace. See handlers/should-terminate? for docs."
  handlers/should-terminate?)

;; =============================================================================
;; Dispatch Predicates
;; =============================================================================

(defn initialized?
  "Data has been initialized (turn counter exists)."
  [data]
  (contains? data :turn))

(defn has-context?
  "Context has been reconstructed."
  [data]
  (contains? data :context))

(defn llm-responded?
  "LLM response has been parsed."
  [data]
  (contains? data :response-type))

(defn has-tool-calls?
  "LLM response contains tool calls to execute."
  [data]
  (boolean (and (= :tool_calls (:response-type data))
                (seq (:tool-calls data)))))

(defn no-tool-calls?
  "LLM response is text or error (no tools to execute)."
  [data]
  (or (= :text (:response-type data))
      (= :error (:response-type data))
      (and (= :tool_calls (:response-type data))
           (empty? (:tool-calls data)))))

(defn tools-executed?
  "Tool execution phase is complete."
  [data]
  (contains? data :tool-results))

(defn compressed?
  "Compression phase is complete (turn incremented, step recorded)."
  [data]
  (and (contains? data :obs-count)
       (pos? (count (:steps data [])))))

(defn done?
  "Termination heuristics say we should stop."
  [data]
  (get-in data [:termination :terminate?] false))

(defn continue?
  "Termination heuristics say we should continue (loop edge)."
  [data]
  (not (done? data)))

(defn finalized?
  "FSM result has been built."
  [data]
  (contains? data :fsm-result))

;; =============================================================================
;; FSM Spec
;; =============================================================================

(def drone-loop-spec
  "Parent drone-loop FSM specification.

   First hive-events FSM with an internal loop edge:
   ::check-done -> ::reconstruct-context (when continue?)

   States:
   1. ::init                 — Setup session, seed KG, zero counters
   2. ::reconstruct-context  — Rebuild compressed context from session store
   3. ::llm-call             — Call LLM via llm-call sub-FSM
   4. ::dispatch-response    — Route based on response type
   5. ::execute-tools        — Execute tool calls
   6. ::compress       — Record observations in session store
   7. ::check-done           — Evaluate termination heuristics
   8. ::finalize             — Build result, cleanup"
  {:fsm {::fsm/start
         {:handler    handlers/handle-init
          :dispatches [[::reconstruct-context initialized?]
                       [::fsm/error (constantly true)]]}

         ::reconstruct-context
         {:handler    handlers/handle-reconstruct-context
          :dispatches [[::llm-call has-context?]
                       [::fsm/error (constantly true)]]}

         ::llm-call
         {:handler    handlers/handle-llm-call
          :dispatches [[::dispatch-response llm-responded?]
                       [::fsm/error (constantly true)]]}

         ::dispatch-response
         {:handler    handlers/handle-dispatch-response
          :dispatches [[::execute-tools has-tool-calls?]
                       [::check-done no-tool-calls?]
                       [::fsm/error (constantly true)]]}

         ::execute-tools
         {:handler    handlers/handle-execute-tools
          :dispatches [[::compress tools-executed?]
                       [::fsm/error (constantly true)]]}

         ::compress
         {:handler    handlers/handle-compress
          :dispatches [[::check-done compressed?]
                       [::fsm/error (constantly true)]]}

         ::check-done
         {:handler    handlers/handle-check-done
          :dispatches [;; LOOP EDGE: continue -> back to context reconstruction
                       [::reconstruct-context continue?]
                       ;; Terminal: done -> finalize
                       [::finalize done?]
                       [::fsm/error (constantly true)]]}

         ::finalize
         {:handler    handlers/handle-finalize
          :dispatches [[::fsm/end finalized?]
                       [::fsm/error (constantly true)]]}

         ::fsm/end
         {:handler (fn [_r data] data)}

         ::fsm/error
         {:handler handlers/handle-fsm-error}}

   :opts {:max-trace 50}})  ;; Higher trace limit for looping FSM

;; Compile once, reuse
(def compiled
  "Pre-compiled drone-loop FSM.
   Use with: (fsm/run @compiled resources {:data input-map})"
  (delay (fsm/compile drone-loop-spec)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- clean-internal-fields
  "Remove FSM-internal fields from the result data.
   ::fsm/end handler does NOT execute (engine design), so cleanup
   happens here in the public API wrapper."
  [data]
  (dissoc data
          :context :response-type :tool-calls :text-content
          :completion? :error :tool-results :all-failed?
          :termination :system-prompt :messages
          :obs-count :reason-count))

(def ^:private noop-result
  "Result returned when no LLM backend is provided."
  {:status          :noop
   :result          "No LLM backend provided"
   :steps           []
   :tool_calls_made 0
   :tokens          {:input 0 :output 0 :total 0}
   :turns           0
   :model           "none"
   :kg-stats        nil})

(defn run-drone-loop
  "Execute the drone-loop FSM.

   Main entry point for FSM-based agentic execution.
   Replaces the imperative loop in drone/loop.clj.

   Args:
     data      - Input map (see ns docstring for shape)
     resources - Map with :backend, :tool-schemas, :execute-tools-fn, etc.

   Returns:
     Result map matching drone/loop.clj run-agentic-loop contract:
     {:status :completed|:max_steps|:error|:noop
      :result string
      :steps [...]
      :tool_calls_made int
      :tokens {:input :output :total}
      :turns int
      :model string
      :kg-stats {...}}"
  [data resources]
  (if-not (:backend resources)
    noop-result
    ;; Run the FSM
    (try
      (let [fsm-result (fsm/run @compiled resources {:data data})
            result-data (-> (:data fsm-result)
                            clean-internal-fields)]
        ;; Extract the :fsm-result from data (our structured output)
        (or (:fsm-result result-data)
            ;; Fallback: build result from raw data
            (handlers/make-default-result result-data
                                          :status :error
                                          :result "FSM completed without building result")))
      (catch Throwable e
        (log/error e "drone-loop FSM unexpected error" {:drone-id (:drone-id data)})
        (handlers/make-default-result data
                                      :status :error
                                      :result (str "FSM exception: " (ex-message e)))))))

(defn- build-execute-tools-fn
  "Build the execute-tools-fn closure that wraps tool execution
   with request context management."
  [execute-fn resolve-fn]
  (when execute-fn
    (fn [aid calls perms]
      (if-let [_with-ctx (resolve-fn 'hive-mcp.agent.context/with-request-context)]
        (let [ctx-fn (resolve-fn 'hive-mcp.agent.context/set-request-context!)
              clear-fn (resolve-fn 'hive-mcp.agent.context/clear-request-context!)]
          (try
            (when ctx-fn (ctx-fn {:agent-id aid}))
            (execute-fn aid calls perms)
            (finally
              (when clear-fn (clear-fn)))))
        (execute-fn aid calls perms)))))

(defn make-production-resources
  "Create resources map wired to production dependencies.

   Lazily resolves implementations via requiring-resolve to avoid
   hard namespace dependencies.

   Args:
     backend       - LLMBackend instance
     opts          - Optional overrides:
       :session-store  - IKGStore for session store
       :tools          - tool name allowlist
       :permissions    - tool permission set
       :agent-id       - agent ID string

   Note: :trace? is now read from FSM data (not resources).
   Fire-and-forget effects use registered FX handlers.

   Returns:
     Resources map for run-drone-loop."
  [backend & [opts]]
  (let [{:keys [session-store tools agent-id]} opts
        resolve-fn (fn [sym]
                     (rescue nil (requiring-resolve sym)))
        tool-schemas (when-let [get-schemas-fn (resolve-fn 'hive-mcp.agent.registry/get-schemas)]
                       (get-schemas-fn tools))
        execute-fn (when-let [exec-fn (resolve-fn 'hive-mcp.agent.executor/execute-tool-calls)]
                     exec-fn)]
    ;; Fire-and-forget effects (seed, emit, record-obs, record-reason)
    ;; are now declared as FX data by handlers and executed by registered
    ;; FX handlers in hive-mcp.events.effects.drone-loop.
    {:backend          backend
     :tool-schemas     tool-schemas
     :session-store    session-store
     :agent-id         agent-id
     :execute-tools-fn (build-execute-tools-fn execute-fn resolve-fn)
     :reconstruct-fn   (when-let [f (resolve-fn 'hive-mcp.agent.drone.session-kg/reconstruct-context)]
                         f)}))
