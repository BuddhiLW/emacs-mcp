(ns hive-mcp.memory.synthesis-protection-test
  "Regression coverage for the synthesis-afterlife provider: confidence-floor
   membership and the fail-safe SPI registry seam."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-spi.memory.ports :as ports]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.memory.synthesis-protection :as sp]))

;; Restore whatever provider the running system had, so tests don't leave the
;; live reaper mis-wired.
(defn- with-clean-provider [f]
  (let [saved (ports/protected-ids)] ;; touch to force realization; value unused
    (try (f)
         (finally (ports/register-protection-provider! nil)
                  ;; re-install the real provider for the live system
                  (sp/install!)))
    saved))

(use-fixtures :each with-clean-provider)

(defn- stub-rows [rows]
  ;; conn/query returns [[entity] ...]; pull-shape is the entity map
  (with-redefs [conn/db-snapshot (fn [] ::db)
                conn/query (fn [_q _db] (mapv vector rows))]
    (sp/synthesis-protected-ids)))

(deftest floor-membership
  (testing "members of synthetics at/above the floor are protected; below are released"
    (let [protected (stub-rows
                     [{:kg-synthetic/members ["a1" "a2"] :kg-synthetic/confidence 0.5}
                      {:kg-synthetic/members ["b1"]      :kg-synthetic/confidence sp/protection-confidence-floor}
                      {:kg-synthetic/members ["c1"]      :kg-synthetic/confidence 0.29}
                      {:kg-synthetic/members ["d1"]      :kg-synthetic/confidence nil}])]
      (is (= #{"a1" "a2" "b1"} protected))
      (is (set? protected))))
  (testing "a member shared by a low- and high-conf synthetic stays protected"
    (is (contains? (stub-rows [{:kg-synthetic/members ["x"] :kg-synthetic/confidence 0.1}
                               {:kg-synthetic/members ["x"] :kg-synthetic/confidence 0.9}])
                   "x"))))

(deftest provider-is-fail-safe
  (testing "no provider registered -> protect nothing"
    (ports/register-protection-provider! nil)
    (is (= #{} (ports/protected-ids))))
  (testing "registered provider result is surfaced as a set"
    (ports/register-protection-provider! (fn [] ["m1" "m2" "m1"]))
    (is (= #{"m1" "m2"} (ports/protected-ids))))
  (testing "a throwing provider never breaks the reaper"
    (ports/register-protection-provider! (fn [] (throw (ex-info "boom" {}))))
    (is (= #{} (ports/protected-ids)))))

(deftest provider-swallows-kg-failure
  (testing "synthesis-protected-ids yields #{} when the KG read throws"
    (with-redefs [conn/db-snapshot (fn [] (throw (ex-info "kg down" {})))]
      (is (= #{} (sp/synthesis-protected-ids))))))
