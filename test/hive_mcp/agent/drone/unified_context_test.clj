(ns hive-mcp.agent.drone.unified-context-test
  "Tests for unified context gathering — active tests only.

   Tests for unimplemented fns (classify-entries, truncate-content,
   enrich-context) are disabled until those fns land in unified_context.clj.
   Extension delegation tests disabled pending ext/registry availability."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [hive-mcp.agent.drone.unified-context :as uc]))

;; =============================================================================
;; Format Tests (existing fns)
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
    (is (nil? (uc/format-unified-context nil)))))

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
    (is (nil? (#'uc/format-entries-section "Nil" nil)))))

(deftest format-edges-section-test
  (testing "formats edges with arrow notation"
    (let [edges [{:from "20260207-abc" :to "20260206-def" :relation :implements}
                 {:from "20260206-def" :to "20260205-ghi" :relation :depends-on}]
          result (#'uc/format-edges-section edges)]
      (is (str/includes? result "-impl->"))
      (is (str/includes? result "-dep->"))))

  (testing "returns nil for empty edges"
    (is (nil? (#'uc/format-edges-section [])))))

;; =============================================================================
;; Stub Noop Fallback Tests (existing fns)
;; =============================================================================

(deftest resolve-seeds-noop-test
  (testing "returns empty vector when no extension"
    (is (= [] (uc/resolve-seeds {:task "test" :project-id "p"}))))

  (testing "returns empty vector with nil opts"
    (is (= [] (uc/resolve-seeds nil)))))

(deftest prepare-drone-context-noop-test
  (testing "returns empty context map when no extension"
    (let [result (uc/prepare-drone-context {:task "test" :project-id "p"})]
      (is (map? result))
      (is (= [] (:conventions result)))
      (is (= [] (:decisions result)))))

  (testing "noop context formats to nil (graceful degradation)"
    (let [result (uc/prepare-drone-context {:task "test" :project-id "p"})]
      (is (nil? (uc/format-unified-context result))))))

(deftest unified-context-available-noop-test
  (testing "returns false when no extension"
    (is (false? (uc/unified-context-available?)))))

;; =============================================================================
;; DISABLED: Tests for unimplemented fns
;; Re-enable when classify-entries, truncate-content, enrich-context land.
;; Also re-enable extension delegation tests when ext/registry is wired.
;; =============================================================================
