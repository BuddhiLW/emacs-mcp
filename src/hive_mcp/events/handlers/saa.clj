(ns hive-mcp.events.handlers.saa
  "SAA (Silence-Abstract-Act) workflow event handlers.

   Handles events related to the SAA lifecycle:
   - :saa/started         - SAA workflow initiated
   - :saa/phase-complete  - SAA phase transition (Silence→Abstract→Act)
   - :saa/completed       - SAA workflow finished successfully
   - :saa/failed          - SAA workflow error

   These events integrate the FSM-driven SAA workflow (saa_workflow.clj)
   with the hive-events system for observability and composition."

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.events.interceptors :as interceptors]
            [hive-mcp.events.handlers.saa-config :as saa-config]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Handler: :saa/started
;; =============================================================================

(defn handle-saa-started
  "Handler for :saa/started events.

   Called when a SAA workflow is initiated. Broadcasts start to hivemind
   and logs the workflow initiation.

   Expects event data:
   {:agent-id  \"swarm-ling-123\"
    :task      \"Fix auth bug in login flow\"
    :directory \"/path/to/project\"
    :plan-only? false}

   Produces effects:
   - :log   - Log SAA start
   - :shout - Broadcast SAA start to hivemind coordinator"
  [_coeffects [_ {:keys [agent-id task directory plan-only?]}]]
  (let [effective-id (saa-config/effective-agent-id agent-id)
        mode (if plan-only? "plan-only" "full")]
    {:log {:level :info
           :message (str "SAA workflow started: " effective-id
                         " task=" (subs (or task "") 0 (min 80 (count (or task ""))))
                         " mode=" mode)}
     :shout {:agent-id effective-id
             :event-type :started
             :data {:task task
                    :directory directory
                    :workflow :saa
                    :mode mode}}}))

;; =============================================================================
;; Handler: :saa/phase-complete
;; =============================================================================

