(ns hive-mcp.tools.kanban.transitions-property-test
  "Properties for pure kanban transitions.

   Key invariants:
   - `compute-new-content` is total over (content x status).
   - `compute-new-tags` always returns a vector of non-empty strings
     containing the new status.
   - `transition` round-trips status: out :new-status equals normalised input."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.tools.kanban.predicates :as kp]
            [hive-mcp.tools.kanban.transitions :as kt]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def gen-status      (gen/elements (vec kp/valid-statuses)))
(def gen-mcp-status  (gen/elements (vec (keys kp/status-enum->tag))))
(def gen-priority    (gen/elements ["high" "medium" "low"]))
(def gen-project-id  (gen/such-that seq gen/string-alphanumeric))
(def gen-title       (gen/such-that seq gen/string-alphanumeric))

(def gen-content
  (gen/let [title    gen-title
            status   gen-status
            priority gen-priority]
    {:task-type "kanban"
     :title     title
     :status    status
     :priority  priority
     :created   "2026-04-04T21:34:28-0300"
     :started   nil
     :context   nil}))

(def gen-entry
  (gen/let [content    gen-content
            project-id gen-project-id]
    {:id      (str "id-" project-id)
     :tags    ["kanban" (:status content) (str "priority-" (:priority content))
               (str "scope:project:" project-id)]
     :content content}))

(defspec compute-new-content-totality 200
  (prop/for-all [content gen-content
                 status  gen-status]
    (let [c (kt/compute-new-content content status)]
      (and (map? c)
           (= status (:status c))
           (= (:title content) (:title c))))))

(defspec compute-new-content-stamps-completed-iff-done 200
  (prop/for-all [content gen-content
                 status  gen-status]
    (let [c (kt/compute-new-content content status)]
      (= (= "done" status) (some? (:completed c))))))

(defspec compute-new-tags-shape 200
  (prop/for-all [status     gen-status
                 priority   gen-priority
                 project-id gen-project-id]
    (let [tags (kt/compute-new-tags [] status priority project-id)]
      (and (vector? tags)
           (every? string? tags)
           (every? seq tags)
           (some #{status} tags)
           (some #(= (str "priority-" priority) %) tags)
           (some #(= (str "scope:project:" project-id) %) tags)))))

(defspec transition-normalises-mcp-status 200
  (prop/for-all [entry      gen-entry
                 mcp-status gen-mcp-status
                 project-id gen-project-id]
    (let [{:keys [new-status]} (kt/transition entry mcp-status project-id)]
      (= (kp/normalize-status mcp-status) new-status))))

(defspec transition-preserves-title 200
  (prop/for-all [entry      gen-entry
                 status     gen-status
                 project-id gen-project-id]
    (let [{:keys [title]} (kt/transition entry status project-id)]
      (= (get-in entry [:content :title]) title))))
