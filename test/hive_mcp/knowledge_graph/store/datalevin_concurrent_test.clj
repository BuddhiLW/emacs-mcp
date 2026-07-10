;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.store.datalevin-concurrent-test
  "Linearizability regression for the konserve-race incident (2026-05-07).

   The konserve filestore .ksv.new -> .ksv atomic-rename pattern corrupted
   datahike under the 51-scope concurrent carto scan. STORAGE-1 moves the
   `:carto` slot onto Datalevin (LMDB) precisely because LMDB has no
   rename race — concurrent writers serialize through the LMDB transactor
   without filestore-level interference.

   This spec is the bottom turtle: N threads issue concurrent
   `transact!` calls against a single DatalevinStore. Invariant: every
   submitted edge must be durably present in the final db snapshot
   (no lost writes, no exceptions, no half-commits)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as dtlv]
            [hive-mcp.knowledge-graph.store.datalevin :as dl]
            [hive-mcp.protocols.kg :as pkg]
            [hive-test.linearizability :as lin])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir!
  "Fresh isolated LMDB directory per test iteration."
  []
  (-> (Files/createTempDirectory "datalevin-concurrent-" (make-array FileAttribute 0))
      .toFile
      .getAbsolutePath))

(defn- delete-recursive!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursive! c)))
  (.delete f))

(defn- gen-edge-id
  "Deterministic-ish edge id; the property generator drives uniqueness."
  [n]
  (format "edge-conc-%010d" n))

(defn- mk-edge
  [n]
  {:kg-edge/id         (gen-edge-id n)
   :kg-edge/from       (str "from-" n)
   :kg-edge/to         (str "to-" n)
   :kg-edge/relation   :implements
   :kg-edge/scope      "scope:test"
   :kg-edge/confidence 1.0
   :kg-edge/created-at (java.util.Date.)})

(defn- count-edges-with-prefix
  [store prefix]
  (count
    (pkg/query store
               '[:find ?id
                 :in $ ?p
                 :where
                 [?e :kg-edge/id ?id]
                 [(clojure.string/starts-with? ?id ?p)]]
               [prefix])))

(deftest concurrent-transact-no-lost-writes
  (testing "4 threads × 25 transacts each — every edge persisted, no exceptions"
    (let [path     (temp-dir!)
          store    (dl/create-store {:db-path path})
          n-thread 4
          per      25
          total    (* n-thread per)
          ops      (vec (range total))
          chunks   (partition-all per ops)]
      (try
        (pkg/ensure-conn! store)
        (lin/run-concurrent
          chunks
          (fn [_state n]
            (pkg/transact! store [(mk-edge n)]))
          store
          15000)
        (let [hit-count (count-edges-with-prefix store "edge-conc-")]
          (is (= total hit-count)
              (str "Every concurrent transact must be durable. "
                   "expected=" total " actual=" hit-count)))
        (finally
          (pkg/close! store)
          (delete-recursive! (java.io.File. path)))))))

(defspec concurrent-transact-totality 5
  (prop/for-all
    [ids (gen/such-that
           #(= (count %) (count (distinct %)))
           (gen/vector (gen/choose 1000 9999) 8 32))]
    (let [path  (temp-dir!)
          store (dl/create-store {:db-path path})]
      (try
        (pkg/ensure-conn! store)
        (let [chunks (partition-all (max 1 (quot (count ids) 4)) ids)]
          (lin/run-concurrent
            chunks
            (fn [_state n]
              (pkg/transact! store [(mk-edge n)]))
            store
            10000))
        (= (count ids)
           (count-edges-with-prefix store "edge-conc-"))
        (finally
          (pkg/close! store)
          (delete-recursive! (java.io.File. path)))))))
