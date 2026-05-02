(ns hive-mcp.events.effects.infrastructure
  "Infrastructure effect handlers for the hive-mcp event system.

   Effects implemented:
   - :ds-transact            - Execute DataScript transaction
   - :git-commit             - Stage files and create git commit
   - :kanban-sync            - Synchronize kanban state
   - :kanban-move-done       - Move kanban tasks to done
   - :report-metrics         - Report event system metrics
   - :tool-registry-refresh  - Refresh tool handlers after hot-reload

   Usage:
   ```clojure
   (require '[hive-mcp.events.effects.infrastructure :as infra-effects])
   (infra-effects/register-infrastructure-effects!)
   ```"

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.emacs-ext.client :as ec]
            [clojure.java.shell :as shell]
            [datascript.core :as d]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Effect: :ds-transact
;; =============================================================================

(defn- handle-ds-transact
  "Execute a :ds-transact effect - DataScript transaction.

   Expected data: vector of transaction data (datoms or tx-maps).

   Example:
   {:ds-transact [{:slave/id \"worker-1\" :slave/status :working}]}"
  [tx-data]
  (when (seq tx-data)
    (let [conn (ds/get-conn)]
      (d/transact! conn tx-data))))

;; =============================================================================
;; Effect: :git-commit (P5-2)
;; =============================================================================

(defn- handle-git-commit
  "Execute a :git-commit effect - stage files and create commit.

   Stages specified files and creates a git commit with the given message.
   Uses shell commands via clojure.java.shell.

   Expected data shape:
   {:files   [\"src/a.clj\" \"src/b.clj\"]
    :message \"feat: Add new feature\"
    :task-id \"task-123\"}  ; optional, for logging"
  [{:keys [files message task-id]}]
  (when (and (seq files) message)
    (try
      (let [_add-result (apply shell/sh
                               "git" "add" "--" (vec files))
            commit-result (shell/sh
                           "git" "commit" "-m" message)]
        (if (zero? (:exit commit-result))
          (log/info "[EVENT] Git commit created:" message
                    (when task-id (str " (task: " task-id ")")))
          (log/warn "[EVENT] Git commit failed:" (:err commit-result))))
      (catch Exception e
        (log/error "[EVENT] Git commit error:" (.getMessage e))))))

;; =============================================================================
;; Effect: :kanban-sync (P5-4)
;; =============================================================================

(defn- handle-kanban-sync
  "Execute a :kanban-sync effect - synchronize kanban state.

   Syncs kanban tasks between memory and external sources.
   Currently logs the intent; actual sync uses existing kanban infrastructure.

   Expected data shape:
   {:project   \"hive-mcp\"
    :direction :bidirectional | :push | :pull}"
  [{:keys [project direction]}]
  (when project
    (try
      ;; TODO: Wire to actual kanban sync infrastructure
      ;; For now, emit channel event for Emacs-side sync
      (channel/emit-event! :kanban-sync {:project project
                                         :direction (or direction :bidirectional)})
      (log/info "[EVENT] Kanban sync triggered:" project
                (str "direction=" (or direction :bidirectional)))
      (catch Exception e
        (log/error "[EVENT] Kanban sync error:" (.getMessage e))))))

;; =============================================================================
;; Effect: :kanban-move-done (Session Complete)
;; =============================================================================

(defn- handle-kanban-move-done
  "Execute a :kanban-move-done effect - move multiple tasks to done.

   Moves each specified kanban task to done status via the memory kanban system.
   Part of the session_complete workflow.

   Expected data shape:
   {:task-ids  [\"task-1\" \"task-2\"]
    :directory \"/project/path\"}  ; optional, for scoping"
  [{:keys [task-ids directory]}]
  (when (seq task-ids)
    (doseq [task-id task-ids]
      (try
        ;; Use emacsclient to call the kanban move function
        (let [dir-arg (if directory
                        (str "\"" directory "\"")
                        "nil")
              elisp (format "(json-encode (hive-mcp-api-kanban-move %s \"done\" %s))"
                            (str "\"" task-id "\"")
                            dir-arg)
              {:keys [success error timed-out]} (ec/eval-elisp-with-timeout elisp 10000)]
          (cond
            timed-out (log/warn "[EVENT] Kanban move for" task-id "timed out (10s) - continuing")
            success   (log/info "[EVENT] Kanban task moved to done:" task-id)
            :else     (log/warn "[EVENT] Kanban move failed for" task-id ":" error)))
        (catch Exception e
          (log/error "[EVENT] Kanban move error for" task-id ":" (.getMessage e)))))))

;; =============================================================================
;; Effect: :report-metrics
;; =============================================================================

(defn- handle-report-metrics
  "Execute a :report-metrics effect - report metrics to external system.

   Reports current event system metrics for observability.
   Currently supports :log destination. Future: :prometheus, :statsd.

   Expected data shape:
   {:destination :log | :prometheus | :statsd}  ; default: :log"

  [{:keys [destination] :or {destination :log}}]
  (let [metrics (ev/get-metrics)]
    (case destination
      :log (log/info "[METRICS] Event system metrics:" metrics)
      ;; Future: :prometheus, :statsd
      (log/warn "[METRICS] Unknown metrics destination:" destination))))

;; =============================================================================
;; Effect: :tool-registry-refresh
;; =============================================================================

(defn- handle-tool-registry-refresh
  "Execute a :tool-registry-refresh effect - refresh tool handlers after hot-reload.

   Uses requiring-resolve to avoid compile-time dependency on agent.registry."
  [_]
  (try
    (let [refresh-fn (requiring-resolve 'hive-mcp.agent.registry/refresh!)]
      (refresh-fn))
    (catch Exception e
      (log/error "[hot-reload] Failed to refresh tool registry:" (ex-message e)))))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-infrastructure-effects!
  "Register all infrastructure effect handlers.

   Effects registered:
   - :ds-transact            - DataScript transactions
   - :git-commit             - Stage files and create git commit
   - :kanban-sync            - Synchronize kanban state
   - :kanban-move-done       - Move kanban tasks to done
   - :report-metrics         - Report metrics
   - :tool-registry-refresh  - Refresh tool handlers after hot-reload

   Called from hive-mcp.events.effects/register-effects!"
  []
  (ev/reg-fx :ds-transact handle-ds-transact)
  (ev/reg-fx :git-commit handle-git-commit)
  (ev/reg-fx :kanban-sync handle-kanban-sync)
  (ev/reg-fx :kanban-move-done handle-kanban-move-done)
  (ev/reg-fx :report-metrics handle-report-metrics)
  (ev/reg-fx :tool-registry-refresh handle-tool-registry-refresh)
  (log/info "[hive-events.infrastructure] Infrastructure effects registered: :ds-transact :git-commit :kanban-sync :kanban-move-done :report-metrics :tool-registry-refresh"))
