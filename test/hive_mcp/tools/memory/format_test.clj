(ns hive-mcp.tools.memory.format-test
  "Unit tests for hive-mcp.tools.memory.format.

   Focus: entry->json-alist surfaces kanban payload fields
   (:description/:title/:status/:priority) onto the envelope so
   `memory get` callers don't have to parse the JSON-encoded :content
   blob themselves. Regression coverage for the 'memory get returns
   title but not description' bug."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-mcp.tools.memory.crud :as mem-crud]
            [hive-mcp.tools.memory.format :as fmt]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- kanban-entry
  "Build a fake persisted kanban memory entry (content as JSON string)."
  [content-map & {:keys [tags id] :or {id "task-1"}}]
  {:id      id
   :type    "note"
   :tags    (or tags ["kanban" "todo" "priority-medium" "scope:project:test"])
   :content (json/write-str content-map)
   :created "2026-04-20T10:00:00-03:00"})

;; =============================================================================
;; entry->json-alist: kanban envelope surfacing
;; =============================================================================

(deftest kanban-description-surfaced-on-envelope
  (testing "kanban entry with :description in JSON content surfaces :description on envelope"
    (let [long-desc (apply str (repeat 50 "Refactor the auth module for OIDC. "))
          entry    (kanban-entry {:task-type   "kanban"
                                  :title       "Refactor auth"
                                  :description long-desc
                                  :status      "todo"
                                  :priority    "high"})
          result   (fmt/entry->json-alist entry)]
      (is (= long-desc (:description result))
          "long description surfaced at top level")
      (is (= "Refactor auth" (:title result)))
      (is (= "todo" (:status result)))
      (is (= "high" (:priority result)))
      (is (some? (:content result))
          "raw :content preserved for backward compatibility"))))

(deftest kanban-without-description-leaves-envelope-unchanged
  (testing "kanban entry without :description still surfaces title/status/priority"
    (let [entry  (kanban-entry {:task-type "kanban"
                                :title     "A task"
                                :status    "doing"
                                :priority  "medium"})
          result (fmt/entry->json-alist entry)]
      (is (nil? (:description result))
          "no :description promoted when payload lacks it")
      (is (= "A task" (:title result)))
      (is (= "doing" (:status result)))
      (is (= "medium" (:priority result))))))

(deftest non-kanban-entry-envelope-passthrough
  (testing "non-kanban entries remain untouched (backward compat)"
    (let [entry  {:id      "note-1"
                  :type    "note"
                  :tags    ["session-summary"]
                  :content "Just a plain note body."
                  :created "2026-04-20T10:00:00-03:00"}
          result (fmt/entry->json-alist entry)]
      (is (= (:content entry) (:content result)))
      (is (nil? (:description result)))
      (is (nil? (:title result))))))

(deftest kanban-entry-with-map-content-also-surfaces
  (testing "kanban entry with already-decoded map content surfaces description too"
    (let [entry  (-> (kanban-entry {})
                     (assoc :content {:task-type "kanban"
                                      :title "X" :description "Y"
                                      :status "todo" :priority "low"}))
          result (fmt/entry->json-alist entry)]
      (is (= "Y" (:description result)))
      (is (= "X" (:title result))))))

(deftest kanban-tagged-but-malformed-content-does-not-throw
  (testing "malformed JSON in :content does not throw; envelope passthrough"
    (let [entry  {:id      "task-broken"
                  :type    "note"
                  :tags    ["kanban" "todo"]
                  :content "not-json-at-all"
                  :created "2026-04-20T10:00:00-03:00"}
          result (fmt/entry->json-alist entry)]
      (is (= "not-json-at-all" (:content result)))
      (is (nil? (:description result))))))

(deftest non-kanban-tagged-entry-with-description-in-content-not-promoted
  (testing "entries without :kanban tag retain envelope shape even if content has :description"
    (let [entry  {:id      "note-with-desc"
                  :type    "note"
                  :tags    ["random"]
                  :content (json/write-str {:task-type "other"
                                            :description "should not leak"})
                  :created "2026-04-20T10:00:00-03:00"}
          result (fmt/entry->json-alist entry)]
      (is (nil? (:description result))
          "guarded: only kanban-tagged entries get envelope promotion"))))

;; =============================================================================
;; Create → Get round-trip (exercises the actual bug report):
;;
;;   `memory get` on a kanban-dispatched task with a long description must
;;   surface :description on the envelope, not bury it in the JSON content.
;; =============================================================================

(deftest create-kanban-description-surfaces-through-format
  (testing "description param to kanban create flows through to format envelope"
    (let [create* (requiring-resolve 'hive-mcp.tools.memory-kanban/create*)
          captured (atom nil)
          long-desc (apply str (repeat 40 "Drone-dispatched kanban step body. "))]
      (with-redefs [mem-crud/handle-add
                    (fn [args]
                      (reset! captured args)
                      {:text "entry-id-1"})]
        (create* {:title "Ship v1" :description long-desc :priority "high"
                  :directory "/tmp" :agent_id "tester"}))
      (let [stored-content (json/read-str (:content @captured) :key-fn keyword)
            fake-entry     {:id      "entry-id-1"
                            :type    "note"
                            :tags    (:tags @captured)
                            :content (:content @captured)
                            :created "2026-04-20T10:00:00-03:00"}
            envelope       (fmt/entry->json-alist fake-entry)]
        ;; (a) create* preserved description into the stored JSON payload
        (is (= long-desc (:description stored-content))
            "description survives kanban create into content payload")
        (is (= "kanban" (:task-type stored-content)))
        ;; (b) format surfaces it on the envelope for `memory get`
        (is (= long-desc (:description envelope))
            "envelope exposes :description for memory-get callers")
        (is (= "Ship v1" (:title envelope)))))))
