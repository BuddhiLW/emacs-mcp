(ns hive-mcp.workflows.complete-session
  "hive-events FSM spec for the Session Complete workflow.

   The complete workflow orchestrates full session lifecycle:
     ::fsm/start -> ::commit -> ::kanban -> ::crystallize -> ::shout
                 -> ::plan-check -> ::evict -> ::end

   This is the Clojure handler implementation matching resources/fsm/complete-session.edn.
   The EDN spec uses keyword handlers (:start, :commit, :kanban, etc.) that are
   resolved to these functions at compile time via the handler-map.

   Design constraints (same as forge-belt):
   - Handlers are PURE functions: (resources, data) -> data'
   - Side effects flow through the resources map (territory)
   - The FSM is the map -- deterministic state transitions
   - Dispatch predicates are pure functions of state data

   Resources map (injected at run time):
     :validate-fn       -- (fn [data] -> nil|{:error str})
     :git-commit-fn     -- (fn [message cwd] -> {:success bool})
     :kanban-done-fn    -- (fn [task-ids directory] -> {:moved N})
     :crystallize-fn    -- (fn [agent-id directory] -> {:summary-id str, ...})
     :shout-fn          -- (fn [agent-id event-type message] -> nil)
     :plan-check-fn     -- (fn [agent-id directory] -> {:triggered? bool}|nil)
     :evict-fn          -- (fn [agent-id] -> {:evicted N})
     :merge-task-ids-fn -- (fn [task-ids agent-id] -> [string])
     :directory          -- string
     :agent-id           -- string

   State data shape:
     {:commit-msg     string   ;; git commit message (required)
      :task-ids       [string] ;; kanban task IDs to mark done
      :agent-id       string   ;; ling identity
      :directory      string   ;; working directory
      :error          any      ;; error info (validation or other)
      :commit-result  map      ;; git commit result
      :kanban-result  map      ;; kanban move result
      :crystal-result map      ;; crystallize result
      :shout-result   map      ;; hivemind shout result
      :plan-result    map      ;; plan-to-kanban trigger result
      :eviction       map}     ;; context eviction result"

  (:require [hive.events.fsm :as fsm]
            [clojure.string :as str]
            [hive-mcp.workflows.support :as support]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Dispatch Predicates (pure functions of state data)
;; =============================================================================

(defn valid?
  "Check if start/validation passed (commit-msg present, no error)."
  [data]
  (and (:commit-msg data) (:agent-id data) (not (:error data))))

(defn invalid?
  "Check if validation failed."
  [data]
  (some? (:error data)))

(defn has-tasks?
  "Check if there are kanban tasks to move to done."
  [data]
  (seq (:task-ids data)))

(defn no-tasks?
  "Check if there are no kanban tasks (skip kanban step)."
  [data]
  (empty? (:task-ids data)))

(def always
  "Dispatch predicate — always true. Shared seam (support/always)."
  support/always)

;; =============================================================================
;; Handlers (pure functions: resources x data -> data')
;;
;; EDN handler-map keys: :start, :commit, :kanban, :crystallize,
;;                       :shout, :plan-check, :evict, :end, :error
;; =============================================================================

(defn handle-start
  "Initialize session complete. Resolve agent-id, directory, validate commit-msg.
   EDN handler key: :start

   Uses resources:
     :validate-fn       (fn [data] -> nil|{:error str})
     :merge-task-ids-fn (fn [task-ids agent-id] -> [string])"
  [resources data]
  (let [validate-fn (or (:validate-fn resources)
                        (fn [{:keys [commit-msg]}]
                          (cond
                            (nil? commit-msg) {:error "Missing required field: commit_msg"}
                            (str/blank? commit-msg) {:error "commit_msg cannot be empty"}
                            :else nil)))
        merge-fn (or (:merge-task-ids-fn resources)
                     (fn [ids _] (vec (or ids []))))
        {:keys [agent-id directory]} (support/resolve-session-identity
                                      resources data {:derive-project? false})
        validation-result (validate-fn data)
        merged-tasks (when-not validation-result
                       (merge-fn (:task-ids data) agent-id))]
    (if validation-result
      (assoc data
             :agent-id agent-id
             :directory directory
             :error (:error validation-result))
      (assoc data
             :agent-id agent-id
             :directory directory
             :task-ids (or merged-tasks [])
             :error nil))))

(defn handle-commit
  "Commit staged changes with the provided message.
   EDN handler key: :commit

   Uses resources:
     :git-commit-fn (fn [message cwd] -> {:success bool})"
  [resources data]
  (let [git-commit-fn (:git-commit-fn resources)
        {:keys [commit-msg directory]} data]
    (if git-commit-fn
      (let [result (git-commit-fn commit-msg directory)]
        (assoc data :commit-result result))
      (assoc data :commit-result {:skipped true}))))

(defn handle-kanban
  "Move kanban tasks to done status.
   EDN handler key: :kanban

   Uses resources:
     :kanban-done-fn (fn [task-ids directory] -> {:moved N})"
  [resources data]
  (let [kanban-done-fn (:kanban-done-fn resources)
        {:keys [task-ids directory]} data]
    (if (and kanban-done-fn (seq task-ids))
      (let [result (kanban-done-fn task-ids directory)]
        (assoc data :kanban-result result))
      (assoc data :kanban-result {:moved 0 :skipped (empty? task-ids)}))))

(defn handle-crystallize
  "Run wrap/crystallize for memory persistence.
   EDN handler key: :crystallize

   Uses resources:
     :crystallize-fn (fn [agent-id directory] -> {:summary-id str, ...})"
  [resources data]
  (let [crystallize-fn (:crystallize-fn resources)
        {:keys [agent-id directory]} data]
    (if crystallize-fn
      (let [result (crystallize-fn agent-id directory)]
        (assoc data :crystal-result result))
      (assoc data :crystal-result {:skipped true}))))

(defn handle-shout
  "Broadcast completion to hivemind coordinator.
   EDN handler key: :shout

   Uses resources:
     :shout-fn (fn [agent-id event-type message] -> nil)"
  [resources data]
  (let [shout-fn (:shout-fn resources)
        {:keys [agent-id commit-msg]} data]
    (when shout-fn
      (shout-fn agent-id :completed (str "Session complete: " commit-msg)))
    (assoc data :shout-sent? true)))

(defn handle-plan-check
  "Check for explorer preset and maybe trigger plan-to-kanban.
   EDN handler key: :plan-check

   Uses resources:
     :plan-check-fn (fn [agent-id directory] -> {:triggered? bool}|nil)"
  [resources data]
  (let [plan-check-fn (:plan-check-fn resources)
        {:keys [agent-id directory]} data]
    (if plan-check-fn
      (let [result (plan-check-fn agent-id directory)]
        (assoc data :plan-result result))
      (assoc data :plan-result nil))))

(defn handle-evict
  "Evict context-store entries for the completing agent.
   EDN handler key: :evict

   Uses resources:
     :evict-fn (fn [agent-id] -> {:evicted N})

   Fatal policy (nil): a thrown evict-fn PROPAGATES (no degraded fallback,
   unlike wrap-session which passes support/continue)."
  [resources data]
  (support/handle-evict resources data nil))

(defn handle-end
  "Terminal state handler. Returns final session complete summary.
   EDN handler key: :end"
  [_resources {:keys [data]}]
  (-> data
      (select-keys [:agent-id :commit-msg :task-ids :commit-result
                    :kanban-result :crystal-result :shout-sent?
                    :plan-result :eviction])
      (assoc :status :ok
             :tasks-completed (count (:task-ids data)))))

(def handle-error
  "Error state handler. Captures error context and throws.
   EDN handler key: :error"
  (support/default-handle-error "Session complete workflow error"
                                [:error :commit-msg]))

;; =============================================================================
;; Handler Map (for EDN spec registration in workflow registry)
;; =============================================================================

(def handler-map
  "Maps EDN keyword handlers to implementation functions.
   Used by registry/register-handlers! for EDN spec compilation."
  {:start       handle-start
   :commit      handle-commit
   :kanban      handle-kanban
   :crystallize handle-crystallize
   :shout       handle-shout
   :plan-check  handle-plan-check
   :evict       handle-evict
   :end         handle-end
   :error       handle-error})

;; =============================================================================
;; In-Code FSM Spec (inline functions, no EDN needed)
;; =============================================================================

(def complete-session-spec
  "hive-events FSM spec for the session complete workflow.
   Uses inline functions -- no handler-map needed at compile time.

   State graph:
   ```
   ::fsm/start -+--> ::commit --> ::kanban --> ::crystallize --> ::shout
                |          --> ::plan-check --> ::evict --> ::end
                |
                +--> ::error (validation failed)
   ```

   If validation fails (no commit-msg), transitions to ::error.
   kanban is skipped (goes straight to crystallize) if no task-ids."
  {:fsm
   {::fsm/start
    {:handler    handle-start
     :dispatches [[::commit valid?]
                  [::fsm/error invalid?]]}

    ::commit
    {:handler    handle-commit
     :dispatches [[::kanban has-tasks?]
                  [::crystallize no-tasks?]]}

    ::kanban
    {:handler    handle-kanban
     :dispatches [[::crystallize always]]}

    ::crystallize
    {:handler    handle-crystallize
     :dispatches [[::shout always]]}

    ::shout
    {:handler    handle-shout
     :dispatches [[::plan-check always]]}

    ::plan-check
    {:handler    handle-plan-check
     :dispatches [[::evict always]]}

    ::evict
    {:handler    handle-evict
     :dispatches [[::fsm/end always]]}

    ::fsm/end
    {:handler handle-end}

    ::fsm/error
    {:handler handle-error}}

   :opts
   {:max-trace 50

    :pre support/trace-log-enter}})

;; =============================================================================
;; Compilation & Execution API
;; =============================================================================

(defn compile-complete
  "Compile the session complete FSM spec. Call once, reuse."
  []
  (fsm/compile complete-session-spec))

(defn run-complete
  "Execute a compiled session complete FSM.

   Args:
     compiled-fsm -- Result of compile-complete
     resources    -- Map of side-effect functions and config
     opts         -- Initial data (must include :commit-msg)

   Returns:
     Final data map with session complete results."
  ([compiled-fsm resources]
   (run-complete compiled-fsm resources {}))
  ([compiled-fsm resources opts]
   (fsm/run compiled-fsm
            resources
            {:data (merge {:commit-msg nil
                           :task-ids []
                           :agent-id nil
                           :directory nil}
                          opts)})))

(defn run-session-complete
  "Convenience: compile and run a single session complete.

   Example:
   ```clojure
   (run-session-complete
     {:git-commit-fn     magit-commit!
      :kanban-done-fn    kanban-move-done!
      :crystallize-fn    wrap-crystallize!
      :shout-fn          hivemind-shout!
      :plan-check-fn     maybe-plan-to-kanban!
      :evict-fn          evict-agent-context!
      :merge-task-ids-fn merge-kanban-ids
      :directory         \"/home/user/project\"
      :agent-id          \"swarm-worker-123\"}
     {:commit-msg \"feat: implement feature X\"
      :task-ids   [\"kb-task-1\"]})
   ```"
  ([resources initial-data]
   (run-complete (compile-complete) resources initial-data)))
