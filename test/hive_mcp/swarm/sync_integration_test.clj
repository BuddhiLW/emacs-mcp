(ns hive-mcp.swarm.sync-integration-test
  "End-to-end test: channel.core event → swarm/sync handler → both the
   in-memory Datascript registry AND the DatahikeBootstrap durable
   projection converge.

   Uses the hive-test trifecta macro (deftest-facets) with :expr golden
   facets — each scenario is a self-contained stateful run whose final
   observable state is snapshotted."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [hive-test.trifecta :refer [deftest-facets]]
            [hive-mcp.swarm.sync :as sync]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.bootstrap.datahike :as dh]
            [hive-mcp.swarm.bootstrap.protocol :as proto]
            [hive-mcp.channel.core :as channel])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-dir
  "Non-existing sub-path under a fresh temp directory — Datahike's file
   backend insists on creating the target dir itself."
  ^String []
  (let [parent (Files/createTempDirectory "hive-sync-it-"
                                          (into-array FileAttribute []))]
    (str (.resolve ^java.nio.file.Path parent "db"))))

(defn- delete-recursively! [^String path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (doseq [child (reverse (file-seq f))]
          (try (.delete ^java.io.File child) (catch Exception _ nil)))))))

(defn- wait-until
  "Poll pred up to timeout-ms; return true if it became truthy."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 20) (recur))))))

(defn- normalize-slave [s]
  (when s
    (cond-> {}
      (:slave-id s)   (assoc :slave-id (:slave-id s))
      (:name s)       (assoc :name (:name s))
      (:status s)     (assoc :status (:status s))
      (:depth s)      (assoc :depth (:depth s))
      (:project-id s) (assoc :project-id (:project-id s)))))

(defn- sort-slaves [xs]
  (->> xs (map normalize-slave) (sort-by :slave-id) vec))

;; =============================================================================
;; Scenario runner: full sync pipeline with a fresh Datahike store + a
;; restarted sync subscription. We drive the pipeline by publishing events
;; onto channel.core and let the go-loop handlers in sync.clj run.
;; =============================================================================

(defn- with-bootstrap-and-sync
  [body-fn]
  (let [dir (tmp-dir)
        bs (dh/make-datahike-bootstrap {:db-path dir})
        prev (sync/get-swarm-bootstrap)]
    (try
      ;; Clean slate for Datascript + bootstrap + sync loop
      (ds/reset-conn!)
      ;; Pre-warm the Datahike connection BEFORE the async go-loop can
      ;; race on ensure-conn! (see production note: concurrent first-write
      ;; from multiple handlers can double-create the file store).
      (proto/-load-slaves bs)
      (sync/set-swarm-bootstrap! bs)
      ;; Start sync WITHOUT running full-sync-from-bootstrap! (would re-read
      ;; the freshly-wiped store and do nothing, but we'd rather be explicit).
      (sync/start-sync! {:bootstrap? false})
      (body-fn bs)
      (finally
        (try (sync/stop-sync!) (catch Exception _ nil))
        (try (sync/set-swarm-bootstrap! prev) (catch Exception _ nil))
        (ds/reset-conn!)
        (delete-recursively! dir)))))

;; =============================================================================
;; Scenario 1 — :slave-spawned propagates to Datascript AND to Datahike
;; =============================================================================

(deftest-facets slave-spawned-converges-both-sides
  identity
  {:type :golden
   :path "test/golden/swarm/sync-integration/spawned-converges.edn"
   :expr (hive-mcp.swarm.sync-integration-test/with-bootstrap-and-sync
           (fn [bs]
             (channel/publish! {:type :slave-spawned
                                :slave-id "it-ling-1"
                                :name "integration-worker"
                                :depth 1
                                :cwd "/tmp/it-proj"})
             ;; Wait for BOTH registries to converge — the Datascript
             ;; write and the bootstrap write-through run from the same
             ;; go-loop but happen in sequence.
             (hive-mcp.swarm.sync-integration-test/wait-until
               #(and (some? (ds/get-slave "it-ling-1"))
                     (seq (proto/-load-slaves bs)))
               2000)
             {:in-datascript? (some? (ds/get-slave "it-ling-1"))
              :in-bootstrap  (hive-mcp.swarm.sync-integration-test/sort-slaves
                               (proto/-load-slaves bs))}))})

;; =============================================================================
;; Scenario 2 — :slave-killed removes from Datascript AND from Datahike
;; =============================================================================

(deftest-facets slave-killed-forgets-both-sides
  identity
  {:type :golden
   :path "test/golden/swarm/sync-integration/killed-forgets.edn"
   :expr (hive-mcp.swarm.sync-integration-test/with-bootstrap-and-sync
           (fn [bs]
             ;; First spawn two lings
             (channel/publish! {:type :slave-spawned
                                :slave-id "it-keep-1"
                                :name "keep"
                                :depth 1
                                :cwd "/tmp/it-keep"})
             (channel/publish! {:type :slave-spawned
                                :slave-id "it-drop-1"
                                :name "drop"
                                :depth 1
                                :cwd "/tmp/it-drop"})
             (hive-mcp.swarm.sync-integration-test/wait-until
               #(and (some? (ds/get-slave "it-keep-1"))
                     (some? (ds/get-slave "it-drop-1"))
                     (= 2 (count (proto/-load-slaves bs))))
               2000)
             ;; Then kill one
             (channel/publish! {:type :slave-killed
                                :slave-id "it-drop-1"})
             (hive-mcp.swarm.sync-integration-test/wait-until
               #(and (nil? (ds/get-slave "it-drop-1"))
                     (= 1 (count (proto/-load-slaves bs))))
               2000)
             {:ds-keep?   (some? (ds/get-slave "it-keep-1"))
              :ds-drop?   (some? (ds/get-slave "it-drop-1"))
              :bootstrap  (hive-mcp.swarm.sync-integration-test/sort-slaves
                            (proto/-load-slaves bs))}))})
