(ns hive-mcp.tools.memory.query-id-test
  "MEM-P2-METADATA-BUG regression tests.

   Two defects, pinned here:

   (A) `memory metadata id=X` was a thin alias onto :memory/query. handle-query's
       closed destructuring drops :id, so the call degraded into an unfiltered,
       type-less in-scope scan that returned ~20 arbitrary entries — the requested
       id nowhere among them, and no error raised.

   (B) The same nil-type hole reachable without :id: a FULLY unconstrained
       `memory query` (no type, no tags, no exclude_tags, no scope, no duration)
       silently returned an arbitrary in-scope slice instead of failing loudly.

   Strategy: handler-level, with-redefs over the IMemoryStore protocol vars —
   no live Milvus/Chroma required."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-mcp.tools.consolidated.memory :as c-mem]
            [hive-mcp.tools.memory.crud.query :as query]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.agent.context :as ctx]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:private target-id "20260712120000-cafebabe")
(def ^:private missing-id "20260101000000-deadbeef")

(def ^:private target-entry
  {:id target-id
   :type "note"
   :content "The one entry the caller actually asked for"
   :tags ["target-tag" "scope:hive-mcp"]
   :project-id "hive-mcp"
   :duration "long"
   :created "2026-07-12T12:00:00Z"})

(defn- decoy-entries
  "The 20 arbitrary in-scope rows the buggy path used to return."
  []
  (mapv (fn [i]
          {:id (str "decoy-" i)
           :type "decision"
           :content (str "Unrelated decoy entry " i)
           :tags ["scope:hive-mcp"]
           :project-id "hive-mcp"
           :duration "long"
           :created "2026-07-01T00:00:00Z"})
        (range 20)))

(defn- entries-by-id [] {target-id target-entry})

(defmacro with-fake-store
  "Redef the IMemoryStore protocol vars + scope helpers.
   `query-fn` receives the query opts map and returns entries."
  [query-fn & body]
  `(with-redefs [mem-proto/store-set?   (constantly true)
                 mem-proto/get-store    (fn ([] ::fake) ([_#] ::fake))
                 mem-proto/get-entry    (fn [_store# id#] (get (entries-by-id) id#))
                 mem-proto/query-entries (fn [_store# opts#] (~query-fn opts#))
                 kg-edges/get-edges-from (constantly [])
                 kg-edges/get-edges-to   (constantly [])
                 kg-edges/record-co-access! (constantly nil)
                 kg-scope/visible-scopes (constantly ["hive-mcp"])
                 kg-scope/visible-scope-tags (constantly #{"scope:hive-mcp"})
                 kg-scope/full-hierarchy-scope-tags (constantly #{"scope:hive-mcp"})
                 kg-scope/descendant-scopes (constantly [])
                 scope/get-current-project-id (constantly "hive-mcp")
                 ctx/current-directory (constantly "/home/leibniz/PP/hive/hive-mcp")]
     ~@body))

(defn- metadata-cmd
  "Invoke the consolidated `metadata` command handler directly."
  [params]
  ((:metadata c-mem/handlers) params))

(defn- parse [resp] (json/read-str (:text resp) :key-fn keyword))

;; =============================================================================
;; T1 — metadata id=X returns exactly X
;; =============================================================================

(deftest metadata-by-id-returns-requested-entry
  (testing "metadata with :id returns exactly the requested entry, not an unfiltered scan"
    (with-fake-store (fn [_opts] (decoy-entries))
      (let [resp (metadata-cmd {:command "metadata" :id target-id})]
        (is (not (:isError resp)) "by-id metadata must succeed")
        (let [body (parse resp)]
          (is (= 1 (count body)) "exactly one entry, not a 20-row scan")
          (is (= target-id (:id (first body))) "the entry returned is the one requested")
          (is (= "note" (:type (first body))) "metadata shape retains :type"))))))

;; =============================================================================
;; T2 — metadata with an unknown id errors; never an unrelated result set
;; =============================================================================

(deftest metadata-unknown-id-errors-and-returns-no-rows
  (testing "metadata with a well-formed but nonexistent id fails loudly"
    (with-fake-store (fn [_opts] (decoy-entries))
      (let [resp (metadata-cmd {:command "metadata" :id missing-id})]
        (is (true? (:isError resp)) "unknown id must be an explicit error")
        ;; Load-bearing: it must ALSO not be a populated result list. An empty
        ;; vector would be a silent-wrong-answer regression sneaking back in.
        (let [body (try (json/read-str (:text resp) :key-fn keyword)
                        (catch Exception _ ::not-json))]
          (is (not (and (sequential? body) (seq body)))
              "response must not be a populated entry list"))))))

(deftest metadata-blank-id-errors
  (testing "metadata with a blank id is rejected rather than silently browsing"
    (with-fake-store (fn [_opts] (decoy-entries))
      (let [resp (metadata-cmd {:command "metadata" :id "   "})]
        (is (true? (:isError resp)))))))

;; =============================================================================
;; T3 — fully-unconstrained query fails loudly (defect B)
;; =============================================================================

(deftest unconstrained-query-fails-loudly
  (testing "handle-query with no type/tags/exclude_tags/scope/duration is an error, not a scan"
    (with-fake-store (fn [_opts] (decoy-entries))
      (let [resp (query/handle-query {})]
        (is (true? (:isError resp)) "fully-unconstrained query must fail loudly")
        (let [body (try (json/read-str (:text resp) :key-fn keyword)
                        (catch Exception _ ::not-json))]
          (is (not (and (sequential? body) (seq body)))
              "must not return an arbitrary in-scope slice"))))))

(deftest text-on-structured-query-fails-loudly
  (testing "query text cannot be ignored while scope=all returns an arbitrary corpus slice"
    (let [store-calls (atom 0)]
      (with-fake-store (fn [_opts]
                         (swap! store-calls inc)
                         (decoy-entries))
        (let [resp (query/handle-query {:query "Stratified Design"
                                        :scope "all"
                                        :limit 500})]
          (is (true? (:isError resp)))
          (is (zero? @store-calls) "invalid semantic intent must not touch the scalar store")
          (is (re-find #"command=search" (:text resp))
              "error must name the correct semantic command"))))))

;; =============================================================================
;; Regression fence for (B): legitimate narrowing predicates still browse
;; =============================================================================

(deftest tag-only-query-still-returns-rows
  (testing "type-less tag-only query keeps working (guard must not be too broad)"
    (with-fake-store (fn [_opts] [target-entry])
      (let [resp (query/handle-query {:tags ["target-tag"]})]
        (is (not (:isError resp)))
        (let [body (parse resp)]
          (is (= 1 (count body)))
          (is (= target-id (:id (first body)))))))))

(deftest scope-only-query-still-returns-rows
  (testing "type-less scope-only query keeps working"
    (with-fake-store (fn [_opts] [target-entry])
      (let [resp (query/handle-query {:scope "all"})]
        (is (not (:isError resp)))
        (is (= 1 (count (parse resp))))))))

(deftest typed-query-still-returns-rows
  (testing "the ordinary typed query path is untouched"
    (with-fake-store (fn [_opts] [target-entry])
      (let [resp (query/handle-query {:type "note"})]
        (is (not (:isError resp)))
        (is (= 1 (count (parse resp))))))))
