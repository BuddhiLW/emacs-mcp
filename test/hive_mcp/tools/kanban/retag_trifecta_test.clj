(ns hive-mcp.tools.kanban.retag-trifecta-test
  "Trifecta + invariant tests for kanban scope-retag.

   Covers:
   - `kt/retag-transition` — pure, golden + property + mutation
   - `events/retag-fx`     — effect-map invariants (no delete, tags-only payload)

   The retag contract: tags-only mutation, preserves entry id + content + KG."
  (:require [clojure.test :refer [deftest is testing]]
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
   :tags    ["kanban" "todo" "priority-medium" "scope:project:hive"]
   :content {:task-type "kanban" :title "x" :status "todo" :priority "medium"}})

;; =============================================================================
;; 1. retag-transition — pure trifecta
;; =============================================================================

(defn run-retag-transition [{:keys [entry new-pid opts]}]
  (kt/retag-transition entry new-pid (or opts {})))

(deftrifecta retag-transition-contract
  hive-mcp.tools.kanban.retag-trifecta-test/run-retag-transition
  {:golden-path "test/golden/kanban/retag-transition.edn"
   :cases       {:scope-only
                 {:entry fixture-entry :new-pid "probe" :opts {}}

                 :add-tags
                 {:entry fixture-entry :new-pid "probe"
                  :opts  {:add-tags ["epic:adapter" "wave:1"]}}

                 :remove-tags
                 {:entry fixture-entry :new-pid "probe"
                  :opts  {:remove-tags ["priority-medium"]}}

                 :add-and-remove
                 {:entry fixture-entry :new-pid "probe"
                  :opts  {:add-tags ["epic:adapter"] :remove-tags ["todo"]}}

                 :no-existing-scope
                 {:entry  (assoc fixture-entry :tags ["kanban" "todo" "priority-medium"])
                  :new-pid "probe"
                  :opts   {}}

                 :duplicate-tags-deduped
                 {:entry fixture-entry :new-pid "probe"
                  :opts  {:add-tags ["kanban" "kanban" "epic:foo"]}}}
   :gen         (gen/let [pid    (gen/such-that #(not (clojure.string/blank? %))
                                                gen/string-alphanumeric)
                          tags   (gen/vector gen/string-alphanumeric 0 5)]
                  {:entry  fixture-entry
                   :new-pid pid
                   :opts    {:add-tags tags}})
   :pred        (fn [r]
                  (and (map? r)
                       (string? (:new-project-id r))
                       (vector? (:new-tags r))
                       ;; Exactly one scope tag in the result
                       (= 1 (count (filter #(.startsWith (str %) "scope:project:")
                                           (:new-tags r))))
                       ;; New scope tag matches the request
                       (some #(= (str "scope:project:" (:new-project-id r)) %)
                             (:new-tags r))))
   :num-tests   100
   :mutations   [["always-empty"     (fn [_] {:new-tags [] :new-project-id ""})]
                 ["double-scope"
                  (fn [{:keys [entry new-pid]}]
                    {:new-project-id new-pid
                     :new-tags (conj (:tags entry)
                                     (str "scope:project:" new-pid))})]
                 ["drop-non-scope"
                  (fn [{:keys [new-pid]}]
                    {:new-project-id new-pid
                     :new-tags [(str "scope:project:" new-pid)]})]]})

;; =============================================================================
;; 2. retag-fx — effect-map invariants
;; =============================================================================

(defn- fx-for [new-pid & {:keys [add-tags remove-tags]}]
  (events/retag-fx
   {:kanban/entry fixture-entry}
   [:kanban/retag {:task-id        (:id fixture-entry)
                   :new-project-id new-pid
                   :add-tags       add-tags
                   :remove-tags    remove-tags}]))

(deftest retag-emits-tags-only-update
  (let [fx (fx-for "probe")]
    (is (some? fx) "fx map present for valid retag")
    (is (contains? fx :kanban/facade-update) "facade-update is the soft commit")
    (is (contains? (get-in fx [:kanban/facade-update :payload]) :tags)
        "payload carries new tags")
    (is (not (contains? (get-in fx [:kanban/facade-update :payload]) :content))
        "payload MUST NOT carry content (tags-only mutation)")))

(deftest retag-never-emits-delete
  (let [fx (fx-for "probe")]
    (is (not (contains? fx :facade/delete-entry)))
    (is (not (contains? fx :kanban/facade-delete)))))

(deftest retag-never-emits-completion-hooks
  (testing "retag is a scope move, not a status transition"
    (let [fx (fx-for "probe")]
      (is (not (contains? fx :kanban/notify-done))
          "no crystal completion hook on retag")
      (is (not (contains? fx :kanban/archive-external))
          "no external archive on retag"))))

(deftest retag-records-temporal-and-movement
  (let [fx (fx-for "probe")]
    (is (= :kanban-retag (get-in fx [:kanban/temporal-record :op])))
    (is (= "hive" (get-in fx [:kanban/temporal-record :data :old-project-id])))
    (is (= "probe" (get-in fx [:kanban/temporal-record :data :new-project-id])))
    (is (= "scope:hive"  (get-in fx [:kanban/track-movement :from])))
    (is (= "scope:probe" (get-in fx [:kanban/track-movement :to])))))

(deftest retag-invalid-entry-yields-nil-fx
  (is (nil? (events/retag-fx
             {:kanban/entry nil}
             [:kanban/retag {:task-id "missing" :new-project-id "probe"}])))
  (is (nil? (events/retag-fx
             {:kanban/entry {:content {:task-type "memo"}}}
             [:kanban/retag {:task-id "x" :new-project-id "probe"}]))))

;; =============================================================================
;; 3. Properties
;; =============================================================================

(def gen-pid (gen/such-that #(not (clojure.string/blank? %))
                            gen/string-alphanumeric))

(defspec retag-tags-contain-exactly-one-scope 200
  (prop/for-all [pid gen-pid]
    (let [fx       (fx-for pid)
          new-tags (get-in fx [:kanban/facade-update :payload :tags])
          scopes   (filter #(.startsWith (str %) "scope:project:") new-tags)]
      (and (= 1 (count scopes))
           (= (str "scope:project:" pid) (first scopes))))))

(defspec retag-preserves-non-scope-non-removed-tags 200
  (prop/for-all [pid gen-pid]
    (let [fx       (fx-for pid)
          new-tags (set (get-in fx [:kanban/facade-update :payload :tags]))]
      ;; "kanban", "todo", "priority-medium" survive — only the scope flips
      (and (contains? new-tags "kanban")
           (contains? new-tags "todo")
           (contains? new-tags "priority-medium")))))

(defspec retag-add-then-remove-leaves-net-zero 100
  (prop/for-all [pid gen-pid
                 t   gen/string-alphanumeric]
    (let [fx       (fx-for pid :add-tags [t] :remove-tags [t])
          new-tags (set (get-in fx [:kanban/facade-update :payload :tags]))]
      (not (contains? new-tags t)))))
