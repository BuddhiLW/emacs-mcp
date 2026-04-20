(ns hive-mcp.emacs.daemon-autoheal
  "Auto-heal orphan cleanup for multi-daemon architecture (W3).

   Detects lings bound to dead/unhealthy daemons (orphans) and either:
   1. Rebinds them to a healthy daemon (if idle)
   2. Marks working lings' tasks as failed and releases claims (graceful termination)

   Integration: Called from daemon_store.clj heartbeat loop on each cycle.

   ADR-010: Compute isolation, not data isolation. DataScript is unified —
   orphan cleanup works across all daemons from a single coordinator view."

  (:require [hive-mcp.emacs.daemon :as daemon]
            [hive-mcp.emacs.daemon-selection :as selection]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.swarm.datascript.lings :as lings]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const unhealthy-daemon-statuses
  "Daemon statuses that indicate a daemon is dead or non-functional.
   Lings bound to daemons in these states are considered orphaned."
  #{:stale :terminated :error})

(defn find-orphaned-lings
  "Find all lings that are bound to dead/unhealthy daemons.

   An orphaned ling is one whose daemon has status in #{:stale :terminated :error}.
   This indicates the Emacs process has crashed, is unresponsive, or was shut down.

   Arguments:
     store - IEmacsDaemon store instance

   Returns:
     Seq of maps with:
       :ling-id      - The orphaned ling's slave ID
       :daemon-id    - The daemon it was bound to
       :daemon-status - Why it's orphaned (:stale/:terminated/:error)
       :ling-status  - Current ling status (:idle/:working/:etc)
       :project-id   - Ling's project ID (for rebinding affinity)"
  [store]
  (let [all-daemons (daemon/get-all-daemons store)
        dead-daemons (filter #(contains? unhealthy-daemon-statuses
                                         (:emacs-daemon/status %))
                             all-daemons)]
    (when (seq dead-daemons)
      (->> dead-daemons
           (mapcat
            (fn [d]
              (let [daemon-id (:emacs-daemon/id d)
                    daemon-status (:emacs-daemon/status d)
                    ling-ids (or (:emacs-daemon/lings d) #{})]
                (map (fn [ling-id]
                       (let [slave (queries/get-slave ling-id)]
                         {:ling-id       ling-id
                          :daemon-id     daemon-id
                          :daemon-status daemon-status
                          :ling-status   (or (:slave/status slave) :unknown)
                          :project-id    (:slave/project-id slave)}))
                     ling-ids))))
           (seq)))))

(defn classify-orphan
  "Classify an orphaned ling for healing action.

   Arguments:
     orphan - Map from find-orphaned-lings

   Returns:
     :rebind     - Ling is idle, can be rebound to healthy daemon
     :terminate  - Ling is working/blocked, task must be failed and ling terminated
     :skip       - Ling is already terminated or in error, no action needed"
  [{:keys [ling-status]}]
  (case ling-status
    (:idle :initializing :spawning :starting)  :rebind
    (:working :blocked)                        :terminate
    (:error :terminated)                       :skip
    ;; Unknown status — be conservative, try to terminate
    :terminate))

(defn- rebind-ling!
  "Rebind an idle ling from a dead daemon to a healthy one.

   1. Unbind from dead daemon
   2. Select best healthy daemon via daemon-selection
   3. Bind to new daemon

   Arguments:
     store      - IEmacsDaemon store instance
     orphan     - Orphan map from find-orphaned-lings

   Returns:
     Map with :action :rebind, :new-daemon-id, :success?"
  [store {:keys [ling-id daemon-id project-id]}]
  (let [;; Find a healthy daemon to rebind to
        selection-result (selection/select-daemon store {:project-id project-id})
        new-daemon-id (:daemon-id selection-result)
        reason (:reason selection-result)]
    (if (and new-daemon-id
             (not= reason :all-disqualified)
             (not= new-daemon-id daemon-id))
      ;; Found a healthy daemon — rebind
      (do
        (daemon/unbind-ling! store daemon-id ling-id)
        (daemon/bind-ling! store new-daemon-id ling-id)
        (log/info "Auto-heal: Rebound ling" ling-id
                  "from dead daemon" daemon-id
                  "to healthy daemon" new-daemon-id)
        {:action       :rebind
         :ling-id      ling-id
         :old-daemon   daemon-id
         :new-daemon-id new-daemon-id
         :success?     true})
      ;; No healthy daemon available — cannot rebind
      (do
        (log/warn "Auto-heal: Cannot rebind ling" ling-id
                  "- no healthy daemons available (reason:" reason ")")
        {:action       :rebind
         :ling-id      ling-id
         :old-daemon   daemon-id
         :new-daemon-id nil
         :success?     false
         :reason       :no-healthy-daemon}))))

(defn- terminate-orphan!
  "Gracefully terminate a working orphan ling.

   Since the daemon is dead, the ling's Emacs process is gone.
   We clean up the DataScript state:
   1. Fail any active tasks (releases claims via lings/fail-task!)
   2. Release remaining claims
   3. Unbind from dead daemon
   4. Mark ling as :terminated

   Arguments:
     store  - IEmacsDaemon store instance
     orphan - Orphan map from find-orphaned-lings

   Returns:
     Map with :action :terminate, :tasks-failed, :claims-released"
  [store {:keys [ling-id daemon-id]}]
  (let [;; Find and fail active tasks for this ling
        active-tasks (queries/get-tasks-for-slave ling-id :dispatched)
        tasks-failed (count active-tasks)]
    ;; Fail each active task (this also releases task-specific claims)
    (doseq [task active-tasks]
      (when-let [task-id (:task/id task)]
        (log/warn "Auto-heal: Failing task" task-id "for orphan ling" ling-id)
        (lings/fail-task! task-id :error)))
    ;; Release any remaining claims not tied to tasks
    (let [claims-released (lings/release-claims-for-slave! ling-id)]
      ;; Unbind from dead daemon
      (daemon/unbind-ling! store daemon-id ling-id)
      ;; Mark ling as terminated in DataScript
      (lings/update-slave! ling-id {:slave/status :terminated})
      (log/warn "Auto-heal: Terminated orphan ling" ling-id
                "from dead daemon" daemon-id
                "tasks-failed:" tasks-failed
                "claims-released:" claims-released)
      {:action          :terminate
       :ling-id         ling-id
       :daemon-id       daemon-id
       :tasks-failed    tasks-failed
       :claims-released claims-released
       :success?        true})))

(defn heal-orphan!
  "Heal a single orphaned ling based on its classification.

   Arguments:
     store  - IEmacsDaemon store instance
     orphan - Orphan map from find-orphaned-lings

   Returns:
     Map with healing action result"
  [store orphan]
  (let [action (classify-orphan orphan)]
    (case action
      :rebind    (rebind-ling! store orphan)
      :terminate (terminate-orphan! store orphan)
      :skip      (do
                   (log/debug "Auto-heal: Skipping ling" (:ling-id orphan)
                              "- already in" (:ling-status orphan) "state")
                   ;; Still unbind from dead daemon for cleanup
                   (daemon/unbind-ling! store (:daemon-id orphan) (:ling-id orphan))
                   {:action  :skip
                    :ling-id (:ling-id orphan)
                    :reason  (:ling-status orphan)}))))

(defn heal-all-orphans!
  "Detect and heal all orphaned lings across all daemons.

   This is the main entry point called from the heartbeat loop.
   Scans all daemons for dead/stale/error status, finds their bound lings,
   and heals each one.

   Arguments:
     store - IEmacsDaemon store instance

   Returns:
     Map with:
       :orphans-found  - Total orphaned lings detected
       :healed         - Number successfully healed (rebound or terminated)
       :failed         - Number that failed to heal
       :results        - Seq of individual healing results
     Or nil if no orphans found."
  [store]
  (when-let [orphans (find-orphaned-lings store)]
    (log/warn "Auto-heal: Found" (count orphans) "orphaned lings across"
              (count (distinct (map :daemon-id orphans))) "dead daemons")
    (let [results (mapv #(heal-orphan! store %) orphans)
          healed  (count (filter :success? results))
          failed  (- (count results) healed)]
      ;; Emit event for Olympus visibility
      (rescue nil (when-let [emit-fn (requiring-resolve 'hive-mcp.transport.olympus/emit-agent-event!)]
          (emit-fn :daemon/orphans-healed
                   {:orphans-found (count orphans)
                    :healed healed
                    :failed failed
                    :details (mapv #(select-keys % [:ling-id :action :success?]) results)})))
      {:orphans-found (count orphans)
       :healed        healed
       :failed        failed
       :results       results})))

(defn orphan-status
  "Get current orphan status without healing. Useful for monitoring.

   Arguments:
     store - IEmacsDaemon store instance

   Returns:
     Map with :orphan-count, :orphans (detailed list), or nil if no orphans"
  [store]
  (when-let [orphans (find-orphaned-lings store)]
    {:orphan-count (count orphans)
     :orphans      (mapv (fn [o]
                           (assoc o :action (classify-orphan o)))
                         orphans)}))

(comment
  ;; Usage examples

  ;; Check for orphans
  ;; (orphan-status (daemon-store/get-store))

  ;; Heal all orphans
  ;; (heal-all-orphans! (daemon-store/get-store))

  ;; Find and classify
  ;; (let [store (daemon-store/get-store)
  ;;       orphans (find-orphaned-lings store)]
  ;;   (mapv #(assoc % :action (classify-orphan %)) orphans))
  )
