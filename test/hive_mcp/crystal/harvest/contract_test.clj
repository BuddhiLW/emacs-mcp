(ns hive-mcp.crystal.harvest.contract-test
  "Contract + property tests for concrete IHarvestSource implementations.

   Complements protocol_test.clj (which tests the protocol via stubs).
   This file tests the REAL source factories (memory-source, hivemind-source,
   kanban-source, git-source) under controlled mocks, verifying they honour
   the IHarvestSource contract.

   Contract tests (C1-C4):
   - C1: Every impl satisfies IHarvestSource
   - C2: harvest returns HarvestOutcome (ok/empty/error)
   - C3: source-id returns keyword
   - C4: available? returns boolean

   Property tests (P1):
   - P1: For any IHarvestSource, harvest never throws (returns :error variant)

   Fault isolation (F1):
   - F1: One source erroring doesn't affect others in harvest-all

   Wave 2, Crystal refactor."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.generators.core :as gen-core]
            [hive-dsl.adt :refer [adt-case]]
            [hive-mcp.crystal.harvest.protocol :as proto
             :refer [harvest-outcome? harvest-ok harvest-empty harvest-error]]
            [hive-mcp.crystal.harvest.sources :as sources]
            [hive-mcp.crystal.pipeline :as pipeline]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.tools.memory.scope :as scope]
            [taoensso.timbre :as log]
            [clojure.java.shell]))

;; =============================================================================
;; Mock infrastructure — deterministic deps for all sources
;; =============================================================================

(def ^:private mock-session-start
  (java.time.Instant/parse "2026-03-01T09:00:00Z"))

