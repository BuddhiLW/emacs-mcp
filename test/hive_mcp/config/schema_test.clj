(ns hive-mcp.config.schema-test
  "Tests for config schema validation.

   Coverage:
   1. Valid memory config shapes (simple routes, dual-write routes)
   2. Invalid memory configs (missing required keys, wrong types)
   3. Full config validation (valid + invalid)
   4. Section validation API
   5. Default config validates cleanly
   6. Roundtrip: load → validate → access"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.config.schema :as schema]
            [hive-mcp.config.merge :as merge]
            [hive-mcp.config.core :as config]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn reset-fixture [f]
  (config/reset-config!)
  (f)
  (config/reset-config!))

(use-fixtures :each reset-fixture)

;; =============================================================================
;; Memory Section — Valid Cases
;; =============================================================================

(deftest test-valid-memory-simple-routes
  (testing "Simple keyword routes are valid"
    (let [mem {:default-store :milvus
               :routes {:decision :milvus
                        :snippet :milvus
                        :preference :chroma}
               :stores {:milvus {:addon :hive-milvus
                                  :host "localhost"
                                  :port 19530}
                        :chroma {:addon :hive-chroma
                                  :host "localhost"
                                  :port 8000}}}
          result (schema/validate-section :memory mem)]
      (is (:valid? result)))))

(deftest test-valid-memory-dual-write-routes
  (testing "Dual-write route maps are valid"
    (let [mem {:default-store :milvus
               :routes {:snippet {:primary :milvus :projection :chroma}
                        :decision :milvus}
               :stores {:milvus {:addon :hive-milvus}
                        :chroma {:addon :hive-chroma}}}
          result (schema/validate-section :memory mem)]
      (is (:valid? result)))))

(deftest test-valid-memory-minimal
  (testing "Minimal memory config (just default-store) is valid"
    (let [result (schema/validate-section :memory {:default-store :chroma})]
      (is (:valid? result)))))

;; =============================================================================
;; Memory Section — Invalid Cases
;; =============================================================================

(deftest test-invalid-memory-missing-default-store
  (testing "Missing :default-store fails validation"
    (let [result (schema/validate-section :memory {:routes {:decision :milvus}})]
      (is (not (:valid? result)))
      (is (some? (:humanized result))))))

(deftest test-invalid-memory-wrong-default-store-type
  (testing "String :default-store fails (must be keyword)"
    (let [result (schema/validate-section :memory {:default-store "milvus"})]
      (is (not (:valid? result))))))

(deftest test-invalid-memory-bad-route-target
  (testing "Numeric route target fails"
    (let [result (schema/validate-section :memory {:default-store :milvus
                                                    :routes {:decision 42}})]
      (is (not (:valid? result))))))

(deftest test-invalid-memory-store-missing-addon
  (testing "Store without :addon fails"
    (let [result (schema/validate-section :memory {:default-store :milvus
                                                    :stores {:milvus {:host "localhost"}}})]
      (is (not (:valid? result))))))

;; =============================================================================
;; Full Config Validation
;; =============================================================================

(deftest test-default-config-validates
  (testing "Default config passes full validation"
    (let [result (schema/validate-config merge/default-config)]
      (is (:valid? result) (str "Default config failed validation: " (:humanized result))))))

(deftest test-full-config-with-memory
  (testing "Full config with memory section validates"
    (let [cfg (assoc merge/default-config
                     :memory {:default-store :milvus
                              :routes {:decision :milvus}
                              :stores {:milvus {:addon :hive-milvus}}})
          result (schema/validate-config cfg)]
      (is (:valid? result)))))

(deftest test-full-config-with-bad-memory
  (testing "Full config with invalid memory fails"
    (let [cfg (assoc merge/default-config :memory {:default-store "not-a-keyword"})
          result (schema/validate-config cfg)]
      (is (not (:valid? result))))))

;; =============================================================================
;; validate-memory-config convenience
;; =============================================================================

(deftest test-validate-memory-config-absent
  (testing "Returns nil when :memory absent"
    (is (nil? (schema/validate-memory-config {:project-roots []})))))

(deftest test-validate-memory-config-present-valid
  (testing "Returns valid when :memory is correct"
    (let [result (schema/validate-memory-config
                   {:memory {:default-store :chroma}})]
      (is (:valid? result)))))

(deftest test-validate-memory-config-present-invalid
  (testing "Returns invalid when :memory is malformed"
    (let [result (schema/validate-memory-config
                   {:memory {:routes {:decision :milvus}}})]
      (is (not (:valid? result))))))

;; =============================================================================
;; Roundtrip: load → validate → access
;; =============================================================================

(defn- write-temp-config! [config-map]
  (let [f (java.io.File/createTempFile "hive-schema-test" ".edn")]
    (.deleteOnExit f)
    (spit f (pr-str config-map))
    (.getAbsolutePath f)))

(deftest test-roundtrip-load-validate-access
  (testing "Load config with :memory, validate, access routes"
    (let [user-cfg {:memory {:default-store :milvus
                             :routes {:decision :milvus
                                      :snippet {:primary :milvus :projection :chroma}}
                             :stores {:milvus {:addon :hive-milvus :host "milvus.local" :port 19530}
                                      :chroma {:addon :hive-chroma :host "localhost" :port 8000}}}}
          path (write-temp-config! user-cfg)
          loaded (config/load-global-config! path)]
      ;; Config loaded and merged
      (is (= :milvus (get-in loaded [:memory :default-store])))
      ;; Validation passes
      (is (:valid? (schema/validate-memory-config loaded)))
      ;; Accessor works
      (is (= :milvus (config/get-memory-route :decision)))
      (is (= {:primary :milvus :projection :chroma} (config/get-memory-route :snippet)))
      ;; Fallback to default-store for unknown types
      (is (= :milvus (config/get-memory-route :unknown-type)))
      ;; Store definition accessible
      (is (= :hive-milvus (:addon (config/get-memory-store :milvus))))
      (is (= 19530 (:port (config/get-memory-store :milvus))))
      ;; Generic path accessor
      (is (= :milvus (config/get-in-config [:memory :default-store]))))))

(deftest test-roundtrip-default-generation
  (testing "Default config has valid :memory section"
    (let [path (write-temp-config! {})  ; empty user config → defaults
          loaded (config/load-global-config! path)]
      ;; Default memory config present
      (is (= :chroma (get-in loaded [:memory :default-store])))
      ;; All default routes point to :chroma
      (is (= :chroma (get-in loaded [:memory :routes :decision])))
      (is (= :chroma (get-in loaded [:memory :routes :snippet])))
      ;; Validates
      (is (:valid? (schema/validate-memory-config loaded))))))
