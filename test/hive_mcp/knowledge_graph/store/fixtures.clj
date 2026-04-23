(ns hive-mcp.knowledge-graph.store.fixtures
  "Shared test fixtures for dual-backend KG testing.

   Provides fixtures that run tests against both DataScript (in-memory)
   and Datalevin (temp directory, cleaned after test).

   Usage in test ns:
     (use-fixtures :each (fixtures/backend-fixture :datascript))
     ;; or for dual-backend:
     (use-fixtures :each fixtures/dual-backend-fixture)"
  (:require [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.datascript :as ds-store]
            [hive-mcp.knowledge-graph.edges :as edges]
            [clojure.java.io :as io]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Backend-Specific Fixtures
;; =============================================================================

(defn datascript-fixture
  "Fixture that runs test f with a fresh DataScript store."
  [f]
  (let [store (ds-store/create-store)]
    (proto/set-store! store)
    (proto/ensure-conn! store)
    (edges/reset-stats-cache!)
    (try
      (f)
      (finally
        (proto/reset-conn! store)
        (edges/reset-stats-cache!)))))

(defn datalevin-fixture
  "Fixture that runs test f with a fresh Datalevin store in a temp dir.
   Cleans up the temp directory after the test."
  [f]
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "hive-kg-test-" (System/nanoTime)))
        db-path (.getAbsolutePath tmp-dir)]
    (try
      ;; Dynamically require datalevin store to avoid hard dep
      (require 'hive-mcp.knowledge-graph.store.datalevin)
      (let [create-fn (resolve 'hive-mcp.knowledge-graph.store.datalevin/create-store)
            store (create-fn {:db-path db-path})]
        (proto/set-store! store)
        (proto/ensure-conn! store)
        (edges/reset-stats-cache!)
        (try
          (f)
          (finally
            (proto/close! store)
            (edges/reset-stats-cache!)
            ;; Clean up temp directory
            (when (.exists tmp-dir)
              (doseq [file (reverse (file-seq tmp-dir))]
                (.delete file))))))
      (catch Exception e
        (println "Datalevin fixture failed, skipping:" (.getMessage e))))))

(defn datahike-fixture
  "Fixture that runs test f with a fresh Datahike store in a temp dir.
   Cleans up the temp directory after the test."
  [f]
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "hive-kg-datahike-test-" (System/nanoTime)))
        db-path (.getAbsolutePath tmp-dir)]
    (try
      ;; Dynamically require datahike store to avoid hard dep
      (require 'hive-mcp.knowledge-graph.store.datahike)
      (let [create-fn (resolve 'hive-mcp.knowledge-graph.store.datahike/create-store)
            store (create-fn {:db-path db-path})]
        (proto/set-store! store)
        (proto/ensure-conn! store)
        (edges/reset-stats-cache!)
        (try
          (f)
          (finally
            (proto/close! store)
            (edges/reset-stats-cache!)
            ;; Clean up temp directory
            (when (.exists tmp-dir)
              (doseq [file (reverse (file-seq tmp-dir))]
                (.delete file))))))
      (catch Exception e
        (println "Datahike fixture failed, skipping:" (.getMessage e))))))

;; =============================================================================
;; Dual-Backend Fixture
;; =============================================================================

(def ^:dynamic *current-backend*
  "Currently active backend for test reporting.
   Bound during dual-backend test execution."
  :datascript)

(defn backend-fixture
  "Returns a fixture for a specific backend.
   backend - :datascript, :datalevin, or :datahike"
  [backend]
  (case backend
    :datascript datascript-fixture
    :datalevin datalevin-fixture
    :datahike datahike-fixture))

(defn dual-backend-fixture
  "Fixture that runs each test against BOTH backends.
   DataScript runs first (fast, in-memory), then Datalevin (temp dir).
   Test failures report which backend failed."
  [f]
  ;; Run with DataScript
  (binding [*current-backend* :datascript]
    (datascript-fixture f))
  ;; Run with Datalevin
  (binding [*current-backend* :datalevin]
    (datalevin-fixture f)))
