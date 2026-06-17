(ns hive-mcp.saa.model
  "Provider-neutral SAA phase model. Ordered phase descriptors with tool/permission
   intent and goal-prompt fragments. NO vendor strings — vendor tokens are emitted
   solely by IPhaseProvider/build-options.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def saa-phase-model
  "Ordered vector of SAA phase descriptors. No vendor strings."
  [{:phase :silence
    :tool-intent #{:read :search :web}
    :permission-intent :observe-only
    :goal-prompt-fragment
    (str "You are in SILENCE phase (SAA Strategy).\n"
         "Your goal: Observe and collect context WITHOUT acting.\n"
         "- Read files, search code, query memory\n"
         "- Record what you find as structured observations\n"
         "- Do NOT edit files or run commands\n"
         "- At the end, produce a summary of observations")}

   {:phase :abstract
    :tool-intent #{:read :search}
    :permission-intent :plan-only
    :goal-prompt-fragment
    (str "You are in ABSTRACT phase (SAA Strategy).\n"
         "Your goal: Synthesize the observations from Silence phase into an action plan.\n"
         "- Prioritize findings by importance and relevance\n"
         "- Identify patterns and connections\n"
         "- Produce a structured plan with specific steps\n"
         "- Each step should name the file, the change, and the rationale")}

   {:phase :act
    :tool-intent #{:read :write :exec}
    :permission-intent :mutate
    :goal-prompt-fragment
    (str "You are in ACT phase (SAA Strategy).\n"
         "Your goal: Execute the plan from the Abstract phase.\n"
         "- Follow the plan step by step\n"
         "- Make precise, focused changes\n"
         "- Verify each change before moving to the next")}])

(defn phase-descriptor
  "Return the descriptor map for phase, or nil."
  [phase]
  (some #(when (= phase (:phase %)) %) saa-phase-model))

(defn ordered-phases
  "Return phases in canonical order."
  []
  (mapv :phase saa-phase-model))

(defn phase->intent
  "Return {:tool-intent _ :permission-intent _} for phase, or nil."
  [phase]
  (when-let [d (phase-descriptor phase)]
    (select-keys d [:tool-intent :permission-intent])))
