(ns hive-mcp.emacs.daemon-redistribution
  "Ling redistribution across Emacs daemons.

   Redistributes lings from overloaded/degraded daemons to healthier ones.
   Unlike W3 (autoheal) which handles DEAD daemons, redistribution handles
   ALIVE but stressed daemons — the Emacs process is still running but is
   degraded or overloaded.

   Redistribution is conservative:
   1. Only moves IDLE lings (never interrupts working lings)
   2. Only migrates when the target daemon is significantly better
   3. Caps migrations per cycle to avoid thundering herd
   4. Respects project affinity (prefers same-project daemons)

   DDD: Domain Service — stateless redistribution algorithm.
   Separated from daemon-selection (SRP: selection vs redistribution)."
  (:require [hive-mcp.emacs.daemon :as daemon]
            [hive-mcp.emacs.daemon-scoring :as scoring]
            [hive-mcp.emacs.daemon-selection :as selection]
            [hive-mcp.swarm.datascript.connection :as conn]
            [datascript.core :as d]
            [taoensso.timbre :as log]
            [hive-mcp.dns.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const redistribution-score-threshold
  "Minimum score improvement to justify a migration.
   Prevents churn from marginal improvements."
  20)

(def ^:const max-migrations-per-cycle
  "Maximum number of lings to redistribute in a single cycle.
   Prevents thundering herd when many lings need to move."
  2)

(def ^:const overloaded-ling-threshold
  "A daemon is considered overloaded if it has this many or more lings.
   Set to max-lings-per-daemon - 1 to trigger before hard cap."
  4)


;; =============================================================================
;; Detection
;; =============================================================================

(defn find-overloaded-daemons
  "Find active daemons that are degraded or overloaded.

   A daemon qualifies for redistribution when:
   - Status is :active (still alive — dead daemons are handled by W3 autoheal)
   - AND either:
     a) Health level is :degraded (score 30-69)
     b) Ling count >= overloaded-ling-threshold (near capacity)

   Arguments:
     store - IEmacsDaemon store instance

   Returns:
     Seq of daemon maps that are candidates for offloading, or nil if none."
  [store]
  (let [all-daemons (daemon/get-all-daemons store)
        active (filter #(= :active (:emacs-daemon/status %)) all-daemons)
        overloaded (filter
                    (fn [d]
                      (let [health (or (:emacs-daemon/health-score d) scoring/default-health-score)
                            level (scoring/health-level health)
                            ling-count (selection/daemon-ling-count d)]
                        (and (pos? ling-count)
                             (or (= :degraded level)
                                 (>= ling-count overloaded-ling-threshold)))))
                    active)]
    (seq overloaded)))

(defn find-migration-candidates
  "Find idle lings on an overloaded daemon that can be migrated.

   Only idle lings are candidates — working/blocked lings are left alone
   to avoid task interruption.

   Arguments:
     daemon - Daemon map (from get-all-daemons)

   Returns:
     Seq of maps with :ling-id, :project-id, :ling-status.
     Empty if no eligible lings."
  [daemon]
  (let [ling-ids (or (:emacs-daemon/lings daemon) #{})
        daemon-id (:emacs-daemon/id daemon)
        c (conn/ensure-conn)
        db @c]
    (->> ling-ids
         (map (fn [ling-id]
                (when-let [e (d/entity db [:slave/id ling-id])]
                  {:ling-id    ling-id
                   :daemon-id  daemon-id
                   :project-id (:slave/project-id e)
                   :ling-status (:slave/status e)})))
         (filter some?)
         (filter #(= :idle (:ling-status %))))))


;; =============================================================================
;; Planning
;; =============================================================================

(defn plan-redistribution
  "Create a redistribution plan: which lings move where.

   For each overloaded daemon, finds idle lings and scores potential
   target daemons. Only plans migrations that improve by at least
   `redistribution-score-threshold` points.

   Arguments:
     store - IEmacsDaemon store instance

   Returns:
     Seq of migration plan maps:
       {:ling-id, :source-daemon, :target-daemon, :source-score, :target-score, :improvement}
     Or nil if no beneficial migrations exist."
  [store]
  (when-let [overloaded (find-overloaded-daemons store)]
    (let [all-daemons (daemon/get-all-daemons store)
          plans (for [source-d overloaded
                      :let [source-id (:emacs-daemon/id source-d)
                            source-score (:score (selection/score-daemon source-d nil))
                            candidates (find-migration-candidates source-d)]
                      candidate candidates
                      :let [project-id (:project-id candidate)
                            targets (->> all-daemons
                                         (remove #(= source-id (:emacs-daemon/id %)))
                                         (map #(selection/score-daemon % project-id))
                                         (remove :disqualified?)
                                         (sort-by :score #(compare %2 %1)))
                            best-target (first targets)]
                      :when best-target
                      :let [improvement (- (:score best-target) (max 0 source-score))]
                      :when (>= improvement redistribution-score-threshold)]
                  {:ling-id       (:ling-id candidate)
                   :project-id    project-id
                   :source-daemon source-id
                   :target-daemon (:daemon-id best-target)
                   :source-score  source-score
                   :target-score  (:score best-target)
                   :improvement   improvement})]
      (when (seq plans)
        (->> plans
             (sort-by :improvement #(compare %2 %1))
             (take max-migrations-per-cycle)
             vec)))))


;; =============================================================================
;; Execution
;; =============================================================================

(defn migrate-ling!
  "Execute a single ling migration from one daemon to another.

   Steps:
   1. Verify ling is still idle (guard against race conditions)
   2. Unbind from source daemon
   3. Bind to target daemon
   4. Log the migration

   Arguments:
     store     - IEmacsDaemon store instance
     migration - Migration plan map from plan-redistribution

   Returns:
     Map with :success?, :ling-id, :source-daemon, :target-daemon, :reason"
  [store {:keys [ling-id source-daemon target-daemon]}]
  (let [c (conn/ensure-conn)
        db @c]
    (if-let [e (d/entity db [:slave/id ling-id])]
      (if (= :idle (:slave/status e))
        (do
          (daemon/unbind-ling! store source-daemon ling-id)
          (daemon/bind-ling! store target-daemon ling-id)
          (log/info "W4 redistribute: Migrated ling" ling-id
                    "from" source-daemon "to" target-daemon)
          {:success?      true
           :ling-id       ling-id
           :source-daemon source-daemon
           :target-daemon target-daemon})
        (do
          (log/debug "W4 redistribute: Skipping" ling-id
                     "- status changed to" (:slave/status e))
          {:success?      false
           :ling-id       ling-id
           :source-daemon source-daemon
           :target-daemon target-daemon
           :reason        :no-longer-idle}))
      {:success?      false
       :ling-id       ling-id
       :source-daemon source-daemon
       :target-daemon target-daemon
       :reason        :ling-not-found})))

(defn redistribute-lings!
  "Main entry point: plan and execute ling redistribution.

   Called from the heartbeat loop to proactively rebalance lings
   across daemons when some become degraded or overloaded.

   Arguments:
     store - IEmacsDaemon store instance

   Returns:
     Map with :migrations-planned, :migrations-executed, :results
     Or nil if no redistribution needed."
  [store]
  (when-let [plan (plan-redistribution store)]
    (log/info "W4 redistribute: Planning" (count plan) "migrations"
              (mapv #(select-keys % [:ling-id :source-daemon :target-daemon :improvement]) plan))
    (let [results (mapv #(migrate-ling! store %) plan)
          executed (count (filter :success? results))
          failed (- (count results) executed)]
      (rescue nil (when-let [emit-fn (requiring-resolve 'hive-mcp.transport.olympus/emit-agent-event!)]
          (emit-fn :daemon/lings-redistributed
                   {:planned (count plan)
                    :executed executed
                    :failed failed
                    :details (mapv #(select-keys % [:ling-id :source-daemon :target-daemon :success?])
                                   results)})))
      (when (pos? executed)
        (log/info "W4 redistribute: Executed" executed "of" (count plan) "migrations"
                  (when (pos? failed) (str "(" failed " failed)"))))
      {:migrations-planned  (count plan)
       :migrations-executed executed
       :migrations-failed   failed
       :results             results})))

(defn redistribution-status
  "Get current redistribution status without executing. Monitoring endpoint.

   Arguments:
     store - IEmacsDaemon store instance

   Returns:
     Map with :overloaded-daemons, :planned-migrations, or nil if balanced."
  [store]
  (let [overloaded (find-overloaded-daemons store)
        plan (when overloaded (plan-redistribution store))]
    (when overloaded
      {:overloaded-count (count overloaded)
       :overloaded-daemons (mapv (fn [d]
                                   {:daemon-id (:emacs-daemon/id d)
                                    :health-score (or (:emacs-daemon/health-score d)
                                                      scoring/default-health-score)
                                    :health-level (scoring/health-level
                                                   (or (:emacs-daemon/health-score d)
                                                       scoring/default-health-score))
                                    :ling-count (selection/daemon-ling-count d)})
                                 overloaded)
       :planned-migrations (or plan [])
       :migrations-available (count (or plan []))})))
