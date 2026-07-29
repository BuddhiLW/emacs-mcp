(ns hive-mcp.tools.memory.crud.role-gate-test
  "Tests for the :role write gate (RoleCard malli validation on memory write).

   Mirrors hive-mcp.plan.gate-test: the gate rejects malformed RoleCard
   content BEFORE it reaches the store, throwing :role-gate-rejected so
   handle-add surfaces it as an mcp-error rather than a 500."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.tools.memory.crud.write :as wr]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private validate-role-gate! #'wr/validate-role-gate!)

;; The gate resolves its validator from `hive-spi.role.card`, which the pinned
;; hive-spi sha does NOT ship — so in this JVM the gate silently skipped and
;; every rejection assertion passed vacuously (nil thrown, nil expected).
;; Inject the RoleCard contract instead of depending on what hive-spi ships.
(defn- role-card-valid?
  [card]
  (and (map? card)
       (keyword? (:role/id card))
       (string? (:role/name card))))

(use-fixtures :each
  (fn [f]
    (wr/set-role-card-validator!
     {:valid?  role-card-valid?
      :explain (fn [card]
                 (cond
                   (not (map? card))                  {:role/_ "not a map"}
                   (not (keyword? (:role/id card)))   {:role/id "should be a keyword"}
                   (not (string? (:role/name card)))  {:role/name "should be a string"}
                   :else nil))})
    (try (f)
         (finally (wr/set-role-card-validator! nil)))))

(deftest valid-role-card-passes-test
  (testing "minimal conformant RoleCard EDN passes the gate"
    (is (nil? (validate-role-gate!
               "{:role/id :role/reviewer :role/name \"Reviewer\"}"))))

  (testing "RoleCard with optional fields passes the gate"
    (is (nil? (validate-role-gate!
               (str "{:role/id :role/coder :role/name \"Coder\""
                    " :role/model :opus :role/system-prompt \"Write code.\""
                    " :role/tags [\"eng\"]}"))))))

(deftest invalid-role-card-rejected-test
  (testing "missing :role/name is rejected with :role-gate-rejected"
    (let [e (try (validate-role-gate! "{:role/id :role/x}")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :role-gate-rejected (:type (ex-data e))))))

  (testing "wrong-typed :role/id is rejected"
    (let [e (try (validate-role-gate! "{:role/id \"not-a-keyword\" :role/name \"X\"}")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :role-gate-rejected (:type (ex-data e))))))

  (testing "non-map content is rejected"
    (let [e (try (validate-role-gate! "[:role/id :role/x]")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :role-gate-rejected (:type (ex-data e))))))

  (testing "unreadable EDN is rejected with :role-gate-rejected"
    (let [e (try (validate-role-gate! "{:role/id :role/x :role/name")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :role-gate-rejected (:type (ex-data e)))))))
