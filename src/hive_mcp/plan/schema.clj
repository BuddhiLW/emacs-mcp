(ns hive-mcp.plan.schema
  "Malli schemas for exploration plan structures.

   Defines the schema for structured plans that lings output after exploration.
   Plans are parsed by plan_to_kanban tool to create kanban tasks with
   KG-based dependency tracking.

   Plan Flow:
   1. Ling explores topic, gathers findings
   2. Ling outputs structured plan in memory (type: decision)
   3. plan_to_kanban parses plan, creates tasks
   4. KG edges link plan -> tasks and task -> task dependencies

   DDD: Value Objects for enums, schemas as domain contracts."
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]
            [hive-mcp.plan.field-registry :as field-registry]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Enumeration Schemas (Value Objects)
;; =============================================================================

(def Priority
  "Valid priority levels for plan steps.

   :high   - Critical path, blocks other work
   :medium - Important but not blocking
   :low    - Nice to have, can be deferred"
  [:enum :high :medium :low])

(def Estimate
  "Valid effort estimates for plan steps.

   :small  - < 1 hour, single function/file change
   :medium - 1-4 hours, multi-file change, some exploration
   :large  - > 4 hours, significant feature, requires design"
  [:enum :small :medium :large])

(def StepStatus
  "Valid status values for plan steps"
  [:enum :todo :in-progress :done :blocked])

(def SourceFormat
  "Valid source formats for plans"
  [:enum :edn :markdown])

;; =============================================================================
;; Step Schema
;; =============================================================================

