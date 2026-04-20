(ns hive-mcp.tools.catchup.shapes-test
  "Tests for entry shape contract functions.

   Paradigms:
   1. Unit: deterministic shape extraction from known inputs
   2. Property-based: entry-content never returns nil for any entry shape"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.tools.catchup.shapes :as shapes]))

;; =============================================================================
;; Generators: Three Entry Shapes
;; =============================================================================

(def gen-raw-entry
  "Generator for raw Chroma entries: {:id :content :type :tags}."
  (gen/let [id (gen/fmap #(str "entry-" %) gen/uuid)
            content gen/string-alphanumeric
            type (gen/elements [:axiom :decision :convention :note :snippet])
            tags (gen/vector gen/string-alphanumeric 0 3)]
    {:id id :content content :type type :tags tags}))

(def gen-catchup-meta
  "Generator for catchup meta entries: {:id :T :P}."
  (gen/let [id (gen/fmap #(str "entry-" %) gen/uuid)
            T (gen/elements ["axiom" "decision" "convention" "note"])
            P gen/string-alphanumeric]
    {:id id :T T :P P}))

(def gen-enriched-meta
  "Generator for enriched meta entries: {:id :T :P :kg}."
  (gen/let [base gen-catchup-meta
            kg (gen/hash-map :depends-on (gen/vector gen/string-alphanumeric 0 2))]
    (assoc base :kg kg)))

(def gen-any-entry
  "Generator for any of the three entry shapes."
  (gen/one-of [gen-raw-entry gen-catchup-meta gen-enriched-meta]))

(def gen-empty-entry
  "Generator for degenerate entry maps."
  (gen/elements [{} {:id "x"} {:foo "bar"}]))

;; =============================================================================
;; Unit Tests
;; =============================================================================

(deftest entry-content-raw-entry-test
  (testing "extracts :content from raw Chroma entries"
    (is (= "hello" (shapes/entry-content {:id "1" :content "hello" :type :decision})))))

(deftest entry-content-catchup-meta-test
  (testing "extracts :P from catchup meta entries"
    (is (= "preview text" (shapes/entry-content {:id "1" :T "decision" :P "preview text"})))))

(deftest entry-content-enriched-meta-test
  (testing "extracts :P from enriched meta entries"
    (is (= "enriched" (shapes/entry-content {:id "1" :T "note" :P "enriched" :kg {:depends-on ["x"]}})))))

(deftest entry-content-prefers-content-over-P-test
  (testing ":content takes precedence over :P when both present"
    (is (= "full content" (shapes/entry-content {:id "1" :content "full content" :P "short"})))))

(deftest entry-content-prefers-preview-over-P-test
  (testing ":preview takes precedence over :P"
    (is (= "prev" (shapes/entry-content {:id "1" :preview "prev" :P "short"})))))

(deftest entry-content-empty-entry-test
  (testing "returns empty string for entries with no content keys"
    (is (= "" (shapes/entry-content {})))
    (is (= "" (shapes/entry-content {:id "1"})))))

(deftest entry-id-test
  (testing "extracts :id or defaults to \"?\""
    (is (= "abc" (shapes/entry-id {:id "abc"})))
    (is (= "?" (shapes/entry-id {})))
    (is (= "?" (shapes/entry-id {:content "x"})))))

(deftest entry-type-name-test
  (testing "extracts type as string from any shape"
    (is (= "decision" (shapes/entry-type-name {:type :decision})))
    (is (= "axiom" (shapes/entry-type-name {:T "axiom"})))
    (is (= "note" (shapes/entry-type-name {})))
    (is (= "note" (shapes/entry-type-name {:id "1"})))))

;; =============================================================================
;; Property-Based Tests
;; =============================================================================

(defspec entry-content-never-nil 500
  (prop/for-all [entry gen-any-entry]
    (string? (shapes/entry-content entry))))

(defspec entry-content-never-nil-for-empty 200
  (prop/for-all [entry gen-empty-entry]
    (string? (shapes/entry-content entry))))

(defspec entry-id-never-nil 500
  (prop/for-all [entry gen-any-entry]
    (string? (shapes/entry-id entry))))

(defspec entry-type-name-never-nil 500
  (prop/for-all [entry gen-any-entry]
    (string? (shapes/entry-type-name entry))))

(defspec raw-entry-roundtrips-content 200
  (prop/for-all [entry gen-raw-entry]
    (= (:content entry) (shapes/entry-content entry))))

(defspec catchup-meta-roundtrips-P 200
  (prop/for-all [entry gen-catchup-meta]
    (= (:P entry) (shapes/entry-content entry))))
