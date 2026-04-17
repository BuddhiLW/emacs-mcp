(ns hive-mcp.tools.swarm.jvm
  "JVM process management and resource guard tools for orphan cleanup and OOM prevention."
  (:require [hive-mcp.tools.core :refer [mcp-error mcp-json]]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [taoensso.timbre :as log]
            [hive-mcp.tools.swarm.jvm.classifier :as classifier]
            [hive-mcp.tools.swarm.jvm.orphan :as orphan]
            [hive-mcp.tools.swarm.jvm.memory :as memory]
            [hive-mcp.tools.swarm.jvm.summary :as summary] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- kill-orphans!
  "Kill orphan processes, returning list of killed PIDs."
  [orphans]
  (doseq [{:keys [pid]} orphans]
    (try
      (shell/sh "kill" pid)
      (catch Exception e
        (log/warn "Failed to kill PID" pid ":" (.getMessage e)))))
  (map :pid orphans))

(defn handle-jvm-cleanup
  "Find and optionally kill orphaned JVM processes."
  [{:keys [min_age_minutes dry_run keep_types swarm_only true_orphans_only]}]
  (try
    (let [min-age (or min_age_minutes 30)
          dry-run (if (nil? dry_run) true dry_run)
          keep-types-set (set (or keep_types ["shadow-cljs" "leiningen"]))
          swarm-only (boolean swarm_only)
          true-orphans-only (if (nil? true_orphans_only) true true_orphans_only)

          all-procs (classifier/find-jvm-processes)
          all-parents (classifier/get-all-process-parents)
          classified (->> all-procs
                          (map classifier/classify-jvm-process)
                          (map #(orphan/enrich-with-parent-info % all-parents)))

          working-procs (if swarm-only
                          (filter :swarm-spawned classified)
                          classified)

          all-classified (orphan/identify-orphans working-procs
                                                  :protected-types keep-types-set
                                                  :true-orphans-only true-orphans-only
                                                  :min-age-minutes min-age)

          orphans (filter :orphan all-classified)

          killed-pids (when (and (not dry-run) (seq orphans))
                        (kill-orphans! orphans))

          result (summary/build-cleanup-summary
                  all-procs classified all-classified orphans killed-pids
                  {:dry-run dry-run
                   :swarm-only swarm-only
                   :min-age min-age
                   :true-orphans-only true-orphans-only})]

      (mcp-json result))
    (catch Exception e
      (mcp-error (str "Error during JVM cleanup: " (.getMessage e))))))

(defn handle-resource-guard
  "Check system resources and auto-clean orphaned JVMs if memory is high."
  [{:keys [ram_threshold min_available_mb auto_cleanup cleanup_dry_run]}]
  (try
    (let [threshold (or ram_threshold 80)
          min-available (or min_available_mb 2048)
          auto-clean (if (nil? auto_cleanup) true auto_cleanup)
          cleanup-dry (if (nil? cleanup_dry_run) false cleanup_dry_run)

          initial-mem (memory/get-memory-usage)

          _ (when (:error initial-mem)
              (throw (Exception. (str "Cannot read memory: " (:error initial-mem)))))

          initial-high? (memory/memory-high? initial-mem threshold min-available)

          cleanup-result (when (and initial-high? auto-clean)
                           (log/info "Memory high (" (:percent-used initial-mem) "%), running jvm_cleanup...")
                           (handle-jvm-cleanup {:dry_run cleanup-dry
                                                :true_orphans_only true}))

          cleanup-data (when cleanup-result
                         (rescue nil (json/read-str (:text cleanup-result) :key-fn keyword)))

          orphans-killed (when cleanup-data (count (:killed cleanup-data)))
          final-mem (if (and cleanup-data (pos? (or orphans-killed 0)))
                      (do
                        (Thread/sleep 500)
                        (memory/get-memory-usage))
                      initial-mem)

          final-high? (memory/memory-high? final-mem threshold min-available)

          result (summary/build-resource-guard-summary
                  (not final-high?) initial-mem final-mem threshold min-available
                  initial-high? final-high?
                  {:auto-clean auto-clean :cleanup-dry cleanup-dry}
                  cleanup-data)]

      (mcp-json result))
    (catch Exception e
      (mcp-error (str "Resource guard error: " (.getMessage e))))))
