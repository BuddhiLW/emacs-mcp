(ns hive-mcp.addons.di-test
  "Tests for the DI (Dependency Injection) module.

   Covers:
   - Service Registry (CRUD, thread-safety, edge cases)
   - Symbol Resolution (success, failure, batch)
   - IServiceConsumer protocol (safe accessors, dependency checking)
   - Addon Pipeline (nil-railway behavior)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.addons.di :as di]
            [clojure.string]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixture: Clean Service Registry Between Tests
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (di/clear-services!)
    (try (f)
         (finally (di/clear-services!)))))

;; =============================================================================
;; Service Registry Tests
;; =============================================================================

(deftest register-and-get-service-test
  (testing "register and retrieve a service"
    (di/register-service! :test/svc {:conn "fake"})
    (is (= {:conn "fake"} (di/get-service :test/svc))))

  (testing "get-service returns nil for missing key"
    (is (nil? (di/get-service :test/missing))))

  (testing "get-service returns default for missing key"
    (is (= :fallback (di/get-service :test/missing :fallback)))))

(deftest service-registered?-test
  (testing "returns true for registered service"
    (di/register-service! :test/exists :val)
    (is (true? (di/service-registered? :test/exists))))

  (testing "returns false for missing service"
    (is (false? (di/service-registered? :test/nope)))))

(deftest last-write-wins-test
  (testing "re-registering same key replaces value"
    (di/register-service! :test/svc :v1)
    (di/register-service! :test/svc :v2)
    (is (= :v2 (di/get-service :test/svc)))))

(deftest deregister-service-test
  (testing "deregister removes service"
    (di/register-service! :test/bye :val)
    (is (true? (di/service-registered? :test/bye)))
    (di/deregister-service! :test/bye)
    (is (nil? (di/get-service :test/bye)))
    (is (false? (di/service-registered? :test/bye)))))

(deftest list-services-test
  (testing "list-services returns all registered keys"
    (di/register-service! :test/a 1)
    (di/register-service! :test/b 2)
    (let [keys (di/list-services)]
      (is (contains? keys :test/a))
      (is (contains? keys :test/b)))))

(deftest register-services!-batch-test
  (testing "batch register multiple services atomically"
    (di/register-services! {:test/x 10 :test/y 20 :test/z 30})
    (is (= 10 (di/get-service :test/x)))
    (is (= 20 (di/get-service :test/y)))
    (is (= 30 (di/get-service :test/z)))))

(deftest require-service-test
  (testing "returns value when registered"
    (di/register-service! :test/req :val)
    (is (= :val (di/require-service :test/req))))

  (testing "throws ExceptionInfo when missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required service not registered"
                          (di/require-service :test/missing-req))))

  (testing "exception includes available services"
    (di/register-service! :test/avail :x)
    (try
      (di/require-service :test/not-here)
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :test/not-here (:key data)))
          (is (contains? (:available data) :test/avail)))))))

(deftest clear-services-test
  (testing "clear-services! removes all registrations"
    (di/register-service! :test/a 1)
    (di/register-service! :test/b 2)
    (di/clear-services!)
    (is (empty? (di/list-services)))))

(deftest service-with-false-value-test
  (testing "service registered with false is still found"
    (di/register-service! :test/flag false)
    (is (true? (di/service-registered? :test/flag)))
    ;; get-service returns false (not nil)
    ;; require-service uses find so it works with false values
    (is (false? (di/require-service :test/flag)))))

;; =============================================================================
;; Symbol Resolution Tests
;; =============================================================================

