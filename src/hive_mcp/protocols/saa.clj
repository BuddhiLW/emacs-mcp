(ns hive-mcp.protocols.saa
  "SAA (Silence-Abstract-Act) protocol seam. Leaf ns: no outbound deps.
   Channel returns are core.async out-chs of PhaseMessage envelopes.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IPhaseProvider Protocol
;;; ============================================================================

(defprotocol IPhaseProvider
  "Per-phase configuration and execution. build-options is the sole vendor-token emitter."

  (phase-config [this phase]
    "Return the config map for phase (keyword).")

  (build-options [this phase neutral-opts]
    "Return provider-options map from neutral-opts. Sole vendor-token emitter.")

  (execute-phase! [this session prompt provider-options]
    "Execute phase; return a core.async out-ch of PhaseMessage envelopes."))

;;; ============================================================================
;;; IObservationScorer Protocol
;;; ============================================================================

(defprotocol IObservationScorer
  "Score Silence-phase observations."

  (score [this observations]
    "Return scored observations.")

  (grounding-score [this observations files-read]
    "Return a numeric grounding score for observations and files-read."))

;;; ============================================================================
;;; IPlanSynthesizer Protocol
;;; ============================================================================

(defprotocol IPlanSynthesizer
  "Synthesize a plan from scored observations."

  (synthesize [this scored-observations task]
    "Return a plan from scored-observations for task."))

;;; ============================================================================
;;; ISAAOrchestrator Protocol
;;; ============================================================================

(defprotocol ISAAOrchestrator
  "SAA phase orchestration. Each run-* returns a core.async out-ch of PhaseMessage envelopes."

  (run-silence! [this session task opts]
    "Execute the Silence phase with read-only tools.")

  (run-abstract! [this session observations opts]
    "Execute the Abstract phase to synthesize a plan.")

  (run-act! [this session plan opts]
    "Execute the Act phase with full tool access.")

  (run-full-saa! [this session task opts]
    "Execute the complete SAA cycle."))
