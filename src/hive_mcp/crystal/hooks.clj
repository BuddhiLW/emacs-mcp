(ns hive-mcp.crystal.hooks
  "Event hooks for progressive crystallization — thin boundary layer.

   Delegates harvest to crystal.harvest.collect, synthesis to crystal.synthesis.
   Keeps only: event handlers, hook registration, backward-compat delegates.

   All error handling uses hive-mcp.dns.result DSL.

   DDD: Boundary layer for crystal/wrap events."
  (:require [hive-mcp.crystal.core :as crystal]
            [hive-mcp.crystal.recall :as recall]
            [hive-mcp.crystal.synthesis :as synthesis]
            [hive-mcp.crystal.harvest.collect :as collect]
            [hive-mcp.emacs.client :as ec]       ;; eval-elisp-safe
            [hive-mcp.channel.core :as channel]   ;; on-kanban-done, on-session-end
            [hive-mcp.hooks.core :as hooks]       ;; register-hooks!
            [hive-mcp.swarm.datascript :as ds]    ;; on-kanban-done
            [hive-mcp.agent.context :as ctx]      ;; on-session-end
            [hive-mcp.dns.result :as result]
            [clojure.data.json :as json]          ;; parse-json-safe
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Shared Helpers (used by on-kanban-done)
;; =============================================================================

(defn- eval-elisp-safe
  "Eval elisp with timeout. Returns {:success :result :error :timed-out}."
  [elisp timeout-ms]
  (let [r (result/try-effect* :elisp/eval-failed
                              (ec/eval-elisp-with-timeout elisp timeout-ms))]
    (if (result/ok? r)
      (let [v (:ok r)]
        (when (:timed-out v)
          (log/warn "eval-elisp-safe: timed out"))
        v)
      {:success false :error (:message r)})))

(defn- parse-json-safe
  "Parse JSON string, returning nil on failure."
  [s]
  (result/rescue nil (json/read-str s :key-fn keyword)))

;; =============================================================================
;; Kanban DONE Hook
;; =============================================================================

(defn on-kanban-done
  "Hook called when a kanban task moves to DONE."
  [{:keys [id title project-id _context _priority _started] :as task}]
  (log/info "Kanban DONE hook triggered for task:" id title "project-id:" project-id)
  (result/rescue nil
                 (do (ds/register-completed-task! id {:title title :project-id project-id})
                     (log/debug "Registered completed task in DataScript:" id "project-id:" project-id)))
  (let [progress-note (crystal/task-to-progress-note
                       (assoc task :completed-at (.toString (java.time.Instant/now))))
        tags-elisp (str "(" (str/join " " (map pr-str (:tags progress-note))) ")")
        elisp (format "(hive-mcp-memory-add 'note %s '%s nil 'ephemeral)"
                      (pr-str (:content progress-note))
                      tags-elisp)
        {:keys [success result error timed-out]} (eval-elisp-safe elisp 10000)]
    (when timed-out
      (log/warn "on-kanban-done: elisp eval timed out for task:" id))
    (if success
      (do
        (log/info "Created progress note for completed task:" id)
        (when (channel/server-connected?)
          (channel/broadcast! {:type "task-completed"
                               :task-id id
                               :title title
                               :progress-note-id result}))
        {:success true :progress-note-id result :task task})
      (do
        (log/error "Failed to create progress note:" error)
        {:success false :error error :task task}))))

(defn extract-task-from-kanban-entry
  "Extract task data from a kanban memory entry."
  [entry]
  (let [content (:content entry)
        project-id (some (fn [tag]
                           (when (and (string? tag) (str/starts-with? tag "scope:project:"))
                             (subs tag (count "scope:project:"))))
                         (:tags entry))]
    (if (map? content)
      (cond-> {:id (:id entry)
               :title (:title content)
               :context (:context content)
               :priority (or (:priority content) "medium")
               :started (:started content)
               :status (:status content)}
        project-id (assoc :project-id project-id))
      (cond-> {:id (:id entry)
               :title (str content)
               :context nil
               :priority "medium"
               :started nil
               :status "done"}
        project-id (assoc :project-id project-id)))))

;; =============================================================================
;; Memory Access Hook
;; =============================================================================

(defn on-memory-accessed
  "Hook called when memory entries are accessed."
  [{:keys [entry-ids source session project] :as _params}]
  (let [current-session (or session (crystal/session-id))]
    (doseq [entry-id entry-ids]
      (let [event (recall/create-recall-event
                   {:source source
                    :session current-session
                    :project project
                    :explicit? (not (contains? #{"catchup" "wrap"} source))})]
        (recall/buffer-recall! entry-id event)))
    {:tracked (count entry-ids)
     :source source}))

;; =============================================================================
;; Harvest + Synthesis Delegates (backward compatibility)
;; =============================================================================

(defn harvest-all
  "Harvest all session data for wrap crystallization.
   Delegates to crystal.harvest.collect/harvest-all."
  ([] (collect/harvest-all))
  ([opts] (collect/harvest-all opts)))

(defn harvest-session-progress
  "Harvest session progress notes. Delegates to collect."
  ([] (collect/harvest-session-progress))
  ([opts] (collect/harvest-session-progress opts)))

(defn harvest-completed-tasks
  "Harvest completed tasks. Delegates to collect."
  ([] (collect/harvest-completed-tasks))
  ([opts] (collect/harvest-completed-tasks opts)))

(defn harvest-git-commits
  "Harvest git commits. Delegates to collect."
  ([] (collect/harvest-git-commits))
  ([opts] (collect/harvest-git-commits opts)))

(defn crystallize-session
  "Crystallize session data into long-term memory.
   Delegates to crystal.synthesis/synthesize."
  [harvested]
  (synthesis/synthesize harvested))

;; =============================================================================
;; Auto-Wrap Session-End Handler
;; =============================================================================

(defn- on-session-end
  "Handler for session-end event."
  [event-ctx]
  (log/info "Auto-wrap triggered on session-end:" (:reason event-ctx "shutdown"))
  (let [r (result/try-effect* :crystal/session-end-failed
            (let [dir (or (:directory event-ctx) (ctx/current-directory))
                  agent-id (or (:agent-id event-ctx) (ctx/current-agent-id))
                  harvested (harvest-all {:directory dir :agent-id agent-id})
                  result (crystallize-session harvested)]
              (when (channel/server-connected?)
                (channel/broadcast! {:type "session-ended"
                                     :wrap-completed true
                                     :session (:session result)
                                     :project-id (:project-id result)
                                     :stats (:stats result)}))
              (log/info "Auto-wrap completed:" (:summary-id result) "project:" (:project-id result))
              {:success true
               :summary-id (:summary-id result)
               :project-id (:project-id result)
               :stats (:stats result)}))]
    (if (result/ok? r)
      (:ok r)
      {:success false :error (:message r)})))

;; =============================================================================
;; Hook Registration
;; =============================================================================

(defonce ^:private hooks-registered? (atom false))

(defn register-hooks!
  "Register crystal hooks with the event system."
  [registry]
  (when-not @hooks-registered?
    (log/info "Registering crystal hooks")
    (hooks/register-hook registry :session-end on-session-end)
    (log/info "Registered auto-wrap handler for :session-end")
    (when (channel/server-connected?)
      (result/rescue nil
                     (log/debug "Channel hooks registered")))
    (reset! hooks-registered? true)
    {:registered true}))

(comment
  ;; When a kanban task completes
  (on-kanban-done {:id "task-123"
                   :title "Implement crystal module"
                   :context "Part of progressive crystallization feature"
                   :priority "high"
                   :started "2026-01-04T10:00:00"})

  ;; Harvest session data for wrap
  (harvest-all)

  ;; Crystallize the session
  (crystallize-session (harvest-all)))
