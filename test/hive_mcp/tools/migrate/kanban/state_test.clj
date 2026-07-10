(ns hive-mcp.tools.migrate.kanban.state-test
  "Round-trip + atomicity tests for the file-backed IState adapter.
   Uses tmp-dir fixtures so production state is never touched."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-mcp.tools.migrate.kanban.ports :as ports]
            [hive-mcp.tools.migrate.kanban.state :as state]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- tmp-state-path []
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/kanban-mig-state-test-" (System/nanoTime))]
    (.mkdirs (io/file dir))
    (str dir "/state.edn")))

(defn- with-tmp-state [f]
  (let [path (tmp-state-path)
        s    (state/make path)]
    (try
      (f s path)
      (finally
        (let [parent (.getParentFile (io/file path))]
          (doseq [child (.listFiles parent)] (.delete child))
          (.delete parent))))))

;; =============================================================================
;; load-state on a fresh path returns initial-state
;; =============================================================================

(deftest load-state-returns-initial-when-file-missing
  (with-tmp-state
    (fn [s _path]
      (let [result (ports/load-state s)]
        (is (r/ok? result))
        (is (= state/initial-state (:ok result)))))))

;; =============================================================================
;; save → load round-trips
;; =============================================================================

(deftest save-load-round-trip
  (with-tmp-state
    (fn [s _path]
      (let [payload  (assoc state/initial-state
                            :phase   :running
                            :cursor  42
                            :all-ids ["a" "b" "c"])
            saved    (ports/save-state! s payload)
            loaded   (ports/load-state s)]
        (is (r/ok? saved))
        (is (r/ok? loaded))
        (is (= payload (:ok loaded)))))))

;; =============================================================================
;; save persists EDN (no spurious objects)
;; =============================================================================

(deftest save-writes-readable-edn
  (with-tmp-state
    (fn [s path]
      (ports/save-state! s (assoc state/initial-state :cursor 7))
      (let [body (slurp path)]
        (is (re-find #":cursor 7" body))
        (is (re-find #":phase :ready" body))))))

;; =============================================================================
;; reset clears state to initial
;; =============================================================================

(deftest reset-state-restores-initial
  (with-tmp-state
    (fn [s _path]
      (ports/save-state! s (assoc state/initial-state :cursor 100 :phase :done))
      (let [reset-result (ports/reset-state! s)
            after        (ports/load-state s)]
        (is (r/ok? reset-result))
        (is (= state/initial-state (:ok after)))))))

;; =============================================================================
;; atomic-spit cleanup: no .tmp residue after a successful save
;; =============================================================================

(deftest no-tmp-residue-after-save
  (with-tmp-state
    (fn [s path]
      (ports/save-state! s (assoc state/initial-state :cursor 1))
      (let [tmp-file (io/file (str path ".tmp"))]
        (is (not (.exists tmp-file))
            "atomic-spit must rename tmp → target")))))

;; =============================================================================
;; sequential saves don't corrupt the file (read-after-write)
;; =============================================================================

(deftest sequential-saves-stay-consistent
  (with-tmp-state
    (fn [s _path]
      (doseq [i (range 5)]
        (ports/save-state! s (assoc state/initial-state :cursor i)))
      (let [{:keys [ok]} (ports/load-state s)]
        (is (= 4 (:cursor ok)))))))
