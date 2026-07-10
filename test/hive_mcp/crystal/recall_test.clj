(ns hive-mcp.crystal.recall-test
  "Tests for crystal recall partition-by-project and created-ids tracking."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.crystal.recall :as recall :refer [created-entry]]))

;; Reset created-ids-buffer before each test
(use-fixtures :each
  (fn [f]
    (recall/flush-created-ids!)
    (f)))

;; =============================================================================
;; partition-by-project tests
;; =============================================================================

(deftest partition-by-project-scoped-entries-test
  (testing "scoped entries with matching project-id go to matched"
    (let [e1 (created-entry :entry/scoped {:id "a" :timestamp "t1" :project-id "alpha"})
          [matched retained] (recall/partition-by-project [e1] "alpha")]
      (is (= 1 (count matched)))
      (is (= 0 (count retained)))
      (is (= "a" (:id (first matched))))))

  (testing "scoped entries with non-matching project-id go to retained"
    (let [e1 (created-entry :entry/scoped {:id "a" :timestamp "t1" :project-id "beta"})
          [matched retained] (recall/partition-by-project [e1] "alpha")]
      (is (= 0 (count matched)))
      (is (= 1 (count retained))))))

(deftest partition-by-project-unscoped-entries-test
  (testing "unscoped entries go to matched (not retained)"
    (let [e1 (created-entry :entry/unscoped {:id "c" :timestamp "t3"})
          [matched retained] (recall/partition-by-project [e1] "alpha")]
      (is (= 1 (count matched)) "Unscoped entries should be in matched")
      (is (= 0 (count retained)) "Unscoped entries should NOT be in retained")
      (is (= "c" (:id (first matched)))))))

(deftest partition-by-project-mixed-entries-test
  (testing "mixed scoped/unscoped entries partition correctly"
    (let [e-scoped-match (created-entry :entry/scoped {:id "a" :timestamp "t1" :project-id "alpha"})
          e-scoped-other (created-entry :entry/scoped {:id "b" :timestamp "t2" :project-id "beta"})
          e-unscoped     (created-entry :entry/unscoped {:id "c" :timestamp "t3"})
          [matched retained] (recall/partition-by-project
                              [e-scoped-match e-scoped-other e-unscoped]
                              "alpha")]
      (is (= 2 (count matched)) "Should match scoped-alpha + unscoped")
      (is (= 1 (count retained)) "Should retain only scoped-beta")
      (is (= #{"a" "c"} (set (map :id matched))))
      (is (= #{"b"} (set (map :id retained)))))))

;; =============================================================================
;; flush-created-ids! tests
;; =============================================================================

(deftest flush-created-ids-with-project-id-test
  (testing "flush with project-id returns scoped matches and unscoped entries"
    (recall/register-created-id! "id-1" "alpha")
    (recall/register-created-id! "id-2" nil)       ;; unscoped
    (recall/register-created-id! "id-3" "beta")
    (let [flushed (recall/flush-created-ids! "alpha")]
      (is (= 2 (count flushed)) "Should return scoped-alpha + unscoped")
      (is (= #{"id-1" "id-2"} (set (map :id flushed))))
      ;; Remaining buffer should only have the beta entry
      (let [remaining (recall/get-created-ids)]
        (is (= 1 (count remaining)))
        (is (= "id-3" (:id (first remaining))))))))

(deftest flush-created-ids-zero-arity-test
  (testing "flush without project-id returns all entries"
    (recall/register-created-id! "id-1" "alpha")
    (recall/register-created-id! "id-2" nil)
    (recall/register-created-id! "id-3" "beta")
    (let [flushed (recall/flush-created-ids!)]
      (is (= 3 (count flushed)))
      (is (= #{"id-1" "id-2" "id-3"} (set (map :id flushed))))
      ;; Buffer should be empty
      (is (= 0 (count (recall/get-created-ids)))))))

;; =============================================================================
;; register-created-id! type-threading tests (wrap stats by type)
;; =============================================================================

(deftest register-created-id-threads-type-test
  (testing "3-arity register threads the memory type onto the entry"
    (recall/register-created-id! "d1" "alpha" "decision")
    (recall/register-created-id! "u1" nil "axiom")
    (let [entries (recall/get-created-ids)
          by-id   (into {} (map (juxt :id identity)) entries)]
      (is (= "decision" (:type (get by-id "d1"))) "scoped entry carries type")
      (is (= :entry/scoped (:adt/variant (get by-id "d1"))))
      (is (= "axiom" (:type (get by-id "u1"))) "unscoped entry carries type")
      (is (= :entry/unscoped (:adt/variant (get by-id "u1"))))
      (is (every? recall/created-entry? entries) "typed entries are valid ADT values")))

  (testing "2-arity register stays backward-compatible (no :type key, still valid)"
    (recall/flush-created-ids!)
    (recall/register-created-id! "legacy" "alpha")
    (let [e (first (recall/get-created-ids))]
      (is (not (contains? e :type)) "legacy entry has no :type key")
      (is (recall/created-entry? e) "legacy entry is still a valid CreatedEntry")))

  (testing "type frequencies drive the by-type breakdown wrap synthesis consumes"
    (recall/flush-created-ids!)
    (recall/register-created-id! "a" "p" "decision")
    (recall/register-created-id! "b" "p" "decision")
    (recall/register-created-id! "c" "p" "convention")
    (recall/register-created-id! "d" "p")             ;; untyped — excluded
    (is (= {"decision" 2 "convention" 1}
           (frequencies (keep :type (recall/get-created-ids)))))))
