(ns hive-mcp.tools.kanban.events
  "Event-driven kanban transitions.

   Pure handler computes the effect map. Effect interpreters
   (`hive-mcp.tools.kanban.effects`) perform IO. Soft-delete is the only
   commit semantic — `done` retags the entry, never deletes it.

   Public entry point: `dispatch-move!`."
  (:require [hive-dsl.result :as r]
            [hive.events.cofx :as cofx]
            [hive.events.router :as router]
            [hive-mcp.tools.kanban.coeffects :as kcofx]
            [hive-mcp.tools.kanban.effects :as keffects]
            [hive-mcp.tools.kanban.predicates :as pred]
            [hive-mcp.tools.kanban.transitions :as kt]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn move-fx
  "Pure handler. Given coeffects (entry + project-id) and an event, return
   the effect map describing the soft transition.

   Returns `nil` when the entry is missing or not a kanban task — the
   public dispatch wraps this in a Result for the MCP boundary.

   Effect map invariants:
     * `:kanban/facade-update` is ALWAYS present (the soft commit)
     * `:kanban/notify-done` and `:kanban/archive-external` ONLY for `done`
     * No effect deletes from the store"
  [{:keys [:kanban/entry :kanban/project-id]} [_ {:keys [task-id new-status]}]]
  (when (and entry (pred/kanban-entry? entry))
    (let [{:keys [old-status new-status new-content new-tags title]}
          (kt/transition entry new-status project-id)
          done? (pred/done? new-status)
          base {:kanban/track-movement {:task-id task-id :title title
                                        :from old-status :to new-status
                                        :project-id project-id}
                :kanban/temporal-record {:entry-id   task-id
                                         :op         (if done? :kanban-done :kanban-move)
                                         :data       {:old-status old-status
                                                      :new-status new-status}
                                         :project-id project-id}
                :kanban/facade-update   {:task-id task-id
                                         :payload {:content new-content
                                                   :tags    new-tags}}}]
      (cond-> base
        done? (assoc :kanban/notify-done      {:entry entry :task-id task-id}
                     :kanban/archive-external {:entry entry :task-id task-id})))))

(defn- result-from-fx
  "Lift a possibly-nil effect map into a Result."
  [fx-map task-id]
  (if fx-map
    (r/ok fx-map)
    (r/err :kanban/invalid-task
           {:message (str "Entry not found or not a kanban task: " task-id)
            :task-id task-id})))

(defn register-all!
  "Idempotent registration of cofx, fx, and event handlers."
  []
  (kcofx/register-all!)
  (keffects/register-all!)
  (router/reg-event-fx
   :kanban/move
   [(cofx/inject-cofx :kanban/entry)
    (cofx/inject-cofx :kanban/project-id)]
   move-fx))

(defonce ^:private initialized? (atom false))

(defn init!
  "Register everything once per JVM. Safe to call repeatedly."
  []
  (when (compare-and-set! initialized? false true)
    (register-all!)))

(defn dispatch-move!
  "Public boundary entry point. Synchronously dispatch a move event and
   return a Result wrapping the effect map (post-commit it can be used
   for status reporting). Side effects are executed before this returns.

   `event-payload` shape: {:task-id .. :new-status .. :directory ..}"
  [event-payload]
  (init!)
  (let [normalized (update event-payload :new-status pred/normalize-status)]
    (cond
      (not (pred/valid-status? (:new-status normalized)))
      (r/err :kanban/invalid-status
             {:message (str "Invalid status: " (:new-status normalized)
                            ". Valid: todo, doing, review, done")
              :given   (:new-status normalized)})

      :else
      ;; Router runs the interceptor chain and `fx/do-fx` on the resulting
      ;; effects map, then returns the final context. We surface that
      ;; effects map to the caller as a Result.
      (let [ctx (router/dispatch-sync [:kanban/move normalized])]
        (result-from-fx (:effects ctx) (:task-id normalized))))))
