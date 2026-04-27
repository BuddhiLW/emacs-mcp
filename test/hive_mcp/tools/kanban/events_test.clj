(ns hive-mcp.tools.kanban.events-test
  "Effect-map invariants for the kanban event handler.

   The crucial property: moving a task to `done` MUST NOT emit any
   facade-delete effect. The transition is soft — the entry remains in
   memory with its KG edges intact."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.tools.kanban.events :as events]
            [hive-mcp.tools.kanban.predicates :as kp]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private fixture-entry
  {:id    "20260101000000-deadbeef"
   :tags  ["kanban" "review" "priority-high" "scope:project:hive-mcp"]
   :content {:task-type "kanban" :title "x" :status "review" :priority "high"}})

(defn- fx-for [status]
  (events/move-fx
   {:kanban/entry      fixture-entry
    :kanban/project-id "hive-mcp"}
   [:kanban/move {:task-id "20260101000000-deadbeef"
                  :new-status status}]))

(deftest done-does-not-emit-delete
  (testing "soft-delete invariant: no delete effect for any normalisation of done"
    (doseq [s ["done" "Done"]]
      (let [fx (fx-for s)]
        (is (some? fx) (str "expected effect map for " s))
        (is (not (contains? fx :facade/delete-entry)) "no facade-delete effect")
        (is (not (contains? fx :kanban/facade-delete)) "no kanban-facade-delete")))))

(deftest done-emits-soft-update-and-completion-hooks
  (let [fx (fx-for "done")]
    (is (contains? fx :kanban/facade-update)    ":facade-update is the soft commit")
    (is (contains? fx :kanban/notify-done)      "crystal hook fires on done")
    (is (contains? fx :kanban/archive-external) "external archive runs on done")
    (is (contains? fx :kanban/temporal-record))
    (is (contains? fx :kanban/track-movement))
    (is (= "done" (get-in fx [:kanban/facade-update :payload :content :status])))))

(deftest non-done-skips-completion-hooks
  (doseq [s ["todo" "doing" "review"]]
    (let [fx (fx-for s)]
      (is (some? fx) (str "fx for " s))
      (is (contains?     fx :kanban/facade-update))
      (is (not (contains? fx :kanban/notify-done))      (str s " must not fire crystal"))
      (is (not (contains? fx :kanban/archive-external)) (str s " must not archive")))))

(deftest invalid-entry-yields-nil-fx
  (is (nil? (events/move-fx
             {:kanban/entry nil :kanban/project-id "hive-mcp"}
             [:kanban/move {:task-id "missing" :new-status "done"}])))
  (is (nil? (events/move-fx
             {:kanban/entry {:content {:task-type "memo"}}
              :kanban/project-id "hive-mcp"}
             [:kanban/move {:task-id "x" :new-status "done"}]))))

;; --- properties ---

(def gen-status (gen/elements (vec kp/valid-statuses)))

(defspec soft-delete-invariant 200
  (prop/for-all [s gen-status]
    (let [fx (fx-for s)]
      (and fx
           (not (contains? fx :facade/delete-entry))
           (not (contains? fx :kanban/facade-delete))
           (contains? fx :kanban/facade-update)))))

(defspec done-iff-completion-hooks 200
  (prop/for-all [s gen-status]
    (let [fx (fx-for s)
          done? (= "done" s)]
      (and (= done? (contains? fx :kanban/notify-done))
           (= done? (contains? fx :kanban/archive-external))))))
