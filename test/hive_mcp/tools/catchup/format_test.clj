(ns hive-mcp.tools.catchup.format-test
  "Tests for catchup formatting and rendering functions.
   Extracted from catchup.clj in Sprint 1 refactoring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-mcp.tools.catchup.format :as fmt]))

;; =============================================================================
;; Entry Metadata Transform Tests
;; =============================================================================

(deftest entry->catchup-meta-test
  (testing "lean format: id + abbreviated type + preview, no tags"
    (let [entry {:id "test-1" :type "decision" :content "This is a test decision" :tags ["foo" "bar"]}
          result (fmt/entry->catchup-meta entry 80)]
      (is (= "test-1" (:id result)))
      (is (= "decision" (:T result)))
      (is (= "This is a test decision" (:P result)))
      (is (nil? (:tags result)) "tags omitted for leanness")))

  (testing "truncation at preview-len"
    (let [long-content (apply str (repeat 200 "x"))
          entry {:id "test-2" :type "note" :content long-content :tags []}
          result (fmt/entry->catchup-meta entry 50)]
      (is (= 50 (count (:P result))))))

  (testing "nil preview-len defaults to 80"
    (let [long-content (apply str (repeat 200 "x"))
          entry {:id "test-3" :type "note" :content long-content :tags []}
          result (fmt/entry->catchup-meta entry nil)]
      (is (= 80 (count (:P result))))))

  (testing "non-string content is converted"
    (let [entry {:id "test-4" :type "note" :content 42 :tags []}
          result (fmt/entry->catchup-meta entry 80)]
      (is (= "42" (:P result)))))

  (testing "nil type defaults to 'note'"
    (let [entry {:id "test-5" :content "test" :tags []}
          result (fmt/entry->catchup-meta entry 80)]
      (is (= "note" (:T result))))))

(deftest entry->axiom-meta-test
  (testing "axiom metadata with full content"
    (let [entry {:id "ax-1" :tags ["axiom" "critical"] :content "Never do X"}
          result (fmt/entry->axiom-meta entry)]
      (is (= "ax-1" (:id result)))
      (is (= "axiom" (:type result)))
      (is (= "INVIOLABLE" (:severity result)))
      (is (= "Never do X" (:content result)))
      (is (= ["axiom" "critical"] (:tags result)))))

  (testing "nil tags default to empty vector"
    (let [entry {:id "ax-2" :content "Rule"}
          result (fmt/entry->axiom-meta entry)]
      (is (= [] (:tags result))))))

(deftest entry->priority-meta-test
  (testing "priority convention with full content"
    (let [entry {:id "prio-1" :tags ["catchup-priority"] :content "Always do Y"}
          result (fmt/entry->priority-meta entry)]
      (is (= "prio-1" (:id result)))
      (is (= "convention" (:type result)))
      (is (= "Always do Y" (:content result)))
      (is (= ["catchup-priority"] (:tags result))))))

;; =============================================================================
;; Content Budget Helper Tests
;; =============================================================================

(deftest cap-axiom-content-test
  (testing "short axiom passes through unchanged"
    (let [entry {:id "ax-1" :content "Short rule" :tags ["axiom"]}
          result (fmt/cap-axiom-content entry)]
      (is (= "Short rule" (:content result)))))

  (testing "long axiom is truncated with retrieval hint"
    (let [long-content (apply str (repeat 700 "x"))
          entry {:id "ax-2" :content long-content :tags ["axiom"]}
          result (fmt/cap-axiom-content entry)]
      (is (< (count (:content result)) (+ fmt/axiom-content-cap 100)))
      (is (.contains (:content result) "[TRUNCATED"))
      (is (.contains (:content result) "ax-2"))))

  (testing "exactly at cap passes through"
    (let [exact-content (apply str (repeat 600 "a"))
          entry {:id "ax-3" :content exact-content}
          result (fmt/cap-axiom-content entry)]
      (is (= exact-content (:content result))))))

(deftest entry->wrap-preview-test
  (testing "long wrap content truncated to wrap-preview-cap; no :content key"
    (let [long (apply str (repeat 500 "z"))
          result (fmt/entry->wrap-preview {:id "w1" :created "2026-07-07"
                                           :tags ["wrap-generated"] :content long})]
      (is (= "w1" (:id result)))
      (is (= "2026-07-07" (:created result)))
      (is (= ["wrap-generated"] (:tags result)))
      (is (= fmt/wrap-preview-cap (count (:preview result))))
      (is (not (contains? result :content)) "full body dropped from inline block")))

  (testing "short content passes through; nil tags default to []"
    (let [result (fmt/entry->wrap-preview {:id "w2" :content "tiny"})]
      (is (= "tiny" (:preview result)))
      (is (= [] (:tags result))))))

