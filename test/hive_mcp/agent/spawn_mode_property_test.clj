(ns hive-mcp.agent.spawn-mode-property-test
  "Property-based tests for SpawnMode ADT.

   Tests algebraic properties:
   - Totality: coercion never throws for valid inputs
   - Round-trip: keyword → ADT → keyword = identity
   - Exhaustiveness: adt-case covers all variants
   - Alias idempotence: resolve(resolve(x)) = resolve(x)
   - Partition: emacs vs headless is a complete partition
   - Metadata totality: every variant has io-model and capabilities

   Convention: 200 iterations per property (per hive-mcp testing convention)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [hive-dsl.adt :as adt :refer [adt-case]]
            [hive-mcp.agent.spawn-mode :as sm]
            [hive-mcp.agent.spawn-mode-registry :as registry]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-spawn-mode-keyword
  "Generator for valid spawn mode keywords."
  (gen/elements (vec sm/all-variants)))

(def gen-spawn-mode-string
  "Generator for valid spawn mode strings."
  (gen/fmap name gen-spawn-mode-keyword))

(def gen-spawn-mode
  "Generator for SpawnMode ADT values."
  (gen/fmap sm/spawn-mode gen-spawn-mode-keyword))

(def gen-invalid-keyword
  "Generator for keywords that are NOT valid spawn modes."
  (gen/such-that #(not (contains? sm/all-variants %))
                 (gen/elements [:bogus :docker :ssh :local :cloud :process
                                :container :vm :wasm :grpc :rest])
                 100))

;; =============================================================================
;; Property: Totality — all valid keywords coerce to SpawnMode
;; =============================================================================

(defspec all-keywords-coerce-to-spawn-mode 200
  (prop/for-all [kw gen-spawn-mode-keyword]
                (let [sm (sm/from-keyword kw)]
                  (and (some? sm)
                       (sm/spawn-mode? sm)))))

(defspec all-strings-coerce-to-spawn-mode 200
  (prop/for-all [s gen-spawn-mode-string]
                (let [sm (sm/from-keyword s)]
                  (and (some? sm)
                       (sm/spawn-mode? sm)))))

(defspec invalid-keywords-return-nil 200
  (prop/for-all [kw gen-invalid-keyword]
                (nil? (sm/from-keyword kw))))

;; =============================================================================
;; Property: Round-trip — keyword → ADT → keyword = identity
;; =============================================================================

(defspec keyword-round-trip 200
  (prop/for-all [kw gen-spawn-mode-keyword]
                (= kw (sm/to-keyword (sm/spawn-mode kw)))))

(defspec from-to-keyword-round-trip 200
  (prop/for-all [kw gen-spawn-mode-keyword]
                (= kw (sm/to-keyword (sm/from-keyword kw)))))

(defspec string-round-trip 200
  (prop/for-all [s gen-spawn-mode-string]
                (= (keyword s) (sm/to-keyword (sm/from-keyword s)))))

;; =============================================================================
;; Property: Serialization round-trip — ADT → serialize → deserialize = ADT
;; =============================================================================

(defspec serialize-deserialize-round-trip 200
  (prop/for-all [sm gen-spawn-mode]
                (let [serialized (adt/serialize sm)
                      deserialized (adt/deserialize serialized)]
                  (and (some? deserialized)
                       (= (:adt/variant sm) (:adt/variant deserialized))
                       (= (:adt/type sm) (:adt/type deserialized))))))

;; =============================================================================
;; Property: Alias idempotence — resolve(resolve(x)) = resolve(x)
;; =============================================================================

(defspec alias-resolution-idempotent 200
  (prop/for-all [sm gen-spawn-mode]
                (let [once (sm/resolve-alias sm)
                      twice (sm/resolve-alias once)]
                  (= (sm/to-keyword once) (sm/to-keyword twice)))))

(defspec canonical-variants-unchanged-by-resolve 200
  (prop/for-all [sm gen-spawn-mode]
                (let [resolved (sm/resolve-alias sm)]
                  (sm/canonical? resolved))))

;; =============================================================================
;; Property: Emacs/Headless partition — every variant is exactly one
;; =============================================================================

