(ns hive-mcp.plan.parser.util
  "Plan utility functions — task-spec conversion and dependency validation.

   Functions:
   - plan->task-specs:       Convert parsed plan to kanban task specifications
   - validate-dependencies:  Verify all depends-on references resolve"
  (:require [hive-mcp.plan.field-registry :as field-registry]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Task Spec Conversion
;; =============================================================================

(defn plan->task-specs
  "Convert a parsed plan to kanban task specifications.

   Useful for feeding into mcp_mem_kanban_create. Carries addon-registered
   projected step fields (plan.field-registry :project?) alongside core fields.

   Returns: vector of task spec maps ready for kanban creation"
  [plan]
  (let [proj (field-registry/project-keys)]
    (mapv (fn [step]
            (merge {:title (:title step)
                    :description (:description step)
                    :priority (name (:priority step))
                    :tags (:tags step)
                    :depends-on (:depends-on step)
                    :plan-step-id (:id step)}
                   (select-keys step proj)))
          (:steps plan))))

;; =============================================================================
;; Dependency Validation
;; =============================================================================

(defn validate-dependencies
  "Validate that all depends-on references exist as step IDs.

   Returns: {:valid true} or {:valid false :missing [...] :step ...}"
  [plan]
  (let [step-ids (set (map :id (:steps plan)))]
    (reduce (fn [acc step]
              (if (:valid acc)
                (let [missing (remove step-ids (:depends-on step))]
                  (if (empty? missing)
                    acc
                    {:valid false
                     :step (:id step)
                     :missing (vec missing)}))
                acc))
            {:valid true}
            (:steps plan))))