(def Step
  "Schema for a single plan step.

   Required:
   - :id          Unique step identifier within plan (e.g., \"step-1\")
   - :title       Brief description (becomes kanban task title)

   Optional:
   - :description Extended description with context
   - :depends-on  Vector of step IDs this step depends on
   - :priority    :high | :medium | :low
   - :files       Vector of file paths this step affects
   - :estimate    :small | :medium | :large

   Example:
   {:id \"step-1\"
    :title \"Add disc entity schema for file tracking\"
    :description \"Extend KG schema with disc entities for file state\"
    :depends-on []
    :priority :high
    :files [\"src/hive_mcp/knowledge_graph/schema.clj\"]
    :estimate :small}"
  [:map
   [:id :string]
   [:title :string]
   [:description {:optional true} [:maybe :string]]
   [:depends-on {:optional true :default []} [:vector :string]]
   [:priority {:optional true :default :medium} Priority]
   [:files {:optional true :default []} [:vector :string]]
   [:estimate {:optional true :default :medium} Estimate]
   [:tags {:optional true :default []} [:vector :string]]])

;; =============================================================================
;; Plan Schema
;; =============================================================================

(def Plan
  "Schema for a complete exploration plan.

   Required:
   - :id            Unique plan identifier
   - :title         Human-readable plan title
   - :steps         Vector of step maps (at least one)

   Optional:
   - :decision-id   Memory entry ID of parent decision (links to exploration)
   - :description   Brief summary of the plan
   - :source-format :edn or :markdown (how it was parsed)
   - :tags          Tags for categorization

   Example:
   {:id \"plan-20260128-kg-staleness\"
    :title \"Implement Bayesian Staleness for KG\"
    :decision-id \"20260128161901-40d76a65\"
    :description \"Add staleness detection to KG for memory validity tracking\"
    :steps [{:id \"step-1\"
             :title \"Add disc entity schema\"
             :files [\"src/hive_mcp/knowledge_graph/schema.clj\"]
             :priority :high
             :estimate :small}
            {:id \"step-2\"
             :title \"Implement staleness detection\"
             :depends-on [\"step-1\"]
             :priority :high
             :estimate :medium}]
    :source-format :edn
    :tags [\"knowledge-graph\" \"staleness\"]}"
  [:map
   [:id :string]
   [:title :string]
   [:description {:optional true} [:maybe :string]]
   [:decision-id {:optional true} [:maybe :string]]
   [:steps [:vector Step]]
   [:source-format {:optional true :default :edn} SourceFormat]
   [:tags {:optional true :default []} [:vector :string]]])

;; =============================================================================
;; EDN Plan Schema (for direct EDN parsing)
;; =============================================================================

(def EdnPlanBlock
  "Schema for EDN blocks found in memory content.

   This is the raw format before normalization to Plan.
   May have abbreviated keys, string enums, or missing defaults.
   Lenient parsing - accepts both keyword and string values for enums."
  [:map
   [:steps [:vector [:map
                     [:id :string]
                     [:title :string]
                     [:description {:optional true} :any]
                     [:depends-on {:optional true} [:vector :string]]
                     [:priority {:optional true} [:or Priority :string]]
                     [:files {:optional true} [:vector :string]]
                     [:estimate {:optional true} [:or Estimate :string]]
                     [:tags {:optional true} [:vector :string]]]]]
   [:id {:optional true} :string]
   [:title {:optional true} :string]
   [:decision-id {:optional true} :string]
   [:description {:optional true} :string]
   [:tags {:optional true} [:vector :string]]])

;; =============================================================================
;; plan_to_kanban Result Schema
;; =============================================================================

(def PlanToKanbanResult
  "Result from plan_to_kanban tool execution.

   - :plan-id           The source plan ID
   - :task-ids          Vector of kanban task IDs created
   - :kg-edges          Vector of KG edge IDs created (plan->tasks, task->task deps)"
  [:map
   [:plan-id :string]
   [:task-ids [:vector :string]]
   [:kg-edges [:vector :string]]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-step
  "Validate a step map against the Step schema.

   Returns:
   - {:valid true :data step} on success
   - {:valid false :errors [...]} on failure with humanized errors"
  [step]
  (if (m/validate Step step)
    {:valid true :data step}
    {:valid false :errors (me/humanize (m/explain Step step))}))

(defn validate-plan
  "Validate a plan map against the Plan schema.

   Returns:
   - {:valid true :data plan} on success
   - {:valid false :errors [...]} on failure with humanized errors"
  [plan]
  (if (m/validate Plan plan)
    {:valid true :data plan}
    {:valid false :errors (me/humanize (m/explain Plan plan))}))

(defn valid-step?
  "Predicate version of validate-step. Returns true if valid."
  [step]
  (m/validate Step step))

(defn valid-plan?
  "Predicate version of validate-plan. Returns true if valid."
  [plan]
  (m/validate Plan plan))

(defn explain-plan
  "Get human-readable explanation of why plan is invalid.

   Returns: nil if valid, humanized error otherwise"
  [plan]
  (when-not (m/validate Plan plan)
    (me/humanize (m/explain Plan plan))))

(defn explain-step
  "Get human-readable explanation of why step is invalid.

   Returns: nil if valid, humanized error otherwise"
  [step]
  (when-not (m/validate Step step)
    (me/humanize (m/explain Step step))))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(def ^:private priority-aliases
  "Common priority aliases mapped to canonical values."
  {:normal :medium :default :medium :med :medium
   :critical :high :urgent :high :blocker :high
   :trivial :low :minor :low :none :low})

(defn normalize-priority
  "Convert priority string or keyword to canonical keyword form.

   Case-insensitive. Aliases :normal → :medium, :critical → :high, etc.
   Unknown values clamp to :medium (safe default)."
  [priority]
  (cond
    (nil? priority) :medium
    (keyword? priority)
    (let [k (keyword (str/lower-case (name priority)))]
      (or (#{:high :medium :low} k)
          (get priority-aliases k)
          :medium))
    (string? priority)
    (normalize-priority (keyword (str/lower-case priority)))
    :else :medium))

(defn normalize-estimate
  "Convert estimate string or keyword to canonical keyword form.

   Case-insensitive. Unknown values clamp to :medium (safe default)."
  [estimate]
  (cond
    (nil? estimate) :medium
    (keyword? estimate)
    (let [k (keyword (str/lower-case (name estimate)))]
      (or (#{:small :medium :large} k) :medium))
    (string? estimate)
    (normalize-estimate (keyword (str/lower-case estimate)))
    :else :medium))

(defn normalize-step
  "Normalize a step map to conform to the Step schema.

   Applies defaults, converts types, and applies addon-registered step-field
   normalizers (plan.field-registry) so core stays field-agnostic."
  [step]
  (-> step
      (update :depends-on #(or % []))
      (update :priority normalize-priority)
      (update :files #(or % []))
      (update :estimate normalize-estimate)
      (update :tags #(or % []))
      (field-registry/apply-normalizers :step)))

(defn normalize-plan
  "Normalize a plan map to conform to the Plan schema.

   Applies defaults, normalizes all steps, applies addon-registered plan-field
   normalizers, and resolves per-step field defaults from plan-level keys
   (plan.field-registry) — e.g. step :method inheriting plan :default-method."
  [plan]
  (-> plan
      (field-registry/apply-normalizers :plan)
      (update :steps #(mapv normalize-step %))
      (update :source-format #(or % :edn))
      (update :tags #(or % []))
      field-registry/resolve-step-defaults))

;; =============================================================================
;; Example Plans (Documentation)
;; =============================================================================

(def example-plan
  "Example plan demonstrating the full schema (for documentation)."
  {:id "plan-20260128-kg-staleness"
   :title "Implement Bayesian Staleness for KG"
   :decision-id "20260128161901-40d76a65"
   :description "Add staleness detection to Knowledge Graph for memory validity tracking"
   :source-format :edn
   :tags ["knowledge-graph" "staleness" "bayesian"]
   :steps
   [{:id "step-1"
     :title "Add disc entity schema for file tracking"
     :description "Extend KG schema with disc entities for file state"
     :depends-on []
     :priority :high
     :files ["src/hive_mcp/knowledge_graph/schema.clj"]
     :estimate :small
     :tags ["schema"]}
    {:id "step-2"
     :title "Implement content-hash staleness detection"
     :description "Add functions to detect when file content changes vs cached hash"
     :depends-on ["step-1"]
     :priority :high
     :files ["src/hive_mcp/knowledge_graph/staleness.clj"]
     :estimate :medium
     :tags ["staleness"]}
    {:id "step-3"
     :title "Add certainty propagation on stale edges"
     :description "When disc changes, propagate reduced certainty to dependent KG edges"
     :depends-on ["step-2"]
     :priority :medium
     :files ["src/hive_mcp/knowledge_graph/certainty.clj"]
     :estimate :medium
     :tags ["certainty" "bayesian"]}]})

;; =============================================================================
;; Validation Predicates for plan_to_kanban
;; =============================================================================

(defn valid-plan-to-kanban-result?
  "Check if result is a valid plan_to_kanban result."
  [result]
  (m/validate PlanToKanbanResult result))

(defn explain-plan-to-kanban-result
  "Get human-readable explanation of why result is invalid."
  [result]
  (when-not (m/validate PlanToKanbanResult result)
    (me/humanize (m/explain PlanToKanbanResult result))))

;; =============================================================================
;; Step Dependency Validation
;; =============================================================================

(defn build-step-index
  "Build a map from step-id to step for fast lookup."
  [steps]
  (into {} (map (juxt :id identity) steps)))

(defn validate-dependencies
  "Validate that all step dependencies reference existing steps.

   Returns:
   - {:valid true} if all deps are valid
   - {:valid false :invalid-refs [{:step-id ... :missing-dep ...}]} otherwise"
  [plan]
  (let [step-ids (set (map :id (:steps plan)))
        invalid-refs
        (for [step (:steps plan)
              dep (:depends-on step)
              :when (not (contains? step-ids dep))]
          {:step-id (:id step) :missing-dep dep})]
    (if (empty? invalid-refs)
      {:valid true}
      {:valid false :invalid-refs (vec invalid-refs)})))

(defn detect-cycles
  "Detect circular dependencies in plan steps.

   Uses depth-first search to find cycles.
   Returns:
   - {:valid true} if no cycles
   - {:valid false :cycle [...step-ids...]} if cycle found"
  [plan]
  (let [step-index (build-step-index (:steps plan))]
    (letfn [(has-cycle? [visiting visited step-id]
              (cond
                (contains? visiting step-id) true
                (contains? visited step-id) false
                :else
                (let [step (get step-index step-id)
                      new-visiting (conj visiting step-id)]
                  (if (some #(has-cycle? new-visiting visited %) (:depends-on step))
                    true
                    false))))]
      (let [all-ids (map :id (:steps plan))
            cycle-found (some #(has-cycle? #{} #{} %) all-ids)]
        (if cycle-found
          {:valid false :cycle "Circular dependency detected"}
          {:valid true})))))
