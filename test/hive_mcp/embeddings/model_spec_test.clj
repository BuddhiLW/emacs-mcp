(ns hive-mcp.embeddings.model-spec-test
  "Trifecta for model-spec resolution.

   The property + mutation facets are SYNTHESIZED from the malli schemas by
   hive-schemas.test — no hand-written generator or oracle. Hand-written below
   only what a schema cannot state: that a later layer wins, that a nil does not
   erase the layer beneath it, and that the catalog port composes."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-schemas.test :as hst]
            [hive-test.mutation :as mut]
            [hive-mcp.embeddings.model-spec :as spec]))

;; ============================================================================
;; Schema-synthesized
;; ============================================================================

;; THE THEOREM: collapsing layers never invents a value and never lets an
;; earlier layer beat a later one.
(hst/deftrifecta-from-schema merge-layers-lets-the-last-layer-win
  hive-mcp.embeddings.model-spec/merge-layers
  {:in  spec/Layers
   :out [:map]
   :rel (fn [layers out]
          (every?
           (fn [k]
             (let [stated (->> layers (keep #(get % k)) last)]
               (= (get out k) stated)))
           [:dimension :num-ctx :vram-mb]))
   :mutation false
   :num-tests 300})

(mut/deftest-mutations merge-layers-mutants-are-caught
  hive-mcp.embeddings.model-spec/merge-layers
  [["lets the FIRST layer win — config would never override a default"
    (fn [layers] (reduce (fn [acc l] (merge (into {} (remove (comp nil? val) l)) acc)) {} layers))]
   ["lets a declared nil erase the layer beneath it"
    (fn [layers] (reduce merge {} layers))]]
  (fn []
    (let [floor   {:num-ctx 32768 :vram-mb 4000}
          config  {:num-ctx 8192}
          silent  {:num-ctx nil}]
      ;; later layer wins
      (is (= 8192 (:num-ctx (spec/merge-layers [floor config]))))
      ;; a nil is silence, not an instruction
      (is (= 32768 (:num-ctx (spec/merge-layers [floor silent]))))
      ;; and the key it says nothing about survives
      (is (= 4000 (:vram-mb (spec/merge-layers [floor config])))))))

;; ============================================================================
;; The port — catalogs compose, config over defaults
;; ============================================================================

(deftest config-overrides-the-built-in-spec
  (testing "a declared num-ctx beats the shipped default for that model"
    ;; THE BUG this exists to prevent: qwen3-embedding:4b was loaded at a 32k
    ;; context because a hardcoded regex, not config, decided. 11 GB on an 8 GB
    ;; card, 34% spilled to CPU, embeds 0.1s -> 4s.
    (let [catalog (spec/default-catalog {"qwen3-embedding:4b" {:num-ctx 4096}})
          s       (spec/spec-for catalog "qwen3-embedding:4b")]
      (is (= 4096 (:num-ctx s)) "config wins")
      (is (= 2560 (:dimension s)) "and the keys config did not mention survive"))))

(deftest an-undeclared-model-falls-back-to-what-we-ship
  (let [s (spec/spec-for (spec/default-catalog {}) "qwen3-embedding:4b")]
    (is (= 2560 (:dimension s)))
    (is (= 8192 (:num-ctx s)))))

(deftest an-unknown-model-has-no-dimension-to-invent
  (testing "we refuse to guess a vector width"
    (let [s (spec/spec-for (spec/default-catalog {}) "some-model-nobody-shipped")]
      (is (nil? (:dimension s)))
      (is (= spec/default-num-ctx (:num-ctx s)) "but it still gets a safe context")
      (is (false? (spec/known-model? (spec/default-catalog {}) "some-model-nobody-shipped"))))))

(deftest an-unknown-model-becomes-known-by-declaring-it
  (testing "config alone is enough — no table edit"
    (let [catalog (spec/default-catalog {"brand-new:1b" {:dimension 1536 :num-ctx 4096}})
          s       (spec/spec-for catalog "brand-new:1b")]
      (is (= 1536 (:dimension s)))
      (is (= 4096 (:num-ctx s)))
      (is (true? (spec/known-model? catalog "brand-new:1b"))))))

(deftest the-empty-catalog-knows-nothing-and-says-so
  (let [s (spec/spec-for (spec/empty-catalog) "nomic-embed-text")]
    (is (nil? (:dimension s)))
    (is (= spec/default-num-ctx (:num-ctx s)))))

(deftest layering-is-left-to-right
  (let [catalog (spec/layered (spec/table-catalog {"m" {:num-ctx 1024 :dimension 8}})
                              (spec/table-catalog {"m" {:num-ctx 2048}}))
        s       (spec/spec-for catalog "m")]
    (is (= 2048 (:num-ctx s)) "the rightmost catalog wins")
    (is (= 8 (:dimension s)) "and does not erase what it is silent about")))

(deftest a-caching-catalog-answers-what-the-inner-one-would
  (let [inner  (spec/default-catalog {"m" {:dimension 42 :num-ctx 512}})
        cached (spec/caching inner)]
    (is (= (spec/spec-for inner "m") (spec/spec-for cached "m")))
    (is (= (spec/spec-for inner "m") (spec/spec-for cached "m")) "and again, from cache")))