(deftest resolve-symbol-test
  (testing "resolves existing Clojure core symbol"
    (is (some? (di/resolve-symbol 'clojure.core/identity)))
    (is (= identity @(di/resolve-symbol 'clojure.core/identity))))

  (testing "returns nil for non-existent namespace"
    (is (nil? (di/resolve-symbol 'nonexistent.namespace.xyz/no-such-fn))))

  (testing "returns nil for non-existent var in existing ns"
    (is (nil? (di/resolve-symbol 'clojure.core/no-such-fn-xyz)))))

(deftest resolve-symbols-batch-test
  (testing "resolves all symbols in map"
    (let [result (di/resolve-symbols {:id  'clojure.core/identity
                                      :inc 'clojure.core/inc})]
      (is (map? result))
      (is (= identity @(:id result)))
      (is (= inc @(:inc result)))))

  (testing "returns nil if any symbol fails"
    (is (nil? (di/resolve-symbols {:id  'clojure.core/identity
                                   :bad 'nonexistent.ns.xyz/nope}))))

  (testing "returns empty map for empty input"
    (is (= {} (di/resolve-symbols {})))))

;; =============================================================================
;; IServiceConsumer Protocol Tests
;; =============================================================================

(deftest safe-required-services-test
  (testing "returns #{} for non-implementing objects"
    (is (= #{} (di/safe-required-services (Object.)))))

  (testing "returns #{} for nil"
    (is (= #{} (di/safe-required-services nil))))

  (testing "returns declared services for implementing object"
    (let [consumer (reify di/IServiceConsumer
                     (required-services [_] #{:hive/kg-store :hive/editor})
                     (optional-services [_] #{}))]
      (is (= #{:hive/kg-store :hive/editor}
             (di/safe-required-services consumer))))))

(deftest safe-optional-services-test
  (testing "returns #{} for non-implementing objects"
    (is (= #{} (di/safe-optional-services (Object.)))))

  (testing "returns declared services for implementing object"
    (let [consumer (reify di/IServiceConsumer
                     (required-services [_] #{})
                     (optional-services [_] #{:hive/editor}))]
      (is (= #{:hive/editor}
             (di/safe-optional-services consumer))))))

(deftest check-service-dependencies-test
  (testing "satisfied when no required services"
    (let [result (di/check-service-dependencies (Object.))]
      (is (true? (:satisfied? result)))
      (is (empty? (:missing result)))))

  (testing "satisfied when required services are registered"
    (di/register-service! :hive/kg-store :fake-store)
    (let [consumer (reify di/IServiceConsumer
                     (required-services [_] #{:hive/kg-store})
                     (optional-services [_] #{}))
          result (di/check-service-dependencies consumer)]
      (is (true? (:satisfied? result)))
      (is (empty? (:missing result)))
      (is (= #{:hive/kg-store} (:available result)))))

  (testing "not satisfied when required service is missing"
    (let [consumer (reify di/IServiceConsumer
                     (required-services [_] #{:hive/editor :hive/nonexistent})
                     (optional-services [_] #{}))
          result (di/check-service-dependencies consumer)]
      (is (false? (:satisfied? result)))
      (is (contains? (:missing result) :hive/nonexistent)))))

;; =============================================================================
;; Addon Pipeline Tests
;; =============================================================================
;;
;; These tests verify nil-railway behavior. Full integration requires
;; hive-mcp.addons.core on the classpath — if not available, the pipeline
;; correctly returns nil (core deps can't resolve).

(deftest pipeline-nil-on-non-iaddon-test
  (testing "pipeline returns nil when addon doesn't satisfy IAddon protocol"
    ;; A non-IAddon object passes dep resolution but fails at register!
    ;; (which has a :pre assertion). The pipeline catches the error and
    ;; returns nil gracefully (nil-railway).
    (let [fake-addon (reify Object)
          result (di/run-addon-pipeline! fake-addon {})]
      (is (nil? result)))))

(deftest pipeline-with-store-atom-not-set-on-failure-test
  (testing "store-atom stays nil when pipeline fails"
    ;; Pipeline fails because (reify Object) doesn't satisfy IAddon
    (let [store (atom nil)]
      (di/run-addon-pipeline! (reify Object) {:store-atom store})
      (is (nil? @store)))))

(deftest pipeline-with-extra-deps-still-fails-at-register-test
  (testing "extra-deps resolve but pipeline still fails at register for non-IAddon"
    (let [result (di/run-addon-pipeline!
                  (reify Object)
                  {:extra-deps {:my-fn 'clojure.core/identity}})]
      ;; Extra deps resolved fine, but register! fails on non-IAddon
      (is (nil? result)))))

(deftest pipeline-fails-on-bad-extra-deps-test
  (testing "pipeline returns nil when extra-deps can't resolve"
    (let [result (di/run-addon-pipeline!
                  (reify Object)
                  {:extra-deps {:bad 'nonexistent.ns.xyz/nope}})]
      ;; Should definitely be nil — bad extra dep
      (is (nil? result)))))

(deftest init-as-addon!-returns-empty-on-nil-make-fn
  (testing "init-as-addon! returns {:total 0} when make-fn returns nil"
    (let [result (di/init-as-addon! "test-addon" (constantly nil) {})]
      (is (= {:registered [] :total 0} result)))))

(deftest init-as-addon!-returns-empty-on-pipeline-failure
  (testing "init-as-addon! returns {:total 0} when pipeline fails"
    (let [result (di/init-as-addon!
                  "test-addon"
                  #(reify Object)
                  {:extra-deps {:bad 'nonexistent.ns.xyz/nope}})]
      (is (= {:registered [] :total 0} result)))))

;; =============================================================================
;; Thread Safety Smoke Test
;; =============================================================================

(deftest concurrent-service-registration-test
  (testing "concurrent registrations don't corrupt the registry"
    (let [n 100
          futures (doall
                   (for [i (range n)]
                     (future (di/register-service!
                              (keyword "test" (str "concurrent-" i))
                              i))))]
      (doseq [f futures] @f)
      (is (= n (count (filter #(clojure.string/starts-with?
                                (name %)
                                "concurrent-")
                               (map name (di/list-services)))))))))
