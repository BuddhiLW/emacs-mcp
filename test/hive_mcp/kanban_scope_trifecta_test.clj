(ns hive-mcp.kanban-scope-trifecta-test
  "Trifecta tests for kanban query scoping behavior.

   Catches the bug where kanban list returned [] when no directory was
   provided, because project-id resolved to 'global' and the query
   didn't push the 'kanban' tag filter server-side — resulting in 500
   random entries that drowned out the actual kanban tasks.

   Tests the pure helper functions extracted from memory_kanban.clj."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-test.properties :as props]))

;; =============================================================================
;; Helpers (defined first — used by golden cases)
;; =============================================================================

(defn- mk-entry
  "Build a minimal entry for testing."
  [id task-type tags]
  {:id id
   :content (if (= task-type "kanban")
              {:task-type "kanban" :title (str "Task " id) :status "todo"}
              (str "Non-kanban content " id))
   :tags tags})

;; =============================================================================
;; Extracted pure functions for testing
;; (Mirror the private helpers in memory_kanban.clj)
;; =============================================================================

(defn kanban-task-type?
  "Check if content has task-type 'kanban'. Mirrors memory_kanban/kanban-task-type?"
  [content]
  (boolean (some #(= "kanban" (get content %)) [:task-type "task-type"])))

(defn kanban-entry?
  "Check if an entry is a kanban task."
  [entry]
  (kanban-task-type? (:content entry)))

(defn filter-kanban-by-tags
  "Filter entries by required tags and kanban-entry? check.
   Mirrors memory_kanban/filter-kanban-by-tags."
  [entries required-tags]
  (->> entries
       (filter (fn [entry]
                 (let [entry-tags (set (:tags entry))]
                   (every? #(contains? entry-tags %) required-tags))))
       (filter kanban-entry?)))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-kanban-entry
  "Generator for a valid kanban entry (parsed content map)."
  (gen/let [title gen/string-alphanumeric
            status (gen/elements ["todo" "doing" "review"])
            priority (gen/elements ["high" "medium" "low"])
            project (gen/elements ["funeraria" "hive-mcp" "hive"])]
    {:id (str "gen-" (rand-int 99999))
     :content {:task-type "kanban" :title title :status status :priority priority}
     :tags ["kanban" status (str "priority-" priority) (str "scope:project:" project)]
     :project-id project}))

(def gen-non-kanban-entry
  "Generator for a non-kanban entry (plain string content)."
  (gen/let [content gen/string-alphanumeric
            project (gen/elements ["funeraria" "hive-mcp" "hive"])]
    {:id (str "gen-nk-" (rand-int 99999))
     :content content
     :tags ["note" (str "scope:project:" project)]
     :project-id project}))

(def gen-mixed-entries
  "Generator for a mixed list of kanban + non-kanban entries."
  (gen/let [kanban-entries (gen/vector gen-kanban-entry 0 10)
            non-kanban-entries (gen/vector gen-non-kanban-entry 0 10)]
    (vec (shuffle (concat kanban-entries non-kanban-entries)))))

;; =============================================================================
;; 1. Trifecta: kanban-task-type? predicate
;; =============================================================================

(deftrifecta kanban-task-type-check
  hive-mcp.kanban-scope-trifecta-test/kanban-task-type?
  {:golden-path "test/golden/kanban/task-type-check.edn"
   :cases       {:map-keyword  {:task-type "kanban" :title "X"}
                 :map-string   {"task-type" "kanban" "title" "Y"}
                 :not-kanban   {:task-type "note" :title "Z"}
                 :plain-string "just a string"
                 :nil-content  nil
                 :empty-map    {}}
   :gen         (gen/one-of
                  [(gen/return {:task-type "kanban"})
                   (gen/return {"task-type" "kanban"})
                   (gen/return {:task-type "note"})
                   (gen/return "plain string")
                   (gen/return nil)
                   (gen/return {})])
   :pred        #(boolean? %)
   :num-tests   100
   :mutations   [["always-true"  (fn [_] true)]
                 ["always-false" (fn [_] false)]
                 ["ignores-string-keys" (fn [c]
                                          (boolean (= "kanban" (get c :task-type))))]]})

;; =============================================================================
;; 2. Property: filter-kanban-by-tags only returns kanban entries
;; =============================================================================

(clojure.test.check.clojure-test/defspec prop-filter-only-returns-kanban 100
  (clojure.test.check.properties/for-all
    [entries gen-mixed-entries]
    (let [result (filter-kanban-by-tags entries ["kanban"])]
      (every? kanban-entry? result))))

(clojure.test.check.clojure-test/defspec prop-filter-respects-tags 100
  (clojure.test.check.properties/for-all
    [entries gen-mixed-entries]
    (let [result (filter-kanban-by-tags entries ["kanban" "todo"])]
      (every? (fn [e] (and (kanban-entry? e)
                           (contains? (set (:tags e)) "todo")))
              result))))

(clojure.test.check.clojure-test/defspec prop-filter-subset-of-input 100
  (clojure.test.check.properties/for-all
    [entries gen-mixed-entries]
    (let [result (filter-kanban-by-tags entries ["kanban"])
          input-ids (set (map :id entries))]
      (every? #(contains? input-ids (:id %)) result))))

;; =============================================================================
;; 3. Golden: global scope returns all kanban tasks
;; =============================================================================

(deftest global-scope-returns-all-kanban
  (testing "When no project filter, all kanban tasks across all projects are found"
    (let [entries [(mk-entry "a" "kanban" ["kanban" "todo" "scope:project:funeraria"])
                   (mk-entry "b" "kanban" ["kanban" "doing" "scope:project:hive-mcp"])
                   (mk-entry "c" "note" ["note" "scope:project:funeraria"])
                   (mk-entry "d" "kanban" ["kanban" "review" "scope:project:hive"])]
          result (filter-kanban-by-tags entries ["kanban"])]
      (is (= 3 (count result))
          "All kanban entries across all projects should be returned")
      (is (every? kanban-entry? result)
          "All results must be actual kanban tasks"))))

(deftest global-scope-with-status-filter
  (testing "Global scope + status filter returns only matching kanban tasks"
    (let [entries [(mk-entry "a" "kanban" ["kanban" "todo" "scope:project:funeraria"])
                   (mk-entry "b" "kanban" ["kanban" "doing" "scope:project:hive-mcp"])
                   (mk-entry "c" "kanban" ["kanban" "todo" "scope:project:hive"])]
          result (filter-kanban-by-tags entries ["kanban" "todo"])]
      (is (= 2 (count result))
          "Only todo kanban tasks should be returned"))))

(deftest non-kanban-entries-never-leak
  (testing "Entries with kanban tag but non-map content are filtered out"
    (let [entries [{:id "leak-1"
                    :content "# Implementation Plan with kanban tag"
                    :tags ["kanban" "todo"]}
                   (mk-entry "real" "kanban" ["kanban" "todo"])]
          result (filter-kanban-by-tags entries ["kanban"])]
      (is (= 1 (count result))
          "Only entries with map content and task-type kanban pass")
      (is (= "real" (:id (first result)))))))

(deftest string-content-with-kanban-tag-rejected
  (testing "Markdown content tagged kanban but without JSON task-type is rejected"
    (let [markdown-entry {:id "md-1"
                          :content "# Kanban board plan\nSome markdown"
                          :tags ["kanban" "backlog-plan"]}
          json-entry (mk-entry "json-1" "kanban" ["kanban" "todo"])]
      (is (not (kanban-entry? markdown-entry))
          "Markdown content must not pass kanban-entry? check")
      (is (kanban-entry? json-entry)
          "JSON map content with task-type kanban passes"))))
