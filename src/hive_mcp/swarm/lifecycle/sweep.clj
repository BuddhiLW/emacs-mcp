(ns hive-mcp.swarm.lifecycle.sweep
  "Boot-time liveness sweep for swarm slave registry.

   On boot, datalevin :slave rows accumulate across sessions because no
   reconciliation runs against OS process state. This sweep walks all slaves
   with a non-nil :slave/process-pid, asks `hive-system.process.liveness`
   whether the OS process is still alive, and marks dead processes whose
   last activity is older than the stale threshold (default 5 minutes) as
   :zombie + :alive? false.

   Liveness signals are typed via the LivenessSignal ADT in
   `hive-system.process.liveness`. :liveness/unknown (nil pid OR transient
   error) is the safe degraded value — never marks :zombie.

   Idempotency: the row-enumeration query filters out :slave/alive? false,
   so subsequent sweeps don't re-touch already-zombied rows."
  (:require [datalevin.core :as dl]
            [hive-system.process.liveness :as liveness]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-stale-threshold-ms
  "Slaves with last-active-at older than this AND a dead OS process are
   marked :zombie. Default 5 minutes."
  (* 5 60 1000))

(defn check-pid-alive?
  "Return a LivenessSignal ADT value for the given pid.
   Thin wrapper over `hive-system.process.liveness/check-pid-alive`
   retained for backwards-compat with existing callers.

     nil pid                 → :liveness/unknown   (e.g. openrouter ling)
     non-integer pid         → :liveness/unknown
     transient error/throw   → :liveness/unknown   (degrade, do NOT zombify)
     OS handle alive         → :liveness/alive
     OS handle absent        → :liveness/dead"
  [pid]
  (liveness/check-pid-alive pid))

(defn- emit-zombified!
  "Dispatch :lifecycle/zombified via hive-mcp.events.core/dispatch.
   Guarded: only emits when a handler is registered (boot races)."
  [slave-id last-active-at at]
  (when-let [reg? (requiring-resolve 'hive-mcp.events.core/handler-registered?)]
    (when (reg? :lifecycle/zombified)
      (let [dispatch (requiring-resolve 'hive-mcp.events.core/dispatch)]
        (dispatch [:lifecycle/zombified
                   {:slave-id       slave-id
                    :last-active-at last-active-at
                    :at             at}])))))

(defn- live-slave-rows
  "All :slave rows with :slave/process-pid set and :slave/alive? not false.
   Legacy rows (no :slave/alive? attr) treated as alive — get-else default."
  [db]
  (dl/q '[:find ?sid ?pid ?last-act
          :where
          [?e :slave/id ?sid]
          [?e :slave/process-pid ?pid]
          [(get-else $ ?e :slave/alive? true) ?alive]
          [(true? ?alive)]
          [(get-else $ ?e :slave/last-active-at 0) ?last-act]]
        db))

(defn sweep-once!
  "Walk slaves with non-nil :slave/process-pid; mark dead+stale as :zombie.

   Transacts {:slave/alive? false :slave/status :zombie
              :slave/status-changed-at now} per zombified row,
   emits :lifecycle/zombified events.

   Returns {:checked N :zombified M :alive K :unknown U}.
   (N - M - K - U) = dead-but-recent rows left untouched."
  ([conn] (sweep-once! conn default-stale-threshold-ms))
  ([conn stale-threshold-ms]
   (let [now      (System/currentTimeMillis)
         rows     (live-slave-rows (dl/db conn))
         signals  (mapv (fn [[sid pid last-act]]
                          {:slave-id sid
                           :last-act last-act
                           :variant  (:adt/variant (check-pid-alive? pid))
                           :stale?   (< last-act (- now stale-threshold-ms))})
                        rows)
         to-zomb  (filterv #(and (= :liveness/dead (:variant %)) (:stale? %)) signals)
         tx-data  (mapv (fn [{:keys [slave-id]}]
                          {:slave/id                slave-id
                           :slave/alive?            false
                           :slave/status            :zombie
                           :slave/status-changed-at now})
                        to-zomb)]
     (when (seq tx-data) (dl/transact! conn tx-data))
     (doseq [{:keys [slave-id last-act]} to-zomb]
       (emit-zombified! slave-id last-act now))
     {:checked   (count rows)
      :zombified (count to-zomb)
      :alive     (count (filter #(= :liveness/alive (:variant %)) signals))
      :unknown   (count (filter #(= :liveness/unknown (:variant %)) signals))})))

(defn sweep-on-boot!
  "Public entry. Logs result. Wired into hive-mcp startup after the datalevin
   connection is open (see server/init.clj start-swarm-sync!)."
  ([conn] (sweep-on-boot! conn default-stale-threshold-ms))
  ([conn stale-threshold-ms]
   (let [result (sweep-once! conn stale-threshold-ms)]
     (log/info "Slave liveness sweep complete:" result)
     result)))
