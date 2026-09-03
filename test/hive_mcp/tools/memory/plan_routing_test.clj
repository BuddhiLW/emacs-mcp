(ns hive-mcp.tools.memory.plan-routing-test
  "Tests for plan type routing in the CRUD and search handlers.

   Post-IMemoryStore-unification there is ONE path: `type=\"plan\"` is stored,
   queried and searched through the same port as every other type. These tests
   assert that by observing the registered IMemoryStore, so nothing here names
   a backend."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.tools.memory.crud :as crud]
            [hive-mcp.tools.memory.search :as search]
            [hive-mcp.plan.tool :as plan-tool]
            [hive-spi.memory.ports :as ports]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]
            [hive-mcp.test.stub.memory-store :as mem-stub]
            [hive-mcp.plan.parser]
            [hive-mcp.tools.memory-kanban]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ============================================================
;; Fixture — an observing stub on the port
;; ============================================================

(def ^:dynamic *store* nil)

(defn- with-observing-store
  [f]
  (mem-stub/with-stub-store
    (fn []
      (let [store (mem-stub/->observing (mem-stub/->stub))]
        (mem-stub/install! store)
        (binding [*store* store] (f))))))

(use-fixtures :each with-observing-store (iso/with-isolations :kg-conn))

(defn- port-calls
  "Ops recorded on the observed store, in order."
  [op]
  (mem-stub/calls-of *store* op))

(defn- seed!
  "Add ENTRY through the port, bypassing the observation log."
  [entry]
  (ports/add-entry! (:inner *store*) entry))

;; ============================================================
;; Mock Data
;; ============================================================

(def ^:private plan-content
  (pr-str {:id "plan-routing-test"
           :title "Routing test plan"
           :steps [{:id "s1" :title "Do X"}
                   {:id "s2" :title "Do Y" :depends-on ["s1"]}
                   {:id "s3" :title "Do Z" :depends-on ["s2"]}]}))

(def ^:private mock-memory-entry
  {:id "20260206-test-note"
   :type "note"
   :content "Some note content"
   :tags ["test" "scope:hive-mcp"]
   :project-id "hive-mcp"
   :duration "long"
   :expires ""
   :abstraction-level 2})

;; ============================================================
;; handle-add
;; ============================================================

(deftest handle-add-routes-plan-through-the-memory-port
  (testing "type=plan is written through IMemoryStore add-entry!, like every other type"
    (let [result (crud/handle-add {:type "plan"
                                   :content plan-content
                                   :tags []
                                   :duration "long"
                                   :directory "/tmp/test"})
          added  (port-calls :add-entry!)]
      (is (not (:isError result)))
      (is (= 1 (count added))
          "plan is stored through the port exactly once")
      (is (= "plan" (:type (ffirst added)))
          "the entry reaches the port still typed as a plan"))))

(deftest handle-add-routes-note-through-the-memory-port
  (testing "type=note is written through the same port"
    (let [result (crud/handle-add {:type "note"
                                   :content "Some note"
                                   :tags []
                                   :duration "long"
                                   :directory "/tmp/test"})
          added  (port-calls :add-entry!)]
      (is (not (:isError result)))
      (is (= 1 (count added)))
      (is (= "note" (:type (ffirst added)))))))

;; ============================================================
;; handle-query
;; ============================================================

(deftest handle-query-reads-through-the-memory-port
  (testing "handle-query resolves entries through IMemoryStore query-entries"
    (seed! mock-memory-entry)
    (let [result (crud/handle-query {:type "note" :directory "/tmp/test"})]
      (is (not (:isError result)))
      (is (pos? (count (port-calls :query-entries)))
          "the query reaches the port"))))

;; ============================================================
;; handle-get-full
;; ============================================================

(deftest handle-get-full-reads-through-the-memory-port
  (testing "get-full resolves the entry through the port"
    (seed! mock-memory-entry)
    (let [result (crud/handle-get-full {:id (:id mock-memory-entry)})
          parsed (json/read-str (:text result) :key-fn keyword)]
      (is (not (:isError result)))
      (is (not= "Entry not found" (:error parsed)))
      (is (pos? (count (port-calls :get-entry)))))))

(deftest handle-get-full-returns-not-found-when-missing
  (testing "get-full reports not-found for an id the port does not hold"
    (let [result (crud/handle-get-full {:id "nonexistent"})
          parsed (json/read-str (:text result) :key-fn keyword)]
      (is (not (:isError result)))
      (is (= "Entry not found" (:error parsed))))))

;; ============================================================
;; handle-search-semantic
;; ============================================================

(deftest handle-search-semantic-searches-through-the-memory-port
  (testing "typed semantic search reaches IMemoryStore search-similar"
    (seed! mock-memory-entry)
    (let [result (search/handle-search-semantic {:query "some note content"
                                                 :type "note"
                                                 :directory "/tmp/test"})]
      (is (not (:isError result)))
      (is (pos? (count (port-calls :search-similar)))))))

(deftest handle-search-semantic-nil-type-searches-through-the-memory-port
  (testing "untyped semantic search takes the same path"
    (seed! mock-memory-entry)
    (let [result (search/handle-search-semantic {:query "something"
                                                 :directory "/tmp/test"})]
      (is (not (:isError result)))
      (is (pos? (count (port-calls :search-similar)))))))

;; ============================================================
;; plan-to-kanban
;; ============================================================

(deftest plan-to-kanban-resolves-through-the-memory-port
  (testing "plan-to-kanban resolves the plan entry through IMemoryStore — single path"
    (seed! {:id "test-plan-id"
            :content "# Plan\n1. Step A"
            :type "plan"
            :project-id "hive-mcp"})
    (with-redefs [hive-mcp.plan.parser/parse-plan
                  (fn [_content _opts]
                    {:success true
                     :plan {:id "test-plan-id"
                            :title "Test Plan"
                            :steps [{:id "step-1" :title "Step A" :depends-on []}]}})
                  hive-mcp.tools.memory-kanban/handle-mem-kanban-create
                  (fn [{:keys [title]}]
                    {:type "text"
                     :text (json/write-str {:id (str "task-" (hash title))})})]
      (let [result (plan-tool/plan-to-kanban "test-plan-id" :directory "/tmp/test")]
        (is (not (:isError result)))
        (is (pos? (count (port-calls :get-entry)))
            "the port is the only resolver")))))
