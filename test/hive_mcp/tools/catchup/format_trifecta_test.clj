(ns hive-mcp.tools.catchup.format-trifecta-test
  "Trifecta tests for catchup format functions.

   Complements the plain clojure.test suite in format_test.clj with:
   - Golden snapshots for regression detection
   - Property-based tests for generative coverage
   - Mutation tests to verify test sensitivity"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-test.generators.memory :as gen-mem]
            [hive-test.generators.core :as gen-core]
            [hive-mcp.tools.catchup.format :as fmt]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-memory-entry-with-id
  "gen-memory-entry enriched with :id (required by entry->catchup-meta)."
  (gen/let [entry gen-mem/gen-memory-entry
            id    gen-core/gen-uuid-str]
    (assoc entry :id id)))

(def gen-catchup-meta-args
  "Generator for [entry preview-len] arg pairs."
  (gen/let [entry gen-memory-entry-with-id
            plen  (gen/one-of [(gen/return nil)
                               (gen/choose 10 200)])]
    [entry plen]))

(def gen-axiom-entry
  "Generator for axiom entries with :id and :content of varying length."
  (gen/let [id      gen-core/gen-uuid-str
            content (gen/fmap #(apply str (repeat % "x"))
                              (gen/choose 1 1200))
            tags    gen-mem/gen-tags]
    {:id id :content content :tags tags}))

(def gen-insight-list
  "Generator for a vector of 0-20 placeholder items."
  (gen/vector gen/small-integer 0 20))

(def gen-kg-insights
  "Generator for KG insight maps with variable-length lists."
  (gen/let [stale      gen-insight-list
            contras    gen-insight-list
            superseded gen-insight-list
            stale-ent  gen-insight-list]
    {:stale-files stale
     :contradictions contras
     :superseded superseded
     :grounding-warnings {:stale-entries stale-ent}}))

;; =============================================================================
;; 1. Trifecta: entry->catchup-meta
;;
;;    Golden: representative entries -> lean meta {:id :T :P}
;;    Property: totality over gen-memory-entry; output always has :id :T :P
;;    Mutation: missing :T should fail golden comparison
;; =============================================================================

(deftrifecta entry->catchup-meta-shape
  hive-mcp.tools.catchup.format/entry->catchup-meta
  {:golden-path "test/golden/catchup/entry-catchup-meta.edn"
   :apply?      true
   :cases       {:decision-full [{:id "d-1" :type :decision :content "Chose React over Vue" :tags ["fe"]} 80]
                 :note-nil-type [{:id "n-1" :content "Quick observation" :tags []} 80]
                 :long-preview  [{:id "l-1" :type :snippet :content (apply str (repeat 200 "z")) :tags ["code"]} 40]
                 :numeric-body  [{:id "x-1" :type :note :content 42 :tags []} 80]
                 :nil-preview   [{:id "p-1" :type :axiom :content "Short" :tags []} nil]}
   :xf          (fn [m] (update m :P #(if (> (count %) 40) (str (subs % 0 40) "...") %)))
   :gen         gen-catchup-meta-args
   :pred        (fn [result] (every? #(contains? result %) [:id :T :P]))
   :property-type :pred-fn
   :num-tests   200
   :mutations   [["nil-type" (fn [e _] {:id (:id e)})]]})

;; Extra property: totality (never throws on any generated entry)
(deftrifecta entry->catchup-meta-totality
  hive-mcp.tools.catchup.format/entry->catchup-meta
  {:gen         gen-catchup-meta-args
   :apply?      true
   :num-tests   200})

;; =============================================================================
;; 2. Trifecta: cap-axiom-content
;;
;;    Golden: short content passthrough, long content truncated with hint
;;    Property: output :content length <= axiom-content-cap + 100
;;    Mutation: identity (never truncates) should fail on long input
;; =============================================================================

(deftrifecta cap-axiom-content-budget
  hive-mcp.tools.catchup.format/cap-axiom-content
  {:golden-path "test/golden/catchup/cap-axiom-content.edn"
   :cases       {:short-pass   {:id "a-1" :content "Brief axiom rule" :tags ["axiom"]}
                 :exact-cap    {:id "a-2" :content (apply str (repeat 600 "a")) :tags ["axiom"]}
                 :over-cap     {:id "a-3" :content (apply str (repeat 800 "b")) :tags ["axiom"]}
                 :way-over     {:id "a-4" :content (apply str (repeat 2000 "c")) :tags []}
                 :nil-content  {:id "a-5" :content nil :tags []}}
   :xf          (fn [entry]
                  {:id       (:id entry)
                   :len      (count (str (:content entry)))
                   :capped?  (boolean (and (:content entry)
                                           (.contains (str (:content entry)) "[TRUNCATED")))})
   :gen         gen-axiom-entry
   :pred        (fn [result]
                  (<= (count (str (:content result)))
                      (+ fmt/axiom-content-cap 100)))
   :num-tests   200
   :mutations   [["no-cap" identity]]})

;; =============================================================================
;; 3. Trifecta: trim-kg-insights
;;
;;    Golden: nil->nil, oversized lists->trimmed, small lists->passthrough
;;    Property: stale-files always <= 5, contradictions <= 5, superseded <= 5
;;    Mutation: identity (never trims) should fail on oversized input
;; =============================================================================

(deftrifecta trim-kg-insights-bounds
  hive-mcp.tools.catchup.format/trim-kg-insights
  {:golden-path "test/golden/catchup/trim-kg-insights.edn"
   :cases       {:nil-input     nil
                 :empty-map     {}
                 :small-lists   {:stale-files [1 2] :contradictions [3]}
                 :oversized     {:stale-files (vec (range 20))
                                 :contradictions (vec (range 15))
                                 :superseded (vec (range 12))
                                 :grounding-warnings {:stale-entries (vec (range 25))}}
                 :only-stale    {:stale-files (vec (range 10))}
                 :only-warnings {:grounding-warnings {:stale-entries (vec (range 20))}}}
   :xf          (fn [result]
                  (when result
                    (cond-> {}
                      (:stale-files result)
                      (assoc :stale-files-count (count (:stale-files result)))

                      (:contradictions result)
                      (assoc :contradictions-count (count (:contradictions result)))

                      (:superseded result)
                      (assoc :superseded-count (count (:superseded result)))

                      (get-in result [:grounding-warnings :stale-entries])
                      (assoc :stale-entries-count
                             (count (get-in result [:grounding-warnings :stale-entries]))))))
   :gen         (gen/one-of [(gen/return nil) gen-kg-insights])
   :pred        (fn [result]
                  (if (nil? result)
                    true
                    (and (<= (count (get result :stale-files [])) 5)
                         (<= (count (get result :contradictions [])) 5)
                         (<= (count (get result :superseded [])) 5)
                         (<= (count (get-in result [:grounding-warnings :stale-entries] [])) 10))))
   :num-tests   200
   :mutations   [["no-trim" identity]]})
