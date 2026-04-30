(ns hive-mcp.events.handlers.saa-fx
  "SAA workflow side-effect handlers for FSM transitions.

   Registers effect handlers that execute side-effects during SAA
   (Silence-Abstract-Act) workflow phase transitions:

   - :saa/run-workflow    - Run SAA workflow via FSM (async)
   - :saa/tool-gate       - Restrict available tools per SAA phase
   - :saa/context-inject  - Inject context at phase transitions
   - :saa/shout           - Hivemind progress reporting for SAA phases

   These effects are produced by the SAA event handlers (events/handlers/saa.clj)
   and the SAA FSM workflow (workflows/saa_workflow.clj). They bridge the
   event system with external systems (agent context, hivemind, FSM engine).

   Usage:
   ```clojure
   (require '[hive-mcp.events.handlers.saa-fx :as saa-fx])
   (saa-fx/register-saa-fx!)
   ```"

  (:require [hive-mcp.events.core :as ev]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Effect: :saa/tool-gate
;; =============================================================================

(defn- handle-saa-tool-gate
  "Execute a :saa/tool-gate effect - restrict tools per SAA phase.

   During Silence phase only read-only tools are allowed, during Abstract
   planning tools, during Act execution tools. Uses agent context to set
   the tool allowlist for the active agent.

   Expected data shape:
   {:phase         :silence | :abstract | :act
    :agent-id      \"swarm-ling-123\"
    :allowed-tools [\"read_file\" \"grep\" \"glob_files\" ...]}

   Example:
   {:saa/tool-gate {:phase :silence
                    :agent-id \"ling-1\"
                    :allowed-tools [\"read_file\" \"grep\" \"glob_files\"]}}"
  [{:keys [phase agent-id allowed-tools]}]
  (when (and phase agent-id)
    (log/info "[saa-fx] Tool gate:" (name phase)
              "agent=" agent-id
              "tools=" (count (or allowed-tools [])))
    (try
      (when-let [set-allowlist! (requiring-resolve
                                 'hive-mcp.agent.context/set-tool-allowlist!)]
        (set-allowlist! agent-id (set (or allowed-tools [])))
        (log/debug "[saa-fx] Tool allowlist set for" agent-id
                   "phase=" (name phase)))
      (catch Throwable e
        (log/warn "[saa-fx] Tool gate failed (non-fatal):" (.getMessage e))))))

;; =============================================================================
;; Effect: :saa/context-inject
;; =============================================================================

(defn- handle-saa-context-inject
  "Execute a :saa/context-inject effect - inject context at phase transitions.

   Injects relevant context data when SAA transitions between phases:
   - Silence: axioms, conventions, decisions from catchup
   - Abstract: observations from silence phase
   - Act: plan from abstract phase

   Expected data shape:
   {:phase    :silence | :abstract | :act
    :agent-id \"swarm-ling-123\"
    :context  {:axioms       [...]    ; for silence
               :conventions  [...]    ; for silence
               :observations [...]    ; for abstract
               :plan         {...}}}  ; for act

   Example:
   {:saa/context-inject {:phase :abstract
                         :agent-id \"ling-1\"
                         :context {:observations [{:type :file :path \"src/foo.clj\"}]}}}"
  [{:keys [phase agent-id context]}]
  (when (and phase agent-id)
    (let [ctx-keys (keys context)
          ctx-size (count ctx-keys)]
      (log/info "[saa-fx] Context inject:" (name phase)
                "agent=" agent-id
                "keys=" (pr-str ctx-keys)
                "size=" ctx-size)
      (try
        (when-let [inject-ctx! (requiring-resolve
                                'hive-mcp.agent.context/inject-context!)]
          (inject-ctx! agent-id phase context)
          (log/debug "[saa-fx] Context injected for" agent-id
                     "phase=" (name phase)))
        (catch Throwable e
          (log/warn "[saa-fx] Context inject failed (non-fatal):"
                    (.getMessage e)))))))

;; =============================================================================
;; Effect: :saa/shout
;; =============================================================================

(defn- handle-saa-shout
  "Execute a :saa/shout effect - hivemind progress reporting for SAA.

   Broadcasts SAA phase transition events to the hivemind coordinator
   so the coordinator has visibility into SAA workflow progress.

   Expected data shape:
   {:agent-id   \"swarm-ling-123\"
    :phase      :silence | :abstract | :act | :catchup | :validate-plan | ...
    :message    \"Entering silence phase\"
    :event-type :progress | :started | :completed | :error}

   Example:
   {:saa/shout {:agent-id \"ling-1\"
                :phase :silence
                :message \"Exploring codebase\"
                :event-type :progress}}"
  [{:keys [agent-id phase message event-type]}]
  (let [effective-id (or agent-id
                         (System/getenv "CLAUDE_SWARM_SLAVE_ID")
                         "unknown-agent")
        effective-type (or event-type :progress)
        phase-name (if (keyword? phase) (name phase) (str phase))]
    (log/info "[saa-fx] Shout:" phase-name
              "agent=" effective-id
              "type=" (name effective-type)
              "msg=" (subs (or message "") 0 (min 80 (count (or message "")))))
    (try
      (when-let [shout! (requiring-resolve 'hive-mcp.hivemind.core/shout!)]
        (shout! effective-id effective-type
                {:workflow :saa
                 :phase phase
                 :message message}))
      (catch Throwable e
        (log/warn "[saa-fx] Shout failed (non-fatal):" (.getMessage e))))))

;; =============================================================================
;; Effect: :saa/run-workflow
;; =============================================================================

(defn- handle-saa-run-workflow
  "Execute a :saa/run-workflow effect - run SAA workflow via FSM.

   Triggers the SAA (Silence-Abstract-Act) workflow asynchronously.
   Uses the compiled FSM from the workflow registry, falling back to
   the inline spec if the registry isn't available.

   Expected data shape:
   {:task       \"Fix auth bug in login flow\"  ; required
    :agent-id   \"swarm-ling-123\"              ; required
    :directory  \"/path/to/project\"            ; required
    :plan-only? false                           ; optional, default false
    :resources  {...}}                          ; optional, override resources map

   Note: Runs in a future to avoid blocking the event loop.
   Dispatches :saa/completed or :saa/failed events on completion."
  [{:keys [task agent-id directory plan-only? resources] :as _data}]
  (when (and task agent-id)
    (future
      (try
        (require 'hive-mcp.workflows.saa-workflow)
        (let [run-fn (if plan-only?
                       (resolve 'hive-mcp.workflows.saa-workflow/run-plan-only)
                       (resolve 'hive-mcp.workflows.saa-workflow/run-full-saa))
              ;; Build minimal resources if not provided
              default-resources {:scope-fn (fn [dir]
                                             (try
                                               (let [scope-fn (requiring-resolve
                                                               'hive-mcp.swarm.scope/get-current-project-id)]
                                                 (scope-fn dir))
                                               (catch Exception e (log/trace "[saa-fx] scope-fn resolution failed:" (.getMessage e)) "unknown")))
                                 :shout-fn (fn [aid phase msg]
                                             (try
                                               (when-let [shout! (requiring-resolve
                                                                  'hive-mcp.hivemind.core/shout!)]
                                                 (shout! aid :progress
                                                         {:workflow :saa
                                                          :phase phase
                                                          :message msg}))
                                               (catch Exception e (log/trace "[saa-fx] shout-fn resolution failed:" (.getMessage e)) nil)))
                                 :clock-fn #(java.time.Instant/now)}
              effective-resources (merge default-resources resources)
              opts {:task task
                    :agent-id agent-id
                    :directory directory}
              result (run-fn effective-resources opts)]
          ;; Dispatch completion event
          (when-let [dispatch-fn (requiring-resolve 'hive-mcp.events.core/dispatch)]
            (dispatch-fn [:saa/completed (merge (select-keys result
                                                             [:agent-id :task :plan-memory-id
                                                              :kanban-task-ids :plan-only?
                                                              :tests-passed? :grounding-score])
                                                {:agent-id agent-id})])))
        (catch Throwable e
          (log/error "[saa-fx] SAA workflow failed:" (.getMessage e))
          ;; Dispatch failure event
          (try
            (when-let [dispatch-fn (requiring-resolve 'hive-mcp.events.core/dispatch)]
              (dispatch-fn [:saa/failed {:agent-id agent-id
                                         :task task
                                         :phase :unknown
                                         :error (.getMessage e)}]))
            (catch Throwable e2 (log/warn "[saa-fx] Failed to dispatch :saa/failed event:" (.getMessage e2)))))))))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-saa-fx!
  "Register SAA workflow side-effect handlers.

   Effects registered:
   - :saa/run-workflow    - Run SAA workflow via FSM (async)
   - :saa/tool-gate       - Restrict tools per SAA phase
   - :saa/context-inject  - Inject context at phase transitions
   - :saa/shout           - Hivemind progress reporting

   Called from hive-mcp.events.effects.agent/register-agent-effects!"
  []
  (ev/reg-fx :saa/run-workflow handle-saa-run-workflow)
  (ev/reg-fx :saa/tool-gate handle-saa-tool-gate)
  (ev/reg-fx :saa/context-inject handle-saa-context-inject)
  (ev/reg-fx :saa/shout handle-saa-shout)
  (log/info "[hive-events.saa-fx] SAA FX registered: :saa/run-workflow :saa/tool-gate :saa/context-inject :saa/shout"))
