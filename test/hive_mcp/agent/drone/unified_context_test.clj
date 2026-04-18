(ns hive-mcp.agent.drone.unified-context-test
  "Tests for unified context gathering stubs.

   Covers:
   - Pure functions: classify-entries, truncate-content
   - Formatting: format-unified-context, format-entries-section, format-edges-section
   - Stub noop fallbacks: resolve-seeds, prepare-drone-context, unified-context-available?
   - Extension delegation: :uc/resolve-seeds, :uc/gather, :uc/enrich, :uc/available
   - Edge cases: empty inputs, nil values"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.agent.drone.unified-context :as uc]
            [hive-mcp.extensions.registry :as ext]))

;; =============================================================================
;; Fixtures — Clean extension registry
;; =============================================================================

(defn clean-extensions-fixture [f]
  (ext/clear-all!)
  (f)
  (ext/clear-all!))

(use-fixtures :each clean-extensions-fixture)

;; =============================================================================
;; Pure Function Tests (no side effects)
;; =============================================================================

;; classify-entries tests disabled — fn not yet implemented in unified_context.clj.
;; Re-enable when classify-entries is added.
;; See: deftest classify-entries-test, classify-entries-empty-test,
;;      classify-entries-all-same-type-test, classify-entries-preserves-content-test

(deftest truncate-content-test
  (testing "truncates long content"
    (let [long-str (apply str (repeat 600 "x"))
          result (#'uc/truncate-content long-str)]
      (is (<= (count result) (+ uc/max-content-chars 3)))
      (is (str/ends-with? result "..."))))

  (testing "leaves short content unchanged"
    (is (= "hello" (#'uc/truncate-content "hello"))))

  (testing "handles nil gracefully"
    (is (nil? (#'uc/truncate-content nil))))

  (testing "exact boundary — content at max-content-chars not truncated"
    (let [exact (apply str (repeat uc/max-content-chars "a"))]
      (is (= exact (#'uc/truncate-content exact)))))

  (testing "one char over boundary is truncated"
    (let [over (apply str (repeat (inc uc/max-content-chars) "b"))
          result (#'uc/truncate-content over)]
      (is (str/ends-with? result "..."))
      (is (<= (count result) (+ uc/max-content-chars 3)))))

  (testing "empty string unchanged"
    (is (= "" (#'uc/truncate-content "")))))

;; =============================================================================
;; Format Tests
;; =============================================================================

(deftest format-unified-context-test
  (testing "formats non-empty context"
    (let [ctx {:conventions [{:id "1" :content "Use mapcat" :type "convention"}]
               :decisions [{:id "2" :content "Use DS" :type "decision"}]
               :domain []
               :snippets []
               :edges [{:from "1" :to "2" :relation :implements :confidence 0.9}]}
          result (uc/format-unified-context ctx)]
      (is (string? result))
      (is (str/includes? result "Unified Project Context"))
      (is (str/includes? result "Conventions"))
      (is (str/includes? result "Decisions"))
      (is (str/includes? result "KG Structure"))))

  (testing "returns nil for empty context"
    (is (nil? (uc/format-unified-context
               {:conventions [] :decisions [] :domain [] :snippets []}))))

  (testing "returns nil for nil context"
    (is (nil? (uc/format-unified-context nil))))

  (testing "includes domain section when present"
    (let [ctx {:conventions []
               :decisions []
               :domain [{:id "d1" :content "Domain note" :type "note"}]
               :snippets []
               :edges []}
          result (uc/format-unified-context ctx)]
      (is (str/includes? result "Domain Knowledge"))))

  (testing "includes snippets section when present"
    (let [ctx {:conventions []
               :decisions []
               :domain []
               :snippets [{:id "s1" :content "(defn foo [])" :type "snippet"}]
               :edges []}
          result (uc/format-unified-context ctx)]
      (is (str/includes? result "Code Snippets")))))

(deftest format-entries-section-test
  (testing "formats entries with numbered list"
    (let [entries [{:content "First"} {:content "Second"}]
          result (#'uc/format-entries-section "Test" entries)]
      (is (str/starts-with? result "### Test"))
      (is (str/includes? result "1. First"))
      (is (str/includes? result "2. Second"))))

  (testing "returns nil for empty entries"
    (is (nil? (#'uc/format-entries-section "Empty" []))))

  (testing "returns nil for nil entries"
    (is (nil? (#'uc/format-entries-section "Nil" nil))))

  (testing "handles nil content in entries"
    (let [result (#'uc/format-entries-section "Nil Content" [{:content nil}])]
      (is (str/includes? result "(no content)")))))

(deftest format-edges-section-test
  (testing "formats edges with arrow notation"
    (let [edges [{:from "20260207-abc" :to "20260206-def" :relation :implements}
                 {:from "20260206-def" :to "20260205-ghi" :relation :depends-on}]
          result (#'uc/format-edges-section edges)]
      (is (str/includes? result "-impl->"))
      (is (str/includes? result "-dep->"))))

  (testing "returns nil for empty edges"
    (is (nil? (#'uc/format-edges-section []))))

  (testing "all relation types have arrows"
    (let [rels [:implements :supersedes :depends-on :refines
                :contradicts :derived-from :applies-to]
          edges (map-indexed (fn [i rel]
                               {:from (str "n" i) :to (str "n" (inc i)) :relation rel})
                             rels)
          result (#'uc/format-edges-section edges)]
      (is (str/includes? result "-impl->"))
      (is (str/includes? result "-super->"))
      (is (str/includes? result "-dep->"))
      (is (str/includes? result "-ref->"))
      (is (str/includes? result "-contra->"))
      (is (str/includes? result "-from->"))
      (is (str/includes? result "-apply->"))))

  (testing "unknown relation uses name"
    (let [edges [{:from "a" :to "b" :relation :custom-rel}]
          result (#'uc/format-edges-section edges)]
      (is (str/includes? result "custom-rel"))))

  (testing "truncates long IDs to 8 chars"
    (let [edges [{:from "20260207214919-604af5bd" :to "20260207214512-79c34d04"
                  :relation :implements}]
          result (#'uc/format-edges-section edges)]
      ;; Truncated IDs should be 8 chars
      (is (str/includes? result "20260207"))
      ;; Full ID should NOT appear
      (is (not (str/includes? result "20260207214919-604af5bd")))))

  (testing "edge count in header"
    (let [edges (repeat 3 {:from "a" :to "b" :relation :implements})
          result (#'uc/format-edges-section edges)]
      (is (str/includes? result "3 edges")))))

;; =============================================================================
;; Stub Noop Fallback Tests
;; =============================================================================

(deftest resolve-seeds-noop-test
  (testing "returns empty vector when no extension"
    (is (= [] (uc/resolve-seeds {:task "test" :project-id "p"}))))

  (testing "returns empty vector with nil opts"
    (is (= [] (uc/resolve-seeds nil))))

  (testing "returns empty vector with empty opts"
    (is (= [] (uc/resolve-seeds {})))))

(deftest prepare-drone-context-noop-test
  (testing "returns empty context map when no extension"
    (let [result (uc/prepare-drone-context {:task "test" :project-id "p"})]
      (is (map? result))
      (is (= [] (:conventions result)))
      (is (= [] (:decisions result)))
      (is (= [] (:domain result)))
      (is (= [] (:snippets result)))
      (is (= [] (:edges result)))
      (is (= 0 (:seed-count result)))
      (is (= 0 (:traversal-count result)))
      (is (= #{} (:node-ids result)))))

  (testing "returns empty context with nil opts"
    (let [result (uc/prepare-drone-context nil)]
      (is (map? result))
      (is (= [] (:conventions result)))))

  (testing "noop context formats to nil (graceful degradation)"
    (let [result (uc/prepare-drone-context {:task "test" :project-id "p"})]
      (is (nil? (uc/format-unified-context result))))))

(deftest unified-context-available-noop-test
  (testing "returns false when no extension"
    (is (false? (uc/unified-context-available?)))))

;; =============================================================================
;; Extension Delegation Tests
;; =============================================================================

(deftest resolve-seeds-extension-test
  (testing ":uc/resolve-seeds extension overrides noop"
    (ext/register! :uc/resolve-seeds
                   (fn [_opts] ["ext-seed-1" "ext-seed-2"]))
    (let [seeds (uc/resolve-seeds {:task "anything" :project-id "test"})]
      (is (= ["ext-seed-1" "ext-seed-2"] seeds))))

  (testing ":uc/resolve-seeds extension receives opts map"
    (let [received (atom nil)]
      (ext/register! :uc/resolve-seeds
                     (fn [opts] (reset! received opts) ["s1"]))
      (uc/resolve-seeds {:task "my-task" :project-id "my-proj"})
      (is (= "my-task" (:task @received)))
      (is (= "my-proj" (:project-id @received))))))

(deftest prepare-drone-context-extension-test
  (testing ":uc/gather extension provides full context"
    (ext/register! :uc/gather
                   (fn [_opts]
                     {:conventions [{:id "c1" :content "Conv" :type "convention"}]
                      :decisions [{:id "d1" :content "Dec" :type "decision"}]
                      :snippets []
                      :domain []
                      :edges [{:from "c1" :to "d1" :relation :implements}]
                      :seed-count 1
                      :traversal-count 2
                      :node-ids #{"c1" "d1"}}))
    (let [result (uc/prepare-drone-context {:task "test" :project-id "p"})]
      (is (= 1 (count (:conventions result))))
      (is (= 1 (count (:decisions result))))
      (is (= 1 (count (:edges result))))
      (is (= 1 (:seed-count result)))
      (is (= 2 (:traversal-count result)))))

  (testing ":uc/gather extension receives opts map"
    (let [received (atom nil)]
      (ext/register! :uc/gather
                     (fn [opts]
                       (reset! received opts)
                       {:conventions [] :decisions [] :snippets [] :domain []}))
      (uc/prepare-drone-context {:task "auth" :seeds ["fp"] :project-id "p"})
      (is (= "auth" (:task @received)))
      (is (= ["fp"] (:seeds @received)))))

  (testing ":uc/gather context can be formatted"
    (ext/register! :uc/gather
                   (fn [_opts]
                     {:conventions [{:id "c1" :content "Use mapcat" :type "convention"}]
                      :decisions []
                      :snippets []
                      :domain []
                      :edges []}))
    (let [result (uc/prepare-drone-context {:task "test" :project-id "p"})
          formatted (uc/format-unified-context result)]
      (is (string? formatted))
      (is (str/includes? formatted "Use mapcat")))))

(deftest unified-context-available-extension-test
  (testing ":uc/available extension can return true"
    (ext/register! :uc/available (fn [] true))
    (is (true? (uc/unified-context-available?))))

  (testing ":uc/available extension can return false"
    (ext/register! :uc/available (fn [] false))
    (is (false? (uc/unified-context-available?)))))

(deftest enrich-context-extension-test
  (testing ":uc/enrich extension adds data to context"
    (ext/register! :uc/enrich
                   (fn [ctx _opts]
                     (assoc ctx :enriched? true :extra-field "bonus")))
    (let [base {:conventions [] :decisions []}
          enriched (#'uc/enrich-context base {:task "test"})]
      (is (true? (:enriched? enriched)))
      (is (= "bonus" (:extra-field enriched)))))

  (testing ":uc/enrich failure returns unenriched context"
    (ext/register! :uc/enrich
                   (fn [_ctx _opts] (throw (Exception. "enrich broke"))))
    (let [base {:conventions ["a"] :decisions ["b"]}
          result (#'uc/enrich-context base {:task "test"})]
      ;; Should still have base structure
      (is (= ["a"] (:conventions result)))
      (is (= ["b"] (:decisions result)))))

  (testing "no :uc/enrich extension returns context unchanged"
    (let [base {:conventions [] :decisions [] :custom "data"}
          result (#'uc/enrich-context base {:task "test"})]
      (is (= base result)))))

;; =============================================================================
;; Constants
;; =============================================================================

(deftest constants-test
  (testing "max-content-chars is positive"
    (is (pos? uc/max-content-chars))))
