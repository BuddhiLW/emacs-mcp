(ns hive-mcp.agent.agentic-loop-property-test
  "Property-based tests for IAgenticLoop protocol predicates.

   Tests:
   - Totality: agentic-loop?, transparent?, opaque?, has-transcript? never throw
   - Complement: transparent? vs opaque? for single-visibility loops
   - session-state always returns valid keyword
   - hooks always returns a set

   Convention: 200 iterations per property (per hive-mcp testing convention)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [hive-test.properties :as hprop]
            [hive-mcp.agent.agentic-loop :as al]))

;; =============================================================================
;; Constants
;; =============================================================================

(def all-known-caps
  "All known capability/hook keywords from IAgenticLoop."
  #{:cap/transparent :cap/opaque :cap/streaming :cap/multi-turn
    :cap/cost-tracking :cap/transcript :cap/constraints :cap/coordinator
    :pre-tool-use :post-tool-use})

(def valid-session-states
  "Valid session state keywords per IAgenticLoop contract."
  #{:idle :running :done :errored :aborted})

(def non-visibility-caps
  "Capabilities that are NOT visibility modes (transparent/opaque)."
  (vec (disj all-known-caps :cap/transparent :cap/opaque)))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-hooks-set
  "Generator for random sets of capability keywords."
  (gen/fmap set (gen/vector (gen/elements (vec all-known-caps)) 0 6)))

(def gen-session-state
  "Generator for valid session state keywords."
  (gen/elements (vec valid-session-states)))

(def gen-agentic-loop
  "Generator for mock IAgenticLoop implementations with random state."
  (gen/let [hooks-set gen-hooks-set
            state gen-session-state]
    (reify al/IAgenticLoop
      (start! [_ _] {:session-id "mock"})
      (abort! [_] {:aborted? true})
      (session-state [_] state)
      (send-message! [_ _] {:sent? true})
      (collect-response! [_ _] {:result "mock"})
      (cost [_] {:total-cost-usd 0.0 :turns 0})
      (transcript [_] [])
      (tool-results! [_ _] {:accepted? true})
      (hooks [_] hooks-set)
      (constrain! [_ _] {:applied? true}))))

(def gen-single-visibility-loop
  "Generator for loops with exactly one of :cap/transparent or :cap/opaque.
   Used for complement property testing — transparent? and opaque? must
   be exact complements when exactly one visibility cap is present."
  (gen/let [vis (gen/elements [:cap/transparent :cap/opaque])
            extra (gen/vector (gen/elements non-visibility-caps) 0 4)
            state gen-session-state]
    (let [hooks-set (conj (set extra) vis)]
      (reify al/IAgenticLoop
        (start! [_ _] {:session-id "mock"})
        (abort! [_] {:aborted? true})
        (session-state [_] state)
        (send-message! [_ _] {:sent? true})
        (collect-response! [_ _] {:result "mock"})
        (cost [_] {:total-cost-usd 0.0 :turns 0})
        (transcript [_] [])
        (tool-results! [_ _] {:accepted? true})
        (hooks [_] hooks-set)
        (constrain! [_ _] {:applied? true})))))

;; =============================================================================
;; Property: Totality — predicates never throw on any value
;; =============================================================================

(hprop/defprop-total agentic-loop-predicate-total
  al/agentic-loop? gen/any-printable)

(hprop/defprop-total transparent-predicate-total
  al/transparent? gen/any-printable)

(hprop/defprop-total opaque-predicate-total
  al/opaque? gen/any-printable)

(hprop/defprop-total has-transcript-predicate-total
  al/has-transcript? gen/any-printable)

;; =============================================================================
;; Property: Complement — transparent? vs opaque? (single visibility loops)
;; =============================================================================

(hprop/defprop-complement transparent-opaque-complement
  al/transparent? al/opaque? gen-single-visibility-loop)

;; =============================================================================
;; Property: session-state returns valid keyword
;; =============================================================================

(hprop/defprop-total session-state-returns-valid-keyword
  al/session-state gen-agentic-loop
  {:pred #(contains? valid-session-states %)})

;; =============================================================================
;; Property: hooks always returns a set
;; =============================================================================

(defspec hooks-always-returns-set 200
  (prop/for-all [loop gen-agentic-loop]
    (set? (al/hooks loop))))

;; =============================================================================
;; Property: coordinator? consistent with hooks
;; =============================================================================

(defspec coordinator-consistent-with-hooks 200
  (prop/for-all [loop gen-agentic-loop]
    (= (al/coordinator? loop)
       (contains? (al/hooks loop) :cap/coordinator))))

;; =============================================================================
;; Deterministic unit tests (edge cases)
;; =============================================================================

(deftest non-loop-values-return-false
  (testing "agentic-loop? returns false for non-loop values"
    (is (false? (al/agentic-loop? nil)))
    (is (false? (al/agentic-loop? "string")))
    (is (false? (al/agentic-loop? 42)))
    (is (false? (al/agentic-loop? {})))
    (is (false? (al/agentic-loop? [])))))

(deftest transparent-opaque-false-for-non-loops
  (testing "transparent? and opaque? return false for non-loop values"
    (is (false? (al/transparent? nil)))
    (is (false? (al/opaque? nil)))
    (is (false? (al/transparent? "hello")))
    (is (false? (al/opaque? "hello")))))

(deftest coordinator-false-for-non-loops
  (testing "coordinator? returns false for non-loop values"
    (is (false? (al/coordinator? nil)))
    (is (false? (al/coordinator? {})))
    (is (false? (al/coordinator? 42)))))
