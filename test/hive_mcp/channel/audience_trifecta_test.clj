(ns hive-mcp.channel.audience-trifecta-test
  "Golden + property + mutation pinning for the piggyback audience layer.

   Two subjects, both pure:

     addressed-to? — the anti-pollution routing decision. The property that
                     matters is a PARTITION one: a non-broadcast shout with a
                     spawner reaches that spawner and no other ling, whatever
                     the ids happen to be.
     digest        — the anti-micromanagement collapse. The property that
                     matters is CONSERVATION: every input row is either
                     present verbatim or accounted for in some rollup's :n.
                     A digest that silently dropped a shout would look exactly
                     like a working one on a golden case.

   Mutants are self-contained — none references the subject var, which
   alter-var-root has already rebound to the mutant by then (see memory
   20260701180828-04a7cec5)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as tc-prop]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.channel.audience :as aud]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Adapters — collapse the binary subject into the trifecta's unary contract
;; =============================================================================

(defn run-addressed-to
  "Unary adapter: {:reader id :msg shout} -> boolean."
  [{:keys [reader msg]}]
  (aud/addressed-to? reader msg))

;; =============================================================================
;; Generators
;; =============================================================================

(def ^:private gen-ling-id
  (gen/fmap #(str "ling-" %) gen/string-alphanumeric))

(def ^:private gen-reader
  (gen/one-of [gen-ling-id
               (gen/return "coordinator")
               (gen/return "coordinator-hive")]))

(def ^:private gen-msg
  (gen/let [agent-id gen-ling-id
            parent (gen/one-of [(gen/return nil)
                                (gen/return "coordinator")
                                gen-ling-id])
            bcast gen/boolean]
    (cond-> {:agent-id agent-id}
      parent (assoc :parent-id parent)
      bcast (assoc :broadcast? true))))

(def ^:private gen-event
  (gen/elements ["progress" "started" "completed" "error" "aborted"]))

(def ^:private gen-row
  (gen/let [a (gen/fmap #(str "a" %) (gen/choose 0 3))
            e gen-event
            m gen/string-alphanumeric]
    {:a a :e e :m m}))

(def ^:private gen-rows (gen/vector gen-row 0 40))

;; =============================================================================
;; 1. addressed-to? — golden + property + mutation
;; =============================================================================

(def ^:private child-shout
  {:agent-id "vt-billing" :parent-id "coordinator" :project-id "hive"})

(def ^:private grandchild-shout
  {:agent-id "grandchild" :parent-id "ling-a"})

(deftrifecta addressed-to-contract
  hive-mcp.channel.audience-trifecta-test/run-addressed-to
  {:golden-path "test/golden/channel/audience-addressed-to.edn"
   :cases       {:spawner-coordinator   {:reader "coordinator-hive" :msg child-shout}
                 :sibling-ling          {:reader "vt-media" :msg child-shout}
                 :self-echo             {:reader "vt-billing" :msg child-shout}
                 :spawner-ling          {:reader "ling-a" :msg grandchild-shout}
                 :coordinator-skips-gc  {:reader "coordinator-hive" :msg grandchild-shout}
                 :root-to-coordinator   {:reader "coordinator-hive" :msg {:agent-id "wave"}}
                 :root-not-to-ling      {:reader "some-ling" :msg {:agent-id "wave"}}
                 :broadcast-to-anyone   {:reader "any-ling"
                                         :msg {:agent-id "coordinator" :broadcast? true}}}
   :gen         (gen/let [reader gen-reader msg gen-msg] {:reader reader :msg msg})
   :pred        boolean?
   :num-tests   300
   :mutations   [["always-true — restores the project-wide broadcast"
                  (constantly true)]
                 ["always-false — nobody ever hears anything"
                  (constantly false)]
                 ["ignores-parent — routes on project membership alone"
                  (fn [{:keys [reader msg]}]
                    (not= (str reader) (str (:agent-id msg))))]
                 ["parent-check-inverted"
                  (fn [{:keys [reader msg]}]
                    (not= (str reader) (str (:parent-id msg))))]]
   :assert      (fn []
                  (is (true? (run-addressed-to {:reader "coordinator-hive"
                                                :msg child-shout}))
                      "the spawner receives")
                  (is (false? (run-addressed-to {:reader "vt-media"
                                                 :msg child-shout}))
                      "a sibling does NOT receive")
                  (is (false? (run-addressed-to {:reader "vt-billing"
                                                 :msg child-shout}))
                      "the author does not read its own shout")
                  (is (true? (run-addressed-to {:reader "any-ling"
                                                :msg {:agent-id "c" :broadcast? true}}))
                      "broadcast reaches everyone"))})

(defspec addressed-to-partitions-the-swarm 300
  (tc-prop/for-all [msg gen-msg
                    r1 gen-ling-id
                    r2 gen-ling-id]
    ;; For a non-broadcast shout, any ling that is neither the author nor the
    ;; named spawner is excluded — so no number of siblings can ever widen the
    ;; audience. This is the property the whole change exists to buy.
    (let [{:keys [agent-id parent-id broadcast?]} msg
          outsider? (fn [r] (and (not= r agent-id) (not= r parent-id)))]
      (or broadcast?
          (every? #(not (aud/addressed-to? % msg))
                  (filter outsider? [r1 r2]))))))

(defspec addressed-to-always-reaches-the-spawner 200
  (tc-prop/for-all [author gen-ling-id
                    parent gen-ling-id]
    ;; A named spawner that is not the author always receives. Guards against
    ;; a routing tightening that silently orphans a running child.
    (or (= author parent)
        (aud/addressed-to? parent {:agent-id author :parent-id parent}))))

;; =============================================================================
;; 2. digest — golden + property + mutation
;; =============================================================================

(defn- burst
  [a n]
  (mapv #(hash-map :a a :e "progress" :m (str "turn " %)) (range 1 (inc n))))

(defn- row-weight
  "How many input rows an output row stands for."
  [row]
  (or (:n row) 1))

(deftrifecta digest-contract
  hive-mcp.channel.audience/digest
  {:golden-path "test/golden/channel/audience-digest.edn"
   :cases       {:empty          []
                 :single         [{:a "a" :e "progress" :m "only"}]
                 :burst-of-21    (burst "vt-billing" 21)
                 :two-agents     (into (burst "a" 3) (burst "b" 2))
                 :lifecycle-kept [{:a "a" :e "progress" :m "t1"}
                                  {:a "a" :e "progress" :m "t2"}
                                  {:a "a" :e "error" :m "boom"}]
                 :interleaved    [{:a "a" :e "progress" :m "a1"}
                                  {:a "b" :e "completed" :m "done"}
                                  {:a "a" :e "progress" :m "a2"}]}
   :gen         gen-rows
   :pred        vector?
   :num-tests   300
   :mutations   [["drops-everything" (constantly [])]
                 ["never-collapses" (fn [rows] (vec rows))]
                 ["keeps-first-not-last"
                  (fn [rows]
                    (let [prog? #(= "progress" (:e %))
                          seen (volatile! #{})]
                      (filterv (fn [r]
                                 (if-not (prog? r)
                                   true
                                   (when-not (@seen (:a r))
                                     (vswap! seen conj (:a r))
                                     true)))
                               rows)))]
                 ["collapses-lifecycle-too"
                  (fn [rows] (vec (vals (into {} (map (juxt :a identity)) rows))))]
                 ["loses-the-count"
                  (fn [rows]
                    (let [prog? #(= "progress" (:e %))
                          last-of (reduce (fn [acc [i r]]
                                            (if (prog? r) (assoc acc (:a r) i) acc))
                                          {} (map-indexed vector rows))]
                      (into [] (keep-indexed
                                (fn [i r]
                                  (cond (not (prog? r)) r
                                        (= i (get last-of (:a r))) r
                                        :else nil))
                                rows))))]]
   :assert      (fn []
                  (let [out (aud/digest (burst "x" 21))]
                    (is (= 1 (count out)) "a burst collapses to one row")
                    (is (= 21 (:n (first out))) "and carries the count")
                    (is (= "turn 21" (:m (first out))) "reporting the LAST turn"))
                  (let [out (aud/digest [{:a "a" :e "progress" :m "t1"}
                                         {:a "a" :e "progress" :m "t2"}
                                         {:a "a" :e "error" :m "boom"}])]
                    (is (= ["progress" "error"] (mapv :e out))
                        "lifecycle rows survive verbatim and in place"))
                  (is (= [{:a "a" :e "progress" :m "only"}]
                         (aud/digest [{:a "a" :e "progress" :m "only"}]))
                      "a lone row gains no :n"))})

(defspec digest-conserves-every-row 300
  (tc-prop/for-all [rows gen-rows]
    ;; Nothing is silently lost: the output's weights sum to the input count.
    (= (count rows)
       (reduce + 0 (map row-weight (aud/digest rows))))))

(defspec digest-never-drops-a-lifecycle-row 300
  (tc-prop/for-all [rows gen-rows]
    (let [lifecycle #(not= "progress" (:e %))]
      (= (filterv lifecycle rows)
         (filterv lifecycle (aud/digest rows))))))

(defspec digest-is-idempotent 200
  (tc-prop/for-all [rows gen-rows]
    ;; A second pass has nothing left to collapse.
    (let [once (aud/digest rows)]
      (= once (aud/digest once)))))

(defspec digest-never-grows 300
  (tc-prop/for-all [rows gen-rows]
    (<= (count (aud/digest rows)) (count rows))))

(defspec digest-keeps-at-most-one-progress-row-per-agent 300
  (tc-prop/for-all [rows gen-rows]
    (let [progress (filter #(= "progress" (:e %)) (aud/digest rows))]
      (= (count progress) (count (distinct (map :a progress)))))))

;; =============================================================================
;; 3. The two together — the end-to-end reduction a coordinator actually sees
;; =============================================================================

(deftest routing-then-digest-shrinks-a-real-swarm-burst-test
  (testing "three lings × 20 turns each: 60 shouts in, 3 rows out for the
            spawner, 0 rows for any sibling"
    (let [shouts (for [a ["vt-billing" "vt-media" "vt-pack"]
                       t (range 1 21)]
                   {:agent-id a :parent-id "coordinator"
                    :event-type :progress :message (str "turn " t)})
          fmt (fn [ms] (mapv (fn [{:keys [agent-id event-type message]}]
                               {:a agent-id :e (name event-type) :m message})
                             ms))
          for-reader (fn [r] (aud/digest (fmt (aud/filter-messages r shouts))))]
      (is (= 60 (count shouts)))
      (is (= 3 (count (for-reader "coordinator-hive"))) "one row per ling")
      (is (= #{20} (set (map :n (for-reader "coordinator-hive"))))
          "each row accounts for its 20 turns")
      (is (= [] (for-reader "vt-media")) "a sibling sees nothing"))))
