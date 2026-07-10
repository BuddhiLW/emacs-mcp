(ns hive-mcp.swarm.lifecycle.boot-reconcile-test
  "Tests for boot-time JVM-restart reconciliation of the swarm slave registry.

   Covers the bug where DatahikeBootstrap rehydrates slaves as :working every
   boot and nothing reaps them (see kanban 20260521150600-4ff6891d). Honors
   decision 20260423152822-70fe5631 (lifecycle over pruning)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.datascript.queries :as q]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.sync :as sync]
            [hive-mcp.swarm.lifecycle.boot-reconcile :as br]
            [hive-mcp.agent.ling.terminal-registry :as terminal-reg]
            [hive-test.isolation :as iso]
            hive-mcp.isolation-methods))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each (iso/with-isolations :swarm-ds))

;; =============================================================================
;; reconcile-rehydrated-slaves!
;; =============================================================================

(deftest reconcile-retires-live-looking-slaves
  (testing "rehydrated :working/:idle slaves are marked :zombie + :alive? false"
    (ds/add-slave! "swarm-w" {:status :working})
    (ds/add-slave! "swarm-i" {:status :idle})
    (let [result (br/reconcile-rehydrated-slaves!)]
      (is (= 2 (:reconciled result)))
      (is (= 0 (:skipped result)))
      (doseq [id ["swarm-w" "swarm-i"]]
        (let [s (q/get-slave id)]
          (is (= :zombie (:slave/status s)) (str id " should be :zombie"))
          (is (false? (:slave/alive? s)) (str id " should be :alive? false"))
          (is (some? (:slave/status-changed-at s)) (str id " should be timestamped")))))))

(deftest reconcile-skips-already-dead-slaves
  (testing "slaves already in a terminal status are left untouched"
    (ds/add-slave! "swarm-dead" {:status :zombie})
    (ds/add-slave! "swarm-live" {:status :working})
    (let [result (br/reconcile-rehydrated-slaves!)]
      (is (= 1 (:reconciled result)) "only the live-looking slave is reconciled")
      (is (= 1 (:skipped result)) "the :zombie slave is skipped"))))

(deftest reconcile-is-idempotent
  (testing "a second reconcile is a no-op (records already retired)"
    (ds/add-slave! "swarm-a" {:status :working})
    (ds/add-slave! "swarm-b" {:status :initializing})
    (br/reconcile-rehydrated-slaves!)
    (let [again (br/reconcile-rehydrated-slaves!)]
      (is (= 0 (:reconciled again)))
      (is (= 2 (:skipped again))))))

(deftest reconcile-keeps-records-not-pruned
  (testing "reconciliation never deletes rows — they remain queryable history"
    (ds/add-slave! "swarm-keep" {:status :working})
    (br/reconcile-rehydrated-slaves!)
    (is (= 1 (count (q/get-all-slaves :include-stale? true)))
        "row still present (episodic log per vision 20260424123042-58151f4f)")
    (is (zero? (count (q/get-all-slaves)))
        "but hidden from the default (active/idle) agent-status view")))

;; =============================================================================
;; reconcile spares re-probable / live-backed rows (the :ling/spawn-mode fix)
;; =============================================================================

(deftest reconcile-spares-terminal-backed-slaves
  (testing "rehydrated terminal-mode vessels are spared for the terminal sweep, not zombified"
    (with-redefs [terminal-reg/registered-terminals (constantly #{:vterm})]
      (ds/add-slave! "swarm-vterm" {:status :working})
      (ds-lings/update-slave! "swarm-vterm" {:ling/spawn-mode :vterm})
      (ds/add-slave! "swarm-headless" {:status :working}) ; no terminal mode, nil pid
      (let [result (br/reconcile-rehydrated-slaves!)]
        (is (= 1 (:reconciled result)) "only the non-terminal slave is zombified")
        (is (= 1 (:spared result)) "the vterm vessel is spared")
        (is (not= :zombie (:slave/status (q/get-slave "swarm-vterm")))
            "vterm vessel left for the periodic terminal sweep")
        (is (= :zombie (:slave/status (q/get-slave "swarm-headless")))
            "headless/in-JVM slave zombified")))))

(deftest reconcile-spares-live-pid-slaves
  (testing "a rehydrated slave whose OS pid is still alive is spared; a dead pid is zombied"
    (with-redefs [terminal-reg/registered-terminals (constantly #{})]
      (let [live-pid (.pid (java.lang.ProcessHandle/current))]
        (ds/add-slave! "swarm-livepid" {:status :working})
        (ds-lings/update-slave! "swarm-livepid" {:slave/process-pid live-pid})
        (ds/add-slave! "swarm-deadpid" {:status :working})
        (ds-lings/update-slave! "swarm-deadpid" {:slave/process-pid 999999})
        (let [result (br/reconcile-rehydrated-slaves!)]
          (is (= 1 (:spared result)) "the live-pid slave is spared")
          (is (not= :zombie (:slave/status (q/get-slave "swarm-livepid")))
              "live-pid subprocess survived the JVM — spared")
          (is (= :zombie (:slave/status (q/get-slave "swarm-deadpid")))
              "dead-pid slave zombified"))))))

;; =============================================================================
;; purge-test-junk!  (datascript side only — durable side stubbed out)
;; =============================================================================

(deftest purge-removes-test-junk-keeps-real-lings
  (testing "throwaway test/probe artifacts are retracted; real work lings kept"
    ;; durable bootstrap stubbed to nil so the test never touches real datahike
    (with-redefs [sync/get-swarm-bootstrap (constantly nil)]
      (doseq [id ["test-anthropic" "probe-spawn-123" "minimal-ling"
                  "facade-headless" "spawn-test-001" "lifecycle-test-9"
                  "swarm-real-work-1" "forja-cl-42"]]
        (ds/add-slave! id {:status :working}))
      (let [result (br/purge-test-junk!)
            remaining (set (map :slave/id (q/get-all-slaves :include-stale? true)))]
        (is (= 6 (:count result)) "six junk artifacts purged")
        (is (= #{"swarm-real-work-1" "forja-cl-42"} remaining)
            "only real work lings remain")))))
