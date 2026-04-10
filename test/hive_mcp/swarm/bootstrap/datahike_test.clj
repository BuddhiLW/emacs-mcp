(ns hive-mcp.swarm.bootstrap.datahike-test
  "Tests for DatahikeBootstrap — persistent slave projection.

   Uses the hive-test trifecta macro (deftest-facets) with :expr golden
   facets to snapshot the observable outcome of each stateful scenario.
   Each scenario runs in its own temp dir (created via
   Files/createTempDirectory) and tears the store down afterwards.

   Scenarios covered:
   - fresh store → load returns []
   - snapshot → load round-trip preserves fields
   - upsert: re-snapshot with new status overwrites
   - forget → removes only the target
   - persistence across two make-datahike-bootstrap instances"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [hive-test.trifecta :refer [deftest-facets]]
            [hive-mcp.swarm.bootstrap.datahike :as dh]
            [hive-mcp.swarm.bootstrap.protocol :as proto])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Helpers (temp dirs, scenario runners)
;; =============================================================================

(defn- tmp-dir
  "Return a unique, NON-EXISTING sub-path under a fresh temp directory.
   Datahike's file backend expects the target directory to NOT exist yet
   (it creates it itself); passing an empty pre-existing dir causes
   `File store already exists at path` on create-database."
  ^String []
  (let [parent (Files/createTempDirectory "hive-swarm-bs-"
                                          (into-array FileAttribute []))]
    (str (.resolve ^java.nio.file.Path parent "db"))))

(defn- delete-recursively!
  "Best-effort recursive delete — tests leave nothing behind."
  [^String path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (doseq [child (reverse (file-seq f))]
          (try (.delete ^java.io.File child) (catch Exception _ nil)))))))

(defn- normalize-slave
  "Strip non-deterministic keys so golden snapshots are stable.
   The record does not emit timestamps via entity->slave, so the
   map shape should already be stable — this is a safety net."
  [slave]
  (-> slave
      (select-keys [:slave-id :name :status :depth :cwd :project-id :parent-id])))

(defn- sort-slaves [xs]
  (->> xs (map normalize-slave) (sort-by :slave-id) vec))

(defn- run-scenario
  "Execute a scenario function against a fresh DatahikeBootstrap in a
   scratch directory. Returns the scenario's result. Cleans up always."
  [scenario-fn]
  (let [dir (tmp-dir)]
    (try
      (scenario-fn dir)
      (finally
        (delete-recursively! dir)))))

;; =============================================================================
;; Scenario 1 — fresh store: load returns []
;; =============================================================================

(deftest-facets fresh-store-loads-empty
  identity
  {:type :golden
   :path "test/golden/swarm/bootstrap/fresh-store-loads-empty.edn"
   :expr (hive-mcp.swarm.bootstrap.datahike-test/run-scenario
           (fn [dir]
             (let [bs (dh/make-datahike-bootstrap {:db-path dir})]
               (try
                 {:loaded (vec (proto/-load-slaves bs))}
                 (finally (proto/-close! bs))))))})

;; =============================================================================
;; Scenario 2 — snapshot → load round-trip
;; =============================================================================

(deftest-facets snapshot-load-roundtrip
  identity
  {:type :golden
   :path "test/golden/swarm/bootstrap/snapshot-load-roundtrip.edn"
   :expr (hive-mcp.swarm.bootstrap.datahike-test/run-scenario
           (fn [dir]
             (let [bs (dh/make-datahike-bootstrap {:db-path dir})]
               (try
                 (proto/-snapshot-slave! bs "ling-1"
                                         {:name "worker-1"
                                          :status :idle
                                          :depth 1
                                          :cwd "/tmp/proj-a"
                                          :project-id "proj-a"
                                          :parent-id "root"})
                 (hive-mcp.swarm.bootstrap.datahike-test/sort-slaves
                   (proto/-load-slaves bs))
                 (finally (proto/-close! bs))))))})

;; =============================================================================
;; Scenario 3 — upsert: second snapshot with different status wins
;; =============================================================================

(deftest-facets upsert-latest-status-wins
  identity
  {:type :golden
   :path "test/golden/swarm/bootstrap/upsert-latest-status-wins.edn"
   :expr (hive-mcp.swarm.bootstrap.datahike-test/run-scenario
           (fn [dir]
             (let [bs (dh/make-datahike-bootstrap {:db-path dir})]
               (try
                 (proto/-snapshot-slave! bs "ling-X"
                                         {:name "x" :status :idle
                                          :depth 1 :cwd "/tmp/x"
                                          :project-id "p-x"})
                 (proto/-snapshot-slave! bs "ling-X"
                                         {:name "x" :status :working
                                          :depth 1 :cwd "/tmp/x"
                                          :project-id "p-x"})
                 (hive-mcp.swarm.bootstrap.datahike-test/sort-slaves
                   (proto/-load-slaves bs))
                 (finally (proto/-close! bs))))))})

;; =============================================================================
;; Scenario 4 — forget removes only the target
;; =============================================================================

(deftest-facets forget-removes-target-only
  identity
  {:type :golden
   :path "test/golden/swarm/bootstrap/forget-removes-target-only.edn"
   :expr (hive-mcp.swarm.bootstrap.datahike-test/run-scenario
           (fn [dir]
             (let [bs (dh/make-datahike-bootstrap {:db-path dir})]
               (try
                 (proto/-snapshot-slave! bs "keep-1"
                                         {:name "keep" :status :idle
                                          :depth 1 :cwd "/k" :project-id "p"})
                 (proto/-snapshot-slave! bs "drop-1"
                                         {:name "drop" :status :idle
                                          :depth 1 :cwd "/d" :project-id "p"})
                 (proto/-forget-slave! bs "drop-1")
                 (hive-mcp.swarm.bootstrap.datahike-test/sort-slaves
                   (proto/-load-slaves bs))
                 (finally (proto/-close! bs))))))})

;; =============================================================================
;; Scenario 5 — persistence across two bootstrap instances on the same dir
;;
;; The whole point of "bootstrap": the durable projection must survive a
;; process restart. We simulate that by closing the first record and
;; opening a second against the same path.
;; =============================================================================

(deftest-facets persists-across-instances
  identity
  {:type :golden
   :path "test/golden/swarm/bootstrap/persists-across-instances.edn"
   :expr (hive-mcp.swarm.bootstrap.datahike-test/run-scenario
           (fn [dir]
             ;; Process 1: write then close
             (let [bs1 (dh/make-datahike-bootstrap {:db-path dir})]
               (try
                 (proto/-snapshot-slave! bs1 "persist-1"
                                         {:name "persisted" :status :idle
                                          :depth 2 :cwd "/tmp/persist"
                                          :project-id "proj-persist"
                                          :parent-id "root"})
                 (finally (proto/-close! bs1))))
             ;; Process 2: reopen, read what Process 1 wrote
             (let [bs2 (dh/make-datahike-bootstrap {:db-path dir})]
               (try
                 (hive-mcp.swarm.bootstrap.datahike-test/sort-slaves
                   (proto/-load-slaves bs2))
                 (finally (proto/-close! bs2))))))})
