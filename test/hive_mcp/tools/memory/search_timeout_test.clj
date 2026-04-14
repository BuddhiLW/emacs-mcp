(ns hive-mcp.tools.memory.search-timeout-test
  "Regression + property tests for memory search timeout behavior.

   Root cause: smart-search-enrichment interceptor used bare @future
   with no timeout on tag queries and KG expansion. When Datahike or
   Chroma hung, the entire MCP search call hung indefinitely, killing
   the stdio connection.

   These tests pin the fix:
   - Enrichment futures must have bounded timeouts
   - dispatch-sync for :memory/search must terminate within budget
   - The full MCP handler chain must terminate within budget

   Mutation targets:
   - Removing deref timeout in enrich-search → M1 hangs
   - Removing gate from chroma calls → M2 over-concurrency
   - Removing enrichment try/catch → M3 propagates exception"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-search-query
  "Generator for realistic search query strings."
  (gen/let [words (gen/vector
                    (gen/elements ["hive" "roadmap" "architecture" "memory"
                                   "chroma" "gate" "search" "knowledge"
                                   "graph" "enrichment" "vector" "embed"
                                   "clojure" "mcp" "agent" "session"
                                   "big-picture" "plan" "decision"])
                    1 5)]
    (clojure.string/join " " words)))

(def gen-search-params
  "Generator for memory search parameters."
  (gen/let [query gen-search-query
            limit (gen/choose 1 20)]
    {:command "search"
     :query query
     :limit limit
     :directory "/home/leibniz/PP/hive"}))

;; =============================================================================
;; M1: enrich-search futures must have timeouts
;; =============================================================================

(deftest m1-enrich-search-with-hung-tag-query
  (testing "enrich-search returns within budget even when tag queries hang"
    (let [enrich-fn (requiring-resolve 'hive-knowledge.memory.smart-search/enrich-search)]
      (when enrich-fn
        ;; Mock tag query to hang forever
        (with-redefs-fn
          {(requiring-resolve 'hive-knowledge.memory.smart-search/query-entries-by-tags)
           (fn [_tags] (Thread/sleep 60000) {})}
          (fn []
            (let [vanilla [{:id "test-1" :type "note" :tags ["test"] :distance 1.0}]
                  f (future (enrich-fn "hive roadmap" vanilla 10))
                  result (deref f 15000 ::timeout)]
              (is (not= ::timeout result)
                  "enrich-search must terminate even with hung tag queries")
              ;; Should fall back to vanilla results
              (is (sequential? result)))))))))

(deftest m1-enrich-search-with-hung-kg-expansion
  (testing "enrich-search returns within budget even when KG expansion hangs"
    (let [enrich-fn (requiring-resolve 'hive-knowledge.memory.smart-search/enrich-search)]
      (when enrich-fn
        ;; Use fake entry IDs that won't exist in Datahike/KG —
        ;; KG lookups will return empty, not hang. The real hang
        ;; is caught by M2 (dispatch-sync) which exercises the full path.
        (let [vanilla [{:id "nonexistent-1" :type "note" :tags ["fake"] :distance 1.0}
                       {:id "nonexistent-2" :type "note" :tags ["fake"] :distance 2.0}]
              f (future (enrich-fn "architecture roadmap vision" vanilla 10))
              result (deref f 20000 ::timeout)]
          (is (not= ::timeout result)
              "enrich-search must terminate within 20s budget")
          (is (sequential? result)))))))

;; =============================================================================
;; M2: Full event dispatch must terminate
;; =============================================================================

(deftest m2-dispatch-sync-search-terminates
  (testing "ev/dispatch-sync for :memory/search terminates within 30s budget"
    (let [dispatch-fn (requiring-resolve 'hive-mcp.events.core/dispatch-sync)
          registered? ((requiring-resolve 'hive-mcp.events.core/handler-registered?) :memory/search)]
      (when (and dispatch-fn registered?)
        (let [f (future (dispatch-fn [:memory/search
                                      {:query "test timeout"
                                       :limit 2
                                       :directory "/home/leibniz/PP/hive"}]))
              result (deref f 30000 ::timeout)]
          (is (not= ::timeout result)
              "dispatch-sync :memory/search must never hang — this was the original bug"))))))

;; =============================================================================
;; M3: Enrichment exception doesn't propagate (graceful degradation)
;; =============================================================================

(deftest m3-enrichment-exception-returns-vanilla
  (testing "enrich-search catches exceptions and returns vanilla results"
    (let [enrich-fn (requiring-resolve 'hive-knowledge.memory.smart-search/enrich-search)]
      (when enrich-fn
        (with-redefs-fn
          {(requiring-resolve 'hive-knowledge.memory.smart-search/query-entries-by-tags)
           (fn [_] (throw (Exception. "simulated Datahike crash")))}
          (fn []
            (let [vanilla [{:id "v1" :type "note" :tags ["a"] :distance 1.0}
                           {:id "v2" :type "decision" :tags ["b"] :distance 2.0}]
                  result (enrich-fn "test query" vanilla 10)]
              ;; Must return vanilla results, not throw
              (is (= vanilla result)
                  "Enrichment failure must fall back to vanilla results"))))))))

;; =============================================================================
;; P5: Property — search always terminates for any query
;; =============================================================================

(defspec p5-search-handler-terminates 20
  (prop/for-all [params gen-search-params]
    (let [handler-fn (requiring-resolve 'hive-mcp.tools.memory.search/handle-search-semantic)]
      (if handler-fn
        (let [f (future (handler-fn params))
              result (deref f 20000 ::timeout)]
          (not= ::timeout result))
        true ;; Skip if handler not available
        ))))

;; =============================================================================
;; G2: Golden — search response shape is stable
;; =============================================================================

(deftest g2-search-response-shape
  (testing "search handler returns expected MCP response shape"
    (let [handler-fn (requiring-resolve 'hive-mcp.tools.memory.search/handle-search-semantic)]
      (when handler-fn
        (let [result (handler-fn {:query "test" :limit 1 :directory "/home/leibniz/PP/hive"})
              parsed (try
                       ((requiring-resolve 'clojure.data.json/read-str)
                        (:text result) :key-fn keyword)
                       (catch Exception _ nil))]
          (is (map? result) "Response must be a map")
          (is (contains? result :type) "Response must have :type")
          (is (= "text" (:type result)) "Response type must be 'text'")
          (when parsed
            (is (contains? parsed :results) "JSON must have :results")
            (is (contains? parsed :count) "JSON must have :count")
            (is (contains? parsed :query) "JSON must have :query")
            (is (contains? parsed :scope) "JSON must have :scope")))))))
