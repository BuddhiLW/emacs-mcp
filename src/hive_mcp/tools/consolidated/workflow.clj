(ns hive-mcp.tools.consolidated.workflow
  "Consolidated Workflow CLI tool for Forja Belt automation.

   Thin facade that routes MCP commands to sub-namespace implementations:
   - workflow.forge-ops: smite, survey, forge-status
   - workflow.forge-cycle: build-fsm-resources, forge-strike logic
   - workflow.spawn: spawn, spark, drone dispatch
   - workflow.readiness: agent readiness checks

   Includes defense-in-depth guard: child lings (spawned agents) are
   denied from executing forge-strike to prevent recursive self-call
   chains."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.core :refer [mcp-error mcp-json]]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.tools.consolidated.session :as c-session]
            [hive-mcp.tools.consolidated.workflow.forge-ops :as forge-ops]
            [hive-mcp.tools.consolidated.workflow.forge-cycle :as forge-cycle]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.dns.result :as result]
            [hive-mcp.config.core :as config]
            [hive-mcp.server.guards :as guards]
            [taoensso.timbre :as log]
            [hive-mcp.tools.consolidated.workflow.ir :as ir]
            [hive-mcp.tools.consolidated.workflow.goal :as goal]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ── Forge State ─────────────────────────────────────────────────────────────

(defonce ^:private forge-state
  (atom {:quenched?           false
         :strike-in-progress? false
         :last-strike         nil
         :last-fsm-result     nil
         :total-smited        0
         :total-sparked       0
         :total-strikes       0}))

;; ── Forge Strike Handlers ───────────────────────────────────────────────────

