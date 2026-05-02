(ns hive-mcp.tools.kanban.effects
  "Effect interpreters for kanban events.

   Each `reg-fx` wraps a single side-effecting helper. Effect handlers are
   the only place where mutation, IO, or external bridges occur.

   Effect map shape (handler return value):

     {:kanban/track-movement     {:task-id .. :title .. :from .. :to .. :project-id ..}
      :kanban/temporal-record    {:entry-id .. :op .. :data .. :project-id ..}
      :kanban/facade-update      {:task-id .. :payload {:content .. :tags ..}}
      :kanban/notify-done        {:entry .. :task-id ..}   ; only for done transitions
      :kanban/archive-external   {:entry .. :task-id ..}}  ; only for done transitions

   Soft-delete invariant: there is no `:kanban/facade-delete` effect."
  (:require [clojure.string :as str]
            [hive-dsl.result :refer [rescue]]
            [hive.events.fx :as fx]
            [hive-mcp.crystal.hooks :as crystal-hooks]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.memory.temporal :as temporal]
            [hive-mcp.swarm.datascript :as ds]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.kanban-facade :as kanban-facade]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- track-movement!
  "Record a kanban status transition in DataScript for wrap harvest.
   Non-fatal — movement tracking failure must not block kanban ops."
  [{:keys [task-id title from to project-id]}]
  (try
    (ds/register-kanban-movement!
     {:task-id task-id :title title :from from :to to :project-id project-id})
    (catch Exception e
      (log/debug "track-movement! failed (non-fatal):" (.getMessage e)))))

(defn- archive-external!
  "Archive task data via extension registry. Non-blocking, non-fatal.
   Delegates to `:da/archive!` extension if available; otherwise no-op."
  [{:keys [entry task-id]}]
  (try
    (when-let [archive-fn (ext/get-extension :da/archive!)]
      (let [content (:content entry)
            scope (some-> entry :tags
                          (->> (filter #(str/starts-with? % "scope:project:"))
                               first
                               (str/replace "scope:project:" "")))
            task-data {:id task-id
                       :title (or (get content :title)
                                  (get content :description)
                                  (str task-id))
                       :scope scope
                       :agent-id (get content :agent-id)
                       :files (get content :files)
                       :completed-at (java.util.Date.)
                       :session-id (rescue nil
                                           (when-let [sid (requiring-resolve
                                                           'hive-mcp.crystal.core/session-id)]
                                             (sid)))
                       :context (get content :context)
                       :tags (filterv #(not (str/starts-with? % "scope:"))
                                      (or (:tags entry) []))}]
        (archive-fn task-data)
        (log/info "Archived done task via extension:" task-id)))
    (catch Exception e
      (log/debug "Done-archive extension not available (non-fatal):"
                 (.getMessage e)))))

(defn- notify-done!
  "Fire the crystal hook for a completed task. Non-fatal."
  [{:keys [entry task-id]}]
  (when-let [task-data (crystal-hooks/extract-task-from-kanban-entry entry)]
    (log/info "Crystal hook for completed kanban task:" task-id
              "project-id:" (:project-id task-data))
    (try (crystal-hooks/on-kanban-done task-data)
         (catch Exception e
           (log/warn "Crystal hook failed (non-fatal):" (.getMessage e))))))

(defn- temporal-record!
  "Record a temporal mutation for audit. Non-fatal."
  [m]
  (temporal/record-mutation-silent! m))

(defn- facade-update!
  "Apply a soft-mutation to the underlying memory store.
   Routes via kanban-facade so the write lands in the configured slot
   (`:default` legacy / `:kanban` post-cutover / both during dual-read).
   This is the COMMIT — preserves entry id, content, KG edges."
  [{:keys [task-id payload]}]
  (kanban-facade/update-entry! task-id payload))

(defn register-all!
  "Idempotent registration of every kanban effect interpreter.
   Safe to call from init or test setup."
  []
  (fx/reg-fx :kanban/track-movement   track-movement!)
  (fx/reg-fx :kanban/archive-external archive-external!)
  (fx/reg-fx :kanban/notify-done      notify-done!)
  (fx/reg-fx :kanban/temporal-record  temporal-record!)
  (fx/reg-fx :kanban/facade-update    facade-update!))
