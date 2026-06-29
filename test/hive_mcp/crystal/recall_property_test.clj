(ns hive-mcp.crystal.recall-property-test
  "Property tests for partition-by-project (P1-P9) + CreatedEntry ADT unit tests."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.properties :as props]
            [hive-test.generators.core :as gen-core]
            [hive-dsl.adt :as adt]
            [hive-mcp.crystal.recall :as recall
             :refer [created-entry created-entry?
                     partition-by-project]]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-entry-id gen-core/gen-uuid-str)

(def gen-timestamp gen-core/gen-timestamp)

(def gen-project-id gen-core/gen-project-id)

(def gen-scoped-entry
  "Generator for :entry/scoped CreatedEntry values."
  (gen/let [id gen-entry-id
            ts gen-timestamp
            pid gen-project-id]
    (created-entry :entry/scoped {:id id :timestamp ts :project-id pid})))

(def gen-unscoped-entry
  "Generator for :entry/unscoped CreatedEntry values."
  (gen/let [id gen-entry-id
            ts gen-timestamp]
    (created-entry :entry/unscoped {:id id :timestamp ts})))

(def gen-created-entry
  "Generator for any CreatedEntry variant."
  (gen/one-of [gen-scoped-entry gen-unscoped-entry]))

(def gen-entries
  "Generator for vectors of CreatedEntry values."
  (gen/vector gen-created-entry 0 50))

;; =============================================================================
;; P1 — Totality: partition never throws for any valid input
;; =============================================================================

(props/defprop-total p1-partition-totality
  #(partition-by-project % "test-project")
  gen-entries
  {:pred vector?})

;; =============================================================================
;; P2 — Conservation: |matched| + |retained| = |original|
;; =============================================================================

(defspec p2-partition-conservation 200
  (prop/for-all [entries gen-entries
                 pid gen-project-id]
                (let [[matched retained] (partition-by-project entries pid)]
                  (= (count entries) (+ (count matched) (count retained))))))

;; =============================================================================
;; P3 — Correctness: every matched entry is either the target-scoped or unscoped
;;      (unscoped entries belong to any project — see partition-by-project docstring)
;; =============================================================================

(defspec p3-partition-correctness 200
  (prop/for-all [entries gen-entries
                 pid gen-project-id]
                (let [[matched _] (partition-by-project entries pid)]
                  (every? #(or (= :entry/unscoped (:adt/variant %))
                               (= pid (:project-id %)))
                          matched))))

;; =============================================================================
;; P4 — Complement: no retained scoped entries have the target project-id
;; =============================================================================

(defspec p4-partition-complement 200
  (prop/for-all [entries gen-entries
                 pid gen-project-id]
                (let [[_ retained] (partition-by-project entries pid)]
                  (not-any? #(and (= :entry/scoped (:adt/variant %))
                                  (= pid (:project-id %)))
                            retained))))

;; =============================================================================
;; P5 — Empty identity: partition of [] is [[] []]
;; =============================================================================

(defspec p5-partition-empty-identity 200
  (prop/for-all [pid gen-project-id]
                (= [[] []] (partition-by-project [] pid))))

;; =============================================================================
;; P6 — ADT preservation: all output entries are valid CreatedEntry values
;; =============================================================================

(defspec p6-partition-preserves-adt 200
  (prop/for-all [entries gen-entries
                 pid gen-project-id]
                (let [[matched retained] (partition-by-project entries pid)]
                  (every? created-entry? (concat matched retained)))))

;; =============================================================================
;; P7 — Idempotency: re-partitioning matched yields [matched []]
;; =============================================================================

(defspec p7-partition-idempotent-on-matched 200
  (prop/for-all [entries gen-entries
                 pid gen-project-id]
                (let [[matched _] (partition-by-project entries pid)
                      [re-matched re-retained] (partition-by-project matched pid)]
                  (and (= matched re-matched)
                       (= [] re-retained)))))

;; =============================================================================
;; P8 — Unscoped invariant: unscoped entries always go to matched
;;       (an unscoped memory belongs to every project — see impl docstring)
;; =============================================================================

(defspec p8-unscoped-always-matched 200
  (prop/for-all [unscoped-entries (gen/vector gen-unscoped-entry 0 20)
                 pid gen-project-id]
                (let [[matched retained] (partition-by-project unscoped-entries pid)]
                  (and (= unscoped-entries matched)
                       (empty? retained)))))

;; =============================================================================
;; P9 — ADT completeness: result is always a 2-element vector
;; =============================================================================

(defspec p9-result-shape 200
  (prop/for-all [entries gen-entries
                 pid gen-project-id]
                (let [result (partition-by-project entries pid)]
                  (and (vector? result)
                       (= 2 (count result))
                       (vector? (first result))
                       (vector? (second result))))))

;; =============================================================================
;; Unit tests for register + flush integration (stateful, non-PBT)
;; =============================================================================

(deftest register-creates-scoped-adt
  (testing "register-created-id! with project-id creates :entry/scoped ADT"
    (reset! @#'recall/created-ids-buffer [])
    (recall/register-created-id! "note-123" "project-alpha")
    (let [ids (recall/get-created-ids)]
      (is (= 1 (count ids)))
      (is (created-entry? (first ids)))
      (is (= :entry/scoped (:adt/variant (first ids))))
      (is (= "note-123" (:id (first ids))))
      (is (= "project-alpha" (:project-id (first ids)))))
    (reset! @#'recall/created-ids-buffer [])))

(deftest register-creates-unscoped-adt
  (testing "register-created-id! with nil project-id creates :entry/unscoped ADT"
    (reset! @#'recall/created-ids-buffer [])
    (recall/register-created-id! "note-456" nil)
    (let [ids (recall/get-created-ids)]
      (is (= 1 (count ids)))
      (is (created-entry? (first ids)))
      (is (= :entry/unscoped (:adt/variant (first ids))))
      (is (= "note-456" (:id (first ids)))))
    (reset! @#'recall/created-ids-buffer [])))

(deftest flush-scoped-filters-by-project
  (testing "flush-created-ids! with project-id returns matching + unscoped, retains the rest"
    (reset! @#'recall/created-ids-buffer [])
    (recall/register-created-id! "a" "project-alpha")
    (recall/register-created-id! "b" "project-beta")
    (recall/register-created-id! "c" "project-alpha")
    (recall/register-created-id! "d" nil)
    (let [matched (recall/flush-created-ids! "project-alpha")
          remaining (recall/get-created-ids)]
      (is (= 3 (count matched)) "Should match project-alpha scoped + unscoped")
      (is (every? #(or (= :entry/unscoped (:adt/variant %))
                       (= "project-alpha" (:project-id %)))
                  matched))
      (is (= 1 (count remaining)) "Should retain only project-beta")
      (is (= #{"b"} (set (map :id remaining)))))
    (reset! @#'recall/created-ids-buffer [])))

(deftest flush-all-clears-buffer
  (testing "flush-created-ids! without args returns all and clears"
    (reset! @#'recall/created-ids-buffer [])
    (recall/register-created-id! "a" "project-alpha")
    (recall/register-created-id! "b" "project-beta")
    (let [all (recall/flush-created-ids!)]
      (is (= 2 (count all)))
      (is (empty? (recall/get-created-ids))))))
