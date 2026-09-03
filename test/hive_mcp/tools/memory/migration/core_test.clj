(ns hive-mcp.tools.memory.migration.core-test
  "Unit coverage for migrate-scoped's target resolution.

   DIP-in-tests: the store is the only effect, injected by redefining the
   three protocol-facade vars migrate-scoped touches to resolve targets. No
   Milvus, no KG, no live store."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.memory.migration.core :as migration]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- with-stub-store
  "Run f with a stub store whose query-entries answers `entries`, recording
   the query maps it was asked. Returns [result queries]."
  [entries f]
  (let [queries (atom [])]
    (with-redefs [mem-proto/store-set?    (fn [] true)
                  mem-proto/get-store     (fn [] ::stub)
                  mem-proto/query-entries (fn [_store q]
                                            (swap! queries conj q)
                                            entries)]
      [(f) @queries])))

(deftest tag-filter-is-pushed-into-the-store-query
  (testing "the tag is part of the query, not a filter applied after enumerating a whole project"
    (let [[_ queries] (with-stub-store
                        []
                        #(migration/handle-migrate-scoped
                          {:old-project-id "mac"
                           :new-project-id "topic:masonry"
                           :tag-filter     "source:90b98c67cf3c9313"}))
          q (first queries)]
      (is (= 1 (count queries)) "target resolution issues exactly one query")
      (is (= "mac" (:project-id q)))
      (is (= ["source:90b98c67cf3c9313"] (:tags q))
          (str "resolving targets by post-filtering a project-wide enumeration makes the "
               "migration depend on the tagged entries falling inside that (truncated) "
               "window; the store can answer the tag directly.")))))

(deftest zero-resolved-targets-is-a-refusal-not-a-successful-no-op
  (testing "a tag-filter that matches nothing reports an error, naming the tag and project"
    (let [[res _] (with-stub-store
                    []
                    #(migration/handle-migrate-scoped
                      {:old-project-id "mac"
                       :new-project-id "topic:masonry"
                       :tag-filter     "no-such-tag"}))]
      (is (:isError res)
          (str "reporting {:migrated 0} with no error leaves the caller unable to tell "
               "'nothing matched' from 'the query shape was unsupported'"))
      (is (str/includes? (:text res) "no-such-tag"))
      (is (str/includes? (:text res) "mac")))))

(deftest resolved-targets-still-migrate
  (testing "a matching tag resolves targets and does not trip the refusal"
    (let [entries [{:id "e1" :tags ["t" "scope:project:mac"]}
                   {:id "e2" :tags ["t" "scope:project:mac"]}]
          [res _] (with-redefs [mem-proto/get-entry (fn [_ id] {:id id :tags ["t"]})]
                    (with-stub-store
                      entries
                      #(migration/handle-migrate-scoped
                        {:old-project-id "mac"
                         :new-project-id "topic:masonry"
                         :tag-filter     "t"
                         :dry-run        true})))]
      (is (not (:isError res)) "two matching entries is not a refusal")
      (is (str/includes? (:text res) "e1"))
      (is (str/includes? (:text res) "e2")))))

(deftest argument-guards-still-refuse
  (testing "the pre-existing required-argument refusals are unchanged"
    (is (:isError (migration/handle-migrate-scoped {:old-project-id "a"})))
    (is (:isError (migration/handle-migrate-scoped {:new-project-id "b"})))
    (is (:isError (migration/handle-migrate-scoped {:old-project-id "a" :new-project-id "a"
                                                    :tag-filter "t"})))
    (is (:isError (migration/handle-migrate-scoped {:old-project-id "a" :new-project-id "b"})))))
