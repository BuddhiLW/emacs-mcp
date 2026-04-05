(ns hive-mcp.tools.session-complete
  "MCP tool for completing ling sessions with full lifecycle.

   - C: Composition - orchestrates git, kanban, crystal, hivemind, plan-to-kanban
   - L: Layers pure - handler validates, event system executes effects
   - A: Architectural performance - single tool call instead of 4
   - R: Represented intent - clear session completion semantics
   - I: Inputs guarded - validates commit_msg required
   - T: Telemetry first - shouts completion for coordinator
   - Y: Yield safe failure - individual effects can fail gracefully

   Session complete sequence:
   1. Git commit staged changes
   2. Move kanban tasks to done
   3. Run wrap/crystallize for memory persistence
   4. Shout completion to hivemind coordinator
   5. Auto-trigger plan_to_kanban if explorer preset (when enabled)

   Usage by lings:
   ```
   session_complete({
     commit_msg: \"feat: implement feature X\",
     task_ids: [\"kanban-task-1\", \"kanban-task-2\"],
     agent_id: \"<CLAUDE_SWARM_SLAVE_ID>\"
   })
   ```

   Result DSL: Internal logic returns Result maps ({:ok val} or {:error category}).
   Single try-result boundary at handler level. Zero nested try-catch."
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.interceptors :as interceptors]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.vectordb.facade :as facade]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private config
  "Session complete configuration.
   :auto-kanban-on-explore - When true, auto-trigger plan_to_kanban for explorer lings"
  (atom {:auto-kanban-on-explore true}))

(defn set-config!
  "Update session_complete configuration.
   Options:
     :auto-kanban-on-explore - Enable/disable auto plan-to-kanban for explorer lings"
  [opts]
  (swap! config merge opts))

(defn get-config
  "Get current session_complete configuration."
  []
  @config)

;; =============================================================================
;; Result DSL Helpers (boundary pattern)
;; =============================================================================

(defn- try-result
  "Execute thunk f returning Result; catch unexpected exceptions as error Result.
   Unlike try-effect, expects f to return a Result map directly."
  [category f]
  (try
    (f)
    (catch Exception e
      (log/error e (str (name category) " failed"))
      (result/err category {:message (.getMessage e)}))))

(defn- result->mcp
  "Convert Result to MCP response.
   {:ok data} -> (mcp-json data), {:error ...} -> (mcp-error message)."
  [r]
  (if (result/ok? r)
    (mcp-json (:ok r))
    (mcp-error (or (:message r) (:cause r) (str (:error r))))))

;; =============================================================================
;; Validation (Result-returning)
;; =============================================================================

(defn- require-commit-msg
  "Validate commit_msg present and non-empty. Returns Result."
  [commit_msg]
  (cond
    (nil? commit_msg)
    (result/err :session/missing-commit-msg {:message "Missing required field: commit_msg"})

    (str/blank? commit_msg)
    (result/err :session/empty-commit-msg {:message "commit_msg cannot be empty"})

    :else (result/ok commit_msg)))

;; =============================================================================
;; Pure Calculations
;; =============================================================================

(defn- resolve-agent-id
  "Resolve agent-id with fallback chain: explicit > context > env > default."
  [agent_id]
  (or agent_id
      (ctx/current-agent-id)
      (System/getenv "CLAUDE_SWARM_SLAVE_ID")
      "unknown-ling"))

(defn- resolve-effective-dir
  "Resolve directory with fallback to context."
  [directory]
  (or directory (ctx/current-directory)))

(defn- merge-kanban-task-ids
  "Merge explicit task_ids with ling's kanban-task-id if not already included.
   Ensures the ling's linked kanban task is auto-completed on session_complete."
  [explicit-task-ids ling-kanban-task-id]
  (let [explicit-set (set (or explicit-task-ids []))]
    (if (and ling-kanban-task-id
             (not (contains? explicit-set ling-kanban-task-id)))
      (conj (vec (or explicit-task-ids [])) ling-kanban-task-id)
      (vec (or explicit-task-ids [])))))

(defn- build-event-data
  "Build event data map for :ling/session-complete dispatch."
  [commit-msg merged-task-ids agent-id cwd]
  {:commit-msg commit-msg
   :task-ids   (when (seq merged-task-ids) merged-task-ids)
   :agent-id   agent-id
   :cwd        cwd})

