;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.catalog-test
  "resources/hive-mcp/slots.edn is the published enumeration of the exclusive
   substitution slots. This binds its :kg entry to the code that implements
   the slot, so the published list cannot drift from the dispatch table."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.slots.factory :as fact]))

(def ^:private catalog
  (some-> (io/resource "hive-mcp/slots.edn") slurp edn/read-string))

(defn- dispatch-backends
  "Every backend `backend->store` can construct. :default is the nil-returning
   catch-all, not a backend."
  []
  (disj (set (keys (methods fact/backend->store))) :default))

(deftest catalog-loads
  (testing "the resource is on the classpath and describes all three slots"
    ;; Non-vacuity first. Every assertion below compares two sets, and two
    ;; empty sets are equal, so a catalog that failed to load would make the
    ;; whole namespace pass while checking nothing.
    (is (map? catalog) "resources/hive-mcp/slots.edn did not load")
    (is (= #{:kg :memory-store :carto-store} (set (keys catalog))))
    (is (seq (dispatch-backends)) "backend->store has no methods; did the ns load?")))

(deftest kg-options-are-the-dispatch-table
  (testing ":kg lists exactly the backends the factory can construct"
    ;; The universe comes from the DEFMETHODS, not from the catalog, so a
    ;; backend added to the factory and forgotten here fails rather than
    ;; going unnoticed. That direction is the whole point of the test.
    (is (= (dispatch-backends)
           (set (keys (get-in catalog [:kg :options]))))
        (str "resources/hive-mcp/slots.edn :kg is out of step with "
             "`defmethod backend->store` in slots/factory.clj")))

  (testing "and agrees with supported-backends-set"
    (is (= fact/supported-backends-set
           (set (keys (get-in catalog [:kg :options]))))
        "supported-backends-set, the catalog and the defmethods must all agree")))

(deftest every-option-names-its-provider
  (testing "each option says who supplies it and whether it ships in core"
    (doseq [[slot-k slot] catalog
            [opt-k opt] (:options slot)]
      (is (string? (:by opt)) (str slot-k " " opt-k " has no :by"))
      (is (contains? #{:core :addon} (:provider opt))
          (str slot-k " " opt-k " has no :provider")))))

(deftest slots-declare-whether-they-are-checked
  (testing ":checked-against is present on every slot, nil where nothing checks it"
    ;; Explicitly nil rather than absent: a slot with no dispatch table to
    ;; verify against is a fact worth stating, not an omission to infer.
    (doseq [[slot-k slot] catalog]
      (is (contains? slot :checked-against)
          (str slot-k " must say what verifies it, even if that is nil"))
      (is (seq (:options slot)) (str slot-k " has no options")))))
