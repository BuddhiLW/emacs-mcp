(ns hive-mcp.tools.kanban.edit-trifecta-test
  "Trifecta + invariant tests for kanban content edit.

   Covers:
   - `kt/edit-transition` — pure, golden + property + mutation
   - `events/edit-fx`     — effect-map invariants (content payload, tags only
                            when the priority moved, no completion hooks)

   The edit contract: content-only mutation of title/description/priority.
   Status, scope and entry id are preserved; the `priority-*` tag travels
   with a priority change so `list :priority` and `get` cannot disagree."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.tools.kanban.events :as events]
            [hive-mcp.tools.kanban.transitions :as kt]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixture
;; =============================================================================

(def ^:private fixture-entry
  {:id      "20260101000000-deadbeef"
   :tags    ["kanban" "todo" "priority-medium" "scope:project:hive" "epic:adapter"]
   :content {:task-type   "kanban"
             :title       "original title"
             :description "original description"
             :status      "todo"
             :priority    "medium"}})

;; =============================================================================
;; 1. edit-transition — pure trifecta
;; =============================================================================

(defn run-edit-transition [{:keys [entry edits]}]
  (kt/edit-transition (or entry fixture-entry) (or edits {})))

(deftrifecta edit-transition-contract
  hive-mcp.tools.kanban.edit-trifecta-test/run-edit-transition
  {:golden-path "test/golden/kanban/edit-transition.edn"
   :cases       {:title-only
                 {:entry fixture-entry :edits {:title "new title"}}

                 :description-only
                 {:entry fixture-entry :edits {:description "new description"}}

                 :priority-only
                 {:entry fixture-entry :edits {:priority "high"}}

                 :all-three
                 {:entry fixture-entry
                  :edits {:title "t" :description "d" :priority "low"}}

                 :blank-is-ignored
                 {:entry fixture-entry :edits {:title "   " :description ""}}

                 :nil-is-ignored
                 {:entry fixture-entry :edits {:title nil :priority nil}}

                 :no-op-same-values
                 {:entry fixture-entry
                  :edits {:title "original title" :priority "medium"}}

                 :string-keyed-content
                 {:entry {:id   "x"
                          :tags ["kanban" "todo" "priority-low"]
                          :content {"title" "from json" "priority" "low"}}
                  :edits {:title "replaced"}}}
   :gen         (gen/let [title (gen/one-of [(gen/return nil) gen/string-alphanumeric])
                          desc  (gen/one-of [(gen/return nil) gen/string-alphanumeric])
                          prio  (gen/elements [nil "high" "medium" "low"])]
                  {:entry fixture-entry
                   :edits {:title title :description desc :priority prio}})
   :pred        (fn [r]
                  (and (map? r)
                       (set? (:changed r))
                       (map? (:new-content r))
                       ;; tags move if and only if the priority moved
                       (= (contains? (:changed r) :priority)
                          (some? (:new-tags r)))
                       ;; a tag rewrite always leaves exactly one priority tag
                       (or (nil? (:new-tags r))
                           (= 1 (count (filter #(str/starts-with? (str %) "priority-")
                                               (:new-tags r)))))
                       ;; status is never touched by an edit
                       (= "todo" (get (:new-content r) :status))))
   :num-tests   100
   :mutations   [["always-changed"
                  (fn [_] {:changed #{:title :description :priority}
                           :new-content {} :new-tags []})]
                 ["never-changed"
                  (fn [_] {:changed #{} :new-content {} :new-tags nil})]
                 ["tags-without-priority"
                  (fn [_] {:changed #{:title}
                           :new-content {:status "todo"}
                           :new-tags ["priority-medium"]})]
                 ["drops-status"
                  (fn [{:keys [edits]}]
                    {:changed #{:title}
                     :new-content (dissoc (:content fixture-entry) :status)
                     :new-tags nil
                     :new-title (:title edits)})]]})

;; =============================================================================
;; 2. edit-fx — effect-map invariants
;; =============================================================================

(defn- fx-for [edits]
  (events/edit-fx
   {:kanban/entry fixture-entry}
   [:kanban/edit (merge {:task-id (:id fixture-entry)} edits)]))

(deftest edit-emits-content-update
  (let [fx (fx-for {:title "new title"})]
    (is (some? fx) "fx map present for a valid edit")
    (is (contains? fx :kanban/facade-update) "facade-update is the soft commit")
    (is (= "new title" (get-in fx [:kanban/facade-update :payload :content :title])))
    (is (= "original description"
           (get-in fx [:kanban/facade-update :payload :content :description]))
        "untouched fields survive the edit")
    (is (= "todo" (get-in fx [:kanban/facade-update :payload :content :status]))
        "status is not a content edit")))

(deftest edit-carries-tags-only-when-priority-moves
  (testing "a title edit leaves tags alone"
    (let [fx (fx-for {:title "new title"})]
      (is (not (contains? (get-in fx [:kanban/facade-update :payload]) :tags))
          "no tag rewrite when the priority did not move")))
  (testing "a priority edit rewrites the priority tag"
    (let [fx   (fx-for {:priority "high"})
          tags (get-in fx [:kanban/facade-update :payload :tags])]
      (is (some? tags) "tags travel with a priority change")
      (is (= ["priority-high"] (filter #(str/starts-with? % "priority-") tags))
          "exactly one priority tag, and it is the new one")
      (is (= "high" (get-in fx [:kanban/facade-update :payload :content :priority]))
          "content and tag agree")
      (is (every? (set tags) ["kanban" "todo" "scope:project:hive" "epic:adapter"])
          "every non-priority tag survives"))))

(deftest edit-never-emits-completion-hooks-or-delete
  (let [fx (fx-for {:title "new title" :priority "high"})]
    (is (not (contains? fx :kanban/notify-done)))
    (is (not (contains? fx :kanban/archive-external)))
    (is (not (contains? fx :kanban/track-movement))
        "an edit is not a movement")
    (is (not (contains? fx :facade/delete-entry)))
    (is (not (contains? fx :kanban/facade-delete)))))

(deftest edit-records-temporal-only-when-something-changed
  (testing "a real change is audited"
    (let [fx (fx-for {:title "new title"})]
      (is (= :kanban-edit (get-in fx [:kanban/temporal-record :op])))
      (is (= ["title"] (get-in fx [:kanban/temporal-record :data :changed])))
      (is (= "original title" (get-in fx [:kanban/temporal-record :data :old-title])))
      (is (= "new title" (get-in fx [:kanban/temporal-record :data :new-title])))
      (is (= "hive" (get-in fx [:kanban/temporal-record :project-id])))))
  (testing "a no-op edit still commits but leaves no audit noise"
    (let [fx (fx-for {:title "original title"})]
      (is (contains? fx :kanban/facade-update))
      (is (not (contains? fx :kanban/temporal-record))))))

(deftest edit-invalid-entry-yields-nil-fx
  (is (nil? (events/edit-fx
             {:kanban/entry nil}
             [:kanban/edit {:task-id "missing" :title "x"}])))
  (is (nil? (events/edit-fx
             {:kanban/entry {:content {:task-type "memo"}}}
             [:kanban/edit {:task-id "x" :title "y"}]))))

;; =============================================================================
;; 3. Properties
;; =============================================================================

(def gen-priority (gen/elements ["high" "medium" "low"]))

(defspec edit-preserves-status-and-scope 200
  (prop/for-all [title gen/string-alphanumeric
                 prio  gen-priority]
    (let [fx      (fx-for {:title title :priority prio})
          content (get-in fx [:kanban/facade-update :payload :content])
          tags    (or (get-in fx [:kanban/facade-update :payload :tags])
                      (:tags fixture-entry))]
      (and (= "todo" (:status content))
           (contains? (set tags) "scope:project:hive")
           (contains? (set tags) "todo")))))

(defspec edit-priority-tag-matches-content 200
  (prop/for-all [prio gen-priority]
    (let [fx      (fx-for {:priority prio})
          content (get-in fx [:kanban/facade-update :payload :content])
          tags    (or (get-in fx [:kanban/facade-update :payload :tags])
                      (:tags fixture-entry))]
      (= (str "priority-" (:priority content))
         (first (filter #(str/starts-with? % "priority-") tags))))))

(defspec edit-blank-never-overwrites 200
  (prop/for-all [pad (gen/vector (gen/elements [\space \tab]) 0 5)]
    (let [fx      (fx-for {:title (apply str pad)})
          content (get-in fx [:kanban/facade-update :payload :content])]
      (= "original title" (:title content)))))

(defspec edit-is-idempotent 100
  (prop/for-all [title gen/string-alphanumeric
                 prio  gen-priority]
    (let [once  (kt/edit-transition fixture-entry {:title title :priority prio})
          twice (kt/edit-transition (assoc fixture-entry
                                           :content (:new-content once)
                                           :tags    (or (:new-tags once)
                                                        (:tags fixture-entry)))
                                    {:title title :priority prio})]
      (and (empty? (:changed twice))
           (= (:new-content once) (:new-content twice))))))
