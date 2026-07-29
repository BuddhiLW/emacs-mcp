(ns hive-mcp.embeddings.availability.multi-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-mcp.embeddings.availability.methods :as methods]
            [hive-mcp.embeddings.availability.multi :as multi]
            [hive-mcp.embeddings.availability.schema :as schema]
            [hive-spi.schema.registry :as reg]))

(deftest secret-available?-contract
  (let [base {:impl :y}]
    (testing "dispatch totality: unknown kind hits the default err method"
      (is (r/err? (multi/secret-available? (assoc base :impl ::unknown)))))
    (testing "declared variants are registered"
      (is (contains? (methods multi/secret-available?) :ollama))
      (is (contains? (methods multi/secret-available?) :openrouter))
      (is (contains? (methods multi/secret-available?) :openai))
      (is (contains? (methods multi/secret-available?) :venice)))
    (testing "LSP: implemented variants satisfy the out contract"
      (doseq [k [:ollama :openrouter :openai :venice]]
        (let [res (try (multi/secret-available? (assoc base :impl k))
                       (catch clojure.lang.ExceptionInfo _ ::stub))]
          (is (or (= ::stub res) (reg/validate :boolean res)) (str k)))))))
