(ns hive-mcp.workflows.strategy
  "Noop default for the :method workflow seam + re-export of its contracts.

   The CONTRACTS themselves moved to hive-spi.workflow.strategy (HWF2-D1b):
   IDispatchStrategy and the WorkflowStrategyEntry ADT. That is what lets the
   hive-workflows addon build entries and implement strategies without a
   hive-mcp dependency (HWF2-M9), and therefore contribute through the IAddon
   `(hooks)` seam — workflows.strategy-registry/register-by-key! — rather than
   reaching into the host with `requiring-resolve`.

   A plan's :method field (contributed by the hive-workflows addon via the plan
   field-registry OCP seam) selects an IDispatchStrategy from the strategy
   registry. A strategy turns a normalized plan into running work — a wave of
   lings, an SAA cycle, a forge belt. Concrete strategies arrive from the addon;
   core ships only the Noop default and these aliases.

   The vars below are plain `def` ALIASES. Do NOT re-`defprotocol`
   IDispatchStrategy or re-`defadt` WorkflowStrategyEntry here: a second
   defprotocol is a distinct protocol (`satisfies?` silently false), and
   hive-dsl.adt keys its registry on the BARE type name, so a duplicate defadt
   is last-loaded-wins JVM-wide. The alias must stay spelled
   `WorkflowStrategyEntry` — `adt-case` resolves its ADT argument by name.

   Mirrors the SAA seam (hive-mcp.saa.types + saa.registry): contracts in
   hive-spi, mechanism in workflows.strategy-registry, wiring in addons.core."
  (:require [hive-dsl.result :as result]
            [hive-spi.workflow.strategy :as spi]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IDispatchStrategy — re-export from hive-spi.workflow.strategy
;; =============================================================================

(def IDispatchStrategy  spi/IDispatchStrategy)
(def dispatch           spi/dispatch)
(def dispatch-strategy? spi/dispatch-strategy?)

;; =============================================================================
;; NoopDispatchStrategy — the only impl core ships
;; =============================================================================

(defrecord NoopDispatchStrategy []
  spi/IDispatchStrategy
  (dispatch [_ plan _opts]
    (result/err :wf/no-strategy
                {:message "No dispatch strategy configured for plan :method — install the hive-workflows addon."
                 :plan-id (:id plan)
                 :method  (:method plan)})))

;; =============================================================================
;; WorkflowStrategyEntry — re-export the defadt surface (all four interned vars)
;; =============================================================================

(def WorkflowStrategyEntry     spi/WorkflowStrategyEntry)
(def workflow-strategy-entry   spi/workflow-strategy-entry)
(def ->workflow-strategy-entry spi/->workflow-strategy-entry)
(def workflow-strategy-entry?  spi/workflow-strategy-entry?)
