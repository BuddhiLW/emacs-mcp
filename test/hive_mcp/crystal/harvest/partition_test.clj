(ns hive-mcp.crystal.harvest.partition-test
  "Trifecta + integration tests for the partitioner (step-3 of plan
   `20260504173159-46dc47f1`).

   Invariants:
   1. Output validates against the HarvestByScope malli schema.
   2. **Conservation**: input flat-datum count = scope-datum + umbrella-datum.
      Property test confirms this for arbitrary attribution shapes.
   3. Strong-attribution datums route to ScopeSlice keyed by their pid.
   4. `:umbrella` sentinel datums route to typed UmbrellaSlice fields by
      slice-key (kg-edges → cross-pid-edges, hivemind → shouts-global,
      everything else → cross-cutting-decisions).
   5. Whole-session scalars (timing, temporal) merge into UmbrellaSlice."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-mcp.crystal.harvest.by-scope :as bs]
            [hive-mcp.crystal.harvest.partition :as p]
            [hive-test.trifecta :refer [deftrifecta]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Generators
;; =============================================================================

(def ^:private gen-pid
  (gen/elements ["hive" "hive-mcp" "funeraria" "sisf-crm"]))

(def ^:private gen-scope-or-umbrella
  (gen/one-of [gen-pid (gen/return bs/umbrella-sentinel)]))

(def ^:private gen-attributed-datum
  (gen/hash-map :pid   gen-scope-or-umbrella
                :datum (gen/hash-map :sample gen/string-alphanumeric)))

(def ^:private gen-slice-key
  (gen/elements [:progress-notes :completed-tasks :git-commits :recalls
                 :hivemind-messages :kg-edges-created :kanban-movements
                 :memory-ids-created :memory-ids-accessed]))

(def ^:private slice-key->datum-gen
  "Each ScopeSlice field expects datums of a specific type. Map slice-key
   → generator that produces type-correct datums so partitioner output
   validates HarvestByScope."
  {:progress-notes      (gen/hash-map :n gen/string-alphanumeric)
   :completed-tasks     (gen/hash-map :title gen/string-alphanumeric)
   :git-commits         gen/string-alphanumeric
   :recalls             gen/any
   :hivemind-messages   (gen/hash-map :m gen/string-alphanumeric)
   :kg-edges-created    (gen/hash-map :kg-edge/scope gen/string-alphanumeric)
   :kanban-movements    (gen/hash-map :kanban-movement/from gen/string-alphanumeric)
   :memory-ids-created  (gen/hash-map :id gen/string-alphanumeric)
   :memory-ids-accessed gen/string-alphanumeric})

(defn- gen-attributed-for
  "Build an attributed-datum generator typed for `slice-key`."
  [slice-key]
  (gen/hash-map :pid   gen-scope-or-umbrella
                :datum (slice-key->datum-gen slice-key gen/any)))

(def ^:private gen-attribution-output
  "An attribution-shaped map with arbitrary slice-keys and a small bag of
   type-correct attributed datums per slice."
  (gen/let [n-slices (gen/choose 1 6)
            slice-keys (gen/vector-distinct gen-slice-key {:num-elements n-slices})]
    (gen/let [datums-per-slice (apply gen/tuple
                                      (mapv (fn [k]
                                              (gen/vector (gen-attributed-for k) 0 5))
                                            slice-keys))]
      {:by-source        (zipmap slice-keys datums-per-slice)
       :umbrella-scalars {}
       :session          "test-session"
       :directory        "/tmp"
       :agent-id         "test"
       :errors           []})))

;; =============================================================================
;; Wrappers (deftrifecta needs var-symbols)
;; =============================================================================

(defn- partition+validity
  [attribution]
  (let [hbs (p/partition-harvest-by-scope attribution)]
    {:valid? (bs/valid? hbs)}))

(defn- partition+conservation
  [attribution]
  (let [hbs (p/partition-harvest-by-scope attribution)
        flat (apply + (map count (vals (:by-source attribution))))
        total (p/total-datum-count hbs)]
    {:flat flat :total total :conserved? (= flat total)}))

;; =============================================================================
;; Trifecta — output always validates HarvestByScope schema
;; =============================================================================

(deftrifecta partition--always-valid
  partition+validity
  {:golden-path "test/golden/hive-mcp/crystal/harvest/partition-validity.edn"
   :cases       {:empty   {:by-source {} :umbrella-scalars {}}
                 :scoped  {:by-source {:progress-notes [{:pid "hive" :datum {:n 1}}]}
                           :umbrella-scalars {}}
                 :umbrella {:by-source {:kg-edges-created [{:pid bs/umbrella-sentinel
                                                              :datum {:e 1}}]}
                            :umbrella-scalars {}}
                 :mixed   {:by-source {:progress-notes [{:pid "hive" :datum {:n 1}}]
                                       :hivemind-messages [{:pid bs/umbrella-sentinel
                                                             :datum {:m "x"}}]}
                           :umbrella-scalars {:session-timing {:t 1}}}}
   :gen         gen-attribution-output
   :pred        (fn [r] (true? (:valid? r)))
   :mutations   [["drop-by-scope"   (fn [_] {:valid? false})]
                 ["always-invalid"  (fn [_] {:valid? false})]]})

;; =============================================================================
;; Trifecta — conservation property (the critical invariant)
;; =============================================================================

(deftrifecta partition--conserves-datum-count
  partition+conservation
  {:golden-path "test/golden/hive-mcp/crystal/harvest/partition-conservation.edn"
   :cases       {:empty
                 {:by-source {} :umbrella-scalars {}}

                 :all-scoped
                 {:by-source {:progress-notes [{:pid "hive" :datum {:n 1}}
                                               {:pid "funeraria" :datum {:n 2}}]}
                  :umbrella-scalars {}}

                 :all-umbrella
                 {:by-source {:kg-edges-created [{:pid bs/umbrella-sentinel :datum {:e 1}}
                                                  {:pid bs/umbrella-sentinel :datum {:e 2}}]}
                  :umbrella-scalars {}}

                 :mixed-with-recalls
                 {:by-source {:progress-notes [{:pid "hive" :datum {:n 1}}]
                              :recalls        [{:pid "funeraria" :datum ["id-a" {}]}]
                              :git-commits    [{:pid "hive" :datum "abc"}]}
                  :umbrella-scalars {:session-timing {:t 1}}}}
   :xf          (fn [r] (select-keys r [:flat :total :conserved?]))
   :gen         gen-attribution-output
   :pred        :conserved?
   :mutations   [["off-by-one" (fn [a]
                                 (let [r (partition+conservation a)]
                                   (assoc r :total (inc (:total r)) :conserved? false)))]
                 ["drop-all"   (fn [a]
                                 (let [r (partition+conservation a)]
                                   (assoc r :total 0 :conserved? (zero? (:flat r)))))]]})

;; =============================================================================
;; Plain deftests — routing semantics
;; =============================================================================

(deftest partition--strong-attribution-routes-to-scope
  (let [attr {:by-source {:progress-notes [{:pid "hive" :datum {:n 1}}
                                            {:pid "funeraria" :datum {:n 2}}]}
              :umbrella-scalars {}}
        hbs  (p/partition-harvest-by-scope attr)]
    (is (= #{"hive" "funeraria"} (bs/scope-pids hbs)))
    (is (= [{:n 1}] (get-in hbs [:by-scope "hive" :progress-notes])))
    (is (= [{:n 2}] (get-in hbs [:by-scope "funeraria" :progress-notes])))
    (is (true? (bs/umbrella-empty? hbs)))))

(deftest partition--umbrella-sentinel-routes-by-slice-key
  (let [attr {:by-source {:kg-edges-created   [{:pid bs/umbrella-sentinel :datum {:edge :a}}]
                          :hivemind-messages  [{:pid bs/umbrella-sentinel :datum {:m "x"}}]
                          :progress-notes     [{:pid bs/umbrella-sentinel :datum {:note 1}}]}
              :umbrella-scalars {}}
        hbs  (p/partition-harvest-by-scope attr)]
    (testing "kg-edges → cross-pid-edges"
      (is (= [{:edge :a}] (get-in hbs [:umbrella :cross-pid-edges]))))
    (testing "hivemind → hivemind-shouts-global"
      (is (= [{:m "x"}] (get-in hbs [:umbrella :hivemind-shouts-global]))))
    (testing "everything else → cross-cutting-decisions"
      (is (= [{:note 1}] (get-in hbs [:umbrella :cross-cutting-decisions]))))
    (testing "no scope slices for sentinel-only input"
      (is (= #{} (bs/scope-pids hbs))))))

(deftest partition--umbrella-scalars-merge
  (let [attr {:by-source {} :umbrella-scalars {:session-timing {:t 100}
                                                :session-temporal {:start "x"}}}
        hbs  (p/partition-harvest-by-scope attr)]
    (is (= {:t 100} (get-in hbs [:umbrella :session-timing])))
    (is (= {:start "x"} (get-in hbs [:umbrella :session-temporal])))))

(deftest partition--threads-session-metadata
  (let [attr {:by-source {} :umbrella-scalars {}
              :session "20260504" :directory "/x" :agent-id "ag1"}
        hbs  (p/partition-harvest-by-scope attr)]
    (is (= "20260504" (:session hbs)))
    (is (= "/x" (:directory hbs)))
    (is (= "ag1" (:agent-id hbs)))))

(deftest partition--errors-flow-through
  (let [attr {:by-source {} :umbrella-scalars {} :errors [{:type :timeout :fn "x"}]}
        hbs  (p/partition-harvest-by-scope attr)]
    (is (= [{:type :timeout :fn "x"}] (:errors hbs)))))

(deftest partition--mixed-real-shape-conserves-eight-datums
  (testing "the multi-scope fixture from the smoke test"
    (let [hr {:progress-notes [{:project-id "hive-mcp"} {:tags ["scope:project:funeraria"]}]
              :git-commits ["a1" "b2"]
              :kg-edges-created {:edges [{:kg-edge/scope "hive"} {:kg-edge/scope nil}]}
              :hivemind-messages [{:project-id "sisf-crm"} {:m "no-pid"}]
              :session "20260504" :directory "/x" :session-timing {:t 1}}
          attr ((requiring-resolve 'hive-mcp.crystal.harvest.attribution/attribute-harvest)
                hr "hive-source")
          hbs (p/partition-harvest-by-scope attr)
          flat (apply + (map count (vals (:by-source attr))))]
      (is (= 8 flat))
      (is (= 8 (p/total-datum-count hbs)))
      (is (= 5 (count (bs/scope-pids hbs))) "hive-mcp + funeraria + hive + sisf-crm + hive-source")
      (is (= 1 (p/umbrella-datum-count hbs)) "the nil-scope edge"))))