(defspec emacs-headless-partition-complete 200
  (prop/for-all [kw gen-spawn-mode-keyword]
                (let [in-emacs? (contains? sm/emacs-variants kw)
                      in-headless? (contains? sm/headless-variants kw)]
      ;; Exactly one of the two sets — XOR
                  (and (or in-emacs? in-headless?)
                       (not (and in-emacs? in-headless?))))))

(defspec requires-emacs-matches-partition 200
  (prop/for-all [sm gen-spawn-mode]
                (let [kw (sm/to-keyword sm)]
                  (= (sm/requires-emacs? sm)
                     (contains? sm/emacs-variants kw)))))

;; =============================================================================
;; Property: Metadata totality — every variant has io-model and capabilities
;; =============================================================================

(defspec io-model-is-total 200
  (prop/for-all [sm gen-spawn-mode]
                (let [model (sm/io-model sm)]
                  (contains? #{:buffer :stdin-stdout :api} model))))

(defspec capabilities-is-total 200
  (prop/for-all [sm gen-spawn-mode]
                (let [caps (sm/capabilities sm)]
                  (and (set? caps)
                       (seq caps)))))

(defspec all-variants-have-dispatch-capability 200
  (prop/for-all [sm gen-spawn-mode]
                (sm/has-capability? sm :dispatch)))

(defspec all-variants-have-kill-capability 200
  (prop/for-all [sm gen-spawn-mode]
                (sm/has-capability? sm :kill)))

;; =============================================================================
;; Property: ADT type consistency
;; =============================================================================

(defspec adt-type-is-always-spawn-mode 200
  (prop/for-all [sm gen-spawn-mode]
                (= :SpawnMode (adt/adt-type sm))))

(defspec adt-valid-for-all-variants 200
  (prop/for-all [sm gen-spawn-mode]
                (adt/adt-valid? sm)))

;; =============================================================================
;; Property: Exhaustiveness — adt-case covers all variants
;; =============================================================================

(defspec exhaustive-dispatch-returns-value 200
  (prop/for-all [sm gen-spawn-mode]
                (let [result (adt-case sm/SpawnMode sm
                                       :claude     :emacs-claude
                                       :vterm      :emacs
                                       :headless   :subprocess
                                       :agent-sdk  :sdk)]
                  (contains? #{:emacs-claude :emacs :subprocess :sdk} result))))

;; =============================================================================
;; Property: Constructor consistency with registry
;; =============================================================================

(defspec variant-set-matches-registry 200
  (prop/for-all [kw gen-spawn-mode-keyword]
                (registry/valid-mode? kw)))

(defspec slot-limit-consistency 200
  (prop/for-all [sm gen-spawn-mode]
                (let [kw (sm/to-keyword sm)]
                  (= (sm/slot-limit sm)
                     (registry/slot-limit kw)))))

;; =============================================================================
;; Deterministic unit tests (edge cases)
;; =============================================================================

(deftest exactly-four-variants
  (testing "SpawnMode has exactly 4 core variants (post-:openrouter cleanup)"
    (is (= 4 (count sm/all-variants)))))

(deftest variant-keywords-correct
  (testing "Variant set matches expected core/abstract modes"
    (is (= #{:claude :vterm :headless :agent-sdk} sm/all-variants))))

(deftest mcp-variants-correct
  (testing "MCP-visible variants are claude, vterm and headless"
    (is (= #{:claude :vterm :headless} sm/mcp-variants))))

(deftest headless-has-no-static-alias
  (testing "resolve-alias leaves :headless unchanged (resolved dynamically at spawn time)"
    (is (= :headless (sm/to-keyword (sm/resolve-alias (sm/spawn-mode :headless)))))))

(deftest all-variants-resolve-to-self
  (testing "All variants are canonical post-cleanup"
    (doseq [kw [:claude :vterm :headless :agent-sdk]]
      (is (= kw (sm/to-keyword (sm/resolve-alias (sm/spawn-mode kw))))
          (str kw " should resolve to itself")))))

(deftest nil-for-invalid-input
  (testing "from-keyword returns nil for invalid inputs"
    (is (nil? (sm/from-keyword :bogus)))
    (is (nil? (sm/from-keyword "invalid")))
    (is (nil? (sm/from-keyword nil)))))
