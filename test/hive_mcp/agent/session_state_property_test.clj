(ns hive-mcp.agent.session-state-property-test
  "Property-based tests for AgentSessionState ADT.

   Tests algebraic properties:
   - Totality: coercion never throws for valid inputs
   - Round-trip: keyword -> ADT -> keyword = identity
   - Serialization round-trip: ADT -> serialize -> deserialize preserves variant/type
   - Exhaustiveness: adt-case covers all 5 variants
   - Invalid keywords return nil from from-keyword
   - ADT type consistency: adt-type is always :AgentSessionState
   - adt-valid? for all variants: always true
   - Subset properties: active ∪ terminal = all, active ∩ terminal = ∅

   Convention: 200 iterations per property (per hive-mcp testing convention)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [hive-dsl.adt :as adt :refer [adt-case]]
            [hive-mcp.agent.session-state :as ss]
            [clojure.set]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-state-keyword
  "Generator for valid AgentSessionState variant keywords."
  (gen/elements (vec ss/all-states)))

(def gen-state-string
  "Generator for valid AgentSessionState strings."
  (gen/fmap #(str (namespace %) "/" (name %)) gen-state-keyword))

(def gen-state
  "Generator for AgentSessionState ADT values."
  (gen/fmap ss/agent-session-state gen-state-keyword))

(def gen-invalid-keyword
  "Generator for keywords that are NOT valid session states."
  (gen/such-that #(not (contains? ss/all-states %))
                 (gen/elements [:bogus :session/bogus :invalid :idle :running
                                :done :errored :aborted :session/paused
                                :session/waiting :session/cancelled])
                 100))

;; =============================================================================
;; Property: Totality - all valid keywords coerce to AgentSessionState
;; =============================================================================

(defspec all-keywords-coerce-to-state 200
  (prop/for-all [kw gen-state-keyword]
                (let [state (ss/from-keyword kw)]
                  (and (some? state)
                       (ss/agent-session-state? state)))))

;; =============================================================================
;; Property: Round-trip - keyword -> ADT -> keyword = identity
;; =============================================================================

(defspec keyword-round-trip 200
  (prop/for-all [kw gen-state-keyword]
                (= kw (ss/to-keyword (ss/agent-session-state kw)))))

(defspec from-to-keyword-round-trip 200
  (prop/for-all [kw gen-state-keyword]
                (= kw (ss/to-keyword (ss/from-keyword kw)))))

;; =============================================================================
;; Property: Serialization round-trip - ADT -> serialize -> deserialize
;; =============================================================================

(defspec serialize-deserialize-round-trip 200
  (prop/for-all [state gen-state]
                (let [serialized (adt/serialize state)
                      deserialized (adt/deserialize serialized)]
                  (and (some? deserialized)
                       (= (:adt/variant state) (:adt/variant deserialized))
                       (= (:adt/type state) (:adt/type deserialized))))))

;; =============================================================================
;; Property: Exhaustiveness - adt-case covers all 5 variants
;; =============================================================================

(defspec exhaustive-dispatch-returns-value 200
  (prop/for-all [state gen-state]
                (let [result (adt-case ss/AgentSessionState state
                                       :session/idle     :waiting
                                       :session/running  :active
                                       :session/done     :finished
                                       :session/errored  :failed
                                       :session/aborted  :cancelled)]
                  (contains? #{:waiting :active :finished :failed :cancelled}
                             result))))

;; =============================================================================
;; Property: Invalid keywords return nil from from-keyword
;; =============================================================================

(defspec invalid-keywords-return-nil 200
  (prop/for-all [kw gen-invalid-keyword]
                (nil? (ss/from-keyword kw))))

;; =============================================================================
;; Property: ADT type consistency - adt-type is always :AgentSessionState
;; =============================================================================

(defspec adt-type-is-always-agent-session-state 200
  (prop/for-all [state gen-state]
                (= :AgentSessionState (adt/adt-type state))))

;; =============================================================================
;; Property: adt-valid? for all variants - always true
;; =============================================================================

(defspec adt-valid-for-all-variants 200
  (prop/for-all [state gen-state]
                (adt/adt-valid? state)))

;; =============================================================================
;; Property: terminal? and active? are complementary
;; =============================================================================

(defspec terminal-active-complementary 200
  (prop/for-all [state gen-state]
                (not= (ss/terminal? state) (ss/active? state))))

;; =============================================================================
;; Property: valid-state? agrees with all-states
;; =============================================================================

(defspec valid-state-matches-all-states 200
  (prop/for-all [kw gen-state-keyword]
                (ss/valid-state? kw)))

(defspec invalid-state-not-valid 200
  (prop/for-all [kw gen-invalid-keyword]
                (not (ss/valid-state? kw))))

;; =============================================================================
;; Deterministic unit tests (edge cases)
;; =============================================================================

(deftest exactly-five-variants
  (testing "AgentSessionState has exactly 5 variants"
    (is (= 5 (count ss/all-states)))))

(deftest variant-keywords-correct
  (testing "Variant set matches expected"
    (is (= #{:session/idle :session/running :session/done
             :session/errored :session/aborted}
           ss/all-states))))

(deftest terminal-states-correct
  (testing "Terminal states are done, errored, aborted"
    (is (= #{:session/done :session/errored :session/aborted}
           ss/terminal-states))))

(deftest active-states-correct
  (testing "Active states are idle, running"
    (is (= #{:session/idle :session/running}
           ss/active-states))))

(deftest terminal-union-active-equals-all
  (testing "terminal ∪ active = all-states"
    (is (= ss/all-states
           (clojure.set/union ss/terminal-states ss/active-states)))))

(deftest terminal-intersect-active-is-empty
  (testing "terminal ∩ active = ∅"
    (is (empty? (clojure.set/intersection ss/terminal-states ss/active-states)))))
