(ns hive-mcp.agent.session-state
  "AgentSessionState ADT — closed algebraic type for agentic loop session states.

   Built on hive-dsl.adt/defadt. Provides type-safe session state dispatch
   with compile-time exhaustiveness checking via adt-case.

   Variants:
     :session/idle     — Loop created but not started
     :session/running  — Loop actively processing turns
     :session/done     — Loop completed successfully
     :session/errored  — Loop terminated with an error
     :session/aborted  — Loop was manually aborted

   Lifecycle:
     :session/idle -> start! -> :session/running -> [abort!|complete]
       -> :session/done | :session/aborted | :session/errored

   Usage:
     (require '[hive-mcp.agent.session-state :as ss])

     ;; Construct
     (ss/agent-session-state :session/idle)
     ;; => {:adt/type :AgentSessionState, :adt/variant :session/idle}

     ;; Coerce from keyword (nil if invalid)
     (ss/->agent-session-state :session/running)

     ;; Predicate
     (ss/agent-session-state? x)

     ;; Exhaustive dispatch
     (adt-case AgentSessionState state
       :session/idle     :waiting
       :session/running  :active
       :session/done     :finished
       :session/errored  :failed
       :session/aborted  :cancelled)"
  (:require [hive-dsl.adt :refer [defadt adt-variant]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; ADT Definition
;; =============================================================================

(defadt AgentSessionState
  "Session lifecycle states for an agentic loop.
   :session/idle     — Loop created but not started
   :session/running  — Loop actively processing turns
   :session/done     — Loop completed successfully
   :session/errored  — Loop terminated with an error
   :session/aborted  — Loop was manually aborted"
  :session/idle
  :session/running
  :session/done
  :session/errored
  :session/aborted)

;; =============================================================================
;; Keyword Coercion
;; =============================================================================

(defn from-keyword
  "Coerce a keyword or string to an AgentSessionState ADT value.
   Returns nil if the input is not a valid session state.

   (from-keyword :session/idle) => {:adt/type :AgentSessionState, :adt/variant :session/idle}
   (from-keyword :bogus)        => nil"
  [k]
  (let [kw (cond
             (keyword? k) k
             (string? k) (keyword k)
             :else nil)]
    (when kw (->agent-session-state kw))))

(defn to-keyword
  "Extract the variant keyword from an AgentSessionState ADT value.
   This is the inverse of from-keyword for round-trip serialization.

   (to-keyword (agent-session-state :session/idle)) => :session/idle"
  [ss]
  (adt-variant ss))

;; =============================================================================
;; Variant Sets
;; =============================================================================

(def all-states
  "Set of all AgentSessionState variant keywords."
  (:variants AgentSessionState))

(def terminal-states
  "Set of terminal session states (loop is no longer active)."
  #{:session/done :session/errored :session/aborted})

(def active-states
  "Set of active session states (loop may still be processing)."
  #{:session/idle :session/running})

;; =============================================================================
;; Predicates
;; =============================================================================

(defn valid-state?
  "Check if a keyword is a valid AgentSessionState variant."
  [k]
  (contains? all-states k))

(defn terminal?
  "Check if the given AgentSessionState ADT value represents a terminal state."
  [ss]
  (contains? terminal-states (adt-variant ss)))

(defn active?
  "Check if the given AgentSessionState ADT value represents an active state."
  [ss]
  (contains? active-states (adt-variant ss)))
