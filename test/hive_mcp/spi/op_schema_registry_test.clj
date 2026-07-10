(ns hive-mcp.spi.op-schema-registry-test
  "Seam test for the addon op-schema router (MALLI-P3). Proves addon-contributed
   op-schema bundles register INTO the shared hive-spi core-op registry by owner
   and tear down per-owner without clobbering another addon's schemas."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.spi.op-schema-registry :as opreg]
            [hive-spi.schema.registry :as reg]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; Test-only keys — namespaced to never collide with real op-schemas.
(def ^:private bundle-a
  {:test.op-schema/alpha [:map {:closed false} [:x {:optional true} :int]]})

(def ^:private bundle-b
  {:test.op-schema/beta  [:map {:closed false} [:y {:optional true} :string]]})

(defn- clean! []
  (opreg/reset-for-test!)
  (reg/deregister-all! (concat (keys bundle-a) (keys bundle-b))))

(use-fixtures :each (fn [t] (clean!) (t) (clean!)))

(deftest register-by-key-routes-into-hive-spi
  (testing "a bundle registers into the shared hive-spi registry and is validatable"
    (let [ks (opreg/register-by-key! :addon-a :op-schema/a bundle-a)]
      (is (= [:test.op-schema/alpha] ks) "returns the registered keys")
      (is (contains? (reg/registered) :test.op-schema/alpha)
          "schema is present in the hive-spi core-op registry")
      (is (reg/validate :test.op-schema/alpha {:x 1})
          "the registered schema resolves + validates through the registry")
      (is (= #{:test.op-schema/alpha} (get (opreg/owned) :addon-a))
          "ownership is tracked under the addon id"))))

(deftest deregister-by-owner-is-scoped
  (testing "two owners register; deregistering one leaves the other intact"
    (opreg/register-by-key! :addon-a :op-schema/a bundle-a)
    (opreg/register-by-key! :addon-b :op-schema/b bundle-b)
    (is (contains? (reg/registered) :test.op-schema/alpha))
    (is (contains? (reg/registered) :test.op-schema/beta))
    (let [removed (opreg/deregister-by-owner! :addon-a)]
      (is (= [:test.op-schema/alpha] removed))
      (is (not (contains? (reg/registered) :test.op-schema/alpha))
          "addon-a's schema is gone")
      (is (contains? (reg/registered) :test.op-schema/beta)
          "addon-b's schema is untouched (no cross-owner clobber)")
      (is (nil? (get (opreg/owned) :addon-a)) "ownership record cleared"))))

(deftest deregister-by-owner-is-idempotent
  (testing "deregistering an unknown / already-cleared owner is a no-op"
    (is (= [] (opreg/deregister-by-owner! :never-registered)))
    (opreg/register-by-key! :addon-a :op-schema/a bundle-a)
    (opreg/deregister-by-owner! :addon-a)
    (is (= [] (opreg/deregister-by-owner! :addon-a)) "second teardown is empty")))

(deftest malformed-bundle-is-skipped
  (testing "a non-map hook value logs + returns nil, registers nothing"
    (is (nil? (opreg/register-by-key! :addon-a :op-schema/a [:not :a :map])))
    (is (nil? (get (opreg/owned) :addon-a)) "no ownership recorded for a bad bundle")))