(deftest cap-piggyback-entry-test
  (testing "axiom content is NEVER capped (word-for-word, any size)"
    (let [long (apply str (repeat 3000 "a"))
          result (fmt/cap-piggyback-entry {:id "ax" :type "axiom" :content long})]
      (is (= long (:content result)))
      (is (= 3000 (count (:content result))))))

  (testing "non-axiom content capped at axiom-content-cap with retrieval hint"
    (let [long (apply str (repeat 1000 "p"))
          result (fmt/cap-piggyback-entry {:id "pr" :type "principle" :content long})]
      (is (.contains (:content result) "[TRUNCATED"))
      (is (.contains (:content result) "pr"))
      (is (< (count (:content result)) (+ fmt/axiom-content-cap 100)))))

  (testing "short non-axiom passes through unchanged"
    (is (= "small" (:content (fmt/cap-piggyback-entry
                              {:id "c" :type "convention" :content "small"}))))))

(deftest trim-kg-insights-test
  (testing "nil insights returns nil"
    (is (nil? (fmt/trim-kg-insights nil))))

  (testing "trims stale-files to 5"
    (let [insights {:stale-files (vec (range 20))}
          result (fmt/trim-kg-insights insights)]
      (is (= 5 (count (:stale-files result))))))

  (testing "trims contradictions and superseded to 5"
    (let [insights {:contradictions (vec (range 10))
                    :superseded (vec (range 10))}
          result (fmt/trim-kg-insights insights)]
      (is (= 5 (count (:contradictions result))))
      (is (= 5 (count (:superseded result))))))

  (testing "trims grounding-warnings.stale-entries to 10"
    (let [insights {:grounding-warnings {:stale-entries (vec (range 20))}}
          result (fmt/trim-kg-insights insights)]
      (is (= 10 (count (get-in result [:grounding-warnings :stale-entries]))))))

  (testing "passes through insights with small lists unchanged"
    (let [insights {:stale-files [1 2] :contradictions [3]}
          result (fmt/trim-kg-insights insights)]
      (is (= [1 2] (:stale-files result)))
      (is (= [3] (:contradictions result))))))

;; =============================================================================
;; Response Builder Tests
;; =============================================================================

(deftest build-catchup-response-test
  (testing "returns a vector of 4 content blocks (axioms/priority via memory piggyback)"
    (let [resp (fmt/build-catchup-response
                {:project-name "test-proj" :project-id "test-proj"
                 :scopes ["scope:project:test-proj"]
                 :git-info {:branch "main" :uncommitted false}
                 :permeation {:permeated 0 :agents []}
                 :axioms-meta [{:id "a1" :content "Never do X" :tags ["axiom"] :severity "INVIOLABLE"}]
                 :priority-meta [{:id "p1" :content "Always do Y" :tags ["catchup-priority"] :type "convention"}]
                 :sessions-meta [{:id "s1"} {:id "s2"}]
                 :decisions-meta [{:id "d1"}]
                 :conventions-meta []
                 :snippets-meta []
                 :expiring-meta [{:id "e1"}]
                 :kg-insights {:edge-count 5}
                 :project-tree-scan {:scanned true}
                 :disc-decay {:updated 3 :skipped 0}})]
      (is (vector? resp))
      (is (= 4 (count resp)))

      ;; Each block is {:type "text" :text "<json>"}
      (doseq [block resp]
        (is (= "text" (:type block)))
        (is (string? (:text block))))

      ;; Block 0: header with memory-piggyback status
      (let [header (json/read-str (:text (nth resp 0)) :key-fn keyword)]
        (is (= "header" (:_block header)))
        (is (true? (:success header)))
        (is (= "test-proj" (:project header)))
        (is (= 1 (get-in header [:counts :axioms])))
        (is (= 2 (get-in header [:counts :sessions])))
        (is (= 1 (get-in header [:counts :decisions])))
        (is (= 1 (get-in header [:counts :expiring])))
        ;; Memory piggyback replaces follow-up-queries
        (is (nil? (:follow-up-queries header)))
        (is (= 2 (get-in header [:memory-piggyback :enqueued])))
        (is (string? (get-in header [:memory-piggyback :note]))))

      ;; Block 1: context (sessions only, rest via piggyback/context-refs)
      (let [ctx-block (json/read-str (:text (nth resp 1)) :key-fn keyword)]
        (is (= "context" (:_block ctx-block)))
        (is (= 2 (count (get-in ctx-block [:context :sessions]))))
        (is (nil? (get-in ctx-block [:context :decisions])) "decisions not inline — use context-refs"))

      ;; Block 2: kg-insights
      (let [kg-block (json/read-str (:text (nth resp 2)) :key-fn keyword)]
        (is (= "kg-insights" (:_block kg-block)))
        (is (= 5 (get-in kg-block [:kg-insights :edge-count]))))

      ;; Block 3: meta (last - piggyback target)
      (let [meta-block (json/read-str (:text (nth resp 3)) :key-fn keyword)]
        (is (= "meta" (:_block meta-block)))
        (is (= 3 (get-in meta-block [:disc-decay :updated])))
        (is (string? (:hint meta-block)))
        (is (.contains (:hint meta-block) "MEMORY"))))))

