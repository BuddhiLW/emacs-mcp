(ns hive-mcp.crystal.harvest.by-scope-test
  "Trifecta tests for HarvestByScope ADT (step-1 of per-scope wrap plan
   `20260504173159-46dc47f1`).

   Three invariants enforced:
   1. `valid-pid?` distinguishes real project-ids from sentinels and
      tag-prefixed strings.
   2. `empty-by-scope` always validates against the HarvestByScope schema.
   3. `assoc-scope` preserves validity and is idempotent on equal inputs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-mcp.crystal.harvest.by-scope :as bs]
            [hive-test.trifecta :refer [deftrifecta]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Generators
;; =============================================================================

(def ^:private gen-valid-pid
  "Project-id strings that satisfy `valid-pid?` — non-blank, no `scope:`
   prefix, not in the reserved set."
  (gen/such-that bs/valid-pid?
                 (gen/fmap (fn [s] (str "proj-" s))
                           (gen/such-that not-empty gen/string-alphanumeric))
                 50))

(def ^:private gen-invalid-pid
  "Strings that must NOT satisfy `valid-pid?`."
  (gen/one-of [(gen/return "")
               (gen/return "umbrella")
               (gen/return "multi-project")
               (gen/fmap #(str "scope:project:" %) gen/string-alphanumeric)
               (gen/fmap #(str "scope:" %) gen/string-alphanumeric)]))

(def ^:private gen-pid-or-junk
  (gen/one-of [gen-valid-pid gen-invalid-pid]))

;; =============================================================================
;; Trifecta — valid-pid? predicate
;; =============================================================================

(deftrifecta valid-pid?-classification
  bs/valid-pid?
  {:golden-path "test/golden/hive-mcp/crystal/harvest/by-scope-valid-pid.edn"
   :cases       {:plain          "hive-mcp"
                 :nested         "hive/hive-mcp"
                 :empty-string   ""
                 :umbrella       "umbrella"
                 :multi-project  "multi-project"
                 :scope-prefix   "scope:project:hive"
                 :scope-global   "scope:global"
                 :nil-input      nil
                 :keyword-input  :hive
                 :funeraria      "funeraria"
                 :sisf-crm       "sisf-crm"}
   :gen         gen-pid-or-junk
   :pred        boolean?
   :mutations   [["always-true"  (fn [_] true)]
                 ["always-false" (fn [_] false)]
                 ["string-only"  string?]]})

;; =============================================================================
;; Wrappers — deftrifecta requires a var-symbol, so lift lambdas into named
;; private fns and reference them by symbol.
;; =============================================================================

(defn- mk-empty-by-scope [args] (bs/empty-by-scope args))

(defn- assoc-roundtrip
  "Apply assoc-scope twice with the same args; result must equal one
   application AND must validate. Returns a small map so trifecta predicates
   can assert on it."
  [{:keys [pid slice]}]
  (let [base   (bs/empty-by-scope)
        once   (bs/assoc-scope base pid slice)
        twice  (bs/assoc-scope once pid slice)]
    {:once   once
     :twice  twice
     :equal? (= once twice)
     :valid? (bs/valid? twice)}))

;; =============================================================================
;; Trifecta — empty-by-scope produces a valid HarvestByScope
;; =============================================================================

(def ^:private gen-empty-by-scope-args
  (gen/hash-map
    :session   (gen/one-of [(gen/return nil) gen/string-alphanumeric])
    :directory (gen/one-of [(gen/return nil) gen/string-alphanumeric])
    :agent-id  (gen/one-of [(gen/return nil) gen/string-alphanumeric])))

(deftrifecta empty-by-scope-shape
  mk-empty-by-scope
  {:golden-path "test/golden/hive-mcp/crystal/harvest/by-scope-empty.edn"
   :cases       {:no-args    nil
                 :session    {:session "20260504"}
                 :all-fields {:session "20260504" :directory "/x" :agent-id "ag1"}}
   :gen         gen-empty-by-scope-args
   :pred        bs/valid?
   :mutations   [["drop-umbrella" (fn [_] {:by-scope {} :errors []})]
                 ["bad-pid-key"   (fn [_] {:by-scope {"" bs/empty-scope-slice}
                                            :umbrella bs/empty-umbrella-slice
                                            :errors []})]
                 ["scope-prefix"  (fn [_] {:by-scope {"scope:project:hive" bs/empty-scope-slice}
                                            :umbrella bs/empty-umbrella-slice
                                            :errors []})]]})

;; =============================================================================
;; Trifecta — assoc-scope preserves validity + idempotence
;; =============================================================================

(def ^:private gen-assoc-args
  (gen/hash-map
    :pid   gen-valid-pid
    :slice (gen/return bs/empty-scope-slice)))

(deftrifecta assoc-scope-roundtrip
  assoc-roundtrip
  {:golden-path "test/golden/hive-mcp/crystal/harvest/by-scope-assoc.edn"
   :cases       {:hive      {:pid "hive"      :slice bs/empty-scope-slice}
                 :funeraria {:pid "funeraria" :slice bs/empty-scope-slice}
                 :sisf-crm  {:pid "sisf-crm"  :slice bs/empty-scope-slice}}
   :xf          (fn [r] (select-keys r [:equal? :valid?]))
   :gen         gen-assoc-args
   :pred        (fn [r] (and (:equal? r) (:valid? r)))
   :mutations   [["non-idempotent" (fn [args]
                                     (let [r (assoc-roundtrip args)]
                                       (assoc r :equal? false)))]
                 ["invalid"        (fn [args]
                                     (let [r (assoc-roundtrip args)]
                                       (assoc r :valid? false)))]]})

;; =============================================================================
;; Plain deftest — umbrella merge semantics (sequential vs scalar)
;; =============================================================================

(deftest merge-umbrella--seq-fields-concat
  (testing "cross-pid-edges + cross-cutting-decisions concat in order"
    (let [base   (bs/empty-by-scope)
          step1  (bs/merge-umbrella base
                    {:cross-pid-edges         [{:edge :a}]
                     :cross-cutting-decisions [{:dec :x}]})
          step2  (bs/merge-umbrella step1
                    {:cross-pid-edges         [{:edge :b}]
                     :cross-cutting-decisions [{:dec :y}]})]
      (is (= [{:edge :a} {:edge :b}]
             (get-in step2 [:umbrella :cross-pid-edges])))
      (is (= [{:dec :x} {:dec :y}]
             (get-in step2 [:umbrella :cross-cutting-decisions])))
      (is (bs/valid? step2)))))

(deftest merge-umbrella--scalar-prefers-extra-when-non-nil
  (testing "session-timing + session-temporal take latest non-nil"
    (let [base  (bs/empty-by-scope)
          t1    (bs/merge-umbrella base {:session-timing {:start 1}})
          t2    (bs/merge-umbrella t1   {:session-timing nil}) ; nil keeps prior
          t3    (bs/merge-umbrella t2   {:session-timing {:start 2}})]
      (is (= {:start 1} (get-in t1 [:umbrella :session-timing])))
      (is (= {:start 1} (get-in t2 [:umbrella :session-timing])))
      (is (= {:start 2} (get-in t3 [:umbrella :session-timing])))
      (is (bs/valid? t3)))))

(deftest scope-pids--excludes-umbrella
  (testing "scope-pids returns only :by-scope keys"
    (let [hbs (-> (bs/empty-by-scope)
                  (bs/assoc-scope "hive" bs/empty-scope-slice)
                  (bs/assoc-scope "funeraria" bs/empty-scope-slice)
                  (bs/merge-umbrella {:cross-pid-edges [{:edge :z}]}))]
      (is (= #{"hive" "funeraria"} (bs/scope-pids hbs)))
      (is (false? (contains? (bs/scope-pids hbs) :umbrella)))
      (is (false? (bs/umbrella-empty? hbs))))))

(deftest umbrella-empty?--detects-zero-payload
  (testing "freshly-empty HarvestByScope has empty umbrella"
    (is (true? (bs/umbrella-empty? (bs/empty-by-scope)))))
  (testing "any non-empty cross-cutting field flips it"
    (is (false? (bs/umbrella-empty?
                  (bs/merge-umbrella (bs/empty-by-scope)
                                     {:hivemind-shouts-global [{:m "hi"}]}))))))