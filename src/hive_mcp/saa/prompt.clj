(ns hive-mcp.saa.prompt
  "Pure SAA phase-prompt construction. Preserves the intent of the legacy
   orchestrator build-phase-prompt while drawing the goal fragment from the
   provider-neutral phase model."
  (:require [hive-mcp.saa.model :as model]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn build-phase-prompt
  "Build the full prompt for a SAA phase. Pure.

   content is the phase input (task for :silence, observations for :abstract,
   plan for :act). extra-context is optional prior knowledge / original task.
   phase-model supplies the goal-prompt-fragment."
  [phase content extra-context phase-model]
  (let [fragment (->> phase-model
                      (some #(when (= phase (:phase %)) %))
                      :goal-prompt-fragment)
        body (case phase
               :silence
               (str "TASK: " content
                    "\n\nExplore the codebase and collect context. "
                    "List all relevant files, patterns, and observations."
                    (when extra-context
                      (str "\n\nPrior knowledge context:\n" (pr-str extra-context))))

               :abstract
               (str "Based on these observations from the Silence phase:\n"
                    (pr-str content)
                    "\n\nSynthesize these into a concrete action plan."
                    (when extra-context
                      (str "\n\nOriginal task: " extra-context))
                    "\n\nProduce a structured plan with specific steps. "
                    "Each step should name the file, the change, and the rationale.")

               :act
               (str "Execute the following plan:\n" (or content "Use best judgment.")
                    (when extra-context
                      (str "\n\nOriginal task: " extra-context))
                    "\n\nFollow the plan precisely. Make changes file by file. "
                    "Verify each change before moving to the next."))]
    (if fragment
      (str fragment "\n\n" body)
      body)))

(defn build-phase-prompt*
  "build-phase-prompt against the default phase model."
  [phase content extra-context]
  (build-phase-prompt phase content extra-context model/saa-phase-model))