(deftest unavailable-memory-is-not-empty-memory-test
  (testing "timeout or error omits counts, sessions, wraps, KG, and context claims"
    (let [response (fmt/build-catchup-response
                    {:project-name "test-proj"
                     :project-id "test-proj"
                     :scopes ["scope:project:test-proj"]
                     :git-info {:branch "main" :uncommitted false}
                     :memory-status {:status :error
                                     :warnings [{:status :error
                                                 :label "bundle"
                                                 :message "failed"}]}
                     :axioms-meta []
                     :principles-meta []
                     :priority-principles-meta []
                     :priority-meta []
                     :sessions-meta []
                     :decisions-meta []
                     :conventions-meta []
                     :snippets-meta []
                     :expiring-meta []
                     :recent-wraps []
                     :context-refs {:axioms "ctx-empty"}
                     :kg-insights nil})
          blocks (mapv #(json/read-str (:text %) :key-fn keyword) response)
          by-kind (into {} (map (juxt :_block identity)) blocks)
          header (get by-kind "header")
          context (get by-kind "context")
          meta-block (get by-kind "meta")]
      (is (= "error" (get-in header [:memory-status :status])))
      (is (not (contains? header :counts)))
      (is (nil? (get-in header [:memory-piggyback :enqueued])))
      (is (nil? (get-in header [:memory-piggyback :context-refs])))
      (is (= {} (:context context)))
      (is (not (contains? by-kind "recent-wraps")))
      (is (not (contains? by-kind "kg-insights")))
      (is (.contains (:hint meta-block) "unavailable")))))

(deftest store-not-configured-error-test
  (testing "returns error with isError flag"
    (let [resp (fmt/store-not-configured-error)
          parsed (json/read-str (:text resp) :key-fn keyword)]
      (is (true? (:isError resp)))
      (is (false? (:success parsed)))
      (is (= "Memory store not configured" (:error parsed))))))

(deftest catchup-error-test
  (testing "returns error from exception"
    (let [resp (fmt/catchup-error (Exception. "test failure"))
          parsed (json/read-str (:text resp) :key-fn keyword)]
      (is (false? (:success parsed)))
      (is (= "test failure" (:error parsed))))))

;; =============================================================================
;; Spawn Context Markdown Formatter Tests
;; =============================================================================

(deftest format-spawn-axioms-test
  (testing "formats numbered axiom list"
    (let [axioms [{:content "Rule one"} {:content "Rule two"}]
          result (fmt/format-spawn-axioms axioms)]
      (is (string? result))
      (is (.contains result "### Axioms"))
      (is (.contains result "1. Rule one"))
      (is (.contains result "2. Rule two"))))

  (testing "returns nil for empty axioms"
    (is (nil? (fmt/format-spawn-axioms [])))))

(deftest format-spawn-priorities-test
  (testing "formats numbered priority list"
    (let [convs [{:content "Conv one"}]
          result (fmt/format-spawn-priorities convs)]
      (is (.contains result "### Priority Conventions"))
      (is (.contains result "1. Conv one"))))

  (testing "returns nil for empty"
    (is (nil? (fmt/format-spawn-priorities [])))))

(deftest format-spawn-decisions-test
  (testing "formats bulleted decision list"
    (let [decisions [{:preview "Decision A"} {:preview "Decision B"}]
          result (fmt/format-spawn-decisions decisions)]
      (is (.contains result "### Active Decisions"))
      (is (.contains result "- Decision A"))
      (is (.contains result "- Decision B"))))

  (testing "returns nil for empty"
    (is (nil? (fmt/format-spawn-decisions [])))))

(deftest format-spawn-git-test
  (testing "formats git info"
    (let [result (fmt/format-spawn-git {:branch "main" :uncommitted true :last-commit "abc - fix"})]
      (is (.contains result "### Git Status"))
      (is (.contains result "**Branch**: main"))
      (is (.contains result "Uncommitted changes"))
      (is (.contains result "abc - fix"))))

  (testing "returns nil for nil git-info"
    (is (nil? (fmt/format-spawn-git nil)))))

(deftest format-spawn-stale-files-test
  (testing "formats stale file list"
    (let [files [{:path "src/foo.clj" :score 0.8 :days-since-read 5 :hash-mismatch? true}]
          result (fmt/format-spawn-stale-files files)]
      (is (.contains result "### Files Needing Re-Grounding"))
      (is (.contains result "`src/foo.clj`"))
      (is (.contains result "content changed"))))

  (testing "returns nil for empty"
    (is (nil? (fmt/format-spawn-stale-files [])))))

(deftest serialize-spawn-context-test
  (testing "assembles full spawn context markdown"
    (let [result (fmt/serialize-spawn-context
                  {:axioms [{:content "Axiom 1"}]
                   :priority-conventions [{:content "Conv 1"}]
                   :decisions [{:preview "Dec 1"}]
                   :git-info {:branch "dev" :uncommitted false :last-commit "xyz"}
                   :project-name "test-proj"
                   :stale-files []})]
      (is (.contains result "## Project Context (Auto-Injected)"))
      (is (.contains result "**Project**: test-proj"))
      (is (.contains result "Axiom 1"))
      (is (.contains result "Conv 1"))
      (is (.contains result "Dec 1"))
      (is (.contains result "**Branch**: dev")))))

(deftest max-spawn-context-chars-test
  (testing "constant is reasonable (~3K tokens)"
    (is (= 12000 fmt/max-spawn-context-chars))))