(ns hive-mcp.swarm.lifecycle.terminal-sweep
  "Liveness sweep for terminal-backed lings.

   Terminal lings do not always have an OS pid in DataScript, so the pid-based
   boot sweep cannot detect closed Emacs/vterm/tmux buffers. This sweep asks the
   registered terminal strategy for status and marks confirmed-dead rows as
   :zombie + :slave/alive? false so default agent status filters hide them.

   When the terminal probe itself errors (e.g. emacsclient unreachable), we
   fall back to a `kill -0 :slave/process-pid` check. A dead pid is a strong
   dead signal even when the addon channel is degraded — without this fallback
   ghost rows accumulate forever every time Emacs restarts."
  (:require [hive-mcp.agent.ling.spawn :as ling]
            [hive-mcp.agent.ling.terminal-registry :as terminal-reg]
            [hive-mcp.agent.protocol :as agent]
            [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.system.registry :as reg]
            [hive-system.process.liveness :as liveness]
            [taoensso.timbre :as log]))

(def ^:const default-sweep-interval-s 60)

(defn- terminal-slave?
  [registered-terminals slave]
  (let [mode (:ling/spawn-mode slave)]
    (and (= 1 (:slave/depth slave))
         (not= false (:slave/alive? slave))
         (not= :zombie (:slave/status slave))
         (contains? registered-terminals mode))))

(defn- dead-status?
  [status]
  (or (= :dead (:slave/status status))
      (false? (:slave/alive? status))
      (false? (:elisp-alive? status))
      (false? (:tmux-alive? status))
      (false? (:terminal-alive? status))))

(defn- pid-fallback-dead?
  "When the addon probe fails, fall back to a system-level liveness check
   on `:slave/process-pid`. Routes through `hive-system.process.liveness`
   (single source of truth for `is this pid alive?` across hive). Returns
   true only on a confirmed-dead pid; nil pid or :unknown stays soft and
   does NOT zombify."
  [slave]
  (liveness/dead? (:slave/process-pid slave)))

(defn- probe-terminal
  [slave]
  (try
    (let [slave-id (:slave/id slave)
          ling (ling/->ling slave-id (ling/slave->ling-opts slave))
          status (agent/status ling)]
      {:slave slave
       :status status
       :dead? (dead-status? status)})
    (catch Throwable t
      (log/warn t "terminal liveness sweep: status probe failed"
                {:slave-id (:slave/id slave)
                 :mode (:ling/spawn-mode slave)})
      (let [pid-dead? (pid-fallback-dead? slave)]
        (cond-> {:slave slave
                 :error (.getMessage t)}
          pid-dead? (assoc :dead? true
                           :status {:slave/status :dead
                                    :pid-fallback? true}))))))

(defn- mark-zombie!
  [now-ms {:keys [slave status]}]
  (let [slave-id (:slave/id slave)]
    (ds-lings/update-slave! slave-id
                            {:slave/alive? false
                             :slave/status :zombie
                             :slave/status-changed-at now-ms})
    {:slave-id slave-id
     :mode (:ling/spawn-mode slave)
     :terminal-status (:slave/status status)}))

(defn sweep-once!
  "Probe registered terminal-backed lings once.

   Returns {:checked N :zombified M :alive K :errors [...]}. Missing terminal
   registrations are ignored so unloaded addons do not cause false zombies."
  ([] (sweep-once! (System/currentTimeMillis)))
  ([now-ms]
   (let [registered-terminals (terminal-reg/registered-terminals)
         slaves (->> (queries/get-all-slaves :include-stale? true)
                     (filter (partial terminal-slave? registered-terminals))
                     vec)
         results (mapv probe-terminal slaves)
         dead (filterv :dead? results)
         zombies (mapv (partial mark-zombie! now-ms) dead)
         errors (into [] (keep :error) results)]
     {:checked (count slaves)
      :zombified (count zombies)
      :alive (- (count slaves) (count dead) (count errors))
      :errors errors})))

(defrecord TerminalLivenessSweep []
  lifecycle/ISweepable
  (sweep-interval-s [_] default-sweep-interval-s)
  (sweep-name [_] "lings/terminal-liveness")
  (sweep! [_ {:keys [now-ms]}]
    (let [result (sweep-once! (or now-ms (System/currentTimeMillis)))]
      {:swept (:checked result)
       :errors (:errors result)
       :result result})))

(defonce registered?
  (do (reg/register-sweep! (->TerminalLivenessSweep)) true))
