(ns hive-mcp.tools.migrate.kanban.events
  "Hive-events surface for the kanban migrator. The migrator's use-cases
   stay railway-oriented and synchronous; events are an observation
   side-channel (logging, telemetry, optional UI feeds) — not the control
   flow.

   Event ids are namespaced under `:kanban-mig/...` so subscribers can
   filter cleanly. Default handlers just log via timbre; downstream
   layers can override via `reg-event-fx` or hook fx by name."
  (:require [hive.events.fx :as fx]
            [hive.events.router :as router]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def event-ids
  "Closed enum of events the migrator dispatches. Tests assert that
   nothing outside this set escapes."
  #{:kanban-mig/scan-started
    :kanban-mig/ids-listed
    :kanban-mig/batch-started
    :kanban-mig/batch-completed
    :kanban-mig/entry-written
    :kanban-mig/entry-failed
    :kanban-mig/run-done})

;; =============================================================================
;; Default fx (overridable)
;; =============================================================================

(defn- log-progress! [{:keys [stage payload]}]
  (log/info "kanban-mig" {:stage stage :payload payload}))

(defn register-default-fx!
  "Register the default `:kanban-mig/log` fx — emits a structured log line."
  []
  (fx/reg-fx :kanban-mig/log log-progress!))

;; =============================================================================
;; Default event handlers (idempotent)
;; =============================================================================

(defn- emit-log-fx [stage payload]
  {:kanban-mig/log {:stage stage :payload payload}})

(defn register-default-handlers!
  "Register no-op-ish observers that just funnel each event to the log fx.
   Idempotent — re-running replaces existing handlers."
  []
  (router/reg-event-fx :kanban-mig/scan-started
    (fn [_ [_ payload]] (emit-log-fx :scan-started payload)))
  (router/reg-event-fx :kanban-mig/ids-listed
    (fn [_ [_ payload]] (emit-log-fx :ids-listed payload)))
  (router/reg-event-fx :kanban-mig/batch-started
    (fn [_ [_ payload]] (emit-log-fx :batch-started payload)))
  (router/reg-event-fx :kanban-mig/batch-completed
    (fn [_ [_ payload]] (emit-log-fx :batch-completed payload)))
  (router/reg-event-fx :kanban-mig/entry-written
    (fn [_ [_ payload]] (emit-log-fx :entry-written payload)))
  (router/reg-event-fx :kanban-mig/entry-failed
    (fn [_ [_ payload]] (emit-log-fx :entry-failed payload)))
  (router/reg-event-fx :kanban-mig/run-done
    (fn [_ [_ payload]] (emit-log-fx :run-done payload))))

(defonce ^:private initialized? (atom false))

(defn init!
  "One-shot setup. Safe to call repeatedly."
  []
  (when (compare-and-set! initialized? false true)
    (register-default-fx!)
    (register-default-handlers!))
  :ok)

(defn emit
  "Convenience helper for use cases: dispatch a migrator event by its
   plain keyword name (no namespace prefix), with payload. Validates the
   id at the boundary so use cases can't typo into nowhere."
  [event-id payload]
  (when-not (contains? event-ids event-id)
    (throw (ex-info "Unknown migrator event id" {:event-id event-id
                                                  :allowed event-ids})))
  (router/dispatch [event-id payload]))
