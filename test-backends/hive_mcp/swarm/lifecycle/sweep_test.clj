(ns hive-mcp.swarm.lifecycle.sweep-test
  "Tests for the boot-time slave liveness sweep.

   Uses a real datalevin conn against a tmp directory so the sweep's
   dl/q + dl/transact! exercise actual LMDB schema constraints."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as dl]
            [hive-mcp.swarm.datalevin.schema :as schema]
            [hive-mcp.swarm.lifecycle.sweep :as sweep])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const stale-threshold-ms (* 5 60 1000))

(defn- tmp-dir []
  (.toString (Files/createTempDirectory "sweep-test-"
                                        (into-array FileAttribute []))))

(defn- delete-recursively [^File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively c)))
  (.delete f))

(defn- this-jvm-pid []
  (.pid (java.lang.ProcessHandle/current)))

(def ^:const dead-pid
  "PID highly unlikely to ever be running on this host. kill -0 returns
   exit 1 (No such process)."
  999999)

(def ^:dynamic *conn* nil)

(defn- with-conn-fixture [f]
  (let [path (tmp-dir)
        c    (dl/get-conn path schema/schema)]
    (try
      (binding [*conn* c] (f))
      (finally
        (dl/close c)
        (delete-recursively (File. ^String path))))))

(use-fixtures :each with-conn-fixture)

;; =============================================================================
;; check-pid-alive?  — LivenessSignal classification
;; =============================================================================

(deftest check-pid-alive?-classifies-correctly
  (testing "nil pid → :liveness/unknown"
    (is (= :liveness/unknown (:adt/variant (sweep/check-pid-alive? nil)))))
  (testing "non-integer pid → :liveness/unknown"
    (is (= :liveness/unknown (:adt/variant (sweep/check-pid-alive? "abc")))))
  (testing "live pid (current JVM) → :liveness/alive"
    (is (= :liveness/alive   (:adt/variant (sweep/check-pid-alive? (this-jvm-pid))))))
  (testing "dead pid → :liveness/dead"
    (is (= :liveness/dead    (:adt/variant (sweep/check-pid-alive? dead-pid))))))

;; =============================================================================
;; sweep-once! — only dead+stale gets zombified
;; =============================================================================

(deftest sweep-once!-marks-only-dead+stale
  (let [now    (System/currentTimeMillis)
        recent now
        stale  (- now (* 10 60 1000))]
    (dl/transact! *conn*
                  [{:slave/id "alive-row"       :slave/process-pid (this-jvm-pid) :slave/last-active-at recent}
                   {:slave/id "dead-recent"     :slave/process-pid dead-pid       :slave/last-active-at recent}
                   {:slave/id "dead-stale"      :slave/process-pid dead-pid       :slave/last-active-at stale}
                   {:slave/id "no-pid"                                            :slave/last-active-at recent}])
    (let [result (sweep/sweep-once! *conn* stale-threshold-ms)
          db'    (dl/db *conn*)
          fetch  (fn [sid] (-> (dl/q '[:find (pull ?e [*]) .
                                       :in $ ?sid
                                       :where [?e :slave/id ?sid]]
                                     db' sid)))]
      (testing "no-pid row excluded from sweep"
        (is (= 3 (:checked result))))
      (testing "exactly one zombified"
        (is (= 1 (:zombified result))))
      (testing "exactly one classified alive"
        (is (= 1 (:alive result))))
      (testing "dead-stale flipped to :zombie + :alive? false"
        (let [s (fetch "dead-stale")]
          (is (false? (:slave/alive? s)))
          (is (= :zombie (:slave/status s)))
          (is (= now (:slave/status-changed-at s)))))
      (testing "alive row untouched"
        (is (nil? (:slave/alive? (fetch "alive-row")))))
      (testing "dead-recent row untouched"
        (is (nil? (:slave/alive? (fetch "dead-recent"))))
        (is (nil? (:slave/status (fetch "dead-recent"))))))))

;; =============================================================================
;; Property: idempotent — running sweep twice equals running it once
;; =============================================================================

(defspec sweep-is-idempotent 20
  (prop/for-all
   [n            (gen/choose 0 6)
    pid-choices  (gen/return [dead-pid dead-pid (this-jvm-pid)])
    age-choices  (gen/return [(* 1 60 1000) (* 30 60 1000)])]
   (let [path (tmp-dir)
         c    (dl/get-conn path schema/schema)]
     (try
       (let [now (System/currentTimeMillis)
             tx  (mapv (fn [i]
                         {:slave/id             (str "s-" i)
                          :slave/process-pid    (nth pid-choices (mod i (count pid-choices)))
                          :slave/last-active-at (- now (nth age-choices (mod i (count age-choices))))})
                       (range n))]
         (when (seq tx) (dl/transact! c tx))
         (let [r1 (sweep/sweep-once! c stale-threshold-ms)
               r2 (sweep/sweep-once! c stale-threshold-ms)]
           ;; r2 should see no NEW zombifications: already-zombied rows
           ;; are filtered by the live-slave-rows query.
           (and (= 0 (:zombified r2))
                (= (- (:checked r1) (:zombified r1)) (:checked r2)))))
       (finally
         (dl/close c)
         (delete-recursively (File. ^String path)))))))