(defmacro ^:private with-source-mocks
  "Bind source deps to deterministic fakes. Accepts option overrides."
  [overrides & body]
  `(let [opts# ~overrides]
     (with-redefs
       [ctx/current-directory    (fn [] (get opts# :directory "/tmp/contract-test"))
        ctx/current-agent-id     (fn [] (get opts# :agent-id "contract-agent"))
        scope/get-current-project-id (fn [_#] (get opts# :project-id "contract-project"))
        crystal/get-session-start (fn [& _#] (get opts# :session-start mock-session-start))]
       ~@body)))

(def ^:private base-opts
  {:directory  "/tmp/contract-test"
   :agent-id   "contract-agent"
   :project-id "contract-project"})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- all-source-factories
  "Returns a map of source-kw -> source-factory-fn for each concrete impl."
  []
  {:memory   sources/memory-source
   :hivemind sources/hivemind-source
   :kanban   sources/kanban-source
   :git      sources/git-source})

(defn- make-all-sources
  "Instantiate all source factories, returning a map of kw -> source instance."
  []
  (into {} (map (fn [[k f]] [k (f)]) (all-source-factories))))

;; =============================================================================
;; C1 — Every impl satisfies IHarvestSource protocol
;; =============================================================================

(deftest c1-all-impls-satisfy-protocol
  (testing "Every source factory returns an IHarvestSource"
    (with-source-mocks {}
      (doseq [[kw src] (make-all-sources)]
        (testing (str "source " kw " satisfies IHarvestSource")
          (is (satisfies? proto/IHarvestSource src)
              (str kw " must satisfy IHarvestSource")))))))

;; =============================================================================
;; C2 — harvest returns HarvestOutcome for each source
;; =============================================================================

(deftest c2-memory-harvest-returns-outcome
  (testing "memory-source harvest returns HarvestOutcome"
    (with-source-mocks {}
      (with-redefs [;; Mock chroma query to return empty
                    clojure.core/requiring-resolve
                    (fn [sym]
                      (case (name sym)
                        "query-entries" (fn [& _] [])
                        (requiring-resolve sym)))]
        (let [src    (sources/memory-source)
              result (proto/harvest src base-opts)]
          (is (harvest-outcome? result))
          (is (contains? #{:harvest/ok :harvest/empty :harvest/error}
                         (:adt/variant result))))))))

(deftest c2-hivemind-harvest-returns-outcome
  (testing "hivemind-source harvest returns HarvestOutcome"
    (with-source-mocks {}
      (with-redefs [clojure.core/requiring-resolve
                    (fn [sym]
                      (case (name sym)
                        "fetch-history" (fn [& _] [])
                        (requiring-resolve sym)))]
        (let [src    (sources/hivemind-source)
              result (proto/harvest src base-opts)]
          (is (harvest-outcome? result))
          (is (contains? #{:harvest/ok :harvest/empty :harvest/error}
                         (:adt/variant result))))))))

(deftest c2-kanban-harvest-returns-outcome
  (testing "kanban-source harvest returns HarvestOutcome"
    (with-source-mocks {}
      (with-redefs [clojure.core/requiring-resolve
                    (fn [sym]
                      (case (name sym)
                        "get-completed-tasks-this-session" (fn [& _] [])
                        (requiring-resolve sym)))]
        (let [src    (sources/kanban-source)
              result (proto/harvest src base-opts)]
          (is (harvest-outcome? result))
          (is (contains? #{:harvest/ok :harvest/empty :harvest/error}
                         (:adt/variant result))))))))

(deftest c2-git-harvest-returns-outcome
  (testing "git-source harvest returns HarvestOutcome"
    (with-source-mocks {}
      (with-redefs [clojure.java.shell/sh
                    (fn [& _] {:exit 0 :out "" :err ""})]
        (let [src    (sources/git-source)
              result (proto/harvest src base-opts)]
          (is (harvest-outcome? result))
          (is (contains? #{:harvest/ok :harvest/empty :harvest/error}
                         (:adt/variant result))))))))

;; =============================================================================
;; C2+ — harvest returns correct variant on success vs empty
;; =============================================================================

(deftest c2-memory-ok-on-data
  (testing "memory-source returns :harvest/ok when notes exist"
    (with-source-mocks {}
      (with-redefs [clojure.core/requiring-resolve
                    (fn [sym]
                      (case (name sym)
                        "query-entries"
                        (fn [& _] [{:content "note1" :duration "ephemeral"}
                                    {:content "note2" :duration "ephemeral"}])
                        (requiring-resolve sym)))]
        (let [result (proto/harvest (sources/memory-source) base-opts)]
          (is (= :harvest/ok (:adt/variant result)))
          (is (= :memory (:source result)))
          (is (= 2 (get-in result [:data :count]))))))))

(deftest c2-git-ok-on-commits
  (testing "git-source returns :harvest/ok when commits exist"
    (with-source-mocks {}
      (with-redefs [clojure.java.shell/sh
                    (fn [& _] {:exit 0
                               :out "abc1234 first commit\ndef5678 second commit\n"
                               :err ""})]
        (let [result (proto/harvest (sources/git-source) base-opts)]
          (is (= :harvest/ok (:adt/variant result)))
          (is (= :git (:source result)))
          (is (= 2 (get-in result [:data :count]))))))))

(deftest c2-git-error-on-failure
  (testing "git-source returns :harvest/error when git fails"
    (with-source-mocks {}
      (with-redefs [clojure.java.shell/sh
                    (fn [& _] {:exit 128 :out "" :err "fatal: not a git repo"})]
        (let [result (proto/harvest (sources/git-source) base-opts)]
          (is (= :harvest/error (:adt/variant result)))
          (is (= :git (:source result))))))))

;; =============================================================================
;; C3 — source-id returns keyword for each impl
;; =============================================================================

(deftest c3-source-id-returns-keyword
  (testing "Every source's source-id is a keyword"
    (with-source-mocks {}
      (doseq [[expected-kw src] (make-all-sources)]
        (testing (str "source-id for " expected-kw)
          (let [id (proto/source-id src)]
            (is (keyword? id)
                (str expected-kw " source-id must be keyword, got " (type id)))
            (is (= expected-kw id)
                (str "expected " expected-kw " but got " id))))))))

;; =============================================================================
;; C4 — available? returns boolean for each impl
;; =============================================================================

(deftest c4-available-returns-boolean
  (testing "Every source's available? returns a boolean"
    (with-source-mocks {}
      (doseq [[kw src] (make-all-sources)]
        (testing (str "available? for " kw)
          (is (boolean? (proto/available? src))
              (str kw " available? must return boolean")))))))

;; =============================================================================
;; P1 — harvest never throws for any source (returns :error variant instead)
;; =============================================================================

(deftest p1-memory-harvest-never-throws
  (testing "memory-source harvest never throws even when dep explodes"
    (with-source-mocks {}
      (with-redefs [clojure.core/requiring-resolve
                    (fn [_] (throw (RuntimeException. "chroma down")))]
        (let [result (proto/harvest (sources/memory-source) base-opts)]
          (is (harvest-outcome? result)
              "must return HarvestOutcome even on exception")
          (is (= :harvest/error (:adt/variant result))))))))

(deftest p1-hivemind-harvest-never-throws
  (testing "hivemind-source harvest never throws even when dep explodes"
    (with-source-mocks {}
      (with-redefs [clojure.core/requiring-resolve
                    (fn [_] (throw (RuntimeException. "piggyback down")))]
        (let [result (proto/harvest (sources/hivemind-source) base-opts)]
          (is (harvest-outcome? result)
              "must return HarvestOutcome even on exception")
          (is (= :harvest/error (:adt/variant result))))))))

(deftest p1-kanban-harvest-never-throws
  (testing "kanban-source harvest never throws even when dep explodes"
    (with-source-mocks {}
      (with-redefs [clojure.core/requiring-resolve
                    (fn [_] (throw (RuntimeException. "datascript down")))]
        (let [result (proto/harvest (sources/kanban-source) base-opts)]
          (is (harvest-outcome? result)
              "must return HarvestOutcome even on exception")
          (is (= :harvest/error (:adt/variant result))))))))

(deftest p1-git-harvest-never-throws
  (testing "git-source harvest never throws even when shell explodes"
    (with-source-mocks {}
      (with-redefs [clojure.java.shell/sh
                    (fn [& _] (throw (RuntimeException. "git not installed")))]
        (let [result (proto/harvest (sources/git-source) base-opts)]
          (is (harvest-outcome? result)
              "must return HarvestOutcome even on exception")
          (is (= :harvest/error (:adt/variant result))))))))

;; Property: harvest with random opts never throws on any stub-wrapped source
(defspec p1-prop-harvest-total-over-all-impls 50
  (prop/for-all [dir gen-core/gen-non-blank-string
                 agent-id gen-core/gen-agent-id
                 project-id gen-core/gen-project-id]
    (with-source-mocks {}
      ;; Mock all deps to return empty — totality is the point, not data
      (with-redefs [clojure.core/requiring-resolve
                    (fn [_] (fn [& _] []))
                    clojure.java.shell/sh
                    (fn [& _] {:exit 0 :out "" :err ""})]
        (let [opts {:directory dir :agent-id agent-id :project-id project-id}]
          (every? (fn [[_kw src]]
                    (let [result (proto/harvest src opts)]
                      (harvest-outcome? result)))
                  (make-all-sources)))))))

;; =============================================================================
;; F1 — Fault isolation: one source erroring doesn't affect others
;; =============================================================================

(deftest f1-fault-isolation-in-harvest-all
  (testing "One source throwing doesn't prevent others from succeeding"
    (let [;; Two well-behaved sources
          ok-source-1 (reify proto/IHarvestSource
                        (source-id [_] :ok-1)
                        (harvest [_ _] (harvest-ok :ok-1 {:count 3} 10))
                        (available? [_] true))
          ok-source-2 (reify proto/IHarvestSource
                        (source-id [_] :ok-2)
                        (harvest [_ _] (harvest-ok :ok-2 {:count 7} 20))
                        (available? [_] true))
          ;; One source that explodes
          bomb-source (reify proto/IHarvestSource
                        (source-id [_] :bomb)
                        (harvest [_ _] (throw (RuntimeException. "kaboom")))
                        (available? [_] true))
          ;; pipeline/harvest-all uses pipeline/IHarvestSource, not proto/IHarvestSource
          ;; so we create pipeline-compatible sources
          ok-pipe-1 (reify pipeline/IHarvestSource
                      (pipeline/source-id [_] :ok-1)
                      (pipeline/harvest [_ _] {:count 3}))
          ok-pipe-2 (reify pipeline/IHarvestSource
                      (pipeline/source-id [_] :ok-2)
                      (pipeline/harvest [_ _] {:count 7}))
          bomb-pipe (reify pipeline/IHarvestSource
                      (pipeline/source-id [_] :bomb)
                      (pipeline/harvest [_ _] (throw (RuntimeException. "kaboom"))))
          results (pipeline/harvest-all [ok-pipe-1 bomb-pipe ok-pipe-2]
                                        {:directory "/tmp"}
                                        5000)]
      (is (= 3 (count results)) "all three sources should produce outcomes")
      ;; ok-1 and ok-2 should succeed
      (let [ok-results (filter #(= :harvest/ok (:adt/variant %)) results)
            err-results (filter #(= :harvest/error (:adt/variant %)) results)]
        (is (= 2 (count ok-results))
            "two sources should succeed despite one erroring")
        (is (= 1 (count err-results))
            "one source should produce an error outcome")
        (is (= :bomb (:source-id (first err-results)))
            "errored source should be :bomb")))))

(deftest f1-fault-isolation-all-error-still-returns
  (testing "Even when ALL sources error, harvest-all returns outcomes for each"
    (let [bombs (mapv (fn [i]
                        (let [kw (keyword (str "bomb-" i))]
                          (reify pipeline/IHarvestSource
                            (pipeline/source-id [_] kw)
                            (pipeline/harvest [_ _]
                              (throw (RuntimeException. (str "boom-" i)))))))
                      (range 3))
          results (pipeline/harvest-all bombs {} 5000)]
      (is (= 3 (count results)))
      (is (every? #(= :harvest/error (:adt/variant %)) results)
          "all outcomes should be :harvest/error"))))

(deftest f1-fault-isolation-empty-sources
  (testing "harvest-all with zero sources returns empty vec"
    (let [results (pipeline/harvest-all [] {} 5000)]
      (is (= [] results)))))

;; =============================================================================
;; Integration: default-sources returns correct set
;; =============================================================================

(deftest default-sources-complete
  (testing "default-sources returns all four source types"
    (with-source-mocks {}
      (let [srcs (sources/default-sources)
            ids  (set (map proto/source-id srcs))]
        (is (= 4 (count srcs)))
        (is (= #{:memory :hivemind :kanban :git} ids))))))
