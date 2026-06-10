(ns hive-mcp.swarm.lifecycle.boot-reconcile
  "Boot-time JVM-restart reconciliation for the swarm slave registry.

   When the hive-mcp JVM restarts, `DatahikeBootstrap` rehydrates every persisted
   slave back into the in-memory DataScript registry with its last-saved status
   (often :working / :idle). But the process that owned those agents is gone — per
   the Agent Lifecycle decision (hive memory `20260423152822-70fe5631`: `* ->
   :historical` on JVM restart), they are dead. This pass marks every rehydrated
   slave `:zombie` + `:alive? false` so they are correctly retired (and hidden from
   default `agent status`) instead of masquerading as live workers. Their rows stay
   in datahike as an append-only episodic log (vision `20260424123042-58151f4f`) —
   we reconcile, we do NOT prune (\"lifecycle over pruning\").

   Runs ONCE at boot from `server/init.clj start-swarm-sync!`, AFTER
   `swarm.sync/start-sync!` has rehydrated the registry and BEFORE the server
   accepts new spawns — so every slave present is, by definition, rehydrated from a
   prior (dead) process. Idempotent: already-dead rows are skipped, so re-runs and
   repeated boots are no-ops.

   Terminal-backed vessels (Emacs/tmux/vterm) and subprocesses with a live OS
   pid can outlive the JVM, so they are SPARED here and left to the periodic
   terminal-liveness sweep; only headless/in-JVM rows with no live backing are
   zombified. Spawn-mode + pid round-trip through `bootstrap/datahike.clj`."
  (:require [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.sync :as sync]
            [hive-mcp.swarm.bootstrap.factory :as bootstrap]
            [hive-mcp.agent.ling.terminal-registry :as terminal-reg]
            [hive-system.process.liveness :as liveness]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def dead-statuses
  "Statuses that already represent a terminal/retired slave — left untouched
   so reconciliation is idempotent."
  #{:zombie :terminated :error})

(defn- live-looking?
  "A rehydrated row still claims to be alive — i.e. it would masquerade as a
   working/idle agent. `:slave/alive? false` or a terminal status means it is
   already retired."
  [slave]
  (and (not (false? (:slave/alive? slave)))
       (not (contains? dead-statuses (:slave/status slave)))))

(defn- backed-by-live-process?
  "True when the slave carries an OS pid that is still alive. nil/unknown pid
   is NOT alive (degrade-safe)."
  [slave]
  (= :liveness/alive (:adt/variant (liveness/check-pid-alive (:slave/process-pid slave)))))

(defn- spare?
  "A live-looking rehydrated slave we must NOT zombify because something may
   still back it: a terminal vessel whose mode is a registered terminal
   (reaped by the periodic terminal-liveness sweep if actually dead), or a
   subprocess whose OS pid is still alive."
  [registered-terminals slave]
  (or (contains? registered-terminals (:ling/spawn-mode slave))
      (backed-by-live-process? slave)))

(defn reconcile-rehydrated-slaves!
  "Retire still-live-looking rehydrated slaves on JVM restart (per decision
   `20260423152822-70fe5631`): mark `:zombie` + `:alive? false`. Terminal-backed
   and live-pid slaves are spared (see `spare?`) and left to the periodic sweeps.
   Idempotent. Returns {:reconciled N :spared S :skipped M :total T}."
  []
  (let [now        (System/currentTimeMillis)
        registered (terminal-reg/registered-terminals)
        slaves     (queries/get-all-slaves :include-stale? true)
        live       (filterv live-looking? slaves)
        {spared true to-retire false} (group-by #(boolean (spare? registered %)) live)]
    (doseq [s to-retire]
      (ds-lings/update-slave! (:slave/id s)
                              {:slave/status            :zombie
                               :slave/alive?            false
                               :slave/status-changed-at now}))
    (let [result {:reconciled (count to-retire)
                  :spared     (count spared)
                  :skipped    (- (count slaves) (count live))
                  :total      (count slaves)}]
      (log/info "Boot reconciliation: retired rehydrated slaves (JVM restart)" result)
      result)))

;; =============================================================================
;; One-time test-junk purge (maintenance helper — NOT boot-wired)
;; =============================================================================

(def ^:private junk-patterns
  "Slave id/name prefixes that only ever name throwaway test/probe artifacts."
  [#"^test-" #"^spawn-test" #"^spawn-task" #"^probe-spawn-"
   #"^lifecycle-test" #"^spawned-via-event"])

(def ^:private junk-exact
  "Exact throwaway ling ids used by tests/probes."
  #{"minimal-ling" "parent-ling" "child-ling" "kanban-ling"
    "facade-headless" "facade-terminal"})

(defn- junk?
  [id-or-name]
  (when id-or-name
    (let [s (str id-or-name)]
      (boolean (or (contains? junk-exact s)
                   (some #(re-find % s) junk-patterns))))))

(defn purge-test-junk!
  "ONE-TIME cleanup: hard-retract throwaway test/probe ling artifacts from BOTH
   the DataScript registry and the durable datahike bootstrap store, so they do
   not rehydrate next boot. Matches `junk-patterns`/`junk-exact` on `:slave/id`
   or `:slave/name`. Real work lings (swarm-*, forja-*) are NOT touched — they
   are kept as episodic history (already reconciled to :zombie).

   Maintenance tool, deliberately not wired into boot (auto-deleting by name
   pattern every boot could nuke a future legitimately-named ling).
   Returns {:purged [ids] :count N}."
  []
  (let [bs     (sync/get-swarm-bootstrap)
        slaves (queries/get-all-slaves :include-stale? true)
        ids    (->> slaves
                    (filter (fn [s] (or (junk? (:slave/id s)) (junk? (:slave/name s)))))
                    (mapv :slave/id))]
    (doseq [id ids]
      (ds-lings/remove-slave! id)                       ; DataScript registry
      (when bs
        (try (bootstrap/forget-slave! bs id)            ; durable datahike store
             (catch Throwable t
               (log/warn "purge-test-junk!: datahike forget failed for" id ":" (.getMessage t))))))
    (log/info "purge-test-junk!: retracted throwaway lings" {:count (count ids) :ids ids})
    {:purged ids :count (count ids)}))