(defn- build-success-response
  "Build success response map with optional plan trigger info."
  [agent-id merged-task-ids ling-kanban-task-id commit-msg plan-trigger-result]
  (cond-> {:status "ok"
           :agent_id agent-id
           :tasks_completed (count merged-task-ids)
           :linked_kanban_task ling-kanban-task-id
           :commit_msg commit-msg}
    (:triggered? plan-trigger-result)
    (assoc :plan_to_kanban_triggered true
           :plan_id (:plan-id plan-trigger-result))))

;; =============================================================================
;; Side-Effect Helpers (isolated actions)
;; =============================================================================

(defn- get-ling-kanban-task-id
  "Look up the kanban-task-id for a ling from DataScript."
  [agent-id]
  (some-> agent-id ds/get-slave :slave/kanban-task-id))

(defn- has-explorer-preset?
  "Check if a ling has the explorer preset."
  [agent-id]
  (when agent-id
    (some-> (ds/get-slave agent-id) :slave/presets set (contains? "explorer"))))

(defn- resolve-project-id
  "Derive project-id from directory. Returns id or nil."
  [directory]
  (when directory
    (try
      ((requiring-resolve 'hive-mcp.tools.memory.scope/get-current-project-id) directory)
      (catch Exception e (log/trace "[session-complete] project-id resolution failed:" (.getMessage e)) nil))))

(defn- find-plan-memory
  "Find the most recent plan memory created by this agent.
   Returns memory entry map or nil."
  [agent-id project-id]
  (let [agent-tag (str "agent:" agent-id)
        entries (facade/query-entries :type "decision"
                                      :project-id project-id
                                      :limit 50)]
    (->> entries
         (filter (fn [entry]
                   (let [tags (set (:tags entry))]
                     (and (contains? tags "plan")
                          (contains? tags "exploration-output")
                          (contains? tags agent-tag)))))
         (sort-by :created #(compare %2 %1))
         (first))))

(defn- trigger-plan-to-kanban-async!
  "Trigger plan_to_kanban for a plan memory entry asynchronously.
   Best-effort, fire-and-forget via future."
  [plan-id directory]
  (future
    (try
      (let [plan-to-kanban (requiring-resolve 'hive-mcp.plan.tool/plan-to-kanban)
            r (plan-to-kanban plan-id :directory directory)]
        (log/info "plan_to_kanban result:" (pr-str r)))
      (catch Exception e
        (log/error e "Async plan_to_kanban failed")))))

(defn- maybe-trigger-plan-to-kanban!
  "Check if explorer ling has plan memory, trigger plan_to_kanban if so.
   Best-effort. Returns {:triggered? true :plan-id id} or nil."
  [agent-id directory]
  (when (and (:auto-kanban-on-explore @config)
             (has-explorer-preset? agent-id))
    (log/info "Explorer preset detected for" agent-id "- checking for plan memory")
    (when-let [plan-memory (find-plan-memory agent-id (resolve-project-id directory))]
      (let [plan-id (:id plan-memory)]
        (log/info "Found plan memory:" plan-id "- triggering plan_to_kanban")
        (trigger-plan-to-kanban-async! plan-id directory)
        {:triggered? true :plan-id plan-id}))))

;; =============================================================================
;; Event Handler (dispatched via hive-events)
;; =============================================================================

(defn- resolve-event-agent
  "Resolve agent-id for event handler with full fallback chain."
  [agent-id coeffects]
  (or agent-id
      (ctx/current-agent-id)
      (get-in coeffects [:agent-context :agent-id])
      (System/getenv "CLAUDE_SWARM_SLAVE_ID")
      "unknown-ling"))

(defn- resolve-event-cwd
  "Resolve working directory for event handler with fallback chain."
  [cwd coeffects]
  (or cwd
      (ctx/current-directory)
      (get-in coeffects [:agent-context :cwd])
      (System/getProperty "user.dir")))

(defn- build-session-effects
  "Build the effects map for session completion event.

   Produces effects:
   - :git-commit       - Stage all and commit
   - :kanban-move-done - Move tasks to done (if task-ids provided)
   - :wrap-crystallize - Persist session to memory
   - :shout            - Broadcast completion"
  [commit-msg task-ids agent-id cwd]
  (cond-> {:git-commit {:message commit-msg
                        :files ["all"]
                        :cwd cwd}
           :wrap-crystallize {:agent-id agent-id :directory cwd}
           :shout {:agent-id agent-id
                   :event-type :completed
                   :message (str "Session complete: " commit-msg)}}
    (seq task-ids)
    (assoc :kanban-move-done {:task-ids (vec task-ids)
                              :directory cwd})))

(defn- handle-ling-session-complete
  "Handler for :ling/session-complete events.

   Orchestrates the full session completion sequence via effects.

   Expects event data:
   {:commit-msg \"feat: message\"
    :task-ids   [\"task-1\" \"task-2\"]  ; optional
    :agent-id   \"ling-123\"
    :cwd        \"/project/path\"}       ; optional"
  [coeffects [_ {:keys [commit-msg task-ids agent-id cwd]}]]
  (let [effective-agent (resolve-event-agent agent-id coeffects)
        effective-cwd   (resolve-event-cwd cwd coeffects)]
    (log/info "ling/session-complete:" effective-agent
              "tasks:" (count (or task-ids [])))
    (build-session-effects commit-msg task-ids effective-agent effective-cwd)))

;; =============================================================================
;; Handler Registration
;; =============================================================================

(defonce ^:private *handler-registered (atom false))

(defn register-handler!
  "Register the :ling/session-complete event handler.
   Safe to call multiple times."
  []
  (when-not @*handler-registered
    (ev/reg-event :ling/session-complete
                  [interceptors/debug]
                  handle-ling-session-complete)
    (reset! *handler-registered true)
    (log/info "[session-complete] Handler registered: :ling/session-complete")
    true))

(defn reset-registration!
  "Reset registration state. For testing."
  []
  (reset! *handler-registered false))

;; =============================================================================
;; Core Logic (Result-returning)
;; =============================================================================

(defn- session-complete*
  "Session completion logic. Returns Result.

   1. Validates commit_msg
   2. Resolves agent-id and directory
   3. Merges kanban task IDs (explicit + linked)
   4. Dispatches :ling/session-complete event
   5. Optionally triggers plan_to_kanban for explorers"
  [{:keys [commit_msg task_ids agent_id directory]}]
  (result/let-ok [_ (require-commit-msg commit_msg)]
                 (let [agent-id           (resolve-agent-id agent_id)
                       effective-dir      (resolve-effective-dir directory)
                       ling-kanban-task-id (get-ling-kanban-task-id agent-id)
                       merged-task-ids    (merge-kanban-task-ids task_ids ling-kanban-task-id)]
                   (when ling-kanban-task-id
                     (log/info "session-complete: auto-adding ling's kanban-task-id:" ling-kanban-task-id))
                   (register-handler!)
                   (ev/dispatch [:ling/session-complete
                                 (build-event-data commit_msg merged-task-ids agent-id effective-dir)])
                   (let [plan-trigger (maybe-trigger-plan-to-kanban! agent-id effective-dir)]
                     (result/ok (build-success-response
                                 agent-id merged-task-ids ling-kanban-task-id commit_msg plan-trigger))))))

;; =============================================================================
;; Public Handler (thin boundary: try-result + result->mcp)
;; =============================================================================

(defn handle-session-complete
  "Complete a ling session: commit changes, move tasks to done, crystallize wrap.

   Required:
   - commit_msg: Git commit message

   Optional:
   - task_ids: Array of kanban task IDs to mark done
   - agent_id: Ling's slave ID (auto-detected from env if not provided)
   - directory: Working directory for git/kanban scoping

   Note: If the ling was spawned with a kanban_task_id, it will be
   automatically added to the task_ids for completion (if not already present).

   Returns:
   - {:status \"ok\", :agent_id \"...\", :tasks_completed N}
   - {:error \"...\"}  on validation failure"
  [params]
  (log/info "session-complete:" (:commit_msg params) "tasks:" (count (or (:task_ids params) [])))
  (result->mcp (try-result :session/complete-failed #(session-complete* params))))

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tools
  "REMOVED: Flat session_complete tool no longer exposed. Use consolidated `session` tool with `complete` command."
  [])
