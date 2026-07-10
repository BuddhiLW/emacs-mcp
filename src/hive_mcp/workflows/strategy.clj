(ns hive-mcp.workflows.strategy
  "Dispatch-strategy contract + closed entry ADT for the :method workflow seam.

   A plan's :method field (contributed by the hive-workflows addon via the plan
   field-registry OCP seam) selects an IDispatchStrategy from the strategy
   registry. A strategy turns a normalized plan into running work — a wave of
   lings, an SAA cycle, a forge belt. Concrete strategies arrive from the addon;
   core ships only the protocol, the Noop default, and the entry ADT.

   Mirrors the SAA seam (hive-mcp.saa.types + saa.registry): contracts here,
   mechanism in workflows.strategy-registry, wiring in addons.core."
  (:require [hive-dsl.adt :as adt]
            [hive-dsl.result :as result]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IDispatchStrategy — selected by a plan's :method
;; =============================================================================

(defprotocol IDispatchStrategy
  "Turns a normalized plan into running work. Selected by the plan's :method."
  (dispatch [this plan opts]
    "Dispatch a normalized plan. Returns a hive-dsl Result ({:ok _} / {:error _})."))

(defrecord NoopDispatchStrategy []
  IDispatchStrategy
  (dispatch [_ plan _opts]
    (result/err :wf/no-strategy
                {:message "No dispatch strategy configured for plan :method — install the hive-workflows addon."
                 :plan-id (:id plan)
                 :method  (:method plan)})))

(defn dispatch-strategy?
  "True if x satisfies IDispatchStrategy."
  [x]
  (satisfies? IDispatchStrategy x))

;; =============================================================================
;; WorkflowStrategyEntry — what addons contribute to the strategy registry
;; =============================================================================

(adt/defadt WorkflowStrategyEntry
  "Addon contribution routed by the \"wf\" hook-key namespace to the strategy
   registry.

   :wf/strategy — registers `:strategy` (an IDispatchStrategy) under `:method`."
  [:wf/strategy {:method keyword? :strategy (constantly true) :owner keyword?}])
