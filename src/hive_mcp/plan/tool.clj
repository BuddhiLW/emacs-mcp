(ns hive-mcp.plan.tool
  "MCP tool for converting plan memory entries to kanban tasks with KG edges.

   Implements the exploration-to-kanban pipeline:
   1. Fetch plan from memory by ID
   2. Parse plan steps using plan.parser (EDN or markdown)
   3. Create kanban tasks via memory system
   4. Create KG edges: plan --depends-on--> tasks
   5. Create KG edges: task --depends-on--> task (from step dependencies)"

  (:require [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.tools.memory-kanban :as mem-kanban]
            [hive-mcp.plan.fsm :as plan-fsm]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.plan.plans :as plans]
            [hive-mcp.knowledge-graph.connection :as kg-conn]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.agent.context :as ctx]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn compute-waves
  "Compute DAG wave numbers for plan steps based on dependencies.

   Returns a map of step-id -> wave-number where:
   - Wave 0: Steps with no dependencies
   - Wave N: Steps whose dependencies are all in waves < N

   This enables parallel execution within each wave and sequential
   ordering across waves for the DAG-Wave swarm pattern.

   Example:
     Steps: A (no deps), B (deps: A), C (no deps), D (deps: B, C)
     Result: {\"A\" 0, \"C\" 0, \"B\" 1, \"D\" 2}"
  [steps]
  (let [step-ids (set (map :id steps))
        step-index (into {} (map (juxt :id identity) steps))

        ;; Iteratively assign wave numbers
        compute-wave (fn [assigned step-id]
                       (let [step (get step-index step-id)
                             deps (filter step-ids (:depends-on step []))]
                         (if (empty? deps)
                           0
                           (inc (apply max (map #(get assigned % 0) deps))))))

        ;; Kahn's algorithm: assign waves in dependency order
        assign-waves (fn [assigned remaining]
                       (if (empty? remaining)
                         assigned
                         (let [;; Find steps whose deps are all assigned
                               ready (filter (fn [sid]
                                               (let [deps (:depends-on (get step-index sid) [])]
                                                 (every? #(contains? assigned %) deps)))
                                             remaining)
                               ;; Assign waves to ready steps
                               new-assigned (reduce (fn [acc sid]
                                                      (assoc acc sid (compute-wave acc sid)))
                                                    assigned
                                                    ready)
                               new-remaining (remove (set ready) remaining)]
                           (if (empty? ready)
                             ;; No progress - cycle detection should have caught this
                             (reduce #(assoc %1 %2 0) assigned remaining)
                             (recur new-assigned new-remaining)))))]
    (assign-waves {} (map :id steps))))

(defn- create-kanban-task!
  "Create a kanban task for a plan step.
   Returns {:ok task-id} or {:error message}"
  [{:keys [title priority]} directory]
  (try
    (let [priority-str (if (keyword? priority) (name priority) (str priority))
          result (mem-kanban/handle-mem-kanban-create
                  {:title title
                   :priority priority-str
                   :directory directory})]
      (if (:isError result)
        {:error (:text result)}
        ;; Parse the result to get the task ID
        (let [parsed (try (json/read-str (:text result) :key-fn keyword)
                          (catch Exception _ nil))]
          (if-let [task-id (or (:id parsed) (get parsed "id"))]
            {:ok task-id}
            {:error "Failed to get task ID from kanban create response"}))))
    (catch Exception e
      {:error (str "Failed to create kanban task: " (.getMessage e))})))

(defn- create-plan-decision-edge!
  "Create KG edge: Plan --derived-from--> Decision.

   Links the plan memory entry to its source decision (exploration result).
   Returns the edge ID or nil if no decision-id provided."
  [plan-id decision-id scope agent-id]
  (when decision-id
    (kg-edges/add-edge!
     {:from plan-id
      :to decision-id
      :relation :derived-from
      :scope scope
      :confidence 1.0
      :source-type :manual
      :created-by (str "plan_to_kanban" (when agent-id (str ":" agent-id)))})))

(defn- create-plan-task-edges!
  "Create KG edges from plan to its tasks.
   plan --depends-on--> task (plan depends on task completion)

   Arguments:
     plan-id  - Plan memory entry ID
     task-ids - Vector of kanban task IDs
     scope    - Project scope
     agent-id - Creating agent
     waves    - Map of step-id -> wave-number (for metadata)
     step-id-to-task-id - Map of step-id -> task-id

   Returns vector of created edge IDs."
  [plan-id task-ids scope agent-id waves step-id-to-task-id]
  (kg-conn/with-tx-batch
    (let [task-id-to-step-id (into {} (map (fn [[k v]] [v k]) step-id-to-task-id))]
      (vec
       (for [task-id task-ids
             :let [step-id (get task-id-to-step-id task-id)
                   _wave (get waves step-id 0)]]
         (kg-edges/add-edge!
          {:from plan-id
           :to task-id
           :relation :depends-on
           :scope scope
           :confidence 1.0
           :source-type :automated
           :created-by (str "plan_to_kanban" (when agent-id (str ":" agent-id)))}))))))

(defn- create-task-dependency-edges!
  "Create KG edges for task-to-task dependencies.
   task-B --depends-on--> task-A (B depends on A completing first)

   Arguments:
     step-id-to-task-id - Map from step ID to kanban task ID
     steps - Plan steps with :depends-on fields
     scope - Project scope
     agent-id - Creating agent
     waves - Map of step-id -> wave-number

   Returns vector of created edge IDs."
  [step-id-to-task-id steps scope agent-id waves]
  (kg-conn/with-tx-batch
    (vec
     (for [step steps
           dep-id (:depends-on step)
           :when (and dep-id (contains? step-id-to-task-id dep-id))]
       (let [from-task-id (get step-id-to-task-id (:id step))
             to-task-id (get step-id-to-task-id dep-id)
             _from-wave (get waves (:id step) 0)
             _to-wave (get waves dep-id 0)]
         (kg-edges/add-edge!
          {:from from-task-id
           :to to-task-id
           :relation :depends-on
           :scope scope
           :confidence 1.0
           :source-type :automated
           :created-by (str "plan_to_kanban" (when agent-id (str ":" agent-id)))}))))))

(defn- build-execute-fn
  "Build the execute function for the Plan FSM.

   Closes over directory/plan-id/project-id/agent-id to create
   kanban tasks and KG edges during the :approved → :executing transition.

   Args (closed over):
     directory      - Working directory for kanban task creation
     plan-memory-id - Plan memory entry ID for KG edge source
     project-id     - Project scope for KG edges
     agent-id       - Creating agent ID

   Returns: (fn [data] -> {:task-ids [...] :kg-edges [...] :waves {...} ...})
   Throws: ExceptionInfo if task creation fails."
  [directory plan-memory-id project-id agent-id]
  (fn [{:keys [plan]}]
    (let [steps (:steps plan)
          ;; Compute wave numbers for DAG-Wave execution
          waves (compute-waves steps)

          ;; Create kanban tasks for each step
          task-results (doall
                        (for [step steps]
                          (let [{:keys [ok error]} (create-kanban-task! step directory)]
                            (if error
                              {:step-id (:id step) :error error}
                              {:step-id (:id step) :task-id ok}))))
          errors (filter :error task-results)
          successes (remove :error task-results)]

      (when (seq errors)
        (throw (ex-info "Failed to create some kanban tasks"
                        {:errors (mapv #(str (:step-id %) ": " (:error %)) errors)})))

      (let [step-id-to-task-id (into {} (map (juxt :step-id :task-id) successes))
            task-ids (mapv :task-id successes)
            decision-id (:decision-id plan)

            ;; Plan --derived-from--> Decision (if decision-id present)
            decision-edge (create-plan-decision-edge!
                           plan-memory-id decision-id project-id agent-id)

            ;; Plan --depends-on--> Task edges
            plan-task-edges (create-plan-task-edges!
                             plan-memory-id task-ids project-id agent-id
                             waves step-id-to-task-id)

            ;; Task --depends-on--> Task dependency edges
            task-dep-edges (create-task-dependency-edges!
                            step-id-to-task-id steps project-id agent-id waves)

            all-edges (cond-> (vec (concat plan-task-edges task-dep-edges))
                        decision-edge (conj decision-edge))]

        {:task-ids task-ids
         :kg-edges all-edges
         :waves waves
         :step-mapping step-id-to-task-id
         :decision-id decision-id}))))

(defn plan-to-kanban
  "Convert a plan memory entry (or file) to kanban tasks with KG edges.

   Uses the Plan FSM (plan.fsm) to drive the lifecycle:
     draft → validated → approved → executing

   The FSM handles parsing, validation (schema + deps + cycles),
   and delegates execution to an injected execute-fn that creates
   kanban tasks and KG edges.

   Arguments:
     plan-memory-id - Memory entry ID containing the plan (nil when using plan-path)
     :directory     - Working directory for project scope (optional)
     :plan-path     - File path to slurp plan content from (alternative to memory ID)
     :auto-assign?  - Auto-assign tasks to lings (optional, not yet implemented)

   Returns:
     {:task-ids [...] :kg-edges [...] :plan-id ...}

   Side effects:
     - Creates kanban tasks in memory system
     - Creates KG edges: plan --depends-on--> tasks
     - Creates KG edges: task --depends-on--> task (based on step dependencies)"
  [plan-memory-id & {:keys [directory plan-path]}]
  (log/info "plan_to_kanban" {:plan-id plan-memory-id :plan-path plan-path :directory directory})
  (try
    (let [directory (or directory (ctx/current-directory))
          agent-id (System/getenv "CLAUDE_SWARM_SLAVE_ID")

          ;; Resolve content: file path takes priority over memory ID
          [content project-id plan-id]
          (if plan-path
            (do (log/info "plan_to_kanban: loading plan from file" {:path plan-path})
                [(slurp plan-path)
                 (ctx/current-project-id)
                 (str "file:" plan-path)])
            (when-let [entry (or (plans/get-plan plan-memory-id)
                                 (facade/get-entry-by-id plan-memory-id))]
              [(:content entry)
               (:project-id entry)
               plan-memory-id]))]

      (if-not content
        (mcp-error (str "Plan not found. Provide plan_id (memory entry) or plan_path (file). "
                        (when plan-memory-id (str "Tried memory ID: " plan-memory-id))
                        (when plan-path (str "Tried file: " plan-path))))

        (let [plan-memory-id (or plan-id plan-memory-id)
              execute-fn (build-execute-fn directory plan-memory-id project-id agent-id)

              ;; Run Plan FSM: draft → validated → approved → executing
              result (plan-fsm/run-plan-fsm
                      {:content content :plan-id plan-memory-id}
                      {:execute-fn execute-fn :directory directory})]

          (mcp-json {:success true
                     :plan-id plan-memory-id
                     :plan-title (get-in result [:plan :title])
                     :plan-source (if plan-path :file :memory)
                     :decision-id (:decision-id result)
                     :task-ids (:task-ids result)
                     :task-count (count (:task-ids result))
                     :waves (:waves result)
                     :max-wave (when (seq (:waves result)) (apply max (vals (:waves result))))
                     :kg-edges (:kg-edges result)
                     :edge-count (count (:kg-edges result))
                     :step-mapping (:step-mapping result)}))))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (log/error e "plan_to_kanban FSM failed" data)
        (mcp-error (str "Plan conversion failed: " (.getMessage e)
                        (when-let [v (:validation data)]
                          (str "\nValidation: " (pr-str v)))
                        (when-let [errs (:errors data)]
                          (str "\nErrors: " (str/join ", " errs)))))))
    (catch Exception e
      (log/error e "plan_to_kanban failed")
      (mcp-error (str "Failed to convert plan to kanban: " (.getMessage e))))))

(defn handle-plan-to-kanban
  "MCP tool handler for plan_to_kanban.
   Accepts plan_id (memory entry) OR plan_path (file path). At least one required."
  [{:keys [plan_id plan_path directory auto_assign]}]
  (if (and (str/blank? plan_id) (str/blank? plan_path))
    (mcp-error "Either plan_id or plan_path is required")
    (plan-to-kanban plan_id
                    :directory directory
                    :plan-path plan_path
                    :auto-assign? auto_assign)))

(def tools
  "REMOVED: Flat plan_to_kanban tool no longer exposed. Use consolidated `kanban` tool with `plan-to-kanban` command."
  [])
