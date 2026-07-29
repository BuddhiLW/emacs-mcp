(ns hive-mcp.tools.catchup.catchup-golden-test
  "Golden/characterization tests for catchup format functions.

   Pins the structural shape of build-catchup-response and
   serialize-spawn-context so refactors that change the response
   contract are caught immediately.

   Run with UPDATE_GOLDEN=true to regenerate snapshots after intentional changes.

   Golden files: test/golden/catchup/"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-test.golden :refer [deftest-golden]]
            [hive-mcp.tools.catchup.format :as fmt]))

;; =============================================================================
;; Shape extraction helpers
;; =============================================================================

(defn- extract-block-shape
  "Parse a content block's JSON text and extract structural shape:
   block name, top-level key set, and counts sub-key set (if present)."
  [{:keys [type text]}]
  (let [parsed (json/read-str text :key-fn keyword)]
    (cond-> {:type       type
             :_block     (:_block parsed)
             :key-set    (set (keys parsed))}
      (:counts parsed)
      (assoc :counts-keys (set (keys (:counts parsed))))

      (:memory-piggyback parsed)
      (assoc :piggyback-keys (set (keys (:memory-piggyback parsed))))

      (:context parsed)
      (assoc :context-keys (set (keys (:context parsed))))

      (:kg-insights parsed)
      (assoc :kg-insights-keys (set (keys (:kg-insights parsed)))))))

(defn- extract-response-shape
  "Extract the structural shape of a full catchup response (vector of blocks).
   Returns {:block-count N, :blocks [<shape per block>]}."
  [response]
  {:block-count (count response)
   :blocks      (mapv extract-block-shape response)})

;; =============================================================================
;; Test data
;; =============================================================================

(def catchup-input
  "Deterministic input for build-catchup-response. No I/O, no clock, no store."
  {:scopes           ["scope:project:golden"]
   :project-name     "golden"
   :git-info         {:branch "main" :uncommitted false :last-commit "abc1234"}
   :counts           {:axioms 1 :principles 1 :priority-principles 1
                      :conventions 1 :priority-conventions 1 :decisions 1
                      :snippets 1 :sessions 1 :recent-wraps 1 :expiring 1}
   :axioms-meta      []
   :principles-meta  []
   :priority-principles-meta []
   :priority-conventions-meta []
   :decisions-meta   []
   :sessions-meta    [{:id "s1" :T "note" :P "session one"}]
   :recent-wraps     []
   :conventions-meta []
   :snippets-meta    []
   :expiring-meta    []
   :kg-insights      {:stale-files        ["src/a.clj" "src/b.clj"]
                      :contradictions     []
                      :superseded         []
                      :grounding-warnings {:stale-entries []}}
   :project-tree-scan nil
   :disc-decay       nil
   :context-refs     nil})

(def ^:private spawn-input
  "Deterministic input for serialize-spawn-context — pure function, no mocking."
  {:axioms                [{:content "Always use TDD"}]
   :priority-conventions  [{:content "Run catchup first"}]
   :decisions             [{:preview "Use Milvus for vectors"}]
   :git-info              {:branch "main" :uncommitted false :last-commit "abc - test"}
   :project-name          "golden-proj"
   :stale-files           []})

;; =============================================================================
;; Golden: build-catchup-response structural shape
;; =============================================================================

(deftest-golden catchup-response-shape
  "test/golden/catchup/response-shape.edn"
  (extract-response-shape (fmt/build-catchup-response catchup-input)))

;; =============================================================================
;; Golden: serialize-spawn-context full output
;; =============================================================================

(deftest-golden spawn-context-full
  "test/golden/catchup/spawn-context-full.edn"
  (fmt/serialize-spawn-context spawn-input))
