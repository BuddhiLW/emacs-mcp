(ns hive-mcp.hivemind.event-type-property-test
  "Property-based tests for the hivemind event-type sum type.

   HISTORY (why this file no longer requires hive-mcp.hivemind.event-type):
   The EventType ADT (src/hive_mcp/hivemind/event_type.clj, defadt via
   hive-dsl.adt) was deleted as dead code in 6f66d4e (\"chore: dead code
   cleanup + new modules\"). Nothing in src constructed ADT values — every
   consumer (messaging, tools, piggyback, olympus, notification effects,
   event schemas) reads plain keywords and derives metadata from
   hive-mcp.hivemind.event-registry, which was ALWAYS the single source of
   truth for this data (the ADT's own docstring said so:
   \"Metadata ... stays in event_registry.clj\").

   So the sum type survives — as the registry's closed key set, with keywords
   as the runtime representation and strings as the MCP/JSON wire form. These
   properties are repointed at that surviving home, one-for-one:

   - P1: keyword→string→keyword round-trip identity     (was: ADT round-trip)
   - P2: string→keyword→string round-trip identity      (was: ADT round-trip)
   - P3: All registry variants coerce without throwing (totality)
   - P4: Unknown keywords are rejected (exhaustiveness — registry validates
         by returning false + documented defaults; the throwing coercion
         boundary died with the ADT's keyword->event-type)
   - P5: All variants are pairwise distinct (no duplicated metadata rows)
   - P6: Every variant has complete metadata in the registry (completeness)
   - P7: terminal? consistency with the registry data
   - P8: slave-status consistency with the registry data
   - P9: Wire round-trip — keyword ⇄ string across the MCP/JSON boundary
         preserves every derived view (was: adt/serialize ⇄ adt/deserialize)
   - P10: valid-event-type? is true for every registry variant, in both
          keyword and string form                 (was: event-type? predicate)"
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.hivemind.event-registry :as reg]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-event-type-keyword
  "Generator for the 6 agent-status variant keywords (the original EventType
   variants). These are simple keywords, so they also have a wire (string) form."
  (gen/elements [:started :progress :completed :error :blocked :wrap_notify]))

(def gen-registry-keyword
  "Generator for EVERY registry variant, including the namespaced
   :conversation/* events added after the ADT was written."
  (gen/elements (vec (keys reg/registry))))

(def gen-event-type-string
  "Generator for valid EventType variant strings (MCP/JSON wire form)."
  (gen/elements ["started" "progress" "completed" "error" "blocked" "wrap_notify"]))

(def gen-unknown-keyword
  "Generator for keywords that are NOT valid event types.
   Filters on valid-event-type? itself, so keywords that merely *name* a
   variant (e.g. :tell) are excluded too."
  (gen/such-that #(not (reg/valid-event-type? %))
                 gen/keyword
                 100))

(def gen-two-different-keywords
  "Generator for two distinct registry variant keywords."
  (gen/such-that (fn [[a b]] (not= a b))
                 (gen/tuple gen-registry-keyword gen-registry-keyword)))

;; =============================================================================
;; P1: keyword→string→keyword round-trip identity
;; =============================================================================

(defspec p1-keyword-roundtrip 200
  (prop/for-all [kw gen-event-type-keyword]
                (and (reg/valid-event-type? kw)
                     (reg/valid-event-type? (name kw))
                     (= kw (keyword (name kw))))))

;; =============================================================================
;; P2: string→keyword→string round-trip identity
;; =============================================================================

(defspec p2-string-roundtrip 200
  (prop/for-all [s gen-event-type-string]
                (and (reg/valid-event-type? s)
                     (= s (name (keyword s)))
                     (contains? reg/all-event-type-strings s))))

;; =============================================================================
;; P3: All registry variants coerce without throwing (totality)
;; =============================================================================

(defspec p3-keyword-coercion-totality 200
  (prop/for-all [kw gen-registry-keyword]
                (try
                  (and (true? (reg/valid-event-type? kw))
                       (keyword? (reg/slave-status kw))
                       (keyword? (reg/severity kw))
                       (boolean? (reg/terminal? kw))
                       (string? (reg/format-icon kw)))
                  (catch Throwable _ false))))

;; =============================================================================
;; P4: Unknown keywords are rejected (exhaustiveness)
;;
;; The ADT threw from keyword->event-type; the registry rejects by returning
;; false from valid-event-type? and falling back to its documented defaults.
;; =============================================================================

(defspec p4-unknown-keywords-rejected 200
  (prop/for-all [kw gen-unknown-keyword]
                (and (false? (reg/valid-event-type? kw))
                     (not (contains? reg/all-event-types kw))
                     (nil? (get reg/registry kw))
                     ;; documented fallbacks for unknown input
                     (= :idle (reg/slave-status kw))
                     (= :info (reg/severity kw))
                     (false? (reg/terminal? kw)))))

;; =============================================================================
;; P5: All variants are pairwise distinct
;; =============================================================================

(defspec p5-variants-pairwise-distinct 100
  (prop/for-all [[kw1 kw2] gen-two-different-keywords]
                (not= (get reg/registry kw1)
                      (get reg/registry kw2))))

;; =============================================================================
;; P6: Every variant has complete metadata in event registry (completeness)
;; =============================================================================

(defspec p6-registry-completeness 100
  (prop/for-all [kw gen-registry-keyword]
                (let [entry (get reg/registry kw)]
                  (and (some? entry)
                       (string? (:description entry))
                       (contains? #{:info :warn :error} (:severity entry))
                       (keyword? (:slave-status entry))
                       (boolean? (:terminal? entry))
                       (boolean? (:mcp? entry))
                       (string? (get-in entry [:format :icon]))))))

;; =============================================================================
;; P7: terminal? consistency with event registry
;; =============================================================================

(defspec p7-terminal-consistency 100
  (prop/for-all [kw gen-registry-keyword]
                (let [expected (:terminal? (get reg/registry kw))]
                  (and (= expected (reg/terminal? kw))
                       (= expected (contains? reg/terminal-event-types kw))))))

;; =============================================================================
;; P8: slave-status consistency with event registry
;; =============================================================================

(defspec p8-slave-status-consistency 100
  (prop/for-all [kw gen-registry-keyword]
                (let [expected (:slave-status (get reg/registry kw))]
                  (and (= expected (reg/slave-status kw))
                       (= expected (get reg/event-type->slave-status kw))))))

;; =============================================================================
;; P9: Wire round-trip — the MCP/JSON boundary (strings) preserves semantics
;;
;; Replaces the old adt/serialize ⇄ adt/deserialize round-trip: strings are
;; now the only serialized form of an event type.
;; =============================================================================

(defspec p9-wire-roundtrip-preserves-semantics 200
  (prop/for-all [kw gen-event-type-keyword]
                (let [serialized (name kw)
                      deserialized (keyword serialized)]
                  (and (= kw deserialized)
                       (= (reg/slave-status kw) (reg/slave-status serialized))
                       (= (reg/severity kw) (reg/severity serialized))
                       (= (reg/terminal? kw) (reg/terminal? serialized))
                       (= (reg/format-icon kw) (reg/format-icon serialized))
                       ;; MCP enum membership agrees with the registry's :mcp? flag
                       (= (boolean (:mcp? (get reg/registry kw)))
                          (contains? (set (reg/mcp-enum)) serialized))))))

;; =============================================================================
;; P10: valid-event-type? predicate is true for every registry variant
;; =============================================================================

(defspec p10-predicate-true-for-all-variants 200
  (prop/for-all [kw gen-registry-keyword]
                (and (true? (reg/valid-event-type? kw))
                     (contains? reg/all-event-types kw)
                     (contains? reg/all-event-type-strings (name kw)))))
