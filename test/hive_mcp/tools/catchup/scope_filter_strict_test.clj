(ns hive-mcp.tools.catchup.scope-filter-strict-test
  "Step-10 tripwire test (per-scope wrap plan `20260504173159-46dc47f1`).

   The strict filter MUST drop entries that lack an explicit
   `scope:project:*` tag, even when their `:project-id` is in the
   visible-ids set. This is the regression that surfaces any future
   writer drift: an entry with the right project-id but a missing
   scope tag will not surface in catchup."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.catchup.scope-filter :as sf]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private hive-tags #{"scope:project:hive"})

(deftest strict--keeps-tag-matching-entries
  (let [entries [{:id 1 :tags ["scope:project:hive" "session-summary"]}
                 {:id 2 :tags ["scope:project:hive" "wrap-generated"]}]]
    (is (= [1 2]
           (mapv :id (sf/scope-filter-entries-strict entries hive-tags))))))

(deftest strict--drops-entries-with-only-project-id-fallback
  (testing "the bleed regression: pwd-derived project-id without scope tag"
    (let [entries [{:id 1 :project-id "hive" :tags []}
                   {:id 2 :project-id "hive" :tags ["session-summary"]}
                   {:id 3 :project-id "funeraria" :tags ["scope:project:funeraria"]}]]
      (is (= []
             (mapv :id (sf/scope-filter-entries-strict entries hive-tags)))
          "no entry has scope:project:hive in its tags — all dropped"))))

(deftest strict--drops-sibling-scope-entries
  (let [entries [{:id 1 :tags ["scope:project:hive"]}
                 {:id 2 :tags ["scope:project:funeraria"]}
                 {:id 3 :tags ["scope:project:sisf-crm"]}]]
    (is (= [1] (mapv :id (sf/scope-filter-entries-strict entries hive-tags)))
        "only the hive-tagged entry passes; siblings dropped")))

(deftest strict--multi-scope-tag-matches-any
  (let [scope-tags #{"scope:project:hive" "scope:project:funeraria"}
        entries [{:id 1 :tags ["scope:project:hive"]}
                 {:id 2 :tags ["scope:project:funeraria"]}
                 {:id 3 :tags ["scope:project:sisf-crm"]}
                 {:id 4 :tags ["scope:project:hive" "scope:project:funeraria"]}]]
    (is (= [1 2 4]
           (mapv :id (sf/scope-filter-entries-strict entries scope-tags))))))

(deftest strict--vs-permissive--proves-tighter-semantics
  (testing "strict drops what permissive keeps via project-id fallback"
    (let [entries [{:id 1 :tags ["scope:project:hive"]}
                   {:id 2 :project-id "hive" :tags []}]
          strict   (sf/scope-filter-entries-strict entries hive-tags)
          perms    (sf/scope-filter-entries entries hive-tags #{"hive"})]
      (is (= 1 (count strict)) "strict: only the tagged entry")
      (is (= 2 (count perms)) "permissive: tagged entry + project-id fallback")
      (is (< (count strict) (count perms))
          "strict is strictly stricter"))))

(deftest strict--empty-tags-collection-drops-entry
  (let [entries [{:id 1 :tags []}
                 {:id 2 :tags nil}
                 {:id 3} ; missing :tags entirely
                 {:id 4 :tags ["scope:project:hive"]}]]
    (is (= [4] (mapv :id (sf/scope-filter-entries-strict entries hive-tags))))))

(deftest permissive--scope-tag-is-authoritative-over-project-id
  (testing "foreign scope tag drops the entry even when :project-id is visible"
    (let [entries [{:id 1 :project-id "hive"      :tags ["scope:project:hive"]}
                   {:id 2 :project-id "hive"      :tags ["scope:project:funeraria"]}
                   {:id 3 :project-id "hive"      :tags []}
                   {:id 4 :project-id "funeraria" :tags []}]]
      (is (= [1 3]
             (mapv :id (sf/scope-filter-entries entries hive-tags #{"hive"})))))))