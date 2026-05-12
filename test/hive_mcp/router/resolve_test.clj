(ns hive-mcp.router.resolve-test
  "Pure unit + property tests for `hive-mcp.router.resolve`.

   Pin the post-Ship-2 invariants:

   1. Every type in `:routes` resolves to a `ProviderSpec` whose
      `:provider/dim` matches the configured provider's `:dimension`
      (the split-brain regression that sat behind 1804 — fail this
      property and a write would land in a wrong-dim collection).
   2. Unknown types fall through to `:default` deterministically.
   3. Missing `:default` AND missing route → `:router/no-default` err.
   4. Bare-keyword routes (`:note → :ollama-nomic`) work alongside
      `:type/note` namespaced routes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-dsl.result :as r]
            [hive-mcp.router.resolve :as resolve]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def sample-config
  {:default :ollama-qwen3-local
   :routes  {:type/plan     :venice-qwen3
             :type/decision :venice-qwen3
             :type/note     :ollama-qwen3-local
             :type/axiom    :ollama-qwen3-local
             :note          :ollama-nomic} ; legacy bare-keyword
   :providers {:ollama-nomic       {:impl :ollama  :model "nomic-embed-text"
                                    :dimension 768  :max-tokens 2048}
               :ollama-qwen3-local {:impl :ollama  :model "qwen3-embedding:0.6b"
                                    :dimension 1024 :max-tokens 8192}
               :venice-qwen3       {:impl :venice  :model "text-embedding-qwen3-8b"
                                    :dimension 4096 :max-tokens 28000}}})

(deftest resolves-explicit-namespaced-route
  (testing ":type/plan → venice-qwen3 (4096-d) per :routes"
    (let [result (resolve/resolve-spec sample-config :type/plan)]
      (is (r/ok? result))
      (is (= :venice-qwen3 (:provider/key (:ok result))))
      (is (= 4096          (:provider/dim (:ok result)))))))

(deftest resolves-bare-keyword-route-takes-precedence-over-default
  (testing ":routes :note (bare kw) wins over :default"
    (let [result (resolve/resolve-spec sample-config :note)]
      (is (r/ok? result))
      ;; sample-config has BOTH :type/note (qwen3-local) and :note (nomic).
      ;; Namespaced wins per type->keys precedence.
      (is (= :ollama-qwen3-local (:provider/key (:ok result)))))))

(deftest unknown-type-falls-through-to-default
  (testing "no matching route → :default key"
    (let [result (resolve/resolve-spec sample-config :type/totally-unknown)]
      (is (r/ok? result))
      (is (= :ollama-qwen3-local (:provider/key (:ok result)))))))

(deftest missing-default-and-routes-errs
  (testing "config without :default and without route → :router/no-default"
    (let [result (resolve/resolve-spec
                   {:providers {:x {:impl :ollama :model "m" :dimension 768 :max-tokens 100}}}
                   :type/anything)]
      (is (r/err? result))
      (is (= :router/no-default (:error result))))))

(deftest unknown-provider-key-errs
  (testing ":default points to a key not in :providers → :router/unknown-provider"
    (let [result (resolve/resolve-spec
                   {:default :nonexistent
                    :providers {}}
                   :type/x)]
      (is (r/err? result))
      (is (= :router/unknown-provider (:error result))))))

(deftest invalid-spec-shape-errs
  (testing "provider missing :dimension → :router/invalid-provider-spec"
    (let [result (resolve/resolve-spec
                   {:default :broken
                    :providers {:broken {:impl :ollama :model "m" :max-tokens 100}}}
                   :type/x)]
      (is (r/err? result))
      (is (= :router/invalid-provider-spec (:error result))))))

;; ---------------------------------------------------------------------------
;; Split-brain regression — property test
;; ---------------------------------------------------------------------------

(def known-types
  "Every memory-type that has an entry in sample-config :routes, plus
   types that fall through to :default. Generator picks uniformly."
  [:type/plan :type/decision :type/note :type/axiom
   :type/something-unknown :type/conversation-turn
   :note :unknown-bare])

(defspec resolved-spec-dim-matches-provider-dim 200
  (prop/for-all [t (gen/elements known-types)]
    (let [result (resolve/resolve-spec sample-config t)]
      (and (r/ok? result)
           (let [spec (:ok result)
                 expected-dim (-> sample-config :providers
                                  (get (:provider/key spec))
                                  :dimension)]
             (= expected-dim (:provider/dim spec)))))))

(defspec every-type-resolves-totally 200
  (prop/for-all [t (gen/elements known-types)]
    (r/ok? (resolve/resolve-spec sample-config t))))
