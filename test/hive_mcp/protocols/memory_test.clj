(ns hive-mcp.protocols.memory-test
  "TDD tests for multi-store registry in hive-mcp.protocols.memory."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.protocols.memory :as proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord StubStore [id]
  proto/IMemoryStore
  (connect! [_ _] nil)
  (disconnect! [_] nil)
  (connected? [_] true)
  (health-check [_] {:healthy? true})
  (add-entry! [_ e] e)
  (get-entry [_ _] nil)
  (update-entry! [_ _ _] nil)
  (delete-entry! [_ _] nil)
  (query-entries [_ _] [])
  (search-similar [_ _ _] [])
  (supports-semantic-search? [_] false)
  (cleanup-expired! [_] {:count 0 :deleted-ids []})
  (entries-expiring-soon [_ _ _] [])
  (find-duplicate [_ _ _ _] nil)
  (store-status [_] {:stub true})
  (reset-store! [_] true))

(defn- reset-registry-fixture [f]
  (proto/reset-registry!)
  (try (f) (finally (proto/reset-registry!))))

(use-fixtures :each reset-registry-fixture)

(deftest registry-isolation-test
  (testing "two stores under different keys retrievable independently"
    (let [a (->StubStore :a)
          b (->StubStore :b)]
      (proto/register-store! :a a)
      (proto/register-store! :b b)
      (is (= :a (:id (proto/get-store :a))))
      (is (= :b (:id (proto/get-store :b)))))))

(deftest default-arity-test
  (testing "(get-store) returns :default entry"
    (let [s (->StubStore :d)]
      (proto/register-store! :default s)
      (is (= :d (:id (proto/get-store)))))))

(deftest missing-default-throws-test
  (testing "(get-store) with empty registry throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo (proto/get-store)))
    (try (proto/get-store)
         (catch clojure.lang.ExceptionInfo e
           (is (contains? (ex-data e) :registry-keys))))))

(deftest missing-key-throws-test
  (testing "(get-store :nonexistent) throws with key + available in ex-data"
    (proto/register-store! :a (->StubStore :a))
    (is (thrown? clojure.lang.ExceptionInfo (proto/get-store :nonexistent)))
    (try (proto/get-store :nonexistent)
         (catch clojure.lang.ExceptionInfo e
           (let [d (ex-data e)]
             (is (= :nonexistent (:store-key d)))
             (is (some #{:a} (:available d))))))))

(deftest unregister-isolation-test
  (testing "unregister-store! removes only targeted key"
    (proto/register-store! :a (->StubStore :a))
    (proto/register-store! :b (->StubStore :b))
    (proto/unregister-store! :a)
    (is (thrown? clojure.lang.ExceptionInfo (proto/get-store :a)))
    (is (= :b (:id (proto/get-store :b))))))

(deftest set-store-backward-compat-test
  (testing "set-store! routes to :default for legacy callers"
    (let [s (->StubStore :legacy)]
      (proto/set-store! s)
      (is (= :legacy (:id (proto/get-store))))
      (is (= :legacy (:id (proto/get-store :default)))))))
