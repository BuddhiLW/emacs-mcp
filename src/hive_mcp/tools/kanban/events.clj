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
            [hive-mcp.tools.kanban.transitions :as kt]
            [clojure.string :as str]))
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

(defn retag-fx
  "Pure handler. Given coeffects (entry) and an event, return the effect map
   describing a scope retag. Tags-only mutation — preserves entry id,
   content, KG edges. Returns nil for missing/non-kanban entries.

   Effect map invariants:
     * `:kanban/facade-update` carries {:tags new-tags :project-id new-pid}
       — no content edit. The :project-id field MUST move with the scope
       tag: stores (qdrant/milvus) filter project queries on the payload
       field, not on tags, so a tags-only retag leaves the entry invisible
       on its new board (lived 2026-06-10, funeraria-db EPIC).
     * `:kanban/temporal-record` op = `:kanban-retag` for audit
     * `:kanban/track-movement` records old→new scope as a movement
     * No completion hooks, no delete effect"
  [{:keys [:kanban/entry]} [_ {:keys [task-id new-project-id add-tags remove-tags]}]]
  (when (and entry (pred/kanban-entry? entry))
    (let [{:keys [old-project-id new-project-id old-tags new-tags title]}
          (kt/retag-transition entry new-project-id
                               {:add-tags add-tags :remove-tags remove-tags})]
      {:kanban/track-movement {:task-id    task-id
                               :title      title
                               :from       (str "scope:" old-project-id)
                               :to         (str "scope:" new-project-id)
                               :project-id new-project-id}
       :kanban/temporal-record {:entry-id   task-id
                                :op         :kanban-retag
                                :data       {:old-project-id old-project-id
                                             :new-project-id new-project-id
                                             :old-tags       old-tags
                                             :new-tags       new-tags}
                                :project-id new-project-id}
       :kanban/facade-update   {:task-id task-id
                                :payload {:tags       new-tags
                                          :project-id new-project-id}}})))

(defn edit-fx
  "Pure handler. Given coeffects (entry) and an event, return the effect map
   describing a content edit — title, description, priority. Status, scope
   and KG edges are untouched. Returns nil for missing/non-kanban entries.

   Effect map invariants:
     * `:kanban/facade-update` is ALWAYS present (the soft commit), and
       carries `:tags` ONLY when the priority moved — see
       `kt/edit-transition` for why the tag must travel with the content.
     * `:kanban/temporal-record` op = `:kanban-edit`, emitted only when a
       field actually changed, so a no-op edit leaves no audit noise.
     * No completion hooks, no movement tracking, no delete effect"
  [{:keys [:kanban/entry]} [_ {:keys [task-id title description priority]}]]
  (when (and entry (pred/kanban-entry? entry))
    (let [{:keys [changed new-content new-tags
                  old-title new-title old-priority new-priority]}
          (kt/edit-transition entry {:title       title
                                     :description description
                                     :priority    priority})
          project-id (kt/extract-project-id-from-tags entry)]
      (cond-> {:kanban/facade-update
               {:task-id task-id
                :payload (cond-> {:content new-content}
                           new-tags (assoc :tags new-tags))}}
        (seq changed)
        (assoc :kanban/temporal-record
               {:entry-id   task-id
                :op         :kanban-edit
                :data       {:changed      (vec (sort (map name changed)))
                             :old-title    old-title
                             :new-title    new-title
                             :old-priority old-priority
                             :new-priority new-priority}
                :project-id project-id})))))

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
   move-fx)
  (router/reg-event-fx
   :kanban/retag
   [(cofx/inject-cofx :kanban/entry)]
   retag-fx)
  (router/reg-event-fx
   :kanban/edit
   [(cofx/inject-cofx :kanban/entry)]
   edit-fx))

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

(defn dispatch-retag!
  "Public boundary entry point. Synchronously dispatch a `:kanban/retag` event
   and return a Result wrapping the effect map. Effects run before this returns.

   `event-payload` shape:
     {:task-id ..             — required
      :new-project-id ..      — required, target scope
      :add-tags    [..]       — optional extra tags
      :remove-tags [..]       — optional tag drops
      :directory   ..}        — optional, threaded for cofx context

   Returns:
     (r/ok effect-map)                       on success
     (r/err :kanban/invalid-task-id ...)     when task-id missing/blank
     (r/err :kanban/invalid-project-id ...)  when new-project-id missing/blank
     (r/err :kanban/invalid-task ...)        when entry missing or non-kanban"
  [{:keys [task-id new-project-id] :as event-payload}]
  (init!)
  (cond
    (or (nil? task-id)
        (and (string? task-id) (clojure.string/blank? task-id)))
    (r/err :kanban/invalid-task-id
           {:message "Retag requires :task-id"
            :given   task-id})

    (or (nil? new-project-id)
        (and (string? new-project-id) (clojure.string/blank? new-project-id)))
    (r/err :kanban/invalid-project-id
           {:message "Retag requires :new-project-id"
            :given   new-project-id})

    :else
    (let [ctx (router/dispatch-sync [:kanban/retag event-payload])]
      (result-from-fx (:effects ctx) task-id))))

(defn dispatch-edit!
  "Public boundary entry point. Synchronously dispatch a `:kanban/edit` event
   and return a Result wrapping the effect map. Effects run before this returns.

   `event-payload` shape:
     {:task-id ..     — required
      :title ..       — optional, non-blank string to replace the title
      :description .. — optional, non-blank string to replace the description
      :priority ..    — optional, one of high|medium|low
      :directory ..}  — optional, threaded for cofx context

   A field that is absent, nil, or blank leaves the current value standing;
   at least one of the three must be present.

   Returns:
     (r/ok effect-map)                      on success
     (r/err :kanban/invalid-task-id ...)    when task-id missing/blank
     (r/err :kanban/nothing-to-edit ...)    when no editable field was given
     (r/err :kanban/invalid-priority ...)   when priority is outside the enum
     (r/err :kanban/invalid-task ...)       when entry missing or non-kanban"
  [{:keys [task-id title description priority] :as event-payload}]
  (init!)
  (let [given?     (fn [v] (and (string? v) (not (clojure.string/blank? v))))
        priorities #{"high" "medium" "low"}]
    (cond
      (or (nil? task-id)
          (and (string? task-id) (clojure.string/blank? task-id)))
      (r/err :kanban/invalid-task-id
             {:message "Edit requires :task-id"
              :given   task-id})

      (not (some given? [title description priority]))
      (r/err :kanban/nothing-to-edit
             {:message "Edit requires at least one of :title, :description, :priority"
              :task-id task-id})

      (and (given? priority) (not (priorities priority)))
      (r/err :kanban/invalid-priority
             {:message "Priority must be one of high, medium, low"
              :given   priority})

      :else
      (let [ctx (router/dispatch-sync [:kanban/edit event-payload])]
        (result-from-fx (:effects ctx) task-id)))))