(defn handle-forge-strike-legacy
  "DEPRECATED: Execute ONE forge cycle via imperative smite!/survey/spark!."
  [params]
  (log/warn "DEPRECATED: forge strike-legacy called. Use 'forge strike' (FSM) instead."
            {:directory (:directory params)})
  (if (:quenched? @forge-state)
    (mcp-json {:success false
               :message "Forge is quenched. Use forge-status to check or restart."
               :quenched? true})
    (rb/result->mcp (rb/try-result :forge/strike-legacy-failed
                                   #(forge-cycle/forge-strike-legacy* params forge-state)))))

(defn- do-forge-strike-fsm
  "FSM-driven forge strike."
  [params]
  (rb/result->mcp (rb/try-result :forge/strike-failed
                                 #(forge-cycle/fsm-forge-strike* params forge-state))))

(defn handle-forge-strike
  "Execute ONE forge cycle, FSM-driven by default with legacy config gate.

   Defense-in-depth: denies forge-strike when called from a child ling process
   (HIVE_MCP_ROLE=child-ling). Forge-strike spawns agents, which must be
   restricted to the coordinator to prevent recursive self-call chains.

   Non-blocking: the FSM execution runs in a background future. Returns
   immediately with a queued ack. Check progress via 'forge status'."
  [params]
  ;; Layer 3: Defense-in-depth forge-strike guard
  (if (guards/child-ling?)
    (do
      (log/warn "Forge-strike denied: child ling attempted forge-strike"
                {:role (guards/get-role) :depth (guards/ling-depth)})
      (mcp-error (str "FORGE DENIED: Child lings cannot execute forge strikes.\n\n"
                      "You are running as a child ling (HIVE_MCP_ROLE=child-ling, depth="
                      (guards/ling-depth) ").\n"
                      "Forge-strike spawns agents, which is restricted to the coordinator\n"
                      "to prevent recursive self-call chains (Ling->forge->spawn->Ling->inf).\n\n"
                      "If you need forge work, use hivemind_shout to request the coordinator\n"
                      "to execute forge-strike on your behalf.")))
    (if (:quenched? @forge-state)
      (mcp-json {:success false
                 :message "Forge is quenched. Use forge-status to check or restart."
                 :quenched? true})
      (if (config/get-service-value :forge :legacy :default false)
        (do
          (log/warn "FORGE STRIKE: legacy mode enabled via config. Using imperative path.")
          (handle-forge-strike-legacy params))
        (let [cfg-spawn-mode (config/get-service-value :forge :spawn-mode :default nil)
              effective-params (cond-> params
                                 (and cfg-spawn-mode (not (:spawn_mode params)))
                                 (assoc :spawn_mode cfg-spawn-mode))]
          (swap! forge-state assoc :strike-in-progress? true)
          (future
            (try
              (let [result (forge-cycle/fsm-forge-strike* effective-params forge-state)]
                (swap! forge-state assoc :last-fsm-result result))
              (catch Exception e
                (log/error e "forge-strike background execution failed"))
              (finally
                (swap! forge-state assoc :strike-in-progress? false))))
          (mcp-json {:queued true
                     :message "Forge strike started in background. Check 'forge status' for results."}))))))

;; ── Forge Status ────────────────────────────────────────────────────────────

(defn handle-forge-status
  "Belt dashboard: show forge state, active lings, kanban summary."
  [params]
  (rb/result->mcp (rb/try-result :forge/status-failed
                                 #(forge-ops/forge-status* params forge-state))))

;; ── Forge Quench ────────────────────────────────────────────────────────────

(defn handle-forge-quench
  "Gracefully stop or restart the forge belt."
  [{:keys [restart]}]
  (if restart
    (do
      (swap! forge-state assoc :quenched? false)
      (log/info "FORGE RESTART: Belt restarted. forge-strike is available again.")
      (mcp-json {:success true
                 :message "Forge restarted. Ready for forge-strike."
                 :state @forge-state}))
    (do
      (swap! forge-state assoc :quenched? true)
      (log/info "FORGE QUENCH: Belt stopped. Active lings will continue to completion.")
      (mcp-json {:success true
                 :message "Forge quenched. Active lings will finish. No new spawns."
                 :state @forge-state}))))

;; ── Multi-Front Coordinator (addon extension) ────────────────────────────

(defn- resolve-multi-front
  "Look up multi-front extension fn via extension registry."
  [fn-name]
  (let [ext-key (keyword "multi-front" fn-name)]
    (or (ext/get-extension ext-key)
        (throw (ex-info (str "multi-front extension not available: " fn-name
                             ". Is the providing addon loaded?")
                        {:fn fn-name :extension-key ext-key})))))

(defn handle-multi-front-start
  "Start multi-front coordination: detect fronts → spawn vterm lings → monitor."
  [params]
  (rb/result->mcp
   (rb/try-result :multi-front/start-failed
                  #(let [start-fn (resolve-multi-front "start-multi-front!")]
                     (result/ok (start-fn params))))))

(defn handle-multi-front-status
  "Get multi-front coordination status with per-front milestone progress."
  [_params]
  (rb/result->mcp
   (rb/try-result :multi-front/status-failed
                  #(let [status-fn (resolve-multi-front "multi-front-status")]
                     (result/ok (status-fn))))))

(defn handle-multi-front-stop
  "Stop multi-front coordination."
  [_params]
  (rb/result->mcp
   (rb/try-result :multi-front/stop-failed
                  #(let [stop-fn (resolve-multi-front "stop-multi-front!")]
                     (result/ok (stop-fn))))))

;; ── Deprecated Aliases ──────────────────────────────────────────────────────

(def handle-forge-strike-fsm
  "DEPRECATED alias: FSM is now the default path via handle-forge-strike."
  handle-forge-strike)

(def handle-forge-strike-imperative
  "DEPRECATED alias: renamed to handle-forge-strike-legacy."
  handle-forge-strike-legacy)

;; ── CLI Handler + Tool Definition ───────────────────────────────────────────

(def canonical-handlers
  {:catchup    c-session/handle-catchup
   :wrap       c-session/handle-wrap
   :complete   (fn [params] (c-session/handle-session (assoc params :command "complete")))
   :plan-goal  goal/handle-plan-goal
   :goal-schema goal/handle-goal-schema
   :forge      {:strike             handle-forge-strike
                :strike-imperative  handle-forge-strike-imperative
                :status             handle-forge-status
                :quench             handle-forge-quench
                :multi-front        {:start    handle-multi-front-start
                                     :status   handle-multi-front-status
                                     :stop     handle-multi-front-stop
                                     :_handler handle-multi-front-status}
                :_handler           handle-forge-status}
   :ir         {:list       ir/handle-list
                :get        ir/handle-get
                :describe   ir/handle-describe
                :author     ir/handle-author
                :register   ir/handle-register
                :run        ir/handle-run
                :status     ir/handle-status
                :cancel     ir/handle-cancel
                :method     ir/handle-describe-method
                :vocabulary ir/handle-describe-vocabulary
                :_handler   ir/handle-list}})

(def handlers canonical-handlers)

(def handle-workflow
  (make-cli-handler handlers))

(def tool-def
  {:name "workflow"
   :consolidated true
   :description "Forja Belt workflow: catchup (restore context), wrap (crystallize), complete (full lifecycle), forge-strike (FSM-driven smite->survey->spark cycle), forge-strike-imperative (DEPRECATED legacy path), forge-status (belt dashboard), forge-quench (graceful stop). HWF2 combinator IR: ir list/get/describe/author/register/run/status/cancel, ir method (describe method strategies), ir vocabulary (describe effect verbs). Goal-directed synthesis: goal-schema (project the GoalSpec contract + example for authoring), plan-goal (synthesize + soundness-check a Plan-EDN from a GoalSpec; author=true persists). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["catchup" "wrap" "complete"
                                                "plan-goal" "goal-schema"
                                                "forge strike"
                                                "forge strike-imperative"
                                                "forge status" "forge quench"
                                                "forge multi-front start"
                                                "forge multi-front status"
                                                "forge multi-front stop"
                                                "ir list" "ir get" "ir describe"
                                                "ir author" "ir register" "ir run"
                                                "ir status" "ir cancel"
                                                "ir method" "ir vocabulary"
                                                "help"]
                                         :description "Workflow operation to perform"}
                              "commit_msg" {:type "string"
                                            :description "Git commit message (for complete)"}
                              "task_ids" {:type "array"
                                          :items {:type "string"}
                                          :description "Kanban task IDs. For complete: marks done. For forge-strike: survey whitelist."}
                              "task_filter" {:type "string"
                                             :description "Title prefix filter for survey. E.g. 'result-dsl:' matches all tasks starting with that prefix. More LLM-friendly than exact task_ids."}
                              "agent_id" {:type "string"
                                          :description "Agent ID for session attribution"}
                              "directory" {:type "string"
                                           :description "Working directory for project scoping"}
                              "ast" {:type "string"
                                     :description "wf-IR AST as an EDN string (ir author / ir register)"}
                              "workflow_id" {:type "string"
                                             :description "Stored workflow id (ir get/describe/run/status/cancel)"}
                              "method" {:type "string"
                                        :description "Method key to describe (ir method); omit for all"}
                              "verb" {:type "string"
                                      :description "Effect verb to describe (ir vocabulary); omit for all"}
                              "goal_spec" {:type "string"
                                           :description "GoalSpec as an EDN string (plan-goal): {:goal #{facts} :init #{facts} :max-depth? :opaque?}. Call goal-schema for the contract + a valid example."}
                              "verb_index" {:type "string"
                                            :description "Verb effect-ontology index for plan-goal as an EDN map verb-id -> {:requires :provides :deletes :cost}. Omit to plan over the live verb federation."}
                              "author" {:type "boolean"
                                        :description "plan-goal: when true, persist the compiled workflow through the author path (fail-loud on unsound / unknown refs). Default false = dry-run cert."}
                              "default_method" {:type "string"
                                                :description "Dispatch method stamped on every compiled goal step (plan-goal); default dag-wave."}
                              "predicates" {:type "array"
                                            :items {:type "string"}
                                            :description "Named predicates available at run time (ir author/register, plan-goal author)"}
                              "tags" {:type "array"
                                      :items {:type "string"}
                                      :description "Tag filter for ir list"}
                              "limit" {:type "integer"
                                       :description "Row cap for ir list"}
                              "spawn_mode" {:type "string"
                                            :enum ["claude" "vterm" "headless"]
                                            :description "[forge strike] Spawn mode for every forged ling; default claude. 'headless' is ABSTRACT — the concrete backend is resolved at spawn time by the headless registry. Falls back to config [:forge :spawn-mode] when omitted."}
                              "max_slots" {:type "integer"
                                           :description "[forge strike] Cap on concurrently forged lings (default 10)."}
                              "presets" {:type "array"
                                         :items {:type "string"}
                                         :description "[forge strike] Presets applied to every forged ling; default ling, mcp-first, saa."}
                              "model" {:type "string"
                                       :description "[forge strike] Model passed to every forged ling."}
                              "restart" {:type "boolean"
                                         :description "[forge quench] Restart the belt instead of stopping it, making forge strike available again."}}
                 :required ["command"]}
   :handler handle-workflow})

(def tools [tool-def])