(defn handle-saa-phase-complete
  "Handler for :saa/phase-complete events.

   Called when the SAA workflow transitions between phases (Silence, Abstract, Act).
   Provides observability into the FSM state machine progress.

   Expects event data:
   {:agent-id         \"swarm-ling-123\"
    :phase            :silence | :silence-review | :abstract | :validate-plan
                      | :store-plan | :act-dispatch | :act-verify
    :grounding-score  0.75       ; optional, for Silence phase
    :plan-valid?      true       ; optional, for Abstract phase
    :silence-iterations 2        ; optional
    :abstract-retries   0}       ; optional

   Produces effects:
   - :log            - Log phase transition
   - :shout          - Broadcast phase progress to hivemind
   - :channel-publish - Emit to WebSocket for Olympus visibility"
  [_coeffects [_ {:keys [agent-id phase grounding-score plan-valid?
                          silence-iterations abstract-retries] :as data}]]
  (let [effective-id (saa-config/effective-agent-id agent-id)
        phase-name (if (keyword? phase) (name phase) (str phase))
        detail (cond
                 (and grounding-score (#{:silence-review} phase))
                 (str " grounding=" (format "%.2f" (double grounding-score))
                      " iteration=" (or silence-iterations "?"))

                 (and (some? plan-valid?) (#{:validate-plan} phase))
                 (str " valid=" plan-valid?
                      (when abstract-retries (str " retries=" abstract-retries)))

                 :else "")]
    {:log {:level :info
           :message (str "SAA phase complete: " phase-name
                         " agent=" effective-id detail)}
     :shout {:agent-id effective-id
             :event-type :progress
             :data {:workflow :saa
                    :phase phase
                    :detail (dissoc data :agent-id)}}
     :channel-publish {:event-type :saa-phase-complete
                       :data {:agent-id effective-id
                              :phase phase
                              :grounding-score grounding-score
                              :plan-valid? plan-valid?}}}))

;; =============================================================================
;; Handler: :saa/completed
;; =============================================================================

(defn handle-saa-completed
  "Handler for :saa/completed events.

   Called when the SAA workflow finishes successfully.
   Broadcasts completion and stores the result summary.

   Expects event data:
   {:agent-id        \"swarm-ling-123\"
    :task            \"Fix auth bug\"
    :plan-memory-id  \"mem-abc-123\"       ; optional
    :kanban-task-ids [\"task-1\" \"task-2\"] ; optional
    :plan-only?      false
    :tests-passed?   true                  ; optional, only for full execution
    :grounding-score 0.85
    :silence-iterations 2
    :abstract-retries   0}

   Produces effects:
   - :log            - Log SAA completion
   - :shout          - Broadcast completion to hivemind
   - :channel-publish - Emit completion event"
  [_coeffects [_ {:keys [agent-id task plan-memory-id kanban-task-ids
                          plan-only? tests-passed? grounding-score] :as data}]]
  (let [effective-id (saa-config/effective-agent-id agent-id)
        mode (if plan-only? "plan-only" "full")
        summary (str "SAA complete (" mode ")"
                     (when plan-memory-id (str " plan=" plan-memory-id))
                     (when (seq kanban-task-ids)
                       (str " kanban-tasks=" (count kanban-task-ids)))
                     (when (and (not plan-only?) (some? tests-passed?))
                       (str " tests=" (if tests-passed? "pass" "fail"))))]
    {:log {:level :info
           :message (str summary " agent=" effective-id)}
     :shout {:agent-id effective-id
             :event-type :completed
             :data {:task task
                    :workflow :saa
                    :mode mode
                    :plan-memory-id plan-memory-id
                    :kanban-task-ids kanban-task-ids
                    :tests-passed? tests-passed?
                    :grounding-score grounding-score}}
     :channel-publish {:event-type :saa-completed
                       :data (select-keys data [:agent-id :task :plan-memory-id
                                                :kanban-task-ids :plan-only?
                                                :tests-passed? :grounding-score])}}))

;; =============================================================================
;; Handler: :saa/failed
;; =============================================================================

(defn handle-saa-failed
  "Handler for :saa/failed events.

   Called when the SAA workflow encounters an unrecoverable error.
   Broadcasts error and emits structured error telemetry.

   Expects event data:
   {:agent-id \"swarm-ling-123\"
    :task     \"Fix auth bug\"
    :phase    :silence | :abstract | :act-dispatch | etc.
    :error    \"Error description\"
    :data     {...}}  ; optional, additional context

   Produces effects:
   - :log               - Log SAA error
   - :shout             - Broadcast error to hivemind
   - :emit-system-error - Structured error telemetry
   - :channel-publish   - Emit error event"
  [_coeffects [_ {:keys [agent-id task phase error] :as data}]]
  (let [effective-id (saa-config/effective-agent-id agent-id)
        phase-name (if (keyword? phase) (name phase) (str phase))]
    {:log {:level :error
           :message (str "SAA workflow failed at phase " phase-name
                         " agent=" effective-id ": " error)}
     :shout {:agent-id effective-id
             :event-type :error
             :data {:task task
                    :workflow :saa
                    :phase phase
                    :error error}}
     :emit-system-error {:error-type :saa-workflow-failed
                         :source (str "saa/" phase-name)
                         :message (str "SAA failed: " error)
                         :context {:agent-id effective-id
                                   :task task
                                   :phase phase
                                   :data (dissoc data :agent-id :task :phase :error)}}
     :channel-publish {:event-type :saa-failed
                       :data {:agent-id effective-id
                              :phase phase
                              :error error}}}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-handlers!
  "Register SAA-related event handlers."
  []
  (ev/reg-event :saa/started
                [interceptors/debug]
                handle-saa-started)

  (ev/reg-event :saa/phase-complete
                [interceptors/debug]
                handle-saa-phase-complete)

  (ev/reg-event :saa/completed
                [interceptors/debug]
                handle-saa-completed)

  (ev/reg-event :saa/failed
                [interceptors/debug]
                handle-saa-failed))
