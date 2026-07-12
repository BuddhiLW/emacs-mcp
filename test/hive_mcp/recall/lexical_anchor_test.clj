(ns hive-mcp.recall.lexical-anchor-test
  "GOLDEN RECALL — CASE 1: LEXICAL ANCHOR.

   'A query containing a rare literal token present in a known entry must
    retrieve it.'

   This is THE case that failed on 2026-07-12. The query
   'SIGSEGV crash JDK 25 bug use Java 21' names four rare literal tokens that
   occur together in exactly one entry (20260511194834-344a3bc0), and `memory
   search` did not return it — while still returning eight confident rows with
   plausible distances.

   Driven through the REAL MCP entry point, `tools.memory.search/handle-search-
   semantic`, so the whole production path is under test: the `limit * 2`
   overfetch in `run-store-query`, the normalization in `store-entry->normalized`,
   the ASCENDING sort + `take limit` in `chroma.search/merge-and-rerank`, and
   the JSON envelope. The only thing stubbed is the network: the store is
   `recall.golden/->golden-store`, which does real cosine over a real corpus
   with a deterministic bag-of-words embedder.

   THE CANARY HAS TEETH. `canary-fires-on-the-inverted-ranking-unit` runs the
   identical pipeline against a store whose only defect is the one
   MEM-P0-EMBED-LANE fixed — a COSINE *similarity* handed across the boundary
   under the `:distance` key — and asserts the suite goes RED. A green canary
   that cannot be made to fail is a lie, and a lie is what we are here to
   remove."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.chroma.search :as chroma-search]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.recall.golden :as g]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.search :as search]))

;; =============================================================================
;; Harness — isolate the handler from KG / ingest / scope IO, nothing else
;; =============================================================================

(defn- run-with-stubs [f]
  (with-redefs [kg-edges/record-co-access!           (constantly nil)
                kg-scope/visible-scopes              (constantly ["hive"])
                kg-scope/descendant-scopes           (constantly [])
                scope/get-current-project-id         (constantly "hive")
                ctx/current-directory                (constantly "/tmp/recall")
                chroma-search/resolve-ingest-search  (constantly nil)]
    (f)))

(defmacro ^:private with-stubs [& body] `(run-with-stubs (fn [] ~@body)))

(defn- search!
  "One `memory search` call through the production handler. Returns the decoded
   JSON body, or an {:isError true} marker so a failure is never mistaken for
   an empty result set."
  [store query limit]
  (with-stubs
    (g/with-store store
      (let [resp (search/handle-search-semantic
                  {:query query :limit limit :directory "/tmp/recall"})]
        (if (:isError resp)
          {:isError true :text (:text resp)}
          (json/read-str (:text resp) :key-fn keyword))))))

;; =============================================================================
;; CASE 1 — the anchor
;; =============================================================================

(deftest lexical-anchor-is-retrieved
  (testing "the rare literal tokens of the query must surface their entry"
    (let [{:keys [results] :as body} (search! (g/->golden-store) g/anchor-query 5)]
      (is (not (:isError body))
          "a populated store must not error on a well-formed query")
      (is (nil? (g/recall-fault {:label        "case-1/lexical-anchor"
                                 :populated?   true
                                 :results      results
                                 :must-contain [g/anchor-id]}))))))

(deftest lexical-anchor-ranks-first
  (testing "not merely present — nearest. The anchor is the only entry carrying
            all four rare tokens; anything else at rank 1 means the ranking is
            being driven by stopword overlap, i.e. the vector lane is dead."
    (let [{:keys [results]} (search! (g/->golden-store) g/anchor-query 5)]
      (is (= g/anchor-id (:id (first results)))
          (str "expected the anchor at rank 1, got: " (mapv :id results))))))

(deftest results-are-ordered-nearest-first
  (testing "the pipeline's unit is DISTANCE, end to end — lower is nearer"
    (let [{:keys [results]} (search! (g/->golden-store) g/anchor-query 5)]
      (is (nil? (g/rank-fault {:label "case-1/ordering" :results results}))))))

;; =============================================================================
;; THE CANARY HAS TEETH — the identical pipeline, one broken unit
;; =============================================================================

(deftest canary-fires-on-the-inverted-ranking-unit
  (testing "a store that hands a COSINE SIMILARITY across the boundary under the
            :distance key — the exact MEM-P0-EMBED-LANE defect — must be caught.
            The store still returns its TRUE top-k; only the unit is wrong. If
            this test can be made to pass, the whole suite is decorative."
    (let [{:keys [results]} (search! (g/->golden-store :similarity) g/anchor-query 5)
          fault (g/recall-fault {:label        "case-1/inverted-unit"
                                 :populated?   true
                                 :results      results
                                 :must-contain [g/anchor-id]})]
      (is (some? fault)
          "the inverted ranking unit MUST be detectable — it was not, and that
           is why 2026-07-12 was silent")
      (is (= :recall/anchor-missing (:fault fault))
          (str "expected the confident-and-wrong shape (rows returned, anchor "
               "absent), got: " fault)))))

;; =============================================================================
;; The fault taxonomy itself — it must not cry wolf
;; =============================================================================

(deftest an-empty-store-returning-nothing-is-not-a-fault
  (testing "'no matches' from an EMPTY store is an honest query outcome. A
            canary that flags this would be ignored within a week."
    (let [{:keys [results]} (search! (g/->empty-store) g/anchor-query 5)]
      (is (empty? results))
      (is (nil? (g/recall-fault {:label        "case-1/empty-store"
                                 :populated?   false
                                 :results      results
                                 :must-contain [g/anchor-id]}))))))

(deftest an-empty-result-from-a-populated-store-is-a-system-fault
  (testing "the same empty vector, drawn from a store we KNOW holds the anchor,
            is a fault — this is the distinction the system could not make"
    (let [fault (g/recall-fault {:label        "case-1/silent-zero"
                                 :populated?   true
                                 :results      []
                                 :must-contain [g/anchor-id]})]
      (is (= :recall/empty-from-populated-store (:fault fault))))))

(deftest carto-snippets-stay-excluded-by-default
  (testing "the corpus holds a carto row carrying every one of the anchor's rare
            tokens. `run-store-query` excludes tag 'carto' by default; if that
            regressed, carto would drown the knowledge lane — which is the
            failure the exclusion was added for."
    (let [{:keys [results]} (search! (g/->golden-store) g/anchor-query 5)]
      (is (not (contains? (set (map :id results)) "carto-1"))
          (str "carto snippet leaked into memory search: " (mapv :id results))))))
