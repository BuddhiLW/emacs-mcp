(ns hive-mcp.saa.types
  "ADT closed sums for the SAA seam.

   - SaaRegistryEntry — addon contributions routed to the SAA registry by key namespace
   - PhaseMessage     — envelopes streamed on a phase out-ch

   Compile-time exhaustiveness via `hive-dsl.adt/adt-case`. raw-msg->phase-message
   lifts the bridge query! raw message shapes into PhaseMessage variants."
  (:require [hive-dsl.adt :as adt]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; SaaRegistryEntry — what addons contribute to the SAA registry
;; =============================================================================

(adt/defadt SaaRegistryEntry
  "Addon contribution routed by key namespace through the SAA registry.

   :saa/phase-provider — registers an IPhaseProvider implementation
   :saa/scorer         — registers an IObservationScorer implementation
   :saa/planner        — registers an IPlanSynthesizer implementation
   :saa/tool-intent     — maps a neutral tool-intent keyword to a concrete tool set"
  [:saa/phase-provider {:provider (constantly true) :owner keyword?}]
  [:saa/scorer         {:scorer (constantly true) :owner keyword?}]
  [:saa/planner        {:planner (constantly true) :owner keyword?}]
  [:saa/tool-intent    {:intent keyword? :tools coll? :owner keyword?}])

;; =============================================================================
;; PhaseMessage — streamed envelopes on a phase out-ch
;; =============================================================================

(adt/defadt PhaseMessage
  "An envelope streamed on a SAA phase out-ch.

   :pm/started        — phase began
   :pm/chunk          — incremental content from the provider
   :pm/observation    — a structured observation collected in Silence
   :pm/phase-complete — phase finished with its terminal payload
   :pm/error          — phase failed
   :pm/saa-complete   — the full SAA cycle finished"
  [:pm/started        {:phase keyword?}]
  [:pm/chunk          {:phase keyword? :content any?}]
  [:pm/observation    {:phase keyword? :observation any?}]
  [:pm/phase-complete {:phase keyword? :payload any?}]
  [:pm/error          {:phase keyword? :error any?}]
  [:pm/saa-complete   {:summary map?}])

;; =============================================================================
;; raw-msg->phase-message — bridge query! raw → PhaseMessage (FIX#6)
;; =============================================================================

(defn raw-msg->phase-message
  "Map a bridge query! raw message into a PhaseMessage variant.

   Raw shapes (from sdk.execution / bridge):
     {:type :message  :phase _ :data _}  → :pm/chunk
     {:type :complete ...}               → :pm/phase-complete
     {:type :result   ...}               → :pm/phase-complete
     {:type :error    :error/:message _} → :pm/error
     {:type :saa-complete ...}           → :pm/saa-complete
   phase is the SAA phase keyword to stamp when the raw message omits one."
  [raw-msg phase]
  (let [ph (or (:saa-phase raw-msg) (:phase raw-msg) phase)]
    (case (:type raw-msg)
      :message
      (phase-message :pm/chunk {:phase ph :content (or (:content raw-msg) (:data raw-msg))})

      (:complete :result)
      (phase-message :pm/phase-complete
                     {:phase ph :payload (or (:content raw-msg) (:data raw-msg) raw-msg)})

      :error
      (phase-message :pm/error {:phase ph :error (or (:error raw-msg) (:message raw-msg))})

      :saa-complete
      (phase-message :pm/saa-complete {:summary (dissoc raw-msg :type)})

      ;; default: treat any unrecognized shape as an opaque chunk
      (phase-message :pm/chunk {:phase ph :content raw-msg}))))
