(ns hive-mcp.tools.agent.spawn
  "Agent spawn handler for creating new ling and drone agents.

   Includes defense-in-depth guard: child lings (spawned agents) are
   denied from spawning further agents to prevent recursive self-call
   chains (Ling→agent.spawn→Ling→agent.spawn→∞)."
  (:require [hive-mcp.tools.core :refer [mcp-error mcp-json]]
            [hive-mcp.tools.agent.helpers :as helpers]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.agent.drone :as drone]
            [hive-mcp.agent.type-registry :as agent-type-registry]
            [hive-mcp.agent.spawn-mode-registry :as spawn-registry]
            [hive-mcp.agent.openrouter :as llm-registry]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.config.core :as config]
            [taoensso.timbre :as log]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- resolve-project-scope
  "Resolve effective project-id for a spawned agent via hierarchy."
  [project_id cwd parent]
  (or project_id
      (when cwd
        (let [inferred (kg-scope/infer-scope-from-path cwd)]
          (when (and inferred (not= inferred "global"))
            inferred)))
      (when parent
        (when-let [parent-data (queries/get-slave parent)]
          (:slave/project-id parent-data)))
      (when cwd
        (last (str/split cwd #"/")))))

;;; =============================================================================
;;; Spawn Guard (Defense-in-Depth — Layer 3)
;;; =============================================================================

(defn- build-spawn-denied-message
  "Build dynamic spawn denial message with current depth info."
  []
  (str "SPAWN DENIED: Child lings cannot spawn agents.\n\n"
       "You are running as a child ling (HIVE_MCP_ROLE=child-ling, depth="
       (guards/ling-depth) ").\n"
       "Agent spawning is restricted to the coordinator to prevent recursive\n"
       "self-call chains (Ling→spawn→Ling→spawn→∞).\n\n"
       "If you need parallel work, use hivemind_shout to request the coordinator\n"
       "to spawn agents on your behalf."))


(defn- heap-pressure-defer
  "Layer 4 OOM backpressure: best-effort heap-headroom admission check.
   Returns a {:level :heap-pct} map when a new spawn should be DEFERRED
   (JVM heap fraction >= the soft watermark), or nil to ADMIT.

   Lings/drones launch inside (or alongside) this nREPL JVM; N concurrent
   heavy spawns atop the multi-GB KG floor have driven kernel OOMs. We shed
   *new* spawns under pressure rather than hard-kill live agents.

   Reuses the self-contained hive-knowledge.cartography.mem-guard governor,
   lazily resolved — hive-mcp does NOT statically depend on hive-knowledge
   (the dep is inverted). FAIL-OPEN: a missing governor, any sampling error,
   or config opt-out all ADMIT (return nil), so the guard can never wedge the
   spawn path.

   Config — note hive-mcp.config.resolve/get-service-value uses (or val default)
   which swallows boolean false, so the kill switch is a default-FALSE *disable*
   flag, not a default-true enable flag:
     [:swarm :heap-admission-disabled] default false; set true (or env
       HIVE_MCP_SWARM_HEAP_ADMISSION_DISABLED=true) to force the gate off.
     [:swarm :heap-admission-soft]      0.0-1.0 heap fraction; nil = mem-guard
       default 0.80 (env HIVE_MCP_SWARM_HEAP_ADMISSION_SOFT)."
  []
  (try
    (when-not (config/get-service-value :swarm :heap-admission-disabled
                                        :env "HIVE_MCP_SWARM_HEAP_ADMISSION_DISABLED"
                                        :parse #(Boolean/parseBoolean %)
                                        :default false)
      (when-let [check (requiring-resolve 'hive-knowledge.cartography.mem-guard/check)]
        (let [soft (config/get-service-value :swarm :heap-admission-soft
                                             :env "HIVE_MCP_SWARM_HEAP_ADMISSION_SOFT"
                                             :parse parse-double
                                             :default nil)
              wm   (when (number? soft) {:soft soft})
              {:keys [level heap-pct]} (check nil wm)]
          (when (contains? #{:soft :hard} level)
            {:level level :heap-pct heap-pct}))))
    (catch Throwable _ nil)))

;;; =============================================================================
;;; Spawn Handler
;;; =============================================================================

(defn handle-spawn
  "Spawn a new agent (ling or drone).

   Defense-in-depth: denies spawn when called from a child ling process
   (HIVE_MCP_ROLE=child-ling). This prevents recursive agent spawning.

   The full request map rides on opts under :spawn/request for the
   :spawn/opts-overlay extension seam, and is stripped before planning."
  [{:keys [type name cwd presets model provider task files parent project_id kanban_task_id spawn_mode agents max_budget_usd kg_compress sliding_window_size verbose llm_retries] :as params}]
  ;; Layer 3: Defense-in-depth spawn guard
  (if-let [_ (when (guards/child-ling?) :denied)]
    (do
      (log/warn "Spawn denied: child ling attempted agent spawn"
                {:role (guards/get-role) :depth (guards/ling-depth)})
      (mcp-error (build-spawn-denied-message)))
    (if-let [defer (heap-pressure-defer)]
      ;; Layer 4: heap-headroom backpressure — defer rather than launch.
      (do
        (log/warn "Spawn deferred: heap pressure backpressure" defer)
        (mcp-json {:success  false
                   :deferred true
                   :reason   "heap-pressure"
                   :level    (clojure.core/name (:level defer))
                   :heap-pct (:heap-pct defer)
                   :message  (str "Spawn deferred: JVM heap at " (:heap-pct defer)
                                  "% (>= soft watermark). Best-effort OOM "
                                  "backpressure — existing agents keep running; "
                                  "retry once active lings drain.")}))
      (let [agent-type (keyword type)]
      (if-not (and (agent-type-registry/valid-type? agent-type)
                   (agent-type-registry/spawnable? agent-type))
        (mcp-error (str "type must be one of: " (pr-str (agent-type-registry/mcp-enum))))
        (try
          ;; Resolve provider+model via registry chain
          (let [resolved (llm-registry/resolve-provider-model
                           {:provider provider :model model :agent-type agent-type})
                effective-model (:model resolved)
                effective-provider (:provider resolved)
                agent-id (or name (helpers/generate-agent-id agent-type))
                effective-project-id (resolve-project-scope project_id cwd parent)]
            (case agent-type
              :ling
              (let [presets-vec (cond
                                  (nil? presets) []
                                  (string? presets) [presets]
                                  (sequential? presets) (vec presets)
                                  :else [presets])
                    effective-spawn-mode (keyword (or spawn_mode "claude"))
                    _ (when-not (spawn-registry/valid-mode? effective-spawn-mode)
                        (throw (ex-info (str "spawn_mode must be one of: " (pr-str spawn-registry/mcp-modes))
                                        {:spawn-mode spawn_mode})))
                    normalized-agents (when (map? agents)
                                        (reduce-kv
                                         (fn [m agent-name agent-spec]
                                           (assoc m (clojure.core/name agent-name)
                                                  (if (map? agent-spec)
                                                    (reduce-kv (fn [m2 k v]
                                                                 (assoc m2 (keyword k) v))
                                                               {} agent-spec)
                                                    agent-spec)))
                                         {} agents))
                    ling-agent (ling/->ling agent-id (cond-> {:cwd cwd
                                                              :presets presets-vec
                                                              :project-id effective-project-id
                                                              :spawn-mode effective-spawn-mode
                                                              :model effective-model
                                                              :provider effective-provider}
                                                       normalized-agents (assoc :agents normalized-agents)
                                                       max_budget_usd    (assoc :max-budget-usd max_budget_usd)
                                                       (some? kg_compress) (assoc :kg-compress? kg_compress)
                                                       (some? verbose)   (assoc :verbose? (if (string? verbose)
                                                                                            (= "true" verbose)
                                                                                            (boolean verbose)))
                                                       llm_retries       (assoc :llm-retries (if (string? llm_retries)
                                                                                               (parse-long llm_retries)
                                                                                               llm_retries))
                                                       sliding_window_size (assoc :sliding-window-size sliding_window_size)))
                    slave-id (proto/spawn! ling-agent (cond-> {:task task
                                                               :parent parent
                                                               :kanban-task-id kanban_task_id
                                                               :spawn-mode (:spawn-mode ling-agent)
                                                               :model effective-model
                                                               :provider effective-provider
                                                               :spawn/request params}
                                                        max_budget_usd (assoc :max-budget-usd max_budget_usd)))]
                (log/info "Spawned ling" {:requested-id agent-id
                                          :slave-id slave-id
                                          :spawn-mode (:spawn-mode ling-agent)
                                          :provider effective-provider
                                          :model effective-model
                                          :cwd cwd :presets presets-vec
                                          :project-id effective-project-id})
                (mcp-json {:success true
                           :agent-id slave-id
                           :type :ling
                           :spawn-mode (:spawn-mode ling-agent)
                           :provider effective-provider
                           :model effective-model
                           :cwd cwd
                           :presets presets-vec
                           :project-id effective-project-id}))

              :drone
              (let [drone-agent (drone/->drone agent-id {:cwd cwd
                                                         :model effective-model
                                                         :provider effective-provider
                                                         :parent-id parent
                                                         :project-id effective-project-id})]
                (proto/spawn! drone-agent {:files files})
                ;; Auto-dispatch when task provided (matches ling spawn behavior)
                (let [task-id (when task
                                (let [delegate-fn @(requiring-resolve 'hive-mcp.agent.core/delegate-agentic-drone!)]
                                  (proto/dispatch! drone-agent {:task task
                                                                :files files
                                                                :delegate-fn delegate-fn})))]
                  (log/info "Spawned drone" {:id agent-id :cwd cwd
                                              :provider effective-provider
                                              :model effective-model
                                              :auto-dispatched? (some? task-id)})
                  (cond-> {:success true
                           :agent-id agent-id
                           :type :drone
                           :provider effective-provider
                           :model effective-model
                           :cwd cwd
                           :files files}
                    task-id (assoc :task-id task-id)
                    :always mcp-json)))))
          (catch Exception e
            (log/error "Failed to spawn agent" {:type agent-type :error (ex-message e)})
            (mcp-error (str "Failed to spawn " (clojure.core/name agent-type) ": " (ex-message e))))))))))
