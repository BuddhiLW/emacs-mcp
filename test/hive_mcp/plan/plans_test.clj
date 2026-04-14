(ns hive-mcp.plan.plans-test
  "Tests for hive-mcp.plan.plans.

   Focus: the `get-or-create-collection` private fn and its
   `cache-and-return-collection!` helper, which DRYs the
   dimension-mismatch recreate branch and the fresh-create branch.

   AXIOM COMPLIANCE:
   - Tests run via nREPL, NOT bash.
   - Provider-side chroma calls are stubbed with `with-redefs`; no
     network / Chroma server required."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.plan.plans :as plans]
            [hive-mcp.chroma.core :as chroma]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn reset-cache-fixture [f]
  (plans/reset-collection-cache!)
  (f)
  (plans/reset-collection-cache!))

(use-fixtures :each reset-cache-fixture)

;; =============================================================================
;; Private fn accessors (tests need to poke at private helpers)
;; =============================================================================

(def get-or-create-collection
  #'hive-mcp.plan.plans/get-or-create-collection)

(def cache-and-return-collection!
  #'hive-mcp.plan.plans/cache-and-return-collection!)

(def try-get-existing-collection
  #'hive-mcp.plan.plans/try-get-existing-collection)

(def create-collection-with-dimension
  #'hive-mcp.plan.plans/create-collection-with-dimension)

(def delete-collection!
  #'hive-mcp.plan.plans/delete-collection!)

(def collection-cache
  #'hive-mcp.plan.plans/collection-cache)

;; =============================================================================
;; cache-and-return-collection! helper
;; =============================================================================

(deftest cache-and-return-collection!-test
  (testing "caches collection and returns it (action :created)"
    (plans/reset-collection-cache!)
    (let [fake-coll {:id "fake-coll" :metadata {:dimension 768}}
          returned  (cache-and-return-collection! fake-coll 768 :created)]
      (is (= fake-coll returned) "returns the collection unchanged")
      (is (= fake-coll @@collection-cache) "caches the collection")))

  (testing "action :recreated also caches and returns"
    (plans/reset-collection-cache!)
    (let [fake-coll {:id "re-coll" :metadata {:dimension 4096}}
          returned  (cache-and-return-collection! fake-coll 4096 :recreated)]
      (is (= fake-coll returned))
      (is (= fake-coll @@collection-cache)))))

;; =============================================================================
;; get-or-create-collection dispatch
;; =============================================================================

(deftest get-or-create-collection-cache-hit-test
  (testing "when cache hit, returns cached value WITHOUT calling provider"
    (let [cached      {:id "cached" :metadata {:dimension 768}}
          called-prov (atom false)
          called-ext  (atom false)
          called-crt  (atom false)]
      (reset! @collection-cache cached)
      (with-redefs [chroma/get-provider-for                        (fn [_] (reset! called-prov true) :mock-provider)
                    chroma/embedding-dimension                     (fn [_] (reset! called-prov true) 768)
                    hive-mcp.plan.plans/try-get-existing-collection (fn [] (reset! called-ext true) nil)
                    hive-mcp.plan.plans/create-collection-with-dimension (fn [_] (reset! called-crt true) nil)]
        (is (= cached (get-or-create-collection)))
        (is (false? @called-prov) "provider not consulted")
        (is (false? @called-ext)  "existing-collection not consulted")
        (is (false? @called-crt)  "create-collection not called")))))

(deftest get-or-create-collection-fresh-create-test
  (testing "no cache, no existing collection -> fresh create path"
    (plans/reset-collection-cache!)
    (let [fresh       {:id "fresh" :metadata {:dimension 4096}}
          create-args (atom nil)]
      (with-redefs [chroma/get-provider-for                             (fn [_] :mock-provider)
                    chroma/embedding-dimension                          (fn [_] 4096)
                    hive-mcp.plan.plans/try-get-existing-collection     (fn [] nil)
                    hive-mcp.plan.plans/create-collection-with-dimension (fn [dim]
                                                                           (reset! create-args dim)
                                                                           fresh)
                    hive-mcp.plan.plans/delete-collection!              (fn [] (throw (ex-info "should not delete" {})))]
        (let [result (get-or-create-collection)]
          (is (= fresh result) "returns newly created collection")
          (is (= 4096 @create-args) "create called with required dim")
          (is (= fresh @@collection-cache) "collection cached (via helper)"))))))

(deftest get-or-create-collection-dim-mismatch-recreate-test
  (testing "no cache, existing with wrong dim -> delete + recreate path"
    (plans/reset-collection-cache!)
    (let [stale        {:id "stale" :metadata {:dimension 768}}
          fresh        {:id "fresh" :metadata {:dimension 4096}}
          called-del   (atom false)
          create-args  (atom nil)
          ext-calls    (atom 0)]
      (with-redefs [chroma/get-provider-for                             (fn [_] :mock-provider)
                    chroma/embedding-dimension                          (fn [_] 4096)
                    hive-mcp.plan.plans/try-get-existing-collection     (fn []
                                                                          (swap! ext-calls inc)
                                                                          stale)
                    hive-mcp.plan.plans/delete-collection!              (fn []
                                                                          (reset! called-del true)
                                                                          true)
                    hive-mcp.plan.plans/create-collection-with-dimension (fn [dim]
                                                                           (reset! create-args dim)
                                                                           fresh)]
        (let [result (get-or-create-collection)]
          (is (= fresh result)                  "returns NEW collection, not stale")
          (is (true? @called-del)               "delete-collection! was called")
          (is (= 4096 @create-args)             "create called with new dim")
          (is (= fresh @@collection-cache)      "new collection cached (via helper)"))))))

(deftest get-or-create-collection-dim-match-reuse-test
  (testing "no cache, existing with matching dim -> reuse path (no recreate)"
    (plans/reset-collection-cache!)
    (let [existing   {:id "existing" :metadata {:dimension 4096}}
          called-crt (atom false)
          called-del (atom false)]
      (with-redefs [chroma/get-provider-for                             (fn [_] :mock-provider)
                    chroma/embedding-dimension                          (fn [_] 4096)
                    hive-mcp.plan.plans/try-get-existing-collection     (fn [] existing)
                    hive-mcp.plan.plans/delete-collection!              (fn [] (reset! called-del true) true)
                    hive-mcp.plan.plans/create-collection-with-dimension (fn [_] (reset! called-crt true) nil)]
        (let [result (get-or-create-collection)]
          (is (= existing result)               "returns existing collection")
          (is (false? @called-crt)              "no create when dim matches")
          (is (false? @called-del)              "no delete when dim matches")
          (is (= existing @@collection-cache)   "existing collection cached"))))))
