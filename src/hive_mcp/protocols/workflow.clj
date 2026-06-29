(ns hive-mcp.protocols.workflow
  "Protocol definitions for workflow execution engines."
  (:require [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; IWorkflowEngine Protocol
;;; =============================================================================

(defprotocol IWorkflowEngine
  "Protocol for workflow execution engines."

  (load-workflow [this workflow-name opts]
    "Load a workflow definition by name.")

  (validate-workflow [this workflow]
    "Validate a loaded workflow definition.")

  (execute-step [this workflow step-id opts]
    "Execute a single step within a workflow.")

  (execute-workflow [this workflow opts]
    "Execute all steps in a workflow respecting dependency order.")

  (get-status [this workflow-id]
    "Get the current status of a workflow execution.")

  (cancel-workflow [this workflow-id opts]
    "Cancel a running workflow."))

;;; =============================================================================
;;; IWorkflowPersistence Protocol (Extension)
;;; =============================================================================

(defprotocol IWorkflowPersistence
  "Optional extension for durable workflow state."

  (save-state [this workflow-id state]
    "Persist current workflow execution state.")

  (load-state [this workflow-id]
    "Load persisted workflow state.")

  (list-workflows [this opts]
    "List workflow executions matching criteria."))

;;; =============================================================================
;;; NoopWorkflowEngine (No-Op Fallback Implementation)
;;; =============================================================================

(defrecord NoopWorkflowEngine []
  IWorkflowEngine

  (load-workflow [_ workflow-name _opts]
    {:workflow-id (str "noop-" workflow-name "-" (System/currentTimeMillis))
     :name workflow-name
     :steps []
     :metadata {:engine :noop}
     :loaded? false
     :errors ["NoopWorkflowEngine: No workflow engine configured. Set one via set-workflow-engine!"]})

  (validate-workflow [_ _workflow]
    {:valid? false
     :errors ["NoopWorkflowEngine: No workflow engine configured."]
     :warnings []
     :dependency-order []})

  (execute-step [_ _workflow step-id _opts]
    {:success? false
     :step-id step-id
     :result nil
     :duration-ms 0
     :errors ["NoopWorkflowEngine: No workflow engine configured."]
     :context {}})

  (execute-workflow [_ workflow _opts]
    {:success? false
     :workflow-id (:workflow-id workflow)
     :steps-completed 0
     :steps-total (count (:steps workflow))
     :results {}
     :duration-ms 0
     :errors ["NoopWorkflowEngine: No workflow engine configured."]
     :final-context {}})

  (get-status [_ workflow-id]
    {:workflow-id workflow-id
     :name nil
     :status :unknown
     :current-step nil
     :steps-completed 0
     :steps-total 0
     :started-at nil
     :completed-at nil
     :errors ["NoopWorkflowEngine: No workflow engine configured."]
     :progress 0.0})

  (cancel-workflow [_ workflow-id _opts]
    {:success? false
     :workflow-id workflow-id
     :status :unknown
     :steps-completed 0
     :errors ["NoopWorkflowEngine: No workflow engine configured."]}))

;;; =============================================================================
;;; Active Engine Management
;;; =============================================================================

(defonce ^:private slot
  (reg/single-slot {:validate #(satisfies? IWorkflowEngine %)
                    :on-empty ->NoopWorkflowEngine}))

(defn set-workflow-engine!
  "Set the active workflow engine implementation."
  [engine]
  (reg/install! slot engine))

(defn get-workflow-engine
  "Get the active workflow engine, or NoopWorkflowEngine if none set."
  []
  (reg/current slot))

(defn workflow-engine-set?
  "Check if an active workflow engine is configured."
  []
  (reg/present? slot))

(defn clear-workflow-engine!
  "Clear the active workflow engine."
  []
  (reg/clear! slot))

;;; =============================================================================
;;; Utility Functions
;;; =============================================================================

(defn workflow-engine?
  "Check if object implements IWorkflowEngine protocol."
  [x]
  (satisfies? IWorkflowEngine x))

(defn persistent-engine?
  "Check if workflow engine supports persistence."
  [x]
  (satisfies? IWorkflowPersistence x))

(defn enhanced?
  "Check if a non-noop workflow engine is active."
  []
  (and (workflow-engine-set?)
       (not (instance? NoopWorkflowEngine (get-workflow-engine)))))

(defn capabilities
  "Get a summary of available workflow capabilities."
  []
  (let [engine (get-workflow-engine)]
    {:engine-type (if (enhanced?)
                    (-> engine class .getSimpleName)
                    :noop)
     :enhanced? (enhanced?)
     :load? true            ;; Always available (may be no-op)
     :validate? true
     :execute-step? true
     :execute-workflow? true
     :get-status? true
     :cancel? true
     :persistence? (persistent-engine? engine)}))
